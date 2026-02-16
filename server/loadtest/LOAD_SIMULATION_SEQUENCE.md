# Load Conduit Simulation Sequence (HTTP + WS/WSS)

This document describes the exact request/message sequence executed by `server/cmd/loadconduit` and `server/loadtest/run-local.sh`.

Scope: `WS signaling only` (no media-plane load generation).

## Endpoint Matrix

| Endpoint | Method / Transport | Used by | Purpose |
|---|---|---|---|
| `/api/room-id` | `GET` (preflight), `POST` (conduit room creation fallback) | `run-local.sh`, `loadconduit` | Validate service availability and/or create room IDs |
| `/api/internal/stats` | `GET` | `run-local.sh`, `loadconduit` | Preflight validation and per-step stats snapshots |
| `/ws` | `WS` or `WSS` | `loadconduit` virtual clients | Signaling channel under test |

Notes:
- `loadconduit` uses `ws://.../ws` by default for `http://` base URLs, and `wss://.../ws` for `https://`.
- SSE is not used by this conduit path.

## 1) `run-local.sh` preflight sequence

Before the conduit starts its sweep, `server/loadtest/run-local.sh` performs:

1. `docker compose up -d --build` with `ENABLE_INTERNAL_STATS=1`.
2. `GET /api/room-id` (curl check).
3. `GET /api/internal/stats` with `X-Internal-Token` (run-local sets a local default token when unset).
4. Starts `go run ./cmd/loadconduit ...`.

## 2) Sweep-level sequence (`runSweep`)

For each target concurrency step (`start-clients`, then `+step-clients`, until `max-clients`):

1. Execute `runStep(...)`.
2. Evaluate pass/fail thresholds.
3. Stop on first failing step; otherwise continue to next step.

## 3) Per-step sequence (`runStep`)

### A. Step initialization

1. Normalize clients to an even number (`paired` mode).
2. Compute rooms: `targetRooms = targetClients / 2`.
3. Pre-ramp stabilization wait (default `10s`, configurable by `--pre-ramp-stabilize-seconds`):
   - waits before opening client sockets
   - polls `/api/internal/stats` during the wait (best effort)
   - if stats are available, requires idle gauges (`activeClients`, `activeWsClients`, `activeSseClients`) before ramping
4. Fetch initial stats snapshot (baseline for deltas):
   - `GET /api/internal/stats` (3s request timeout).
   - Header: `X-Internal-Token: <stats-token>`.

### B. Room ID allocation

Room IDs are created before clients connect:

1. If `--room-id-secret` is set (or inherited from env), room IDs are generated locally (no HTTP calls).
2. Otherwise, for each room:
   - `POST /api/room-id`
   - HTTP timeout: 10s
   - Retry policy: up to 3 attempts with backoff delays `200ms`, `400ms`

### C. Ramp phase (connection and join)

For each virtual client:

1. Wait per-client ramp offset:
   - `rampInterval = rampSeconds / (targetClients - 1)` (if more than 1 client)
2. Open WebSocket:
   - `WS/WSS /ws`
   - Handshake timeout: 10s
3. Immediately send `join` JSON envelope:
   - `{"v":1,"type":"join","rid":"<roomId>","payload":{"device":"loadtest","capabilities":{"trickleIce":true}}}`
   - Reconnect case adds: `"reconnectCid":"<previousCid>"`
4. Wait for join outcome:
   - success on incoming `type="joined"` (captures join latency)
   - failure on incoming `type="error"`, socket error, context cancellation, or join timeout
5. Start per-connection background loops:
   - read loop for incoming signaling messages
   - ping loop: sends `{"v":1,"type":"ping","rid":"...","cid":"..."}` every 12s

### D. Steady phase

1. Relay generation starts after ramp completes:
   - one sender per room (host client only)
   - sends `type="ice"` messages at:
     - `interval = max(1 / offerRatePerRoom, 50ms)`
   - envelope shape:
     - `{"v":1,"type":"ice","rid":"...","cid":"...","payload":{"candidate":{...}}}`
2. Optional reconnect storm (if configured):
   - at `reconnectStormAtSecond` into the steady window
   - selects `reconnectStormPercent` of clients (deterministic RNG seed)
   - each selected client:
     - closes existing WS connection
     - opens a new `WS/WSS /ws`
     - sends `join` with `payload.reconnectCid`

3. Steady timer runs for `steadySeconds`.

### E. Step teardown

After steady window completes:

1. Stop relay loops and wait for reconnect tasks to finish.
2. Fetch final stats snapshot:
   - `GET /api/internal/stats` (3s timeout, same token logic).
3. For each connected client:
   - send `leave` envelope: `{"v":1,"type":"leave","rid":"...","cid":"..."}`
   - close WS connection
4. Sleep `cooldownSeconds` before next step.

### F. Step evaluation

Step fails if any threshold is violated (configured by flags):

- `join_error_rate > max_join_error_rate`  
  where `join_error_rate = (targetClients - joinSuccess) / targetClients`
- `error_rate > max_error_rate`
- `join_p95_ms > max_join_p95_ms`
- `send_queue_drop_delta > max_send_queue_drops` (when server stats are available)

## 4) Call and message volume per step (approximate)

Without reconnect storm:

- Stats HTTP calls: `2` (`start` + `end`)
- Room ID HTTP calls:
  - `0` if local room ID generation is enabled
  - otherwise `targetRooms` successful `POST /api/room-id` calls (plus retries on failures)
- WS handshakes: `targetClients`
- `join` messages: `targetClients`
- `leave` messages: up to `targetClients` (best effort on teardown)
- `ping` messages: approximately one per connected client every 12s while connected
- Relay (`ice`) sends:
  - approximately `targetRooms * steadySeconds * offerRatePerRoom`
  - bounded by the 50ms minimum interval clamp

Reconnect storm adds:

- Additional WS handshakes and `join` messages for selected clients.

## 5) Timing summary

Per step wall-clock duration is approximately:

`rampSeconds + steadySeconds + cooldownSeconds` plus connection/setup/teardown overhead.

With defaults (`60 + 600 + 15`), each step is roughly `~11m 15s` plus overhead.
