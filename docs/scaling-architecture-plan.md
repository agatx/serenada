# Scaling Architecture Plan: Room-Affinity + Cloudflare TURN

**Status:** Draft (v9)
**Date:** 2026-03-26

## Goal

Remove hardware single points of failure (SPOFs) from the signaling server and enable horizontal scaling, while keeping the solution lightweight and operationally simple.

## Current State: SPOFs Identified

| Component | SPOF? | State | Impact of Failure |
|-----------|-------|-------|-------------------|
| **Go signaling server** | **Yes** | All rooms/clients/watchers in-memory (`Hub` struct) | All active calls lose signaling — peers keep media flowing P2P but can't reconnect or join |
| **SQLite** (`subscriptions.db`) | **Yes** | Push subscriptions on local disk | Push notifications stop working |
| **coturn** | **Yes** | Stateless per-session, but single instance | Clients behind NAT lose media relay; direct P2P calls unaffected |
| **nginx** | **Yes** | Stateless | All traffic stops |
| **Host machine** | **Yes** | Everything on one box | Total outage |

Media flows P2P (not through the server), so the signaling server is lightweight and low-throughput. A room with 4 participants generates maybe a few dozen signaling messages total. The scaling challenge is primarily **availability**, not throughput.

## Approach: Room-Affinity Routing

Serenada's rooms are **2-4 participants and short-lived**. There is no scenario where a single room needs to span servers. Room affinity — routing all clients for a room to the same server via consistent hashing — keeps all signaling logic in-memory and untouched while still removing every SPOF.

If a server crashes, clients reconnect within seconds (existing resilience logic) and create a new room on a surviving server. Media continues P2P throughout — users see a brief "reconnecting" overlay, not a dropped call.

### Design

```
                         ┌────────────────────────────────────────┐
                         │           Load Balancer                 │
                         │  (TLS termination, health checks)       │
                         │                                        │
                         │  /ws?rid=X, /sse?rid=X → hash(X)      │
                         │  /api/*, /device-check  → round-robin  │
                         └──────┬───────────┬─────────────────────┘
                                │           │
                    ┌───────────▼──┐  ┌─────▼───────────┐
                    │  Server A    │  │  Server B        │
                    │  (Go)        │  │  (Go)            │
                    │              │  │                   │
                    │ Owns rooms   │  │ Owns rooms       │
                    │ where        │  │ where             │
                    │ hash(rid)=A  │  │ hash(rid)=B      │
                    │              │  │                   │
                    │ signaling.go │  │ signaling.go      │
                    │ UNCHANGED    │  │ UNCHANGED         │
                    └──────┬───┬──┘  └──┬───┬────────────┘
                           │   │        │   │
              ┌────────────▼───▼────────▼───▼─────────┐
              │            Redis (Sentinel)             │
              │                                        │
              │  Shared (non-signaling only):           │
              │   • Push subscriptions (with auto-IDs) │
              │   • Snapshots (binary, 10min TTL)      │
              │   • Rate limit counters                │
              │   • Room status snapshots (TTL +        │
              │     heartbeat for watcher visibility)   │
              │   • Watcher event firehose (pub/sub)   │
              └────────────────────────────────────────┘

     Clients ◄────── WebRTC P2P media ──────► Clients
        │                                        │
        │  TURN credentials (legacy format):     │
        │  Server tries Cloudflare → coturn       │
        │  Returns whichever succeeds             │
        │  Zero client changes                    │
        └──── STUN/TURN ──► active provider  ◄───┘
```

### Key Design Decisions

- **Room-affinity routing.** The LB consistently hashes `rid` (room ID) from the query string to route all clients for a room to the same server instance. All room state stays in-memory with the current Hub/Room/Client mutex-based logic. **`signaling.go` core logic is unchanged.**
- **Client transport URL change required.** Current clients do not include `rid` on WS/SSE URLs (they send it later in the join message). All three clients must be updated to include `rid` as a query parameter. This is the only client-side prerequisite for scaling. See Layer 1.
- **No sticky session complexity.** Because all WS/SSE connections for a room land on the same server, SSE's split GET/POST works naturally — the `sid` lookup is always local.
- **Redis for non-signaling state only.** Push subscriptions, snapshots, rate limits, cross-server watcher status. Redis is never in the signaling hot path. A brief Redis outage degrades push notifications, watchers, and rate limiting but does not affect active calls.
- **Watcher firehose for cross-server visibility.** Owner servers write room status snapshots to Redis (with TTL + heartbeat to prevent ghost rooms) and publish updates on a single global `watcher:events` channel. Non-owner servers subscribe once and fan out to local watchers. Preserves the existing `room_statuses` / `room_status_update` push contract. See Layer 3.
- **Cloudflare TURN as primary, self-hosted coturn as fallback.** Server-side provider selection with failover — returns whichever succeeds in the existing legacy `TurnConfig` format. Zero client changes. See Layer 6.
- **Single new Go dependency:** `go-redis/v9`.

## Layer 1: Transport URL Changes (Required for Room-Affinity Routing)

### Problem

Room-affinity routing requires the LB to hash on `rid` from the connection URL. Current clients do not include `rid` on transport URLs:

| Platform | Current WS URL | Current SSE URL |
|----------|---------------|-----------------|
| Web | `wss://{host}/ws` | `https://{host}/sse?sid={sid}` |
| Android | `wss://{host}/ws` | `https://{host}/sse?sid={sid}` |
| iOS | `wss://{host}/ws` | `https://{host}/sse?sid={sid}` |

The room ID is only sent later inside the `join` message payload. The LB cannot inspect WebSocket frame contents or SSE POST bodies, so it has nothing to hash on.

### Required changes

| Platform | New WS URL | New SSE URL |
|----------|-----------|-------------|
| Participants | `wss://{host}/ws?rid={rid}` | `https://{host}/sse?rid={rid}&sid={sid}` |
| Watchers | `wss://{host}/ws` (unchanged) | `https://{host}/sse?sid={sid}` (unchanged) |

Current client `connect()` APIs do not carry room context into transport setup:
- **Web:** `SignalingEngine.connect()` takes no parameters; `createSignalingTransport()` receives only `{ wsUrl, httpBaseUrl, sseSid? }`
- **Android:** `SignalingClient.connect(host)` takes only `host`; transports are instantiated without room context
- **iOS:** `SignalingClient.connect(host:)` takes only `host`; transports built with `host` only

To add `rid` for participant connections while keeping watcher connections rid-less, room context must be threaded through the engine/client API and down to the transport layer.

**Web client:**
- `SignalingEngine` — pass optional `roomId` to `connect()` or set it as a property before connect
- `CreateTransportOptions` (`transports/index.ts`) — add optional `rid?: string`
- `ws.ts` / `sse.ts` — append `rid` to URL if provided

**Android client:**
- `SignalingClient` — add `roomId` parameter to `connect(host, roomId?)` or set via property
- `WebSocketSignalingTransport.connect()` — accept optional `rid` parameter
- `SseSignalingTransport.connect()` — accept optional `rid` parameter
- `buildWssUrl()` / `buildSseUrl()` — append `rid` if non-null

**iOS client:**
- `SignalingClient` — add `roomId` parameter to `connect(host:roomId:)` or set via property
- `WebSocketSignalingTransport.connect()` — accept optional `rid` parameter
- `SseSignalingTransport.connect()` — accept optional `rid` parameter
- `buildWssURL()` / `buildSseURL()` — append `rid` if non-nil

**Server** (`ws.go`, `sse.go`):
- Extract `rid` from query parameter and store as a **new field** `transportRID` on the `Client` struct. This must NOT reuse the existing `Client.rid` field, which means "currently joined room" and drives rejoin logic (`signaling.go`) and SSE stale-client handling (`sse.go`). `transportRID` is the room requested at connection time; `rid` is the room the client has actually joined (set by `handleJoin`, cleared by `handleLeave`).
- Pass `transportRID` to `handleJoin()` for validation (see Layer 2)

**Diagnostics probes:** If SSE/WS diagnostics probes open connections without a room context, they should use a sentinel value (e.g., `rid=_diag`) so the LB can still route them. These connections don't participate in rooms, so any server can handle them.

### Backward compatibility & Rollout Safety

Old clients without `rid` on the URL cannot participate in room-affinity. If the LB falls back to round-robin for these requests, two old clients joining the same room will likely land on different servers, creating a split-brain (disjoint in-memory rooms). 

**Critical Invariant:** Phase 4 (multi-instance deployment) **must be strictly gated** until all supported participant client versions send `rid`. Until the rollout is complete, the backend must run as a single instance.

## Layer 2: Signaling — What Changes (Almost Nothing)

### Minor Changes Required

- **`signaling.go` Enforce `rid` matching** — In `handleJoin`, the server must validate that the `join` payload's `roomId` exactly matches `Client.transportRID` (the URL query param). If they differ, the server must reject the join. This prevents clients from bypassing the LB's hash ring or switching rooms on a single connection. Clients must establish a new transport to join a different room.
- `ws.go` / `sse.go` — WebSocket and SSE transports must extract the `rid` query param and store it as `Client.transportRID` (a new field, separate from the existing `Client.rid` which tracks the currently-joined room).
- `room_id.go` / `security.go` — Unchanged.

### Small additions

- `main.go` — Add two health endpoints:
  - `/healthz` — **liveness only** (returns 200 if process is up). Used by LB active health checks to detect server failures. Must NOT check Redis — a Redis blip must not cause the LB to eject an otherwise healthy signaling node (this would turn a degraded dependency into a full outage, contradicting the "Redis is non-critical" design).
  - `/readyz` — **readiness** (returns 200 if Redis is reachable, 503 otherwise). Used for operational monitoring and dashboards, NOT for LB health decisions.

## Layer 3: Watchers — Redis Status with TTL Heartbeat + Single Firehose

### Problem

Current watchers (`watch_rooms`) use in-memory state and push-style updates. With room affinity, a watcher connected to Server A cannot see rooms owned by Server B. Watchers expect:

1. **`room_statuses`** — initial snapshot of all watched rooms: `{ "rid_1": { count, maxParticipants }, ... }`
2. **`room_status_update`** — incremental push on every watcher-visible change: `{ rid, count, maxParticipants }`

These updates fire on: join, leave, capacity lock (provisional → locked `maxParticipants`), room delete, host ends room.

Two additional concerns:
- **Ghost rooms on server crash:** If a server dies, it never cleans up its room status keys. Watchers would see ghost rooms indefinitely without a TTL-based expiry mechanism.
- **Subscription management:** Subscribing to one Redis channel per watched room adds unnecessary lifecycle complexity when a single global channel suffices for the low event volume (a few events per call lifetime).

### Solution: TTL heartbeat + single global firehose

```
Owner server (has the room in memory):
  On every watcher-visible event (join/leave/capacity-lock/delete):
    1. SET room:status:{rid} → JSON { count, maxParticipants, epoch, version } EX 30
       - epoch = random token generated when the room is created in-memory (e.g., 8-byte hex). Distinguishes different call sessions that reuse the same room ID (room IDs are HMAC-validated with no expiry, so the same rid can host many calls over time).
       - version = monotonic counter per room lifetime, for stale update detection within a single epoch
       - 30-second TTL auto-expires if server crashes (no explicit DEL needed)
       - If room deleted: DEL room:status:{rid} (immediate cleanup; TTL is the safety net)
    2. PUBLISH watcher:events → JSON { rid, count, maxParticipants, epoch, version, deleted }
       - Single global channel for ALL room status events across ALL servers

  Heartbeat (every 15 seconds, for all active rooms):
    - For each active room: SET room:status:{rid} <current payload> EX 30
    - This refreshes the TTL for rooms that are alive
    - If server crashes, all its room status keys expire within 30 seconds
    - Heartbeat can be batched into a single Redis pipeline (one round-trip)

Any server (has local watcher clients):
  On startup:
    1. SUBSCRIBE watcher:events (single persistent subscription per server instance)

  On watch_rooms request:
    1. For each requested rid:
       - If room is local → read from in-memory Hub (current behavior, unchanged)
       - If room is NOT local → GET room:status:{rid} from Redis
       - If key doesn't exist → count=0 (room doesn't exist or host server crashed)
    2. Combine into room_statuses response (same format as today)
    3. Register watcher's interest in these rids (local bookkeeping)

  On watcher:events message:
    1. Parse { rid, count, maxParticipants, epoch, version, deleted }
    2. Check if any local watcher cares about this rid
    3. If yes: compare epoch + version to last-seen values. If epoch differs from cached epoch, treat as a new room lifetime — reset cached version and accept the update. If epoch matches, drop if version ≤ last-seen version (stale/out-of-order).
    4. Fan out as room_status_update to matching local watchers (same message format as today)

  Expiry sweep (mandatory, every 15 seconds):
    - Each server runs a periodic sweep of its locally-tracked watched rids
    - For each non-local rid a watcher cares about:
      EXISTS room:status:{rid} → if missing (expired or deleted) AND
      last-known count was > 0, push room_status_update with count=0
      to local watchers and clear cached state (last-seen epoch/version/count)
    - Important: do NOT remove the watcher's interest in the rid.
      Subscriptions persist until the client sends a new watch_rooms
      or disconnects. If the room is later recreated, the firehose
      event will still match and be delivered to the watcher.
    - This ensures connected watchers see ghost rooms disappear within
      ~45 seconds of a server crash (30s TTL + up to 15s sweep interval)
    - The sweep is cheap: one pipelined EXISTS per tracked rid per server
```

### Why this works

- **Local rooms** (owned by this server): zero change — current in-memory watcher path is untouched
- **Remote rooms** (owned by another server): Redis snapshot for initial state, global pub/sub for live updates
- **Ghost room protection**: TTL + heartbeat ensures rooms auto-vanish within 30 seconds of server crash. No orphaned keys.
- **Single subscription per server**: One `SUBSCRIBE watcher:events` instead of N per-room subscriptions. Simpler Go code, no subscription lifecycle management, no Redis connection pool pressure.
- **Low overhead**: room status events are tiny (~80 bytes JSON) and fire only on room events (a few per call lifetime). Even with 1000 concurrent rooms across all servers, the firehose carries <1 message/second.
- **No polling for live updates**: watcher gets push-style updates same as today
- **Epoch + version**: epoch distinguishes call sessions that reuse the same room ID (room IDs have no expiry). Version prevents stale/out-of-order updates within a single call session. Together they ensure a delete-and-recreate cycle (version resets to 1 with a new epoch) is never incorrectly dropped as stale.

### Server changes

- `signaling.go` — After `broadcastRoomStatusUpdate()`: write `room:status:{rid}` (with epoch + version) to Redis with EX 30 and `PUBLISH watcher:events`. Generate epoch (random hex) on room creation. ~25 lines.
- `signaling.go` — In `handleWatchRooms()`: for non-local rooms, read from Redis. ~15 lines.
- `main.go` — Heartbeat goroutine: every 15s, refresh TTL on all active rooms. ~15 lines.
- New helper in `redis.go` — Single `watcher:events` subscriber, local watcher interest tracking, epoch+version-checked fanout, mandatory expiry sweep (every 15s, pipelined EXISTS per tracked rid). ~55 lines.

## Layer 4: Push Subscriptions — SQLite to Redis

### Problem

SQLite is a local file SPOF. Push subscriptions must be shared across server instances.

### Shared VAPID keys (required for multi-instance web push)

The current server calls `loadOrGenerateVAPIDKeys()` which reads or creates a local `vapid.json` file. Each instance would generate its own VAPID key pair. Web push subscriptions are cryptographically bound to the VAPID public key used at subscription time — if instance A serves its public key to a browser, and instance B (with a different private key) tries to send to that subscription, the push service rejects it.

**Solution:** Load the VAPID key pair from environment variables (`VAPID_PRIVATE_KEY`, `VAPID_PUBLIC_KEY`) instead of a local file. All instances share the same pair. The keys are generated once (via `openssl` or a one-time script) and stored in the deployment's secret management (e.g., `.env`, Docker secrets, Fly.io secrets).

**Migration:** On startup, if env vars are set, use them. If not, fall back to the existing `vapid.json` behavior (single-instance compat). Phase 2 adds the env var support; Phase 4 (multi-instance) requires them to be set.

**Changes:** `push.go` — modify `loadOrGenerateVAPIDKeys()` to check `VAPID_PRIVATE_KEY` / `VAPID_PUBLIC_KEY` env vars first, fall back to file. ~10 lines. Add `VAPID_PRIVATE_KEY`, `VAPID_PUBLIC_KEY` to `.env.example`.

### Stable numeric IDs

The snapshot encryption flow depends on stable numeric subscription IDs:
1. Client calls `GET /api/push/recipients` → receives `[{id: 5, publicKey: ...}]`
2. Client encrypts snapshot key per recipient ID
3. Server delivers push with per-recipient wrapped keys, looked up by numeric ID

### Redis schema

The current SQLite schema has `UNIQUE(room_id, endpoint)` — the same browser endpoint can subscribe to multiple rooms. The Redis design preserves this.

```
INCR push:id_counter → returns next ID (e.g., 42)

HSET push:sub:42 room_id <rid> transport <t> endpoint <ep> auth <a>
     p256dh <p> locale <l> enc_pubkey <k> created_at <ts>

SADD push:room:{rid} 42                    # index: room → subscription IDs
SET  push:ep:{rid}:{endpointHash} 42       # index: (room, endpoint) → subscription ID
                                            # endpointHash = SHA-256(endpoint) truncated
                                            # to keep key length bounded
```

**Lookup patterns:**
- **Subscribe (upsert):** Check `GET push:ep:{rid}:{endpointHash}` — if exists, update that sub's fields. If not, `INCR` to get new ID, `HSET` the sub, `SADD` to room index, `SET` the composite endpoint index.
- **Unsubscribe:** `GET push:ep:{rid}:{endpointHash}` → get ID → `DEL push:sub:{id}`, `SREM push:room:{rid} {id}`, `DEL push:ep:{rid}:{endpointHash}`
- **Get recipients for room:** `SMEMBERS push:room:{rid}` → get IDs → `HGETALL` each
- **Snapshot key lookup:** Same numeric IDs, no change to snapshot metadata format

**Why composite key:** A single endpoint (browser) can subscribe to rooms A and B. `push:ep:A:{hash}` → ID 5, `push:ep:B:{hash}` → ID 7. Unsubscribing from room A deletes only ID 5. This matches the current `DELETE FROM subscriptions WHERE room_id = ? AND endpoint = ?` behavior.

### Snapshots

Push snapshots (encrypted camera frames) move from filesystem to Redis:

```
SET snap:{id}:data <binary>  EX 600    # 10-minute TTL
SET snap:{id}:meta <json>    EX 600
```

Replaces `data/snapshots/*.bin` and `*.json` files. Cleanup is automatic via Redis TTL (replaces `cleanupOldSnapshots`).

## Layer 5: Rate Limiting — Shared Across Instances

Current `IPLimiter` uses in-memory token buckets. With multiple servers, the same IP could get N times the intended rate by hitting different servers.

### Redis sliding window

Replace in-memory token buckets with Redis sorted sets (standard sliding window pattern):

```
Key: rl:{endpoint}:{ip}
ZADD rl:ws:1.2.3.4 <now_ms> <request_id>
ZREMRANGEBYSCORE rl:ws:1.2.3.4 0 <now_ms - window_ms>
ZCARD rl:ws:1.2.3.4 → count in window
```

If count exceeds limit → 429. Each key auto-expires via `EXPIRE` slightly longer than the window.

This is a well-understood pattern, no Lua required (the three commands can run in a pipeline; minor over-counting under extreme concurrency is acceptable for rate limiting).

## Layer 6: TURN — Server-Side Provider Failover (Zero Client Changes)

TURN migration is **decoupled from the scaling phases**. It can be done before, after, or in parallel with Phases 0-4. It does not depend on Redis, room affinity, or multi-instance deployment.

The server **selects one TURN provider** and returns its credentials in the existing `TurnConfig` format. Clients don't know or care which provider they're using — the format is identical either way.

### How it works

```
Client calls GET /api/turn-credentials?token=...

Server (handleTurnCredentials in turn_auth.go):
  1. If CF_TURN_KEY_ID is configured:
     a. Call Cloudflare API to generate short-lived credentials
        POST https://rtc.live.cloudflare.com/v1/turn/keys/{key-id}/credentials/generate
        Authorization: Bearer {api-token}
        Body: { "ttl": 900 }
     b. If successful → return Cloudflare credentials in legacy format:
        {
          "username": "<cloudflare-generated-username>",
          "password": "<cloudflare-generated-credential>",
          "uris": ["stun:turn.cloudflare.com:3478", "turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"],
          "ttl": 900
        }
     c. If Cloudflare API fails → fall through to step 2
  2. Generate coturn credentials using existing HMAC-SHA1 logic (current behavior)
     Return in same legacy format with coturn URIs
```

### Why this works

- **`TurnConfig` struct is unchanged.** Same `{ username, password, uris, ttl }` JSON.
- **All three clients are unchanged.** They receive credentials in the format they already parse.
- **Failover is automatic.** If Cloudflare is down, coturn credentials are returned seamlessly.
- **The turn-refresh flow works identically.** Client sends `turn-refresh` signaling message → server issues new `turnToken` → client fetches fresh credentials from `/api/turn-credentials` → server picks active provider.
- **Gradual rollout is trivial.** Set `CF_TURN_KEY_ID` → Cloudflare is primary. Unset → coturn only. No client coordination needed.

### What changes

**Server only (`turn_auth.go`):** ~60 lines
- Add `fetchCloudflareCredentials()` function (HTTP call to Cloudflare API)
- In `handleTurnCredentials()`: try Cloudflare first (if configured), fall back to existing coturn HMAC logic
- New env vars: `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`

**Clients:** Nothing.

### Tradeoff

Clients use one TURN provider per credential fetch, not both simultaneously. In practice this is fine: Cloudflare TURN has high availability (global edge), and if it's down, the server falls back to coturn on the next credential request. The worst case for failover depends on the turn-refresh cycle: turn token TTL is 30 minutes (`signaling.go`), clients refresh at 80% of TTL = 24 minutes (`TURN_REFRESH_TRIGGER_RATIO` in `constants.ts`), and TURN credentials themselves are valid for 15 minutes (`turn_auth.go`). So in the worst case, a client using Cloudflare TURN credentials could wait up to ~24 minutes before its next `turn-refresh` request triggers coturn fallback. If faster failover is needed, reduce `turnTokenTTL` or `TURN_REFRESH_TRIGGER_RATIO`. Cloudflare credential TTL (900s in the API call above) should be aligned with the turn-refresh interval to avoid credentials expiring mid-call before refresh fires.

### Cloudflare TURN pricing

$0.05/GB of relayed traffic. For a 1:1 video call where TURN is needed (~500kbps video), that's roughly $0.01/hour. Most calls don't need TURN at all (direct P2P works).

## Layer 7: Load Balancer

### Routing rules

| Path | Routing | Why |
|------|---------|-----|
| `/ws?rid=X` | Consistent hash on `rid` param | All room participants on same server |
| `/sse?rid=X&sid=Y` | Consistent hash on `rid` param | SSE GET + POST land on same server via `rid` |
| `/ws` (Watchers, no `rid`) | Round-robin | Watchers monitor multiple rooms, no single `rid`. Firehose is global, any server works. |
| `/sse?sid=Y` (Watchers, no `rid`) | Consistent hash on `sid` param | Watcher SSE needs sticky sessions for GET/POST. `sid` ensures both land on the same server. |
| `/api/turn-credentials` | Round-robin | Stateless credential generation |
| `/api/room-id` | Round-robin | Stateless |
| `/api/push/*` | Round-robin | Reads/writes Redis (shared) |
| `/api/internal/stats` | Per-server | Server-specific metrics |
| `/device-check` | Round-robin | Stateless |
| `/healthz` | Not proxied — LB probes each backend directly | **Liveness only** (process up = 200). Used by LB active health checks. Does NOT check Redis — Redis degradation must not eject signaling nodes. |
| `/readyz` | Not proxied — per-server monitoring | **Readiness** (Redis reachable = 200, else 503). For dashboards/alerting, not LB decisions. |

### SSE detail

With room-affinity, the SSE sticky session problem disappears. The client includes `rid` on both the GET (open stream) and POST (send message) requests. The LB hashes `rid` → same server for both. Since `clientsBySID` is local and both requests land on the same server, the `sid` lookup always succeeds.

**Pre-room SSE connections** (client connects before knowing the room ID): these don't exist in the current client flow. Clients open WS/SSE only when navigating to `/call/:roomId`, at which point the room ID is known.

### LB options

| LB | Configuration | Notes |
|----|--------------|-------|
| **nginx upstream** | `map` + `hash $hash_key consistent;` (see below) | Works with existing docker-compose. Add second `app-server` service. |
| **Cloudflare LB** | Session affinity rule on `rid` query param | Zero-infra if already on Cloudflare |
| **Fly.io** | `fly-replay` header based on rid hash | Built-in multi-region support |

### nginx hash key selection

Participants and watchers require different hash keys. Participants have `rid` (room affinity), watchers have only `sid` (sticky sessions). A naive concatenation (`$arg_rid$arg_sid`) breaks room affinity because participants in the same room have different `sid` values.

Two upstreams are needed: one hashed (for signaling connections with affinity requirements) and one round-robin (for stateless API routes and WS watchers):

```nginx
# Hashed upstream: room affinity (rid) or sticky sessions (sid)
map $arg_rid $hash_key {
    ""      $arg_sid;    # no rid (watchers) → hash on sid for sticky sessions
    default $arg_rid;    # rid present (participants) → hash on rid for room affinity
}

upstream serenada_signaling {
    hash $hash_key consistent;
    server app1:8080;
    server app2:8080;
}

# Round-robin upstream: stateless routes
upstream serenada_api {
    server app1:8080;
    server app2:8080;
}

# Signaling connections (WS/SSE) → hashed upstream
location /ws  { proxy_pass http://serenada_signaling; ... }
location /sse { proxy_pass http://serenada_signaling; ... }

# Stateless routes → round-robin upstream
location /api         { proxy_pass http://serenada_api; ... }
location /device-check { proxy_pass http://serenada_api; ... }

# /healthz is NOT proxied — it is used by the LB's own active health
# checks, which probe each backend directly (not through the upstream).
# IMPORTANT: /healthz is liveness-only (process up = 200). It does NOT
# check Redis. Use /readyz for dependency monitoring dashboards.
# nginx health checks are configured per-server in the upstream block:
#   server app1:8080 max_fails=2 fail_timeout=5s;
#   server app2:8080 max_fails=2 fail_timeout=5s;
# Or with nginx Plus / third-party module: health_check uri=/healthz;
```

Requests to `/ws` or `/sse` with neither `rid` nor `sid` (e.g., diagnostics probes) will hash on an empty key, which consistently routes to the same server — acceptable for non-room connections.

### Server failure and rehashing

When a server crashes:
1. LB health check detects failure (1-3 seconds)
2. LB rehashes — rooms from dead server distribute across surviving servers
3. Clients' WS/SSE connections drop → existing reconnect logic kicks in
4. Clients reconnect, LB routes to surviving server, new room is created
5. WebRTC renegotiation happens automatically
6. **Media never stopped** — P2P stream continued throughout

When a server is added (requires maintenance procedure):

Adding a server changes the hash ring. Existing participants stay connected to the old server, but any new join or reconnect for the same room will hash to the new server — splitting the room across two instances.

**Required procedure:**
1. Add the new server to the LB upstream but mark it as `down` (accepts no traffic yet)
2. Drain active rooms: stop accepting new connections on existing servers (health check returns 503) or wait for natural call completion during a low-traffic window
3. Once active rooms are empty (or acceptably few), enable the new server and re-enable health checks
4. Clients reconnect and the new hash ring takes effect cleanly

**Alternative: ring pinning.** The server can register its active room IDs in Redis on startup. The LB (or a thin routing layer) checks Redis before hashing: if a room is pinned to a server, route there regardless of the hash. Pins auto-expire when rooms end. This allows live addition without draining, but adds routing complexity.

For Serenada's expected scale (infrequent server additions), the drain procedure is simpler and safer. Ring pinning is a future optimization if zero-downtime scaling becomes a requirement.

## Migration Path (Phased)

### Scaling phases (sequential)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase 0** | Add `/healthz` endpoint. Add Redis client dependency (`go-redis/v9`). Keep everything else unchanged. | None — additive | Prepare the codebase for Redis without changing behavior. |
| **Phase 1** | Add `rid` query parameter to WS/SSE URLs across all three clients. Server extracts `rid` and enforces matching with join payload. | Low — additive | LB prerequisite. Ship client updates. Server rejects joins where URL `rid` != payload `roomId`. Single-instance deployment until all clients ship `rid`. |
| **Phase 2** | Move push subscriptions + snapshots to Redis (with stable numeric IDs). Shared VAPID keys via env vars. Remove SQLite dependency. Deploy 1 instance + Redis. | Low — push is non-critical path | Push subs use counter-based IDs. Snapshots become Redis keys with TTL. VAPID keys loaded from env vars (required for multi-instance in Phase 4). SQLite + filesystem removed. **Migration note:** existing SQLite subscriptions are NOT migrated — they are dropped. This is acceptable because (a) push subscriptions auto-recreate when users next open the app, (b) push is a non-critical path (notifications, not call functionality), and (c) the subscription count is small. If zero-loss migration is needed, a one-time `sqlite-to-redis-import` script can be run during the Phase 2 deployment window before removing SQLite. |
| **Phase 3** | Add Redis watcher firehose (TTL heartbeat + global pub/sub). Move rate limiting to Redis. | Low — watcher changes are additive | Watchers can see rooms on other servers via Redis. Ghost rooms auto-expire. Rate limits shared. Local-room watcher path unchanged. |
| **Phase 4** | Configure LB with consistent hash on `rid`. Deploy second server instance. | Low — signaling code unchanged | This is the actual multi-instance deployment. Depends on Phase 1 (clients ship `rid`). Test with load testing tool. |

### TURN phase (independent, can run at any time)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase T** | Add Cloudflare TURN provider in `turn_auth.go` with server-side failover to coturn. Set `CF_TURN_KEY_ID` + `CF_TURN_API_TOKEN` env vars. | Low — server-only, zero client changes | Clients receive credentials in the same legacy `TurnConfig` format. Cloudflare primary, coturn fallback. Can be deployed independently of scaling phases. |

## Estimated Scope

| Component | Lines Changed (est.) | New Dependencies |
|-----------|---------------------|------------------|
| `rid` threading through transport API (Web) | ~25 | None |
| `rid` threading through transport API (Android) | ~25 | None |
| `rid` threading through transport API (iOS) | ~25 | None |
| Server `rid` extraction + join validation | ~15 | None |
| Redis push subscriptions (with ID counter + composite keys) | ~150 | `go-redis/v9` |
| Redis snapshots (replace filesystem) | ~50 | (same) |
| Redis rate limiting (sliding window) | ~80 | (same) |
| Redis watcher firehose (TTL heartbeat + global pub/sub + fanout) | ~90 | (same) |
| Cloudflare TURN with server-side failover | ~60 | None (HTTP call) |
| Health check endpoints (`/healthz` + `/readyz`) | ~20 | None |
| Shared VAPID key support (env vars) | ~10 | None |
| LB configuration (nginx) | ~20 | None |
| **Total** | **~575-640** | **1 new Go dep** |

## Files Affected

### Server (`server/`)
- `main.go` — Redis init, `/healthz` (liveness) + `/readyz` (readiness) endpoints, room-status heartbeat goroutine
- `signaling.go` — In `handleJoin()`, validate URL `rid` matches join payload `roomId` (~5 lines). Watcher-status writes: after `broadcastRoomStatusUpdate()`, write `room:status:{rid}` with EX 30 + `PUBLISH watcher:events` (~20 lines). In `handleWatchRooms()`, read Redis for non-local rooms (~15 lines).
- `push.go` — Redis-backed subscriptions with auto-incrementing IDs, Redis snapshot storage, shared VAPID key loading from env vars
- `rate_limit.go` — Redis sliding window rate limiting
- `turn_auth.go` — Cloudflare TURN credential generation with coturn fallback (~60 lines)
- New: `redis.go` — Redis client setup, connection pool, single `watcher:events` subscriber, local watcher interest tracking + version-checked fanout

### Client changes — threading `rid` through transport API
**Web:**
- `client/packages/core/src/signaling/SignalingEngine.ts` — Pass `roomId` to transport creation
- `client/packages/core/src/signaling/transports/index.ts` — Add `rid` to `CreateTransportOptions`
- `client/packages/core/src/signaling/transports/ws.ts` — Append `rid` to WS URL
- `client/packages/core/src/signaling/transports/sse.ts` — Append `rid` to SSE URL

**Android:**
- `serenada-core/.../SignalingClient.kt` — Add `roomId` to `connect()` signature or property
- `serenada-core/.../WebSocketSignalingTransport.kt` — Accept `rid`, append to `buildWssUrl()`
- `serenada-core/.../SseSignalingTransport.kt` — Accept `rid`, append to `buildSseUrl()`

**iOS:**
- `SerenadaCore/Sources/Signaling/SignalingClient.swift` — Add `roomId` to `connect()` signature or property
- `SerenadaCore/Sources/Signaling/WebSocketSignalingTransport.swift` — Accept `rid`, append to `buildWssURL()`
- `SerenadaCore/Sources/Signaling/SseSignalingTransport.swift` — Accept `rid`, append to `buildSseURL()`

### Client changes — TURN
None.

### Docker / Infra
- `docker-compose.yml` — Add Redis service, add second `app-server`, keep coturn
- `docker-compose.prod.yml` — Same + nginx upstream with consistent hash
- `nginx/nginx.prod.conf` — Dual upstream (`serenada_signaling` with `map`/`hash`, `serenada_api` round-robin) + location blocks routing `/ws`,`/sse` to hashed upstream and `/api/*` to round-robin
- `.env.example` — New vars: `REDIS_URL`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`, `VAPID_PRIVATE_KEY`, `VAPID_PUBLIC_KEY`

### NOT changed
- `signaling.go` — Hub/Room/Client types, join/leave/relay logic, mutex concurrency (unchanged beyond watcher-status additions)
- `ws.go` — WebSocket transport (extracts `rid` query param → `Client.transportRID`)
- `sse.go` — SSE transport (extracts `rid` query param → `Client.transportRID`)
- `room_id.go` — Room ID validation (unchanged)
- `security.go` — CORS (unchanged)
- All client TURN parsing code (unchanged — legacy format preserved)

## Required Test Coverage

### Scaling tests
1. **Transport `rid` parameter** — Verify all three clients include `rid` on WS and SSE URLs; verify server accepts connections with and without `rid` (backward compat)
2. **`rid` matching enforcement** — Verify server rejects join when URL `rid` differs from join payload `roomId`; verify server allows join when they match; verify server allows join when URL `rid` is absent (backward compat with old clients on single instance)
3. **Room-affinity routing** — Verify all clients for the same `rid` land on the same server via LB consistent hash; verify nginx `map` routes watchers (no `rid`) by `sid`
4. **Server failure + client reconnect** — Kill one server, verify clients reconnect to surviving server within resilience timeout, new room is created, WebRTC renegotiates
5. **Server addition with drain** — Add a server using the drain procedure; verify no active rooms are split across servers; verify new rooms hash correctly after drain completes
6. **Watcher cross-server visibility** — Verify watchers see room status for rooms on other servers; verify `room_statuses` initial response includes remote rooms; verify `room_status_update` fires for remote room events (join, leave, capacity lock, delete); verify epoch+version prevents stale updates; verify room delete-and-recreate (same rid, new epoch) delivers updates correctly to watchers that saw the previous lifetime
7. **Watcher ghost room cleanup** — Kill a server, verify its rooms' Redis status keys expire within 30 seconds; verify watchers see count=0 after expiry
8. **Snapshot recipient ID mapping** — Verify numeric IDs survive Redis migration and round-trip correctly through subscribe → recipients → upload → deliver
9. **Multi-room push subscriptions** — Verify the same endpoint can subscribe to rooms A and B independently; unsubscribing from A must not affect B
10. **Rate limiting shared state** — Verify rate limits are enforced across servers (same IP can't get 2x the limit)
11. **Redis unavailability** — Verify that Redis being down does not affect active calls (signaling is in-memory); push, watchers, and rate limiting degrade gracefully; verify `/healthz` returns 200 even when Redis is down; verify `/readyz` returns 503 when Redis is unreachable
12. **Shared VAPID keys** — Verify that two server instances with the same `VAPID_PRIVATE_KEY`/`VAPID_PUBLIC_KEY` env vars can each send web push to subscriptions created by the other instance; verify fallback to `vapid.json` when env vars are unset (single-instance compat)

### TURN tests (independent)
13. **Cloudflare TURN happy path** — Verify server calls Cloudflare API and returns credentials in legacy `TurnConfig` format; verify client connects to Cloudflare TURN successfully
14. **Cloudflare TURN failover** — Verify that when Cloudflare API is unreachable, server falls back to coturn credentials; verify client connects to coturn successfully
14. **TURN provider switch transparency** — Verify that switching between providers (by setting/unsetting `CF_TURN_KEY_ID`) requires zero client changes and no client restarts

## Alternatives Considered

### Redis centralized state
Move all room state to Redis so any server can handle any room. Required Lua scripts for atomic join (provisional capacity, second-participant locking, ghost eviction, stable JoinedAt) and atomic leave (host reassignment, empty room deletion race). Also required Redis pub/sub for cross-instance relay, a cross-node ghost eviction protocol (because ghost cleanup must close local WS/SSE connections), SSE sticky sessions (because SSE split GET/POST resolves `sid` from local memory), and careful reconnect token validation ordering to avoid security regression. **Rejected:** ~1200 lines of changes, Redis in the signaling hot path, multiple subtle distributed systems failure modes. Complexity not justified given that rooms are small, short-lived, and clients already handle reconnection.

### P2P server replication
Servers replicate room state to peers via internal connections so any server can handle any room. **Rejected:** Still requires a room-owner arbiter for join atomicity (effectively room affinity for the control plane). Adds peer connection management, cluster membership, and split-brain handling. Relay broadcast to all peers is wasted overhead when rooms are 2-4 participants and the LB already sends both to the same server.

### Embedded NATS
Embed NATS server in each Go instance for inter-node messaging. **Rejected:** Similar complexity to P2P replication with an additional dependency. Solves a problem (cross-server room state) that room affinity avoids entirely.

### TURN dual-provider `iceServers` array
Return both Cloudflare and coturn credentials simultaneously in a new `iceServers` array format so the ICE agent can try both providers. **Rejected:** Required wire format changes across all three client platforms, a 3-phase backward-compatibility rollout, and cross-repo coordination. Server-side provider selection with failover achieves the same goal (Cloudflare primary, coturn fallback) with zero client changes. The tradeoff — clients use one provider at a time instead of both simultaneously — is acceptable given Cloudflare's edge availability.

### Per-room Redis pub/sub for watchers
Subscribe to individual `room:status:{rid}` channels for each watched room. **Rejected:** Adds subscription lifecycle management complexity without benefit — typical watchers monitor 10-20 rooms, and the total event volume is trivially low. A single global `watcher:events` firehose is simpler. This approach also lacked ghost room protection (no TTL on status keys), meaning a server crash would leave orphaned room entries visible to watchers indefinitely.
