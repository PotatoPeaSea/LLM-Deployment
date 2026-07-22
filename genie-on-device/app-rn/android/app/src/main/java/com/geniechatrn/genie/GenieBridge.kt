package com.geniechatrn.genie

/** Thrown for any non-success status out of the Genie C API. */
class GenieException(message: String) : RuntimeException(message)

/** Receives generated text as it streams out of the NPU, fragment by fragment. */
fun interface TokenSink {
    fun onToken(text: String)
}

/**
 * Thin, stateless wrapper over the native Genie dialog. Everything here blocks;
 * callers must stay off the main thread. [ChatEngine] owns the handle.
 */
object GenieBridge {
    init {
        System.loadLibrary("geniebridge")
    }

    @JvmStatic external fun nativeCreate(configJson: String, nativeLibDir: String): Long
    @JvmStatic external fun nativeQuery(handle: Long, prompt: String, sink: TokenSink): String?
    @JvmStatic external fun nativeSetMaxTokens(handle: Long, maxTokens: Int)
    @JvmStatic external fun nativeAbort(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeContextOccupancy(handle: Long): Int
    @JvmStatic external fun nativeFree(handle: Long)
}
