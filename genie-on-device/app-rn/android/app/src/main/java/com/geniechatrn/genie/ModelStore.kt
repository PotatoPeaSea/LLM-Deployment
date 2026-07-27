package com.geniechatrn.genie

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Which models the app knows about, and where their bundles live.
 *
 * Bundles are GBs, so they are not in the APK. scripts/10_push_app_model.sh
 * pushes them to the app's external files dir, which adb can write and the app
 * can read without any permission -- unlike /data/local/tmp, which SELinux
 * hides from an untrusted app.
 *
 *   /sdcard/Android/data/com.geniechatrn/files/models/<model-id>/
 */
/**
 * Which inference stack a model runs on. Two of them ship in this app because
 * they take different artifacts and neither can load the other's:
 *
 *  GENIE  -- QAIRT's Genie C API over pre-compiled QNN context binaries
 *            (genie_bridge.cpp / ChatEngine). Prompts are rendered by our own
 *            [ChatTemplate].
 *  GENIEX -- Qualcomm's GenieX SDK running community GGUF through llama.cpp
 *            (GenieXEngine). The SDK owns the tokenizer and applies the model's
 *            real chat template, so ChatTemplate is unused for these.
 *
 * They also compete for the same DSP/GPU memory, so only one model is ever
 * resident regardless of runtime -- GenieModule enforces that.
 */
enum class Runtime { GENIE, GENIEX }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val template: ChatTemplate,
    /** Qwen3 can reason on demand; the Llamas have no such mode. */
    val supportsReasoning: Boolean,
    val note: String,
    /**
     * System prompt, per model rather than global.
     *
     * At ctx512 the prompt is not free: the original 45-token prompt was 9% of
     * Qwen's entire window before a single word was said. Qwen also needs less
     * steering than a 1B does, so it gets the terse one.
     */
    val systemPrompt: String,
    /** Appended when the brevity toggle is on. */
    val brevityClause: String = " Answer in one or two short, natural sentences.",

    val runtime: Runtime = Runtime.GENIE,

    // ---- GENIEX only ------------------------------------------------------
    /** GGUF weights, relative to the bundle dir. */
    val ggufFile: String? = null,
    /** Vision projector. Present iff the model can see images. */
    val mmprojFile: String? = null,
    /** "npu" | "gpu" | "cpu" | "hybrid" -- passed to GenieX as compute_unit. */
    val computeUnit: String = "npu",
    /**
     * Context window, declared here rather than read from the bundle.
     *
     * For a QNN bundle the window is baked into the export, so ModelStore reads
     * it back (below). A GGUF has no such ceiling -- Qwen3.5-2B is trained to
     * 262144 -- so the number is OUR choice, bounded by what the Hexagon DSP can
     * actually map. Measured on this QCS8550: 176K loads, 192K fails in
     * fastrpc_mmap. 164K keeps a margin under that wall.
     */
    val declaredContextLength: Int = 0,
    val supportsImages: Boolean = false,
    val supportsTools: Boolean = false,
)

object ModelStore {
    private const val TAG = "ModelStore"

    /**
     * Context length is NOT declared here -- it is read from each bundle's
     * genie_config.json, because it is a property of how the bundle was
     * exported. Claiming it in two places is how they drift.
     *
     * Qwen3-4B is the ctx512 export on purpose: on this QCS8550 the longer
     * exports do not fit (see docs/README.md). 512 tokens is genuinely small,
     * so ChatEngine's trim-and-re-prime path is a normal occurrence with it,
     * not an edge case.
     */
    val MODELS = listOf(
        ModelSpec(
            id = "llama_v3_2_1b_instruct_ctx4096",
            displayName = "Llama 3.2 1B",
            template = ChatTemplate.Llama3,
            supportsReasoning = false,
            note = "Fast, 4096-token context",
            systemPrompt =
                "You are a helpful assistant running entirely on this device's NPU.",
        ),
        ModelSpec(
            id = "llama_v3_2_3b_instruct_ctx2048",
            displayName = "Llama 3.2 3B",
            template = ChatTemplate.Llama3,
            supportsReasoning = false,
            note = "Stronger than the 1B, 2048-token context",
            systemPrompt =
                "You are a helpful assistant running entirely on this device's NPU.",
        ),
        ModelSpec(
            id = "qwen3_4b",
            displayName = "Qwen3 4B",
            template = ChatTemplate.Qwen3,
            supportsReasoning = true,
            note = "Smarter, reasoning, 512-token context",
            // 4 tokens instead of 31. Measured on-device, Qwen3-4B behaves the
            // same with this as with the long version, and at ctx512 the
            // difference is ~5% of the window handed back to the conversation.
            systemPrompt = "You are a helpful assistant.",
            brevityClause = " Be brief.",
        ),
        ModelSpec(
            id = "qwen3_5_2b",
            displayName = "Qwen3.5 2B",
            // Unused: GenieX applies the model's own chat template from the
            // GGUF. Kept non-null so the spec type stays uniform.
            template = ChatTemplate.Qwen3,
            supportsReasoning = true,
            note = "164K context, sees images, uses tools",
            // This one has room to spare, so it gets the prompt the others
            // cannot afford -- and it has to be told about its tools.
            systemPrompt =
                "You are a helpful assistant running entirely on this device. " +
                    "You can call tools to answer questions about the device, the " +
                    "user's contacts and calendar, and the web. Call a tool only " +
                    "when it is actually needed, and answer directly otherwise.",
            brevityClause = " Be brief.",
            runtime = Runtime.GENIEX,
            ggufFile = "Qwen3.5-2B-Q4_0.gguf",
            mmprojFile = "mmproj-F16.gguf",
            computeUnit = "npu",
            declaredContextLength = 164000,
            supportsImages = true,
            supportsTools = true,
        ),
        ModelSpec(
            id = "gemma4_e2b",
            displayName = "Gemma 4 E2B",
            // Unused, same reason as qwen3_5_2b: GenieX renders the GGUF's own
            // template.
            template = ChatTemplate.Qwen3,
            supportsReasoning = true,
            note = "Second GenieX/GGUF model -- added to test GenieX<->GenieX " +
                "switching (same runtime, different model) in isolation from " +
                "the QNN<->GenieX cross-runtime switch bugs. See " +
                "HANDOFF-cli-tool-and-crash-rootcause.md.",
            systemPrompt =
                "You are a helpful assistant running entirely on this device's NPU.",
            brevityClause = " Be brief.",
            runtime = Runtime.GENIEX,
            ggufFile = "gemma-4-E2B-it-Q4_0.gguf",
            // No mmprojFile -- text-only on purpose, same reason as
            // qwen3_5_2b's images being off: the VLM path SIGSEGVs
            // unconditionally on this device/plugin build.
            computeUnit = "npu",
            // Deliberately smaller than qwen3_5_2b's 164000: this is testing
            // switch reliability, not context ceilings, and Gemma 4's hybrid
            // local/global attention (sliding_window=512 on 4 of every 5
            // layers) means most of the KV cache doesn't scale with this
            // number anyway -- no need to chase the DSP mapping wall here.
            declaredContextLength = 32768,
            supportsImages = false,
            supportsTools = false,
        ),
    )

    val DEFAULT_MODEL_ID = MODELS.first().id

    fun spec(modelId: String): ModelSpec =
        MODELS.firstOrNull { it.id == modelId } ?: MODELS.first()

    fun modelsRoot(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /**
     * Where models are actually loaded from, and it is NOT the dir adb pushes to.
     *
     * The external files dir is FUSE-backed, and a large region of a FUSE file
     * cannot be mapped into the DSP's SMMU. Measured on this board: Qwen3-4B's
     * 968MB shard fails there with "Failed to map buffer of size 1006632960 ...
     * err 1002", while the identical bundle loads in 2.0s from internal storage
     * -- and loads fine via genie-t2t-run, which runs out of /data/local/tmp.
     * Llama's 525MB shards map either way, which is what makes the bug look
     * model-specific when it is really a storage-and-size interaction.
     *
     * So external storage is a delivery mechanism only; [stage] copies bundles
     * to internal storage, which is real f2fs, before anything tries to load them.
     */
    fun internalModelsRoot(context: Context): File = File(context.filesDir, "models")

    fun bundleDir(context: Context, modelId: String): File =
        File(internalModelsRoot(context), modelId)

    fun stagedDir(context: Context, modelId: String): File =
        File(modelsRoot(context), modelId)

    /**
     * Completion markers, written LAST so an interrupted transfer never looks
     * like a finished bundle.
     *
     * A QNN bundle gets this for free: genie_config.json is what every check
     * keys on, and [stage] copies it after the binaries. A GGUF bundle has no
     * such file -- the weights are one 1.15GB blob -- so the push script and
     * [stage] each drop their own marker instead.
     */
    private const val PUSHED_MARKER = ".push_complete"
    private const val STAGED_MARKER = ".staged"

    /** Is [dir] a complete bundle as delivered by adb (external storage)? */
    private fun isPushedBundle(dir: File, spec: ModelSpec) = when (spec.runtime) {
        Runtime.GENIE -> File(dir, "genie_config.json").isFile
        Runtime.GENIEX -> File(dir, PUSHED_MARKER).isFile
    }

    /** Is [dir] a complete bundle in internal storage, ready to load? */
    private fun isStagedBundle(dir: File, spec: ModelSpec) = when (spec.runtime) {
        Runtime.GENIE -> File(dir, "genie_config.json").isFile
        Runtime.GENIEX -> File(dir, STAGED_MARKER).isFile
    }

    /**
     * Copy a pushed bundle into internal storage if it isn't there already.
     * Several GB, so it reports progress and is skipped on every later load.
     *
     * The config file is written LAST: it is what every other check keys on, so
     * an interrupted copy must not look like a complete bundle.
     */
    fun stage(context: Context, modelId: String, onProgress: (Long, Long) -> Unit): File {
        val spec = spec(modelId)
        val target = bundleDir(context, modelId)
        if (isStagedBundle(target, spec)) return target

        val source = stagedDir(context, modelId)
        require(isPushedBundle(source, spec)) {
            val script = if (spec.runtime == Runtime.GENIEX) "11_push_gguf_model.sh" else "10_push_app_model.sh"
            "Model bundle not on device. Push it from the host:\n" +
                "./scripts/$script $modelId ${context.packageName}"
        }

        target.mkdirs()
        // The marker is copied last, by hand, once everything else has landed.
        val marker = if (spec.runtime == Runtime.GENIEX) PUSHED_MARKER else "genie_config.json"
        val files = source.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        val payload = files.filter { it.name != marker && it.name != STAGED_MARKER }
        val total = payload.sumOf { it.length() }
        var copied = 0L
        Log.i(TAG, "staging $modelId to internal storage (${total / 1_000_000}MB)")

        for (file in payload) {
            file.inputStream().use { input ->
                File(target, file.name).outputStream().use { output ->
                    val buffer = ByteArray(4 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            }
        }
        when (spec.runtime) {
            Runtime.GENIE ->
                File(source, "genie_config.json")
                    .copyTo(File(target, "genie_config.json"), overwrite = true)
            Runtime.GENIEX -> File(target, STAGED_MARKER).writeText("ok")
        }
        Log.i(TAG, "staged $modelId")
        return target
    }

    /** Absolute path of a file inside a staged bundle. */
    fun bundleFile(context: Context, modelId: String, name: String): String =
        File(bundleDir(context, modelId), name).absolutePath

    /** A model is usable if it is staged internally, or still pushable from external. */
    fun isAvailable(context: Context, modelId: String): Boolean {
        val spec = spec(modelId)
        return isStagedBundle(bundleDir(context, modelId), spec) ||
            isPushedBundle(stagedDir(context, modelId), spec)
    }

    fun isStaged(context: Context, modelId: String): Boolean =
        isStagedBundle(bundleDir(context, modelId), spec(modelId))

    /** Every known model plus whether its bundle is actually on the device. */
    fun inventory(context: Context): List<Pair<ModelSpec, Boolean>> {
        val root = modelsRoot(context)
        Log.i(TAG, "models root=$root readable=${root.canRead()} " +
            "children=${root.list()?.joinToString() ?: "<null>"}")
        return MODELS.map { it to isAvailable(context, it.id) }
    }

    /**
     * Read genie_config.json and rewrite every path in it to an absolute one.
     *
     * genie-t2t-run gets away with relative paths by chdir'ing into the bundle;
     * an app has one process-wide cwd it should not be moving around. The keys
     * touched are exactly the ones the export emits.
     */
    fun buildConfigJson(bundleDir: File): String {
        val root = JSONObject(File(bundleDir, "genie_config.json").readText())
        val dialog = root.getJSONObject("dialog")

        fun abs(name: String) = File(bundleDir, name).absolutePath

        dialog.optJSONObject("tokenizer")?.let { it.put("path", abs(it.getString("path"))) }

        // The exported sampler has no penalties at all, and these models do get
        // stuck repeating a sentence -- observed on Qwen3-4B, which restated
        // "The NPU is a specialized processing unit" three times before the
        // token cap stopped it. The cap bounds the damage; this reduces how
        // often it happens. 1.15 is mild enough not to distort normal prose.
        dialog.optJSONObject("sampler")?.let { sampler ->
            if (!sampler.has("token-penalty")) {
                sampler.put("token-penalty", JSONObject().apply {
                    put("version", 1)
                    put("penalize-last-n", 64)
                    put("repetition-penalty", 1.15)
                })
            }
        }

        val engine = dialog.getJSONObject("engine")
        engine.optJSONObject("backend")?.let { backend ->
            backend.optString("extensions").takeIf { it.isNotEmpty() }
                ?.let { backend.put("extensions", abs(it)) }
        }
        engine.optJSONObject("model")?.optJSONObject("binary")?.optJSONArray("ctx-bins")
            ?.let { bins -> for (i in 0 until bins.length()) bins.put(i, abs(bins.getString(i))) }

        return root.toString()
    }

    /**
     * Context length the bundle was exported with -- the app must not exceed it.
     * GENIE only; a GGUF has no exported window, so GENIEX models take theirs
     * from [ModelSpec.declaredContextLength].
     */
    fun contextLength(bundleDir: File): Int = try {
        JSONObject(File(bundleDir, "genie_config.json").readText())
            .getJSONObject("dialog").getJSONObject("context").getInt("size")
    } catch (e: Exception) {
        Log.w(TAG, "No context size in genie_config.json, assuming 4096", e)
        4096
    }
}
