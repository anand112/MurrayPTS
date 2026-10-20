#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# MURRAY PTS v2 - start (Linux/macOS, no Docker)
#
#   ./deploy/run.sh                 build the distribution if needed, then run in the
#                                   foreground (Ctrl+C stops it cleanly)
#   PTS_DETACH=1 ./deploy/run.sh    nohup into the background; PID goes to $PTS_LOG_DIR/pts.pid
#   ./deploy/run.sh --build-only    just refresh build/install
#
# Configuration comes from .env next to the repository root, except PTS_LOG_DIR /
# PTS_LOG_LEVEL, which Logback reads from the real environment before .env exists.
# ---------------------------------------------------------------------------
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DIST="$ROOT/build/install/murray-pts"
LAUNCHER="$DIST/bin/murray-pts"

if ! command -v java >/dev/null 2>&1; then
    echo "[PTS] java not found on PATH - install JDK/JRE 21" >&2
    exit 1
fi

cd "$ROOT"

# Logback needs these as actual environment variables (defaults match logback.xml).
PTS_LOG_DIR=${PTS_LOG_DIR:-"$ROOT/logs"}
PTS_LOG_LEVEL=${PTS_LOG_LEVEL:-INFO}
export PTS_LOG_DIR PTS_LOG_LEVEL
mkdir -p "$PTS_LOG_DIR"

if [ ! -f "$LAUNCHER" ] || [ "${1:-}" = "--build-only" ]; then
    echo "[PTS] building the distribution (gradlew installDist) ..."
    ./gradlew installDist
fi
# `if` rather than `&& exit 0` because a failing test as the last command would trip set -e.
if [ "${1:-}" = "--build-only" ]; then exit 0; fi

if [ ! -f "$LAUNCHER" ]; then
    echo "[PTS] launcher missing after build: $LAUNCHER" >&2
    exit 1
fi

# A distribution copied from a Windows volume can lose its +x bit; fall back to `sh`.
if [ -x "$LAUNCHER" ]; then PTS_BIN="$LAUNCHER"; else PTS_BIN="sh $LAUNCHER"; fi

if [ "${PTS_DETACH:-0}" = "1" ]; then
    nohup $PTS_BIN >>"$PTS_LOG_DIR/pts.out" 2>&1 &
    echo $! > "$PTS_LOG_DIR/pts.pid"
    echo "[PTS] started (PID $(cat "$PTS_LOG_DIR/pts.pid")), log: $PTS_LOG_DIR/pts.log"
    echo "[PTS] stop with: kill \$(cat $PTS_LOG_DIR/pts.pid)"
else
    echo "[PTS] starting in the foreground; Ctrl+C to stop. Log: $PTS_LOG_DIR/pts.log"
    exec $PTS_BIN
fi
