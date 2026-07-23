#!/usr/bin/env bash
# Push a GGUF model bundle into the Android app's external files dir.
#
# The GenieX counterpart to 10_push_app_model.sh. A GGUF "bundle" is far simpler
# than a QNN one -- the weights are a single blob and the tokenizer lives inside
# them -- so this pushes at most two files:
#
#   <model-id>/
#     Qwen3.5-2B-Q4_0.gguf   the weights
#     mmproj-F16.gguf        the vision projector, only for a VLM
#     .push_complete         written LAST; ModelStore keys on it
#
# Unlike the QNN bundle there is no genie_config.json to act as the "this is
# complete" marker, and a half-copied 1.15GB file looks exactly like a whole one
# to a size check -- hence the explicit marker. See ModelStore.isPushedBundle.
#
# Source dir defaults to workspace/gguf/<model-id>, override with GGUF_SRC.
#
# Usage: ./scripts/11_push_gguf_model.sh [model-id] [package]
set -euo pipefail

MODEL_ID="${1:-qwen3_5_2b}"
PKG="${2:-com.geniechatrn}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${GGUF_SRC:-$REPO_ROOT/workspace/gguf/$MODEL_ID}"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/models/$MODEL_ID"

[ -d "$SRC" ] || {
  echo "No source dir $SRC" >&2
  echo "Put the .gguf (and mmproj, for a VLM) there, or set GGUF_SRC." >&2
  exit 1
}

shopt -s nullglob
GGUFS=("$SRC"/*.gguf)
[ ${#GGUFS[@]} -gt 0 ] || { echo "No .gguf files in $SRC" >&2; exit 1; }

adb get-state >/dev/null 2>&1 || { echo "No adb device attached." >&2; exit 1; }

# Same trick as the QNN script: fingerprint sizes+mtimes rather than contents,
# because hashing several GB on every run costs more than the push it saves.
FINGERPRINT="$(find "$SRC" -maxdepth 1 -type f -name '*.gguf' -printf '%f:%s:%T@\n' \
  | sort | sha256sum | cut -c1-32)"
REMOTE_FP="$(adb shell "cat $DEVICE_DIR/.push_complete 2>/dev/null || true" | tr -d '\r\n')"

if [ "$REMOTE_FP" = "$FINGERPRINT" ]; then
  echo "[push] device already has this bundle ($FINGERPRINT), nothing to do"
  exit 0
fi

echo "[push] $SRC"
echo "[push]   -> $DEVICE_DIR"
adb shell "rm -rf $DEVICE_DIR && mkdir -p $DEVICE_DIR"

for gguf in "${GGUFS[@]}"; do
  echo "[push]   $(basename "$gguf") ($(du -h "$gguf" | cut -f1))"
  adb push "$gguf" "$DEVICE_DIR/"
done

# adb creates these files owned by `shell`, and the app runs as a different uid.
# Without this the app can see the directory but every File.canRead() is false,
# and it reports the model as missing. The parent needs +rx too, so the app can
# list it.
#
# Both are best-effort: on an Android 13 device /sdcard is FUSE (sdcardfs is
# gone), which synthesises permissions and rejects chmod outright with
# "Operation not permitted". That refusal is harmless -- the synthesised mode
# already lets the owning app read its own files -- but it must not abort the
# push before the completion marker below is written.
adb shell "chmod -R a+rX $DEVICE_DIR" 2>/dev/null || true
adb shell "chmod a+rx $(dirname "$DEVICE_DIR")" 2>/dev/null || true

# Written last, so an interrupted push is never mistaken for a complete one.
adb shell "echo $FINGERPRINT > $DEVICE_DIR/.push_complete"
adb shell "chmod a+r $DEVICE_DIR/.push_complete"
echo "[push] done ($FINGERPRINT)"
