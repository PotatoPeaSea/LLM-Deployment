package com.geniechatrn.genie

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.runBlocking

/**
 * The GGUF half of the app: Qwen3.5-2B on the NPU through GenieX's llama.cpp
 * runtime.
 *
 * Three things make this genuinely different from [ChatEngine], not just a
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
 * 3. It can call tools and see images. Neither is an SDK feature -- see
 *    [Tools] for the tool-call protocol and the loop in [generate].
 *
 * The 164K window means the context arithmetic ChatEngine needs (trimming,
 * re-priming, reserve-for-reply) is simply not required: a conversation would
 * have to run to roughly half a million characters before it mattered.
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
         * libmtmd's media placeholder. One per attached image must appear in
         * the prompt text; the plugin swaps each for the model's real vision
         * tokens at generate time. Trailing newline so the question that
         * follows is not glued to it.
         */
        private const val MEDIA_MARKER = "<__media__>\n"
    }

    private var wrapper: VlmWrapper? = null
    private var loadedModelId: String? = null
    private var primedChatId: String? = null
    private var sdkReady = false

    /**
     * Whether the loaded model can actually see images.
     *
     * NOT the same as [ModelSpec.supportsImages]: that says the model is a VLM
     * and we shipped an mmproj; this says the mmproj actually LOADED. A projector
     * GGUF that clip.cpp can't parse (observed: the community Qwen3.5 mmproj vs
     * GenieX 0.3.12's clip fails with "failed to seek for tensor mm.2.bias")
     * leaves the model text-only -- and sending it an image then segfaults the
     * native side, because the prompt carries a media marker with no bitmap
     * behind it. So this gate is what stands between a bad mmproj and a crash.
     */
    var visionReady: Boolean = false
        private set

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

        // A VlmWrapper is used even for text-only chats. It is a superset of
        // LlmWrapper -- a message whose contents are all "text" behaves
        // identically -- and choosing per-turn would mean unloading and
        // reloading 1.15GB the first time the user attaches a photo.
        val mmproj = spec.mmprojFile?.let { java.io.File(bundle, it) }?.takeIf { it.isFile }
        val input = VlmCreateInput(
            spec.displayName,
            gguf.absolutePath,
            mmproj?.absolutePath.orEmpty(),
            ModelConfig().apply { nCtx = contextLength },
            "llama_cpp",
            spec.computeUnit,
        )

        val built = runBlocking {
            VlmWrapper.builder().vlmCreateInput(input).build().getOrThrow()
        }
        wrapper = built
        loadedModelId = modelId
        primedChatId = null
        visionReady = mmproj != null && probeVision(built)
        Log.i(TAG, "loaded $modelId on ${spec.computeUnit} (ctx $contextLength) " +
            "in ${System.currentTimeMillis() - t0}ms, mmproj=${mmproj != null}, visionReady=$visionReady")
    }

    /**
     * Ask the native VLM whether its vision path is live.
     *
     * `build()` succeeds even when the projector fails to load -- the model is
     * simply text-only afterwards -- so this is the only way to know before we
     * hand it an image. The capability lives on the internal `Vlm` handle that
     * `VlmWrapper` keeps private, so it is reached reflectively; any failure is
     * treated as "no vision", which is the safe default (worst case: images are
     * refused for a model that could actually see, never a crash).
     */
    private fun probeVision(w: VlmWrapper): Boolean = runCatching {
        val vlmField = w.javaClass.getDeclaredField("vlm").apply { isAccessible = true }
        val handleField = w.javaClass.getDeclaredField("handle").apply { isAccessible = true }
        val vlm = vlmField.get(w)
        val handle = handleField.getLong(w)
        val caps = vlm.javaClass
            .getMethod("getCapabilities", Long::class.javaPrimitiveType)
            .invoke(vlm, handle)
        val supportsVision = caps?.javaClass?.getMethod("getSupportsVision")?.invoke(caps) as? Boolean
        supportsVision == true
    }.getOrElse {
        Log.w(TAG, "vision capability probe failed, assuming text-only", it)
        false
    }

    /**
     * Run one user turn, resolving any tool calls, and stream the visible reply
     * into [sink]. Blocking; call from a worker thread.
     *
     * [onStatus] reports what the model is doing between generations ("Searching
     * the web…"). Without it a tool-using turn looks frozen for ten seconds,
     * because nothing is streamed while a tool runs.
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

        // Never hand an image to a model whose projector didn't load -- that is
        // the native segfault. Drop the images and say so, rather than pretend
        // to have looked or crash the app.
        val images = if (imagePaths.isNotEmpty() && !visionReady) {
            Log.w(TAG, "dropping ${imagePaths.size} image(s): vision not available on $modelId")
            emptyList()
        } else {
            imagePaths
        }
        if (imagePaths.isNotEmpty() && !visionReady) {
            val note = "This model's vision component could not be loaded on this " +
                "device, so I can't see the attached image. I can still answer text questions."
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
        val messages = mutableListOf<VlmChatMessage>()
        messages.add(textMessage("system", system))
        for (message in history) {
            messages.add(textMessage(message.role.wire, message.content))
        }
        messages.add(userMessage(userText, images))

        // Tools are offered on text turns only. A vision question rarely needs
        // one, and keeping the image out of the tool loop means it is encoded
        // through the projector exactly once -- re-rendering a prompt that still
        // holds the media marker on a second iteration is asking for the
        // marker/bitmap mismatch that crashes the plugin.
        val toolsJson = if (spec.supportsTools && images.isEmpty()) Tools.schemaJson() else null
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
            messages.add(textMessage("assistant", reply))
            visible = Tools.stripCalls(reply).trim()

            for (call in calls) {
                onStatus(statusFor(call.name))
                val result = execute(call)
                used.add(call.name)
                Log.i(TAG, "tool ${call.name}(${call.arguments}) -> ${result.take(120)}")
                messages.add(textMessage("tool", result))
            }
        }

        lastToolsUsed = used
        return visible.ifBlank { "I wasn't able to finish looking that up." }
    }

    /** One generation: render the prompt, stream it, return the raw reply. */
    private fun runOnce(
        active: VlmWrapper,
        messages: Array<VlmChatMessage>,
        toolsJson: String?,
        thinking: Boolean,
        sink: TokenSink,
        alreadyEmitted: String,
    ): String = runBlocking {
        val prompt = active.applyChatTemplate(messages, toolsJson, thinking)
            .getOrThrow()
            .formattedText

        // The SDK pulls the image paths out of the messages themselves, so the
        // config and the prompt can never disagree about how many images there
        // are -- a mismatch there desynchronises the vision placeholders.
        val config = active.injectMediaPathsToConfig(
            messages,
            GenerationConfig().apply { maxTokens = MAX_NEW_TOKENS },
        )

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

    private fun textMessage(role: String, content: String) =
        VlmChatMessage(role, listOf(VlmContent("text", content)))

    /**
     * The user's turn, carrying any attached images.
     *
     * Two things have to line up or the VLM plugin segfaults (observed: a null
     * deref in LlamaVlm::generate when they don't):
     *
     *  1. Each `image` content is what `extractMediaPaths` reads to load the
     *     bitmaps, so the paths reach `GenerationConfig.imagePaths`.
     *  2. The prompt needs one libmtmd `<__media__>` marker per bitmap -- but
     *     the plugin's apply_chat_template INSERTS that marker itself for each
     *     `image` content (measured: it grows the message by 11 chars per
     *     image). So we must NOT add our own, or the count becomes 2 markers to
     *     1 bitmap and libmtmd aborts.
     *
     * Images lead the text -- Qwen wants them ahead of the question.
     */
    private fun userMessage(text: String, imagePaths: List<String>): VlmChatMessage {
        val contents = mutableListOf<VlmContent>()
        for (path in imagePaths) contents.add(VlmContent("image", path))
        contents.add(VlmContent("text", text))
        return VlmChatMessage("user", contents)
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
