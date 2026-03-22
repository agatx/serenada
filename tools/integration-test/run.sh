#!/usr/bin/env bash
#
# Integration test runner for the Serenada signaling server.
#
# Starts the Go server on a random port, runs Node.js WebSocket tests,
# and tears everything down.
#
# Requirements: Go 1.24+, Node.js 18+
#
# Usage:
#   bash tools/integration-test/run.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# ── Pick a random available port ────────────────────────────────────────────
PORT=$((10000 + RANDOM % 50000))

# ── Generate ephemeral secrets ──────────────────────────────────────────────
export ROOM_ID_SECRET
export TURN_SECRET
export TURN_TOKEN_SECRET
export ROOM_ID_ENV=test
export ALLOWED_ORIGINS="*"
export RATE_LIMIT_BYPASS_IPS="127.0.0.1,::1"
export PORT

ROOM_ID_SECRET=$(openssl rand -hex 32)
TURN_SECRET=$(openssl rand -hex 32)
TURN_TOKEN_SECRET=$(openssl rand -hex 32)

export DATA_DIR
DATA_DIR="$(mktemp -d)"

# ── Install test dependencies ───────────────────────────────────────────────
echo "Installing test dependencies..."
(cd "$SCRIPT_DIR" && npm install --silent 2>&1 | tail -1)

# ── Start the Go server ────────────────────────────────────────────────────
echo "Starting server on port $PORT..."
SERVER_PID=""

cleanup() {
  if [ -n "$SERVER_PID" ]; then
    # Kill the process group (server + child go process)
    kill $SERVER_PID 2>/dev/null || true
    pkill -P $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
  fi
  [ -n "${DATA_DIR:-}" ] && rm -rf "$DATA_DIR"
}
trap cleanup EXIT

(cd "$REPO_ROOT/server" && go run .) &
SERVER_PID=$!

# ── Wait for the server to become healthy ───────────────────────────────────
MAX_WAIT=30
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "ERROR: Server process died during startup."
    exit 1
  fi
  if curl -sf -X POST "http://localhost:$PORT/api/room-id" -o /dev/null 2>/dev/null; then
    echo "Server healthy (waited ${WAITED}s)."
    break
  fi
  sleep 1
  WAITED=$((WAITED + 1))
done

if [ $WAITED -ge $MAX_WAIT ]; then
  echo "ERROR: Server did not become healthy within ${MAX_WAIT}s."
  exit 1
fi

# ── Run the tests ──────────────────────────────────────────────────────────
echo ""
TEST_EXIT=0
node "$SCRIPT_DIR/signaling.test.mjs" "http://localhost:$PORT" || TEST_EXIT=$?

exit $TEST_EXIT
