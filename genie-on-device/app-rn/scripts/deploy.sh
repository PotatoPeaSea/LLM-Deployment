#!/usr/bin/env bash
# Build GenieChatRN and push it, plus whichever models you ask for, to an
# attached device. This is the one-command version of the steps in
# ../../docs/ANDROID-RN-APP.md and this file's own README -- read one of those
# first if a step here fails and you need to know *why* it exists.
#
# What this does NOT do:
#   - Export QNN model bundles (workspace/output/<id>) -- that is the cloud
#     compile pipeline one level up (../scripts/04_export_model.sh), and it
#     needs an AI Hub account. See ../../docs/REPRODUCTION.md.
#   - Fetch GGUF weights (workspace/gguf/<id>) -- those are plain downloads,
#     not a build step; see this directory's README for what to fetch and
#     where to put it.
#   - Re-stage the QAIRT vendor libraries into jniLibs/ -- the libs this app
#     needs are already committed there (they're ~28MB total, small enough to
#     track), so a fresh checkout builds without touching QAIRT at all. Only
#     run ../scripts/09_stage_qairt_for_app.sh if you're deliberately
#     upgrading the QAIRT version.
#
# Usage:
#   ./scripts/deploy.sh                              # build + install only
#   ./scripts/deploy.sh --models qwen3_4b,gemma4_e2b  # + push these models
#   ./scripts/deploy.sh --models all                  # + push every model
#   ./scripts/deploy.sh --skip-build --models qwen3_4b  # push only, app already installed
#
# Env overrides:
#   PKG        installed package id (default: com.geniechatrn)
#   VARIANT    gradle build variant: Debug or Release (default: Debug)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # .../genie-on-device
APP_DIR="$REPO_ROOT/app-rn"
PKG="${PKG:-com.geniechatrn}"
VARIANT="${VARIANT:-Debug}"
MODELS=""
SKIP_BUILD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --models) MODELS="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

# Runtime + push-script for every model ModelStore.kt declares. Keep this in
# sync with MODELS in android/app/src/main/java/com/geniechatrn/genie/ModelStore.kt
# -- there is no way to read the Kotlin registry from bash, so this is the one
# place that duplicates it.
ALL_MODELS="llama_v3_2_1b_instruct_ctx4096 llama_v3_2_3b_instruct_ctx2048 qwen3_4b qwen3_5_2b gemma4_e2b"
gguf_model() { case "$1" in qwen3_5_2b|gemma4_e2b) return 0 ;; *) return 1 ;; esac; }

if [ "$MODELS" = "all" ]; then
  MODEL_LIST="$ALL_MODELS"
elif [ -n "$MODELS" ]; then
  MODEL_LIST="$(echo "$MODELS" | tr ',' ' ')"
else
  MODEL_LIST=""
fi

adb get-state >/dev/null 2>&1 || { echo "No adb device attached." >&2; exit 1; }

if [ "$SKIP_BUILD" -eq 0 ]; then
  command -v node >/dev/null 2>&1 || { echo "node not found -- install Node 20.x first." >&2; exit 1; }

  echo "[deploy] bundling JS (this is what ships in the APK -- no Metro needed on-device)"
  cd "$APP_DIR"
  node node_modules/.bin/react-native bundle \
    --platform android --dev false --entry-file index.js \
    --bundle-output android/app/src/main/assets/index.android.bundle \
    --assets-dest android/app/src/main/res

  echo "[deploy] gradle assemble${VARIANT} + install${VARIANT}"
  cd android
  ./gradlew "install${VARIANT}"
  cd "$REPO_ROOT"
else
  echo "[deploy] --skip-build: reusing whatever APK is already installed"
fi

for id in $MODEL_LIST; do
  # Invoked via `bash`, not execute bit: this repo runs with core.fileMode=false,
  # so a fresh clone checks these out as non-executable regardless of what's on
  # disk in any one checkout.
  if gguf_model "$id"; then
    bash "$REPO_ROOT/scripts/11_push_gguf_model.sh" "$id" "$PKG"
  else
    bash "$REPO_ROOT/scripts/10_push_app_model.sh" "$id" "$PKG"
  fi
done

echo "[deploy] launching"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "[deploy] done"
