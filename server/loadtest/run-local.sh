#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# Preserve explicit shell overrides so .env defaults do not clobber them.
OVERRIDE_RATE_LIMIT_BYPASS_IPS="${RATE_LIMIT_BYPASS_IPS-}"
OVERRIDE_INTERNAL_STATS_TOKEN="${INTERNAL_STATS_TOKEN-}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [[ -n "${OVERRIDE_RATE_LIMIT_BYPASS_IPS}" ]]; then
  RATE_LIMIT_BYPASS_IPS="${OVERRIDE_RATE_LIMIT_BYPASS_IPS}"
fi
if [[ -n "${OVERRIDE_INTERNAL_STATS_TOKEN}" ]]; then
  INTERNAL_STATS_TOKEN="${OVERRIDE_INTERNAL_STATS_TOKEN}"
fi

INTERNAL_STATS_TOKEN="${INTERNAL_STATS_TOKEN:-loadtest-local-token}"

START_CLIENTS="${START_CLIENTS:-20}"
STEP_CLIENTS="${STEP_CLIENTS:-20}"
MAX_CLIENTS="${MAX_CLIENTS:-100}"
RAMP_SECONDS="${RAMP_SECONDS:-60}"
STEADY_SECONDS="${STEADY_SECONDS:-600}"
COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-15}"
PRE_RAMP_STABILIZE_SECONDS="${PRE_RAMP_STABILIZE_SECONDS:-10}"
OFFER_RATE_PER_ROOM="${OFFER_RATE_PER_ROOM:-0.2}"
RECONNECT_STORM_PERCENT="${RECONNECT_STORM_PERCENT:-0}"
RECONNECT_STORM_AT_SECOND="${RECONNECT_STORM_AT_SECOND:-0}"
MAX_JOIN_ERROR_RATE="${MAX_JOIN_ERROR_RATE:-0}"

REPORT_DIR="$ROOT_DIR/server/loadtest/reports"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_PATH="$REPORT_DIR/load-report-$TIMESTAMP.json"

mkdir -p "$REPORT_DIR"

echo "[loadtest] starting local stack with internal stats enabled"
ENABLE_INTERNAL_STATS=1 INTERNAL_STATS_TOKEN="$INTERNAL_STATS_TOKEN" docker compose up -d --build

echo "[loadtest] validating endpoints"
curl -fsS http://localhost/api/room-id >/dev/null

curl -fsS -H "X-Internal-Token: ${INTERNAL_STATS_TOKEN}" http://localhost/api/internal/stats >/dev/null

echo "[loadtest] running sweep"
LOAD_CMD=(
  go run ./cmd/loadconduit
  --base-url http://localhost
  --report-json "$REPORT_PATH"
  --start-clients "$START_CLIENTS"
  --step-clients "$STEP_CLIENTS"
  --max-clients "$MAX_CLIENTS"
  --ramp-seconds "$RAMP_SECONDS"
  --steady-seconds "$STEADY_SECONDS"
  --cooldown-seconds "$COOLDOWN_SECONDS"
  --pre-ramp-stabilize-seconds "$PRE_RAMP_STABILIZE_SECONDS"
  --offer-rate-per-room "$OFFER_RATE_PER_ROOM"
  --reconnect-storm-percent "$RECONNECT_STORM_PERCENT"
  --reconnect-storm-at-second "$RECONNECT_STORM_AT_SECOND"
  --max-join-error-rate "$MAX_JOIN_ERROR_RATE"
)

LOAD_CMD+=(--stats-token "$INTERNAL_STATS_TOKEN")
if [[ -n "${ROOM_ID_SECRET:-}" ]]; then
  LOAD_CMD+=(--room-id-secret "$ROOM_ID_SECRET")
fi
if [[ -n "${ROOM_ID_ENV:-}" ]]; then
  LOAD_CMD+=(--room-id-env "$ROOM_ID_ENV")
fi

(
  cd server
  "${LOAD_CMD[@]}"
)

echo "[loadtest] report written to $REPORT_PATH"
