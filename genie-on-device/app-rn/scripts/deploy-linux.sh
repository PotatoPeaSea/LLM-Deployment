#!/usr/bin/env bash
# Build GenieChat and deploy it to a Qualcomm board running Ubuntu.
#
# The Linux counterpart of deploy.sh (which targets Android). Same idea, very
# different machine: there is no APK, no gradle and no Android SDK involved.
# What ships instead is four things, over adb:
#
#   /opt/llama            llama.cpp built for arm64 Linux + the Hexagon backend
#   /opt/node             a Node 20 runtime (the board has no package manager
#                         reachable — see "no network" below)
#   /opt/geniechat/server the app-server (compiled TypeScript)
#   /opt/geniechat/web    the react-native-web bundle
#   /data/models/<id>/    GGUF weights
#
# Usage:
#   bash scripts/deploy-linux.sh                       # build + deploy + start
#   bash scripts/deploy-linux.sh --models gguf         # + push both GGUF models
#   bash scripts/deploy-linux.sh --models qwen3_5_2b   # + push one
#   bash scripts/deploy-linux.sh --skip-build          # deploy what is already built
#   bash scripts/deploy-linux.sh --skip-llama          # skip the slow llama.cpp build
#   bash scripts/deploy-linux.sh --no-start            # deploy only, don't launch
#   bash scripts/deploy-linux.sh --device HTP0         # run on the NPU, not the CPU
#
# Env overrides: PORT (8080), RELAY_PORT (8079), ADB (adb),
# LLAMA_SRC (workspace/llama.cpp), MODELS_SRC (workspace/gguf).
#
# ---------------------------------------------------------------------------
# Three facts about this board drive most of what looks odd below. All three
# were measured, not assumed; docs/UBUNTU-BOARD.md has the detail.
#
#  1. **/data is mounted noexec.** Binaries cannot live there, so the llama.cpp
#     package and Node go to /opt (on /, which has ~11GB free). Models are only
#     ever mmapped for reading, so they go to /data (181GB free) where they fit.
#
#  2. **The board has no network interface** — loopback and tunnel devices only.
#     Nothing can be installed on it, which is why a Node tarball is pushed
#     rather than apt-getting one. It also means:
#       - the browser reaches the UI through `adb forward` (host -> board), and
#       - `web_search` reaches the internet through `adb reverse` (board ->
#         host) into scripts/search-relay.mjs, which runs here.
#
#  3. **CPU beats the NPU for chat.** Measured with llama-bench on
#     Qwen3.5-2B-Q4_0: decode 21.6 t/s on CPU vs 7.0 t/s on HTP0, though the NPU
#     wins prefill 164 vs 101 t/s. Chat is decode-bound, so CPU is the default.
#     `--device HTP0` switches it.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."
APP_DIR="$PWD"
WORKSPACE="$APP_DIR/../workspace"

ADB=${ADB:-adb}
PORT=${PORT:-8080}
RELAY_PORT=${RELAY_PORT:-8079}
LLAMA_SRC=${LLAMA_SRC:-$WORKSPACE/llama.cpp}
SYSROOT=${SYSROOT:-$WORKSPACE/sysroot-jammy-arm64}
MODELS_SRC=${MODELS_SRC:-$WORKSPACE/gguf}
NODE_TARBALL=${NODE_TARBALL:-$WORKSPACE/node-arm64.tar.xz}
NODE_VERSION=${NODE_VERSION:-v20.18.1}

TOOLCHAIN_IMAGE=ghcr.io/snapdragon-toolchain/arm64-linux:v0.1
UBUNTU_ARM64_IMAGE=arm64v8/ubuntu:22.04

MODELS=""
SKIP_BUILD=0
SKIP_LLAMA=0
NO_START=0
DEVICE=none

while [ $# -gt 0 ]; do
    case "$1" in
    --models) MODELS="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --skip-llama) SKIP_LLAMA=1; shift ;;
    --no-start) NO_START=1; shift ;;
    -h | --help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Docker may need a group that the current login session predates; `sg` picks it
# up without a re-login. Fall back to plain docker when already in the group.
docker_run() {
    if docker info >/dev/null 2>&1; then
        docker "$@"
    elif id -nG | tr ' ' '\n' | grep -qx docker; then
        sg docker -c "docker $*"
    else
        die "cannot use docker as $(id -un). Add yourself: sudo usermod -aG docker $(id -un)"
    fi
}

command -v "$ADB" >/dev/null || die "adb not found; set ADB=/path/to/adb"
[ -n "$($ADB devices | awk 'NR>1 && $2=="device"')" ] ||
    die "no adb device. Check the cable, then \`adb devices\`."

# ---------------------------------------------------------------- build ----

if [ "$SKIP_BUILD" = 0 ] && [ "$SKIP_LLAMA" = 0 ]; then
    if [ -x "$LLAMA_SRC/pkg-sysroot/bin/llama-server" ]; then
        say "llama.cpp already built (pkg-sysroot present) — skipping"
    else
        [ -d "$LLAMA_SRC" ] || {
            say "Cloning llama.cpp"
            git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$LLAMA_SRC"
        }

        # Why a sysroot at all: the toolchain image is Debian trixie, whose
        # glibc headers redirect strtol/sscanf to __isoc23_* symbols that only
        # exist in glibc >= 2.38. This board is Ubuntu 22.04 with glibc 2.35, so
        # a stock build links fine and then fails to start with
        # "version `GLIBC_2.38' not found". `-std=gnu17` does NOT fix it —
        # -D_GNU_SOURCE (which the preset sets) implies _ISOC23_SOURCE. Building
        # against real 22.04 headers does.
        if [ ! -d "$SYSROOT/usr/include" ]; then
            say "Building an Ubuntu 22.04 arm64 sysroot (needs qemu/binfmt for arm64)"
            mkdir -p "$SYSROOT"
            docker_run run --rm --platform linux/arm64 -v "$SYSROOT:/out" "$UBUNTU_ARM64_IMAGE" \
                bash -c 'set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update -qq
                    apt-get install -y -qq --no-install-recommends \
                        libc6-dev libstdc++-11-dev libgcc-11-dev >/dev/null
                    mkdir -p /out/usr
                    cp -a /usr/include /out/usr/
                    cp -a /usr/lib     /out/usr/
                    cp -a /lib         /out/'
        fi

        say "Cross-compiling llama.cpp (CPU + Hexagon v73 backends) — several minutes"
        docker_run run --rm -u "$(id -u):$(id -g)" \
            --volume "$LLAMA_SRC:/workspace" --volume "$SYSROOT:/sysroot:ro" \
            --platform linux/amd64 -w /workspace "$TOOLCHAIN_IMAGE" bash -lc '
            set -e
            cp -n docs/backend/snapdragon/CMakeUserPresets.json . 2>/dev/null || true
            BASE="-march=armv8.2a+fp16+dotprod -fvectorize -fno-finite-math-only -flto -D_GNU_SOURCE"
            SYS="--sysroot=/sysroot --gcc-toolchain=/sysroot/usr"
            cmake --preset arm64-linux-snapdragon-release -B build-sysroot \
                -DCMAKE_C_FLAGS="$BASE $SYS" -DCMAKE_CXX_FLAGS="$BASE $SYS" \
                -DCMAKE_EXE_LINKER_FLAGS="$SYS" -DCMAKE_SHARED_LINKER_FLAGS="$SYS"
            cmake --build build-sysroot -j "$(nproc)"
            rm -rf pkg-sysroot
            cmake --install build-sysroot --prefix pkg-sysroot'

        # Cheap guard against the failure above silently coming back.
        if readelf --dyn-syms -W "$LLAMA_SRC/pkg-sysroot/lib/libggml-base.so" 2>/dev/null |
            grep -q 'GLIBC_2\.3[6-9]\|GLIBC_2\.4'; then
            die "built binaries still need glibc > 2.35; the sysroot was not applied"
        fi
    fi
fi

if [ "$SKIP_BUILD" = 0 ]; then
    say "Building the app-server"
    npm --prefix server install --no-audit --no-fund
    npm --prefix server run build

    say "Building the web UI"
    npm run build:web

    say "Checking the bundle actually renders"
    node web/smoke-test.js
fi

[ -x "$LLAMA_SRC/pkg-sysroot/bin/llama-server" ] ||
    die "no llama.cpp build at $LLAMA_SRC/pkg-sysroot (drop --skip-llama, or build it)"

# Node runtime: the board cannot install one, so fetch the official tarball here.
if [ ! -d "$WORKSPACE/node-arm64/bin" ]; then
    [ -f "$NODE_TARBALL" ] || {
        say "Fetching the Node $NODE_VERSION arm64 runtime"
        curl -fsSL -o "$NODE_TARBALL" \
            "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz"
    }
    mkdir -p "$WORKSPACE/node-arm64"
    tar -xJf "$NODE_TARBALL" -C "$WORKSPACE/node-arm64" --strip-components=1
fi

# --------------------------------------------------------------- deploy ----

say "Stopping anything already running on the board"
$ADB shell '[ -x /opt/geniechat/geniechat.sh ] && /opt/geniechat/geniechat.sh stop || true' >/dev/null 2>&1 || true

say "Pushing llama.cpp to /opt/llama"
# /opt, not /data: /data is noexec. See the header.
$ADB shell 'rm -rf /opt/llama && mkdir -p /opt/llama'
$ADB push "$LLAMA_SRC/pkg-sysroot/bin" "$LLAMA_SRC/pkg-sysroot/lib" /opt/llama/ >/dev/null
$ADB shell 'chmod +x /opt/llama/bin/*'

say "Pushing the Node runtime to /opt/node"
$ADB shell 'mkdir -p /opt/node'
$ADB push "$WORKSPACE/node-arm64/bin" "$WORKSPACE/node-arm64/lib" /opt/node/ >/dev/null
$ADB shell 'chmod +x /opt/node/bin/*'

say "Pushing the app-server and UI to /opt/geniechat"
$ADB shell 'rm -rf /opt/geniechat/server /opt/geniechat/web && mkdir -p /opt/geniechat'
$ADB push server/dist /opt/geniechat/server >/dev/null
$ADB push web/dist /opt/geniechat/web >/dev/null
$ADB push scripts/board/geniechat.sh /opt/geniechat/ >/dev/null
$ADB shell 'chmod +x /opt/geniechat/geniechat.sh'

if [ -n "$MODELS" ]; then
    case "$MODELS" in
    all | gguf) LIST="qwen3_5_2b gemma4_e2b" ;;
    *) LIST=$(echo "$MODELS" | tr ',' ' ') ;;
    esac
    for id in $LIST; do
        src="$MODELS_SRC/$id"
        [ -d "$src" ] || die "no weights at $src — see docs/USAGE.md for what to download"
        say "Pushing model $id (this is GBs over USB)"
        $ADB shell "mkdir -p /data/models/$id"
        for f in "$src"/*.gguf; do
            [ -e "$f" ] || die "no .gguf in $src"
            $ADB push "$f" "/data/models/$id/"
        done
    done
fi

# --------------------------------------------------------------- tunnels ---

say "Setting up adb tunnels"
# forward: the workstation's browser reaches the board's app-server.
$ADB forward "tcp:$PORT" "tcp:$PORT" >/dev/null
# reverse: the board's web_search reaches the relay running here.
$ADB reverse "tcp:$RELAY_PORT" "tcp:$RELAY_PORT" >/dev/null
echo "  forward localhost:$PORT      -> board app-server"
echo "  reverse board:$RELAY_PORT    -> host search relay"

if [ "$NO_START" = 1 ]; then
    say "Deployed. Not starting (--no-start)."
    exit 0
fi

say "Starting the app-server on the board (device: $DEVICE)"
$ADB shell "LLAMA_DEVICE=$DEVICE PORT=$PORT GENIE_SEARCH_RELAY=http://127.0.0.1:$RELAY_PORT \
    /opt/geniechat/geniechat.sh start"

cat <<EOF

  Open the app:   http://127.0.0.1:$PORT

  Web search needs the relay running on this machine:
      node scripts/search-relay.mjs $RELAY_PORT

  Board-side controls:
      adb shell /opt/geniechat/geniechat.sh {status|stop|restart}
      adb shell tail -f /tmp/geniechat.log

EOF
