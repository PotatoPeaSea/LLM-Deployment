# Handoff: Qwen3.5-2B (GenieX GGUF) in GenieChatRN — images + tool calling

Date: 2026-07-22/23. Plan: `~/.claude/plans/deep-waddling-zephyr.md`.
Memory: `geniex-android-sdk-rn-app.md`, `geniex-llamacpp-gguf-npu-gpu.md`.

## ✅ RESOLVED (2026-07-23) — QNN NPU crash was a mismatched QAIRT lib

**Root cause: a QAIRT version split introduced by adding the GenieX AAR.**
The crash tombstone's frame #00 is in **`libQnnHtpNetRunExtensions.so`**, NOT
libGenie.so (earlier notes misread the top user frame). The Genie log shows
`QnnBackend_create done successfully` and then a null deref (fault addr 0x18)
the instant Genie calls the backend-extensions library to apply
`htp_backend_ext_config.json`.

Our QAIRT set was internally inconsistent in the APK:
- `libGenie.so` (22ddeef…) + `libQnnHtp.so` (7011b02…) = QAIRT **2.47**, staged
  by `scripts/09_stage_qairt_for_app.sh`, byte-identical to the working
  reference app.
- `libQnnHtpNetRunExtensions.so` (886abfd…) = a **newer** QAIRT, byte-identical
  to the copy inside `geniex-android-0.3.12.aar`.

The staging script never listed `libQnnHtpNetRunExtensions.so`, and there was no
`pickFirst`/`exclude` for it, so once the GenieX AAR was added its newer copy
landed in the APK unopposed. Old `libGenie.so` dlopens that newer extensions lib
by name, reaches across the version boundary, and SIGSEGVs. Before the AAR the
lib was simply absent → Genie ran with default HTP settings → worked. That is
exactly why "it worked before image attachment."

**Fix (committed as working-tree changes):**
1. `scripts/09_stage_qairt_for_app.sh` — added `libQnnHtpNetRunExtensions.so`
   to `HOST_LIBS` so the matching 2.47 copy (a611b6f…) is staged.
2. `android/app/build.gradle` — added
   `lib/arm64-v8a/libQnnHtpNetRunExtensions.so` to `pickFirsts` so our staged
   copy wins the merge over the AAR's.

**Verified on device (2026-07-23):** Llama 3.2 3B loads (~1.6s) and answers on
the NPU ("Red, blue, and yellow are the three primary colors.", 0.9s, ctx
357/2048). Log now reaches `qnn-htp: model has been validated!` →
`GenieBridge: dialog created` instead of crashing at `GenieDialog_create`.

Note: after `adb install -r`, `adb reverse tcp:8081 tcp:8081` is dropped; the
app then runs a stale JS bundle and throws `Genie.generate got 8 arguments,
expected 9` (arity drift from the `imagePaths` change). Re-set the reverse and
relaunch to pick up the current bundle — not a native bug.

<details><summary>Original blocker write-up (kept for history)</summary>

Both native LLM runtimes in this app SIGSEGV on the NPU, on every single text
turn, on a completely fresh install:

- **GenieX (Qwen3.5-2B, llama.cpp/Hexagon path):** `libgeniex_plugin_llama_cpp.so
  geniex::LlamaVlm::generate+4144`, fault addr `0x0`, right after prompt
  tokenization, before any tokens are produced.
- **QNN Genie C API (Llama 3.2 1B/3B, Qwen3 4B — a completely different,
  unrelated runtime):** `libGenie.so GenieDialog_create+420`, fault addr
  `0x18`, immediately after `QnnBackend_create` reports success and QNN logs
  `Initializing HtpProvider`.

Both crash at the same *kind* of place: the moment the runtime actually
reaches into the Hexagon NPU, with a null-ish pointer deref. **The device's
DSP itself is healthy** — Qualcomm's own pre-installed reference app
(`com.qcs.geniechat`, in the app drawer as "Genie Chat") loads a model and
replies correctly on the NPU, repeatably, throughout this investigation.
So the bug is specific to **our APK**, not the board.

### What was ruled out (with hard evidence, don't re-check these)
1. **GenieX vision/clip involvement** — forced `visionReady=false` (no
   `probeVision` reflection call) → still crashes. Set `mmprojFile = null`
   (clip never touched at all) → still crashes, identically.
2. **Tool-schema prompt length / n_ubatch=512 batching** — disabled tools,
   sent a 74-token prompt → still crashes at the same offset, same as a
   757-token prompt. Not size-dependent; crashes unconditionally.
3. **Model file corruption** — QNN Genie's own log shows `qnn-api initialized
   with 6 graph(s)` (all 3 `.bin` parts parsed fine) before the crash; the
   crash is one step later, at device/HTP creation, not at model parsing.
4. **Corrupted on-disk cache** — checked `run-as com.geniechatrn` files/
   cache dirs; nothing unexpected, no stale session files.
5. **Native lib version/corruption** — pulled both APKs
   (`adb pull .../base.apk`), diffed `lib/arm64-v8a`. `libGenie.so`,
   `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV73Skel.so`,
   `libQnnHtpV73Stub.so` are **byte-identical (md5) to the working reference
   app**. This is not a bad/mismatched QNN build.
6. **Symbol collision between GenieX and QNN libs** (my leading theory for a
   while) — `llvm-nm -D` dumped exported dynamic symbols from every
   `libgeniex*`/`libggml-htp-v73.so` vs every `libGenie.so`/`libQnn*.so`.
   Only trivial `_init`/`_fini` overlap (present in every ELF). No shared
   FastRPC/rpcmem helper names, nothing that could be silently interposed.
7. **GenieX ships incompatible-arch QNN libs (V79/V81) alongside our V73
   ones** — this device is v73-only; the GenieX AAR drags in
   `libQnnHtpV79*`/`libQnnHtpV81*` (~40MB of libs for hardware we don't
   have) that the working reference app simply doesn't ship. Added
   `packagingOptions.jniLibs.excludes` for those patterns in
   `android/app/build.gradle` (see diff — this part is a real, harmless
   cleanup, kept even though it **did not fix the crash**: identical
   crash, same offset, same fault addr, after rebuild+reinstall).
8. **GenieX SDK eager-initializing and poisoning the process before QNN
   Genie even runs** — `GenieXSdk.getInstance().init()` is only called from
   `GenieXEngine.initSdk()`, itself only reachable from `ensureModel()`.
   Confirmed via full logcat dump: zero `GenieX`/`geniex` log lines appear
   before the QNN Genie crash in a session that never touched the 2B model.
9. **targetSdkVersion-gated vendor library restrictions** — `dumpsys package`
   shows our app and the reference app both target SDK 34 (minSdk differs,
   27 vs 26, irrelevant here).
10. **Device-wide DSP wedge / needs a real reboot** — ruled out twice: the
    reference app worked *interleaved* with our failing tests (proving the
    DSP accepts new sessions from a healthy process at the same moments our
    app fails), and it kept working after force-stopping every other
    QNN/Genie process on the device.
11. **A background process holding the DSP** — found and disabled two
    always-on processes worth knowing about for future sessions:
    `com.smartsocfw.qnnapp` (installed today, alive since near-boot, respawns
    within ~1s of `am force-stop` — looks like a factory/QA watchdog-driven
    NPU exerciser on this reference board) and `com.qcs.geniechat` itself.
    Disabling both (`pm disable-user --user 0 <pkg>`) and killing our own app
    made no difference to the crash — so this wasn't it either, though it's
    good to know this process exists and auto-restarts.

### A red herring worth knowing about
There's a full standalone `genie-t2t-run` deployment at
`/data/local/tmp/genie_llama_v3_2_1b_instruct_ctx4096/` (and sibling dirs for
other models), left over from an earlier `deploy-genie-llm` skill run on
2026-07-21. Running it directly via `adb shell` fails with `[ERROR] "Failed to
create device: 14001"` — **this is not a real device failure**, it's SELinux:
`adb shell`'s `u:r:shell:s0` domain is denied `search`/`getattr`/`read` on
`adsprpcd_file`/`vendor_xdsp_device` (confirmed via `adb logcat -d | grep avc:`
right after the failing run). Apps run in a different, permitted domain. Don't
waste time on this path again — test through an actual installed app.

### Leads not yet tried
- **Process/VA-space pressure theory**: our app is a full React Native +
  Hermes process (~100+ native libs incl. several 80MB+ QAIRT prep libs,
  loaded for both the Genie C API path *and* the GenieX AAR) vs the
  reference app's handful of small libs. `ps -A` RSS was comparable
  (~110MB vs ~101MB) but VSZ was drastically larger for our app
  (`16648072` vs `16662276` — actually similar, recheck this, may not be
  the lead it first looked like). Worth checking `/proc/<pid>/maps` region
  count and `/proc/<pid>/status` `VmRSS`/`VmHWM` for our app **right as it
  crashes** vs the reference app at the equivalent moment — not yet done,
  needs a tight poll-loop launched right before tapping into a chat.
- **Minimal repro APK**: build a throwaway Kotlin-only Android project that
  links `libGenie.so` + the staged QAIRT libs and nothing else (no RN, no
  Hermes, no GenieX AAR) and calls `GenieDialog_create` with the exact same
  config/model files our app uses. If that works, the bug is really about
  "this process is too heavy" (RN/Hermes/GenieX footprint) rather than
  anything content-specific. If it *also* crashes, the bug is in something
  about our specific model files or `htp_backend_ext_config.json`/
  `genie_config.json` content vs the CLI-deployed copies (byte-diff those
  next — not yet done).
- **User has offered to locate the last-known-working point** in this
  session's history — the very first successful GenieX text-generation test
  ("red, blue, yellow"; "Tokyo" answered correctly) happened *before* the
  crash first appeared, on the same build session, before `probeVision`
  existed. Whatever that build actually was (git stash / diff against it, or
  ask the user for the exact repro) is the highest-value next lead — it's
  the one point we know for certain worked, and item #1 above already proves
  it wasn't about `probeVision` or vision at all, so something else in that
  same window changed.

### Diagnostic commands that worked well this session
```
# Full-buffer logcat (faster via on-device redirect than -d over adb):
adb shell "logcat -d > /sdcard/x.log 2>&1" && adb pull /sdcard/x.log

# Pull + diff an installed APK's native libs against another app's:
adb shell pm path <pkg>
adb pull "<path from above>" out.apk && unzip -o -q out.apk -d dir/
md5sum dir/lib/arm64-v8a/*.so   # compare against the other app's

# Dynamic symbol dump (NDK, not host binutils -- host nm can't read Android's ELF flavor reliably for this):
/opt/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only <lib>.so

# Background/watchdog process check:
adb shell "ps -A" | grep -iE "genie|qnn"
adb shell pm disable-user --user 0 <pkg>   # survives respawn attempts; force-stop alone does not
```

</details>

---

## Original goal (Qwen3.5-2B vision/tools work — status below assumes the
## NPU blocker above gets fixed first; nothing here can be verified until then)

Add Qwen3.5-2B (GGUF, GenieX llama.cpp, NPU, 164K ctx) to the RN app as a 2nd
runtime beside the QNN Genie C API, with image attachment (VLM) + on-device
tool calling. (9B dropped — 9B@100K OOMs, 9B@8K reboots the QCS8550.)

## Status
| Feature | State |
|---|---|
| GenieX runtime integration (2nd runtime, routing, one-resident) | ✅ built |
| Text generation (real chat template, streaming, 164K ctx) | ✅ QNN Genie path re-verified on device 2026-07-23 after the extensions-lib fix (Llama 3.2 3B, "Red, blue, and yellow…"). ⚠️ GenieX 2B @164K ctx now loads+repacks weights fully but is **LMKD-killed** during load (~2.2GB RSS, "device is not responding", signal 9 — an OOM, NOT the old SIGSEGV). Separate issue: try a smaller ctx in the 2B ModelStore spec |
| Tool calling (6 tools, agent loop) | Was ✅ verified — battery returned real 75% (retest after fix pending) |
| Image picker + attach UI + thumbnails + store | ✅ built & verified (SAF, openFileDescriptor) |
| VLM image inference | ❌ blocked by SDK (see below), degrades gracefully |
| APK builds & installs | ✅ |

## VLM image inference — root cause (external, not our code)
GenieX's clip.cpp (both Maven **0.3.12** and GitHub **0.3.16**) cannot load the
community Qwen3.5-2B mmproj: `clip_init: failed to load ... failed to seek for
tensor mm.2.bias`. The mmproj is sha256-verified complete; its
`clip.projector_type = qwen3vl_merger` (Qwen3-VL) is unsupported by GenieX's
clip. All community mmproj use this same converter → none will load.
- App handles this: `GenieXEngine.visionReady` (via getCapabilities) is false →
  attaching an image returns a graceful "can't see images" message, no crash.
- Vision will light up with **no code change** once GenieX ships qwen3vl_merger
  support — just bump the dep.

## Build / run (host has no node/adb display niceties)
- Portable node: a scratchpad `nodejs/bin` dir (path changes per session —
  `find /tmp -maxdepth 5 -iname nodejs -type d`) — prepend to PATH for gradle.
- `cd genie-on-device/app-rn/android && env PATH="$SP/nodejs/bin:$PATH" ./gradlew :app:assembleDebug`
  (if daemon lacks node: `./gradlew --stop` first).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Metro: `env PATH=... npx react-native start` + `adb reverse tcp:8081 tcp:8081`.
- Device **dozes** → screencap is black; `adb shell input keyevent KEYCODE_WAKEUP` first. Screen 720x1280. Package `com.geniechatrn`.
- Model bundle already pushed. Re-push: `scripts/11_push_gguf_model.sh qwen3_5_2b com.geniechatrn`
  (weights sha-verified in `workspace/gguf/qwen3_5_2b/`, gitignored).

## Files changed (all uncommitted)
- gradle: `android/build.gradle` (minSdk 23→27), `android/app/build.gradle`
  (geniex-android:0.3.12, pickFirst libQnnHtp/libQnnSystem, kotlinx-coroutines,
  **new**: excludes for GenieX's V79/V81 QNN libs — harmless cleanup, did not
  fix the NPU blocker).
- manifest: READ_CONTACTS, READ_CALENDAR.
- Kotlin new: `GenieXEngine.kt`, `Tools.kt`, `DeviceTools.kt`, `ContactsTool.kt`, `CalendarTool.kt`, `WebSearchTool.kt`, `ImagePickerModule.kt`.
- Kotlin changed: `ModelStore.kt` (Runtime enum, 2B spec, GGUF staging), `GenieModule.kt` (routing, images, tool perms), `GeniePackage.kt`.
- JS: `genie.ts`, `store.ts`, `components/Composer.tsx`, `components/Bubble.tsx`, `screens/ChatScreen.tsx`.
- New script: `scripts/11_push_gguf_model.sh`.

## Key verified facts
- GenieX Android SDK real API (from javap): `VlmWrapper.builder().vlmCreateInput(VlmCreateInput(name, gguf, mmproj, ModelConfig(nCtx), "llama_cpp", "npu")).build()`; `applyChatTemplate(msgs, toolsJson, enableThinking)` (tools = JSON string, plugin inserts `<__media__>` marker per image content itself); `injectMediaPathsToConfig`; `generateStreamFlow`; `reset()`.
- jniLib collision (libQnnHtp/libQnnSystem) resolved via pickFirst — app's QNN wins, both runtimes intact (Genie path uses V73 skel the AAR lacks; GenieX NPU uses libggml-htp-v73).
- NPU ctx ceiling for 2B ≈ 176K (192K fails). 164K safe.
- Reference apps on this device worth knowing about: `com.qcs.geniechat`
  ("Genie Chat" in the app drawer) is Qualcomm's own working NPU demo — good
  smoke-test target when debugging whether the *board* or *our app* is at
  fault. `com.smartsocfw.qnnapp` is an always-on, auto-respawning background
  process (installed today, purpose unknown, possibly a factory QA watchdog)
  — currently left `pm disable-user`'d from this session's debugging.
