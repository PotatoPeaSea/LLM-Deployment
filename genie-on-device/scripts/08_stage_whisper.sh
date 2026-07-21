#!/usr/bin/env bash
# Stage the Whisper-Base QNN runtime on the connected device.
#
# The Genie LLM bundle is pushed by 05_deploy_and_run.sh (or, for the chatbot,
# by chatbot/llama.py). Whisper is separate: it's raw QNN context binaries
# driven by qnn-net-run, not a genie_config.json bundle, so it needs its own
# staging. This script existed only as ad-hoc commands in
# docs/HANDOFF-whisper-llama-voice-assistant.md §4 until now -- which meant
# attaching a fresh board left the voice path silently broken.
#
# Usage:
#   ./08_stage_whisper.sh [qairt_sdk_path] [hexagon_arch] [target_os]
# Example (QCS8550 = hexagon v73):
#   ./08_stage_whisper.sh /mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601 v73 android
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

QAIRT_HOME="${1:-/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601}"
HEX_ARCH="${2:-v73}"
TARGET_OS="${3:-android}"

case "${TARGET_OS}" in
  android) RUNTIME_DIR_NAME="aarch64-android";          DEVICE_DIR="/data/local/tmp/whisper_poc"; STAGE_NAME="whisper_poc" ;;
  # Qualcomm's Linux BSP mounts /data noexec; /dev/shm is exec-allowed.
  linux)   RUNTIME_DIR_NAME="aarch64-oe-linux-gcc11.2"; DEVICE_DIR="/dev/shm/whisper_poc";       STAGE_NAME="whisper_poc_linux" ;;
  *) echo "Unknown target_os '${TARGET_OS}', expected 'android' or 'linux'" >&2; exit 1 ;;
esac

BUNDLE_DIR="${OUTPUT_DIR}/whisper_base/whisper_base-qnn_context_binary-float-qualcomm_qcs8550_proxy"
RUNTIME_LIB="${QAIRT_HOME}/lib/${RUNTIME_DIR_NAME}"
RUNTIME_BIN="${QAIRT_HOME}/bin/${RUNTIME_DIR_NAME}"
DSP_LIB="${QAIRT_HOME}/lib/hexagon-${HEX_ARCH}/unsigned"

for p in "${BUNDLE_DIR}/encoder.bin" "${BUNDLE_DIR}/decoder.bin" \
         "${RUNTIME_LIB}" "${RUNTIME_BIN}/qnn-net-run" "${DSP_LIB}"; do
  [ -e "${p}" ] || { echo "Missing component: ${p}" >&2; exit 1; }
done

STAGE_DIR="${WORKSPACE_DIR}/deploy/${STAGE_NAME}"
rm -rf "${STAGE_DIR}"; mkdir -p "${STAGE_DIR}/dsp"
cp -f "${BUNDLE_DIR}/encoder.bin" "${BUNDLE_DIR}/decoder.bin" "${STAGE_DIR}/"
cp -f "${RUNTIME_LIB}/"*.so "${STAGE_DIR}/"
cp -f "${RUNTIME_BIN}/qnn-net-run" "${STAGE_DIR}/"
cp -f "${DSP_LIB}/"*.so "${STAGE_DIR}/dsp/"
echo "Staged: ${STAGE_DIR} ($(du -sh "${STAGE_DIR}" | cut -f1))"

echo "Pushing to ${DEVICE_DIR} ..."
adb shell "rm -rf ${DEVICE_DIR}; mkdir -p ${DEVICE_DIR}/io"
adb push "${STAGE_DIR}/." "${DEVICE_DIR}/" >/dev/null
adb shell "chmod 755 ${DEVICE_DIR}/qnn-net-run"

# Prove the binary actually runs here rather than discovering it mid-transcription
# (the Linux BSP's noexec /data was found exactly this way).
if adb shell "cd ${DEVICE_DIR} && LD_LIBRARY_PATH=${DEVICE_DIR} ./qnn-net-run --version 2>&1" \
     | grep -qiE "qnn-net-run|version"; then
  echo "OK: qnn-net-run executes on device at ${DEVICE_DIR}"
else
  echo "WARNING: qnn-net-run did not run cleanly at ${DEVICE_DIR}" >&2
fi
