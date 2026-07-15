#!/usr/bin/env bash
# Export/compile a qai_hub_models LLM for genie/QNN on-device deployment.
#
# Usage: ./04_export_model.sh <model_id> <chipset> [runtime] [extra export args...]
#   e.g. ./04_export_model.sh qwen3_4b qualcomm-qcs8550-proxy geniex_qairt
#
# <chipset> is the "chipset:" attribute value from 03_list_devices.sh, not
# the human-readable device name (e.g. "qualcomm-qcs8550-proxy", not
# "QCS8550 (Proxy)").
#
# Non-flagship chipsets are typically AI Hub "proxy" compile targets with no
# hosted physical device, so this defaults to --skip-profiling
# --skip-inferencing (AI Hub cannot run cloud-hosted perf/inference tests on
# a proxy target -- there's no real device behind it to run them on). Add
# your own extra args at the end to override, e.g. drop those flags for a
# flagship device that does have a hosted device farm.
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
    --skip-profiling --skip-inferencing \
    --output-dir "${MODEL_OUTPUT_DIR}" \
    "${EXTRA_ARGS[@]}"

echo "Exported genie bundle to ${OUTPUT_DIR}/${MODEL_ID}"
