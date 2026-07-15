#!/usr/bin/env bash
# List AI Hub devices matching a filter string (case-insensitive substring
# match against device name), showing their compile-target attributes.
# Useful for finding the right --device value for an older/non-flagship
# chipset that won't show up in a model's perf.yaml "supported_chipsets".
#
# Usage: ./03_list_devices.sh [filter]
#   e.g. ./03_list_devices.sh QCS8550
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/00_env.sh"

FILTER="${1:-}"

docker_run "${IMAGE_NAME}:latest" python3 -c "
import qai_hub as hub
f = '${FILTER}'.lower()
for d in hub.get_devices():
    if f in d.name.lower():
        print(d.name)
        for attr in (d.attributes or []):
            print('   ', attr)
"
