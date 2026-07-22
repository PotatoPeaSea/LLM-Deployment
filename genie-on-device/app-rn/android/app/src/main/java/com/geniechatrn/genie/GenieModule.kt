package com.geniechatrn.genie

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
 */
class GenieModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val engine = ChatEngine(reactContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    override fun getName() = "Genie"

    private fun emit(event: String, payload: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, payload)
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
                val t0 = System.currentTimeMillis()
                var lastPercent = -1
                engine.ensureModel(modelId) { copied, total ->
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
                promise.resolve(Arguments.createMap().apply {
                    putString("modelId", modelId)
                    putInt("contextLength", engine.contextLength)
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
     */
    @ReactMethod
    fun generate(
        chatId: String,
        modelId: String,
        history: ReadableArray,
        userText: String,
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

        worker.execute {
            val splitter = ReasoningSplitter()
            val t0 = System.currentTimeMillis()
            try {
                engine.generate(chatId, modelId, messages, userText, brevity, thinking) { fragment ->
                    // Split as it streams so the UI can show the answer and the
                    // reasoning separately without waiting for the reply to end.
                    splitter.append(fragment)
                    emit("GenieToken", Arguments.createMap().apply {
                        putString("chatId", chatId)
                        putString("answer", splitter.answer)
                        putString("thoughts", splitter.thoughts)
                        putBoolean("hasThoughts", splitter.hasThoughts)
                    })
                }
                val (answer, thoughts) = splitter.finish()
                promise.resolve(Arguments.createMap().apply {
                    putString("answer", answer)
                    putString("thoughts", thoughts)
                    putBoolean("hasThoughts", thoughts.isNotBlank())
                    putDouble("elapsedMs", (System.currentTimeMillis() - t0).toDouble())
                    putInt("contextUsed", engine.contextUsed)
                    putInt("contextLength", engine.contextLength)
                    putBoolean("capped", engine.lastReplyWasCapped)
                })
            } catch (e: Throwable) {
                promise.reject("generate_failed", e.message, e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Aborts the in-flight query; the partial reply is still returned. */
    @ReactMethod
    fun stop(promise: Promise) {
        engine.abort()
        promise.resolve(null)
    }

    /** Drops the KV cache, e.g. after deleting the open chat. */
    @ReactMethod
    fun resetConversation(promise: Promise) {
        worker.execute {
            engine.resetConversation()
            promise.resolve(null)
        }
    }

    // RN requires these to exist for NativeEventEmitter on both platforms.
    @ReactMethod fun addListener(eventName: String) = Unit
    @ReactMethod fun removeListeners(count: Int) = Unit

    override fun invalidate() {
        worker.execute { engine.close() }
        worker.shutdown()
        super.invalidate()
    }
}
