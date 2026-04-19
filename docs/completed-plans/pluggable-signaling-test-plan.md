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

## 2. Cross-Transport Interop ✅ Implemented

> `tools/integration-test/signaling.test.mjs`

One WS client + one SSE client in the same room. Tests both directions of message relay.

- [x] **Two-client signaling round-trip (WS + SSE)** — full offer/answer/leave cycle across transports
- [x] **ICE candidate relay (WS → SSE)** — WS client sends ICE to SSE client
- [x] **ICE candidate relay (SSE → WS)** — reverse direction
- [x] **Host end_room across transports** — WS host ends room, SSE client receives room_ended

## 3. Transport Fallback Under Failure ✅ Implemented

Client SDK tests that verify the SignalingClient/SignalingEngine WS→SSE fallback logic using injected `FakeSignalingTransport` instances. Added a `transportFactory` parameter to `SignalingClient` on iOS and Android to enable transport injection.

### Web (already existed)
> `client/packages/core/test/signaling/SignalingEngine.test.ts` — 11 tests

- [x] WS never connected → SSE fallback
- [x] WS drops with timeout → SSE
- [x] WS unsupported → SSE
- [x] Ping/pong heartbeat timeout + pong reset
- [x] Exponential backoff (capped at 5s)
- [x] Auto-rejoin on reconnect
- [x] Join hard timeout
- [x] Pending join buffered until open

### iOS ✅
> `client-ios/.../SignalingClientFallbackTests.swift` — 8 tests

- [x] WS never connected → SSE fallback
- [x] WS drops with timeout → SSE
- [x] WS unsupported → SSE
- [x] SSE connects successfully after WS fallback
- [x] No fallback with single transport (SSE-only)
- [x] Messages routed through active transport
- [x] Force-closes after missed pong threshold
- [x] Pong resets missed pong counter

### Android ✅
> `client-android/.../SignalingClientFallbackTest.kt` — 6 tests

- [x] WS never connected → SSE fallback
- [x] WS drops with timeout → SSE
- [x] WS unsupported → SSE
- [x] SSE connects successfully after WS fallback
- [x] No fallback with single transport (SSE-only)
- [x] Messages routed through active transport

**Note:** Ping/pong tests are on web and iOS. Android's `SignalingClient` uses `System.currentTimeMillis()` which Robolectric's ShadowLooper doesn't advance.

**Production changes:**
- Added `transportFactory` parameter (defaults to nil) to `SignalingClient` on both iOS and Android
- Refactored iOS `SignalingClient` to use `SessionClock` instead of `CFAbsoluteTimeGetCurrent()` / `Task.sleep` — enables time-controlled ping/pong tests via `FakeSessionClock`

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

## 6. Stress / Concurrency at the Server Level ✅ Implemented

> `tools/integration-test/signaling.test.mjs`

Set `MAX_ROOM_PARTICIPANTS=10` in `run.sh` to allow larger rooms.

- [x] **Rapid join/leave cycling** — 10 WS clients join one room sequentially, verify last joiner sees all 10 participants, all CIDs unique, then all leave
- [x] **Concurrent rooms with no cross-leak** — 20 rooms active simultaneously, each with 2 WS clients doing offer/answer with room-unique SDP, verify no cross-room message leakage
- [x] **Mixed WS+SSE load** — 4 clients (2 WS + 2 SSE) in one room, each sends directed offers to every other client, every client verifies it received offers from all 3 others

## 7. Provider Version Gating

Not yet implemented.

- **Version mismatch** — supply a provider with `version: 99`, verify the SDK rejects it at config time (not silently at runtime)
- **Version 1 accepted** — supply a provider with `version: 1`, verify normal operation

---

## Prioritization

| Priority | Area | Status | Effort | Value | Notes |
|----------|------|--------|--------|-------|-------|
| 1 | SSE parity (#1) | ✅ Done | Low | High | 9 tests in `signaling.test.mjs` |
| 2 | Cross-transport (#2) | ✅ Done | Low | High | 4 tests in `signaling.test.mjs` |
| 3 | Loopback provider (#4) | ✅ Done | Medium | High | 8 web + 7 iOS + 8 Android tests |
| 4 | Fallback (#3) | ✅ Done | Medium | Medium | 11 web + 8 iOS + 6 Android (client SDK tests) |
| 5 | Reconnection (#5) | Not started | Medium | Medium | Needs timing control |
| 6 | Stress (#6) | ✅ Done | Medium | Medium | 3 tests: 10-client room, 20 concurrent rooms, mixed transport |
| 7 | Version gating (#7) | Not started | Low | Low | Simple config validation |
