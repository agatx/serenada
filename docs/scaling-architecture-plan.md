# Scaling Architecture Plan: Redis Pub/Sub + Cloudflare TURN

**Status:** Draft (v2 — revised after code review)
**Date:** 2026-03-25

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

## State Inventory

Every piece of state that needs a scaling strategy:

| State | Current Location | Size/Volume | Lifetime |
|-------|-----------------|-------------|----------|
| `Hub.rooms` (room membership, host, capacity) | In-memory map | Small (~100 bytes/room) | Duration of call |
| `Hub.clients` / `clientsBySID` | In-memory map | Small (~200 bytes/client) | Duration of WS/SSE connection |
| `Hub.watchers` (room occupancy monitors) | In-memory map | Small | Duration of watcher connection |
| Rate limit buckets (`IPLimiter`) | In-memory map | Small | 30min TTL, auto-pruned |
| Push subscriptions | SQLite (`subscriptions.db`) | Rows, grows over time | Persistent until unsubscribed |
| VAPID keys | File (`vapid.json`) | 2 keys | Permanent |
| Push snapshots | Filesystem (`data/snapshots/`) | Up to 300KB each | 10min TTL |
| TURN credentials | Generated on-the-fly (HMAC) | Stateless | 15min TTL |
| Reconnect tokens | Generated on-the-fly (HMAC) | Stateless | Stateless (verified by HMAC) |

## Target Architecture

```
                         ┌──────────────────────────────────┐
                         │        Cloud Load Balancer        │
                         │  (TLS termination, health checks) │
                         │  WS: sticky by IP or cookie       │
                         │  SSE: sticky by sid param          │
                         └──────┬───────────┬───────────────┘
                                │           │
                    ┌───────────▼──┐  ┌─────▼───────────┐
                    │  Server A    │  │  Server B        │
                    │  (Go)        │  │  (Go)            │
                    │              │  │                   │
                    │ Local:       │  │ Local:            │
                    │  • WS/SSE    │  │  • WS/SSE        │
                    │    conns     │  │    conns          │
                    │  • Client    │  │  • Client         │
                    │    objects   │  │    objects         │
                    │  • clientsBy │  │  • clientsBy      │
                    │    SID map   │  │    SID map        │
                    └──────┬───┬──┘  └──┬───┬────────────┘
                           │   │        │   │
              ┌────────────▼───▼────────▼───▼─────────┐
              │            Redis (Sentinel)             │
              │                                        │
              │  Shared:                               │
              │   • Room state (hashes)                │
              │   • Cross-instance relay (pub/sub)     │
              │   • Ghost eviction commands (pub/sub)  │
              │   • Watcher notifications (pub/sub)    │
              │   • Rate limit counters                │
              │   • Push subscriptions (with auto-IDs) │
              │   • Snapshots (binary, 10min TTL)      │
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

- **Sticky sessions required for SSE transport.** The SSE transport uses split GET (stream) + POST (send) connections keyed by `sid`, resolved from the local `clientsBySID` map (`sse.go:98`). A POST landing on the wrong server returns 410 Gone. The LB must route SSE requests to the same backend by `sid` query param. WS connections are self-contained (single upgraded connection), so WS only needs stickiness for connection lifetime (which any LB provides natively).
- **Redis Sentinel for HA.** Open-source, ships with Redis, zero licensing cost. 3 nodes (1 primary + 2 replicas).
- **Cloudflare TURN as primary, self-hosted coturn as fallback.** Requires a wire format change to support per-server credentials (see Layer 3).
- **Lua scripts for atomic room operations.** Redis MULTI/EXEC cannot conditionally abort based on intermediate reads, so the join flow's capacity locking and ghost eviction require Lua scripts (see Layer 2).
- **Single new Go dependency:** `go-redis/v9`.

## Layer 1: Signaling — What Stays Local vs. Moves to Redis

### Stays local (per-instance, not shared)

- `Client` struct (WS/SSE connection, send channel, transport goroutines)
- `clientsBySID` map — only the server holding the connection needs this; SSE POST resolution depends on this being local
- SSE stale reaper — only evicts local connections

### Moves to Redis

| Current | Redis Structure | Key Pattern |
|---------|----------------|-------------|
| `Hub.rooms[rid]` | Hash | `room:{rid}` → `{hostCid, maxParticipants, capacityLocked, requestedMax}` |
| `Room.Participants` | Hash | `room:{rid}:participants` → `{cid: serverID\|joinedAt}` |
| `Hub.watchers` | Not stored — use pub/sub channel instead | — |
| Rate limit buckets | Sorted set + Lua script (sliding window) | `rl:{endpoint}:{ip}` |
| Push subscriptions | Hash + counter (see Layer 5) | `push:subs:{roomId}`, `push:sub:{id}`, `push:id_counter` |
| Snapshots | Binary key + TTL | `snap:{id}:data`, `snap:{id}:meta` with 10min `EXPIRE` |

### Cross-instance messaging (Redis Pub/Sub)

```
Channel: "room:{rid}"
  → All signaling messages for that room (offer, answer, ice, room_state, etc.)
  → Server subscribes when it has ≥1 local client in that room
  → Server unsubscribes when its last local client leaves

Channel: "room:{rid}:status"
  → Occupancy change notifications (for watcher clients)
  → Server subscribes when it has ≥1 local watcher for that room

Channel: "server:{instanceID}"
  → Targeted commands to a specific server instance (ghost eviction, etc.)
  → Each server subscribes to its own channel on startup
```

## Layer 2: How Key Operations Change

### Join flow — Lua script required

The current join logic (`signaling.go:265-484`) performs a complex multi-step state transition under mutex:
1. Create room if missing (with provisional capacity if group-capable)
2. Validate reconnect token and evict ghost if reconnecting
3. Lock capacity on second participant (min of requested, client capability, server ceiling)
4. Reject clients that don't support the room's locked capacity
5. Check room full after ghost eviction
6. Add participant, record stable JoinedAt timestamp

**Redis MULTI/EXEC cannot express this** because it cannot conditionally abort based on intermediate read results (e.g., "if HLEN == 1 AND capacityLocked == false, then update maxParticipants"). This requires a **Lua script** that executes atomically on the Redis server.

```
New (Lua script: room_join.lua):
  Inputs: rid, cid, serverID, reconnectCID, reconnectToken,
          clientMaxParticipants, createMaxParticipants, serverCeiling

  Atomically:
  1. HSETNX room:{rid} fields to create if missing
     - If new: set provisional capacity (maxParticipants=2 if createMax>2,
       capacityLocked=false, requestedMax=createMax)
  2. If reconnectCID provided:
     - HGET room:{rid}:participants {reconnectCID} → get ownerServerID
     - If exists: HDEL the ghost entry (Redis side); return ownerServerID
       for local cleanup (see ghost eviction below)
  3. If !capacityLocked AND participant count == 1:
     - Lock capacity: min(requestedMax, clientMaxParticipants, serverCeiling)
     - HSET capacityLocked=true, maxParticipants=lockedValue
  4. If clientMaxParticipants < room.maxParticipants → return ROOM_CAPACITY_UNSUPPORTED
  5. If participant count >= maxParticipants → return ROOM_FULL
  6. HSET room:{rid}:participants {cid} → {serverID}|{joinedAt}
     - Only set joinedAt if CID has no existing timestamp (stable on reconnect)
  7. Return success + participant list + room metadata

  The Go server then:
  - Sends "joined" to the local client
  - PUBLISH room:{rid} → room_state message
  - If ghost was on another server: publish eviction command (see below)
```

### Relay flow (offer/answer/ice)

```
Current:
  1. Find room in local Hub
  2. Iterate room.Participants, send to each local *Client

New:
  1. PUBLISH room:{rid} → the relay message (with "from" CID and optional "to" CID)
  2. Each server receiving the pub/sub message:
     - Checks if it has local clients matching the target CID
     - Delivers to matching local WS/SSE connections
     - Ignores if no local clients match (message was for another server's clients)
```

### Disconnect / leave

```
Current:
  1. Remove client from local Hub.clients
  2. Remove from Room.Participants
  3. If room empty, delete room

New:
  1. Remove client from local maps
  2. HDEL room:{rid}:participants {cid}
  3. If HLEN == 0, DEL room:{rid} + DEL room:{rid}:participants
  4. PUBLISH room:{rid} → room_state update
```

### Reconnect — explicit cross-node ghost eviction

The current ghost eviction (`signaling.go:346-416`, `signaling.go:807-827`) performs critical cleanup: removes the ghost from the hub's client maps, removes from watchers, decrements transport stats, and **closes the ghost's send channel** (which terminates the WS/SSE write pump). Simply overwriting the Redis participant entry is **not safe** — the ghost's local WS/SSE connection remains active on the old server and can continue relaying stale messages.

```
New (two cases):

Case A — Ghost is on THIS server:
  1. Lua script removes ghost from Redis participants (step 2 above)
  2. Server finds ghost in local clientsBySID → runs cleanupEvictedClient()
     (close send channel, remove from local maps, decrement stats)
  3. Proceed with join

Case B — Ghost is on ANOTHER server:
  1. Lua script removes ghost from Redis participants, returns ownerServerID
  2. Joining server publishes eviction command to "server:{ownerServerID}":
     { type: "evict_ghost", cid: "C-xxx", sid: "S-yyy", rid: "room123" }
  3. Owner server receives command, finds ghost client locally,
     runs cleanupEvictedClient() (close send channel, etc.)
  4. Owner server publishes ack (optional — join can proceed optimistically
     since Redis participant entry is already updated)

  Safety invariant: after the Lua script runs, the ghost's CID is removed
  from Redis participants. Even if the old server hasn't cleaned up yet,
  the ghost cannot relay because:
  - Relay messages are published to room:{rid} pub/sub
  - The relay handler on the old server checks if the sending client's
    CID is still in Redis participants before publishing
  - Ghost's CID is gone → relay blocked

  This requires adding a Redis participants check to the relay path
  (HGET room:{rid}:participants {senderCID} before PUBLISH).
```

## Layer 3: TURN — Dual Provider with Wire Format Change

### Problem with current format

The current `TurnConfig` struct (`turn_auth.go:18`) returns a single `username`/`password` pair applied to all URIs:

```json
{
  "username": "1674000000:192-168-1-1",
  "password": "base64HmacSha1...",
  "uris": ["stun:stun.example.com", "turn:turn.example.com", "turns:turn.example.com:443?transport=tcp"],
  "ttl": 900
}
```

All three clients (Web `MediaEngine.ts:747`, Android `TurnManager.kt`, iOS `TurnManager.swift`) apply this single credential to all URIs. Cloudflare TURN and self-hosted coturn use different credential schemes, so a single pair cannot authenticate to both.

### New wire format: `iceServers` array

Change the response to return an array of ICE server configurations, each with its own credentials:

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

### Changes required

**Server (`turn_auth.go`):**
- New struct: `IceServersResponse { IceServers []IceServer; TTL int }`
- `handleTurnCredentials()` builds the array: Cloudflare entry (via API call) + coturn entry (existing HMAC logic)
- Backward compat: if `CF_TURN_KEY_ID` is unset, fall back to current single-provider format

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

## Layer 4: Load Balancer + Deployment

### Load balancer requirements

- WebSocket upgrade support (for `/ws`)
- Long-lived HTTP connections (for `/sse`)
- **Sticky sessions for SSE transport** — route by `sid` query parameter so that SSE POST requests (which send signaling messages) land on the same server that holds the SSE GET stream. Without this, `hub.getClientBySID(sid)` returns nil and the POST gets 410 Gone.
- WS connections are inherently sticky (single upgraded connection), but the LB should support connection draining on deploy.
- Health check endpoint (`GET /healthz` → 200 if Redis is reachable)

### Sticky session strategies

| LB | WS | SSE Stickiness | Notes |
|----|-----|---------------|-------|
| **Cloudflare LB** | Native WS support | Session affinity by cookie or header | Can use `sid` in cookie; simplest if already on Cloudflare |
| **nginx upstream** | `proxy_http_version 1.1; proxy_set_header Upgrade` | `hash $arg_sid consistent` | Requires extracting `sid` from query string |
| **Fly.io** | Native | `fly-force-instance-id` header or `fly-prefer-region` | App can set affinity header in SSE response |
| **AWS ALB** | Native | Sticky sessions by cookie | Standard; requires cookie-based affinity |

### Redis deployment

- **Managed (simplest):** Upstash Redis (serverless, free tier 10K cmds/day), or Redis Cloud free tier (30MB).
- **Self-hosted:** Redis Sentinel with 3 nodes (1 primary + 2 replicas). Lightweight — Redis uses ~10MB RAM for this workload.

## Layer 5: Push Subscriptions — Stable Numeric IDs

### Problem

The current SQLite schema uses `INTEGER PRIMARY KEY AUTOINCREMENT` for subscription IDs. The snapshot upload flow depends on these stable numeric IDs:
1. Client calls `GET /api/push/recipients` → receives `[{id: 5, publicKey: ...}, {id: 7, publicKey: ...}]`
2. Client encrypts snapshot key material per recipient ID
3. Client uploads snapshot with `recipients: [{id: 5, wrappedKey: ...}, {id: 7, wrappedKey: ...}]`
4. Server stores wrapped keys keyed by numeric ID in snapshot metadata
5. Push delivery (`sendOne`) looks up `snapshotMeta.Recipients[fmt.Sprintf("%d", target.ID)]`

A plain Redis hash without auto-incrementing IDs would break this correlation.

### Solution: Redis counter + structured keys

```
INCR push:id_counter → returns next ID (e.g., 42)

HSET push:sub:42 room_id <rid> transport <t> endpoint <ep> auth <a>
     p256dh <p> locale <l> enc_pubkey <k> created_at <ts>

SADD push:room:{rid} 42       # index: room → subscription IDs
SET  push:ep:{endpoint} 42    # index: endpoint → subscription ID (for UPSERT/dedup)
```

**Lookup patterns:**
- Subscribe: `INCR` to get ID, `HSET` the sub, `SADD` to room index, `SET` endpoint index
- Unsubscribe: look up ID by endpoint, `DEL` the sub hash, `SREM` from room, `DEL` endpoint index
- Get recipients for room: `SMEMBERS push:room:{rid}` → get IDs → `HGETALL` each
- Snapshot key lookup: same numeric IDs, no change to snapshot metadata format

## Migration Path (Phased)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase 0** | Add `/healthz` endpoint. Add Redis client dependency (`go-redis/v9`). Add server instance ID. Keep everything else unchanged. | None — additive | Prepare the codebase for Redis without changing behavior. |
| **Phase 1** | Move push subscriptions + snapshots to Redis (with stable numeric IDs). Remove SQLite dependency. Deploy 1 instance + Redis. | Low — push is non-critical path | Push subs use counter-based IDs. Snapshots become Redis keys with TTL. SQLite + filesystem removed. |
| **Phase 2** | Move room state to Redis. Implement Lua join script. Add pub/sub for cross-instance relay and ghost eviction. Rate limiting to Redis. Deploy 2 instances with sticky LB. | **High — core signaling changes** | Hub becomes a thin local cache + Redis backend. Lua script handles atomic join. Cross-node ghost eviction via server channels. This is the main effort. |
| **Phase 3** | Change TURN wire format to `iceServers` array. Update all three clients. Add Cloudflare TURN credential generation. Keep coturn as fallback. | Medium — cross-platform client changes | Backward-compatible: new clients handle both formats, server returns legacy format if CF not configured. |
| **Phase 4** | Clean up: remove SQLite migration code, clean up env vars, update documentation. | Low — just removing old code | coturn stays as fallback in docker-compose. |

## Estimated Scope

| Component | Lines Changed (est.) | New Dependencies |
|-----------|---------------------|------------------|
| Redis room state + Lua join script | ~400-500 | `go-redis/v9` |
| Redis pub/sub + ghost eviction protocol | ~200 | (same) |
| Redis push subscriptions (with ID counter) | ~150 | (same) |
| Redis snapshots | ~50 | (same) |
| Redis rate limiting | ~80 | (same) |
| Cloudflare TURN integration (server) | ~80 | None (HTTP call) |
| TURN wire format change (server) | ~40 | None |
| TURN wire format change (Web client) | ~30 | None |
| TURN wire format change (Android client) | ~30 | None |
| TURN wire format change (iOS client) | ~30 | None |
| Health check endpoint + instance ID | ~20 | None |
| **Total** | **~1100-1200** | **1 new Go dep** |

## Files Affected

### Server (`server/`)
- `main.go` — Redis init, healthz endpoint, instance ID
- `signaling.go` — Hub refactored: local client maps + Redis room state + pub/sub relay
- `ws.go` / `sse.go` — Minimal changes (local client management unchanged)
- `turn_auth.go` — New `iceServers` array format, Cloudflare TURN credential generation, coturn fallback
- `push.go` — Redis-backed subscriptions with auto-incrementing IDs, Redis snapshot storage
- `rate_limit.go` — Redis sliding window rate limiting
- New: `redis.go` — Redis client setup, connection pool, helpers
- New: `pubsub.go` — Redis pub/sub subscription management, message routing, ghost eviction protocol
- New: `lua/room_join.lua` — Atomic join script (capacity locking, ghost eviction, participant add)

### Client changes (TURN format only)
- `client/packages/core/src/media/MediaEngine.ts` — Parse `iceServers` array with fallback
- `client-android/serenada-core/.../TurnManager.kt` or `CoreApiClient.kt` — Parse `iceServers` array with fallback
- `client-ios/SerenadaCore/Sources/Networking/TurnCredentials.swift` — Parse `iceServers` array with fallback

### Docker / Infra
- `docker-compose.yml` — Add Redis service, keep coturn
- `docker-compose.prod.yml` — Add Redis, add sticky session config for nginx upstream
- `.env.example` — New vars: `REDIS_URL`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`

## Required Test Coverage Before Implementation

The following multi-node scenarios must have integration tests before treating the implementation as production-ready:

1. **SSE routing under sticky LB** — Verify SSE GET + POST pairs always land on the same backend; verify 410 behavior when stickiness breaks
2. **Concurrent joins** — Two clients joining the same room simultaneously must not oversubscribe; Lua script atomicity must be verified under contention
3. **Cross-node reconnect / ghost eviction** — Ghost on Server A, reconnect on Server B: verify ghost's WS/SSE connection is terminated, send channel closed, and relay blocked
4. **Snapshot recipient ID mapping** — Verify numeric IDs survive Redis migration and round-trip correctly through subscribe → recipients → upload → deliver
5. **TURN dual-provider fallback** — Verify all three clients correctly parse the new `iceServers` array, create separate ICE server entries per provider, and fall back to legacy format
6. **Redis failover** — Verify behavior during Redis Sentinel promotion (brief unavailability); signaling should degrade gracefully, not crash
