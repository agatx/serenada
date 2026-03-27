# Scaling Architecture Plan: Room-Affinity + Cloudflare TURN

**Status:** Draft (v7)
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
| All | `wss://{host}/ws?rid={rid}` | `https://{host}/sse?rid={rid}&sid={sid}` |

**Web client** (`client/packages/core/src/signaling/transports/ws.ts`):
- `buildWsUrl()`: append `?rid={roomId}` query parameter
- The `roomId` is already available in `SignalingEngine` (set before `connect()` is called)

**Web client** (`client/packages/core/src/signaling/transports/sse.ts`):
- `buildSseUrl()`: add `rid={roomId}` alongside existing `sid` parameter

**Android client** (`serenada-core/.../WebSocketSignalingTransport.kt`):
- `buildWssUrl()` at line 77: append `?rid={roomId}` parameter

**Android client** (`serenada-core/.../SseSignalingTransport.kt`):
- `buildSseUrl()` at line 191: add `rid` query parameter alongside `sid`

**iOS client** (`SerenadaCore/Sources/Signaling/WebSocketSignalingTransport.swift`):
- `buildWssURL()` at line 99: add `rid` query item

**iOS client** (`SerenadaCore/Sources/Signaling/SseSignalingTransport.swift`):
- `buildSseURL()` at line 135: add `rid` query item alongside `sid`

**Server** (`ws.go`, `sse.go`):
- Server ignores the `rid` query parameter (it's consumed by the LB only)
- No server-side changes needed for this parameter

**Diagnostics probes:** If SSE/WS diagnostics probes open connections without a room context, they should use a sentinel value (e.g., `rid=_diag`) so the LB can still route them. These connections don't participate in rooms, so any server can handle them.

### Backward compatibility & Rollout Safety

Old clients without `rid` on the URL cannot participate in room-affinity. If the LB falls back to round-robin for these requests, two old clients joining the same room will likely land on different servers, creating a split-brain (disjoint in-memory rooms). 

**Critical Invariant:** Phase 4 (multi-instance deployment) **must be strictly gated** until all supported participant client versions send `rid`. Until the rollout is complete, the backend must run as a single instance.

## Layer 2: Signaling — What Changes (Almost Nothing)

### Minor Changes Required

- **`signaling.go` Enforce `rid` matching** — In `handleJoin`, the server must validate that the `join` payload's `roomId` exactly matches the transport URL's `rid`. If they differ, the server must reject the join. This prevents clients from bypassing the LB's hash ring or switching rooms on a single connection. Clients must establish a new transport to join a different room.
- `ws.go` / `sse.go` — WebSocket and SSE transports must extract the `rid` query param and pass it to the `Client` struct for validation during join.
- `room_id.go` / `security.go` — Unchanged.

### Small additions

- `main.go` — Add `/healthz` endpoint (returns 200 if server is up, optionally checks Redis)

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
    1. SET room:status:{rid} → JSON { count, maxParticipants, version } EX 30
       - version = monotonic counter per room, for stale update detection
       - 30-second TTL auto-expires if server crashes (no explicit DEL needed)
       - If room deleted: DEL room:status:{rid} (immediate cleanup; TTL is the safety net)
    2. PUBLISH watcher:events → JSON { rid, count, maxParticipants, version, deleted }
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
    1. Parse { rid, count, maxParticipants, version, deleted }
    2. Check if any local watcher cares about this rid
    3. If yes: compare version to last-seen version; drop if stale/out-of-order
    4. Fan out as room_status_update to matching local watchers (same message format as today)

  On Redis key expiry (ghost room cleanup):
    - Server does NOT need to detect expiry in real-time
    - The watcher UI already handles count=0 gracefully (room shown as empty)
    - On next watch_rooms request or page refresh, the expired key returns nil → count=0
    - Optionally: a periodic sweep (every 30s) can re-check watched rooms and push
      room_status_update with count=0 for any that have expired
```

### Why this works

- **Local rooms** (owned by this server): zero change — current in-memory watcher path is untouched
- **Remote rooms** (owned by another server): Redis snapshot for initial state, global pub/sub for live updates
- **Ghost room protection**: TTL + heartbeat ensures rooms auto-vanish within 30 seconds of server crash. No orphaned keys.
- **Single subscription per server**: One `SUBSCRIBE watcher:events` instead of N per-room subscriptions. Simpler Go code, no subscription lifecycle management, no Redis connection pool pressure.
- **Low overhead**: room status events are tiny (~80 bytes JSON) and fire only on room events (a few per call lifetime). Even with 1000 concurrent rooms across all servers, the firehose carries <1 message/second.
- **No polling for live updates**: watcher gets push-style updates same as today
- **Version field**: prevents stale/out-of-order updates after watcher reconnect or pub/sub redelivery

### Server changes

- `signaling.go` — After `broadcastRoomStatusUpdate()`: write `room:status:{rid}` to Redis with EX 30 and `PUBLISH watcher:events`. ~20 lines.
- `signaling.go` — In `handleWatchRooms()`: for non-local rooms, read from Redis. ~15 lines.
- `sse.go` or `main.go` — Heartbeat goroutine: every 15s, refresh TTL on all active rooms. ~15 lines.
- New helper in `redis.go` — Single `watcher:events` subscriber, local watcher interest tracking, version-checked fanout. ~40 lines.

## Layer 4: Push Subscriptions — SQLite to Redis

### Problem

SQLite is a local file SPOF. Push subscriptions must be shared across server instances.

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

Clients use one TURN provider per credential fetch, not both simultaneously. In practice this is fine: Cloudflare TURN has high availability (global edge), and if it's down, the server falls back to coturn on the next credential request. The 15-minute worst case (current turn token TTL) can be shortened by reducing the turn-refresh interval if needed.

### Cloudflare TURN pricing

$0.05/GB of relayed traffic. For a 1:1 video call where TURN is needed (~500kbps video), that's roughly $0.01/hour. Most calls don't need TURN at all (direct P2P works).

## Layer 7: Load Balancer

### Routing rules

| Path | Routing | Why |
|------|---------|-----|
| `/ws?rid=X` | Consistent hash on `rid` param | All room participants on same server |
| `/sse?rid=X&sid=Y` | Consistent hash on `rid` param | Participant SSE logic |
| `/ws` (Watchers) | Round-robin | Watchers monitor multiple rooms, so they lack a single `rid`. Because the watcher firehose is global, they can land on any server. |
| `/sse?sid=Y` (Watchers) | Consistent hash on `sid` param | Watcher SSE needs sticky sessions. Since watcher connections lack `rid`, hashing on `sid` ensures GET and POST land on the same server. |
| `/api/turn-credentials` | Round-robin | Stateless credential generation |
| `/api/room-id` | Round-robin | Stateless |
| `/api/push/*` | Round-robin | Reads/writes Redis (shared) |
| `/api/internal/stats` | Per-server | Server-specific metrics |
| `/device-check` | Round-robin | Stateless |
| `/healthz` | Per-server (LB health probe) | Server health |

### SSE detail

With room-affinity, the SSE sticky session problem disappears. The client includes `rid` on both the GET (open stream) and POST (send message) requests. The LB hashes `rid` → same server for both. Since `clientsBySID` is local and both requests land on the same server, the `sid` lookup always succeeds.

**Pre-room SSE connections** (client connects before knowing the room ID): these don't exist in the current client flow. Clients open WS/SSE only when navigating to `/call/:roomId`, at which point the room ID is known.

### LB options

| LB | Configuration | Notes |
|----|--------------|-------|
| **nginx upstream** | `hash $arg_rid consistent;` | Works with existing docker-compose. Add second `app-server` service. |
| **Cloudflare LB** | Session affinity rule on `rid` query param | Zero-infra if already on Cloudflare |
| **Fly.io** | `fly-replay` header based on rid hash | Built-in multi-region support |

### Server failure and rehashing

When a server crashes:
1. LB health check detects failure (1-3 seconds)
2. LB rehashes — rooms from dead server distribute across surviving servers
3. Clients' WS/SSE connections drop → existing reconnect logic kicks in
4. Clients reconnect, LB routes to surviving server, new room is created
5. WebRTC renegotiation happens automatically
6. **Media never stopped** — P2P stream continued throughout

When a server is added:
1. Consistent hashing means only ~1/N rooms rehash to the new server
2. Those rooms' clients reconnect naturally (same flow as above)
3. No drain needed for a clean addition — but graceful shutdown is nice-to-have

## Migration Path (Phased)

### Scaling phases (sequential)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase 0** | Add `/healthz` endpoint. Add Redis client dependency (`go-redis/v9`). Keep everything else unchanged. | None — additive | Prepare the codebase for Redis without changing behavior. |
| **Phase 1** | Add `rid` query parameter to WS/SSE URLs across all three clients. Server ignores it. | Low — additive, no behavior change | LB prerequisite. Ship client updates. Existing clients without `rid` continue working via round-robin fallback. |
| **Phase 2** | Move push subscriptions + snapshots to Redis (with stable numeric IDs). Remove SQLite dependency. Deploy 1 instance + Redis. | Low — push is non-critical path | Push subs use counter-based IDs. Snapshots become Redis keys with TTL. SQLite + filesystem removed. |
| **Phase 3** | Add Redis watcher firehose (TTL heartbeat + global pub/sub). Move rate limiting to Redis. | Low — watcher changes are additive | Watchers can see rooms on other servers via Redis. Ghost rooms auto-expire. Rate limits shared. Local-room watcher path unchanged. |
| **Phase 4** | Configure LB with consistent hash on `rid`. Deploy second server instance. | Low — signaling code unchanged | This is the actual multi-instance deployment. Depends on Phase 1 (clients ship `rid`). Test with load testing tool. |

### TURN phase (independent, can run at any time)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase T** | Add Cloudflare TURN provider in `turn_auth.go` with server-side failover to coturn. Set `CF_TURN_KEY_ID` + `CF_TURN_API_TOKEN` env vars. | Low — server-only, zero client changes | Clients receive credentials in the same legacy `TurnConfig` format. Cloudflare primary, coturn fallback. Can be deployed independently of scaling phases. |

## Estimated Scope

| Component | Lines Changed (est.) | New Dependencies |
|-----------|---------------------|------------------|
| `rid` on transport URLs (Web) | ~10 | None |
| `rid` on transport URLs (Android) | ~10 | None |
| `rid` on transport URLs (iOS) | ~10 | None |
| Redis push subscriptions (with ID counter + composite keys) | ~150 | `go-redis/v9` |
| Redis snapshots (replace filesystem) | ~50 | (same) |
| Redis rate limiting (sliding window) | ~80 | (same) |
| Redis watcher firehose (TTL heartbeat + global pub/sub + fanout) | ~90 | (same) |
| Cloudflare TURN with server-side failover | ~60 | None (HTTP call) |
| Health check endpoint | ~15 | None |
| LB configuration (nginx) | ~20 | None |
| **Total** | **~495-550** | **1 new Go dep** |

## Files Affected

### Server (`server/`)
- `main.go` — Redis init, healthz endpoint, room-status heartbeat goroutine
- `signaling.go` — Watcher-status writes: after `broadcastRoomStatusUpdate()`, write `room:status:{rid}` with EX 30 + `PUBLISH watcher:events` (~20 lines). In `handleWatchRooms()`, read Redis for non-local rooms (~15 lines).
- `push.go` — Redis-backed subscriptions with auto-incrementing IDs, Redis snapshot storage
- `rate_limit.go` — Redis sliding window rate limiting
- `turn_auth.go` — Cloudflare TURN credential generation with coturn fallback (~60 lines)
- New: `redis.go` — Redis client setup, connection pool, single `watcher:events` subscriber, local watcher interest tracking + version-checked fanout

### Client changes — transport URLs only (`rid` parameter)
- `client/packages/core/src/signaling/transports/ws.ts` — Add `rid` query param to WS URL
- `client/packages/core/src/signaling/transports/sse.ts` — Add `rid` query param to SSE URL
- `client-android/serenada-core/.../WebSocketSignalingTransport.kt` — Add `rid` to `buildWssUrl()`
- `client-android/serenada-core/.../SseSignalingTransport.kt` — Add `rid` to `buildSseUrl()`
- `client-ios/SerenadaCore/Sources/Signaling/WebSocketSignalingTransport.swift` — Add `rid` to `buildWssURL()`
- `client-ios/SerenadaCore/Sources/Signaling/SseSignalingTransport.swift` — Add `rid` to `buildSseURL()`

### Client changes — TURN
None.

### Docker / Infra
- `docker-compose.yml` — Add Redis service, add second `app-server`, keep coturn
- `docker-compose.prod.yml` — Same + nginx upstream with consistent hash
- `nginx/nginx.prod.conf` — `hash $arg_rid$arg_sid consistent;` in upstream block (hashes on `rid` if present, or `sid` for watcher SSE fallback)
- `.env.example` — New vars: `REDIS_URL`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`

### NOT changed
- `signaling.go` — Hub/Room/Client types, join/leave/relay logic, mutex concurrency (unchanged beyond watcher-status additions)
- `ws.go` — WebSocket transport (extracts `rid` and passes to Client)
- `sse.go` — SSE transport (extracts `rid` and passes to Client)
- `room_id.go` — Room ID validation (unchanged)
- `security.go` — CORS (unchanged)
- All client TURN parsing code (unchanged — legacy format preserved)

## Required Test Coverage

### Scaling tests
1. **Transport `rid` parameter** — Verify all three clients include `rid` on WS and SSE URLs; verify server accepts connections with and without `rid` (backward compat)
2. **Room-affinity routing** — Verify all clients for the same `rid` land on the same server via LB consistent hash
3. **Server failure + client reconnect** — Kill one server, verify clients reconnect to surviving server within resilience timeout, new room is created, WebRTC renegotiates
4. **Consistent hash rebalancing** — Add/remove server, verify only ~1/N rooms are disrupted
5. **Watcher cross-server visibility** — Verify watchers see room status for rooms on other servers; verify `room_statuses` initial response includes remote rooms; verify `room_status_update` fires for remote room events (join, leave, capacity lock, delete); verify version monotonicity prevents stale updates
6. **Watcher ghost room cleanup** — Kill a server, verify its rooms' Redis status keys expire within 30 seconds; verify watchers see count=0 after expiry
7. **Snapshot recipient ID mapping** — Verify numeric IDs survive Redis migration and round-trip correctly through subscribe → recipients → upload → deliver
8. **Multi-room push subscriptions** — Verify the same endpoint can subscribe to rooms A and B independently; unsubscribing from A must not affect B
9. **Rate limiting shared state** — Verify rate limits are enforced across servers (same IP can't get 2x the limit)
10. **Redis unavailability** — Verify that Redis being down does not affect active calls (signaling is in-memory); push, watchers, and rate limiting degrade gracefully

### TURN tests (independent)
11. **Cloudflare TURN happy path** — Verify server calls Cloudflare API and returns credentials in legacy `TurnConfig` format; verify client connects to Cloudflare TURN successfully
12. **Cloudflare TURN failover** — Verify that when Cloudflare API is unreachable, server falls back to coturn credentials; verify client connects to coturn successfully
13. **TURN provider switch transparency** — Verify that switching between providers (by setting/unsetting `CF_TURN_KEY_ID`) requires zero client changes and no client restarts

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
