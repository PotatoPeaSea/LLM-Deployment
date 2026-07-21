#!/usr/bin/env bash
# Send a single prompt to the ALREADY-DEPLOYED model on the device.
# Reuses the on-device bundle at $GENIE_DEVICE_DIR -- only the tiny prompt.txt
# is pushed, so this is fast (no 3.5 GB re-push like 05_deploy_and_run.sh).
#
# Usage:
#   ./ask.sh "What is the capital of France?"
#   ./ask.sh "Write a haiku about the ocean" --no-think   # skip <think> block
#   ./ask.sh "<|im_start|>...full template..."            # pass raw if it
#                                                          # already has tags
set -euo pipefail
DEVICE_DIR="${GENIE_DEVICE_DIR:-/data/local/tmp/genie_qwen3_4b}"
PROMPT="${1:?Usage: $0 \"your question\" [--no-think]}"
MODE="${2:-}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if printf '%s' "$PROMPT" | grep -q '<|im_start|>'; then
  printf '%s' "$PROMPT" > "$WORK/prompt.txt"          # already templated
else
  # Qwen3-4B chat template with REAL newlines.
  printf '<|im_start|>system\nYou are a helpful AI assistant.<|im_end|>\n<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n' "$PROMPT" > "$WORK/prompt.txt"
  # Qwen3 thinks by default; --no-think prefills an empty think block to skip it.
  [ "$MODE" = "--no-think" ] && printf '<think>\n\n</think>\n\n' >> "$WORK/prompt.txt"
fi

adb push "$WORK/prompt.txt" "$DEVICE_DIR/prompt.txt" >/dev/null
adb shell "cd $DEVICE_DIR && \
  export LD_LIBRARY_PATH=\$PWD:\$LD_LIBRARY_PATH && \
  export ADSP_LIBRARY_PATH=\$PWD/dsp && \
  ./genie-t2t-run -c genie_config.json --prompt_file prompt.txt"
