package com.geniechatrn.genie

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the one Genie dialog that fits in DSP memory at a time.
 *
 * Two facts drive this whole class:
 *
 * 1. Only ONE model can be resident. Switching models is a real unload/load,
 *    not a pointer swap, which is why a chat is pinned to the model it started
 *    with -- see [prepare].
 * 2. The dialog's KV cache SURVIVES between queries. That is the entire reason
 *    the app isn't just genie-t2t-run in a wrapper: a turn only has to prefill
 *    the new message. But the cache holds ONE conversation, so switching chats
 *    means resetting it and re-priming from that chat's history.
 *
 * The chat history itself lives in JS (it is persisted there); this class is
 * told what it needs per call. [primedChatId] is the only conversation state
 * kept here, and it exists purely to decide "incremental turn or full prime?".
 */
class ChatEngine(private val context: Context) {

    companion object {
        private const val TAG = "ChatEngine"

        /**
         * Space kept free for the reply when deciding whether the next turn fits,
         * and the hard ceiling handed to Genie for that reply.
         *
         * This used to be a flat 256, which was written for Llama's 4096 window
         * (6% of it) and silently cost Qwen at ctx512 HALF its window. Scaling by
         * context length gives Qwen 128 and roughly doubles its usable history.
         *
         * Reasoning genuinely needs more room, because the <think> block and the
         * answer both land in the KV cache -- so the reserve depends on the
         * toggles, not just the model.
         */
        fun reserveForReply(contextLength: Int, brevity: Boolean, thinking: Boolean): Int {
            val fraction = when {
                thinking -> 2      // half the window; reasoning is the expensive case
                brevity -> 6       // a couple of sentences needs very little
                else -> 4
            }
            return (contextLength / fraction).coerceIn(96, 512)
        }

        /** Never let a single message eat so much that no history can survive. */
        private const val MAX_SINGLE_MESSAGE_FRACTION = 0.6

        /** A reply not ending in one of these was cut short rather than finished. */
        private const val TERMINAL_PUNCTUATION = ".!?\"')]}…”’"

        /**
         * System message plus as much recent history as fits, dropping from the
         * OLDEST end and only at turn boundaries, so the model never sees an
         * assistant reply whose user message has been evicted.
         *
         * Pure on purpose -- this is the arithmetic that decides whether a prompt
         * fits in the window, and it is far easier to get wrong than to test.
         * See ChatEngineContextTest.
         */
        fun fitToContext(
            template: ChatTemplate,
            system: Message,
            messages: List<Message>,
            contextLength: Int,
            reserve: Int,
        ): List<Message> {
            val budget = contextLength - reserve - template.messageTokens(system) - 16
            require(budget > 0) {
                "The system prompt alone exceeds the $contextLength-token context budget."
            }

            // The newest message is never dropped -- but it IS truncated if it
            // alone would blow the budget. Keeping it whole let a long paste build
            // a prompt bigger than the window; Genie then returns
            // WARNING_CONTEXT_EXCEEDED and the reply is silently truncated.
            val newest = messages.last().let { msg ->
                val cap = (budget * MAX_SINGLE_MESSAGE_FRACTION).toInt()
                if (template.messageTokens(msg) <= cap) msg
                else msg.copy(content = truncateToTokens(msg.content, cap))
            }

            val kept = ArrayDeque<Message>()
            kept.addFirst(newest)
            var used = template.messageTokens(newest)
            for (msg in messages.dropLast(1).asReversed()) {
                val cost = template.messageTokens(msg)
                if (used + cost > budget) break
                kept.addFirst(msg)
                used += cost
            }
            while (kept.size > 1 && kept.first().role != Role.USER) kept.removeFirst()
            return listOf(system) + kept
        }

        /**
         * Keep the head and the tail, drop the middle. For a long paste the
         * question is usually at one end, so cutting the middle preserves more
         * intent than a plain head-truncation.
         */
        fun truncateToTokens(text: String, maxTokens: Int): String {
            val marker = "\n…[trimmed to fit the context window]…\n"
            val maxChars = (maxTokens * 3.2).toInt() - marker.length
            if (maxChars <= 0 || text.length <= maxChars) return text.take(maxOf(maxChars, 0))
            val head = maxChars * 2 / 3
            return text.take(head) + marker + text.takeLast(maxChars - head)
        }
    }

    private var handle: Long = 0L
    private var loadedModelId: String? = null
    private var primedChatId: String? = null
    private var occupancy = 0

    var contextLength: Int = 0
        private set

    /** Tokens Genie reports it is holding, for the UI's context readout. */
    val contextUsed: Int get() = occupancy

    /** True when the last reply stopped at the token ceiling, not at EOS. */
    var lastReplyWasCapped: Boolean = false
        private set

    val currentModelId: String? get() = loadedModelId

    /**
     * Make [modelId] resident, unloading whatever else was. Blocking, and the
     * expensive part of switching models -- ~1s for Llama's 1.3GB, longer for
     * Qwen's 3GB, since the context binaries are mmap'd rather than copied.
     */
    @Synchronized
    fun ensureModel(modelId: String, onStaging: (Long, Long) -> Unit = { _, _ -> }) {
        if (loadedModelId == modelId && handle != 0L) return
        close()

        // First run for this model copies it out of the pushed (FUSE) dir into
        // internal storage -- several GB, but only once. See ModelStore.stage.
        val bundle = ModelStore.stage(context, modelId, onStaging)
        val t0 = System.currentTimeMillis()
        handle = GenieBridge.nativeCreate(
            ModelStore.buildConfigJson(bundle),
            context.applicationInfo.nativeLibraryDir,
        )
        loadedModelId = modelId
        contextLength = ModelStore.contextLength(bundle)
        primedChatId = null
        occupancy = 0
        Log.i(TAG, "loaded $modelId (context $contextLength) in ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * Run one user turn and stream the reply into [sink]; returns it in full.
     * Blocking -- call from a worker thread.
     *
     * [history] is the conversation BEFORE this turn, oldest first, and is only
     * read when the cache has to be rebuilt.
     */
    fun generate(
        chatId: String,
        modelId: String,
        history: List<Message>,
        userText: String,
        brevity: Boolean,
        thinking: Boolean,
        sink: TokenSink,
    ): String {
        ensureModel(modelId)
        val spec = ModelStore.spec(modelId)
        val template = spec.template
        val options = RenderOptions(thinking = thinking && spec.supportsReasoning)
        val userMessage = Message(Role.USER, userText)
        val system = Message(
            Role.SYSTEM,
            if (brevity) spec.systemPrompt + spec.brevityClause else spec.systemPrompt,
        )

        val reserve = reserveForReply(contextLength, brevity, options.thinking)
        val incoming = template.messageTokens(userMessage) + 32
        val mustRebuild = chatId != primedChatId ||
            occupancy + incoming + reserve > contextLength

        val query = if (mustRebuild) {
            if (primedChatId != null) GenieBridge.nativeReset(handle)
            val fitted = fitToContext(template, system, history + userMessage, contextLength, reserve)
            primedChatId = chatId
            occupancy = 0
            Log.i(TAG, "priming chat $chatId with ${fitted.size} messages")
            template.renderFull(fitted, options)
        } else {
            template.renderTurn(userMessage, options)
        }

        // Two guards against a generation that never stops. Genie enforces the
        // cap itself; without it, a model that fails to emit EOS -- which these
        // do at small context lengths -- runs until the window is full, and at
        // ctx512 that wedges the conversation in a single turn.
        val promptTokens = if (mustRebuild) template.estimateTokens(query) else occupancy + incoming
        val maxNew = (contextLength - promptTokens - 8).coerceIn(32, reserve)
        GenieBridge.nativeSetMaxTokens(handle, maxNew)

        val reply = (GenieBridge.nativeQuery(handle, query, sink) ?: "").trim()

        // Genie doesn't report "I hit the cap". Comparing occupancy against
        // promptTokens doesn't work either: occupancy is Genie's exact count,
        // but promptTokens is the chars/3.2 OVER-estimate, so their difference
        // understates what was generated and the check missed real cut-offs
        // (measured: generated~108 against maxNew=128 for a reply that had
        // visibly stopped mid-list).
        //
        // So judge the artifact the user actually sees: a reply that ends
        // without terminal punctuation was cut short, whatever cut it.
        val generated = (GenieBridge.nativeContextOccupancy(handle) - promptTokens).coerceAtLeast(0)
        lastReplyWasCapped = reply.isNotEmpty() && reply.last() !in TERMINAL_PUNCTUATION

        occupancy = GenieBridge.nativeContextOccupancy(handle)
            .takeIf { it >= 0 } ?: (occupancy + incoming + template.estimateTokens(reply))
        Log.i(TAG, "occupancy $occupancy/$contextLength (prompt~$promptTokens generated~$generated maxNew=$maxNew reserve=$reserve capped=$lastReplyWasCapped)")
        return reply
    }

    /** Forget the conversation currently in the KV cache. */
    fun resetConversation() {
        if (handle == 0L) return
        GenieBridge.nativeReset(handle)
        primedChatId = null
        occupancy = 0
    }

    /** Stop the in-flight generation. Safe to call from another thread. */
    fun abort() {
        if (handle != 0L) GenieBridge.nativeAbort(handle)
    }

    @Synchronized
    fun close() {
        if (handle == 0L) return
        GenieBridge.nativeFree(handle)
        handle = 0L
        loadedModelId = null
        primedChatId = null
        occupancy = 0
    }
}
