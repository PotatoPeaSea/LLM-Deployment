# Handoff: get GenieX Qwen3.5-2B TEXT generation working at 164K ctx

Date: 2026-07-24. **Goal, narrowed to one thing: make the 2B produce a plain
text reply on the NPU at `nCtx = 164000`.** No vision, not even tools required —
just one working text turn. Right now EVERY generate SIGSEGVs.

Vision (`HANDOFF-qwen-vision.md`) is downstream of this — you can't test images
until text generates. The runtime-switch and QNN-NPU work is done (see status).

App: `genie-on-device/app-rn`, package `com.geniechatrn`, board QCS8550 (Hexagon
v73, 11.5GB), screen 720x1280. Memory: `geniex-android-sdk-rn-app.md`.

---

## The crash (exact, captured 2026-07-24)
```
signal 11 (SIGSEGV), fault addr 0x0, tid name DefaultDispatch, com.geniechatrn
#00 libgeniex_plugin_llama_cpp.so  geniex::LlamaVlm::generate(geniex_VlmGenerateInput const*, geniex_VlmGenerateOutput*)+4144
#01 libgeniex.so                    geniex_vlm_generate+864
#02 libnpu_jni.so                   Java_com_geniex_sdk_jni_Vlm_generate+676
#05 com.geniex.sdk.VlmWrapper$generateStreamFlow$1$1.invokeSuspend
```
Log right before it, every time:
```
vlm.cpp:320 generate] using text-only (direct llama) path
vlm.cpp:344 generate] _ml_vlm_generate_internal: Tokenized new text portion into ~755 tokens
--- SIGSEGV ---
```
It dies **right after tokenizing, before emitting a single token**, inside
Qualcomm's **prebuilt** `libgeniex_plugin_llama_cpp.so` at a **fixed offset
(+4144)**. We can only change what we *pass* the plugin, not its internals.

**This is NOT new and NOT our regression.** The identical crash
(`geniex::LlamaVlm::generate+4144`, fault 0x0, "right after prompt tokenization")
is documented in `HANDOFF-qwen35-2b.md` as an active blocker from the prior
session, before any of this session's commits. The QNN-NPU fix (`49fccb5`) and
the runtime-switch fix (`c73f275`) touch the load path and the QNN `.so` set —
neither is in the plugin's generate path. GenieX text-gen **did work early in the
prior session** ("red, blue, yellow"; "Tokyo"; battery tool call returned real
75%) and then some change in that same session flipped it into this unconditional
crash. That change was never found. **Finding it is the goal.**

---

## Already ruled out — DO NOT re-test these
- **Prompt size / tools** — a 74-token prompt with tools **disabled** crashes at
  the same offset as a 757-token one (`HANDOFF-qwen35-2b.md` ruled-out #2). "Not
  size-dependent; crashes unconditionally." So this is NOT about the tool schemas
  or prompt length, and trimming the prompt is a dead end.
- **Vision / mmproj** — `mmprojFile = null` (clip never touched) and forced
  `visionReady = false` both still crash identically (ruled-out #1). The failed
  `mtmd_init_from_file` (qwen3vl_merger unsupported) is a red herring for THIS
  crash — the plugin logs "using text-only (direct llama) path" and dies anyway.
- **The QAIRT lib mismatch** — that was the *separate* QNN `GenieDialog_create`
  crash, fixed in `49fccb5`. GenieX uses `libggml-htp-v73`, not the QNN libs.
- **Board state** — reproduces on a clean `adb reboot` (fresh boot, 9.7GB free).
- **The switch / load path** — the model loads fine (`loaded qwen3_5_2b ... in
  ~56s`); the crash is strictly in generate.

---

## Prime suspect: `nCtx = 164000` itself (test this FIRST)
The crash is prompt-size-independent, but **nCtx has never been varied against
generate.** The 164K KV cache is the one "large" knob left. The plugin clearly
*loads* 164K (graph reserves, model validates) — but loading ≠ being able to
generate at it.

**Experiment 1 (highest value, do first):**
1. `ModelStore.kt` `qwen3_5_2b` spec (line ~139): `declaredContextLength = 8192`.
2. Rebuild, install, send a plain "hello".
3. **If it generates at 8K** → the 164K context is the trigger; the plugin can't
   generate at that window. Then bisect upward (32K, 64K, 96K, 128K) to find the
   ceiling, and chase the plugin knobs that bound the KV/compute buffers:
   `genie_config` for the QNN path uses `spill-fill-bufsize` / `mmap-budget`; for
   GenieX check whether `ModelConfig` (only `nCtx` is set today, GenieXEngine.kt
   kt:162) or `GenerationConfig` expose `nBatch`/`nUbatch`/kv-offload knobs via
   `javap` on the AAR. The *goal is text at 164K*, so a working smaller ctx is a
   diagnostic, not the finish line — but it tells you the fight is "why can't the
   plugin generate at 164K" (likely a buffer it sizes from nCtx overflowing).
4. **If it STILL crashes at 8K** → nCtx isn't it; go to the suspect list below.

---

## If nCtx isn't it: bisect the `generateStreamFlow` inputs
The generate call is `GenieXEngine.runOnce` (kt:342):
```
prompt = active.applyChatTemplate(messages, toolsJson, thinking).formattedText   // kt:350
config = active.injectMediaPathsToConfig(messages, GenerationConfig{maxTokens=1024}) // kt:357
active.generateStreamFlow(prompt, config).collect { ... }                        // kt:364  <-- crashes
```
Get to a **minimal working baseline**, then add variables back one at a time.
Minimal = the closest thing to the prior-session "Tokyo" test that worked:
1. **`thinking = false`** always (the `enableThinking` arg to `applyChatTemplate`).
   Qwen3 thinking mode changes the template; try forcing it off.
2. **Skip `injectMediaPathsToConfig`** on text turns — pass a plain
   `GenerationConfig().apply { maxTokens = MAX_NEW_TOKENS }` straight to
   `generateStreamFlow` when there are no images. It is called on *every* turn
   today, including pure text; if it leaves a vision field half-set, that could
   be what `LlamaVlm::generate` derefs.
3. **`GenerationConfig` params** — try the SDK default (drop `maxTokens`).
4. **Messages structure** — try a single user message, no system message, empty
   history (`applyChatTemplate` with one `VlmChatMessage("user", [text])`).
If the bare case generates, re-add system prompt → history → tools → thinking →
injectMedia until it crashes; the one that flips it is the cause.

---

## Highest-value lead: the last-known-working prior-session build
GenieX text worked, then broke, **within the prior session** — same technique
that cracked the QNN crash (find the last-working point and diff). There is no
intermediate git history (the whole GenieX feature landed in one commit,
`49fccb5`), so reconstruct it from the prior session's record:
- Plan: `~/.claude/plans/deep-waddling-zephyr.md`.
- `HANDOFF-qwen35-2b.md` "Leads not yet tried" #3 is exactly this and was never
  done. It notes the working test predates `probeVision` — but #1 already proves
  vision/probeVision isn't the cause, so look at the *other* things that changed
  in that window: was `nCtx` bumped to 164K after the working test? were the
  system prompt / tools / thinking flag added after it? That diff is the answer.

---

## Status (what's done vs this goal)
| Item | State |
|---|---|
| QNN NPU crash (QAIRT lib mismatch) | ✅ fixed `49fccb5`, verified (Llama 3.2 3B on NPU) |
| Runtime-switch DSP race | ✅ fixed `c73f275`, verified on device |
| GenieX 2B **load** at 164K | ✅ works (~56s cold / ~9.5s warm graph_reserve) |
| GenieX 2B **text generation** at 164K | 🔴 **THE GOAL** — SIGSEGV `LlamaVlm::generate+4144`, every turn |
| Vision (image inference) | ⛔ deferred — blocked by clip.cpp qwen3vl_merger AND gated behind this |

---

## Gotchas / how to run
- **Capture the crash with a big buffer.** `adb logcat -G 16M` first, or the
  ~56s cold load spews verbose `GenieXSdk` repack lines that rotate the tombstone
  out before you can dump it. Then `adb shell "logcat -d"` and grep for
  `Tokenized new text portion` and `LlamaVlm::generate`.
- **Load is slow when cold** (~56s, `graph_reserve` for the n_tokens=512 graph);
  ~9.5s once the on-disk HTP graph cache warms. `adb reboot` clears the cache.
- **Board reboots under memory stress** and drops off USB — replug, `adb
  kill-server && adb start-server`, `adb reverse tcp:8081 tcp:8081`.
- **`adb install -r` clears `adb reverse`** → stale JS bundle → bogus
  `Genie.generate got N arguments` errors. Re-set the reverse and relaunch.
- **Device dozes** → black screencap → `adb shell input keyevent KEYCODE_WAKEUP`.
- Build: portable node (`find /tmp -maxdepth 6 -iname node -type f`),
  `env PATH="$ND/bin:$PATH" ./gradlew :app:assembleDebug` from `app-rn/android`,
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Re-push the 2B bundle if needed: `scripts/11_push_gguf_model.sh qwen3_5_2b com.geniechatrn`.
- **Inspect the real SDK API** with `javap` on the AAR's `classes.jar`
  (`~/.gradle/caches/.../geniex-android/0.3.12/*.aar`) — the web docs are wrong.
  Look specifically for `ModelConfig` / `GenerationConfig` fields beyond `nCtx`
  and `maxTokens` (batch/kv knobs) for Experiment 1.
- **Escalation** if no input combo avoids the crash: it's a bug inside Qualcomm's
  prebuilt plugin. Try a newer `com.qualcomm.qti:geniex-android` (> 0.3.12) — but
  re-verify the QAIRT `pickFirst` after any bump (see `HANDOFF-qwen-vision.md`,
  the "bumping the AAR can re-break the NPU" section), or raise it with Qualcomm.
