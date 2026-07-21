#!/usr/bin/env bash
# Push a Genie model bundle into the Android app's external files dir.
#
# The bundle is ~1.3GB, so it can't live in the APK. It goes to
#   /sdcard/Android/data/<pkg>/files/models/<model-id>/
# which adb can write and the app can read with no runtime permission -- unlike
# /data/local/tmp, which SELinux hides from an untrusted app.
#
# Only the files Genie actually opens are pushed: the context binaries, the
# tokenizer and the two config JSONs. The QAIRT runtime libraries are NOT here;
# they ship inside the APK (see scripts/09_stage_qairt_for_app.sh).
#
# Usage: ./scripts/10_push_app_model.sh [model-id] [package]
set -euo pipefail

MODEL_ID="${1:-llama_v3_2_1b_instruct_ctx4096}"
PKG="${2:-com.qcs.geniechat}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/workspace/output/$MODEL_ID"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/models/$MODEL_ID"

# The AI Hub export writes a nested, device-named subdir under the model dir.
if [ -f "$OUTPUT_DIR/genie_config.json" ]; then
  BUNDLE="$OUTPUT_DIR"
else
  BUNDLE="$(find "$OUTPUT_DIR" -maxdepth 2 -name genie_config.json -printf '%h\n' 2>/dev/null | head -1)"
fi
[ -n "${BUNDLE:-}" ] && [ -d "$BUNDLE" ] || {
  echo "No genie_config.json under $OUTPUT_DIR -- export the model first." >&2
  exit 1
}

adb get-state >/dev/null 2>&1 || { echo "No adb device attached." >&2; exit 1; }

# Fingerprint on sizes+mtimes, not contents: content-hashing 1.3GB every run
# would cost more than the push it saves. Any re-export changes both.
FINGERPRINT="$(find "$BUNDLE" -maxdepth 1 -type f -printf '%f:%s:%T@\n' | sort | sha256sum | cut -c1-32)"
# `|| true` inside the shell command, not outside: adb propagates the device
# shell's exit code, and a missing marker (the normal first-run case) would
# otherwise trip set -e before the comparison below ever runs.
REMOTE_FP="$(adb shell "cat $DEVICE_DIR/.push_fingerprint 2>/dev/null || true" | tr -d '\r\n')"

if [ "$REMOTE_FP" = "$FINGERPRINT" ]; then
  echo "[push] device already has this bundle ($FINGERPRINT), nothing to do"
  exit 0
fi

echo "[push] $BUNDLE"
echo "[push]   -> $DEVICE_DIR"
adb shell "rm -rf $DEVICE_DIR && mkdir -p $DEVICE_DIR"

for f in genie_config.json htp_backend_ext_config.json tokenizer.json; do
  [ -f "$BUNDLE/$f" ] || { echo "Bundle is missing $f" >&2; exit 1; }
  adb push "$BUNDLE/$f" "$DEVICE_DIR/" >/dev/null
done

shopt -s nullglob
BINS=("$BUNDLE"/*.bin)
[ ${#BINS[@]} -gt 0 ] || { echo "Bundle has no .bin context binaries" >&2; exit 1; }
for bin in "${BINS[@]}"; do
  echo "[push]   $(basename "$bin") ($(du -h "$bin" | cut -f1))"
  adb push "$bin" "$DEVICE_DIR/"
done

# adb creates these files owned by `shell`, and the app is a different uid --
# measured on this board, File.canRead() is false without this even though the
# group (ext_data_rw) looks like it should be enough. Symptom is the app showing
# "No model bundle found" for a bundle that is plainly there.
adb shell "chmod -R a+rX $DEVICE_DIR"

# Written last, so an interrupted push is never mistaken for a complete one.
adb shell "echo $FINGERPRINT > $DEVICE_DIR/.push_fingerprint"
adb shell "chmod a+r $DEVICE_DIR/.push_fingerprint"
echo "[push] done ($FINGERPRINT)"
