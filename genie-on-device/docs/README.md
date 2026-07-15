# On-device LLM deployment via Qualcomm AI Hub Genie (QCS8550)

Deploys an LLM from Qualcomm AI Hub Models to an Android device on a QCS8550
(codename `kalama`) chipset, using the QNN/Genie NPU runtime described in
https://github.com/qualcomm/ai-hub-apps/tree/main/tutorials/llm_on_genie.

All host-side tooling runs inside a Docker container (see `docker/Dockerfile`)
so nothing is installed into the machine's global Python environment. The
container needs Python 3.10 specifically (qai-hub-models pins `>=3.10,<3.14`),
which may conflict with other projects on the host.

## Key findings from setting this up (read before changing models)

1. **AI Hub's own model catalog page and the published PyPI package can be
   out of sync.** The pip extra name for a model is not always the model id
   shown on aihub.qualcomm.com, and the extra may not exist yet even if the
   model is listed on the website. Always check
   `qai_hub_models/models/<model_id>/README.md` inside the installed package
   for the real extra name and export invocation before trusting docs.

2. **Not every model on AI Hub supports the Genie/QNN NPU runtime.** Check
   `info.yaml` in the model's package directory:
   ```
   llm_details:
     genie_compatible: true|false
     geniex_qairt_compatible: true|false
     geniex_llamacpp_compatible: true|false
   ```
   Example: `gemma_4_e4b_it` is `genie_compatible: false` — it is only
   distributed as a llama.cpp-compatible GGUF
   (`google/gemma-4-E4B-it-qat-q4_0-gguf`), and running it on-device requires
   a llama.cpp/Android build, not this genie pipeline, regardless of chipset.
   `qwen3_4b` is `genie_compatible: true` and was used for the first working
   deployment.

3. **The "officially validated" chipset list (`perf.yaml` /
   `supported_chipsets`) undersells what's actually usable.** It only lists
   chipsets AI Hub has published profiling numbers for (recent flagships:
   8 Elite, 8 Elite Gen 5, X Elite, X2 Elite, QCS9075). Older chipsets like
   QCS8550 can still be valid **compile targets** even when absent from that
   list. Confirm via the live device catalog instead of the perf table:
   ```python
   import qai_hub as hub
   for d in hub.get_devices():
       print(d.name, d.attributes)
   ```
   QCS8550 shows up as **`QCS8550 (Proxy)`**, with attributes including
   `chipset:qualcomm-qcs8550-proxy`, `hexagon:v73`, `framework:qnn`,
   `htp-supports-fp16:true` — i.e. it is a valid compile target for QNN
   models even though it won't have hosted-device profiling numbers. "Proxy"
   devices compile against the target architecture but aren't in AI Hub's
   physical device farm for automatic profiling/benchmarking.

4. **Genie itself is being deprecated in favor of "GenieX"** per the model
   READMEs ("Genie support will be deprecated soon" /
   https://geniex.aihub.qualcomm.com). This tutorial still uses classic
   Genie (`genie-t2t-run`); revisit GenieX before doing new deployments after
   mid-2026.

5. **The exported bundle contains ONLY the model, not the runtime.** This is
   the single biggest gotcha. `04_export_model.sh` produces a directory with:
   `part1..N_of_N.bin` (compiled QNN context binaries), `genie_config.json`,
   `htp_backend_ext_config.json`, and tokenizer files. It does **not** contain
   `genie-t2t-run` or any `.so` libraries. The runtime comes **separately from
   the QAIRT SDK** and must be pushed to the device alongside the model. See
   "Runtime (QAIRT SDK)" below.

6. Minimum QNN/QAIRT SDK version is model-specific (the export prints the exact
   `qairt:` build it compiled with at the end, e.g. `2.45.0.260326154327` for
   Qwen3-4B; also in `info.yaml`'s `minimum_qnn_sdk_version`). The device does
   not need a system-wide QAIRT install, but the QAIRT runtime `.so` files +
   `genie-t2t-run` binary (from a QAIRT SDK whose version >= the compile
   version) must be pushed alongside the model.

## Runtime (QAIRT SDK)

The QAIRT SDK provides the on-device runtime. Per the tutorial, download it
from the Qualcomm Software Center (login-gated):
https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community
There is no reliable unauthenticated direct-download URL — the software center
requires a Qualcomm account.

On this machine an existing SDK was found and reused:
**`/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601`** (v2.47.0 — newer than the
2.45.0 used at compile, which is fine; QAIRT runs older context binaries on a
newer runtime).

The three things the deploy pulls from the SDK, for QCS8550 (Hexagon **v73**):

| Need | SDK path |
|------|----------|
| `genie-t2t-run` binary | `bin/aarch64-android/genie-t2t-run` |
| Runtime libs (libGenie.so, libQnnHtp.so, libQnnHtpV73Stub.so, libQnnSystem.so, …) | `lib/aarch64-android/*.so` |
| Hexagon DSP skels | `lib/hexagon-v73/unsigned/*.so` |

The hexagon arch must match the chip: **v73** for QCS8550. (v75/v79/v81 for
newer Snapdragons — see the perf table / device attributes.)

On device, `05_deploy_and_run.sh` sets:
- `LD_LIBRARY_PATH` → the dir holding the aarch64-android `.so` files
- `ADSP_LIBRARY_PATH` → the dir holding the hexagon-v73 skel `.so` files
- runs `genie-t2t-run -c genie_config.json --prompt_file prompt.txt`
  (a prompt file with **real newlines** in the model's chat template is far
  more robust than `-p` with escaped `\n` through nested adb-shell quoting).

## Directory layout

```
genie-on-device/
  docker/Dockerfile        Isolated Python 3.10 + qai-hub-models toolchain
  scripts/                 Automation scripts (see below)
  workspace/               Persists across runs; mounted into the container:
    qai_hub_config/        AI Hub API token (client.ini)
    qaihm_cache/           Cached source-checkpoint downloads (~/.qaihm) so the
                           17.8 GB re-download is avoided on re-runs
    output/<model>/…       Exported bundle (compiled .bin + config + tokenizer)
    deploy/<model>/        Host-assembled push staging: bundle + QAIRT libs +
                           genie-t2t-run + prompt.txt (built by script 05)
  docs/                    This file
```

## Usage

```bash
# 1. Build the toolchain image (bakes in a model's pip extra so heavy deps
#    like torch aren't re-downloaded on every run)
./scripts/01_build_image.sh qwen3-4b

# 2. One-time: configure your AI Hub API token (from
#    https://aihub.qualcomm.com -> Account -> Settings -> API Token)
./scripts/02_configure_hub.sh <API_TOKEN>

# 3. Find the right AI Hub chipset id for your device
./scripts/03_list_devices.sh QCS8550
# -> look for the "chipset:..." attribute, e.g. chipset:qualcomm-qcs8550-proxy

# 4. Export/compile the model targeting that chipset
#    (--device and --chipset are mutually exclusive in the underlying CLI;
#    chipset is used here since it doesn't require picking one specific
#    device instance)
./scripts/04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt

# 5. Push the bundle + QAIRT runtime to the device and run inference.
#    Args: <model_id> <qairt_sdk_path> <hexagon_arch> "<prompt>"
#    (plain prompts are auto-wrapped in the Qwen3 chat template)
./scripts/05_deploy_and_run.sh qwen3_4b \
  /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 \
  "What is the capital of France?"
```

Note: the export CLI's actual `--help` output for `qwen3_4b` has no `--precision`
flag (despite the model's own generated README snippet mentioning
`--precision w4a16`) -- that flag is stale/model-generation-specific. Always
check `qai-hub-models export <model_id> --help` inside the container rather
than trusting a README verbatim.

## Known caveats / things to double check for a new model

- Re-run step 1's `MODEL_EXTRA` build arg per new model (or install ad hoc
  inside a throwaway container) — the extra name convention uses hyphens
  (`qwen3-4b`) while the model id/module path uses underscores (`qwen3_4b`).
- Check `info.yaml`'s `genie_compatible` flag before starting; if false, this
  whole pipeline doesn't apply and you need the llama.cpp path instead.
- QCS8550 (and similarly older/non-flagship chipsets) are compile targets
  only — expect to do your own on-device benchmarking since AI Hub has no
  published perf numbers for them.
