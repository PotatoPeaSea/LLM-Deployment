#!/bin/sh
# GenieChat app-server launcher, as it runs ON THE BOARD.
#
# Deployed to /opt/geniechat/geniechat.sh by ../deploy-linux.sh. Kept as a
# script rather than inlined into the deploy command for two reasons: it is
# what a systemd unit or a manual `ssh board /opt/geniechat/geniechat.sh start`
# invokes, and detaching a long-lived process from `adb shell` needs enough
# care (setsid, closed stdin, redirected output) that doing it by hand each
# time gets it wrong.
#
#   geniechat.sh start    launch detached, if not already running
#   geniechat.sh stop     stop the app-server and any llama-server it owns
#   geniechat.sh restart
#   geniechat.sh status
#   geniechat.sh run      run in the foreground (for debugging)
#
# Environment (all optional, defaults suit this board):
#   LLAMA_ROOT          where the llama.cpp package lives   (/opt/llama)
#   GENIE_MODELS_ROOT   where the GGUFs live                (/data/models)
#   LLAMA_DEVICE        'none' for CPU, 'HTP0' for the NPU  (none — see llama.ts)
#   GENIE_SEARCH_RELAY  web_search relay on the host        (unset = search off)
#   PORT                app-server port                     (8080)
set -eu

APP_ROOT=${APP_ROOT:-/opt/geniechat}
NODE=${NODE:-/opt/node/bin/node}
LOG=${LOG:-/tmp/geniechat.log}
PIDFILE=${PIDFILE:-/tmp/geniechat.pid}

export LLAMA_ROOT=${LLAMA_ROOT:-/opt/llama}
export GENIE_MODELS_ROOT=${GENIE_MODELS_ROOT:-/data/models}
export WEB_ROOT=${WEB_ROOT:-$APP_ROOT/web}
export PORT=${PORT:-8080}

# The binaries live on / because /data is mounted noexec on this board; the
# models live on /data because / has only ~11GB free. Both facts are load-
# bearing — see docs/UBUNTU-BOARD.md.
export LD_LIBRARY_PATH="$LLAMA_ROOT/lib:${LD_LIBRARY_PATH:-}"
# FastRPC hands this path to the DSP, which opens libggml-htp-v73.so itself.
export ADSP_LIBRARY_PATH="$LLAMA_ROOT/lib;${ADSP_LIBRARY_PATH:-}"

running() {
    [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

case "${1:-start}" in
start)
    if running; then
        echo "geniechat already running (pid $(cat "$PIDFILE"))"
        exit 0
    fi
    # setsid + </dev/null is what survives the adb shell that launched us
    # going away: without a new session the process group is killed with it.
    setsid "$NODE" "$APP_ROOT/server/index.js" </dev/null >"$LOG" 2>&1 &
    echo $! >"$PIDFILE"
    sleep 1
    if running; then
        echo "geniechat started (pid $(cat "$PIDFILE")), logging to $LOG"
    else
        echo "geniechat failed to start; log follows:" >&2
        cat "$LOG" >&2
        exit 1
    fi
    ;;
stop)
    if running; then
        kill "$(cat "$PIDFILE")" 2>/dev/null || true
        # The app-server takes llama-server down on SIGTERM; give it a moment
        # before insisting, because an orphaned llama-server holds the model.
        sleep 2
    fi
    pkill -f "$APP_ROOT/server/index.js" 2>/dev/null || true
    pkill -f "$LLAMA_ROOT/bin/llama-server" 2>/dev/null || true
    rm -f "$PIDFILE"
    echo "geniechat stopped"
    ;;
restart)
    "$0" stop
    "$0" start
    ;;
status)
    if running; then
        echo "geniechat running (pid $(cat "$PIDFILE"))"
        curl -s "http://127.0.0.1:$PORT/api/health" || echo "(health endpoint not answering)"
        echo
    else
        echo "geniechat not running"
        exit 1
    fi
    ;;
run)
    exec "$NODE" "$APP_ROOT/server/index.js"
    ;;
*)
    echo "usage: $0 {start|stop|restart|status|run}" >&2
    exit 2
    ;;
esac
