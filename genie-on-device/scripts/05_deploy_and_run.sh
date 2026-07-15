#!/usr/bin/env bash
# Assemble a self-contained Genie bundle (compiled model + QAIRT runtime libs +
# genie-t2t-run binary), push it to the connected Android device via adb, and
# run a prompt through genie-t2t-run on the device's Hexagon NPU.
#
# Runs on the HOST (needs host adb + the QAIRT SDK on the host filesystem).
#
# IMPORTANT: the exported bundle from 04_export_model.sh contains ONLY the model
# (part*.bin), genie_config.json, htp_backend_ext_config.json and tokenizer
# files. The runtime (genie-t2t-run + *.so) is NOT in it -- it comes from the
# QAIRT SDK, whose version should match/exceed the one used at compile time
# (see the "qairt:" line printed at the end of the export, e.g. 2.45.0).
#
# Usage:
#   ./05_deploy_and_run.sh <model_id> <qairt_sdk_path> <hexagon_arch> "<prompt>"
# Example (QCS8550 = hexagon v73):
#   ./05_deploy_and_run.sh qwen3_4b /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 \
#     "What is the capital of France?"
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

MODEL_ID="${1:?Usage: $0 <model_id> <qairt_sdk_path> <hexagon_arch> \"<prompt>\"}"
QAIRT_HOME="${2:?Need path to QAIRT SDK (e.g. /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601)}"
HEX_ARCH="${3:?Need hexagon arch, e.g. v73 for QCS8550}"
PROMPT="${4:?Need a prompt string}"

# Locate the exported bundle (the export writes a nested, device-named subdir).
BUNDLE_PARENT="${OUTPUT_DIR}/${MODEL_ID}"
BUNDLE_DIR="$(find "${BUNDLE_PARENT}" -maxdepth 1 -type d -name '*geniex*' | head -n1)"
[ -z "${BUNDLE_DIR}" ] && BUNDLE_DIR="${BUNDLE_PARENT}"
if [ ! -f "${BUNDLE_DIR}/genie_config.json" ]; then
  echo "No genie_config.json under ${BUNDLE_DIR}. Run 04_export_model.sh first." >&2
  exit 1
fi

ANDROID_LIB="${QAIRT_HOME}/lib/aarch64-android"
ANDROID_BIN="${QAIRT_HOME}/bin/aarch64-android"
DSP_LIB="${QAIRT_HOME}/lib/hexagon-${HEX_ARCH}/unsigned"
for p in "${ANDROID_LIB}" "${ANDROID_BIN}/genie-t2t-run" "${DSP_LIB}"; do
  [ -e "${p}" ] || { echo "Missing QAIRT component: ${p}" >&2; exit 1; }
done

# --- Assemble a flat staging dir: bundle + libs + binary + dsp skels -----------
STAGE_DIR="${WORKSPACE_DIR}/deploy/${MODEL_ID}"
rm -rf "${STAGE_DIR}"; mkdir -p "${STAGE_DIR}/dsp"
cp -rf "${BUNDLE_DIR}/"* "${STAGE_DIR}/"
cp -f "${ANDROID_LIB}/"*.so "${STAGE_DIR}/"
cp -f "${ANDROID_BIN}/genie-t2t-run" "${STAGE_DIR}/"
cp -f "${DSP_LIB}/"*.so "${STAGE_DIR}/dsp/"

# --- Build prompt.txt with the Qwen3 chat template + REAL newlines -------------
# genie-t2t-run --prompt_file avoids all the shell-quoting/newline pain of -p.
# If the caller already passed a fully-templated prompt (contains <|im_start|>),
# use it verbatim; otherwise wrap their plain text in the Qwen3-4B template.
PROMPT_FILE="${STAGE_DIR}/prompt.txt"
if printf '%s' "${PROMPT}" | grep -q '<|im_start|>'; then
  printf '%s' "${PROMPT}" > "${PROMPT_FILE}"
else
  # Qwen3-4B template (see tutorial "Prompt Formats" table). Real newlines.
  printf '<|im_start|>system\nYou are a helpful AI assistant.<|im_end|>\n<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n' "${PROMPT}" > "${PROMPT_FILE}"
fi
echo "Staged deploy dir: ${STAGE_DIR} ($(du -sh "${STAGE_DIR}" | cut -f1))"

# --- Push to device ------------------------------------------------------------
DEVICE_DIR="/data/local/tmp/genie_${MODEL_ID}"
echo "Pushing to ${DEVICE_DIR} (this is a few GB; will take a while) ..."
adb shell "rm -rf ${DEVICE_DIR}; mkdir -p ${DEVICE_DIR}"
adb push "${STAGE_DIR}/." "${DEVICE_DIR}/" >/dev/null
adb shell "chmod 755 ${DEVICE_DIR}/genie-t2t-run"

# --- Run -----------------------------------------------------------------------
# LD_LIBRARY_PATH -> aarch64-android libs (flat in DEVICE_DIR)
# ADSP_LIBRARY_PATH -> hexagon DSP skels (in ./dsp)
echo "Running genie-t2t-run on device ..."
adb shell "cd ${DEVICE_DIR} && \
  export LD_LIBRARY_PATH=${DEVICE_DIR}:\${LD_LIBRARY_PATH} && \
  export ADSP_LIBRARY_PATH=${DEVICE_DIR}/dsp && \
  ./genie-t2t-run -c genie_config.json --prompt_file prompt.txt"
