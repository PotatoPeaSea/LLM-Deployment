#!/usr/bin/env bash
# Export/compile a qai_hub_models LLM for genie/QNN on-device deployment.
#
# Usage: ./04_export_model.sh <model_id> <chipset> [runtime] [extra export args...]
#   e.g. ./04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt
#
# NOTE (QCS8550): Qwen3-4B only LOADS on this chip at context length 512.
# The default 4096 (and 1024) fail on-device at model load with
# "Could not create context from binary ... err 1002" -- the 4-part model
# exceeds the DSP memory budget. Pass a short context length:
#   ./04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt --context-lengths 512
# See docs/README.md finding #5 for the full analysis.
#
# <chipset> is the "chipset:" attribute value from 03_list_devices.sh, not
# the human-readable device name (e.g. "qualcomm-qcs8550-proxy", not
# "QCS8550 (Proxy)").
#
# Non-flagship chipsets are typically AI Hub "proxy" compile targets with no
# hosted physical device, so this defaults to --skip-profiling (AI Hub cannot
# run cloud-hosted perf tests on a proxy target -- there's no real device
# behind it to run them on). Add your own extra args at the end to override,
# e.g. drop that flag for a flagship device that does have a hosted device
# farm.
#
# 01_build_image.sh installs `qai-hub-models` unpinned, so its CLI flags can
# and do drift between image rebuilds -- confirmed 2026-07-27: an earlier
# version of this script also hardcoded --skip-inferencing, which a later
# rebuild's qai-hub-models no longer recognizes at all ("unrecognized
# arguments"). If export fails with an unrecognized/removed argument, run
# `qai-hub-models export <model_id> --help` inside the container (see
# docs/README.md's own warning about this) before assuming this script is
# right.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

MODEL_ID="${1:?Usage: $0 <model_id> <chipset> [runtime] [extra args...]}"
CHIPSET="${2:?Usage: $0 <model_id> <chipset> [runtime] [extra args...]}"
RUNTIME="${3:-geniex_qairt}"
shift $(( $# >= 3 ? 3 : $# ))
EXTRA_ARGS=("$@")

MODEL_OUTPUT_DIR="/workspace/output/${MODEL_ID}"

docker_run "${IMAGE_NAME}:latest" \
  qai-hub-models export "${MODEL_ID}" \
    --runtime "${RUNTIME}" \
    --chipset "${CHIPSET}" \
    --skip-profiling \
    --output-dir "${MODEL_OUTPUT_DIR}" \
    "${EXTRA_ARGS[@]}"

echo "Exported genie bundle to ${OUTPUT_DIR}/${MODEL_ID}"
