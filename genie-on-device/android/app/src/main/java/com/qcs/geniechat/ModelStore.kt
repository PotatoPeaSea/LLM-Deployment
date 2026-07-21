package com.qcs.geniechat

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Locates the Genie bundle on the device and prepares its config for the C API.
 *
 * The bundle is ~1.3GB, so it is not in the APK. scripts/10_push_app_model.sh
 * adb-pushes it into the app's external files dir, which is world-writable by
 * adb and readable by the app without any permission -- unlike /data/local/tmp,
 * which SELinux hides from an untrusted app.
 *
 *   /sdcard/Android/data/com.qcs.geniechat/files/models/<model-id>/
 */
object ModelStore {
    private const val TAG = "ModelStore"
    const val DEFAULT_MODEL_ID = "llama_v3_2_1b_instruct_ctx4096"

    fun modelsRoot(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /** Every bundle present, newest-looking first; a bundle is a dir with a genie_config.json. */
    fun availableBundles(context: Context): List<File> =
        modelsRoot(context).listFiles { f -> f.isDirectory && File(f, "genie_config.json").isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun findBundle(context: Context, modelId: String = DEFAULT_MODEL_ID): File? {
        val root = modelsRoot(context)
        val exact = File(root, modelId)
        Log.i(TAG, "looking for $exact: root exists=${root.exists()} readable=${root.canRead()} " +
            "children=${root.list()?.joinToString() ?: "<null>"} " +
            "config=${File(exact, "genie_config.json").isFile}")
        if (File(exact, "genie_config.json").isFile) return exact
        return availableBundles(context).firstOrNull()
    }

    /**
     * Read genie_config.json and rewrite every path in it to an absolute one.
     *
     * genie-t2t-run gets away with relative paths by chdir'ing into the bundle;
     * an app has one process-wide cwd it should not be moving around, so the
     * paths are absolutised here instead. The keys touched are exactly the ones
     * the export emits: tokenizer.path, engine.model.binary.ctx-bins, and
     * engine.backend.extensions.
     */
    fun buildConfigJson(bundleDir: File): String {
        val root = JSONObject(File(bundleDir, "genie_config.json").readText())
        val dialog = root.getJSONObject("dialog")

        fun abs(name: String) = File(bundleDir, name).absolutePath

        dialog.optJSONObject("tokenizer")?.let { it.put("path", abs(it.getString("path"))) }

        val engine = dialog.getJSONObject("engine")
        engine.optJSONObject("backend")?.let { backend ->
            backend.optString("extensions").takeIf { it.isNotEmpty() }
                ?.let { backend.put("extensions", abs(it)) }
        }
        engine.optJSONObject("model")?.optJSONObject("binary")?.optJSONArray("ctx-bins")
            ?.let { bins ->
                for (i in 0 until bins.length()) bins.put(i, abs(bins.getString(i)))
            }

        return root.toString()
    }

    /** Context length the bundle was exported with -- the app must not exceed it. */
    fun contextLength(bundleDir: File): Int = try {
        JSONObject(File(bundleDir, "genie_config.json").readText())
            .getJSONObject("dialog").getJSONObject("context").getInt("size")
    } catch (e: Exception) {
        Log.w(TAG, "No context size in genie_config.json, assuming 4096", e)
        4096
    }

    /** Human-readable summary for the "model missing" screen. */
    fun expectedPath(context: Context, modelId: String = DEFAULT_MODEL_ID): String =
        File(modelsRoot(context), modelId).absolutePath
}
