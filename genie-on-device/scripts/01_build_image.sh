#!/usr/bin/env bash
# Build the isolated Python 3.10 + qai-hub-models toolchain image.
#
# Usage: ./01_build_image.sh <model-pip-extra>
#   e.g. ./01_build_image.sh qwen3-4b
#
# The extra name comes from qai_hub_models/models/<model_id>/README.md's
# "pip install" line -- it is not always the same string as the model id
# (which uses underscores, e.g. qwen3_4b).
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

MODEL_EXTRA="${1:-}"
TAG="${MODEL_EXTRA:-base}"

if [ -z "${MODEL_EXTRA}" ]; then
  echo "No model extra given; building base image without a model preinstalled." >&2
fi

docker build \
  --build-arg "MODEL_EXTRA=${MODEL_EXTRA}" \
  -t "${IMAGE_NAME}:${TAG}" \
  -t "${IMAGE_NAME}:latest" \
  "${DOCKER_DIR}"

echo "Built ${IMAGE_NAME}:${TAG}"
