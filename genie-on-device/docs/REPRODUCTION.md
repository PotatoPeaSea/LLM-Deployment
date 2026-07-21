# Reproduction Guide — Qwen3-4B on QCS8550 via Qualcomm Genie/QNN

This document is the complete, blow-by-blow record of getting **Qwen3-4B**
running on a **QCS8550** (codename `kalama`, Hexagon v73) Android device using
the Qualcomm AI Hub Genie/QNN NPU pipeline, plus everything that went wrong and
how to fix it. It is written so a competent engineer with the same hardware can
reproduce the result from scratch.

- **Result achieved:** Qwen3-4B (w4a16) generating on the QCS8550 NPU at
  **~19.9 tokens/sec**, 92 ms time-to-first-token, ~303 tok/s prefill.
- **The single non-obvious requirement:** the model only *loads* at
  **context length 512** on this chip. 4096 (default) and 1024 fail.
- **Companion docs:** `ARCHITECTURE.md` (how the pieces interact),
  `README.md` (findings summary + directory layout), and the
  `deploy-genie-llm` skill (condensed runbook).

---

## 0. Hardware / software baseline

| Thing | Value in this setup |
|-------|--------------------|
| Device | QCS8550 dev board, reports board `kalama`, model `Kalama_for_arm64` |
| Android | 13, ABI `arm64-v8a` |
| Device RAM | 15.7 GB total, ~10.5 GB available |
| Hexagon arch | **v73** (critical — determines which DSP skels to use) |
| Host OS | Ubuntu-class Linux, Docker 29 available, 60 GB RAM, 28 cores |
| Host Python | 3.12 system (unusable directly — see below) |
| QAIRT SDKs on host | 2.47.0 at `/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601`, 2.46.0 at `/mnt/sda1/matthew/SNPE/qairt/2.46.0.260424` |

Confirm your device the same way:
```bash
adb devices -l
adb shell getprop ro.product.board          # kalama
adb shell getprop ro.build.version.release  # 13
adb shell getprop ro.product.cpu.abi        # arm64-v8a
adb shell cat /proc/meminfo | grep MemTotal
```

---

## 1. Why an isolated environment (Docker)

`qai-hub-models` pins **Python 3.10** (`>=3.10,<3.14`), but more importantly you
do not want its heavy deps (torch, onnx, aimet, …) polluting the host's global
Python. Everything host-side therefore runs in a Docker container built from
`docker/Dockerfile` (Ubuntu 22.04 + Python 3.10 venv + `qai-hub-models`).

The container is stateless; all persistent state lives in bind-mounted
`workspace/` subdirs (config, download cache, outputs, deploy staging). This is
centralized in `scripts/00_env.sh` via the `docker_run` helper.

```bash
cd genie-on-device
./scripts/01_build_image.sh qwen3-4b   # builds genie-llm-toolchain:latest with the model extra
```

> **Gotcha — pip extra name.** The model *id* is `qwen3_4b` (underscores) but the
> pip *extra* is `qwen3-4b` (hyphens), and the two are not guaranteed to match.
> The extra `gemma-4-e4b-it` does not even exist despite the model being listed
> on the website. **Always** read `qai_hub_models/models/<id>/README.md` inside
> the installed package for the real extra name.

---

## 2. AI Hub account + token (compile is a cloud service)

The compile runs on Qualcomm's servers (AI Hub Workbench). You need an account
and API token from https://aihub.qualcomm.com → Account → Settings → API Token.

```bash
./scripts/02_configure_hub.sh <YOUR_API_TOKEN>   # writes workspace/qai_hub_config/client.ini
```

The token is stored in the bind-mounted config dir so it persists across runs.

---

## 3. Confirm the chipset is a valid compile target

QCS8550 is **not** in Qwen3-4B's `perf.yaml` `supported_chipsets` (that list only
covers flagships AI Hub has published benchmarks for: 8 Elite, 8 Elite Gen 5,
X Elite, X2 Elite, QCS9075). That does **not** mean it can't compile — it can.
Verify against the live device catalog:

```bash
./scripts/03_list_devices.sh QCS8550
```
Yields `QCS8550 (Proxy)` with attributes including:
```
chipset:qualcomm-qcs8550-proxy   hexagon:v73   framework:qnn
htp-supports-fp16:true           htp-supports-weight-sharing:true
```
- **"Proxy"** = AI Hub compiles for that architecture but has no physical device
  in its farm, so it can't auto-profile/inference-test. Use
  `--skip-profiling --skip-inferencing` when exporting.
- Note **`hexagon:v73`** — you will need the matching `hexagon-v73` DSP skels at
  runtime.

---

## 4. Compile the model (cloud) — WITH the context-length fix

```bash
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
```

What this does under the hood (`qai-hub-models export`):
1. Downloads the AIMET-quantized checkpoint (`qwen3_4b_w4a16.zip`, **17.8 GB**).
2. Splits/uploads it as 4 parts (`Qwen3_4B_Part{1..4}_Of_4_w4a16.aimet.zip`).
3. Submits compile jobs to `workbench.aihub.qualcomm.com` (visible as
   `https://workbench.aihub.qualcomm.com/jobs/<id>/`).
4. Downloads the compiled bundle into
   `workspace/output/qwen3_4b/qwen3_4b-geniex_qairt-w4a16-qualcomm_qcs8550_proxy/`.

The bundle contains **only the model**:
```
part1_of_4.bin ... part4_of_4.bin   # compiled QNN context binaries (~2.9 GB)
genie_config.json                   # runtime wiring (backend, sampler, context size)
htp_backend_ext_config.json         # {soc_model:43, dsp_arch:v73, weight_sharing_enabled:true}
tokenizer.json / vocab.json / merges.txt / *.json
metadata.json                       # includes tool_versions.qairt (e.g. 2.45.0...)
```
There is **no** `genie-t2t-run` and **no** `.so` here. That is expected.

Timing: ~25 min download (first time) + ~10 min upload + ~30–40 min cloud
compile + ~5 min result download ≈ ~1 hr. The `workspace/qaihm_cache` mount
caches the 17.8 GB checkpoint so *re-compiles* (e.g. trying another context
length) skip the download.

> **Why `--context-lengths 512` and not the default?** See §7. Short version:
> the default 4096 compiles fine but won't *load* on QCS8550.

---

## 5. Assemble the runtime and deploy

Runtime libraries come from a QAIRT SDK, not the export. `05_deploy_and_run.sh`
takes the SDK path and hexagon arch, assembles a self-contained staging dir, and
pushes+runs:

```bash
./scripts/05_deploy_and_run.sh \
    qwen3_4b \
    /mnt/sda1/matthew/SNPE/qairt/2.46.0.260424 \
    v73 \
    "What is the capital of France?"
```

It:
1. Copies the bundle + `lib/aarch64-android/*.so` + `bin/aarch64-android/genie-t2t-run`
   + `lib/hexagon-v73/unsigned/*.so` (into `dsp/`) into `workspace/deploy/qwen3_4b/`.
2. Writes `prompt.txt` in the **Qwen3 chat template with real newlines**
   (auto-wraps a plain prompt).
3. `adb push` the ~3.2 GB staging dir to `/data/local/tmp/genie_qwen3_4b`.
4. Runs on device with:
   ```bash
   export LD_LIBRARY_PATH=$PWD                # aarch64-android libs
   export ADSP_LIBRARY_PATH=$PWD/dsp          # hexagon-v73 skels
   ./genie-t2t-run -c genie_config.json --prompt_file prompt.txt
   ```

### What a successful run prints
```
Using libGenie.so version 1.18.0
[INFO]  "Using create From Binary"
[INFO]  "Allocated total size = 78774784 across 8 buffers"
[PROMPT]: <|im_start|>system\n...assistant\n
[BEGIN]: <think> ... </think>
The capital of France is **Paris**. ...[END]
```

---

## 6. Interacting afterward + performance

Once deployed, use the lightweight wrapper (pushes only the prompt, reuses the
on-device 3 GB bundle):
```bash
./scripts/ask.sh "In one sentence, what is a transformer?"
./scripts/ask.sh "Write a haiku about the ocean" --no-think   # skip <think> block
```
Notes:
- Each `genie-t2t-run` call is **stateless** (fresh dialog). Use `--save PATH` /
  `--restore PATH` for multi-turn memory.
- Prompt + response must fit in the **512-token** window.

Get KPIs by adding `--profile prof.json`, then read `token-generation-rate`,
`time-to-first-token` (µs), `prompt-processing-rate`, `init-time`. Measured:

| Metric | Value |
|--------|-------|
| Token generation | **19.9 tok/s** |
| Time to first token (28-tok prompt) | 92.5 ms |
| Prefill rate | 303 tok/s |
| Init/load | ~1.1 s |

For reference AI Hub lists Qwen3-4B at ~17.5 tok/s on Snapdragon X Elite, so
~19.9 tok/s on QCS8550 is a strong result.

---

## 7. What DIDN'T work — failures, causes, fixes

### 7.1 Wrong model in the original request (Gemma-4-E4B-it)
- **Symptom:** the requested model + tutorial were incompatible; the pip extra
  `gemma-4-e4b-it` didn't exist.
- **Cause:** `gemma_4_e4b_it/info.yaml` has `genie_compatible: false`,
  `geniex_llamacpp_compatible: true`. It is distributed only as a llama.cpp GGUF
  (`google/gemma-4-E4B-it-qat-q4_0-gguf`); the genie/QNN pipeline cannot run it,
  regardless of chipset.
- **Fix:** always check `info.yaml` `llm_details` first. Switched to `qwen3_4b`
  (`genie_compatible: true`).

### 7.2 `err 1002` — model fails to load on device (THE big one)
- **Symptom:**
  ```
  [ERROR] "Could not create context from binary for context index = 2 : err 1002"
  [ERROR] "Create From Binary FAILED!"
  Failure to initialize model.
  ```
- **Cause:** The 4-part model must have all 4 context binaries resident on the
  HTP/DSP at once. Each context reserves DSP memory (VTCM / graph scratch / I/O
  buffers) that **scales with context length**. QCS8550's DSP budget is smaller
  than the flagships this model targets, so it runs out partway through loading.
- **Proof it's memory (not something else):** the failure index moved
  predictably as context length dropped:

  | Context length | Buffer alloc reported | Fails at | Contexts loaded |
  |----------------|----------------------|----------|-----------------|
  | 4096 (default) | 344 MB | idx 2 | 2 / 4 |
  | 1024 | 116 MB | idx 3 | 3 / 4 |
  | **512** | **79 MB** | — | **4 / 4 ✅** |

- **Fix:** re-export with a smaller `--context-lengths` (512 here). If even a
  short context won't fit, use a smaller model (e.g. `qwen3_1_7b`).

### 7.3 Dead-ends that looked plausible but were NOT the cause
Documented so you don't repeat the wasted effort:

| Hypothesis | How it was tested | Result |
|-----------|-------------------|--------|
| QAIRT runtime version mismatch (2.47 runtime vs 2.45 compile) | Re-ran the 4096 bundle with the 2.46 SDK (closest to 2.45) | **Identical** failure at idx 2. Ruled out. All of 2.45/2.46/2.47 ship `libGenie.so 1.18.0`. |
| `spill-fill-bufsize: 0` disabling weight sharing | Set it to the largest bin size (~1 GB) on-device and re-ran | No effect; same idx-3 failure. |
| `use-mmap: true` exhausting DSP address space | Set `use-mmap: false` on-device and re-ran | Byte-identical result → not a mapping issue. |
| Getting more diagnostic detail | `genie-t2t-run --log verbose`; `adb shell dmesg` | verbose didn't propagate to backend; dmesg needs root. `err 1002` stays opaque — don't over-invest, just cut context length. |

Note: `err 1002` is **not** the "incompatible binaries" code (that is 2008 in
the SNPE/QNN enum), which is another reason version-matching was a red herring.

### 7.4 Checkpoint re-downloaded on the first re-compile
- **Symptom:** the 1024 recompile re-downloaded the full 17.8 GB.
- **Cause:** the original 4096 export ran *before* the `~/.qaihm` cache mount was
  added, so its download went to the container's ephemeral layer.
- **Fix:** `00_env.sh` now bind-mounts `workspace/qaihm_cache` → `/root/.qaihm`.
  The 512 recompile reused the cache and skipped the download.

### 7.5 Device dropped off adb mid-process
- **Symptom:** `adb devices` shows nothing or a `?` serial.
- **Cause:** QCS8550 dev boards are flaky over USB.
- **Fix:** re-seat the cable / try another port, `adb kill-server && adb start-server`,
  and accept the on-device "Allow USB debugging?" prompt. A `?` serial =
  unauthorized/marginal connection.

---

## 8. Fast path (if you're just repeating the exact working setup)

```bash
cd genie-on-device
./scripts/01_build_image.sh qwen3-4b
./scripts/02_configure_hub.sh <API_TOKEN>
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
./scripts/05_deploy_and_run.sh qwen3_4b /mnt/sda1/matthew/SNPE/qairt/2.46.0.260424 v73 "What is the capital of France?"
# then, for more prompts:
./scripts/ask.sh "your question"
```

## 9. Known limits / next steps
- **512-token context** only — fine for Q&A, not long documents. 4B at >512 does
  not fit QCS8550's DSP.
- For a longer context window, deploy a **smaller model** (`qwen3_1_7b`) the same
  way — it has smaller/fewer context binaries.
- `genie-t2t-run` is one-shot; for a persistent multi-turn session use
  `--save`/`--restore`, `genie-app` with a script, or the Genie C++ API /
  Android ChatApp from the ai-hub-apps repo.
