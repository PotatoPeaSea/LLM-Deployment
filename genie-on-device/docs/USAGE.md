# USAGE — quick start and deployment for GenieChatRN

Practical operator guide for the app in **[`../app-rn/`](../app-rn)**: build
it, get models onto a device, run it. For *why* it's built the way it is
(architecture, on-device measurements, the debugging history) see
[ANDROID-RN-APP.md](ANDROID-RN-APP.md). This file and
[`../app-rn/README.md`](../app-rn/README.md) cover the same ground — this one
is the docs/ index entry, that one is what you actually run from.

> **Targeting a Qualcomm board running Ubuntu rather than Android?** Everything
> below is the Android target. The Linux target is a different stack — stock
> llama.cpp instead of the GenieX AAR, a Node app-server instead of the Kotlin
> engine, the same UI compiled for the browser — and has its own guide:
> [UBUNTU-BOARD.md](UBUNTU-BOARD.md). One command:
> `bash scripts/deploy-linux.sh --models gguf`.

---

## 1. Prerequisites

| Requirement | Check | If missing |
|---|---|---|
| Node 20.x | `node -v` | install from nodejs.org — `engines.node` in `app-rn/package.json` pins this |
| JDK 17 + Android SDK + NDK 26.1.10909125 | `echo $ANDROID_HOME` | NDK version is pinned in `app-rn/android/build.gradle`; a different NDK fails the native build |
| Device attached over adb | `adb devices` shows one `device` line | replug / `adb kill-server && adb start-server` |
| Qualcomm NPU-capable device | `adb shell getprop ro.product.board` | built/verified on QCS8550 ("kalama"); other chipsets untested |

The QAIRT vendor libraries the app needs (`libGenie.so`, `libQnnHtp*.so`,
~28MB) are **already committed** under `app-rn/android/app/src/main/jniLibs/`
— nothing to stage for a first build.

> This repo runs with `core.fileMode=false`: a fresh clone checks every
> `.sh` file out non-executable regardless of what's on disk right now. Every
> command below is written with `bash script.sh`, not `./script.sh`, for
> exactly that reason.

## 2. Quick start — no cloud compile needed

Two of the app's five models (`qwen3_5_2b`, `gemma4_e2b`) are plain GGUF
downloads with no export step — the fastest way to a working app:

```bash
cd genie-on-device/app-rn
npm install

# Fetch the weights (not tracked in git — see step 4):
#   workspace/gguf/qwen3_5_2b/Qwen3.5-2B-Q4_0.gguf   (~1.2GB)
#   workspace/gguf/qwen3_5_2b/mmproj-F16.gguf         (~670MB)
#   workspace/gguf/gemma4_e2b/gemma-4-E2B-it-Q4_0.gguf (~2.9GB)

bash scripts/deploy.sh --models gguf
```

That one command bundles the JS, runs `gradlew installDebug`, pushes both
GGUF models to the device, and launches the app.

## 3. Deployment instructions, in full

```bash
bash scripts/deploy.sh                                    # build + install only, no models
bash scripts/deploy.sh --models qwen3_4b                  # + push one specific model
bash scripts/deploy.sh --models qwen3_4b,gemma4_e2b       # + push several, comma-separated
bash scripts/deploy.sh --models gguf                      # + push both GGUF models (no export needed)
bash scripts/deploy.sh --models all                       # + push every model (~11GB total)
bash scripts/deploy.sh --skip-build --models gemma4_e2b   # app already installed, just push
```

Env overrides: `PKG` (installed package id, default `com.geniechatrn`),
`VARIANT` (gradle build variant, default `Debug`), `CHIPSET` (AI Hub chipset
for a GENIE export, default `qualcomm-qcs8550-proxy`), `AI_HUB_API_TOKEN` /
`HF_TOKEN` (see §4). Run `bash scripts/deploy.sh --help` for the option list
with explanations, or read the script itself — every step is commented with
what it does and why.

Under the hood `deploy.sh` dispatches each model to whichever push script
matches its runtime — and for a GENIE model with no bundle on disk yet,
**runs the cloud-compile pipeline first**, automatically:

| Runtime | Models | If no bundle yet | Push script |
|---|---|---|---|
| GENIE (QNN) | `llama_v3_2_1b_instruct_ctx4096`, `llama_v3_2_3b_instruct_ctx2048`, `qwen3_4b` | builds the Docker image, configures the AI Hub token, runs the cloud compile (`ensure_genie_bundle` in `deploy.sh`) | `../scripts/10_push_app_model.sh` |
| GENIEX (GGUF) | `qwen3_5_2b`, `gemma4_e2b` | nothing automatic — see §4, no confirmed download URL is recorded for every model so this step stays manual | `../scripts/11_push_gguf_model.sh` |

So `bash scripts/deploy.sh --models qwen3_4b` is a genuine one-command path
from a bare clone **if** Docker and an AI Hub token are available — it is not
"assumes you already ran the export pipeline by hand." It's still a real
cloud compile costing real time (~1 hour) the first time for each model, and
it says so loudly before starting.

## 4. Getting model weights

**GENIEX (GGUF) models** — no export, just a download, dropped into
`workspace/gguf/<model-id>/`. This part stays manual: no confirmed download
URL for every model is recorded in this repo's history, so `deploy.sh`
doesn't guess one.

- `qwen3_5_2b`: `Qwen3.5-2B-Q4_0.gguf` (~1.2GB) + `mmproj-F16.gguf` (~670MB,
  the vision projector — this model sees images and calls tools).
- `gemma4_e2b`: `gemma-4-E2B-it-Q4_0.gguf` (~2.9GB) — verified working from
  `unsloth/gemma-4-E2B-it-GGUF` on Hugging Face.

Neither `*.gguf` is tracked in git (see `.gitignore` — model weights never
are); `deploy.sh`/`11_push_gguf_model.sh` push straight from
`workspace/gguf/` to the device's app-private storage.

**GENIE (QNN) models** — `deploy.sh` runs this part for you automatically
when the bundle isn't already in `workspace/output/<model-id>/`. What it
needs to do that:

- **Docker**, to run the toolchain image.
- **`AI_HUB_API_TOKEN`** — only the first time ever (once
  `workspace/qai_hub_config/client.ini` exists, it's reused). Get one from
  https://aihub.qualcomm.com → Account → Settings → API Token.
- **`HF_TOKEN`**, only for `llama_v3_2_3b_instruct_ctx2048` — `meta-llama` is
  a gated Hugging Face repo. A token without access to that repo will fail
  the export with a clear error.

```bash
AI_HUB_API_TOKEN=... bash scripts/deploy.sh --models qwen3_4b
HF_TOKEN=... AI_HUB_API_TOKEN=... bash scripts/deploy.sh --models llama_v3_2_3b_instruct_ctx2048
```

Missing Docker or a token fails fast with an explicit message rather than
hanging — neither can be fetched automatically. Expect on the order of an
hour end to end the first time per model (mostly cloud compile + a one-time
large checkpoint download that's cached for later re-exports). Full
step-by-step, including the exact chipset/context-length gotchas for
QCS8550 and what to do if `qai-hub-models`' CLI flags have drifted again
(it's installed unpinned — this has already happened once), is in
[REPRODUCTION.md](REPRODUCTION.md).

To export manually instead (e.g. a different context length):

```bash
cd genie-on-device
./scripts/01_build_image.sh qwen3-4b
./scripts/02_configure_hub.sh <AI_HUB_API_TOKEN>
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
```

## 5. Iterating on the UI

For fast reload instead of rebuilding the standalone bundle every time:

```bash
cd app-rn
npx react-native start                 # Metro, in one terminal
cd android && ./gradlew installDebug   # in another, once
```

## 6. Debug tooling (debug builds only)

- **`app-rn/scripts/genie_cli.py`** drives the app from the host over adb —
  send prompts, pick a model, toggle reasoning/brevity — without touching
  the UI, tailing logcat continuously so a mid-turn crash or reboot doesn't
  lose the evidence. `python3 scripts/genie_cli.py --help`.
- **`app-rn/scripts/stress_switch.py`** repeatedly switches between two
  models to exercise the restart-on-switch path (see *Model switching* in
  [ANDROID-RN-APP.md](ANDROID-RN-APP.md)).

Both talk to `CliReceiver`, which only exists in debug builds — they need
`installDebug`, not a release APK.

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Permission denied` running a `.sh` script | `core.fileMode=false` — see §1 | `bash script.sh`, not `./script.sh` |
| `node not found` from `deploy.sh` | Node not installed / not on PATH | install Node 20.x |
| Model shows "No model bundle found" in-app after a push | adb-pushed files are `shell`-owned; the app is a different uid | the push scripts already `chmod` for this — re-run the push if it was interrupted |
| GENIE model fails to *load* with `err 1002` | DSP memory ceiling at this context length (QCS8550-specific) | see REPRODUCTION.md §7.2 — re-export at a shorter `--context-lengths` |
| App restarts when you switch models mid-chat | Expected — see *Model switching* in [ANDROID-RN-APP.md](ANDROID-RN-APP.md) | not a bug; JS resumes the same chat automatically |
| `qwen3_5_2b` gives odd answers on an image turn | known model-quality caveat, not a bug in the app | see `app-rn/handoffs/HANDOFF-qwen-vision.md` |

For anything not covered here: `app-rn/handoffs/HANDOFF-*.md` has the
root-cause history for every known issue, indexed from
[ANDROID-RN-APP.md](ANDROID-RN-APP.md#still-open).
