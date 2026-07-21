# HANDOFF — Offline compilation & on-device deployment of nvidia/canary-qwen-2.5b

**Written:** 2026-07-15. **Updated:** 2026-07-17 (spike #1 + encoder ONNX
export done, see §9; decoder extraction + Gen AI Builder investigation, see
§10). **For:** the next session (fresh context) picking up a new, harder
deployment. **Read this whole doc before starting**, and read §9/§10 first if
you're resuming — they correct assumptions made below and change the
recommended path for the decoder.

---

## 0. TL;DR / the ask

Deploy **`nvidia/canary-qwen-2.5b`** (a speech model) onto the QCS8550 Android
device, **compiled offline** (no AI Hub cloud), and run it as a voice assistant:

> Run in **transcription (ASR) mode** capturing/transcribing speech **until
> speech stops** (silence/VAD), then switch to **LLM mode** to **answer** based
> on the transcript.

This is **research-grade**, not a turnkey port. The text-LLM half is tractable
(we already have a working Qwen3 genie pipeline); the audio encoder half is the
harder part but is **substantially de-risked** by the decision below.

## DECISIONS (confirmed with user, 2026-07-15)
- ✅ **Running the audio encoder on the device's CPU or GPU is acceptable** — it
  does NOT have to run on the NPU. This removes the biggest unknown (compiling a
  FastConformer for QNN/HTP). Plan: **encoder on CPU/GPU via onnxruntime, LLM
  decoder on NPU via Genie.** Only the Qwen3-1.7B decoder needs QNN compilation.

---

## 1. What already exists (reuse this — do NOT rebuild)

A complete, working **text-LLM** on-device pipeline lives in this repo. It got
**Qwen3-4B running on the QCS8550 NPU at ~19.9 tok/s**. Read these first:

- `INDEX.md` — entry point, fast path, script table.
- `docs/README.md` — findings/gotchas (model compat, proxy devices, the memory
  ceiling).
- `docs/REPRODUCTION.md` — exact step-by-step + a full "what didn't work" catalog.
- `docs/ARCHITECTURE.md` — how the pieces fit; **§6 "Offline alternative"** is the
  key one for this handoff (the offline Gen AI Builder).
- `.claude/skills/deploy-genie-llm/SKILL.md` — condensed runbook.
- Project memory: `qcs8550-qwen3-4b-genie.md`.

### Reusable assets
| Asset | Path / value |
|-------|--------------|
| Docker toolchain (Py3.10 + qai-hub-models) | `docker/Dockerfile`, `genie-llm-toolchain:latest` |
| Numbered scripts | `scripts/00_env.sh` … `05_deploy_and_run.sh`, `ask.sh` |
| Device | QCS8550 `kalama`, Android 13, arm64-v8a, Hexagon **v73**, 15.7 GB RAM |
| QAIRT SDK 2.46 | `/mnt/sda1/matthew/SNPE/qairt/2.46.0.260424` |
| QAIRT SDK 2.47 | `/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601` |
| On-device deploy dir pattern | `/data/local/tmp/genie_<model>` (LD_LIBRARY_PATH=dir, ADSP_LIBRARY_PATH=dir/dsp) |
| AI Hub token | already configured in `workspace/qai_hub_config/client.ini` |

### Hard-won lessons that carry over
- The exported bundle has **no runtime** — `genie-t2t-run` + `.so` come from the
  QAIRT SDK, pushed alongside. hexagon-**v73** skels for QCS8550.
- **DSP memory ceiling**: multi-GB models only load at **short context length**
  (Qwen3-4B needed 512; 4096/1024 → `err 1002`). Canary's Qwen3-1.7B is smaller,
  so it may tolerate a longer context — but start short.
- `err 1002` = DSP memory, not version/config. Don't chase QAIRT version,
  spill-fill, or mmap (all ruled out).

---

## 2. The offline compiler (the "offline" in the ask)

Qualcomm's **Gen AI Builder** (`qairt.gen_ai_api`) is a supported **offline** LLM
compiler shipped in the QAIRT SDK — present locally in BOTH 2.46 and 2.47 at
`<QAIRT>/lib/python/qairt/gen_ai_api/`. It replaces the AI Hub cloud step.

- Entry: `gen_ai_builder_factory.create()` (auto-detects arch from `config.json`)
  → `builder.build()` runs all 7 stages locally → GenAIContainer.
- **Input required:** a **quantized ONNX + `.encodings`** (AIMET step-1 output).
  This is the gate — you must produce it; the builder does not quantize from a
  raw HF checkpoint.
- **LoRA is supported** (`lora_config`) — directly relevant, see §4.
- Preconfigured `SupportedLLMs`: Llama, `Qwen2ForCausalLM`, `Qwen3MoeForCausalLM`,
  Phi, Mistral, Baichuan, Jais, Plamo, Indus. **Dense `Qwen3ForCausalLM` is NOT
  listed** → falls back to generic `GenAIBuilderHTP` (unverified). Canary's
  decoder is Qwen3-1.7B **dense**, so expect the generic-builder path.
- Reference examples in SDK: `examples/QAIRT/python/llm_on_device_inference.py`,
  `lora_on_device_inference.py`, `speculative_decoding_tutorial.py`.
- Full write-up: `docs/ARCHITECTURE.md` §6.

---

## 3. The model — accurate technical facts (verified from the HF card)

`nvidia/canary-qwen-2.5b` is a **Speech-Augmented Language Model (SALM)**, NOT a
plain text LLM:

| Property | Value |
|----------|-------|
| Framework | **NVIDIA NeMo** ≥2.5.0, PyTorch ≥2.6 (`from nemo.collections.speechlm2.models import SALM`) |
| Speech encoder | **FastConformer** (from `nvidia/canary-1b-flash`) |
| LLM decoder | **Qwen3-1.7B** (dense) |
| Glue | audio encoder output → **linear projection** into LLM embedding space; **LoRA** on the LLM |
| Format | **safetensors**, BF16, ~3B params total |
| Audio input | 16 kHz mono `.wav`/`.flac` |
| **ASR mode** | transcription only (LoRA **enabled**) — "does not retain LLM reasoning" |
| **LLM mode** | base Qwen reasoning (LoRA **disabled** via `disable_adapter()` context manager) |
| Edge/ONNX support | **None documented** — it's a GPU model. Expect to blaze the trail. |

So there are **three** compute pieces: (A) FastConformer encoder, (B) linear
projection, (C) Qwen3-1.7B decoder + LoRA. Genie/QNN only natively handles (C).

---

## 4. Proposed architecture mapping (the promising idea)

The two modes map onto capabilities Genie already has — this is the insight that
makes the project plausible:

- **Mode switch = LoRA on/off.** ASR mode = Qwen3-1.7B **with** the Canary LoRA
  adapter; LLM mode = **without** it. Genie supports LoRA: `genie-t2t-run --lora
  ADAPTER,...` and the Gen AI Builder builds LoRA graphs (`lora_config`). So a
  single compiled base + a LoRA adapter could serve both modes.
- **Audio → LLM = embedding injection.** The encoder+projection produce
  embeddings in the LLM's embedding space; the SALM feeds those as *input
  embeddings*. `genie-t2t-run` has **`--embedding_file PATH[,TYPE,SCALE,OFFSET]`**
  and `--embedding_table` — i.e. Genie can take embeddings directly instead of
  token IDs. That is the seam to feed audio-derived embeddings into the on-device
  LLM.

### Candidate on-device pipeline
```
mic → 16kHz PCM → [VAD: detect speech / silence]
   → FastConformer encoder (CPU/GPU via onnxruntime)  ──► audio embeddings
   → linear projection                                 ──► LLM-space embeddings
   → genie-t2t-run --lora canary_asr --embedding_file <emb>   (ASR mode → transcript)
   → [on silence] feed transcript as text to
   → genie-t2t-run (no lora)                            (LLM mode → answer)
```

**Encoder (A)+(B) — CONFIRMED plan (per §0 decision):** run the FastConformer +
projection on the device's **CPU/GPU via onnxruntime**, NOT the NPU. Steps:
1. **NeMo → ONNX export** of the encoder (NeMo has `.export()` for Conformer
   encoders). The projection is a tiny matmul — fold into the ONNX or do in numpy.
2. Ship an `onnxruntime` aarch64-android build to the device; run the encoder ONNX
   on **CPU** (or Adreno **GPU** via ORT's OpenCL/GPU EP if the encoder is a
   bottleneck). **No QNN/HTP compile of the Conformer is needed** — that was the
   biggest risk and it's now off the table.
3. Encoder outputs → projection → embeddings → feed to the on-NPU LLM (§4 seam).

---

## 5. Why this is much harder than Qwen3-4B (set expectations)
- **Genie is text-to-text.** The audio encoder is entirely outside the pipeline
  we built. New tooling/runtime for (A)+(B) is required.
- **NeMo, not HF transformers.** Loading/exporting needs the NeMo toolkit; the
  weights are a SALM, not a standard `AutoModel`. Extracting the three sub-modules
  cleanly (encoder, projection, LLM+LoRA) is itself work.
- **Offline quantized-ONNX input gate** (from §2) applies to the LLM decoder, and
  there's no precedent for quantizing/compiling the Canary encoder for QNN.
- **Dense Qwen3** has no preconfigured Gen AI Builder (generic path, unverified).
- **VAD / turn-taking / audio capture** on Android is its own mini-project
  (`AudioRecord`, a VAD like Silero/webrtcvad, buffering).

---

## 6. Concrete first steps (do these as small spikes, in order)

Do NOT attempt the whole pipeline at once. Sequence to de-risk cheaply:

1. **Load & understand the model (host, GPU).** `pip install nemo_toolkit`,
   load the SALM, run the model-card ASR example on a sample wav, then the
   `disable_adapter()` LLM example. Confirm you can drive both modes and inspect
   the three sub-modules (encoder, projection, LLM, LoRA weights).
2. ~~**Prove the LLM half on-device FIRST.**~~ **CORRECTED 2026-07-17:**
   `qwen3_1_7b` does **NOT** exist in `qai-hub-models` (checked the installed
   0.57.3 catalog — only `qwen3_4b`, `qwen3_4b_instruct_2507`, `qwen3_8b`,
   `qwen3_vl_4b_instruct`). There is no cloud "quick win" for the exact Canary
   decoder. Getting the real Qwen3-1.7B decoder on-device requires the offline
   Gen AI Builder path (step 4 below / §2) regardless of whether offline is a
   hard requirement — the cloud pipeline simply has no recipe for this size.
   See §9 for the corrected plan.
3. **Encoder export spike.** Try `nemo` `.export()` on the FastConformer encoder
   → ONNX. See if it exports cleanly and what the input/output tensor shapes are.
   Run the ONNX encoder on host with onnxruntime; verify transcription embeddings.
4. **Offline compile spike (Gen AI Builder).** Point `gen_ai_builder_factory`
   at the Qwen3-1.7B `config.json` in the container; see whether the generic
   builder accepts dense Qwen3 and exactly what quantized-ONNX+encodings input it
   demands. This tells you the real cost of the "offline" requirement.
5. **Embedding-injection spike.** Confirm `genie-t2t-run --embedding_file` works
   at all with a hand-crafted embedding on the already-deployed Qwen3, before
   wiring the encoder to it.
6. Only after 1–5: assemble the encoder(QNN/ORT) + projection + LoRA + Genie +
   VAD into the voice loop.

---

## 7. Key risks / open questions (flag these to the user early)
- ~~Can the FastConformer encoder be compiled for QNN?~~ **RETIRED** — §0 decision:
  encoder runs on CPU/GPU via onnxruntime. No longer a blocker.
- **NeMo → ONNX export of the encoder may need effort.** SALM/speechlm2 export is
  less trodden than plain Conformer-CTC export; the encoder may need to be pulled
  out of the SALM wrapper first. Verify shapes/opset. **Now the biggest unknown.**
- **Does the Canary LoRA adapter survive the AIMET-quantize → Gen AI Builder path,
  and does Genie's `--lora` accept it?** Unproven for this model. Fallback: compile
  ASR-mode (LoRA-merged) and LLM-mode as two separate bundles.
- **Embedding scale/offset:** `--embedding_file` needs correct quantization
  params (TYPE,SCALE,OFFSET) matching the LLM's input embedding encodings; fiddly.
  Validate the embedding seam (spike #5) before wiring the real encoder.
- **Dense-Qwen3 generic builder** may produce a non-working/unoptimized bundle
  (offline path). The cloud pipeline (`qwen3_1_7b` in qai-hub-models) is the safe
  fallback for the LLM if offline compile stalls.
- Realistic end-state: **encoder on CPU/GPU + Qwen3-1.7B decoder on NPU** — a
  hybrid, not a fully-NPU SALM. That is the target, and it's acceptable.

---

## 8. Suggested first message to the user when resuming
Encoder-on-CPU/GPU is already confirmed acceptable (§0), so scope is set. One
open question remains: **is offline compilation a hard requirement for the LLM,
or was it only about avoiding cloud?** The existing cloud pipeline (`qwen3_1_7b`
in qai-hub-models) would get the 1.7B decoder on-device fastest; the offline Gen
AI Builder path is more work (quantized-ONNX gate + generic dense-Qwen3 builder).
Recommended plan: do **spike #2 (deploy Qwen3-1.7B on device via existing
pipeline)** immediately as a concrete win + LLM baseline, then **spike #1 (load
SALM in NeMo)** and **spike #3 (NeMo→ONNX encoder)** for the audio path, then #5
(embedding seam), then assemble the VAD voice loop.

---

## 9. Session update (2026-07-17) — spike #1 done, encoder ONNX export done

Ran on the **host CPU** (this machine has no discrete GPU — only Intel iGPU —
so "host, GPU" in §6 step 1 is aspirational; CPU works fine for loading/
inspecting/exporting, just slow for generation). Two blockers found first, then
good progress on the two spikes that don't need the device:

### Blockers found (before starting)
- **No adb device was connected** this session. Everything device-side (spikes
  2, 5's on-device half, 6) is blocked until the QCS8550 is reattached — this
  didn't stop spikes 1/3 since neither needs the device.
- **`qwen3_1_7b` is not in `qai-hub-models`** (see the correction in §6 step 2
  above). The "quick win" cloud deploy for the real Canary decoder doesn't
  exist; the offline Gen AI Builder path is now load-bearing for the LLM half
  too, not just "the thing to evaluate if cloud is disallowed."

### Environment
No sudo on this host (`apt install python3.12-venv` needs a password we don't
have), so NeMo work runs in a **separate Docker container**, not the existing
`genie-llm-toolchain` image:
```
docker run -d --name canary-nemo-dev \
  -v <workdir>/pipcache:/root/.cache/pip \
  -v <workdir>/hf_cache:/root/.cache/huggingface \
  -v <workdir>:/work \
  python:3.12-slim sleep infinity
# apt-get install git ffmpeg libsndfile1 build-essential
# pip install 'nemo_toolkit[all]==2.7.3'
```
Working tree for this is `/mnt/ssd/smart/canary-qwen-work/` (**outside** this
git repo — the repo has no `.gitignore` and `workspace/` is committed as-is, so
heavy scratch artifacts (venvs, HF checkpoints, ONNX exports) were kept out of
it deliberately). Recreate the container from the recipe above if it's gone;
nothing here depends on container state surviving.

**Two dependency landmines, both fixed:**
- `python:3.10-slim` fails to even import `nemo.collections.speechlm2` —
  `ear_tts_vae_codec.py` uses `Concatenate[int, ...]` which needs Python 3.11+
  typing. **Use `python:3.11` or `3.12`, not `3.10`**, despite NeMo's own
  install docs implying 3.10 is fine.
- `nvidia-resiliency-ext` pulled in at `0.5.0` by default but `megatron.core`
  asserts `>=0.6.0` at import time (`AssertionError: Minimum required
  nvidia-resiliency-ext package version is 0.6.0`). Fix: `pip install -U
  nvidia-resiliency-ext==0.6.0` after the main install. (A `protobuf`
  version warning also appears — harmless for this workflow, ignored.)

### Spike #1 result: model loads and both modes work, on CPU
`SALM.from_pretrained("nvidia/canary-qwen-2.5b")` loads cleanly. Top-level
structure, confirmed by `named_children()`:

| Submodule | Type | Params |
|---|---|---|
| `llm` | `PeftModelForCausalLM` (base: `Qwen/Qwen3-1.7B`) | 1,746,265,088 |
| `embed_tokens` | `Embedding` (moved out of `llm.model.embed_tokens`) | 311,164,928 |
| `perception` | `AudioPerceptionModule` | 813,083,648 |
| `perception.preprocessor` | `AudioToMelSpectrogramPreprocessor` | — |
| `perception.encoder` | `ConformerEncoder` (FastConformer) | — |
| `perception.modality_adapter` | `IdentityConnector` (no-op) | — |
| `perception.proj` | `Linear` (d_model → 2048, matches LLM hidden size) | — |

**Exact LoRA config** (from the loaded adapter, useful for the Gen AI Builder's
`lora_config` later): `r=128, lora_alpha=256, target_modules=['q_proj',
'v_proj'], lora_dropout=0.01, bias='none'`.

Ran both documented modes on the HF widget sample
(`https://cdn-media.huggingface.co/speech_samples/sample1.flac`, 16kHz/13.7s):
- **ASR mode** (`model.generate(prompts=[[{"content": f"Transcribe the
  following: {model.audio_locator_tag}", "audio": [...]}]])`): correct
  transcript in 9.0s on CPU.
- **LLM mode** (same call wrapped in `with model.llm.disable_adapter():`,
  text-only prompt + transcript): correctly used Qwen3's own reasoning
  (visible `<think>` block) to answer about the transcript, in 15.0s on CPU.

Both match the HF model card's documented usage exactly — no surprises here,
the SALM API works as advertised.

**Embedding-injection mechanism, confirmed by reading `salm.py` source**
(`nemo/collections/speechlm2/models/salm.py`, function
`replace_placeholders_and_build_targets` + `SALM.generate`): it's exactly what
§4's "promising idea" hypothesized. The text prompt is tokenized, the single
`<|audioplaceholder|>` token's position is located, `embed_tokens(text_ids)`
gives text embeddings, `perception(audio)` gives audio embeddings **already in
the LLM's 2048-dim hidden space**, and the placeholder position is spliced out
and replaced in-place with the audio embedding sequence — producing one
`inputs_embeds` tensor fed to `llm.generate(inputs_embeds=..., attention_mask=...)`
(standard HF generate call, no custom decoding logic). Important detail:
`embed_tokens` **is** `llm.model.embed_tokens` (moved out, not copied) — so
there is only one token embedding table, shared between text-only and
audio-mixed generation. This maps directly onto Genie's `--embedding_file`
seam as hypothesized; spike #5 (validate `--embedding_file` against the
already-deployed Qwen3 on-device) is still the right next on-device step once
the LoRA/scale-offset details are worked out.

### Spike #3 result: encoder ONNX export WORKS — the "biggest unknown" is retired
`AudioPerceptionModule` (in `nemo/collections/speechlm2/modules/perception.py`)
already implements NeMo's `Exportable` mixin with `input_example()`/
`input_types`/`output_types` — it's designed to be exportable, just not a
well-trodden path yet. Two real bugs hit, both worked around **without editing
the installed NeMo package** (monkeypatches in the export script):

1. `AudioPerceptionModule` doesn't override `disabled_deployment_input_names`
   to drop unused ports the way NeMo's plain `ASRModel` export path does (it
   forwards to `encoder.disabled_deployment_input_names`, which only handles
   streaming-cache ports, not this). Exporting with the default `input_example()`
   (which passes `None` for `processed_signal`/`processed_signal_length`)
   makes `get_dynamic_axes()` build an axis spec for a port with no example
   value → `RuntimeError: Dynamic shape axis should be no more than the shape
   dimension for processed_signal_dynamic_axes_2`. Fix: monkeypatch
   `AudioPerceptionModule.disabled_deployment_input_names` to a property
   returning the *unused* port names for whichever input path you're exporting
   (see below — we ended up disabling `input_signal`/`input_signal_length`
   instead, for a different reason).
2. `torch.onnx`'s TorchScript exporter **cannot trace `aten::stft` with
   `return_complex=True`** (`SymbolicValueError: STFT does not currently
   support complex types`), which the mel-spectrogram preprocessor
   (`AudioToMelSpectrogramPreprocessor` → `FilterbankFeatures.stft`) uses
   internally. This is a known torch.onnx gap, not specific to this model.
   **Fix: don't export the STFT/mel stage.** Call
   `perception.preprocessor(input_signal, length)` yourself to get
   `processed_signal` (mel features), then export `perception` starting from
   `processed_signal`/`processed_signal_length` (disabling `input_signal`/
   `input_signal_length` instead via the same monkeypatch from bug 1). Mel/STFT
   extraction is simple, standard, and should run outside the ONNX graph
   on-device anyway (plain numpy/torchaudio-equivalent code, not a NPU/QNN
   concern).

With that split, `perception.export("perception.onnx", input_example=(None,
None, processed_signal, processed_signal_length), onnx_opset_version=17)`
**succeeds**, producing a 3.1 GB ONNX model (fp32, external-data format — one
file per large weight tensor, standard for >2GB protobufs) covering
FastConformer encoder + `IdentityConnector` + the final `Linear` projection
into LLM space.

**Verified numerically**: ran the same `sample1.flac` through both the
PyTorch module and an `onnxruntime.InferenceSession` (`CPUExecutionProvider`)
starting from the same `processed_signal`. Output shapes match
(`(1, 172, 2048)`), and **max abs diff = 7.5e-5, mean abs diff = 2e-6** — i.e.
floating-point noise, not a real discrepancy. The ONNX encoder is numerically
equivalent to the PyTorch one.

**What this means:** §5's "NeMo → ONNX export of the encoder may need effort"
risk is retired — it took two known, well-understood workarounds (a missing
`disabled_deployment_input_names` override, and the standard stft/complex
ONNX limitation), not a research problem. §7's biggest-unknown flag should be
struck.

### Still not done / next steps
- **Mel/STFT preprocessing on-device.** Needs a plain (non-ONNX) mel-spectrogram
  implementation for the Android/CPU side — check what `AudioToMelSpectrogramPreprocessor`'s
  exact filterbank params are (window, hop, n_mels, etc. — read off
  `perception.preprocessor.featurizer` config) and either reimplement in
  C++/Java for the device or find an onnxruntime/other library that covers it
  cleanly. Not attempted yet.
- **Quantizing the encoder ONNX for CPU/GPU speed** was NOT attempted — §0's
  decision was CPU/GPU via onnxruntime, not NPU, so fp32 (or a plain ORT
  dynamic/static quant pass) is likely good enough; only worth revisiting if
  on-device latency is a problem.
- **Spike #4 (offline Gen AI Builder on the real Qwen3-1.7B decoder)** not yet
  attempted this session. Now more important than originally scoped, since
  spike #2's cloud shortcut doesn't exist (see correction above). The base
  `genie-llm-toolchain` Docker image (used for the existing Qwen3-4B pipeline)
  already has `aimet-onnx` installed, which is encouraging for producing the
  quantized-ONNX+`.encodings` input the builder needs — not yet verified this
  actually works end-to-end for the Canary decoder + LoRA.
- **Spikes 2, 5 (on-device half), 6** still blocked on the device being
  reattached (adb showed no devices this session).
- Scratch code from this session (`load_salm.py`, `run_modes.py`,
  `export_encoder.py`, `verify_onnx.py`, the exported `perception.onnx`) lives
  in `/mnt/ssd/smart/canary-qwen-work/scratch/` and `onnx_export/` — outside
  this repo. Worth moving the export script (cleaned up) into this repo's
  `scripts/` if the encoder path is pursued further.

---

## 10. Session update (2026-07-17, continued) — decoder extraction + Gen AI Builder investigation (spike #4)

Same session as §9, continuing into spike #4 (offline-compile the real
Qwen3-1.7B+LoRA decoder). Result: **the decoder was correctly extracted from
the NeMo checkpoint into plain HF/safetensors format** (with a real bug fixed
along the way), and **a much more promising compile path was found** than the
one originally scoped — but the actual compile was not attempted yet. This
section is a map for whoever picks it up next.

### Decoder extraction: done, verified, one real bug fixed
Script: `extract_decoder.py` (in the same scratch dir, same NeMo container as
§9). Loads the SALM, then:

```python
peft_config = model.llm.peft_config["default"]
# ... coerce OmegaConf ListConfig/DictConfig fields to plain containers
# (NeMo builds LoraConfig from OmegaConf; json.dumps chokes on ListConfig
# in target_modules otherwise) ...
model.llm.save_pretrained("/work/extracted/lora_adapter")   # PEFT adapter dir

base = model.llm.unload()   # NOT get_base_model() -- see bug below
base.model.embed_tokens = model.embed_tokens   # SALM moved this out of the base model
base.save_pretrained("/work/extracted/base_qwen3_1_7b_canary", safe_serialization=True)
```

**Bug found and fixed:** `model.llm.get_base_model()` (the obvious first thing
to try) only unwraps the outer `PeftModel` — it does **not** remove the
`lora.Linear` wrapper on `q_proj`/`v_proj`. Saving that gives a checkpoint
whose state dict keys are `q_proj.base_layer.weight` / `q_proj.lora_A...` /
`q_proj.lora_B...` instead of plain `q_proj.weight`. Reloading it with plain
`transformers.AutoModelForCausalLM.from_pretrained()` silently **reinitializes
q_proj/v_proj to random weights** (transformers logs "newly initialized" but
doesn't error), producing a model that loads fine and runs but outputs
garbage. **Use `model.llm.unload()` instead** — it properly replaces each
`lora.Linear` with a plain `nn.Linear` holding the original frozen
`base_layer` weight (LoRA is applied separately at runtime, not merged in —
this is the correct extraction for a "no LoRA" / LLM-mode base checkpoint).
This was only caught by actually generating text with the saved checkpoint and
noticing it was garbage (`火花OMETRYIOUSIOUS...`) — **always smoke-test an
extracted checkpoint by generating, not just by loading without error.**

Result verified: the corrected extraction, reloaded with plain `transformers`
(no NeMo/PEFT involved), answers "What is the capital of France?" coherently
(with a `<think>` block, confirming Qwen3's reasoning mode intact). Output at
`/mnt/ssd/smart/canary-qwen-work/extracted/`:
- `base_qwen3_1_7b_canary/` — standard HF Qwen3ForCausalLM checkpoint (safetensors,
  config.json, tokenizer files), 6.5GB, **fp32** (not yet quantized).
- `lora_adapter/` — standard PEFT adapter dir (`adapter_config.json` +
  `adapter_model.safetensors`), 99MB, exact config: `r=128, lora_alpha=256,
  target_modules=[q_proj, v_proj], lora_dropout=0.01`.

Exact architecture (from the extracted `config.json`, needed for whichever
compile path is used next): `num_hidden_layers=28, hidden_size=2048,
num_attention_heads=16, num_key_value_heads=8, head_dim=128,
intermediate_size=6144, vocab_size=151936, rope_theta=1000000,
max_position_embeddings=40960, tie_word_embeddings=true`. Note the last one —
Qwen3-4B's own qai_hub_models definition unties embeddings; **this checkpoint
ties them**, which matters for whichever export path is used (see below, the
qai_hub_models Qwen3 code already handles both cases).

### Two candidate compile paths, investigated but not executed

**Path A: hand-roll it directly against `qairt.gen_ai_api` (QAIRT SDK, both
2.46 and 2.47).** Read `gen_ai_builder_factory.py`, `gen_ai_builder_htp.py`,
`gen_ai_builder.py`, and both SDK example scripts
(`examples/QAIRT/python/llm_on_device_inference.py`,
`lora_on_device_inference.py`). Findings:
- Confirms §2/§7: `Qwen3ForCausalLM` (dense) is genuinely not in
  `SupportedLLMs` (only `Qwen2ForCausalLM` and `Qwen3MoeForCausalLM` dispatch
  to a dedicated builder) — it falls through to the generic
  `GenAIBuilderHTP.from_pretrained()` with `logger.warning("Architecture is
  unknown or unsupported; Returning default. ... may work but will probably
  require additional configuration.")`.
- **The builder does NOT accept a PyTorch checkpoint directly** — there's a
  literal `# TODO: if it's a pytorch directory, load the model and export to
  onnx / see AISW-129859` in `build()`. We would have to export ONNX
  ourselves first.
- **Pre-computed AIMET `.encodings` are not strictly mandatory.** `build()`
  calls `_confirm_encodings()`, which just leaves `encodings_path` as `None`
  if it can't find a `.encodings` file next to the ONNX (no exception).
  Separately, `set_conversion_options(config, calibration_config)` accepts a
  `CalibrationConfig` (`dataset`, `num_of_samples=512`, `weights_precision`,
  `act_precision`, `param_calibration_method`, etc. —
  `qairt/api/converter/converter_config.py:297`) — the docstring for
  `build()` says conversion "may include quantization using encodings, and/or
  calibration if sample inputs are provided." So there appear to be **two
  ways in**: bring pre-computed AIMET encodings (the documented/recommended
  "Step 1" workflow, whose notebook tooling is NOT in this SDK — it's on
  Qualcomm's QPM portal, not something we have local access to), **or** hand
  the builder a plain ONNX + a `CalibrationConfig` and let it calibrate
  in-process. The second option was not tried but looks like the more
  reachable one given we don't have the QPM notebook.
- **LoRA via the builder's `lora_config` is a much bigger undertaking than a
  single adapter toggle.** `lora_on_device_inference.py`'s prerequisites are
  **N+1 separately-quantized ONNX graphs** (base + one per adapter/use-case
  combination, each with its own `.onnx`/`.encodings`), a
  `top_level_lora_meta.yaml`, and a **PyTorch→ONNX node-name mapping file**
  produced by tooling this SDK doesn't include. For our single Canary LoRA
  adapter this is "only" N=1 (2 graphs: base, base+lora), but it's still a
  nontrivial artifact-authoring problem with no example of how to produce the
  node-mapping file ourselves. **This confirms §7's documented fallback is
  the pragmatic choice: compile two plain (non-LoRA-graph) bundles — a
  merged-LoRA one for ASR mode (`model.llm.merge_and_unload()` instead of
  `.unload()`) and the plain base for LLM mode — rather than chasing the
  builder's native multi-adapter mechanism.**
- Net assessment: Path A is *possible* per the API surface, but means writing
  our own ONNX export (with the right input/output tensor conventions for
  Genie — sequence splitting, KV-cache I/O, RoPE handling, MHA layout) totally
  from scratch, with no worked example for a bare dense-Qwen3-shaped model.
  High effort, uncertain payoff, since Path B below reuses code that already
  does exactly this correctly for the same architecture family.

**Path B (recommended): reuse `qai_hub_models`'s own Qwen3 export/quantize
machinery instead of hand-rolling ONNX export.** This is the code that
*already* produces the working Qwen3-4B bundle (`scripts/04_export_model.sh`
→ `qai-hub-models export qwen3_4b ...`). Found in the `genie-llm-toolchain`
image at `qai_hub_models/models/_shared/qwen3/model.py` (architecture-generic,
parameterized) and `qai_hub_models/models/qwen3_4b/model.py` (the concrete
4B subclass — a **~100-line** file supplying just the architecture constants
and a `parts` registry). Key findings from reading `_shared/qwen3/model.py`:
- `Qwen3PreSplitBase.__init__`/`.from_pretrained()` take an explicit
  `checkpoint: str | os.PathLike | Path | None` argument — **not hardcoded to
  an AI-Hub-hosted checkpoint or a specific HF repo**; it's passed straight
  through to the underlying HF loader, which accepts local directories fine.
  This means our extracted `/work/extracted/base_qwen3_1_7b_canary` (a
  standard HF checkpoint) is very plausibly a drop-in `checkpoint=` value.
- `_verify_ckpt()` checks the loaded config's `num_hidden_layers`,
  `hidden_size`, `num_attention_heads`, `num_key_value_heads` against class
  constants (`self.num_layers` etc.) and raises `ValueError` on mismatch — so
  a new subclass just needs those four (plus `head_dim`) set to our extracted
  values above.
- **Tied embeddings are already handled.** The code explicitly detects
  `tie_word_embeddings` and, if true, forces it `False` in the exported
  config while copying the tied weight into a distinct `lm_head.weight` so
  the ONNX export gets two separate initializers with unchanged behavior —
  exactly the situation our checkpoint is in (`tie_word_embeddings=true`,
  unlike Qwen3-4B).
- Net assessment: writing a new small model-definition module (mirroring
  `qwen3_4b/model.py`'s ~100 lines, with our 1.7B constants and
  `checkpoint="/path/to/base_qwen3_1_7b_canary"` in place of
  `hf_repo_name`) is a **far smaller, lower-risk task** than Path A, and
  reuses AIMET quantization logic already proven to produce a working Genie
  bundle for this exact architecture family. **This is the recommended
  starting point for whoever picks up spike #4 next.**
- **One open fork remains**: `qwen3_4b/export.py`'s `compile_model()`
  unconditionally calls `hub.submit_compile_job(...)` — **AI Hub cloud**, not
  a local compile. Reusing the export/quantize half of this pipeline (which
  runs entirely locally — that's why `aimet-onnx` is installed in the image)
  and then feeding the resulting quantized ONNX + encodings into QAIRT's
  local `gen_ai_api`/`qairt.compile()` instead of `hub.submit_compile_job`
  would keep the whole thing offline. Whether that's necessary or whether
  cloud-compiling *just this final step* is acceptable is the same open
  question §8 originally flagged — now much more concrete: **quantization can
  be fully local either way; only the last compile step forks on cloud vs.
  offline.**

### Path B attempted: works up to a hard memory wall

Wrote `qwen3_1_7b_canary_model.py` (in the scratch dir), mirroring
`qwen3_4b/model.py` almost exactly: `Qwen3_1_7B_Canary_PreSplit` /
`_QuantizablePreSplit` / `_PartBase` / `_Part{1..4}_Of_4` / `_Collection`,
using the architecture constants from §10's extracted `config.json`
(`num_layers=28, num_splits=4, num_layers_per_split=7, hidden_size=2048,
num_attention_heads=16, num_key_value_heads=8, head_dim=128`), with
`hf_repo_name` pointed at the local extracted checkpoint dir instead of an HF
Hub repo. Two things confirmed working before hitting the wall:

1. **A missing dependency**: `genie-llm-toolchain` doesn't have `accelerate`
   installed, so `Qwen3_1_7B_Canary_PreSplit.from_pretrained(checkpoint=...)`
   fails with `NameError: name 'init_empty_weights' is not defined` deep in
   `transformers.modeling_utils`. `pip install accelerate` fixes it — this
   never surfaced before because the existing Qwen3-4B pipeline never loads a
   raw local checkpoint through this exact code path (it works from
   AI-Hub-hosted pre-quantized checkpoints). **Worth adding to
   `docker/Dockerfile` if this path is pursued further.**
2. **The FP model loads correctly from our checkpoint, tied-embedding fix
   confirmed working**: loading logs `Some weights ... were not initialized
   ...: ['lm_head.weight']` (expected — our checkpoint has
   `tie_word_embeddings=true`, so no separate `lm_head.weight` was ever saved;
   `edit_llm_config()` forces `tie_word_embeddings=False` before load, which
   is why transformers reports it as freshly/randomly initialized). Verified
   directly: after loading, `Qwen3ForCausalLM.lm_head.weight` **does** equal
   `get_input_embeddings().weight` (`torch.equal(...) == True`) — the
   post-load copy in `Qwen3PreSplitBase.__init__` (§10 Path B writeup) fixed
   it exactly as designed. This is the "tied-embedding encoding fix" the
   original `qwen3_4b/model.py` docstring mentions — confirmed it generalizes
   correctly to a checkpoint that actually has tied embeddings (Qwen3-4B's
   own doesn't).

**Then ran real `quantize()`** (calling `_shared.llm.quantize.quantize()`
directly rather than the CLI wrapper) with intentionally minimal settings for
a smoke test — `context_length=512, seq_len=128, num_samples=2,
precision=w4a16, allow_cpu_to_quantize=True` — expecting this to be slow but
survivable. It got through model loading and into ONNX export/tracing
(`torch.onnx`'s dynamo-based exporter), then **the host was OOM-killed**:
`docker inspect` showed `OOMKilled=true`; `free -h` showed the 60GB RAM **and**
8GB swap both fully exhausted at time of death (`docker exec ... ps aux`
simply stopped listing the process — no python traceback, since the kernel
OOM-killer doesn't give the process a chance to log anything). This happened
with only 2 calibration samples and short sequence/context lengths, so it's
not primarily calibration-data volume — most likely the ONNX export +
AIMET QuantSim wrapping step itself has multi-x memory overhead over the
6.5GB fp32 checkpoint (multiple live copies: HF model, traced graph,
QuantSim-wrapped copy, ...), independent of `num_samples`.

**This is a hard resource wall on this host, not a pipeline bug.** The
`qai_hub_models` code itself hints at this being expected: `quantize()`
requires `allow_cpu_to_quantize=True` to even attempt CPU at all, and its own
error message when that flag is absent says "This model requires a CUDA GPU
(V100/A100)... Please re-try with GPU machine" — i.e. **CPU quantization is
explicitly the unsupported/best-effort path**, not the intended one, even
before considering memory. **Next session should get access to a GPU machine
(or a host with substantially more RAM/swap) before re-attempting this step**
— this is very likely the actual, correct fix, not a code change.

### Also checked: is there a genuinely local/offline final-compile path?
Briefly read `qwen3_4b/export.py`'s `export_without_hub_access()` (imported
from `qai_hub_models.utils.export_without_hub_access`), hoping it might be a
real offline-compile fallback. **It is not** — it only reuses **previously
published results from Qualcomm's own catalog-model runs** ("Using results
from a previous job run on the same device") when no AI Hub token is
configured; it has nothing to offer for a novel, never-before-compiled
checkpoint like ours. The cloud-vs-local-compile fork noted just above (in
the Path B writeup) remains the real open question — still unresolved, and
moot until quantization itself succeeds on adequate hardware.

### Not attempted yet
- Re-running quantization on a machine with a GPU or much more RAM/swap
  (recommended concrete next step — see above).
- Producing the "ASR mode" bundle (`model.llm.merge_and_unload()` instead of
  `.unload()` in `extract_decoder.py` — one-line change, not yet run).
- The `--embedding_file` seam validation (handoff's original spike #5) — still
  blocked on device access, independent of everything in this section.
- Deciding the cloud-vs-local-compile fork (only relevant once quantization
  succeeds).
- `qwen3_1_7b_canary_model.py` lives at
  `/mnt/ssd/smart/canary-qwen-work/scratch/` (outside the repo, alongside
  everything else from this session) — copy it in properly (e.g. as
  `qai_hub_models/models/qwen3_1_7b_canary/model.py` inside a fork/patch of
  the toolchain image, matching the real package layout) if this path is
  pursued on a bigger machine.

---

## 11. Session update (2026-07-17, continued) — tried to route around the OOM via AI Hub Workbench cloud quantize/compile; confirmed it's not possible through the proven pipeline

Prompted by <https://aihub.qualcomm.com/get-started#workbench>: AI Hub
Workbench's premise is "optimize your custom trained model... with a few
lines of code," and the low-level `qai_hub` SDK (already installed in
`genie-llm-toolchain`, distinct from the higher-level `qai_hub_models`) genuinely
supports this in general:

- `qai_hub.submit_quantize_job(model, calibration_data, weights_dtype, activations_dtype, ...)`
  — takes a **plain ONNX model** + calibration data, quantizes it **in the
  cloud**, returns a QDQ-format quantized ONNX.
- `qai_hub.submit_compile_job(model, device, calibration_data=..., ...)` —
  takes a **PyTorch or ONNX model directly** (not necessarily pre-quantized);
  its docstring says calibration_data triggers "post-training quantization...
  applied to the model during translation" server-side.

So the generic capability to hand AI Hub an unquantized model and have it
quantize + compile in the cloud is real. **But it doesn't rescue this
particular pipeline.** Traced `qwen3_4b/export.py`'s actual call chain
(`export_model()` → `upload_model()` → `hub.submit_compile_job()`) and found:

- `export_model()` **hard-asserts** `precision in [Precision.w4a16]` before
  doing anything else — for Qwen3 there is no FP/unquantized option to even
  request.
- The model it uploads (`model.serialize_component_graph(...)`) comes from a
  `Collection`/`Part` object tree that — confirmed by directly testing it —
  **requires a local AIMET-quantized checkpoint to already exist**, even when
  explicitly requesting the FP-only path. Concretely: instantiating
  `Qwen3_1_7B_Canary_Collection.from_pretrained(checkpoint="DEFAULT", ...)`
  (which loads fresh FP32 weights from HF/local checkpoint, no calibration)
  still internally calls `quant_presplit_cls.from_pretrained(...)`
  **even with the `_skip_quantsim_creation=True` default**, and raises:
  `ValueError: No checkpoint is available for this model in w4a16 precision.
  Please generate a local quantized checkpoint.` — `_skip_quantsim_creation`
  only skips *recomputing* a QuantSim when a quantized checkpoint is already
  present; it does not make the Part classes work in a pure-FP mode. (This
  test itself did **not** OOM — capped the container at `--memory=48g
  --memory-swap=48g` this time so a repeat failure would be contained rather
  than taking down the whole host again, and it failed fast on the
  `ValueError` instead.)

**Conclusion: for this architecture family, `qai_hub_models` treats local
AIMET quantization as a hard prerequisite, not a step that can be swapped
for AI Hub's cloud PTQ.** The Genie-compatible split/Part/MHA→SHA export
logic (the part of this pipeline that's actually valuable to reuse — see §10
Path B) is entangled with the quantized-checkpoint requirement; you cannot
get the correct Genie-shaped ONNX graphs out of it without AIMET having run
first.

The only way to *actually* use AI Hub's cloud PTQ (`submit_compile_job(model=
<plain onnx>, calibration_data=...)`) would be to bypass `qai_hub_models`'
Qwen3 pipeline entirely and hand-build a plain ONNX export ourselves (e.g. via
`optimum-cli` or manual `torch.onnx.export` on the vanilla HF `Qwen3ForCausalLM`)
— but then we'd lose the split/Part structure and MHA→SHA conversion this
pipeline provides, with **no guarantee the resulting compiled model would be
usable via `genie-t2t-run`** (it might only be usable via lower-level raw QNN
APIs, a materially different and untested runtime path for this project).
Given that risk, and that §10's conclusion already stands independent of this
detour, **the recommendation is unchanged: run local AIMET quantization
(`qai_hub_models`' proven path) on a machine with a GPU or substantially more
RAM/swap than this host (60GB RAM + 8GB swap, both exhausted at OOM).**
Cloud only re-enters the picture for the *final compile step*, same as §10
already noted — not as a way to avoid local quantization.
