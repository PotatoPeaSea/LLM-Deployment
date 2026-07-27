#!/usr/bin/env bash
# Build GenieChatRN and push it, plus whichever models you ask for, to an
# attached device. This is the one-command version of the steps in
# ../../docs/ANDROID-RN-APP.md and this file's own README -- read one of those
# first if a step here fails and you need to know *why* it exists.
#
# What this does NOT do:
#   - Fetch GGUF weights (workspace/gguf/<id>) -- those are plain downloads,
#     not a build step; see this directory's README for what to fetch and
#     where to put it. No confirmed download URL for every model is recorded
#     in this repo's history, so this is left manual on purpose rather than
#     guessed.
#   - Re-stage the QAIRT vendor libraries into jniLibs/ -- the libs this app
#     needs are already committed there (they're ~28MB total, small enough to
#     track), so a fresh checkout builds without touching QAIRT at all. Only
#     run ../scripts/09_stage_qairt_for_app.sh if you're deliberately
#     upgrading the QAIRT version.
#
# What this DOES do that costs real time/money: for a GENIE model
# (llama_v3_2_1b_instruct_ctx4096, llama_v3_2_3b_instruct_ctx2048, qwen3_4b)
# with no bundle yet in workspace/output/<id>, this runs the cloud-compile
# pipeline itself -- builds the Docker toolchain image if needed, configures
# your AI Hub token if needed, and runs the ~1-hour cloud compile -- so
# `deploy.sh --models qwen3_4b` is a genuine one-command path from a bare
# clone, not "assuming you already ran 01-04 by hand." See ensure_genie_bundle
# below for the exact recipe per model. Needs Docker, an AI Hub account
# ($AI_HUB_API_TOKEN, if not already configured), and for the 3B specifically
# a Hugging Face token with meta-llama access ($HF_TOKEN) -- none of these
# can be fetched automatically, so it fails fast with a clear message if
# they're missing rather than hanging.
#
# Usage:
#   ./scripts/deploy.sh                              # build + install only
#   ./scripts/deploy.sh --models gguf                 # + push qwen3_5_2b + gemma4_e2b --
#                                                      # the two GGUF models, no cloud
#                                                      # compile needed, just a download.
#                                                      # This is the fastest way to get a
#                                                      # working app: no AI Hub account, no
#                                                      # Docker, no cloud export step.
#   ./scripts/deploy.sh --models qwen3_4b,gemma4_e2b  # + push these specific models,
#                                                      # exporting qwen3_4b first if needed
#   ./scripts/deploy.sh --models all                  # + push every model, exporting any
#                                                      # missing GENIE bundle first
#   ./scripts/deploy.sh --skip-build --models qwen3_4b  # push only, app already installed
#
# Left with no --models at all, this just builds and installs the empty app
# shell (no working models loaded yet); --models gguf is the quickest path to
# something you can actually chat with.
#
# Env overrides:
#   PKG        installed package id (default: com.geniechatrn)
#   VARIANT    gradle build variant: Debug or Release (default: Debug)
#   CHIPSET    AI Hub chipset id for a GENIE export (default: qualcomm-qcs8550-proxy)
#   AI_HUB_API_TOKEN  needed only the first time (workspace/qai_hub_config/client.ini
#                      not there yet) and only if a GENIE model needs exporting
#   HF_TOKEN   needed only for llama_v3_2_3b_instruct_ctx2048 (gated meta-llama repo)
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
GGUF_MODELS="qwen3_5_2b gemma4_e2b"
gguf_model() { case "$1" in qwen3_5_2b|gemma4_e2b) return 0 ;; *) return 1 ;; esac; }

# Makes a GENIE model's bundle exist in workspace/output/<id> before it gets
# pushed, running the cloud-compile pipeline (01/02/04) if it doesn't yet.
# The recipe per model -- AI Hub model id, context length, pip extra -- is
# duplicated here from docs/ANDROID-RN-APP.md/docs/REPRODUCTION.md for the
# same reason ALL_MODELS is: there's no registry to read this from
# programmatically. The actual export flags are NOT duplicated here -- this
# delegates to 04_export_model.sh so there's exactly one place that can drift
# out of sync with qai-hub-models' CLI (which is real: 01_build_image.sh
# installs it unpinned, so its flags change across image rebuilds --
# confirmed 2026-07-27 when a previously-valid --skip-inferencing became a
# hard "unrecognized arguments" error and had to be dropped from that script).
ensure_genie_bundle() {
  local id="$1"
  local bundle_dir="$REPO_ROOT/workspace/output/$id"

  # AI Hub nests the actual bundle under a device-named subdir regardless of
  # --output-dir (e.g. workspace/output/qwen3_4b/qwen3_4b-geniex_qairt-.../),
  # so check both direct and one-level-nested, same as 10_push_app_model.sh.
  if [ -f "$bundle_dir/genie_config.json" ] || \
     [ -n "$(find "$bundle_dir" -maxdepth 2 -name genie_config.json 2>/dev/null)" ]; then
    echo "[deploy] $id already exported -> $bundle_dir"
    return
  fi

  echo "[deploy] $id has no exported bundle yet -- running the cloud-compile pipeline"
  echo "[deploy] this is a REAL cloud compile: roughly an hour, needs Docker + an AI Hub account"

  command -v docker >/dev/null 2>&1 || {
    echo "docker not found -- install Docker, or export $id manually per docs/REPRODUCTION.md and re-run." >&2
    exit 1
  }

  # shellcheck source=/dev/null
  source "$REPO_ROOT/scripts/00_env.sh"   # gives us IMAGE_NAME, HUB_CONFIG_DIR

  if [ ! -f "$HUB_CONFIG_DIR/client.ini" ] && [ -z "${AI_HUB_API_TOKEN:-}" ]; then
    echo "No AI Hub token configured yet ($HUB_CONFIG_DIR/client.ini missing)" >&2
    echo "and \$AI_HUB_API_TOKEN is not set. Get one from" >&2
    echo "https://aihub.qualcomm.com -> Account -> Settings -> API Token," >&2
    echo "then re-run with AI_HUB_API_TOKEN=... set." >&2
    exit 1
  fi

  local ai_hub_id ctx pip_extra chipset
  case "$id" in
    llama_v3_2_1b_instruct_ctx4096)
      ai_hub_id=llama_v3_2_1b_instruct; ctx=4096; pip_extra=llama-v3-2-1b-instruct ;;
    llama_v3_2_3b_instruct_ctx2048)
      ai_hub_id=llama_v3_2_3b_instruct; ctx=2048; pip_extra=llama-v3-2-3b-instruct
      if [ -z "${HF_TOKEN:-}" ]; then
        echo "llama_v3_2_3b_instruct is a gated meta-llama repo -- set \$HF_TOKEN" >&2
        echo "(a Hugging Face token with access to meta-llama models) and re-run." >&2
        exit 1
      fi ;;
    qwen3_4b)
      ai_hub_id=qwen3_4b; ctx=512; pip_extra=qwen3-4b ;;
    *)
      echo "No export recipe here for $id -- export it manually per docs/REPRODUCTION.md, then re-run." >&2
      exit 1 ;;
  esac
  chipset="${CHIPSET:-qualcomm-qcs8550-proxy}"

  if [ -f "$HUB_CONFIG_DIR/client.ini" ]; then
    echo "[deploy] AI Hub token already configured"
  else
    echo "[deploy] configuring AI Hub token"
    bash "$REPO_ROOT/scripts/02_configure_hub.sh" "$AI_HUB_API_TOKEN"
  fi

  echo "[deploy] building the toolchain image for $pip_extra (cached after the first run)"
  bash "$REPO_ROOT/scripts/01_build_image.sh" "$pip_extra"

  echo "[deploy] compiling $id in the cloud (chipset=$chipset ctx=$ctx) -- this is the slow step"
  bash "$REPO_ROOT/scripts/04_export_model.sh" "$ai_hub_id" "$chipset" geniex_qairt \
    --context-lengths "$ctx" --output-dir "/workspace/output/$id"

  [ -f "$bundle_dir/genie_config.json" ] || \
     [ -n "$(find "$bundle_dir" -maxdepth 2 -name genie_config.json 2>/dev/null)" ] || {
    echo "Export finished but no genie_config.json under $bundle_dir -- check the qai-hub-models output above." >&2
    exit 1
  }
  echo "[deploy] exported -> $bundle_dir"
}

case "$MODELS" in
  all) MODEL_LIST="$ALL_MODELS" ;;
  gguf) MODEL_LIST="$GGUF_MODELS" ;;
  "") MODEL_LIST="" ;;
  *) MODEL_LIST="$(echo "$MODELS" | tr ',' ' ')" ;;
esac

adb get-state >/dev/null 2>&1 || { echo "No adb device attached." >&2; exit 1; }

if [ "$SKIP_BUILD" -eq 0 ]; then
  command -v node >/dev/null 2>&1 || { echo "node not found -- install Node 20.x first." >&2; exit 1; }

  cd "$APP_DIR"
  if [ ! -d node_modules ]; then
    echo "[deploy] node_modules missing -- running npm install"
    npm install
  fi

  echo "[deploy] bundling JS (this is what ships in the APK -- no Metro needed on-device)"
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
    ensure_genie_bundle "$id"
    bash "$REPO_ROOT/scripts/10_push_app_model.sh" "$id" "$PKG"
  fi
done

echo "[deploy] launching"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "[deploy] done"
