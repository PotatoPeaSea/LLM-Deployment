# GenieChatRN

A React Native chat app that runs LLMs entirely on a Qualcomm device's
Hexagon NPU — no network, no cloud inference. Five models across two
on-device runtimes, multiple persisted chats, and per-chat brevity/reasoning
toggles.

Built and verified on a **QCS8550 ("Kalama") devkit, Android 13**. The two
runtimes underneath are chipset-generic (Genie/QNN, GenieX/llama.cpp), but
only this board has been tested.

For the full architecture, on-device measurements, and the debugging history
behind the current design, see
**[../docs/ANDROID-RN-APP.md](../docs/ANDROID-RN-APP.md)**. This file is the
practical "build it, deploy it" guide.

> **There is a second target: the same board running Ubuntu instead of
> Android.** It reuses the `src/` UI verbatim through react-native-web, and
> replaces the Kotlin/GenieX half with stock llama.cpp plus a Node app-server
> (`server/`). GGUF models only. Build and run it with
> `bash scripts/deploy-linux.sh --models gguf`; the guide is
> **[../docs/UBUNTU-BOARD.md](../docs/UBUNTU-BOARD.md)**. Nothing in that target
> is on the Android build path — `server/`, `web/` and the `.web.ts` files are
> invisible to gradle and Metro.

## What's inside

```
App.tsx  ── chats, settings, navigation
  └─ ChatScreen ── src/genie.ts ── NativeModules.Genie
                                     └─ GenieModule.kt   worker thread, events
                                        ├─ ChatEngine.kt    GENIE runtime  (QNN context binaries)
                                        └─ GenieXEngine.kt  GENIEX runtime (GGUF via llama.cpp)
```

| Model | Runtime | Reasoning | Context | Sees images | Uses tools |
|---|---|---|---|---|---|
| `llama_v3_2_1b_instruct_ctx4096` | GENIE | no | 4096 | no | no |
| `llama_v3_2_3b_instruct_ctx2048` | GENIE | no | 2048 | no | no |
| `qwen3_4b` | GENIE | yes | 512 | no | no |
| `qwen3_5_2b` (Qwen3.5-2B) | GENIEX | yes | 164,000 | yes | yes |
| `gemma4_e2b` (Gemma 4 E2B) | GENIEX | yes | 32,768 | no | no |

Only one model is resident at a time — switching models restarts the app
process (see *Model switching* in ANDROID-RN-APP.md for why). A chat is
pinned to whichever model it was started with.

## Prerequisites

- **Node 20.x** (`engines.node` in `package.json`). Nothing else about the
  toolchain is unusual — `npm install` in this directory pulls the rest.
- **JDK 17**, **Android SDK**, **NDK 26.1.10909125** (pinned in
  `android/build.gradle` — a different NDK will fail the native build).
  Point Gradle at your SDK with `android/local.properties` (`sdk.dir=...`,
  gitignored) or `$ANDROID_HOME`.
- **adb**, with exactly one Qualcomm NPU-capable device attached
  (`adb devices` shows one `device` line).
- The QAIRT vendor libraries (`libGenie.so`, `libQnnHtp*.so`, ~28MB total)
  are **already committed** under `android/app/src/main/jniLibs/` — a fresh
  clone builds without touching QAIRT at all. Only re-run
  `../scripts/09_stage_qairt_for_app.sh` if you're deliberately upgrading the
  QAIRT SDK version.
- This repo runs with `core.fileMode=false`, so a fresh clone checks every
  `.sh` file out **non-executable** regardless of what's in the working tree
  right now. Run scripts with `bash scripts/deploy.sh …`, or `chmod +x` them
  once yourself.

## Deploy to a device

The fastest path to a working app needs no AI Hub account, no Docker, and no
cloud compile — just download two GGUF files (below) and:

```bash
cd genie-on-device/app-rn
npm install
bash scripts/deploy.sh --models gguf     # build + install + push qwen3_5_2b + gemma4_e2b
```

This bundles the JS (into `android/app/src/main/assets/index.android.bundle`
— the APK runs standalone, no Metro server needed on a devkit that isn't
always tethered), runs `gradlew installDebug`, pushes the named model(s), and
launches the app. See `bash scripts/deploy.sh --help` for every option, and
the script's own header comments for exactly what each step does and why.

```bash
bash scripts/deploy.sh --skip-build --models gemma4_e2b   # already installed, just push one
bash scripts/deploy.sh --models all                       # push every model (~11GB total)
```

**Getting the model weights, per runtime:**

- **GENIEX models** (`qwen3_5_2b`, `gemma4_e2b`) — the `--models gguf` path
  above — are plain GGUF downloads, no export step, and `deploy.sh` doesn't
  fetch them for you (no confirmed download URL for every model is recorded
  in this repo's history, so this step stays manual on purpose). Fetch a
  Q4_0 quant and drop it in `../workspace/gguf/<model-id>/`:
  - `qwen3_5_2b` needs `Qwen3.5-2B-Q4_0.gguf` (~1.2GB) and
    `mmproj-F16.gguf` (~670MB, the vision projector).
  - `gemma4_e2b` needs `gemma-4-E2B-it-Q4_0.gguf` (~2.9GB) — verified
    working from `unsloth/gemma-4-E2B-it-GGUF` on Hugging Face.

  Neither `*.gguf` file is tracked in git (see `.gitignore`) — they're
  multi-GB binaries, and `deploy.sh`/`11_push_gguf_model.sh` push straight
  from `workspace/gguf/` to the device's app-private storage.

- **GENIE models** (`llama_*`, `qwen3_4b`) — **`deploy.sh` runs this step for
  you** when the bundle isn't already in `../workspace/output/<model-id>/`:
  it builds the Docker toolchain image if needed, configures your AI Hub
  token if needed, and runs the cloud compile itself
  (`../scripts/01_build_image.sh`/`02_configure_hub.sh`/`04_export_model.sh`
  under the hood). Needs Docker and, the first time only,
  `AI_HUB_API_TOKEN` (from https://aihub.qualcomm.com → Account → Settings →
  API Token); `llama_v3_2_3b_instruct_ctx2048` additionally needs `HF_TOKEN`
  (`meta-llama` is a gated Hugging Face repo). Missing either fails fast with
  a clear message — this is a real cloud compile, roughly an hour the first
  time per model, and `deploy.sh` says so before starting. See
  [../docs/REPRODUCTION.md](../docs/REPRODUCTION.md) for the full story, or
  to export manually instead.

## Iterating on the UI

For fast reload instead of the standalone-bundle flow above:

```bash
npx react-native start          # Metro, in one terminal
cd android && ./gradlew installDebug   # in another, once
```

## Debug tooling (debug builds only)

- **`scripts/genie_cli.py`** drives the app from the host over adb —
  send prompts, pick a model, toggle reasoning/brevity — without touching
  the UI, and tails logcat continuously so a mid-turn crash or reboot
  doesn't lose the evidence. `python3 scripts/genie_cli.py --help`.
- **`scripts/stress_switch.py`** repeatedly switches between two models to
  exercise the restart-on-switch path.

Both talk to `CliReceiver`, which only exists in debug builds — they need
`installDebug`, not a release APK.

## Known limitations

- Only tested on one board (QCS8550 "Kalama", Android 13).
- `qwen3_5_2b`'s vision path (`supportsImages`) is real but has known model
  quality caveats — see `handoffs/HANDOFF-qwen-vision.md`.
- No way to delete a staged/pushed model bundle from inside the app; each is
  several GB of on-device storage.
- Full known-issues and root-cause history: the `handoffs/HANDOFF-*.md`
  files, indexed from [../docs/ANDROID-RN-APP.md](../docs/ANDROID-RN-APP.md#still-open).
