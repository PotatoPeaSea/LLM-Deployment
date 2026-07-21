package com.qcs.geniechat

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The conversation, on top of one long-lived Genie dialog.
 *
 * This is where the app diverges most from scripts/chatbot/agent.py. That
 * version re-renders the ENTIRE transcript every turn because genie-t2t-run is
 * one-shot and keeps no state, so TTFT grows with history length. Here the
 * dialog's KV cache survives between queries, so a turn only sends the new
 * user message and the assistant header -- prefill cost is constant per turn.
 *
 * The history list is still kept, for two reasons: the UI needs it, and it is
 * what lets us rebuild the conversation after a context overflow (see [ask]).
 */
class ChatEngine(private val bundleDir: File, private val nativeLibDir: String) {

    companion object {
        private const val TAG = "ChatEngine"

        // Kept short on purpose. Measured on this exact model (Llama-3.2-1B, w4):
        // instruction-following degrades sharply as the system prompt grows --
        // see docs/CHATBOT.md "Tool calling".
        const val SYSTEM_PROMPT =
            "You are a helpful assistant running entirely on this device's NPU. " +
                "Answer in one or two short, natural sentences."

        // Headroom left for the reply when deciding whether the next turn fits.
        private const val RESERVE_FOR_REPLY = 512

        fun create(context: Context, modelId: String = ModelStore.DEFAULT_MODEL_ID): ChatEngine? {
            val bundle = ModelStore.findBundle(context, modelId) ?: return null
            return ChatEngine(bundle, context.applicationInfo.nativeLibraryDir)
        }
    }

    val contextLength: Int = ModelStore.contextLength(bundleDir)
    val history: MutableList<Message> = mutableListOf()

    private var handle: Long = 0L
    private val system = Message(Role.SYSTEM, SYSTEM_PROMPT)

    /** Tokens Genie reports it is holding. -1 until the first query completes. */
    private var occupancy: Int = 0

    /** True once the current dialog has been primed with BOS + system prompt. */
    private var primed = false

    val isLoaded: Boolean get() = handle != 0L

    /** Loads the model onto the NPU. Blocking, tens of seconds. */
    @Synchronized
    fun load() {
        if (handle != 0L) return
        val json = ModelStore.buildConfigJson(bundleDir)
        handle = GenieBridge.nativeCreate(json, nativeLibDir)
        Log.i(TAG, "loaded ${bundleDir.name} (context $contextLength)")
    }

    /**
     * Run one user turn. [sink] receives the reply as it streams; the full reply
     * is returned. Blocking -- call from a worker thread.
     */
    fun ask(userText: String, sink: TokenSink): String {
        check(handle != 0L) { "Model is not loaded" }
        val userMessage = Message(Role.USER, userText)

        // Does this turn still fit? Genie's occupancy is authoritative for what
        // is already in the cache; the estimate covers only the new text.
        val incoming = Prompt.messageTokens(userMessage) +
            Prompt.estimateTokens(Prompt.GENERATION_HEADER)
        val query = if (!primed || occupancy + incoming + RESERVE_FOR_REPLY > contextLength) {
            rebuildFor(userMessage)
        } else {
            Prompt.renderTurn(userMessage)
        }

        history.add(userMessage)
        val reply = (GenieBridge.nativeQuery(handle, query, sink) ?: "").trim()
        history.add(Message(Role.ASSISTANT, reply))

        occupancy = GenieBridge.nativeContextOccupancy(handle).takeIf { it >= 0 }
            ?: (occupancy + incoming + Prompt.estimateTokens(reply))
        Log.i(TAG, "context occupancy $occupancy/$contextLength")
        return reply
    }

    /**
     * Drop the KV cache and return a full prompt containing the system message,
     * as much recent history as fits, and [next].
     *
     * This costs one full prefill, which is the price of a conversation that
     * outgrows its context window -- the alternative is refusing to continue.
     */
    private fun rebuildFor(next: Message): String {
        if (primed) {
            Log.i(TAG, "context full ($occupancy/$contextLength) -- trimming and re-priming")
            GenieBridge.nativeReset(handle)
        }
        val messages = Prompt.fitToContext(
            system, history + next, contextLength, RESERVE_FOR_REPLY,
        )
        primed = true
        occupancy = 0
        return Prompt.render(messages)
    }

    /** Forget the conversation, on the device and in the UI. */
    fun reset() {
        if (handle == 0L) return
        GenieBridge.nativeReset(handle)
        history.clear()
        primed = false
        occupancy = 0
    }

    /** Stop the in-flight generation. Safe to call from the UI thread. */
    fun abort() {
        if (handle != 0L) GenieBridge.nativeAbort(handle)
    }

    @Synchronized
    fun close() {
        if (handle == 0L) return
        GenieBridge.nativeFree(handle)
        handle = 0L
        primed = false
    }
}
