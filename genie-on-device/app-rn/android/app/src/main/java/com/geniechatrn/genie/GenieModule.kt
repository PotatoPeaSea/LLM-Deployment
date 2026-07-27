package com.geniechatrn.genie

import android.content.Intent
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
 * cache alone is ~2GB at 164K. [switchToOrRestart] is what enforces that --
 * by restarting the whole process on a genuine switch rather than unloading
 * and reloading in place. See that function's doc for why.
 */
private const val TOOL_PERMISSION_REQUEST = 0x9102

/**
 * Extra settle before THIS PROCESS's very first model create() -- on top of
 * whatever React Native's own cold start already costs (observed 1-4s).
 *
 * Restarting the process (see [GenieModule.switchToOrRestart]) fixed every
 * crash/wedge failure mode tried, but under scripts/stress_switch.py's
 * back-to-back restart cadence (a new process every 2-15s) a genuine device
 * REBOOT still happened once: qwen3_5_2b's process loaded, generated one
 * reply, and the board went down moments into a second one -- no
 * `lowmemorykiller` kill logged first, unlike the original memory-pressure
 * reboot this echoes, suggesting something lower-level (kernel/watchdog)
 * this time. The OLD process being fully dead does not guarantee the
 * kernel/DSP has finished reclaiming ITS memory, and the new process's
 * first large allocation (qwen3_5_2b's KV buffer is ~1.9GB) can race that
 * reclaim. This is a blunt, unconditional wait rather than a real
 * readiness check because no such check exists on this device -- see the
 * research notes in HANDOFF-cli-tool-and-crash-rootcause.md (proc/meminfo,
 * debugfs, tracefs, and the GenieX SDK API were all checked; none expose
 * DSP/ION/FastRPC free memory to an app-level process).
 */
private const val FIRST_LOAD_SETTLE_MS = 3000L

class GenieModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val engine = ChatEngine(reactContext)
    private val genieX = GenieXEngine(reactContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val firstLoadSettled = AtomicBoolean(false)

    override fun getName() = "Genie"

    private fun emit(event: String, payload: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, payload)
    }

    /**
     * Make sure [modelId] -- and only [modelId] -- is what's resident,
     * restarting the whole app process first if a DIFFERENT model is
     * currently loaded in either engine.
     *
     * Why a restart instead of unloading the old model and loading the new
     * one in place, which is what this used to do: every combination of
     * in-process unload+reload was tried and found unreliable on this
     * device's Hexagon/FastRPC driver, all four of them tracing back to the
     * same family of `fastrpc_mmap`/"DSP session not released yet" failure,
     * just surfacing differently each time (see
     * HANDOFF-cli-tool-and-crash-rootcause.md and
     * scripts/stress_switch.py, which is how each was reproduced):
     *
     *  - QNN -> GenieX: GenieX's create() fails `-100201` (catchable, was
     *    made rare by [GenieXEngine]'s settle+retry loop, not eliminated).
     *  - GenieX -> QNN: QNN's device create fails `err 1002`, then its own
     *    cleanup (`QnnBackend_free`) SIGSEGVs -- a native, un-catchable
     *    crash. A 2.5x longer settle (2000ms -> 5000ms) did not help.
     *  - GenieX -> GenieX (two different GGUFs): the cDSP compute process
     *    itself aborts on the first `llama_decode` after the switch --
     *    create() succeeds, generation crashes.
     *  - QNN -> QNN (two different QNN models): `err 1002` on create, same
     *    as above, except this one never recovers -- every later create in
     *    that process fails identically, forever.
     *
     * Longer waits and retry loops made some of these rarer but fixed none
     * of them outright. The one thing that was 100% reliable across every
     * repeated-switch stress run, for every model and every runtime, was a
     * FRESH process's first load. So a genuine switch does not attempt
     * create() next to a live session at all -- restart, and let the new
     * process's first load be a first load, not a second one. JS resumes the
     * same chat afterward (App.tsx persists the open chat id for exactly
     * this) and calls loadModel again, which this time has nothing else
     * resident.
     *
     * Returns true if a restart was triggered. The caller must stop
     * immediately in that case -- [Runtime.getRuntime].exit kills the
     * process before anything queued after this call would run, but nothing
     * should be queued after it regardless.
     */
    private fun switchToOrRestart(modelId: String): Boolean {
        val current = engine.currentModelId ?: genieX.currentModelId
        if (current == null || current == modelId) return false

        Log.w(
            "GenieModule",
            "model switch $current -> $modelId requires a process restart " +
                "(in-process switching is unreliable on this device, see HANDOFF)",
        )
        val ctx = reactApplicationContext
        val launchIntent = requireNotNull(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)) {
            "no launch intent for ${ctx.packageName}"
        }
        ctx.startActivity(Intent.makeRestartActivityTask(launchIntent.component))
        // Fully qualified: this file's own Runtime enum (GENIE/GENIEX) shadows
        // java.lang.Runtime otherwise.
        java.lang.Runtime.getRuntime().exit(0)
        return true
    }

    /**
     * Pay [FIRST_LOAD_SETTLE_MS] exactly once per process, right before the
     * first real model create(). No-ops on every call after the first.
     */
    private fun settleBeforeFirstLoad() {
        if (!firstLoadSettled.compareAndSet(false, true)) return
        Log.i("GenieModule", "settling ${FIRST_LOAD_SETTLE_MS}ms before this process's first model load")
        try {
            Thread.sleep(FIRST_LOAD_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
                if (switchToOrRestart(modelId)) return@execute
                settleBeforeFirstLoad()
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
                // Not expected on the normal UI path -- the JS side only calls
                // generate() once loadModel already resolved for this exact
                // model -- but guarded anyway for callers (the CLI) that skip
                // straight to generate().
                if (switchToOrRestart(modelId)) return@execute
                settleBeforeFirstLoad()
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
     * engine/[switchToOrRestart]/[busy] path as [generate] -- just with plain
     * Kotlin types in and a callback out, since there is no JS bridge (and no
     * `ReadableArray`/`Promise`) on this path. Kept as its own method rather
     * than folded into [generate] so the JS-facing method stays untouched: it
     * is the last known-working baseline this whole debugging session is
     * trying not to disturb.
     *
     * A model switch here restarts the process exactly like [generate] and
     * [loadModel] -- which means, unlike before, two `--model` values in one
     * scripts/genie_cli.py run now cost a full app restart between them, not
     * an in-process switch. Expected and fine: genie_cli.py already treats a
     * pid change as `app_crash` and relaunches/continues past it.
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
                if (switchToOrRestart(modelId)) return@execute
                settleBeforeFirstLoad()
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
