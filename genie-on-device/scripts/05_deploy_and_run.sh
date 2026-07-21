#!/usr/bin/env bash
# Assemble a self-contained Genie bundle (compiled model + QAIRT runtime libs +
# genie-t2t-run binary), push it to the connected device via adb, and
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
#   ./05_deploy_and_run.sh <model_id> <qairt_sdk_path> <hexagon_arch> "<prompt>" [target_os]
# target_os is "android" (default) or "linux" -- same QCS8550 silicon can run
# either Android or Qualcomm's Ubuntu-based Linux BSP ("qti-distro"), and
# they need different QAIRT runtime binaries (bionic vs glibc, confirmed via
# `file`: Android's genie-t2t-run wants /system/bin/linker64, the Linux one
# wants /lib/ld-linux-aarch64.so.1 -- neither runs on the other's device).
# The compiled model bundle itself (part*.bin) is OS-independent, only the
# runtime differs.
# Example (QCS8550 = hexagon v73):
#   ./05_deploy_and_run.sh qwen3_4b /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 \
#     "What is the capital of France?"
#   ./05_deploy_and_run.sh qwen3_4b /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 \
#     "What is the capital of France?" linux
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

MODEL_ID="${1:?Usage: $0 <model_id> <qairt_sdk_path> <hexagon_arch> \"<prompt>\" [target_os]}"
QAIRT_HOME="${2:?Need path to QAIRT SDK (e.g. /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601)}"
HEX_ARCH="${3:?Need hexagon arch, e.g. v73 for QCS8550}"
PROMPT="${4:?Need a prompt string}"
TARGET_OS="${5:-android}"

case "${TARGET_OS}" in
  android) RUNTIME_DIR_NAME="aarch64-android"; DEVICE_BASE_DIR="/data/local/tmp" ;;
  # Qualcomm's Ubuntu/"qti-distro" Linux BSP mounts /data noexec (confirmed on
  # a QCS8550 HDK board) -- even correctly-built, correctly-permissioned
  # binaries there fail with "Permission denied". /dev/shm (tmpfs) is
  # exec-allowed and has GBs free; it just doesn't survive a reboot, which is
  # fine since this script re-pushes everything fresh on every run anyway.
  linux) RUNTIME_DIR_NAME="aarch64-oe-linux-gcc11.2"; DEVICE_BASE_DIR="/dev/shm" ;;
  *) echo "Unknown target_os '${TARGET_OS}', expected 'android' or 'linux'" >&2; exit 1 ;;
esac

# Locate the exported bundle (the export writes a nested, device-named subdir).
BUNDLE_PARENT="${OUTPUT_DIR}/${MODEL_ID}"
BUNDLE_DIR="$(find "${BUNDLE_PARENT}" -maxdepth 1 -type d -name '*geniex*' | head -n1)"
[ -z "${BUNDLE_DIR}" ] && BUNDLE_DIR="${BUNDLE_PARENT}"
if [ ! -f "${BUNDLE_DIR}/genie_config.json" ]; then
  echo "No genie_config.json under ${BUNDLE_DIR}. Run 04_export_model.sh first." >&2
  exit 1
fi

RUNTIME_LIB="${QAIRT_HOME}/lib/${RUNTIME_DIR_NAME}"
RUNTIME_BIN="${QAIRT_HOME}/bin/${RUNTIME_DIR_NAME}"
DSP_LIB="${QAIRT_HOME}/lib/hexagon-${HEX_ARCH}/unsigned"
for p in "${RUNTIME_LIB}" "${RUNTIME_BIN}/genie-t2t-run" "${DSP_LIB}"; do
  [ -e "${p}" ] || { echo "Missing QAIRT component: ${p}" >&2; exit 1; }
done

# --- Assemble a flat staging dir: bundle + libs + binary + dsp skels -----------
STAGE_DIR="${WORKSPACE_DIR}/deploy/${MODEL_ID}"
rm -rf "${STAGE_DIR}"; mkdir -p "${STAGE_DIR}/dsp"
cp -rf "${BUNDLE_DIR}/"* "${STAGE_DIR}/"
cp -f "${RUNTIME_LIB}/"*.so "${STAGE_DIR}/"
cp -f "${RUNTIME_BIN}/genie-t2t-run" "${STAGE_DIR}/"
cp -f "${DSP_LIB}/"*.so "${STAGE_DIR}/dsp/"

# --- Build prompt.txt with the right chat template + REAL newlines -------------
# genie-t2t-run --prompt_file avoids all the shell-quoting/newline pain of -p.
# If the caller already passed a fully-templated prompt (contains a known
# turn-marker token), use it verbatim; otherwise wrap their plain text in the
# template for MODEL_ID's family (each family uses different special tokens --
# picking the wrong one silently produces garbage, it won't error out).
PROMPT_FILE="${STAGE_DIR}/prompt.txt"
if printf '%s' "${PROMPT}" | grep -qE '<\|im_start\|>|<\|start_header_id\|>'; then
  printf '%s' "${PROMPT}" > "${PROMPT_FILE}"
elif [[ "${MODEL_ID}" == llama_v3* ]]; then
  # Llama-3.x template (tokenizer_config.json chat_template). Real newlines.
  printf '<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou are a helpful AI assistant.<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n%s<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n' "${PROMPT}" > "${PROMPT_FILE}"
else
  # Qwen3-4B template (see tutorial "Prompt Formats" table). Real newlines.
  printf '<|im_start|>system\nYou are a helpful AI assistant.<|im_end|>\n<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n' "${PROMPT}" > "${PROMPT_FILE}"
fi
echo "Staged deploy dir: ${STAGE_DIR} ($(du -sh "${STAGE_DIR}" | cut -f1))"

# --- Push to device ------------------------------------------------------------
DEVICE_DIR="${DEVICE_BASE_DIR}/genie_${MODEL_ID}"
echo "Pushing to ${DEVICE_DIR} (this is a few GB; will take a while) ..."
adb shell "rm -rf ${DEVICE_DIR}; mkdir -p ${DEVICE_DIR}"
adb push "${STAGE_DIR}/." "${DEVICE_DIR}/" >/dev/null
adb shell "chmod 755 ${DEVICE_DIR}/genie-t2t-run"

# --- Run -----------------------------------------------------------------------
# LD_LIBRARY_PATH -> ${RUNTIME_DIR_NAME} libs (flat in DEVICE_DIR)
# ADSP_LIBRARY_PATH -> hexagon DSP skels (in ./dsp)
echo "Running genie-t2t-run on device ..."
adb shell "cd ${DEVICE_DIR} && \
  export LD_LIBRARY_PATH=${DEVICE_DIR}:\${LD_LIBRARY_PATH} && \
  export ADSP_LIBRARY_PATH=${DEVICE_DIR}/dsp && \
  ./genie-t2t-run -c genie_config.json --prompt_file prompt.txt"
