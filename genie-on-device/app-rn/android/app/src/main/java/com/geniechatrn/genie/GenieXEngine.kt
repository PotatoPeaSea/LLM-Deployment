package com.geniechatrn.genie

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import kotlinx.coroutines.runBlocking

/**
 * The GGUF half of the app: Qwen3.5-2B on the NPU through GenieX's llama.cpp
 * runtime.
 *
 * Two things make this genuinely different from [ChatEngine], not just a
 * second copy of it:
 *
 * 1. The SDK owns the prompt. `applyChatTemplate` renders the model's real
 *    template out of the GGUF, so [ChatTemplate] is unused here. That matters:
 *    feeding this model raw text produces fluent-looking garbage ("layout
 *    layout layout..."), which is exactly what happens if you skip this step.
 * 2. The whole conversation is re-rendered every turn, and that is cheap.
 *    llama.cpp prefix-matches the new prompt against the KV cache and only
 *    prefills the tail, so passing the full transcript costs the same as an
 *    incremental turn while being far harder to get wrong. The cache is reset
 *    only when the chat changes.
 *
 * The 164K window means the context arithmetic ChatEngine needs (trimming,
 * re-priming, reserve-for-reply) is simply not required: a conversation would
 * have to run to roughly half a million characters before it mattered.
 *
 * **Text only, deliberately (see HANDOFF-qwen-text-164k.md).** GenieX ships a
 * second, VLM-capable API (`VlmWrapper`/`LlamaVlm::generate`) for image
 * attachment. That path's `generate()` SIGSEGVs unconditionally on this
 * device/plugin build -- fault addr 0x0, right after tokenization, on every
 * input tried (prompt, nCtx, compute unit, AAR version, KV-cache reset timing,
 * sampler config -- all ruled out). The plain text `LlmWrapper`/`Llm::generate`
 * used here does not share that bug and streams normally. So images are not
 * supported right now; wiring them back in means routing through VlmWrapper
 * again, which reintroduces the crash.
 */
class GenieXEngine(private val context: Context) {

    companion object {
        private const val TAG = "GenieXEngine"

        /**
         * How many times the model may call tools before it has to answer.
         *
         * Four covers "look up two things and combine them" while bounding the
         * worst case: every iteration is a full generation, so a runaway loop
         * costs the user tens of seconds of staring at a spinner.
         */
        private const val MAX_TOOL_ITERATIONS = 4

        /** Reply ceiling. The window is huge; the reply still should not be. */
        private const val MAX_NEW_TOKENS = 1024

        /**
         * Create attempts when loading a model. The first load after switching
         * away from the QNN runtime can lose a race for the DSP: the QNN model's
         * HTP session is still tearing down (async) when llama.cpp tries to
         * create its own HTP0 device, and the build fails with `-100201`
         * (`load_all_data: device HTP0 does not support async...`). GenieModule
         * already pauses after the unload; this is the backstop for when that
         * pause was not quite enough. A fresh load succeeds on the first try and
         * never sleeps.
         */
        private const val CREATE_ATTEMPTS = 5

        /**
         * Extra DSP-settle between failed create attempts (see
         * [CREATE_ATTEMPTS]). Was 900ms/3 attempts; reproduced via
         * scripts/genie_cli.py that all 3 attempts (2.7s of settling) still
         * failed identically ("HTP0 buffer mapping failed", "0 MiB free")
         * right after a QNN->GenieX switch. Bumped both the per-attempt
         * settle and the attempt count for more total headroom.
         */
        private const val CREATE_SETTLE_MS = 1500L
    }

    private var wrapper: LlmWrapper? = null
    private var loadedModelId: String? = null
    private var primedChatId: String? = null
    private var sdkReady = false

    /**
     * Bring the SDK up, and wait for it.
     *
     * `init` reports through a callback rather than returning, and it is what
     * registers the llama_cpp plugin -- building a wrapper before it lands
     * fails with an unhelpful "no such plugin". Since every caller here is
     * already on a blocking worker thread, waiting is simpler than threading a
     * readiness state through the engine.
     */
    private fun initSdk() {
        if (sdkReady) return
        val latch = java.util.concurrent.CountDownLatch(1)
        var failure: String? = null
        GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(message: String) {
                failure = message
                latch.countDown()
            }
        })
        check(latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            "GenieX SDK init timed out"
        }
        failure?.let { error("GenieX SDK init failed: $it") }
        sdkReady = true
    }

    var contextLength: Int = 0
        private set

    /** Tools actually used in the last turn, for the UI's status line. */
    var lastToolsUsed: List<String> = emptyList()
        private set

    val currentModelId: String? get() = loadedModelId

    /**
     * Make [modelId] resident. Blocking and slow the first time -- the GGUF is
     * copied out of external storage before anything can map it.
     */
    @Synchronized
    fun ensureModel(modelId: String, onStaging: (Long, Long) -> Unit = { _, _ -> }) {
        if (loadedModelId == modelId && wrapper != null) return
        close()

        val spec = ModelStore.spec(modelId)
        val bundle = ModelStore.stage(context, modelId, onStaging)
        val gguf = java.io.File(bundle, requireNotNull(spec.ggufFile) { "$modelId has no ggufFile" })
        require(gguf.isFile) { "Missing ${gguf.name} in ${bundle.absolutePath}" }

        initSdk()

        val t0 = System.currentTimeMillis()
        contextLength = spec.declaredContextLength

        val input = LlmCreateInput(
            spec.displayName,
            gguf.absolutePath,
            "", // tokenizer_path -- embedded in the GGUF, nothing separate to point at
            ModelConfig().apply { nCtx = contextLength },
            "llama_cpp",
            spec.computeUnit,
        )

        wrapper = createWithRetry(input)
        loadedModelId = modelId
        primedChatId = null
        Log.i(TAG, "loaded $modelId on ${spec.computeUnit} (ctx $contextLength) " +
            "in ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * Build the wrapper, retrying the transient DSP-contention failure that can
     * follow a runtime switch (see [CREATE_ATTEMPTS]). The first attempt runs
     * immediately; only a failure pays the settle, so a fresh load is unaffected.
     * A `-100201` here means the outgoing runtime's HTP session had not finished
     * releasing -- closing anything half-built and pausing lets the DSP catch up.
     */
    private fun createWithRetry(input: LlmCreateInput): LlmWrapper {
        var lastError: Throwable? = null
        for (attempt in 1..CREATE_ATTEMPTS) {
            if (attempt > 1) {
                runCatching { wrapper?.close() }
                wrapper = null
                System.gc()
                Log.w(TAG, "LLM create failed (attempt ${attempt - 1}/$CREATE_ATTEMPTS), " +
                    "settling ${CREATE_SETTLE_MS}ms then retrying", lastError)
                try {
                    Thread.sleep(CREATE_SETTLE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val result = runBlocking { LlmWrapper.builder().llmCreateInput(input).build() }
            result.getOrNull()?.let { return it }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("LLM create failed with no error")
    }

    /**
     * Run one user turn, resolving any tool calls, and stream the visible reply
     * into [sink]. Blocking; call from a worker thread.
     *
     * [onStatus] reports what the model is doing between generations ("Searching
     * the web…"). Without it a tool-using turn looks frozen for ten seconds,
     * because nothing is streamed while a tool runs.
     *
     * Images are not supported -- see the class doc -- so [imagePaths] is
     * always declined with an explanatory note rather than passed through.
     */
    fun generate(
        chatId: String,
        modelId: String,
        history: List<Message>,
        userText: String,
        imagePaths: List<String>,
        brevity: Boolean,
        thinking: Boolean,
        sink: TokenSink,
        onStatus: (String) -> Unit = {},
    ): String {
        ensureModel(modelId)
        val active = requireNotNull(wrapper) { "model not loaded" }
        val spec = ModelStore.spec(modelId)

        if (imagePaths.isNotEmpty()) {
            val note = "Image attachments aren't supported on this model right now " +
                "-- I can still answer text questions."
            sink.onToken(note)
            lastToolsUsed = emptyList()
            return note
        }

        // The KV cache holds one conversation. Switching chats invalidates it;
        // staying in the same chat lets prefix matching do its job.
        if (chatId != primedChatId) {
            runBlocking { active.reset() }
            primedChatId = chatId
            Log.i(TAG, "reset KV cache for chat $chatId")
        }

        val system = spec.systemPrompt + if (brevity) spec.brevityClause else ""
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", system))
        if (history.isEmpty()) {
            // Work around a native crash in this GGUF's embedded Jinja chat
            // template: applyChatTemplate's renderer aborts the whole process
            // (uncaught std::invalid_argument, "Unexpected message role.") for
            // some -- not all -- user text when this is the FIRST real
            // generation run against a freshly-reset session (right after
            // ensureModel/active.reset()). The same triggering text (e.g.
            // "Write an essay about the telephone") renders fine once one
            // real generation has already completed on this session, and a
            // synthetic message merely appended to the array WITHOUT actually
            // running a generation does NOT help (tried first, still
            // crashed) -- so whatever native state this depends on is set by
            // actually driving one real generateStreamFlow call, not by the
            // shape of the `messages` array. This priming reply is discarded,
            // never shown to the user, never sent to [sink]. See
            // HANDOFF-reasoning-tools-fixes.md and scripts/genie_cli.py,
            // which is how this was isolated -- confirmed via repeated
            // on-device reproduction; root cause in the closed-source SDK not
            // found (extracted and read the template itself, the bug is not
            // in the Jinja logic, which never inspects message content to
            // decide role).
            val primingReply = runOnce(
                active,
                arrayOf(ChatMessage("system", system), ChatMessage("user", "Hi")),
                null,
                false,
                TokenSink {},
                alreadyEmitted = "",
            )
            messages.add(ChatMessage("user", "Hi"))
            messages.add(
                ChatMessage(
                    "assistant",
                    Tools.stripCalls(primingReply).trim().ifBlank { "Hello! How can I help you today?" },
                ),
            )
        }
        for (message in history) {
            messages.add(ChatMessage(message.role.wire, message.content))
        }
        messages.add(ChatMessage("user", userText))

        val toolsJson = if (spec.supportsTools) Tools.schemaJson() else null
        val used = mutableListOf<String>()
        var visible = ""

        for (iteration in 0 until MAX_TOOL_ITERATIONS) {
            val isLast = iteration == MAX_TOOL_ITERATIONS - 1
            // On the final permitted pass, drop the tools so the model has no
            // choice but to answer with what it already gathered.
            val reply = runOnce(
                active,
                messages.toTypedArray(),
                if (isLast) null else toolsJson,
                thinking && spec.supportsReasoning,
                sink,
                alreadyEmitted = visible,
            )

            if (!Tools.wantsTool(reply)) {
                lastToolsUsed = used
                return Tools.stripCalls(reply).trim()
            }

            val calls = Tools.parseCalls(reply)
            if (calls.isEmpty()) {
                // It opened a tool call but wrote nothing parseable. Treat the
                // prose as the answer rather than burning another generation.
                lastToolsUsed = used
                return Tools.stripCalls(reply).trim()
            }

            // Keep the model's own tool-call turn in the transcript: Qwen's
            // template pairs each tool result with the call that asked for it,
            // and omitting it makes the results look unmotivated.
            messages.add(ChatMessage("assistant", reply))
            visible = Tools.stripCalls(reply).trim()

            for (call in calls) {
                onStatus(statusFor(call.name))
                val result = execute(call)
                used.add(call.name)
                Log.i(TAG, "tool ${call.name}(${call.arguments}) -> ${result.take(120)}")
                // The GGUF's chat template dispatches on role=="tool" using
                // loop.previtem/loop.nextitem -- Jinja2 loop extensions the
                // llama.cpp minja engine doesn't fully support, which aborts
                // the whole process (uncaught C++ exception, not catchable
                // from Kotlin). The SAME template's own multi-step-tool scan
                // (search "multi_step_tool" in the template) expects tool
                // results as a "user" turn wrapped in <tool_response>, so use
                // that form instead -- it only touches the plain user branch.
                messages.add(ChatMessage("user", "<tool_response>\n$result\n</tool_response>"))
            }
        }

        lastToolsUsed = used
        return visible.ifBlank { "I wasn't able to finish looking that up." }
    }

    /** One generation: render the prompt, stream it, return the raw reply. */
    private fun runOnce(
        active: LlmWrapper,
        messages: Array<ChatMessage>,
        toolsJson: String?,
        thinking: Boolean,
        sink: TokenSink,
        alreadyEmitted: String,
    ): String = runBlocking {
        val prompt = active.applyChatTemplate(messages, toolsJson, thinking, true)
            .getOrThrow()
            .formattedText

        val config = GenerationConfig().apply { maxTokens = MAX_NEW_TOKENS }

        val raw = StringBuilder()
        var emitted = alreadyEmitted
        active.generateStreamFlow(prompt, config).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> {
                    raw.append(result.text)
                    // Stream prose as it arrives but never the tool-call XML.
                    // Recomputing the stripped text each token is cheap next to
                    // generating one, and it means a reply that mixes a
                    // sentence with a call shows the sentence immediately.
                    val clean = Tools.stripCalls(raw.toString())
                    if (clean.length > emitted.length) {
                        sink.onToken(clean.substring(emitted.length))
                        emitted = clean
                    }
                }
                is LlmStreamResult.Completed -> Unit
                is LlmStreamResult.Error -> throw result.throwable
            }
        }
        raw.toString()
    }

    /**
     * Run one tool. A tool must never take the turn down with it: a missing
     * permission or a dead network becomes a sentence the model can relay.
     */
    private fun execute(call: ToolCall): String {
        val tool = Tools.byName(call.name)
            ?: return "No such tool: ${call.name}."
        if (!Tools.hasPermission(context, tool.permission)) {
            return "Permission denied for ${tool.name}. Tell the user to grant " +
                "the permission in Settings, then ask again."
        }
        return runCatching { tool.run(context, call.arguments) }
            .getOrElse { e ->
                Log.w(TAG, "tool ${call.name} failed", e)
                "The ${call.name} tool failed: ${e.message ?: e::class.java.simpleName}."
            }
    }

    private fun statusFor(toolName: String) = when (toolName) {
        WebSearchTool.name -> "Searching the web…"
        ContactsTool.name -> "Looking in contacts…"
        CalendarTool.name -> "Checking the calendar…"
        else -> "Checking the device…"
    }

    /** Forget the conversation currently in the KV cache. */
    fun resetConversation() {
        val active = wrapper ?: return
        runBlocking { active.reset() }
        primedChatId = null
    }

    /** Stop the in-flight generation. Safe to call from another thread. */
    fun abort() {
        val active = wrapper ?: return
        runCatching { runBlocking { active.stopStream() } }
    }

    @Synchronized
    fun close() {
        val active = wrapper ?: return
        runCatching { active.close() }
        wrapper = null
        loadedModelId = null
        primedChatId = null
    }
}
