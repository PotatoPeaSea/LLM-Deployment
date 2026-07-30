# Handoff: Qwen3.5-2B VISION (VLM image inference) on GenieX

Date: 2026-07-23. Supersedes the active work in `HANDOFF-qwen35-2b.md` (that
doc's NPU-crash blocker is **resolved** — kept only for the deep QAIRT history).
Memory: `geniex-android-sdk-rn-app.md`, `geniex-llamacpp-gguf-npu-gpu.md`.

App: `genie-on-device/app-rn` (React Native). Package `com.geniechatrn`.
Board: QCS8550 reference device, Hexagon v73, 11.5GB RAM, screen 720x1280.

---

## 🎯 GOAL: make the Qwen3.5-2B actually SEE attached images

Everything around vision is already built — image picker, attach UI, thumbnails,
the graceful "can't see images" fallback, and the message-construction path. The
one thing missing is the projector: **GenieX's bundled clip.cpp cannot load the
community Qwen3.5-2B mmproj**, so `visionReady` is always false and images are
dropped before they reach the model.

### The block, precisely
- The 2B bundle ships `mmproj-F16.gguf` (`ModelStore` `qwen3_5_2b` spec, sha-verified complete).
- On load, GenieX logs: `mtmd_init_from_file: error: Failed to load CLIP model
  from .../qwen3_5_2b/mmproj-F16.gguf` (older geniex builds: `clip_init: failed
  to load ... failed to seek for tensor mm.2.bias`).
- Cause: the mmproj's `clip.projector_type = qwen3vl_merger` (Qwen3-VL) is not
  supported by GenieX 0.3.12's clip.cpp. **All community Qwen3.5-2B mmproj use
  this same converter**, so swapping mmproj files alone won't help.
- `VlmWrapper.build()` still SUCCEEDS (the model is just text-only after), so the
  only signal is `GenieXEngine.probeVision()` → `visionReady=false`. Confirmed in
  today's load log: `loaded qwen3_5_2b on npu (ctx 164000) ... mmproj=true, visionReady=false`.

### Paths to unblock (ranked)
1. **Bump `com.qualcomm.qti:geniex-android` past 0.3.12** to a build whose
   clip.cpp supports `qwen3vl_merger`. Check Maven Central for > 0.3.12 and the
   GenieX GitHub for a newer AAR. 0.3.16 was already tried and **failed the same
   way** (see old handoff) — you need something newer than that, or confirmation
   from Qualcomm that qwen3vl support landed. This is the "vision lights up with
   no app code change" path — BUT see the ⚠️ QAIRT gotcha below, it is not free.
2. **Different projector format**: obtain/convert an mmproj whose `projector_type`
   GenieX's clip *does* load (e.g. an older `mlp`/`qwen2vl`-style merger) that is
   still weight-compatible with this GGUF. Unlikely for a Qwen3-VL model, but
   worth a quick check of what projector types 0.3.12's clip.cpp enumerates.
3. **Different VLM**: pick a small VLM whose mmproj GenieX already loads (changes
   the model, not just the projector). Falls back to "what can this SDK see at all".
4. **Patch clip.cpp**: if GenieX's clip source is reachable, add `qwen3vl_merger`
   and rebuild the plugin. Heavy; last resort.

### ⚠️ CRITICAL: bumping the geniex AAR can re-break the NPU (just fixed)
The whole reason both NPU runtimes crashed this session (commit `49fccb5`) is that
the geniex-android **AAR bundles its own, newer-QAIRT `libQnnHtpNetRunExtensions.so`**,
and it collided with our older `libGenie.so`. We fixed it by staging our matching
2.47 copy and pinning it via `pickFirst`. **A newer AAR may ship yet another QAIRT
version of that lib (and libQnnHtp/libQnnSystem).** So after ANY dep bump:
```
# rebuild, then confirm our copy still won the merge:
unzip -p app/build/outputs/apk/debug/app-debug.apk lib/arm64-v8a/libQnnHtpNetRunExtensions.so | md5sum
#   MUST be a611b6f3525ad1735aacd379038c2d41  (QAIRT 2.47, matches libGenie.so 22ddeef3...)
unzip -p .../app-debug.apk lib/arm64-v8a/libQnnHtp.so | md5sum   # MUST be 7011b0233...
```
If the AAR adds new colliding QNN filenames, add them to `pickFirsts` (or the
`excludes` for wrong-arch V79/V81) in `android/app/build.gradle`. Smoke-test a
**QNN** model (Llama 3.2 3B) after every bump, not just the 2B — the crash was in
the QNN path, not GenieX.

---

## Vision code map (all already in place, waiting on the projector)

- `ImagePickerModule.kt` — SAF `ACTION_OPEN_DOCUMENT`, copies the pick into
  `filesDir/attachments` via `openFileDescriptor` (MediaProvider URIs return null
  from `openInputStream`), downsamples to ≤1024px. Returns an absolute path. ✅ works.
- `GenieXEngine.visionReady` (set at load by `probeVision`, GenieXEngine.kt:171) —
  reflection on the private `Vlm` handle's `getCapabilities`. Any failure → false
  (safe: images refused, never a crash).
- `GenieXEngine.generate` (GenieXEngine.kt:257-269) — if images attached but
  `!visionReady`: drops them and streams a one-line "can't see images" note. This
  is the branch you'll stop hitting once the projector loads.
- `GenieXEngine.userMessage` (GenieXEngine.kt:429) — builds the VLM turn:
  `VlmContent("image", path)` per image, then `VlmContent("text", text)`.
  **⚠️ UNVERIFIED marker handling — the #1 thing to test first (see below).**
- Tools are disabled on image turns (GenieXEngine.kt:292) so the image is encoded
  through the projector exactly once.
- `ModelStore` `qwen3_5_2b`: `supportsImages = true`, `mmprojFile = "mmproj-F16.gguf"`,
  `computeUnit = "npu"`, `declaredContextLength = 164000`.

### ⚠️ The libmtmd media-marker trap (first thing to verify when vision loads)
libmtmd aborts (native segfault in `LlamaVlm::generate`) unless **exactly one
`<__media__>` marker appears in the prompt per attached bitmap**. There are two
*conflicting* theories in this repo's history, and NEITHER is verified end-to-end
(vision has never actually run):
- **Current code (GenieXEngine.kt:416-434)** assumes the plugin's
  `apply_chat_template` inserts the marker itself for each `image` content, so it
  adds **no** marker (comment: "measured: it grows the message by 11 chars per image").
- **Memory `geniex-android-sdk-rn-app.md` (older theory)** says the plugin drops
  the image contents and inserts NO marker, so you must **prepend** `MEDIA_MARKER`
  = `"<__media__>\n"` yourself (the const is still at GenieXEngine.kt:61, currently unused).

When vision first loads, dump the templated prompt and **count the markers vs the
bitmaps**. If 0 markers → switch to prepending `MEDIA_MARKER`. If 2 → remove one.
This is the most likely first crash after the projector starts loading.

---

## How to test vision once the projector loads
```
# 1. make an unambiguous test image
python3 -c "from PIL import Image,ImageDraw; i=Image.new('RGB',(640,480),'white'); d=ImageDraw.Draw(i); d.ellipse([80,140,280,340],fill=(220,40,40)); d.rectangle([360,160,540,340],fill=(40,80,220)); i.save('/tmp/vlmtest.png')"
adb push /tmp/vlmtest.png /sdcard/Pictures/vlmtest.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/vlmtest.png
# 2. in the 2B chat, tap the + attach button (~x=58 y=1122 on 720x1280), pick Recent > the image, ask "what shapes and colours do you see?"
# 3. expect visionReady=true in the load log, and a reply naming a red circle + blue square.
adb shell "logcat -d" | grep -iE "visionReady|mtmd|clip|media marker|LlamaVlm"
```

---

## State of the tree (what's done vs pending)

| Item | State |
|---|---|
| QNN NPU crash (QAIRT lib mismatch) | ✅ fixed + committed `49fccb5`, verified on device (Llama 3.2 3B on NPU) |
| GenieX 2B text + tools | ✅ works (fresh load; battery tool returned real data earlier) |
| GenieX 2B loads at 164K ctx | ✅ reliable from a clean start (~9.5s, ~2GB peak, not an OOM) |
| Runtime-switch DSP race | ✅ **fix verified on device** — QNN→2B now loads (settle log fires, no `-100201`, no kill) where it reliably failed before |
| GenieX 2B **text generation** | 🔴 **BLOCKER (pre-existing, root-caused 2026-07-23)** — SIGSEGV `fault addr 0x0` right after `Tokenized ... into ~755 tokens` in `vlm.cpp _ml_vlm_generate_internal`. Reproduces on a **clean-booted** board and on a **fresh** load, so it is NOT board state, NOT the switch, NOT the QAIRT/switch fixes. **Likely cause: prompt > ubatch.** Load reserves the HTP graph for `ubatch n_tokens = 512` (see `graph_reserve` log), but the tool-schema-laden prompt is ~755 tokens — the plugin appears not to split a prompt longer than the reserved ubatch and overruns. **Must be solved before vision is testable.** |
| Vision (image inference) | ❌ the eventual goal — blocked by clip.cpp qwen3vl_merger (above), AND gated behind the generate crash |

### 2B load time: ~9.5s (warm) vs ~56s (cold HTP graph)
A fresh 2B load is ~56s when the HTP graph cache is cold and ~9.5s when warm.
The ~50s is `graph_reserve` for the `n_tokens=512` prompt graph at 164K ctx. A
clean `adb reboot` was tried this session and did NOT change it (still 56s cold),
so this is normal cold-cache behaviour, not a wedged board — the cache warms
after the first post-boot load. Not a bug; just budget for it in tests.

### The runtime-switch fix (implemented + verified on device 2026-07-23)
Verified: after the fix, a QNN→2B switch logs `runtime switch: settling 700ms`
and the 2B loads successfully (no `-100201`, no LMKD kill) — 2/2, where the
switch reliably failed before. The `createWithRetry` backstop was in place but
did not need to fire (the settle alone was enough). Caveat: could not show
switch→generate end-to-end because of the separate generate SIGSEGV above.

Root cause: switching QNN model → GenieX 2B failed on the first try (either an
LMKD "device is not responding" kill, or `GenieXSdk create() failed -100201` at
HTP0), because the cDSP tears down the outgoing runtime's HTP session
asynchronously and the incoming runtime raced it. A retry always worked.
Fix (2 files):
- `GenieModule.switchTo` — now pauses `SWITCH_SETTLE_MS` (700ms) after an *actual*
  unload (no-op switches pay nothing), giving the DSP time to release.
- `GenieXEngine.ensureModel` → `createWithRetry` — retries the VLM create up to 3×
  with a 900ms settle + `System.gc()` if it throws (backstop for `-100201`).

**To verify when the board is back:** load a QNN model (Llama 3.2 3B), then open
the 2B chat and send — it should load first try now (watch for
`runtime switch: settling` then `loaded qwen3_5_2b`, no `-100201`, no death).
Repeat the switch a few times; the pre-fix failure was intermittent.

---

## Carry-over gotchas (bit us this session)
- **Board reboots / drops off USB under memory stress.** It rebooted during the
  switch testing and left ADB. After a reboot: replug USB, `adb kill-server &&
  adb start-server`, `adb devices`, then `adb reverse tcp:8081 tcp:8081`.
- **`adb reverse` is cleared by every `adb install -r`.** The app then runs a
  stale JS bundle and throws e.g. `Genie.generate got 8 arguments, expected 9`
  (JS/native arity drift). Re-set the reverse and relaunch — it is NOT a code bug.
- **Device dozes → `screencap` is black.** `adb shell input keyevent KEYCODE_WAKEUP` first.
- **Ignore the camera noise.** `vendor.qti.camera.provider-service_64` /
  `camera.qcom.so` SIGABRT every ~5s (`CSLGetHwInfo FATAL`) is an unrelated board
  daemon crash-loop; it is not our app.
- **`com.qcs.geniechat`** ("Genie Chat" in the drawer) is Qualcomm's own working
  NPU demo — the smoke test for "is the board or our app at fault".

## Build / run
- Portable node (no system node): `find /tmp -maxdepth 6 -iname node -type f` →
  `env PATH="$ND/bin:$PATH" ./gradlew :app:assembleDebug` from `app-rn/android`.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Re-stage QAIRT libs after a version change: `scripts/09_stage_qairt_for_app.sh`
  (now also stages `libQnnHtpNetRunExtensions.so` — the fix).
- Re-push the 2B bundle: `scripts/11_push_gguf_model.sh qwen3_5_2b com.geniechatrn`
  (weights sha-verified in `workspace/gguf/qwen3_5_2b/`, gitignored).
- Metro: `env PATH=... npx react-native start` + `adb reverse tcp:8081 tcp:8081`.
