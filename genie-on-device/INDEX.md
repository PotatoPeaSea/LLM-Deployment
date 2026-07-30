# genie-on-device — Index

On-device LLM deployment to Qualcomm NPUs (QCS8550 / Snapdragon) via the
Qualcomm AI Hub **Genie/QNN** pipeline, now shipping as **GenieChatRN**: five
models (Llama-3.2-1B/3B, Qwen3-4B on GENIE/QNN; Qwen3.5-2B and Gemma 4 E2B on
GenieX/llama.cpp) across two on-device runtimes.

## Start here

| If you want to… | Read |
|-----------------|------|
| **Build/deploy the chat app** (quick start, no cloud compile needed) | [docs/USAGE.md](docs/USAGE.md) |
| **Understand the app's architecture** (5 models, 2 runtimes, debugging history) | [docs/ANDROID-RN-APP.md](docs/ANDROID-RN-APP.md), source in [app-rn/](app-rn) |
| **Understand the findings & gotchas** (compat checks, proxy devices, the memory ceiling) | [docs/README.md](docs/README.md) |
| **Reproduce the GENIE export pipeline step-by-step** (exact commands, what worked, what didn't + fixes) | [docs/REPRODUCTION.md](docs/REPRODUCTION.md) |
| **Understand how the export pipeline's pieces fit** (components, data flow, why context length matters) | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| **A condensed reusable runbook** (for a new model/chip) | [`.claude/skills/deploy-genie-llm/SKILL.md`](../.claude/skills/deploy-genie-llm/SKILL.md) |

> This branch (`release/app-rn`) is scoped to the chat app above. The
> standalone chatbot/voice-assistant CLI and its Whisper/Canary-Qwen
> handoffs, and the older Kotlin/Views reference app, aren't part of this
> release — see `debug`/`master` for those.

## Fast path (repeat the known-good setup)

```bash
cd genie-on-device
./scripts/01_build_image.sh  qwen3-4b
./scripts/02_configure_hub.sh <AI_HUB_API_TOKEN>
./scripts/03_list_devices.sh QCS8550
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
./scripts/05_deploy_and_run.sh qwen3_4b /mnt/sda1/matthew/SNPE/qairt/2.46.0.260424 v73 "What is the capital of France?"
./scripts/ask.sh "your next question"          # fast follow-up prompts
```

## Scripts

| Script | Role |
|--------|------|
| [scripts/00_env.sh](scripts/00_env.sh) | shared paths + `docker_run` mounts (sourced by all) |
| [scripts/01_build_image.sh](scripts/01_build_image.sh) | build the Py3.10 + qai-hub-models Docker image |
| [scripts/02_configure_hub.sh](scripts/02_configure_hub.sh) | store the AI Hub API token |
| [scripts/03_list_devices.sh](scripts/03_list_devices.sh) | confirm the chipset is a valid compile target |
| [scripts/04_export_model.sh](scripts/04_export_model.sh) | cloud-compile the model → bundle (use `--context-lengths`) |
| [scripts/05_deploy_and_run.sh](scripts/05_deploy_and_run.sh) | stage bundle + QAIRT runtime, adb push, run |
| [scripts/ask.sh](scripts/ask.sh) | send one prompt to the already-deployed model (no re-push) |

## The one thing to remember

The exported bundle is **only the model** — the runtime (`genie-t2t-run` + `.so`)
comes from a separate **QAIRT SDK**. And on memory-constrained chips like QCS8550,
a 4B model only *loads* at a **short context length** (512 here); the default 4096
fails at load with `err 1002`. See REPRODUCTION §7 for the full story.
