#!/usr/bin/env bash
# One-time: configure the Qualcomm AI Hub API token.
# Token lives at https://aihub.qualcomm.com -> Account -> Settings -> API Token.
#
# Usage: ./02_configure_hub.sh <API_TOKEN>
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

TOKEN="${1:?Usage: $0 <API_TOKEN>}"

docker_run -e QAI_HUB_API_TOKEN="${TOKEN}" "${IMAGE_NAME}:latest" \
  bash -c 'qai-hub configure --api_token "$QAI_HUB_API_TOKEN"'

echo "Saved to ${HUB_CONFIG_DIR}/client.ini"
