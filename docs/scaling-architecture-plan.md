# Scaling Architecture Plan: Room-Affinity + Cloudflare TURN

**Status:** Draft (v5 — transport routing, watcher bus, TURN migration)
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

The good news: media flows P2P (not through the server), so the signaling server is lightweight and low-throughput. A room with 4 participants generates maybe a few dozen signaling messages total. The scaling challenge is primarily **availability**, not throughput.

## Approach: Room-Affinity Routing

### Why room affinity over centralized state

An earlier iteration of this plan proposed moving all room state to Redis with Lua scripts for atomic operations (join, leave), Redis pub/sub for cross-instance relay, and a cross-node ghost eviction protocol. Code review revealed escalating complexity:

- **Lua scripts** required to replicate the join state machine (provisional capacity, second-participant locking, ghost eviction, stable JoinedAt)
- **Lua scripts** required for leave (atomic host reassignment + empty room deletion to prevent race with concurrent join)
- **SSE sticky sessions** required because SSE uses split GET/POST keyed by `sid`, resolved from local `clientsBySID` (`sse.go:98`)
- **Cross-node ghost eviction protocol** required because ghost cleanup must close local WS/SSE connections (`signaling.go:807-827`)
- **Reconnect token validation ordering** required careful two-phase design to avoid security regression (`signaling.go:350-355`)
- **Push subscription IDs** required composite-keyed Redis indexes to preserve `UNIQUE(room_id, endpoint)` semantics (`push.go:163`)
- **TURN wire format change** required across all three client platforms for dual-provider credentials

The core insight: Serenada's rooms are **2-4 participants and short-lived**. There is no scenario where a single room needs to span servers. The only benefit of cross-server room state was surviving a server crash — but clients already have robust reconnect logic, and media flows P2P throughout. A 5-second reconnect is invisible to users.

Room affinity eliminates all signaling complexity while still removing every SPOF.

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
              │   • Room status snapshots (for         │
              │     cross-server watchers)              │
              │   • Room status pub/sub (watcher        │
              │     update fanout)                      │
              └────────────────────────────────────────┘

     Clients ◄────── WebRTC P2P media ──────► Clients
        │                                        │
        │  ICE servers (returned as array):      │
        │  ┌─────────────────────────────────┐   │
        │  │ [0] Cloudflare TURNS (primary)  │   │
        │  │ [1] Self-hosted coturn (fallback)│   │
        │  └─────────────────────────────────┘   │
        └──── STUN/TURN ──► either provider  ◄───┘
```

### Key Design Decisions

- **Room-affinity routing.** The LB consistently hashes `rid` (room ID) from the query string to route all clients for a room to the same server instance. All room state stays in-memory with the current Hub/Room/Client mutex-based logic. **`signaling.go` core logic is unchanged.**
- **Client transport URL change required.** Current clients do not include `rid` on WS/SSE URLs (they send it later in the join message). All three clients must be updated to include `rid` as a query parameter. This is a prerequisite for room-affinity routing. See Layer 1.
- **No sticky session complexity.** Because all WS/SSE connections for a room land on the same server, SSE's split GET/POST works naturally — the `sid` lookup is always local.
- **Redis for non-signaling state only.** Push subscriptions, snapshots, rate limits, cross-server watcher status. Redis is never in the signaling hot path. A brief Redis outage degrades push notifications, watchers, and rate limiting but does not affect active calls.
- **Watcher-status bus for cross-server visibility.** Owner servers write room status snapshots to Redis and publish updates. Non-owner servers subscribe for their local watchers. Preserves the existing `room_statuses` / `room_status_update` push contract. See Layer 3.
- **Server failure = rooms on that server are lost.** Clients reconnect within seconds (existing resilience logic), create a new room on a surviving server. Media continues P2P throughout — users see a brief "reconnecting" overlay, not a dropped call.
- **Cloudflare TURN as primary, self-hosted coturn as fallback.** Requires a wire format change to support per-provider credentials. Rolled out independently from scaling. See Layer 6.
- **Single new Go dependency:** `go-redis/v9`.

## Layer 1: Transport URL Changes (Required for Room-Affinity Routing)

### Problem

Room-affinity routing requires the LB to hash on `rid` from the connection URL. **Current clients do not include `rid` on transport URLs:**

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

### Backward compatibility

Old clients without `rid` on the URL will still connect successfully — the server doesn't require it. The LB should fall back to round-robin for requests without `rid`, which means old clients may land on the wrong server for their room. This is acceptable during the rollout window: the worst case is that an old client's room ends up on a different server than expected, but since old clients are the only ones without `rid`, they'll be consistently round-robined and their room will be created on whatever server they land on.

Once all supported client versions include `rid`, the fallback can be removed.

## Layer 2: Signaling — What Changes (Almost Nothing)

### Unchanged

- `signaling.go` — Hub, Room, Client types, all join/leave/relay logic, mutex-based concurrency
- `ws.go` — WebSocket transport (server ignores new `rid` query param)
- `sse.go` — SSE transport (GET stream + POST send, `clientsBySID` lookup; server ignores new `rid` query param)
- `room_id.go` — HMAC-based room ID generation/validation
- `security.go` — CORS/origin validation

### Small additions

- `main.go` — Add `/healthz` endpoint (returns 200 if server is up, optionally checks Redis)

## Layer 3: Watchers — Redis Status Bus

### Problem

Current watchers (`watch_rooms`) use in-memory state and push-style updates. With room affinity, a watcher connected to Server A cannot see rooms owned by Server B. Watchers expect:

1. **`room_statuses`** — initial snapshot of all watched rooms: `{ "rid_1": { count, maxParticipants }, ... }`
2. **`room_status_update`** — incremental push on every watcher-visible change: `{ rid, count, maxParticipants }`

These updates fire on: join, leave, capacity lock (provisional → locked `maxParticipants`), room delete, host ends room. Simple existence flags or HTTP polling cannot replicate this contract.

### Solution: Redis room-status snapshot + pub/sub fanout

```
Owner server (has the room in memory):
  On every watcher-visible event (join/leave/capacity-lock/delete):
    1. SET room:status:{rid} → JSON { count, maxParticipants, version }
       - version = monotonic counter per room, so stale updates are ignored
       - If room deleted: DEL room:status:{rid}
    2. PUBLISH room:status:{rid} → same JSON payload

Any server (has local watcher clients):
  On watch_rooms request:
    1. For each requested rid:
       - If room is local → read from in-memory Hub (current behavior, unchanged)
       - If room is NOT local → GET room:status:{rid} from Redis
    2. Combine into room_statuses response
    3. SUBSCRIBE to room:status:{rid} for each non-local rid
    4. On pub/sub message → fan out as room_status_update to local watchers
       - Compare version to last-seen version; drop if stale/out-of-order
    5. When watcher disconnects → UNSUBSCRIBE from non-local room channels
```

### Why this works

- **Local rooms** (owned by this server): zero change — current in-memory watcher path is untouched
- **Remote rooms** (owned by another server): Redis snapshot for initial state, pub/sub for live updates
- **Low overhead**: room status is tiny (~50 bytes JSON), pub/sub messages fire only on room events (a few per call lifetime), not on every signaling message
- **No polling**: watcher gets push-style updates same as today
- **Version field**: prevents stale/out-of-order updates after watcher reconnect or Redis pub/sub redelivery

### Server changes

- `signaling.go` — After `broadcastRoomStatusUpdate()`: also write `room:status:{rid}` to Redis and `PUBLISH`. ~20 lines added to existing function.
- `signaling.go` — In `handleWatchRooms()`: for non-local rooms, read from Redis + subscribe to pub/sub. ~40 lines.
- New helper in `redis.go` — Redis pub/sub subscription manager for watcher channels. ~30 lines.

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

## Layer 6: TURN — Dual Provider (Separate Rollout from Scaling)

TURN migration is **decoupled from the scaling phases**. It can be done before, after, or in parallel with Phases 0-3. It does not depend on Redis, room affinity, or multi-instance deployment.

### Problem with current format

The current `TurnConfig` struct (`turn_auth.go:18`) returns a single `username`/`password` pair applied to all URIs. All three clients apply this single credential to all URIs. Cloudflare TURN and self-hosted coturn use different credential schemes, so a single pair cannot authenticate to both.

### New wire format: `iceServers` array

```json
{
  "iceServers": [
    {
      "urls": ["stun:stun.cloudflare.com:3478", "turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"],
      "username": "cf-generated-username",
      "credential": "cf-generated-credential"
    },
    {
      "urls": ["stun:coturn.serenada.app:3478", "turn:coturn.serenada.app:3478", "turns:coturn.serenada.app:5349?transport=tcp"],
      "username": "1674000000:192-168-1-1",
      "credential": "base64HmacSha1..."
    }
  ],
  "ttl": 900
}
```

This aligns with the [RTCIceServer](https://developer.mozilla.org/en-US/docs/Web/API/RTCIceServer) spec, which browsers and WebRTC libraries already support natively as an array.

### Backward-compatibility migration plan

Already-shipped mobile clients parse the current `{ username, password, uris, ttl }` format. Enabling Cloudflare TURN on the server without handling old clients would break them. Three-phase rollout:

**Phase T1 — Update all clients (server stays legacy):**
- Update Web, Android, iOS clients to parse **both** formats:
  - If response contains `iceServers` array → use new format
  - If response contains `username` + `uris` → use legacy format (current behavior)
- Ship updated clients. Server continues returning legacy format.
- **No user-facing change.** This is a no-op upgrade that prepares clients for the future.

**Phase T2 — Server returns dual format (transition period):**
- Server returns **both** fields in the same response:
  ```json
  {
    "iceServers": [ ... ],
    "username": "coturn-only-credential",
    "password": "coturn-only-credential",
    "uris": ["stun:coturn...", "turn:coturn...", "turns:coturn..."],
    "ttl": 900
  }
  ```
- Updated clients use `iceServers` (Cloudflare + coturn with separate credentials)
- Old clients ignore `iceServers` (unknown field) and use `username` + `uris` (coturn only)
- **Old clients still work**, they just don't get Cloudflare TURN. Since coturn is still running as fallback, this is fine.

**Phase T3 — Drop legacy format (after minimum supported version ships):**
- Once all supported client versions understand `iceServers`, stop including legacy fields
- Server returns only `{ iceServers, ttl }`
- coturn can optionally be decommissioned at this point (or kept as fallback)

### Changes required

**Server (`turn_auth.go`):**
- New struct: `IceServersResponse { IceServers []IceServer; TTL int }` + legacy fields
- `handleTurnCredentials()` builds the array: Cloudflare entry (via API call) + coturn entry (existing HMAC logic)
- During Phase T2: include both `iceServers` and legacy fields in response
- `CF_TURN_KEY_ID` unset → coturn-only in both formats (no Cloudflare call)

**Web client (`MediaEngine.ts`):**
- Parse `iceServers` array if present, fall back to legacy `{username, password, uris}` format
- Each array entry becomes a separate `RTCIceServer` in the peer connection config

**Android client (`TurnManager.kt` / `CoreApiClient.kt`):**
- Parse `iceServers` array, fall back to legacy format
- Each entry becomes a separate `PeerConnection.IceServer`

**iOS client (`TurnManager.swift` / `TurnCredentials.swift`):**
- Parse `iceServers` array, fall back to legacy format
- Each entry becomes a separate `IceServerConfig`

### Cloudflare TURN credential generation (server-side)

```
POST https://rtc.live.cloudflare.com/v1/turn/keys/{key-id}/credentials/generate
Authorization: Bearer {api-token}
Body: { "ttl": 900 }
```

Response provides the `urls`, `username`, `credential` for the Cloudflare entry.

### Cloudflare TURN pricing

$0.05/GB of relayed traffic. For a 1:1 video call where TURN is needed (~500kbps video), that's roughly $0.01/hour. Most calls don't need TURN at all (direct P2P works).

## Layer 7: Load Balancer

### Routing rules

| Path | Routing | Why |
|------|---------|-----|
| `/ws?rid=X` | Consistent hash on `rid` param | All room participants on same server |
| `/sse?rid=X&sid=Y` | Consistent hash on `rid` param | SSE GET + POST land on same server naturally |
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
| **Phase 3** | Add Redis watcher-status bus (snapshot + pub/sub). Move rate limiting to Redis. | Low — watcher changes are additive | Watchers can see rooms on other servers via Redis. Rate limits shared. Local-room watcher path unchanged. |
| **Phase 4** | Configure LB with consistent hash on `rid`. Deploy second server instance. | Low — signaling code unchanged | This is the actual multi-instance deployment. Depends on Phase 1 (clients ship `rid`). Test with load testing tool. |

### TURN phases (independent, can run in parallel with scaling)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase T1** | Update all three clients to parse both `iceServers` array and legacy format. Ship client updates. Server stays legacy. | Low — no behavior change | Prepares clients for dual-format response. No user-facing impact. |
| **Phase T2** | Server returns dual format (`iceServers` + legacy fields). Enable Cloudflare TURN. | Low — old clients use legacy fields (coturn only), new clients use `iceServers` (Cloudflare + coturn) | Both old and new clients work. Old clients miss Cloudflare TURN but still have coturn. |
| **Phase T3** | Drop legacy fields once minimum supported client version understands `iceServers`. Optionally decommission coturn. | Low — gated on client version adoption | Clean up. coturn can stay as fallback indefinitely. |

## Estimated Scope

| Component | Lines Changed (est.) | New Dependencies |
|-----------|---------------------|------------------|
| `rid` on transport URLs (Web) | ~10 | None |
| `rid` on transport URLs (Android) | ~10 | None |
| `rid` on transport URLs (iOS) | ~10 | None |
| Redis push subscriptions (with ID counter + composite keys) | ~150 | `go-redis/v9` |
| Redis snapshots (replace filesystem) | ~50 | (same) |
| Redis rate limiting (sliding window) | ~80 | (same) |
| Redis watcher-status bus (snapshot + pub/sub + fanout) | ~90 | (same) |
| Cloudflare TURN integration (server) | ~80 | None (HTTP call) |
| TURN wire format change (server — dual format) | ~50 | None |
| TURN wire format change (Web client) | ~30 | None |
| TURN wire format change (Android client) | ~30 | None |
| TURN wire format change (iOS client) | ~30 | None |
| Health check endpoint | ~15 | None |
| LB configuration (nginx) | ~20 | None |
| **Total** | **~655-720** | **1 new Go dep** |

## Files Affected

### Server (`server/`)
- `main.go` — Redis init, healthz endpoint
- `signaling.go` — Watcher-status writes: after `broadcastRoomStatusUpdate()`, write `room:status:{rid}` to Redis + `PUBLISH` (~20 lines). In `handleWatchRooms()`, read Redis for non-local rooms + subscribe to pub/sub (~40 lines).
- `push.go` — Redis-backed subscriptions with auto-incrementing IDs, Redis snapshot storage
- `rate_limit.go` — Redis sliding window rate limiting
- `turn_auth.go` — New `iceServers` array format (dual-format during transition), Cloudflare TURN credential generation, coturn fallback
- New: `redis.go` — Redis client setup, connection pool, watcher pub/sub subscription helpers

### Client changes — transport URLs (`rid` parameter)
- `client/packages/core/src/signaling/transports/ws.ts` — Add `rid` query param to WS URL
- `client/packages/core/src/signaling/transports/sse.ts` — Add `rid` query param to SSE URL
- `client-android/serenada-core/.../WebSocketSignalingTransport.kt` — Add `rid` to `buildWssUrl()`
- `client-android/serenada-core/.../SseSignalingTransport.kt` — Add `rid` to `buildSseUrl()`
- `client-ios/SerenadaCore/Sources/Signaling/WebSocketSignalingTransport.swift` — Add `rid` to `buildWssURL()`
- `client-ios/SerenadaCore/Sources/Signaling/SseSignalingTransport.swift` — Add `rid` to `buildSseURL()`

### Client changes — TURN format
- `client/packages/core/src/media/MediaEngine.ts` — Parse `iceServers` array with legacy fallback
- `client-android/serenada-core/.../TurnManager.kt` or `CoreApiClient.kt` — Parse `iceServers` array with legacy fallback
- `client-ios/SerenadaCore/Sources/Networking/TurnCredentials.swift` — Parse `iceServers` array with legacy fallback

### Docker / Infra
- `docker-compose.yml` — Add Redis service, add second `app-server`, keep coturn
- `docker-compose.prod.yml` — Same + nginx upstream with consistent hash
- `nginx/nginx.prod.conf` — `hash $arg_rid consistent;` in upstream block
- `.env.example` — New vars: `REDIS_URL`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`

### NOT changed
- `signaling.go` — Hub/Room/Client types, join/leave/relay logic, mutex concurrency (unchanged beyond watcher-status additions)
- `ws.go` — WebSocket transport (server ignores `rid` query param)
- `sse.go` — SSE transport (server ignores `rid` query param)
- `room_id.go` — Room ID validation (unchanged)
- `security.go` — CORS (unchanged)

## Required Test Coverage

### Scaling tests
1. **Transport `rid` parameter** — Verify all three clients include `rid` on WS and SSE URLs; verify server accepts connections with and without `rid` (backward compat)
2. **Room-affinity routing** — Verify all clients for the same `rid` land on the same server via LB consistent hash
3. **Server failure + client reconnect** — Kill one server, verify clients reconnect to surviving server within resilience timeout, new room is created, WebRTC renegotiates
4. **Consistent hash rebalancing** — Add/remove server, verify only ~1/N rooms are disrupted
5. **Watcher cross-server visibility** — Verify watchers see room status for rooms on other servers; verify `room_statuses` initial response includes remote rooms; verify `room_status_update` fires for remote room events (join, leave, capacity lock, delete); verify version monotonicity prevents stale updates
6. **Snapshot recipient ID mapping** — Verify numeric IDs survive Redis migration and round-trip correctly through subscribe → recipients → upload → deliver
7. **Multi-room push subscriptions** — Verify the same endpoint can subscribe to rooms A and B independently; unsubscribing from A must not affect B
8. **Rate limiting shared state** — Verify rate limits are enforced across servers (same IP can't get 2x the limit)
9. **Redis unavailability** — Verify that Redis being down does not affect active calls (signaling is in-memory); push, watchers, and rate limiting degrade gracefully

### TURN tests (independent)
10. **TURN dual-format response** — Verify server returns both `iceServers` and legacy fields during Phase T2
11. **TURN new client + new server** — Verify updated clients parse `iceServers` array, create separate ICE server entries per provider
12. **TURN old client + new server** — Verify old clients ignore `iceServers` and use legacy fields (coturn only); calls still work
13. **TURN new client + old server** — Verify updated clients fall back to legacy format when `iceServers` is absent

## Alternatives Considered

### Redis centralized state (Option 2, v1-v3 of this plan)
Move all room state to Redis. Any server handles any room. Required Lua scripts for atomic join/leave, Redis pub/sub for relay, cross-node ghost eviction protocol, SSE sticky sessions. **Rejected:** ~1200 lines of changes, Redis in the signaling hot path, multiple subtle distributed systems failure modes. Complexity not justified by the marginal benefit of room survival.

### P2P server replication
Servers replicate room state to peers via internal connections. Any server handles any room. **Rejected:** Still requires a room-owner arbiter for join atomicity (which is effectively room affinity for the control plane). Adds peer connection management, cluster membership, split-brain handling. The relay broadcast is wasted overhead when rooms are 2-4 participants and the LB already sends both to the same server.

### Embedded NATS
Embed NATS server in each Go instance for inter-node messaging. **Rejected:** Similar complexity to P2P replication with an additional dependency. Solves a problem (cross-server room state) that room affinity avoids entirely.
