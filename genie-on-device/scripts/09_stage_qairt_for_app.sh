#!/usr/bin/env bash
# Stage the QAIRT pieces the Android app needs into android/app/src/main/.
#
# Nothing under jniLibs/ or cpp/include/ is committed (see android/.gitignore) --
# they are vendor binaries and headers, so the build is a two-step: stage, then
# gradle. Re-run this after changing QAIRT versions.
#
# Usage: ./scripts/09_stage_qairt_for_app.sh [APP_DIR] [QAIRT_HOME] [HEX_ARCH]
#   APP_DIR defaults to app-rn/android (the React Native app). Pass "android"
#   for the original Kotlin/Views app -- both consume the identical staging.
set -euo pipefail

APP_DIR="${1:-app-rn/android}"
QAIRT_HOME="${2:-/mnt/ssd/bryan/AI_SMART/qairt/2.47.0.260601}"
HEX_ARCH="${3:-v73}"        # QCS8550 / Kalama is Hexagon v73
HEX_UPPER="$(echo "$HEX_ARCH" | tr '[:lower:]' '[:upper:]')"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_MAIN="$REPO_ROOT/$APP_DIR/app/src/main"
[ -d "$APP_MAIN" ] || { echo "No such app dir: $APP_MAIN" >&2; exit 1; }
JNILIBS="$APP_MAIN/jniLibs/arm64-v8a"
INCLUDE="$APP_MAIN/cpp/include"

# Deliberately NOT the whole 413MB lib dir. This is the closure Genie actually
# dlopens for a prebuilt HTP context binary. libQnnHtpPrepare.so (86MB) is only
# needed to compile a graph on-device, which we never do -- we ship .bin
# context binaries. If Genie ever reports a missing backend library, add it here.
HOST_LIBS=(
  libGenie.so
  libQnnHtp.so
  "libQnnHtp${HEX_UPPER}Stub.so"
  libQnnSystem.so
)

# The Hexagon-side skel. It is an aarch64-hosted file only in the sense that we
# ship it in the APK; the DSP loads it through FastRPC, which is why
# ADSP_LIBRARY_PATH must point at the app's nativeLibraryDir at runtime.
DSP_LIB="$QAIRT_HOME/lib/hexagon-$HEX_ARCH/unsigned/libQnnHtp${HEX_UPPER}Skel.so"

echo "[stage] app=$APP_DIR QAIRT_HOME=$QAIRT_HOME hex=$HEX_ARCH"
[ -d "$QAIRT_HOME" ] || { echo "No such QAIRT_HOME: $QAIRT_HOME" >&2; exit 1; }

rm -rf "$JNILIBS" "$INCLUDE"
mkdir -p "$JNILIBS" "$INCLUDE"

for lib in "${HOST_LIBS[@]}"; do
  src="$QAIRT_HOME/lib/aarch64-android/$lib"
  [ -f "$src" ] || { echo "Missing QAIRT lib: $src" >&2; exit 1; }
  cp -f "$src" "$JNILIBS/"
done

[ -f "$DSP_LIB" ] || { echo "Missing DSP skel: $DSP_LIB" >&2; exit 1; }
cp -f "$DSP_LIB" "$JNILIBS/"

# Genie's public headers only; the bridge includes <Genie/GenieDialog.h>.
cp -rf "$QAIRT_HOME/include/Genie" "$INCLUDE/"

echo "[stage] staged $(ls "$JNILIBS" | wc -l) libs ($(du -sh "$JNILIBS" | cut -f1)) into $JNILIBS"
echo "[stage] headers -> $INCLUDE/Genie"
