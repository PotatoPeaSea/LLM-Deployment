#!/usr/bin/env bash
# Shared paths/vars sourced by the other scripts in this directory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DOCKER_DIR="${PROJECT_ROOT}/docker"
WORKSPACE_DIR="${PROJECT_ROOT}/workspace"
HUB_CONFIG_DIR="${WORKSPACE_DIR}/qai_hub_config"
OUTPUT_DIR="${WORKSPACE_DIR}/output"
# Persistent cache for qai-hub-models source checkpoint downloads (the 17.8 GB
# qwen3_4b_w4a16.zip and friends). Mounted at /root/.qaihm so re-runs reuse
# the download instead of re-fetching it into the ephemeral container layer.
CACHE_DIR="${WORKSPACE_DIR}/qaihm_cache"

IMAGE_NAME="${GENIE_IMAGE_NAME:-genie-llm-toolchain}"

mkdir -p "${HUB_CONFIG_DIR}" "${OUTPUT_DIR}" "${CACHE_DIR}"

docker_run() {
  docker run --rm \
    -v "${HUB_CONFIG_DIR}:/root/.qai_hub" \
    -v "${CACHE_DIR}:/root/.qaihm" \
    -v "${OUTPUT_DIR}:/workspace/output" \
    ${HF_TOKEN:+-e HF_TOKEN="${HF_TOKEN}"} \
    "$@"
}

# Same as docker_run, but shares the host's network namespace so an adb
# client inside the container can reach the host's already-running adb
# server (and the USB-attached device behind it) over localhost:5037 -- no
# adb keys/USB passthrough needed in the container itself. Used by drivers
# that need both qai_hub_models (only installed in the image) and on-device
# access (e.g. the Whisper qnn-net-run decode loop, see
# docs/HANDOFF-whisper-llama-voice-assistant.md §4).
docker_run_networked() {
  docker run --rm --network host \
    -v "${HUB_CONFIG_DIR}:/root/.qai_hub" \
    -v "${CACHE_DIR}:/root/.qaihm" \
    -v "${OUTPUT_DIR}:/workspace/output" \
    ${HF_TOKEN:+-e HF_TOKEN="${HF_TOKEN}"} \
    ${WHISPER_DEVICE_DIR:+-e WHISPER_DEVICE_DIR="${WHISPER_DEVICE_DIR}"} \
    "$@"
}
