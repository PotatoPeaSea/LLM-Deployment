# Architecture — how the Genie-on-device pipeline fits together

This document explains **what each component is, what it does, and how the
components interact** to get an LLM running on a Qualcomm NPU. Where
`REPRODUCTION.md` is the "how to run it" narrative, this is the "how it works"
reference.

---

## 1. The big picture: three locations, two artifacts

The whole system spans **three physical locations** and produces **two artifacts**
that only meet on the device.

```
  ┌────────────────────────┐        ┌──────────────────────────┐        ┌───────────────────┐
  │   HOST (this machine)  │        │  QUALCOMM AI HUB (cloud)  │        │   DEVICE (QCS8550) │
  │  Docker + qai-hub-...  │        │      Workbench compile    │        │   Android + NPU    │
  └───────────┬────────────┘        └────────────┬─────────────┘        └─────────┬─────────┘
              │  1. upload AIMET checkpoint       │                                │
              │──────────────────────────────────▶│                                │
              │                                    │  2. compile → QNN context bins │
              │  3. download compiled bundle       │                                │
              │◀───────────────────────────────────│                                │
              │                                                                     │
              │  4. bundle (model)  +  QAIRT SDK (runtime)  ──── adb push ─────────▶│
              │                                                                     │  5. genie-t2t-run
              │                                                                     │     on Hexagon NPU
```

**Artifact A — the model bundle** (built in the cloud, step 2–3):
compiled QNN context binaries + config + tokenizer. Has *no* runtime.

**Artifact B — the QAIRT runtime** (obtained separately): the `genie-t2t-run`
binary and `.so` libraries for the device's CPU + DSP.

They are independent and are only combined at deploy time (step 4). Keeping them
straight is the key to understanding the system.

---

## 2. Component-by-component

### 2.1 The Docker toolchain (`docker/Dockerfile`, `scripts/00_env.sh`)
- **What:** Ubuntu 22.04 image with a Python 3.10 venv and `qai-hub-models`
  (plus an optional model extra baked in at build time via `--build-arg
  MODEL_EXTRA`).
- **Why:** `qai-hub-models` requires Python 3.10 and drags in heavy deps
  (torch/onnx/aimet). The container isolates all of that from the host's global
  Python.
- **How it connects:** `00_env.sh` defines a `docker_run` function that bind-mounts
  four host dirs into every container invocation, so the stateless container can
  read/write persistent state:
  | Host path | Container path | Purpose |
  |-----------|----------------|---------|
  | `workspace/qai_hub_config` | `/root/.qai_hub` | API token (`client.ini`) |
  | `workspace/qaihm_cache` | `/root/.qaihm` | cached 17.8 GB checkpoint downloads |
  | `workspace/output` | `/workspace/output` | compiled bundles land here |
  - Every numbered script `source`s `00_env.sh`, so paths/mounts are defined once.

### 2.2 `qai-hub-models` (inside the container)
- **What:** Qualcomm's Python package. Two roles here: (a) a **model registry**
  (each model ships `info.yaml`, `perf.yaml`, `README.md`, and an `export`
  entrypoint), and (b) a **cloud-compile client** (wraps `qai_hub.submit_compile_job`).
- **Critical property:** `qai-hub-models` itself has **no local compile** for
  LLMs — its `export` always submits to AI Hub (the host only downloads the
  checkpoint, uploads it, polls, downloads the result). This is a property of
  *this package*, NOT of the platform: the QAIRT SDK ships a separate, supported
  **offline** compiler — the Gen AI Builder (`qairt.gen_ai_api`) — that does the
  same compile/package step locally. See §6 "Offline alternative" below.
- **How it connects:** reads the token from `/root/.qai_hub/client.ini`, talks to
  `workbench.aihub.qualcomm.com`, writes the bundle to `/workspace/output`.

### 2.3 Qualcomm AI Hub Workbench (cloud)
- **What:** the compile service. Converts the AIMET-quantized PyTorch checkpoint
  → QNN IR → HTP-optimized **context binaries**, splitting the model into N parts
  and enabling weight sharing across them, tuned for a specific SoC.
- **Why we used cloud:** the `qai-hub-models` tutorial path routes here, and it
  needs no local quantized-ONNX input. It is NOT the only option — the same
  compile/package pipeline (graph conversion + split + weight-sharing + per-SoC
  HTP compile) is also available offline via the QAIRT Gen AI Builder (§6).
- **How it connects:** receives uploads from the host, exposes jobs at
  `workbench.aihub.qualcomm.com/jobs/<id>/`, returns the compiled bundle.

### 2.4 The model bundle (Artifact A) — output of the compile
Files and their runtime roles:
| File | Role |
|------|------|
| `part1..N_of_N.bin` | compiled QNN context binaries (the actual graphs + weights) |
| `genie_config.json` | **the wiring file** — tells `genie-t2t-run` the backend (`QnnHtp`), context size, sampler params, tokenizer path, and the list of `ctx-bins` |
| `htp_backend_ext_config.json` | HTP/SoC params: `{soc_model, dsp_arch, weight_sharing_enabled, perf_profile}` |
| `tokenizer.json`, `vocab.json`, `merges.txt`, `*.json` | tokenizer |
| `metadata.json` | provenance incl. `tool_versions.qairt` (the compile SDK version) |

`genie_config.json` is the linchpin at runtime — it's what you edit to point at
the context bins and set context size, and the object the runtime consumes.

### 2.5 The QAIRT SDK (Artifact B) — the runtime
- **What:** Qualcomm AI Runtime SDK. Provides the on-device execution layer that
  the bundle does *not* include.
- **The three pieces the deploy pulls out**, keyed to the device's Hexagon arch
  (v73 for QCS8550):
  | From SDK | Runs on | Provides |
  |----------|---------|----------|
  | `bin/aarch64-android/genie-t2t-run` | device CPU | the CLI driver / dialog loop |
  | `lib/aarch64-android/*.so` | device CPU | Genie + QNN host libs (`libGenie.so`, `libQnnHtp.so`, `libQnnHtpV73Stub.so`, `libQnnSystem.so`, …) |
  | `lib/hexagon-v73/unsigned/*.so` | device **DSP** | the HTP "skel" libs that actually execute on the NPU (`libQnnHtpV73Skel.so`, …) |
- **Version note:** must be ≥ the compile version, but in practice runtime
  version is forgiving (2.45/2.46/2.47 all worked identically). The `aarch64-android`
  vs `hexagon-vNN` split matters far more than the version.

### 2.6 The deploy staging + on-device layout (`scripts/05_deploy_and_run.sh`)
- **What it builds:** a single flat directory combining Artifact A + Artifact B:
  ```
  workspace/deploy/qwen3_4b/            (host staging)   →   /data/local/tmp/genie_qwen3_4b/  (device)
    part*.bin, genie_config.json, tokenizer…   (from bundle)
    *.so, genie-t2t-run                        (from QAIRT lib/bin aarch64-android)
    dsp/*.so                                   (from QAIRT lib/hexagon-v73/unsigned)
    prompt.txt                                 (generated: chat template, real newlines)
  ```
- **The two env vars that make it run** (the crux of on-device wiring):
  | Variable | Points at | So the loader finds |
  |----------|-----------|---------------------|
  | `LD_LIBRARY_PATH` | the deploy dir | the CPU-side `.so` libs |
  | `ADSP_LIBRARY_PATH` | `dsp/` subdir | the DSP-side hexagon skel libs |
- Without `ADSP_LIBRARY_PATH` pointed at the right hexagon-vNN skels, the NPU
  side can't load and you get failures at model init.

### 2.7 `genie-t2t-run` (on device)
- **What:** the runtime driver. Reads `genie_config.json`, loads each `ctx-bin`
  as a QNN context on the HTP, builds a "dialog", tokenizes the prompt, runs
  prefill + decode on the NPU, detokenizes, and streams text.
- **Interaction model:** one-shot and stateless per invocation (fresh dialog).
  `--prompt_file` (real newlines) is more robust than `-p` through adb quoting.
  `--save`/`--restore` persist dialog state for multi-turn; `--profile` emits KPIs.

### 2.8 `scripts/ask.sh` — the lightweight interaction path
- **What:** sends one prompt to the already-deployed bundle. Pushes only a
  regenerated `prompt.txt`, then invokes `genie-t2t-run` on device.
- **Why separate from `05`:** `05` re-pushes ~3.2 GB every call; `ask.sh` reuses
  the resident bundle, so iterating on prompts is fast.

---

## 3. End-to-end control flow (numbered scripts)

```
00_env.sh          — sourced by all; defines paths + docker_run (mounts)
   │
01_build_image.sh  — docker build (Py3.10 + qai-hub-models[extra])         [HOST]
   │
02_configure_hub.sh— write API token into workspace/qai_hub_config          [HOST→cloud auth]
   │
03_list_devices.sh — hub.get_devices() → confirm qualcomm-qcs8550-proxy     [HOST→cloud query]
   │
04_export_model.sh — qai-hub-models export … --context-lengths 512          [HOST→CLOUD compile]
   │                    └─ produces Artifact A in workspace/output/…
   │
05_deploy_and_run  — stage (A + QAIRT B) → adb push → genie-t2t-run         [HOST→DEVICE→NPU]
   │                    └─ LD_LIBRARY_PATH + ADSP_LIBRARY_PATH set here
   │
ask.sh             — push prompt.txt only → genie-t2t-run                    [HOST→DEVICE]
```

Data dependencies:
- `04` depends on `02` (token) and a valid compile target from `03`.
- `05` depends on `04`'s bundle **and** an external QAIRT SDK path (Artifact B),
  which is *not* produced by any script — you supply it.
- `ask.sh` depends on `05` having already pushed the bundle.

---

## 4. The memory constraint, architecturally

Why context length is the tuning knob (this is the crux of the whole exercise):

- The model is split into **N context binaries** (4 for Qwen3-4B). Genie loads
  **all N as live QNN contexts on the HTP simultaneously** — they are pipeline
  stages of one model, not alternatives.
- Each context reserves DSP memory that has a **context-length-dependent**
  component (KV cache, attention scratch, VTCM, I/O buffers). Weights are roughly
  fixed; the per-context overhead is what scales.
- The QCS8550's DSP has a smaller budget than the flagship chips this model
  targets. At 4096 context the cumulative reservation overflows before all 4
  contexts load → `err 1002` at context index 2. Lowering context length shrinks
  the per-context overhead, letting more (eventually all 4) fit.
- This is a **device/DSP** constraint, not a host-RAM or runtime-version one —
  which is why the fix lives at *compile* time (`--context-lengths`), not in the
  runtime or `genie_config` knobs.

```
context length ↓   ⇒   per-context DSP reservation ↓   ⇒   more contexts fit
   4096:  load 2/4 ✗        1024:  load 3/4 ✗          512:  load 4/4 ✓
```

---

## 5. Component interaction summary (one table)

| Component | Consumes | Produces | Talks to |
|-----------|----------|----------|----------|
| Docker toolchain | Dockerfile, mounts | running container | host FS |
| qai-hub-models | checkpoint, token | compile jobs, bundle | AI Hub cloud |
| AI Hub Workbench | uploaded checkpoint | QNN context binaries | qai-hub-models |
| Model bundle (A) | — | model + config + tokenizer | consumed by genie-t2t-run |
| QAIRT SDK (B) | — | genie-t2t-run + .so libs | consumed by deploy |
| 05_deploy_and_run | A + B + prompt | on-device staged dir | adb → device |
| genie-t2t-run | genie_config + ctx-bins + prompt | generated tokens | Hexagon NPU via QNN HTP |
| ask.sh | prompt string | on-device prompt.txt + run | adb → device |

---

## 6. Offline alternative — QAIRT Gen AI Builder (no cloud)

The cloud (AI Hub) path above is what this project uses, but it is **not the
only way** to produce the model bundle. The QAIRT SDK ships a first-party,
fully-offline compiler that replaces AI Hub Workbench (§2.3) for the
compile/package step:

- **Where:** `<QAIRT_SDK>/lib/python/qairt/gen_ai_api/` (present in the 2.46 and
  2.47 SDKs on this host), plus `libQnnGenAiTransformer*.so` and the
  `qnn-genai-transformer-composer` CLI.
- **What it does:** `gen_ai_builder_factory.create()` auto-detects the model
  architecture from `config.json` and `builder.build()` runs all 7 stages
  locally (AR/CL convert → split → MHA2SHA → ONNX→DLC → quantize → LoRA →
  context-binary gen), with a content-addressed build cache.
- **Input it needs:** a **quantized ONNX + `.encodings`** (the output of AIMET
  quantization, "step 1" of the QNN model-prep notebooks). This is the real
  gate for going offline — it is a different input than AI Hub consumes, and we
  do not currently have it for this model.
- **Preconfigured architectures:** `SupportedLLMs` covers Llama, `Qwen2ForCausalLM`,
  `Qwen3MoeForCausalLM` (MoE), Phi, Mistral, Baichuan, Jais, Plamo, Indus.
  **Note:** dense **`Qwen3ForCausalLM`** (our Qwen3-4B) is not in that list, so it
  would fall back to the generic `GenAIBuilderHTP` (unverified for this arch).

**Trade-off vs cloud:** offline removes the ~17.8 GB upload / cloud round-trip
and gives full local control (splits, AR, context lengths, targets) plus
air-gapped reproducibility — at the cost of having to obtain the quantized ONNX
yourself and (for dense Qwen3) relying on the generic builder. It was NOT used
for the working deployment documented here; it's the path to evaluate if a
no-cloud pipeline is required. (An earlier version of these docs incorrectly
claimed no supported offline path existed — corrected here.)
