# Genie Chat — the on-device Android app

**Written:** 2026-07-21. **Source:** [`android/`](../android). **Port of:** [CHATBOT.md](CHATBOT.md).

## What it is

`scripts/07_chatbot.py` as a real Android app. The host disappears entirely:
the phone runs the model, holds the conversation, and draws the UI.

```
you (typed)
   -> ChatEngine.kt         Llama-3.x template + context budgeting
   -> libgeniebridge.so     JNI
   -> libGenie.so           GenieDialog, held open for the whole session
   -> Hexagon NPU
```

### How it differs from the Python chatbot

| | `07_chatbot.py` | Genie Chat (app) |
|---|---|---|
| Where it runs | host orchestrates over adb | entirely on the device |
| Genie invocation | `genie-t2t-run` per turn | one `GenieDialog`, held open |
| Model load | 1.3GB context binary **per turn** | **once**, ~1.0s (mmap'd) |
| Conversation state | re-render whole transcript each turn | **KV cache persists** in the dialog |
| Prompt sent per turn | entire transcript | just the new turn |
| Output | one blob when finished | **streams** token by token |

That third row is the point of the whole exercise. Because the dialog's KV cache
survives between queries, a turn only prefills the new message: measured
occupancy went **62 → 92 tokens** across two turns rather than re-ingesting
everything. Prefill cost per turn is constant instead of growing with history.

## Verified on device (2026-07-21, QCS8550 `kalama`, Android 13)

- Model load: **0.97s** for `llama_v3_2_1b_instruct_ctx4096` (mmap, not a copy).
- Turn latency: **2.0s** and **2.8s** for the two turns below.
- Multi-turn memory: told it "My name is Bryan", asked "What is my name" one
  turn later — recalled it, with only the new turn sent to Genie.
- Arithmetic is still wrong (137×24 → "3,072", correct 3288). That is the 1B
  model at 4-bit, exactly as documented in CHATBOT.md — the harness is not the
  limit here.

## Build and install

```bash
cd genie-on-device

./scripts/09_stage_qairt_for_app.sh          # QAIRT libs + headers -> the project
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd .. && ./scripts/10_push_app_model.sh      # the 1.3GB bundle (skipped if current)
```

Neither the QAIRT binaries nor the model bundle are committed; both staging
steps are re-runnable and idempotent. `10_push_app_model.sh` fingerprints on
sizes+mtimes and skips a push the device already has.

## Four things that had to be right

Each of these failed first, and none of them produces an obvious error message.

1. **`<uses-native-library android:name="libcdsprpc.so">`** in the manifest.
   `libQnnHtpV73Stub.so` dlopens the FastRPC client to reach the DSP. It *is* a
   public vendor library, but an app's classloader namespace doesn't expose one
   unless it is declared. Symptom: `Failed to load skel, error: 4000`.

2. **`useLegacyPackaging = true`** (i.e. `extractNativeLibs`). The DSP skel is
   not dlopen'd by us — FastRPC passes its *path* to the DSP, which opens a real
   file. Left compressed inside the APK there is no such file. Symptom:
   `QNN_TRANSPORT_CONFIG crc32 failed`, `Failed to load skel, error: 1002`.

3. **`ADSP_LIBRARY_PATH` = the app's `nativeLibraryDir`**, set from JNI before
   the dialog is created. That is the only directory an app may hand the DSP.

4. **`chmod -R a+rX` on the pushed bundle.** adb creates those files owned by
   `shell`; the app is a different uid and `File.canRead()` returns false
   without it, despite the shared `ext_data_rw` group. Symptom: the app insists
   there is no model bundle at a path where the bundle plainly is.

Genie's own logger is bound to logcat under the tag `Genie` (see
`genie_bridge.cpp`) — it is what diagnosed 1, 2 and 3, and it is worth reaching
for first on any new board.

## Files

| Path | Role |
|---|---|
| `android/app/src/main/cpp/genie_bridge.cpp` | JNI ↔ Genie C API: create/query/abort/reset |
| `android/.../GenieBridge.kt` | native declarations, `TokenSink` |
| `android/.../ChatEngine.kt` | dialog lifetime, per-turn prompt, context budgeting |
| `android/.../Prompt.kt` | Llama-3.x template + token estimate (port of `prompt.py`) |
| `android/.../ModelStore.kt` | finds the bundle, absolutises paths in `genie_config.json` |
| `android/.../ChatViewModel.kt` | UI state, streaming, load/generate off the main thread |
| `scripts/09_stage_qairt_for_app.sh` | QAIRT libs + Genie headers into the project |
| `scripts/10_push_app_model.sh` | the model bundle onto the device |

`genie_config.json` is rewritten in memory at load time so every path in it is
absolute: `genie-t2t-run` gets away with relative paths by chdir'ing into the
bundle, which an app has no business doing.

## Context handling

Genie reports true occupancy (`GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY`), so unlike
the Python version the app only needs the `chars/3.2` estimate to predict
whether the *next* turn fits. When it doesn't, `ChatEngine.rebuildFor` resets the
dialog and re-primes it with the system prompt plus as much recent history as
fits — one full prefill, which is the price of outgrowing the window.

## Not built yet

- **Dictation.** The mic button is hidden. The device has a microphone, but the
  existing Whisper path (`scripts/chatbot/dictation.py`) drives `qnn-net-run`
  over adb from Python and depends on `qai_hub_models`' decode loop, none of
  which exists on-device. Porting it needs a native QNN context-binary runner
  (encoder 1→12 tensors, decoder 27→13), a log-mel front end, the KV-threaded
  greedy decode loop, and the Whisper BPE detokenizer.
- **Tool calling.** Deliberately, same reasoning as CHATBOT.md.
- Sampler settings are whatever the bundle's `genie_config.json` says
  (`temp 0.8, top-k 40`); there is no UI for them.
