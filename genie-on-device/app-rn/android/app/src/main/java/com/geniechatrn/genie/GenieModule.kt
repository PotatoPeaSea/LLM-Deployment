package com.geniechatrn.genie

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JS-facing surface of the NPU.
 *
 * Generation runs on a single-threaded executor, never the RN bridge thread:
 * a query blocks for seconds and Genie holds one dialog, so serialising is
 * both necessary and sufficient.
 *
 * Streaming crosses to JS as `GenieToken` events rather than promise
 * resolutions -- a promise can only settle once, and the whole point is
 * showing the reply as it is generated.
 *
 * Two engines live behind this module (see [Runtime]). They are never resident
 * at the same time: both want the same DSP memory, and the GGUF model's KV
 * cache alone is ~2GB at 164K. [switchTo] is what enforces that.
 */
private const val TOOL_PERMISSION_REQUEST = 0x9102

/**
 * How long to let the DSP settle after unloading one runtime before loading the
 * other. The cDSP tears down a runtime's HTP session asynchronously, so the
 * incoming runtime can race the outgoing one for the device -- see [GenieModule.switchTo].
 *
 * 700ms was not always enough: reproduced via scripts/genie_cli.py (load
 * Qwen3-4B/GENIE, then immediately a fresh Qwen3.5-2B/GENIEX chat) hitting
 * "HTP0 buffer mapping failed ... 0 MiB free" / error -100201 on every one of
 * [GenieXEngine.CREATE_ATTEMPTS], i.e. the QNN side's HTP memory still wasn't
 * released 700ms + 3*900ms (3.4s) later. Bumped up front rather than only in
 * the retry loop, since a longer first wait means fewer retries are needed.
 * [GenieXEngine.ensureModel] additionally retries if a create still slips
 * through this window, so this value only needs to make retries rare, not
 * eliminate them.
 */
private const val SWITCH_SETTLE_INTO_GENIEX_MS = 2000L

/**
 * Same race as [SWITCH_SETTLE_INTO_GENIEX_MS], opposite direction (GenieX
 * unloading, QNN loading) -- but with NO retry backstop possible, which is
 * why this needs a longer, more conservative settle than that direction gets
 * away with.
 *
 * Reproduced via scripts/stress_switch.py cycling qwen3_5_2b -> qwen3_4b: at
 * 2000ms settle, QnnDevice_create failed with err 1002
 * ("Transport layer setup failed", itself caused by the DSP queue create
 * hitting "fastrpc_mmap failed ... tNode->map.fd != fd" -- the same class of
 * "DSP session not released yet" symptom as -100201, just on the QNN side)
 * on EVERY one of 15 consecutive cycles. Unlike the GenieX side, this is not
 * a catchable, retryable error: QNN's own cleanup path (`QnnBackend_free`)
 * SIGSEGVs while freeing the half-initialized backend immediately after the
 * failed create, which is a native crash -- unrecoverable, and un-catchable
 * from Kotlin. [ChatEngine.ensureModel] has no retry loop and cannot get one
 * for the same reason: there is nothing left to retry once the process is
 * gone. The only lever here is not entering this failure path in the first
 * place, hence a longer, one-shot wait rather than a short-wait+retry
 * strategy.
 */
private const val SWITCH_SETTLE_INTO_GENIE_MS = 5000L

class GenieModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val engine = ChatEngine(reactContext)
    private val genieX = GenieXEngine(reactContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    override fun getName() = "Genie"

    private fun emit(event: String, payload: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, payload)
    }

    /**
     * Unload whichever runtime is NOT about to be used. Cheap when it is
     * already unloaded, which is the common case.
     *
     * When it does unload one, it then pauses -- see
     * [SWITCH_SETTLE_INTO_GENIEX_MS] / [SWITCH_SETTLE_INTO_GENIE_MS] for why
     * the two directions get different, asymmetric waits. Both runtimes
     * reach the NPU through the same cDSP, which releases an HTP session
     * asynchronously after `close()` returns. If the incoming runtime creates
     * its HTP device before that teardown lands, the create races it: GenieX's
     * llama.cpp HTP0 create fails with `-100201` (catchable, retried by
     * [GenieXEngine.ensureModel]), while QNN's device create fails with
     * `err 1002` and then SIGSEGVs inside its own cleanup (native, NOT
     * catchable -- [ChatEngine.ensureModel] has no retry loop and can't get
     * one). The pause fires only on a real switch, so a same-runtime reopen
     * pays nothing.
     */
    private fun switchTo(runtime: Runtime) {
        val unloaded = when (runtime) {
            Runtime.GENIE -> (genieX.currentModelId != null).also { genieX.close() }
            Runtime.GENIEX -> (engine.currentModelId != null).also { engine.close() }
        }
        if (unloaded) {
            val settleMs = when (runtime) {
                Runtime.GENIE -> SWITCH_SETTLE_INTO_GENIE_MS
                Runtime.GENIEX -> SWITCH_SETTLE_INTO_GENIEX_MS
            }
            Log.i("GenieModule", "runtime switch: settling ${settleMs}ms for DSP release")
            try {
                Thread.sleep(settleMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Model catalogue plus whether each bundle is actually present on the device. */
    @ReactMethod
    fun listModels(promise: Promise) {
        try {
            val array = Arguments.createArray()
            for ((spec, installed) in ModelStore.inventory(reactApplicationContext)) {
                array.pushMap(Arguments.createMap().apply {
                    putString("id", spec.id)
                    putString("name", spec.displayName)
                    putString("note", spec.note)
                    putBoolean("supportsReasoning", spec.supportsReasoning)
                    putBoolean("supportsImages", spec.supportsImages)
                    putBoolean("supportsTools", spec.supportsTools)
                    putString("runtime", spec.runtime.name)
                    putBoolean("installed", installed)
                    putString("path", ModelStore.bundleDir(reactApplicationContext, spec.id).absolutePath)
                })
            }
            promise.resolve(array)
        } catch (e: Throwable) {
            promise.reject("list_failed", e.message, e)
        }
    }

    /**
     * Make a model resident. Safe to call redundantly -- it no-ops when the
     * model is already loaded, which is what lets the UI call it on every
     * chat open without thinking about it.
     */
    @ReactMethod
    fun loadModel(modelId: String, promise: Promise) {
        worker.execute {
            try {
                val spec = ModelStore.spec(modelId)
                val t0 = System.currentTimeMillis()
                var lastPercent = -1
                val onStaging: (Long, Long) -> Unit = { copied, total ->
                    // Staging moves GBs; emit per percent, not per 4MB buffer.
                    val percent = if (total > 0) (copied * 100 / total).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        emit("GenieStaging", Arguments.createMap().apply {
                            putString("modelId", modelId)
                            putInt("percent", percent)
                            putDouble("totalBytes", total.toDouble())
                        })
                    }
                }

                switchTo(spec.runtime)
                val contextLength = when (spec.runtime) {
                    Runtime.GENIE -> {
                        engine.ensureModel(modelId, onStaging)
                        engine.contextLength
                    }
                    Runtime.GENIEX -> {
                        genieX.ensureModel(modelId, onStaging)
                        genieX.contextLength
                    }
                }

                promise.resolve(Arguments.createMap().apply {
                    putString("modelId", modelId)
                    putInt("contextLength", contextLength)
                    putBoolean("capped", engine.lastReplyWasCapped)
                    putDouble("loadMs", (System.currentTimeMillis() - t0).toDouble())
                })
            } catch (e: Throwable) {
                promise.reject("load_failed", e.message, e)
            }
        }
    }

    /**
     * Run one turn. [history] is the conversation before this turn as
     * [{role, content}], oldest first; it is only read when the KV cache has to
     * be rebuilt (new chat, model switch, or context overflow).
     *
     * [imagePaths] are absolute files. Only a VLM model accepts them; for the
     * QNN models the UI never offers the attach button, and anything that did
     * slip through is ignored rather than failing the turn.
     */
    @ReactMethod
    fun generate(
        chatId: String,
        modelId: String,
        history: ReadableArray,
        userText: String,
        imagePaths: ReadableArray,
        brevity: Boolean,
        thinking: Boolean,
        promise: Promise,
    ) {
        if (!busy.compareAndSet(false, true)) {
            promise.reject("busy", "A generation is already running")
            return
        }
        val messages = buildList {
            for (i in 0 until history.size()) {
                val m = history.getMap(i) ?: continue
                val role = when (m.getString("role")) {
                    "assistant" -> Role.ASSISTANT
                    "system" -> Role.SYSTEM
                    else -> Role.USER
                }
                add(Message(role, m.getString("content") ?: ""))
            }
        }
        val images = buildList {
            for (i in 0 until imagePaths.size()) imagePaths.getString(i)?.let { add(it) }
        }

        worker.execute {
            val spec = ModelStore.spec(modelId)
            val splitter = ReasoningSplitter()
            val t0 = System.currentTimeMillis()
            var status = ""

            // One emitter for both engines, so the JS side sees an identical
            // event stream whichever runtime produced it.
            fun push() = emit("GenieToken", Arguments.createMap().apply {
                putString("chatId", chatId)
                putString("answer", splitter.answer)
                putString("thoughts", splitter.thoughts)
                putBoolean("hasThoughts", splitter.hasThoughts)
                putString("status", status)
            })

            try {
                switchTo(spec.runtime)
                val sink = TokenSink { fragment ->
                    // Split as it streams so the UI can show the answer and the
                    // reasoning separately without waiting for the reply to end.
                    splitter.append(fragment)
                    push()
                }

                when (spec.runtime) {
                    Runtime.GENIE ->
                        engine.generate(chatId, modelId, messages, userText, brevity, thinking, sink)
                    Runtime.GENIEX ->
                        genieX.generate(
                            chatId, modelId, messages, userText, images, brevity, thinking, sink,
                        ) { note ->
                            // Tool progress. Not part of the reply, so it goes
                            // out of band rather than into the transcript.
                            status = note
                            push()
                        }
                }

                status = ""
                val (answer, thoughts) = splitter.finish()
                promise.resolve(Arguments.createMap().apply {
                    putString("answer", answer)
                    putString("thoughts", thoughts)
                    putBoolean("hasThoughts", thoughts.isNotBlank())
                    putDouble("elapsedMs", (System.currentTimeMillis() - t0).toDouble())
                    putInt("contextUsed", if (spec.runtime == Runtime.GENIE) engine.contextUsed else 0)
                    putInt(
                        "contextLength",
                        if (spec.runtime == Runtime.GENIE) engine.contextLength else genieX.contextLength,
                    )
                    putBoolean("capped", spec.runtime == Runtime.GENIE && engine.lastReplyWasCapped)
                    putArray("toolsUsed", Arguments.createArray().apply {
                        if (spec.runtime == Runtime.GENIEX) {
                            genieX.lastToolsUsed.forEach { pushString(it) }
                        }
                    })
                })
            } catch (e: Throwable) {
                promise.reject("generate_failed", e.message, e)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Ask for the permissions the tools need, up front.
     *
     * Requested when a tool-capable chat opens rather than mid-turn: a
     * permission dialog appearing while the model is generating would either
     * block the worker thread or force the tool to fail and be retried. A
     * refusal is fine -- [Tools.hasPermission] turns it into a sentence the
     * model relays instead of an error.
     */
    @ReactMethod
    fun requestToolPermissions(promise: Promise) {
        val activity = currentActivity
        val wanted = Tools.all().mapNotNull { it.permission }.distinct()
            .filter { !Tools.hasPermission(reactApplicationContext, it) }

        if (wanted.isEmpty() || activity !is com.facebook.react.modules.core.PermissionAwareActivity) {
            promise.resolve(null)
            return
        }
        activity.requestPermissions(wanted.toTypedArray(), TOOL_PERMISSION_REQUEST) { _, _, _ ->
            promise.resolve(null)
            true
        }
    }

    /** Aborts the in-flight query; the partial reply is still returned. */
    @ReactMethod
    fun stop(promise: Promise) {
        engine.abort()
        genieX.abort()
        promise.resolve(null)
    }

    /** Drops the KV cache, e.g. after deleting the open chat. */
    @ReactMethod
    fun resetConversation(promise: Promise) {
        worker.execute {
            engine.resetConversation()
            genieX.resetConversation()
            promise.resolve(null)
        }
    }

    /** Result of [runCliTurn] -- the CLI's equivalent of [generate]'s promise payload. */
    data class CliTurnResult(
        val answer: String,
        val thoughts: String,
        val hasThoughts: Boolean,
        val elapsedMs: Long,
        val toolsUsed: List<String>,
    )

    /**
     * Entry point for [CliReceiver], the debug-build adb interface described in
     * `HANDOFF-reasoning-tools-fixes.md`. Exercises the exact same
     * engine/[switchTo]/[busy] path as [generate] -- just with plain Kotlin
     * types in and a callback out, since there is no JS bridge (and no
     * `ReadableArray`/`Promise`) on this path. Kept as its own method rather
     * than folded into [generate] so the JS-facing method stays untouched: it
     * is the last known-working baseline this whole debugging session is
     * trying not to disturb.
     */
    fun runCliTurn(
        chatId: String,
        modelId: String,
        history: List<Message>,
        userText: String,
        brevity: Boolean,
        thinking: Boolean,
        onToken: (answer: String, thoughts: String, hasThoughts: Boolean, status: String) -> Unit,
        onDone: (Result<CliTurnResult>) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            onDone(Result.failure(IllegalStateException("A generation is already running")))
            return
        }
        worker.execute {
            val spec = ModelStore.spec(modelId)
            val splitter = ReasoningSplitter()
            val t0 = System.currentTimeMillis()
            var status = ""
            fun push() = onToken(splitter.answer, splitter.thoughts, splitter.hasThoughts, status)

            try {
                switchTo(spec.runtime)
                val sink = TokenSink { fragment -> splitter.append(fragment); push() }

                when (spec.runtime) {
                    Runtime.GENIE ->
                        engine.generate(chatId, modelId, history, userText, brevity, thinking, sink)
                    Runtime.GENIEX ->
                        genieX.generate(
                            chatId, modelId, history, userText, emptyList(), brevity, thinking, sink,
                        ) { note -> status = note; push() }
                }

                status = ""
                val (answer, thoughts) = splitter.finish()
                onDone(Result.success(CliTurnResult(
                    answer = answer,
                    thoughts = thoughts,
                    hasThoughts = thoughts.isNotBlank(),
                    elapsedMs = System.currentTimeMillis() - t0,
                    toolsUsed = if (spec.runtime == Runtime.GENIEX) genieX.lastToolsUsed else emptyList(),
                )))
            } catch (e: Throwable) {
                onDone(Result.failure(e))
            } finally {
                busy.set(false)
            }
        }
    }

    // RN requires these to exist for NativeEventEmitter on both platforms.
    @ReactMethod fun addListener(eventName: String) = Unit
    @ReactMethod fun removeListeners(count: Int) = Unit

    override fun invalidate() {
        worker.execute {
            engine.close()
            genieX.close()
        }
        worker.shutdown()
        super.invalidate()
    }
}
