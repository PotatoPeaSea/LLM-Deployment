---
name: deploy-genie-llm
description: >
  Use when deploying an LLM from Qualcomm AI Hub Models to an on-device Qualcomm
  Android/Snapdragon target (e.g. QCS8550, 8 Elite, X Elite) via the Genie/QNN
  NPU runtime (genie-t2t-run). Covers: model compatibility checks, choosing a
  compile target (incl. "proxy" devices for unsupported chipsets), cloud compile
  with qai-hub-models, assembling the QAIRT runtime, adb deploy, and the
  memory/context-length troubleshooting needed on memory-constrained chips.
  Triggers: "run/deploy <model> on <Qualcomm device/QCS8550/Snapdragon> NPU",
  "genie-t2t-run", "llm_on_genie", "err 1002", "QNN context binary".
---

# Deploy an AI Hub LLM to a Qualcomm device via Genie/QNN

This is a runbook distilled from a real QCS8550 deployment. Reference
implementation: `genie-on-device/` (Docker toolchain + numbered scripts).
Full write-ups: `genie-on-device/docs/REPRODUCTION.md` (step-by-step) and
`genie-on-device/docs/ARCHITECTURE.md` (how the pieces fit).

## Mental model (read first)

There are **two halves**, and conflating them causes most confusion:

1. **The model** — compiled into QNN context binaries (`part*.bin`) +
   `genie_config.json` + tokenizer. Two ways to produce it:
   (a) **cloud** via `qai-hub-models export` (what this runbook uses) — the
   `qai-hub-models` package has no local-compile path, so `export` always
   submits to AI Hub; your machine only downloads the checkpoint, uploads it,
   waits, downloads the result. (b) **offline** via the QAIRT SDK's Gen AI
   Builder (`qairt.gen_ai_api`, `builder.build()`) — a supported local compiler,
   but it needs a quantized ONNX + `.encodings` as input (AIMET step-1 output).
   Use cloud unless you specifically need an air-gapped/no-cloud build.
2. **The runtime** — the `genie-t2t-run` binary + `.so` libraries, which come
   from a separately-downloaded **QAIRT SDK**, NOT from the export. Both halves
   get pushed to the device together.

## Preconditions to verify before doing anything

1. **adb device online**: `adb devices` shows `device` (not `unauthorized`/`?`).
   QCS8550 dev boards are flaky over USB — a `?` serial means re-seat the cable
   / accept the debugging prompt.
2. **Model supports Genie.** In the installed package, read
   `qai_hub_models/models/<model_id>/info.yaml`:
   ```yaml
   llm_details:
     genie_compatible: true          # MUST be true for this pipeline
     geniex_qairt_compatible: true
   ```
   If `genie_compatible: false` (e.g. `gemma_4_e4b_it`), STOP — that model is
   llama.cpp/GGUF-only and needs a completely different runtime. Pick a
   genie-compatible model (e.g. `qwen3_4b`, `qwen3_1_7b`).
3. **The pip extra name ≠ model id.** The id uses underscores (`qwen3_4b`); the
   pip extra uses hyphens and may differ. Get the real one from
   `qai_hub_models/models/<model_id>/README.md`, not from the website.
4. **Target chipset is a valid compile target.** It does NOT need to be in the
   model's `perf.yaml` `supported_chipsets` (that list is only chips AI Hub has
   published profiling for). Confirm via the live catalog:
   ```python
   import qai_hub as hub
   for d in hub.get_devices():
       if 'qcs8550' in d.name.lower(): print(d.name, d.attributes)
   ```
   QCS8550 appears as `QCS8550 (Proxy)` with `chipset:qualcomm-qcs8550-proxy`,
   `hexagon:v73`. "Proxy" = compiles for the arch but has no hosted device for
   profiling, so pass `--skip-profiling --skip-inferencing`.
5. **A QAIRT SDK is available** (version >= the one AI Hub compiles with; the
   export prints `qairt: X.Y.Z...` at the end). Runtime version is forgiving in
   practice (see gotcha below). You need, for the device's Hexagon arch:
   - `bin/aarch64-android/genie-t2t-run`
   - `lib/aarch64-android/*.so`
   - `lib/hexagon-<vNN>/unsigned/*.so`  (v73 for QCS8550)

## Workflow

Use the isolated Docker toolchain so nothing hits the host's global Python
(qai-hub-models needs Python 3.10 specifically). The numbered scripts wrap each
step; env/mounts are centralized in `scripts/00_env.sh`.

```bash
cd genie-on-device
./scripts/01_build_image.sh  qwen3-4b                          # Py3.10 + qai-hub-models[extra]
./scripts/02_configure_hub.sh <AI_HUB_API_TOKEN>               # one-time
./scripts/03_list_devices.sh QCS8550                           # confirm compile target
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
./scripts/05_deploy_and_run.sh qwen3_4b <QAIRT_SDK_PATH> v73 "What is the capital of France?"
```

`05` assembles a self-contained staging dir (bundle + QAIRT libs + binary +
hexagon skels + a prompt.txt in the model's chat template), pushes it to
`/data/local/tmp/genie_<model>`, sets `LD_LIBRARY_PATH` (android libs) and
`ADSP_LIBRARY_PATH` (hexagon skels), and runs `genie-t2t-run --prompt_file`.

For subsequent prompts without re-pushing 3.5 GB, use `scripts/ask.sh "..."`
(pushes only prompt.txt, reuses the on-device bundle).

## The one troubleshooting axis that matters: context length vs DSP memory

A multi-GB model is split into N context binaries that must ALL load onto the
HTP/DSP at once. On memory-constrained chips the load fails with:
```
[ERROR] "Could not create context from binary for context index = K : err 1002"
```
This is a **DSP memory ceiling**, and per-context memory scales with context
length. **Lower `--context-lengths` and re-export.** Observed on QCS8550 with
Qwen3-4B: 4096 → dies at ctx idx 2; 1024 → dies at idx 3; **512 → loads all 4,
runs.** If even a short context won't fit, drop to a smaller model.

### Rule these out FIRST (they wasted hours — don't repeat that):
- **QAIRT runtime version is NOT usually the cause.** 2.45/2.46/2.47 behaved
  byte-identically (all ship `libGenie.so 1.18.0`). Don't chase version-matching
  for `err 1002` unless the message explicitly says "incompatible binaries"
  (that's error 2008, not 1002).
- **`genie_config.json` knobs don't fix it.** `spill-fill-bufsize` (even set to
  the largest bin) and `use-mmap: false` had zero effect on the load failure.
- The `err 1002` is opaque; `--log verbose` doesn't help and dmesg needs root.
  Don't over-diagnose — just reduce context length; the failure index moving as
  you shrink it confirms it's memory.

## Verify it actually ran (don't trust exit code alone)

Success looks like: `Using libGenie.so ...` → `Allocated total size = ...` →
`[PROMPT]:` → `[BEGIN]:` → answer → `[END]`. Get perf with
`--profile prof.json` then read `token-generation-rate` (tok/s),
`time-to-first-token` (µs), `prompt-processing-rate`. QCS8550 + Qwen3-4B@512
did ~19.9 tok/s, 92 ms TTFT.

## Gotchas checklist
- [ ] Model `genie_compatible: true`
- [ ] Right pip extra (from package README, not website)
- [ ] Compile target confirmed via `hub.get_devices()` (proxy is fine)
- [ ] `--skip-profiling --skip-inferencing` for proxy targets
- [ ] QAIRT runtime staged (bundle has NO runtime) with correct hexagon-vNN
- [ ] Prompt uses the model's exact chat template with REAL newlines
- [ ] Context length low enough to fit the DSP (start short on unknown chips)
