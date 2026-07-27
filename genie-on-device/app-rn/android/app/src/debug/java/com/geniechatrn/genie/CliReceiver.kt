package com.geniechatrn.genie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.react.ReactApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-build-only entry point for driving a Genie turn from adb instead of
 * through the RN UI. Only compiled into the `debug` variant (this file lives
 * under `src/debug/`, not `src/main/`) -- a release build never contains it.
 *
 * Why this exists: see `HANDOFF-reasoning-tools-fixes.md`. Some of this app's
 * bugs (a full device reboot, not just an app crash) can only be pinned down
 * by pushing many prompts through unattended and watching the *host* side for
 * when the device drops off adb -- a screenshot can't catch a reboot in the
 * act, and the on-device logcat ring buffer is wiped by the time adb
 * reconnects. `scripts/genie_cli.py` is the host half of this: it fires one
 * broadcast per prompt, tails logcat continuously into a file on the host
 * (so a mid-turn reboot doesn't destroy the evidence), and watches for the
 * device coming back.
 *
 * This calls the exact same code path the UI does --
 * [GenieModule.runCliTurn] -> [ChatEngine]/[GenieXEngine] -- just without the
 * JS bridge, so whatever crashes for the UI crashes here too; nothing about
 * the reboot this is chasing is specific to React Native.
 *
 * Usage:
 * ```
 * adb shell am broadcast -a com.geniechatrn.CLI_PROMPT \
 *   --es turnId t1 --es modelId qwen3_5_2b --es chatId cli \
 *   --es text "Write an essay about the telephone" \
 *   --ez thinking true --ez brevity false --ez reset false
 * ```
 * Result lands on logcat tag `GenieCli` as a `CLI_RESULT`/`CLI_ERROR` line
 * carrying the turnId, plus the full answer (which can be many KB, over
 * logcat's per-line limit) as JSON under
 * `/sdcard/Android/data/com.geniechatrn/files/cli/<turnId>.json`.
 */
class CliReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GenieCli"
        const val ACTION = "com.geniechatrn.CLI_PROMPT"

        // One history per chatId, kept here (not in the JS store) so a CLI
        // conversation can span multiple broadcasts. Lost on process death,
        // which is fine -- a CLI run resends `reset=true` when it starts.
        private val histories = mutableMapOf<String, MutableList<Message>>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val turnId = intent.getStringExtra("turnId") ?: System.currentTimeMillis().toString()
        val chatId = intent.getStringExtra("chatId") ?: "cli"
        val modelId = intent.getStringExtra("modelId") ?: "qwen3_5_2b"
        // textFile (a path this app's own external files dir, pushed by adb
        // beforehand) is preferred over the text extra: an essay-length or
        // quote-containing prompt is one more thing that can go wrong getting
        // through `adb shell am broadcast`'s command-line join, and a pushed
        // file sidesteps that entirely. `text` is kept for quick manual pokes.
        val textFile = intent.getStringExtra("textFile")
        val text = textFile?.let { runCatching { File(it).readText() }.getOrNull() }
            ?: intent.getStringExtra("text")
        val thinking = intent.getBooleanExtra("thinking", true)
        val brevity = intent.getBooleanExtra("brevity", false)
        val reset = intent.getBooleanExtra("reset", false)

        if (text == null) {
            Log.e(TAG, "CLI_ERROR turnId=$turnId reason=missing_text_and_textFile_unreadable")
            return
        }

        // .reactHost (bridgeless-mode ReactHost) is never actually started by
        // this app -- MainApplication.getUseDeveloperSupport/new-arch flags
        // are off, so MainActivity runs the classic bridge via
        // reactNativeHost.reactInstanceManager. Reading .reactHost here
        // silently returns a ReactContext that is always null.
        val reactContext = (context.applicationContext as? ReactApplication)
            ?.reactNativeHost?.reactInstanceManager?.currentReactContext
        // Not reactContext.getNativeModule(GenieModule::class.java): that
        // overload does a reflection lookup keyed on a @ReactModule
        // annotation GenieModule doesn't have (it was never needed -- JS
        // resolves modules by the string from getName(), not this path), and
        // throws IllegalArgumentException instead of returning null. Walking
        // the module collection needs no annotation.
        val module = reactContext?.nativeModules?.filterIsInstance<GenieModule>()?.firstOrNull()
        if (module == null) {
            Log.e(TAG, "CLI_ERROR turnId=$turnId reason=react_context_not_ready")
            return
        }

        if (reset) histories.remove(chatId)
        val history = histories.getOrPut(chatId) { mutableListOf() }

        Log.i(
            TAG,
            "CLI_START turnId=$turnId chatId=$chatId modelId=$modelId thinking=$thinking " +
                "brevity=$brevity historyLen=${history.size} textLen=${text.length}",
        )

        module.runCliTurn(
            chatId, modelId, history.toList(), text, brevity, thinking,
            onToken = { _, _, _, status ->
                if (status.isNotBlank()) Log.i(TAG, "CLI_STATUS turnId=$turnId status=$status")
            },
            onDone = { result ->
                result.fold(
                    onSuccess = { r ->
                        history.add(Message(Role.USER, text))
                        history.add(Message(Role.ASSISTANT, r.answer))
                        writeResult(context, turnId, JSONObject().apply {
                            put("turnId", turnId)
                            put("chatId", chatId)
                            put("modelId", modelId)
                            put("elapsedMs", r.elapsedMs)
                            put("hasThoughts", r.hasThoughts)
                            put("thoughts", r.thoughts)
                            put("answer", r.answer)
                            put("toolsUsed", JSONArray(r.toolsUsed))
                        })
                        Log.i(
                            TAG,
                            "CLI_RESULT turnId=$turnId elapsedMs=${r.elapsedMs} answerLen=${r.answer.length} " +
                                "hasThoughts=${r.hasThoughts} toolsUsed=${r.toolsUsed} file=cli/$turnId.json",
                        )
                    },
                    onFailure = { e ->
                        writeResult(context, turnId, JSONObject().apply {
                            put("turnId", turnId)
                            put("chatId", chatId)
                            put("modelId", modelId)
                            put("error", e.message ?: e::class.java.simpleName)
                        })
                        Log.e(
                            TAG,
                            "CLI_ERROR turnId=$turnId error=${e.message ?: e::class.java.simpleName}",
                            e,
                        )
                    },
                )
            },
        )
    }

    private fun writeResult(context: Context, turnId: String, payload: JSONObject) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "cli").apply { mkdirs() }
            File(dir, "$turnId.json").writeText(payload.toString())
        }.onFailure { Log.w(TAG, "failed to write CLI result file for $turnId", it) }
    }
}
