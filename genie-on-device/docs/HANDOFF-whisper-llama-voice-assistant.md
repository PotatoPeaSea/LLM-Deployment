# HANDOFF — Whisper (ASR) + Llama-3.2-1B (LLM) voice assistant on QCS8550

**Written:** 2026-07-20, updated twice same day (decode loop, then the
end-to-end voice assistant script). **For:** the next session picking up VAD
and/or turn latency (the full pipeline — ASR, LLM, and a working
press-to-talk driver — already runs end-to-end, see §7 and §9). **Context:**
this is the pragmatic alternative to
[HANDOFF-canary-qwen-offline.md](HANDOFF-canary-qwen-offline.md)
— instead of getting `nvidia/canary-qwen-2.5b`'s custom SALM architecture
through local AIMET quantization (blocked on a hardware/licensing wall, see
that doc §10-11), this pipeline uses two off-the-shelf, catalog-available
models: **Whisper-Base** for transcription and **Llama-3.2-1B-Instruct** for
the answer. Both come from Qualcomm's own `qai_hub_models` catalog, so
neither needed local AIMET quantization.

## 0. TL;DR / current state

```
this host's mic → 16kHz PCM (arecord, manual start/stop -- no VAD yet)
   → Whisper-Base encoder (NPU, QNN)      ──► cross-attention K/V cache
   → Whisper-Base decoder (NPU, QNN)      ──► transcript text  (WORKING, §3-4)
   → Llama-3.2-1B-Instruct (NPU, genie-t2t-run, WORKING)  ──► answer
```
**The target QCS8550 device has no mic** — audio is captured on this host and
only the transcript text crosses to the target. End-to-end voice assistant:
`python3 scripts/06_voice_assistant.py` (§7).

- **Llama-3.2-1B-Instruct: fully working end-to-end.** Deployed, verified on
  device via `genie-t2t-run`, coherent output. See §1.
- **Whisper-Base: fully working end-to-end, on-device, real NPU inference.**
  `HfWhisperApp` driven by two on-device QNN adapters transcribed the bundled
  JFK demo clip verbatim: *"And so my fellow Americans, ask not what your
  country can do for you, ask what you can do for your country."* in 29
  decode steps / 20.9s. See §3-4.
- **Root cause of the §3 "±512 pattern" found and fixed**: it was
  `qnn-net-run` silently defaulting to fp32 file I/O regardless of the
  graph's declared fp16 dtype, not a real model behavior — see §3.
- **`scripts/06_voice_assistant.py`: end-to-end press-Enter-to-talk tool,
  working.** Records on this host, transcribes and answers entirely on the
  target's NPU. See §7.
- **Not started:** VAD/silence detection (recording is currently manual
  start/stop), a persistent low-latency driver (each turn currently costs
  ~0.7s/decode-step + ~7s Llama redeploy — fine for correctness, not for a
  snappy live assistant). See §9.

---

## 1. Llama-3.2-1B-Instruct — done, working

### Why this model (not the originally-requested ones)
Session started wanting Qwen3.5-2B, then Qwen3-1.7B — both turned out to be
**GenieX/llama.cpp-only** (`genie_compatible: false`, `geniex_qairt_compatible:
false` in their `info.yaml`), i.e. no QNN/NPU path exists for them in this
catalog, only a GGUF download for a separate (untested here) llama.cpp-based
runtime. Llama-3.2-3B-Instruct was tried next (`genie_compatible: true`) but:
- Needed a Hugging Face account with Meta's gated license accepted (401/403
  until accepted — this is per-repo, not covered by having a valid token).
- Even after the license was accepted, the export OOM'd locally (exit 137)
  **twice**, at both context-length 2048 and 512 — unlike Qwen3-4B, Llama's
  export pipeline loads and ONNX-traces the **full FP checkpoint locally**
  (no precompiled bundle exists for any Llama size — `qai-hub-models fetch`
  returns *"No pre-compiled model files ... available due to licensing
  restrictions"*). This is the same OOM shape as the Canary decoder wall.
  Fixed by adding a 64GB swapfile (see §5).

Switched to **Llama-3.2-1B-Instruct** (smaller, same fix applies) at the
user's request once swap was added. **Every ungated genie-compatible model in
this catalog is 4B+** (`qwen3_4b`, `qwen3_8b`, `phi_3_5_mini_instruct`, etc.)
— Llama-3.2-1B/3B are the *only* sub-4B models with a QNN/NPU path at all, and
they're gated. If a smaller ungated NPU model is ever needed, there isn't one
in this catalog today.

### How it was built (reusable commands)
```bash
# One-time: accept the license at huggingface.co/meta-llama/Llama-3.2-1B-Instruct
# (or -3B-Instruct), generate a read token, put it in HF_TOKEN.

cd genie-on-device
export HF_TOKEN="<your token>"   # 00_env.sh's docker_run now passes this through if set
source scripts/00_env.sh
docker_run "${IMAGE_NAME}:latest" \
  qai-hub-models export llama_v3_2_1b_instruct \
    --runtime geniex_qairt \
    --chipset qualcomm-qcs8550-proxy \
    --skip-profiling \
    --output-dir /workspace/output/llama_v3_2_1b_instruct \
    --context-lengths 512
```
**Note:** unlike `04_export_model.sh`'s hardcoded flags, Llama's `export.py`
does **not** accept `--skip-inferencing` (Qwen3-4B's does) — passing it is a
hard CLI error, not a warning. Don't use `04_export_model.sh` unmodified for
Llama; call `qai-hub-models export` directly as above, or teach the script to
drop that flag per-model-family if this becomes common.

Deploy (works with the *existing* `05_deploy_and_run.sh`, now generalized —
see §6):
```bash
./scripts/05_deploy_and_run.sh llama_v3_2_1b_instruct \
  /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 "What is the capital of France?"
# -> [BEGIN]: The capital of France is Paris.[END]
```
Bundle: `workspace/output/llama_v3_2_1b_instruct/llama_v3_2_1b_instruct-geniex_qairt-w4-qualcomm_qcs8550_proxy/`
(1.3GB, 3 parts, `qairt: 2.45.0.260326154327`, w4 precision, context length 512).

### Context length 2048 also works — 512 was not a hard ceiling for this model

Unlike Qwen3-4B, where 512 is a *hard requirement* (anything longer fails
on-device load with `err 1002`, see `docs/README.md` finding #5), 512 was
only ever *carried over* to Llama-3.2-1B from that precedent, never
independently tested. Tried `--context-lengths 2048` (same export command as
above, just `--output-dir .../llama_v3_2_1b_instruct_ctx2048`, re-exported in
~5 min reusing the already-cached checkpoint) — **loaded and ran cleanly,
no memory error**:
```
[INFO]  "Allocated total size = 101532160 across 5 buffers"   # ctx 2048, vs 50807296 at ctx 512
[BEGIN]: The capital of France is Paris.[END]
```
Bundle: `workspace/output/llama_v3_2_1b_instruct_ctx2048/llama_v3_2_1b_instruct-geniex_qairt-w4-qualcomm_qcs8550_proxy/`
(same 1.3GB — quantized weight size doesn't depend on context length, only
the runtime KV-cache allocation does). **`scripts/06_voice_assistant.py` now
defaults to this bundle** (`LLAMA_MODEL_ID = "llama_v3_2_1b_instruct_ctx2048"`)
for more conversational headroom; the original 512-context bundle is left
in place, untouched, as a fallback. Whether 4096/8192 also fit hasn't been
tried — this only answers "does 2048 work," not "what's the actual ceiling."

---

## 2. Whisper-Base — fetched and staged, encoder POC done

### Getting the bundle (no compile needed — precompiled asset exists)
Unlike Llama, Whisper *is* redistributable, so skip `export` entirely and use
`fetch` — it pulls Qualcomm's own precompiled QNN context binary directly,
sidesteps a broken `qai_hub_models==0.57.3` packaging bug (`whisper_base`'s
`info.yaml` fails a `release-assets.yaml`-existence pydantic validator that
`export` hits but `fetch` doesn't):
```bash
docker run --rm -v "$(pwd)/workspace/output:/workspace/output" genie-llm-toolchain:latest \
  qai-hub-models fetch whisper_base -r qnn_context_binary -p float -c qualcomm-qcs8550-proxy \
    -o /workspace/output/whisper_base
```
Result: `workspace/output/whisper_base/whisper_base-qnn_context_binary-float-qualcomm_qcs8550_proxy/`
— `encoder.bin` (49.5MB), `decoder.bin`, `metadata.json` (full I/O tensor spec
for both, see below). `qairt: 2.45.0.260326154327`, same as Llama's.

### Two dependencies needed adding to `docker/Dockerfile` (done, image rebuilt)
- `pip install accelerate` — `transformers.from_pretrained`'s
  `init_empty_weights` needs it; same bug hit in the Canary session §10, never
  surfaced for Qwen3-4B because that pipeline never loads a raw checkpoint
  through this code path.
- `apt-get install ffmpeg libportaudio2` — `qai_hub_models`'s whisper `app.py`
  imports `sounddevice` unconditionally at module load, which needs PortAudio
  even if you never use the mic-streaming demo path.
- Also added the `whisper-base` pip extra (`pip install
  "qai-hub-models[whisper-base]"`) for the feature-extractor/audio deps.

### Metadata (I/O tensor spec — needed for anything driving this model)
`metadata.json` under the fetched bundle has the complete spec. Key facts:
- **Encoder**: input `input_features` shape `[1,80,3000]` fp16 (a 30-second
  mel spectrogram, always this fixed size — pad/truncate shorter/longer
  audio). Outputs: 6 layers × (`k_cache_cross_N`, `v_cache_cross_N`), each
  `[8,1,64,1500]` fp16 (8 heads, head_dim 64, 1500 = encoder output timesteps).
- **Decoder**: one step at a time. Inputs: `input_ids` `[1,1]` int32,
  `position_ids` `[1]` int32, `attention_mask` `[1,1,1,200]` fp16, 6×
  `k/v_cache_self_N_in` `[8,1,64,199]`/`[8,1,199,64]` fp16 (self-attention KV
  cache, grows as you decode), 6× `k/v_cache_cross_N` (same cross-attention
  cache from the encoder, unchanged across steps). Outputs: 6×
  `k/v_cache_self_N_out` (feed back in as next step's `_in`), `logits`
  `[1,51865,1,1]` fp16 (Whisper's vocab size). Max 200 decode steps
  (`Max decoded sequence length: 200 tokens` per `info.yaml`).

### Runtime: `qnn-net-run` (from the QAIRT SDK), not `genie-t2t-run`
`genie-t2t-run` only understands the `genie_config.json`-driven LLM bundle
format (part-chained, tokenizer built in). Whisper's bundle is raw QNN context
binaries with no such config — the correct low-level tool is
`<QAIRT>/bin/aarch64-android/qnn-net-run`, which does **one forward pass per
invocation**, reading/writing raw tensor files:
```bash
./qnn-net-run --retrieve_context encoder.bin --backend libQnnHtp.so \
  --input_list input_list.txt --output_dir out \
  --use_native_input_files --use_native_output_files
```
**The two `--use_native_*` flags are not optional** — see §3: without them
`qnn-net-run` silently reads/writes every non-int32 tensor as fp32 regardless
of the graph's declared fp16 dtype, which doesn't error, it just corrupts the
data. `--use_native_output_files` also appends `_native` to every output
filename (`k_cache_cross_0_native.raw`, not `k_cache_cross_0.raw`) — `qnn_device.py`'s
`run()` accounts for this.

`input_list.txt` format (one line, `tensorName:=relativePath`, matching names
in `metadata.json`):
```
input_features:=input_features.raw
```
Push `encoder.bin`/`decoder.bin` + `qnn-net-run` + the QAIRT `aarch64-android`
`.so`s + `hexagon-v73/unsigned` DSP skels to `/data/local/tmp/...`, same
`LD_LIBRARY_PATH`/`ADSP_LIBRARY_PATH` pattern as the Genie deploy — see the
staging block in §4 for the exact commands used.

### Generating the mel-spectrogram input (solved — reuse this, don't rebuild it)
`qai_hub_models` already has the exact preprocessing Whisper needs, plus a
bundled real test clip (`audio/jfk.npz`, ~11s):
```python
from qai_hub_models.models._shared.hf_whisper.demo import load_demo_audio
from qai_hub_models.models._shared.hf_whisper.model import get_feature_extractor, SAMPLE_RATE
import numpy as np

audio, sr = load_demo_audio()                                    # (176000,), 16kHz
fe = get_feature_extractor("openai/whisper-base")
feats = fe(audio, sampling_rate=SAMPLE_RATE, return_tensors="pt")["input_features"]  # [1,80,3000] fp32
feats.numpy().astype(np.float16).tofile("input_features.raw")    # -> exactly what qnn-net-run needs
```
This ran inside `genie-llm-toolchain:latest` (needs the `whisper-base` extra
from the Dockerfile fix above). Script + output live at
`workspace/output/whisper_base/poc/`.

---

## 3. Encoder POC result — the ±512 pattern was a `qnn-net-run` I/O default, not real model behavior

Ran the encoder on-device against the real JFK-clip mel spectrogram above.
`qnn-net-run` completed cleanly (`Finished Executing Graphs`), produced all 12
expected output files at exactly the right byte sizes (1,536,000 bytes =
8×1×64×1500×2, matching `metadata.json`).

**Original finding (now resolved, kept for the record):** `k_cache_cross_0.raw`
reshaped to `[8,1,64,1500]` had every other value along the 64 (head_dim)
axis pinned to exactly ±512.0, consistently across all 1500 timesteps.

**Root cause, found while debugging the decoder (§4): `qnn-net-run` defaults
to fp32 for *all* raw file I/O, regardless of the graph's declared dtype**,
unless told otherwise. From `qnn-net-run --help`:
```
[ --use_native_input_files ]
                      ... If not specified, input files will be parsed in floating point [fp32].
[ --use_native_output_files ]
                      ... If not specified, output files will be generated in floating point [fp32].
```
The encoder/decoder graphs declare every activation tensor as fp16
(`metadata.json`, confirmed against the ground-truth graph I/O via
`qnn-context-binary-utility --context_binary decoder.bin --json_file ...` —
see `bin/<host-arch>/qnn-context-binary-utility` in the QAIRT SDK, useful any
time metadata.json's declared shapes are in doubt). The original POC wrote
and read raw files as fp16 without either flag, so `qnn-net-run` was silently
byte-reinterpreting: on read, it treated our fp16 input bytes as fp32 (with
no complaint, since a single-input graph has nothing to cross-check batch
consistency against — see the multi-input decoder's loud failure mode
below); on write, it wrote outputs as actual fp32, which we then misread as
fp16 — exactly the kind of operation that produces structured-looking
garbage (adjacent fp32 values, viewed as fp16 pairs, are not remotely random).
±512 was an artifact of that reinterpretation, not a `Clip` op or a real
attention-cache value.

**This surfaced loudly once decoder.bin (27 inputs) was in the loop**: with
input files still being read as fp32-by-default, one of the 12 KV-cache
inputs came out with a computed byte length inconsistent with the other 26
inputs' consensus, and `qnn-net-run` refused to run (`Current input tensor
... batch size = 2 does not match with expected ... batch size = 1`). That
inconsistency is what forced tracking this down — a single-input graph (the
encoder) has no such cross-check and fails silently instead.

**Fix**: always pass both `--use_native_input_files --use_native_output_files`
(see `qnn_device.py`'s `run()` in §4). With the fix, `k_cache_cross_0`'s
values are unremarkable small floats (range ≈ [-7.2, 8.6], mean ≈ -0.015, no
value anywhere near ±512) — confirming this really was purely an I/O bug.
**Any future `qnn-net-run` invocation in this repo must pass both flags** —
their absence doesn't error, it just quietly corrupts every non-int32 tensor,
which is worse than a crash.

---

## 4. The decode loop — built, working, on real NPU hardware

**Key finding: don't hand-roll the KV-cache bookkeeping.**
`qai_hub_models/models/_shared/hf_whisper/app.py`'s `HfWhisperApp.transcribe()`
already implements the *entire* correct decode loop — token-by-token
generation, KV-cache threading, attention-mask sliding window, EOT handling —
against a generic `encoder`/`decoder` pair that just need to be **callables**
(`ExecutableModelProtocol`: takes positional tensor args, returns tensor(s)).
The call signature the decoder must honor (from reading `app.py`):
```python
decoder_input = (
    input_ids, attention_mask,
    *flattened_kv_cache_self,   # 12 tensors: (k0,v0,k1,v1,...,k5,v5)
    *flattened_kv_cache_cross,  # 12 tensors, same layout, from the encoder call
    position_ids,
)
decoder_output = self.decoder(*decoder_input)   # -> flat 13-tuple: (logits, k0,v0,...,k5,v5)
```
`HfWhisperApp`'s tuple-unwrapping logic accepts a flat tuple for both the
encoder's return value and the decoder's — no need to nest into `(k,v)` pairs
yourself, see the adapter code for the exact shapes that satisfy its
`isinstance` checks.

**Implementation, all under `workspace/output/whisper_base/poc/`:**
- **`qnn_device.py`** — the on-device I/O layer. `tensor_spec(model_file, name)`
  reads shape/dtype straight from `metadata.json` (no hardcoded magic
  numbers). `run(...)` does one `qnn-net-run --retrieve_context` invocation:
  writes tensors to a local tmp dir, `adb push`, run, `adb pull`, read back
  reshaped per spec — **always with `--use_native_input_files
  --use_native_output_files`** (§3 — without these it silently corrupts
  every non-int32 tensor). Validates every tensor's shape/dtype against
  `metadata.json` before writing, since a mismatch here is exactly the kind
  of bug that produces plausible-but-wrong output. `stage_static_tensors(...)`
  pushes tensors once and returns their on-device paths, so the ~18MB
  cross-attention KV cache (unchanged across all ~200 decode steps) is
  uploaded **once per transcription**, not once per step — `input_list.txt`
  entries can reference any already-on-device path, not just freshly-pushed
  files, and `qnn-net-run` doesn't care which.
- **`whisper_qnn_adapters.py`** — `QnnEncoder` and `QnnDecoder`, the two
  `ExecutableModelProtocol` adapters. `QnnEncoder.__call__` runs `encoder.bin`
  once and stages its cross-cache output via `stage_static_tensors`;
  `QnnDecoder` (holds a reference to the `QnnEncoder` instance to reach those
  staged paths) runs `decoder.bin` once per step, referencing the cross-cache
  by path instead of re-uploading it.
- **`run_whisper_decode.py`** — the entry point:
  `HfWhisperApp(QnnEncoder(), QnnDecoder(encoder), "openai/whisper-base").transcribe(audio, sr)`
  against the bundled JFK demo clip.

**Run it** (needs both `qai_hub_models`, only in the image, and `adb` reaching
the host's already-authenticated device — `docker_run_networked`, added to
`00_env.sh`, shares the host network namespace so the container's `adb`
client talks to the host's `adb` server over `localhost:5037`, no USB
passthrough or in-container device auth needed):
```bash
source scripts/00_env.sh
docker_run_networked genie-llm-toolchain:latest \
  python3 /workspace/output/whisper_base/poc/run_whisper_decode.py
```
**Result**: `"And so my fellow Americans, ask not what your country can do
for you, ask what you can do for your country."` — the correct, verbatim JFK
inaugural excerpt. 29 decode steps, 20.9s total (~0.7s encoder, ~0.65-0.75s
per decode step). This is real NPU inference end to end, not a host fallback.

**Performance caveat (unchanged from the original plan, now measured)**: each
`QnnDecoder.__call__` shells out to `adb shell qnn-net-run`, which reloads
`decoder.bin`'s context from scratch every call — confirmed ~0.65-0.75s/step
end-to-end (adb round-trip + context reload + inference), vs. Qualcomm's
benchmarked ~4.2ms/token for a persistent process. Fine for a
correctness-proving pass and short utterances (this 11s clip finished in
21s); a real interactive deployment needs a persistent on-device driver using
the QNN C API directly (skip `qnn-net-run` entirely) — see below.

**Reference implementation that already solves the "persistent on-device
driver" problem**: Qualcomm's public (no QPM/login needed) `quic/qidk` GitHub
repo, `Solutions/NLPSolution3-AutomaticSpeechRecognition-Whisper/` — a full
Android app doing this exact integration: encoder on DSP via QAIRT/SNPE C++
JNI, decoder with autoregressive KV-cache decoding, mel preprocessing from the
`usefulsensors` OpenAI Whisper fork. Its decoder is a TFLite model rather than
our QNN `decoder.bin`, so it's not a literal drop-in, but the app
architecture/JNI plumbing is the right blueprint for a persistent, low-latency
driver instead of the shell-out-per-token approach above.

(For context: Qualcomm's own turnkey answer to all of this is the **Voice AI
SDK**, gated behind Qualcomm Package Manager — https://qpm.qualcomm.com/#/main/tools/details/VoiceAI_ASR
— we don't have access to it. It's a separate product from both QAIRT, the
low-level runtime this whole repo is built on, and "Cloud AI" — Qualcomm's
unrelated *datacenter* accelerator card line, a dead end if you find it while
searching.)

---

## 5. Host environment changes made this session (persist, but check reboot survival)

- **64GB swapfile added**: `/mnt/ssd/swapfile2`, via `sudo fallocate` +
  `mkswap` + `swapon` (not added to `/etc/fstab`) — **will NOT survive a host
  reboot.** If a future large local export OOMs again and `swapon --show`
  doesn't list it, redo:
  ```bash
  sudo fallocate -l 64G /mnt/ssd/swapfile2 && sudo chmod 600 /mnt/ssd/swapfile2 && \
  sudo mkswap /mnt/ssd/swapfile2 && sudo swapon /mnt/ssd/swapfile2
  ```
  Or add an `/etc/fstab` entry to make it permanent.
- **`docker/Dockerfile`**: added `ffmpeg libportaudio2` (apt), `accelerate`
  and `qai-hub-models[whisper-base]` (pip). Image rebuilt (`./scripts/01_build_image.sh qwen3-4b`
  — same tag/extra as before, these are additive).
- **`scripts/00_env.sh`**: `docker_run()` now passes `HF_TOKEN` through to the
  container if set in the calling shell's environment (needed for any gated
  HF model, e.g. Llama). No-op if unset. Also added **`docker_run_networked()`**
  (same mounts, plus `--network host`) for drivers that need both
  `qai_hub_models` (only installed in the image) and on-device `adb` access
  in the same process — the container's `adb` client reaches the host's
  already-running `adb` server over `localhost:5037`, no USB passthrough or
  in-container device auth needed. Used by `run_whisper_decode.py` (§4).
- **HF token**: in `secrets.txt` at the repo root (`META_HF_API_KEY`), account
  `potatopeasea`. Llama-3.2-1B and -3B licenses both accepted on that account.
- **`decoder.bin` pushed to the device**: `/data/local/tmp/whisper_poc/decoder.bin`
  (152MB), alongside the `encoder.bin` already staged there. Both are now
  present for the working decode loop (§4).

---

## 6. `scripts/05_deploy_and_run.sh` generalized for multiple chat templates

Previously hardcoded the Qwen3 `<|im_start|>`/`<|im_end|>` template for *any*
plain-text prompt regardless of model — silently wrong for Llama (would have
produced garbage, not an error). Now branches on `MODEL_ID`:
`llama_v3*` gets the Llama-3.x `<|begin_of_text|><|start_header_id|>...`
template (read directly from the exported bundle's `tokenizer_config.json`
`chat_template`), everything else keeps the existing Qwen3 template. If a
third model family gets added, extend this dispatch rather than assuming one
template fits all.

---

## 7. `scripts/06_voice_assistant.py` — press-Enter-to-talk, mic on this host

**The target device (QCS8550) has no mic**, so this is a host-side orchestrator:
records audio locally (`arecord`), sends it through the on-device Whisper
decode loop (§4) for a transcript, then through on-device Llama (§1,
`genie-t2t-run`) for an answer. Only the transcript *text* ever crosses to
the target — no audio leaves the host.

```
this host's mic --arecord--> 16kHz mono WAV
    --> Whisper-Base encoder/decoder on the QCS8550 NPU  --> transcript
    --> Llama-3.2-1B-Instruct on the QCS8550 NPU (genie-t2t-run)  --> answer
```

Python, not bash like `00`-`05` — needs an interactive `input()` start/stop
loop and to parse two subprocesses' stdout per turn, both awkward in bash.

```bash
cd genie-on-device
python3 scripts/06_voice_assistant.py
# Press ENTER to start recording...
# Recording... press ENTER to stop.
# Transcribing (on-device NPU)...
#   you said: 'What is the capital of France?'
# Asking Llama-3.2-1B...
#   answer: The capital of France is Paris.
```

**No working mic on this host?** `--audio-file PATH` skips recording entirely
and transcribes an existing audio file instead (one turn, then exits — mic
mode loops, this doesn't). Accepts *any* format `ffmpeg` can read — arbitrary
sample rate, stereo, mp3/m4a/etc — normalized via `convert_to_wav()` (`ffmpeg
-ac 1 -ar 16000 -acodec pcm_s16le`) before hitting the same `transcribe()`
path as the mic. Verified against a re-encoded 44.1kHz stereo MP3 of the JFK
clip — transcribed correctly despite the format mismatch.
```bash
python3 scripts/06_voice_assistant.py --audio-file /path/to/recording.m4a
```

**How it's wired:**
- `record_to_wav()` shells out to `arecord -D default -f S16_LE -r 16000 -c 1`
  between two `input()` calls, stops it with `SIGINT` (which `arecord`
  handles by finalizing the WAV header — confirmed working, not just assumed).
  This host has a real capture device (`card 0: PCH [HDA Intel PCH]`,
  confirmed via `arecord -l`); if a future host doesn't, `arecord -l` will
  show no capture devices and this needs a different backend.
- `transcribe()` reuses `run_whisper_decode.py` (§4), extended to accept a
  WAV path (`load_wav()`, stdlib `wave` + int16→float32 normalization, no
  new deps) instead of only the hardcoded JFK demo clip. Regression-tested
  by round-tripping the JFK audio through the new file-loading path — still
  produces the exact verbatim transcript (§4), so the WAV I/O is trusted.
  Invoked through `bash -c "source 00_env.sh && docker_run_networked ..."`
  rather than re-deriving the docker mounts in Python, so it can't drift
  from `00_env.sh`. `run_whisper_decode.py` now also prints a
  `TRANSCRIPT: ...` line specifically so callers can grep it out of the
  progress-log noise (`[encoder] ...`, `[decoder] step N ...`).
- `ask_llama()` shells out to the existing `05_deploy_and_run.sh` unmodified
  and regexes `\[BEGIN\]:\s*(.*?)\[END\]` out of `genie-t2t-run`'s raw
  stdout (verified against a real run's captured output, not guessed).
  **This re-pushes the full ~1.7GB Llama bundle every turn** (measured
  ~6.9s end-to-end for deploy+run) — acceptable for now; see §9 item 4 if a
  faster turn-around is needed later.

Not handled by this first version (see §9): no VAD (recording is manual
start/stop, not silence-triggered), no barge-in/interruption, and each turn
pays the full ~7s Llama redeploy plus Whisper's ~0.65-0.75s/decode-step cost
(§4) — expect roughly (audio duration's worth of decode steps × ~0.7s) + ~7s
per turn, e.g. ~28s for an 11-second question.

---

## 8. Board portability: same QCS8550 chipset, Android vs. Qualcomm's Linux BSP

Swapped to a different physical board (2026-07-21), same chipset — **but a
completely different OS**, which broke every deploy script until fixed here.
Verify which is connected any time you can't tell:
```bash
adb shell cat /etc/os-release   # empty/no such file -> Android; "Ubuntu 22.04..." -> Linux BSP
adb shell cat /sys/devices/soc0/chip_id   # "QCS_KAILUA" -- confirms same QCS8550 silicon either way
```
The new board: `chip_id: QCS_KAILUA`, `hw_platform: HDK` (Qualcomm's own
QCS8550 Hardware Development Kit reference board), `family: Snapdragon`, and
the ADSP/CDSP firmware strings explicitly say `QCS8550W-ADSP`/`QCS8550-CDSP`
— genuinely the same chipset as before, just running Qualcomm's Ubuntu-based
Linux reference distro ("qti-distro") instead of Android.

**Two real incompatibilities, both now handled by a `target_os`/`--target-os`
selector in the scripts (default `android`, pass `linux` for this board):**

1. **Different ABI, so different QAIRT runtime binaries.** Android's
   `genie-t2t-run`/`qnn-net-run` want bionic's `/system/bin/linker64`, which
   doesn't exist on Linux; the Linux board needs the QAIRT SDK's
   `aarch64-oe-linux-gcc11.2` build instead (`file` on both binaries shows
   the difference directly — confirmed the Linux board's own pre-staged
   `/data/AI/qnn-net-run` demo binary is **byte-identical**, md5-matched, to
   QAIRT's `aarch64-oe-linux-gcc11.2/qnn-net-run`, so that's confirmed
   correct, not a guess). The **compiled model bundles themselves
   (`part*.bin`, `encoder.bin`/`decoder.bin`) are OS-independent** — only the
   runtime around them changes.
2. **`/data` is mounted `noexec` on the Linux board** (`mount` shows
   `/dev/sda9 on /data type ext4 (...,noexec,...)`) — confirmed even the
   board's own pre-staged demo binary at `/data/AI/qnn-net-run` can't execute
   in place, so this isn't a permissions mistake on our end, it's a genuine
   platform difference. **`/dev/shm` is exec-allowed** (tmpfs, no `noexec`,
   ~5.5GB free on this board) and was verified to actually run a copied
   binary — used as the Linux staging location instead of
   `/data/local/tmp`. It doesn't survive a reboot, which doesn't matter since
   every script here re-pushes its bundle fresh on every run anyway.

**What changed:**
- `05_deploy_and_run.sh` takes an optional 5th arg, `target_os` (`android`
  default, or `linux`) — selects both the QAIRT runtime dir
  (`aarch64-android` vs `aarch64-oe-linux-gcc11.2`) and the device staging
  base (`/data/local/tmp` vs `/dev/shm`). Verified: Llama-3.2-1B-Instruct
  (ctx2048, §1) redeployed and answered correctly on the Linux board with
  `... "What is the capital of France?" linux`.
- `qnn_device.py`'s `DEVICE_DIR` now reads `WHISPER_DEVICE_DIR` from the
  environment (defaults to the original `/data/local/tmp/whisper_poc` for
  Android). For the Linux board, re-staged a parallel runtime at
  `/dev/shm/whisper_poc` (same `encoder.bin`/`decoder.bin`, but the
  `aarch64-oe-linux-gcc11.2` `qnn-net-run` + libs — the same hexagon-v73 DSP
  skels work unchanged on either OS, since those run on the DSP core, not
  the ARM host). Verified: transcribed the JFK clip correctly on the Linux
  board, ~1.0s/decode-step (vs ~0.7s on the Android board — a bit slower,
  possibly this board's debug-flavored image, not investigated further).
- `06_voice_assistant.py` (§7) takes `--target-os {android,linux}` (default
  `android`), threading through to both `ask_llama()`'s `05_deploy_and_run.sh`
  call and `transcribe()`'s `WHISPER_DEVICE_DIR`. Verified end-to-end with
  `--target-os linux --audio-file <clip>`: correct transcript, coherent (if
  characteristically small-model-hallucinated) answer.
- `docker_run_networked` (`00_env.sh`) passes `WHISPER_DEVICE_DIR` through to
  the container if set, same pattern as the existing `HF_TOKEN` passthrough.

**If you switch boards again**: don't assume either the OS or the exec-path
question — check `/etc/os-release` and re-verify `noexec` with `mount`
(`grep noexec`) rather than reusing this board's answer, since a third board
could differ from both seen so far.

---

## 9. Not started / open items, in priority order

1. ~~Verify the ±512 encoder pattern~~ — **done, §3**: it was a `qnn-net-run`
   fp32-vs-fp16 I/O default, fixed.
2. ~~Write `QnnEncoder`/`QnnDecoder` adapters + wire into `HfWhisperApp`~~ —
   **done, §4**: working end-to-end, produces a correct transcript.
3. ~~Wire Whisper's transcript into Llama's prompt~~ — **done, §7**:
   `scripts/06_voice_assistant.py` is the full press-to-talk loop.
4. **Skip the ~7s full-bundle redeploy in `ask_llama()` (§7) when the
   on-device Llama bundle is already current** — push only `prompt.txt` and
   re-run `genie-t2t-run` directly via `adb shell`, matching the pattern
   `qnn_device.py`'s decode loop already uses for per-step tensors. Worth
   doing once turn latency actually matters.
5. **VAD / silence detection** — `06_voice_assistant.py` currently needs a
   manual ENTER to stop recording; auto-stop-on-silence (Silero/webrtcvad)
   would make it feel more like a real assistant. Also needed for **chunking
   into ≤30s windows** if someone talks longer than Whisper's fixed input.
6. **A real (not shell-out) persistent driver for interactive use** — the
   current ~0.7s/decode-step (§4) and ~7s/turn Llama redeploy (§7) are fine
   for correctness but too slow for a snappy live assistant; see the `qidk`
   reference in §4 for the blueprint.
7. **On-device mic capture**, if the target ever gets a mic and this needs to
   stop depending on a host-side workstation — not attempted, `06_voice_assistant.py`
   sidesteps this entirely by capturing on the host instead (§7).
