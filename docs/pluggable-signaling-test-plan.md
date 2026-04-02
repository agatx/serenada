# Pluggable Signaling — Integration Test Plan

Ideas for integration tests that stress the pluggable signaling path introduced in PR #60.

---

## 1. SSE Transport Parity ✅ Implemented

> `tools/integration-test/signaling.test.mjs`

Added a `connectSSE()` helper alongside the existing `connectWS()` using Node.js built-in `fetch()` streaming + SSE line parsing. All sends are awaited to ensure deterministic POST→response ordering.

**Mirrored tests (7):**
- [x] SSE signaling round-trip (offer/answer/leave)
- [x] ICE candidate relay
- [x] Room full error
- [x] Host end_room
- [x] Non-host end_room rejected
- [x] Invalid room ID rejected
- [x] Ping-pong

**SSE-specific tests (2):**
- [x] Server sends keepalive ping (waits 13s, asserts `: ping` received)
- [x] POST to unknown sid returns 410 Gone

## 2. Cross-Transport Interop

Not yet implemented. Client A on WebSocket, Client B on SSE, in the same room:

- **Offer/answer relay across transports** — A (WS) sends offer, B (SSE) receives it and sends answer back
- **ICE candidate relay across transports**
- **room_state broadcast consistency** — both clients see the same participant list and room_state updates regardless of transport

Most likely place for subtle bugs (e.g., message envelope differences between WS and SSE paths on the server). Low effort to add — both `connectWS()` and `connectSSE()` share the same interface.

## 3. Transport Fallback Under Failure

Not yet implemented. Use the server's `BLOCK_WEBSOCKET=block` or `BLOCK_WEBSOCKET=hang` env var:

- **WS blocked -> SSE fallback** — start server with `BLOCK_WEBSOCKET=block`, run a full signaling round-trip confirming it works over SSE
- **WS hang -> timeout -> SSE** — start server with `BLOCK_WEBSOCKET=hang`, verify client-side timeout triggers SSE fallback (requires a client-level test, not just the Node.js harness)

## 4. Custom SignalingProvider (End-to-End with SDK) ✅ Implemented

In-memory `LoopbackSignalingProvider` + `LoopbackRoom` on all three platforms. Two sessions share a room, provider routes messages between them without any server.

### Web ✅
> `client/packages/core/test/LoopbackSignalingProvider.test.ts` — 8 tests

- [x] Session joins alone → waiting phase
- [x] Two sessions join → both inCall
- [x] sendToPeer routes offer to remote session's MediaEngine
- [x] broadcast routes content_state to remote session (not sender)
- [x] Peer leaving → remaining session back to waiting
- [x] endRoom → both sessions reach ending phase
- [x] Custom messages via onPeerMessage reach the other session
- [x] MediaEngine receives room state updates for each join

### iOS ✅
> `client-ios/.../LoopbackSignalingProviderTests.swift` — 7 tests

- [x] Session joins alone → waiting phase
- [x] Two sessions join → both inCall
- [x] sendToPeer delivers offer to remote FakePeerConnectionSlot
- [x] broadcast routes content_state (verified via diagnostics.remoteContentParticipantId)
- [x] Peer leaving → remaining session back to waiting
- [x] endRoom → both sessions reach ending phase
- [x] MediaEngine creates slot for remote participant

### Android ✅
> `client-android/.../LoopbackSignalingProviderTest.kt` — 8 tests

- [x] Session joins alone → Waiting phase
- [x] Two sessions join → both InCall
- [x] sendToPeer delivers offer to remote FakePeerConnectionSlot
- [x] broadcast routes content_state (verified via diagnostics.remoteContentCid)
- [x] Peer leaving → remaining session back to Waiting
- [x] endRoom → both sessions leave InCall (ending/idle)
- [x] MediaEngine creates slot for remote participant
- [x] Pre-endRoom assertion that both sessions are InCall

**Platform differences:**
- iOS/Android auto-negotiate offers between sessions (real PeerNegotiationEngine), so sendToPeer tests verify slot received the offer rather than asserting the sender didn't.
- iOS needs extra `settle()` yields (20x `Task.yield()`) to drain the `Task { @MainActor }` delegate proxy chain.
- Android's ending phase is transient — `ShadowLooper.idleMainLooper()` drains the ending timer, so the test asserts sessions left InCall rather than checking for Ending specifically.

## 5. Reconnection Semantics

Not yet implemented.

- **Provider with `handlesReconnection: true`** — simulate disconnect + reconnect, verify session does NOT attempt its own reconnection logic (no duplicate joins)
- **Provider with `handlesReconnection: false`** — simulate disconnect, verify session drives reconnection (calls `connect()` again with backoff)
- **Reconnect with peer ID preservation** — disconnect client A, reconnect with `reconnectPeerId`, verify the peer slot is reused (not a new participant)

## 6. Stress / Concurrency at the Server Level

Not yet implemented. Extend the existing Node.js integration test harness:

- **Rapid join/leave cycling** — 10 clients join and leave a room in quick succession, verify room_state is always consistent and room cleans up
- **Concurrent rooms** — 20 rooms active simultaneously, each with 2 clients doing offer/answer, verify no cross-room message leakage
- **SSE + WS mixed load** — half the clients on WS, half on SSE, all in the same room, verify message ordering and completeness

## 7. Provider Version Gating

Not yet implemented.

- **Version mismatch** — supply a provider with `version: 99`, verify the SDK rejects it at config time (not silently at runtime)
- **Version 1 accepted** — supply a provider with `version: 1`, verify normal operation

---

## Prioritization

| Priority | Area | Status | Effort | Value | Notes |
|----------|------|--------|--------|-------|-------|
| 1 | SSE parity (#1) | ✅ Done | Low | High | 9 tests in `signaling.test.mjs` |
| 2 | Cross-transport (#2) | Not started | Low | High | Same harness, mix WS + SSE clients |
| 3 | Loopback provider (#4) | ✅ Done | Medium | High | 8 web + 7 iOS + 8 Android tests |
| 4 | Fallback (#3) | Not started | Medium | Medium | Needs server restart with env vars |
| 5 | Reconnection (#5) | Not started | Medium | Medium | Needs timing control |
| 6 | Stress (#6) | Not started | Medium | Medium | Concurrency edge cases |
| 7 | Version gating (#7) | Not started | Low | Low | Simple config validation |
