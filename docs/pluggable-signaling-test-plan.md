# Pluggable Signaling — Integration Test Plan

Ideas for integration tests that stress the pluggable signaling path introduced in PR #60.

---

## 1. SSE Transport Parity

The existing integration tests (`tools/integration-test/`) only use WebSocket. Mirror every test over SSE:

- **SSE signaling round-trip** — same offer/answer/ICE flow but via `GET /sse` + `POST /sse`
- **SSE keepalive** — connect, verify server sends `:keepalive` comments within ~12s, connection stays open
- **SSE session cleanup** — disconnect, reconnect with same SID, verify server returns 410 Gone on POST to stale SID

Add a `connectSSE()` helper alongside the existing `connectWS()` in `signaling.test.mjs` using `fetch()` with streaming body + EventSource-style line parsing.

## 2. Cross-Transport Interop

Client A on WebSocket, Client B on SSE, in the same room:

- **Offer/answer relay across transports** — A (WS) sends offer, B (SSE) receives it and sends answer back
- **ICE candidate relay across transports**
- **room_state broadcast consistency** — both clients see the same participant list and room_state updates regardless of transport

Most likely place for subtle bugs (e.g., message envelope differences between WS and SSE paths on the server).

## 3. Transport Fallback Under Failure

Use the server's `BLOCK_WEBSOCKET=block` or `BLOCK_WEBSOCKET=hang` env var:

- **WS blocked -> SSE fallback** — start server with `BLOCK_WEBSOCKET=block`, run a full signaling round-trip confirming it works over SSE
- **WS hang -> timeout -> SSE** — start server with `BLOCK_WEBSOCKET=hang`, verify client-side timeout triggers SSE fallback (requires a client-level test, not just the Node.js harness)

## 4. Custom SignalingProvider (End-to-End with SDK)

Write a minimal in-process SignalingProvider (no server) that routes messages between two local SDK sessions through an in-memory bus:

- **Web**: a `LoopbackSignalingProvider` in TypeScript that two `SerenadaSession` instances share
- **iOS**: same pattern using `FakeSignalingProvider` but wired to actually relay messages between two sessions
- **Android**: same in Kotlin

This validates the contract: if the provider emits the right events in the right order, the session state machine works correctly without ever touching the Serenada server.

Test flow:
1. Session A calls `joinRoom` -> provider emits `joined` to A
2. Provider emits `peerJoined` to A, `joined` to B
3. Sessions exchange offer/answer/ICE through the provider's relay
4. Verify both sessions reach `connected` call phase

## 5. Reconnection Semantics

- **Provider with `handlesReconnection: true`** — simulate disconnect + reconnect, verify session does NOT attempt its own reconnection logic (no duplicate joins)
- **Provider with `handlesReconnection: false`** — simulate disconnect, verify session drives reconnection (calls `connect()` again with backoff)
- **Reconnect with peer ID preservation** — disconnect client A, reconnect with `reconnectPeerId`, verify the peer slot is reused (not a new participant)

## 6. Stress / Concurrency at the Server Level

Extend the existing Node.js integration test harness:

- **Rapid join/leave cycling** — 10 clients join and leave a room in quick succession, verify room_state is always consistent and room cleans up
- **Concurrent rooms** — 20 rooms active simultaneously, each with 2 clients doing offer/answer, verify no cross-room message leakage
- **SSE + WS mixed load** — half the clients on WS, half on SSE, all in the same room, verify message ordering and completeness

## 7. Provider Version Gating

- **Version mismatch** — supply a provider with `version: 99`, verify the SDK rejects it at config time (not silently at runtime)
- **Version 1 accepted** — supply a provider with `version: 1`, verify normal operation

---

## Prioritization

| Priority | Area | Effort | Value | Notes |
|----------|------|--------|-------|-------|
| 1 | SSE parity (#1) | Low | High | Fits existing harness, just add `connectSSE()` |
| 2 | Cross-transport (#2) | Low | High | Same harness, mix WS + SSE clients |
| 3 | Loopback provider (#4) | Medium | High | Per-platform unit test, no server needed |
| 4 | Fallback (#3) | Medium | Medium | Needs server restart with env vars |
| 5 | Reconnection (#5) | Medium | Medium | Needs timing control |
| 6 | Stress (#6) | Medium | Medium | Concurrency edge cases |
| 7 | Version gating (#7) | Low | Low | Simple config validation |
