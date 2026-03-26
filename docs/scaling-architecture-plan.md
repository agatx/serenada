# Scaling Architecture Plan: Redis Pub/Sub + Cloudflare TURN

**Status:** Draft
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
                    └──────┬───┬──┘  └──┬───┬────────────┘
                           │   │        │   │
              ┌────────────▼───▼────────▼───▼─────────┐
              │            Redis (Sentinel)             │
              │                                        │
              │  Shared:                               │
              │   • Room state (hashes)                │
              │   • Cross-instance relay (pub/sub)     │
              │   • Watcher subscriptions (pub/sub)    │
              │   • Rate limit counters                │
              │   • Push subscriptions                 │
              │   • Snapshots (binary, 10min TTL)      │
              └────────────────────────────────────────┘

     Clients ◄────── WebRTC P2P media ──────► Clients
        │                                        │
        └──── STUN/TURN ──► Cloudflare TURNS ◄───┘
                             (global edge, managed)
```

### Key Design Decisions

- **No sticky sessions required.** Any server instance can handle any client for any room — Redis is the shared truth.
- **Redis Sentinel for HA.** Open-source, ships with Redis, zero licensing cost. 3 nodes (1 primary + 2 replicas).
- **Cloudflare TURN replaces self-hosted coturn.** Global edge, managed, pay-per-GB.
- **Single new Go dependency:** `go-redis/v9`.

## Layer 1: Signaling — What Stays Local vs. Moves to Redis

### Stays local (per-instance, not shared)

- `Client` struct (WS/SSE connection, send channel, transport goroutines)
- `clientsBySID` map — only the server holding the connection needs this
- SSE stale reaper — only evicts local connections

### Moves to Redis

| Current | Redis Structure | Key Pattern |
|---------|----------------|-------------|
| `Hub.rooms[rid]` | Hash | `room:{rid}` → `{hostCid, maxParticipants, capacityLocked, requestedMax}` |
| `Room.Participants` | Hash | `room:{rid}:participants` → `{cid: serverID\|joinedAt}` |
| `Hub.watchers` | Not stored — use pub/sub channel instead | — |
| Rate limit buckets | Sorted set + Lua script (sliding window) | `rl:{endpoint}:{ip}` |
| Push subscriptions | Hash | `push:sub:{roomId}` |
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
```

## Layer 2: How Key Operations Change

### Join flow

```
Current:
  1. Lock Hub.mu → create/find room in memory
  2. Lock Room.mu → check capacity, add participant
  3. Send "joined" to client
  4. Broadcast room_state to other local participants

New:
  1. MULTI/EXEC on Redis:
     - HSETNX room:{rid} to create if missing
     - HLEN room:{rid}:participants to check capacity
     - HSET room:{rid}:participants {cid} → {serverID}|{joinedAt}
  2. Send "joined" to local client
  3. PUBLISH room:{rid} → room_state message
     (all servers with clients in this room receive it and forward locally)
```

### Relay flow (offer/answer/ice)

```
Current:
  1. Find room in local Hub
  2. Iterate room.Participants, send to each local *Client

New:
  1. PUBLISH room:{rid} → the relay message (with "from" CID and optional "to" CID)
  2. Each server receiving the pub/sub message:
     - Checks if it has local clients matching the target
     - Delivers to local WS/SSE connections
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

### Reconnect (ghost eviction)

```
Current:
  1. Find ghost client in room.Participants by CID
  2. Remove ghost, reuse CID

New:
  1. HGET room:{rid}:participants {reconnectCID} → get owning serverID
  2. If owner is THIS server: evict ghost locally
  3. If owner is ANOTHER server: publish eviction request via
     "server:{serverID}" channel, wait for ack (or just overwrite
     the participant entry — the other server will detect the
     replacement on next pub/sub message)
```

## Layer 3: Cloudflare TURN

Cloudflare Calls includes a managed TURN service on their global edge network.

### Current flow (`turn_auth.go`)

1. Client joins room → server issues a `turnToken`
2. Client calls `GET /api/turn-credentials?token=...`
3. Server generates HMAC-SHA1 credentials for self-hosted coturn
4. Returns `stun:`, `turn:`, `turns:` URIs pointing to the coturn instance

### New flow with Cloudflare TURN

1. Client joins room → server issues a `turnToken` (unchanged)
2. Client calls `GET /api/turn-credentials?token=...`
3. Server calls Cloudflare API to generate short-lived credentials:
   ```
   POST https://rtc.live.cloudflare.com/v1/turn/keys/{key-id}/credentials/generate
   Authorization: Bearer {api-token}
   Body: { "ttl": 900 }
   ```
4. Cloudflare returns `{ "iceServers": { "urls": [...], "username": "...", "credential": "..." } }`
5. Server returns these to the client in the existing `TurnConfig` format

### What changes in `turn_auth.go`

- `handleTurnCredentials()` calls Cloudflare API instead of computing HMAC-SHA1 locally
- TURN URIs point to Cloudflare's edge (`turn.cloudflare.com`)
- `TURN_SECRET` / `TURN_HOST` / `STUN_HOST` env vars replaced by `CF_TURN_KEY_ID` + `CF_TURN_API_TOKEN`
- coturn container removed from docker-compose

### Fallback option

Keep self-hosted coturn as a secondary ICE server. Return both Cloudflare and coturn URIs — the client's ICE agent will try both.

### Cloudflare TURN pricing

$0.05/GB of relayed traffic. For a 1:1 video call where TURN is needed (~500kbps video), that's roughly $0.01/hour. Most calls don't need TURN at all (direct P2P works).

## Layer 4: Load Balancer + Deployment

### Load balancer requirements

- WebSocket upgrade support (for `/ws`)
- Long-lived HTTP connections (for `/sse`)
- Health check endpoint (add `GET /healthz` → 200 if Redis is reachable)
- No sticky sessions needed (any instance handles any room via Redis)

### Options (lightest to heaviest)

1. **Cloudflare Load Balancing** — if already using Cloudflare for DNS/CDN, this is zero-infra. Supports WS, health checks, geo-routing.
2. **Fly.io** — deploy the Go binary as a Fly app with `fly scale count 2`. Built-in LB, WS support, multi-region.
3. **nginx upstream** — `upstream serenada { server app1:8080; server app2:8080; }` — works with existing docker-compose, just add a second `app-server` service.

### Redis deployment

- **Managed (simplest):** Upstash Redis (serverless, free tier 10K cmds/day), or Redis Cloud free tier (30MB).
- **Self-hosted:** Redis Sentinel with 3 nodes (1 primary + 2 replicas). Lightweight — Redis uses ~10MB RAM for this workload.

## Migration Path (Phased)

| Phase | Scope | Risk | Details |
|-------|-------|------|---------|
| **Phase 0** | Add `/healthz` endpoint. Add Redis client dependency (`go-redis/v9`). Keep everything else unchanged. | None — additive | Prepare the codebase for Redis without changing behavior. |
| **Phase 1** | Move push subscriptions + snapshots to Redis. Remove SQLite dependency. Deploy 1 instance + Redis. | Low — push is non-critical path | Push subs become Redis hashes. Snapshots become Redis keys with TTL. SQLite + filesystem removed. |
| **Phase 2** | Move room state to Redis. Add pub/sub for cross-instance relay. Rate limiting to Redis. Deploy 2 instances. | Medium — core signaling changes | Hub becomes a thin local cache + Redis backend. This is the main effort. |
| **Phase 3** | Add Cloudflare TURN credential generation. Keep coturn as fallback. | Low — credential format unchanged | `handleTurnCredentials()` gains a Cloudflare code path. Both TURN providers returned in ICE servers. |
| **Phase 4** | Remove coturn. Remove SQLite. Clean up. | Low — just removing old code | Remove coturn from docker-compose, delete SQLite migration code, clean up env vars. |

## Estimated Scope

| Component | Lines Changed (est.) | New Dependencies |
|-----------|---------------------|------------------|
| Redis room state + pub/sub | ~300-400 | `go-redis/v9` |
| Redis push subscriptions | ~100 | (same) |
| Redis snapshots | ~50 | (same) |
| Redis rate limiting | ~80 | (same) |
| Cloudflare TURN integration | ~60 | None (HTTP call) |
| Health check endpoint | ~15 | None |
| Server instance ID + graceful shutdown | ~40 | None |
| **Total** | **~650-750** | **1 new dep** |

## Files Affected

### Server (`server/`)
- `main.go` — Redis init, healthz endpoint, instance ID
- `signaling.go` — Hub refactored to use Redis for room state + pub/sub
- `ws.go` / `sse.go` — Minimal changes (local client management unchanged)
- `turn_auth.go` — Cloudflare TURN credential generation
- `push.go` — Redis-backed subscriptions and snapshot storage
- `rate_limit.go` — Redis sliding window rate limiting
- New: `redis.go` — Redis client setup, connection pool, helpers
- New: `pubsub.go` — Redis pub/sub subscription management, message routing

### Docker / Infra
- `docker-compose.yml` — Add Redis service, optionally remove coturn
- `docker-compose.prod.yml` — Same
- `.env.example` — New vars: `REDIS_URL`, `CF_TURN_KEY_ID`, `CF_TURN_API_TOKEN`

### No client changes required
The signaling protocol is unchanged. TURN credential format is unchanged. Clients are unaware of the multi-instance topology.
