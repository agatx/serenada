# Serenada SDK Remediation Plan

**Created:** 2026-03-22
**Source:** [SDK Architecture Review](./SDK_ARCHITECTURE_REVIEW.md)
**Scope:** 12 priority recommendations across 3 stages

---

## Stage 1 — P0: Before SDK Distribution

These items gate beta distribution. They address the most critical gaps: missing web tests, inconsistent error types, and untyped participant state.

---

### 1. Add Web Session Contract Tests

**Why:** Android has `SerenadaSessionContractTest` (1045 lines) and iOS has `SessionOrchestrationTests` + `SessionNegotiationTests` — both with full fake infrastructure (`TestSessionFactory`/`SessionTestHarness`). The web SDK has 6 test files covering only utilities (~20% coverage). The critical paths — `SignalingEngine`, `MediaEngine`, and `SerenadaSession` state transitions — are completely untested.

**Approach:** Port the Android/iOS test harness pattern to TypeScript. Create a `TestSessionHarness` that injects `FakeSignalingEngine` and `FakeMediaEngine` into `SerenadaSession`, then write contract tests mirroring the Android suite.

**Files to create:**
- `client/packages/core/test/helpers/TestSessionHarness.ts`
- `client/packages/core/test/helpers/FakeSignalingEngine.ts`
- `client/packages/core/test/helpers/FakeMediaEngine.ts`
- `client/packages/core/test/helpers/FakeClock.ts`
- `client/packages/core/test/SerenadaSession.test.ts`
- `client/packages/core/test/signaling/SignalingEngine.test.ts`

**Files to reference:**
- Android harness: `client-android/serenada-core/src/test/java/app/serenada/core/fakes/TestSessionFactory.kt`
- iOS harness: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/Helpers/SessionTestHarness.swift`
- Android contract tests: `client-android/serenada-core/src/test/java/app/serenada/core/SerenadaSessionContractTest.kt`
- iOS orchestration tests: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SessionOrchestrationTests.swift`

#### 1.1 Build Web Test Harness

- [ ] **1.1.1** Create `FakeSignalingEngine` implementing the same interface as `SignalingEngine`. Must support: `simulateOpen(transport)`, `simulateMessage(msg)`, `simulateClosed(reason)`, tracking of sent messages by type, and configurable connection state.
  - Reference: `FakeSignaling.swift` (iOS) / `FakeSignaling.kt` (Android) — both track `sentMessages[type]` and expose `onOpen`/`onMessage`/`onClosed` triggers.

- [ ] **1.1.2** Create `FakeMediaEngine` implementing the same interface as `MediaEngine`. Must support: `createPeer(remoteCid)` returning a `FakePeerState`, tracking of `startLocalMedia`/`flipCamera`/`toggleAudio`/`toggleVideo` calls, and configurable ICE/connection states.
  - Reference: `FakeMediaEngine.swift` (iOS) / `FakeMediaEngine.kt` (Android) — both create `FakePeerConnectionSlot` instances and track method invocations.

- [ ] **1.1.3** Create `TestSessionHarness` that constructs a `SerenadaSession` with fakes injected. Provide helper methods: `simulateJoinedResponse(opts)`, `simulateRoomState(participants)`, `simulateError(code, message)`, `simulateOfferFromRemote(cid, sdp)`, `simulateAnswerFromRemote(cid, sdp)`, `advanceToInCall()`.
  - Reference: `TestSessionFactory.kt` (Android) has `advanceToInCallWithTurn()` that chains join→joined→roomState→offer→answer. `SessionTestHarness.swift` (iOS) has `advancePastPermissions()` and `advanceToInCallWithTurn()`.
  - **Key design decision:** `SerenadaSession` currently creates `SignalingEngine` and `MediaEngine` internally. The harness needs a way to inject fakes. Options: (a) add an internal constructor that accepts engines, (b) use a factory/provider pattern. Android/iOS both use option (a) — an internal init that accepts protocol-typed dependencies. Do the same for web.

- [ ] **1.1.4** Create `FakeClock` for deterministic timeout testing. Must support `advance(ms)` to trigger scheduled callbacks without real delays.
  - Reference: `FakeSessionClock.swift` (iOS) / `FakeSessionClock.kt` (Android) — both provide injectable time sources with `.advance(byMs:)`.

- [ ] **1.1.5** Add an internal constructor to `SerenadaSession.ts` (or a `createForTesting` factory) that accepts `SignalingEngine` and `MediaEngine` instances instead of creating them. Keep the public constructor unchanged. Mark the internal constructor with a `@internal` JSDoc tag.
  - Reference: `SerenadaSession.ts` constructor (lines 27-82) currently creates engines inline. Add an overload or static factory.

#### 1.2 Session State Machine Tests

Port from Android `SerenadaSessionContractTest.kt` and iOS `SessionOrchestrationTests.swift`.

- [ ] **1.2.1** Test join flow: construct session → verify phase is `joining` → simulate `joined` message → verify phase transitions to `waiting` (no remote participants) or `inCall` (with remote participant).

- [ ] **1.2.2** Test permission gating: construct session → simulate `joined` with media capabilities → verify phase transitions to `awaitingPermissions` → call `resumeJoin()` → verify phase progresses. Also test `cancelJoin()` returns to `idle`.

- [ ] **1.2.3** Test room state updates: start in `waiting` → simulate `room_state` with a remote participant → verify transition to `inCall` with correct `remoteParticipants` list. Then simulate participant leaving → verify return to `waiting`.

- [ ] **1.2.4** Test error handling: simulate signaling error message → verify phase transitions to `error` with correct `CallError`. Test specific error codes: `ROOM_FULL`, `room_ended`, generic server error.

- [ ] **1.2.5** Test leave/end: in `inCall` → call `leave()` → verify signaling sends `leave` message → verify phase transitions through `ending` to `idle`. Test `end()` sends `end_room`.

- [ ] **1.2.6** Test reconnect on close: in `inCall` → simulate signaling closed → verify automatic reconnect attempt (signaling `connect()` called again). Verify `connectionStatus` transitions: `connected` → `recovering` → `connected` (on successful reconnect) or `retrying` (on continued failure).

- [ ] **1.2.7** Test join timeout: start join → do NOT simulate `joined` response → advance clock past `JOIN_HARD_TIMEOUT_MS` (15000) → verify phase transitions to `error` with timeout error.

#### 1.3 Signaling Engine Tests

- [ ] **1.3.1** Test transport fallback: create `SignalingEngine` with WS transport → simulate WS connection failure 3 times → verify fallback to SSE transport. Verify `activeTransport` changes.

- [ ] **1.3.2** Test ping/pong heartbeat: connect → advance clock past `PING_INTERVAL_MS` → verify ping sent → simulate pong → verify connection maintained. Then advance without pong `PONG_MISS_THRESHOLD` times → verify reconnect triggered.

- [ ] **1.3.3** Test exponential backoff: simulate repeated connection failures → verify reconnect delays follow exponential backoff (`RECONNECT_BACKOFF_BASE_MS` = 500, doubling, capped at `RECONNECT_BACKOFF_CAP_MS` = 5000).

- [ ] **1.3.4** Test auto-rejoin: connected + joined room → simulate transport closed → simulate transport reconnects → verify `join` message sent automatically for the active room.

#### 1.4 Media Control Tests

- [ ] **1.4.1** Test audio/video toggle: verify `toggleAudio()` and `toggleVideo()` propagate to `MediaEngine` and update `CallState.localParticipant`.

- [ ] **1.4.2** Test camera mode: verify `flipCamera()` calls media engine and state reflects the new camera mode.

- [ ] **1.4.3** Test TURN credential flow: simulate `joined` with `turnToken` → verify TURN fetch initiated → simulate credentials received → verify ICE servers applied to media engine.

- [ ] **1.4.4** Test offer negotiation timeout: host creates offer → advance clock past `OFFER_TIMEOUT_MS` (8000) → verify ICE restart triggered.

---

### 2. Unify Error Types

**Why:** iOS has `CallError` enum (`.signalingTimeout`, `.connectionFailed`, `.roomFull`, `.serverError(String)`, `.unknown(String)`). Android uses `errorMessage: String?`. Web uses `CallError { code: string, message: string }` with no enumerated codes. Third-party developers can't programmatically distinguish error types on Android or web.

**Approach:** Define a canonical set of error codes matching iOS, implement on all platforms. iOS is already correct — only Android and web need changes.

**Canonical error codes:**
- `signalingTimeout` — join timed out (JOIN_HARD_TIMEOUT_MS exceeded)
- `connectionFailed` — WebRTC connection could not be established
- `roomFull` — server returned ROOM_FULL
- `roomEnded` — remote host ended the room
- `permissionDenied` — camera/mic permission refused
- `serverError` — server returned an unrecognized error (with message)
- `unknown` — catch-all (with message)

#### 2.1 Web Error Types

- [ ] **2.1.1** Replace the `CallError` interface in `client/packages/core/src/types.ts` with a discriminated union or an interface with enumerated `code` field:
  ```typescript
  export type CallErrorCode =
    | 'signalingTimeout'
    | 'connectionFailed'
    | 'roomFull'
    | 'roomEnded'
    | 'permissionDenied'
    | 'serverError'
    | 'unknown';

  export interface CallError {
    code: CallErrorCode;
    message: string;
  }
  ```

- [ ] **2.1.2** Update `SerenadaSession.ts` `rebuildState()` to use typed error codes when constructing `CallError` objects. Map signaling error codes (e.g., `ROOM_FULL` from server) to `CallErrorCode` values.

- [ ] **2.1.3** Update `SignalingEngine.ts` error handling to produce typed error codes instead of raw strings. Map server error `code` field to `CallErrorCode`.

- [ ] **2.1.4** Export `CallErrorCode` from `client/packages/core/src/index.ts`.

#### 2.2 Android Error Types

- [ ] **2.2.1** Create `CallError.kt` in `client-android/serenada-core/src/main/java/app/serenada/core/` with a sealed class (or enum) matching iOS:
  ```kotlin
  sealed class CallError {
      object SignalingTimeout : CallError()
      object ConnectionFailed : CallError()
      object RoomFull : CallError()
      object RoomEnded : CallError()
      object PermissionDenied : CallError()
      data class ServerError(val message: String) : CallError()
      data class Unknown(val message: String) : CallError()
  }
  ```

- [ ] **2.2.2** Replace `errorMessage: String?` in `CallState.kt` with `error: CallError? = null`. This is a breaking change to the Android SDK public API — acceptable at v0.1.0.

- [ ] **2.2.3** Update `SerenadaSession.kt` `handleError()` (line ~777) to construct `CallError` instances instead of extracting `errorMessage` strings. Map server error codes to sealed class variants.

- [ ] **2.2.4** Update `SerenadaCoreDelegate.kt` `onSessionEnded` to include `CallError` in the `ERROR` end reason, or change `EndReason` to carry the error:
  ```kotlin
  sealed class EndReason {
      object LocalLeft : EndReason()
      object RemoteEnded : EndReason()
      data class Error(val error: CallError) : EndReason()
  }
  ```

- [ ] **2.2.5** Update Android contract tests (`SerenadaSessionContractTest.kt`) to assert on `CallError` types instead of `errorMessage` strings.

- [ ] **2.2.6** Update `serenada-call-ui` and host app (`CallManager.kt`) to handle the new `CallError` type.

#### 2.3 Verify iOS Error Types

- [ ] **2.3.1** Verify iOS `CallError` enum covers all canonical codes. Currently missing: `roomEnded`, `permissionDenied`. Add if absent:
  ```swift
  case roomEnded
  case permissionDenied
  ```

- [ ] **2.3.2** Verify iOS `SerenadaSession.swift` `handleError()` (line ~672) maps all server error codes to `CallError` cases.

---

### 3. Type `RemoteParticipant.connectionState`

**Why:** All three platforms define `connectionState` as a raw `String`. These map to well-defined WebRTC `RTCPeerConnectionState` values (`new`, `connecting`, `connected`, `disconnected`, `failed`, `closed`). Using typed enums prevents typos and enables exhaustive switch handling.

#### 3.1 Web

- [ ] **3.1.1** Add `PeerConnectionState` type to `client/packages/core/src/types.ts`:
  ```typescript
  export type PeerConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';
  ```

- [ ] **3.1.2** Update `Participant` interface: change `connectionState: string` → `connectionState: PeerConnectionState`.

- [ ] **3.1.3** Update `MediaEngine.ts` peer state tracking to use the typed enum when setting connection state.

- [ ] **3.1.4** Export `PeerConnectionState` from `client/packages/core/src/index.ts`.

#### 3.2 Android

- [ ] **3.2.1** Create `PeerConnectionState` enum in `client-android/serenada-core/src/main/java/app/serenada/core/`:
  ```kotlin
  enum class PeerConnectionState(val value: String) {
      NEW("new"), CONNECTING("connecting"), CONNECTED("connected"),
      DISCONNECTED("disconnected"), FAILED("failed"), CLOSED("closed");
  }
  ```

- [ ] **3.2.2** Update `RemoteParticipant` data class: change `connectionState: String` → `connectionState: PeerConnectionState`.

- [ ] **3.2.3** Update `SerenadaSession.kt` `refreshRemoteParticipants()` to map the raw string from `PeerConnectionSlot` to the enum.

#### 3.3 iOS

- [ ] **3.3.1** Create `SerenadaPeerConnectionState` enum in `client-ios/SerenadaCore/Sources/Models/`:
  ```swift
  public enum SerenadaPeerConnectionState: String, Codable, Equatable {
      case new, connecting, connected, disconnected, failed, closed
  }
  ```

- [ ] **3.3.2** Update `SerenadaRemoteParticipant`: change `connectionState: String` → `connectionState: SerenadaPeerConnectionState`.

- [ ] **3.3.3** Update `SerenadaSession.swift` `refreshRemoteParticipants()` to map the raw string from `PeerConnectionSlot` to the enum.

---

## Stage 2 — P1: Before v1.0

These items improve code quality, maintainability, and prepare the SDK for stable release. They don't block beta but should be completed before v1.0.

---

### 4. Extract Session Sub-Engines

**Why:** iOS `SerenadaSession.swift` is 1,172 lines with 52 methods. Android `SerenadaSession.kt` is 1,045 lines with 74 methods. Both are "God Objects" handling state machine transitions, signaling dispatch, room management, media control, stats, and cleanup. The web session is only 322 lines because it delegates heavily to `SignalingEngine` and `MediaEngine`.

**Current state:** `PeerNegotiationEngine` is already extracted on both iOS and Android. iOS has additionally extracted `JoinTimer`, `TurnManager`, `ConnectionStatusTracker`, `StatsPoller`, and `CallAudioSessionController`. The remaining monolithic responsibilities are: signaling message dispatch, room state management, join flow orchestration, and cleanup lifecycle.

**Approach:** Extract two additional engines from the session on both iOS and Android, following the existing closure-injection pattern used by `PeerNegotiationEngine`.

#### 4.1 Extract `SignalingMessageRouter`

Extracts signaling message dispatch from `SerenadaSession`. The session currently has a `handleSignalingMessage` switch/when block that fans out to `handleJoined`, `handleRoomState`, `handleError`, `handleContentState`, `handleSignalingPayload`, `handleTurnRefreshed`. Move this routing + the individual handlers into a dedicated class.

- [ ] **4.1.1** **iOS:** Create `SignalingMessageRouter.swift` in `SerenadaCore/Sources/Call/`. Move methods: `handleSignalingMessage` (line 594), `handleJoined` (617), `handleRoomState` (648), `handleError` (672), `handleContentState` (697), `handleTurnRefreshed` (777), `parseRoomState` (794), `turnToken` (664). Router receives closures for state mutations (e.g., `onJoined(clientId, hostCid, roomState, turnToken)`, `onRoomStateUpdated(roomState)`, `onError(CallError)`, `onContentState(remoteCid, contentType)`).

- [ ] **4.1.2** **Android:** Create `SignalingMessageRouter.kt` in `serenada-core/src/main/java/app/serenada/core/call/`. Move equivalent methods: `handleSignalingMessage` (line 699), `handleJoined` (713), `handleRoomState` (740), `handleError` (777), `handleContentState` (755), `handleTurnRefreshed` (871), `parseRoomState` (882). Same closure-injection pattern.

- [ ] **4.1.3** **Both platforms:** Update `SerenadaSession` to delegate `onMessage` to `SignalingMessageRouter.processMessage(msg)` instead of the inline switch. Session still owns state — router calls back via closures.

- [ ] **4.1.4** **Both platforms:** Move existing signaling handler tests to target the new `SignalingMessageRouter` directly. Add unit tests for message routing (unknown type → ignored, malformed payload → logged not crashed).

#### 4.2 Extract `JoinFlowCoordinator`

Extracts join flow orchestration: permission checks, join message sending, timeout scheduling, recovery, and kickstart logic.

- [ ] **4.2.1** **iOS:** Create `JoinFlowCoordinator.swift`. Move methods: `beginJoinIfNeeded` (line 413), `missingPermissions` (463), `ensureSignalingConnection` (535), `sendJoin` (549), `scheduleJoinTimeout` (752), `scheduleJoinConnectKickstart` (760), `scheduleJoinRecovery` (999), `recoverFromJoiningIfNeeded` (1012), `failJoinWithError` (768), `prepareMediaAndConnect` (953). Coordinator receives closures for signaling (`connect`, `sendMessage`), media (`startLocalMedia`), and state (`setPhase`, `setError`). Absorbs `JoinTimer.swift` functionality (already extracted, 4 KB).

- [ ] **4.2.2** **Android:** Create `JoinFlowCoordinator.kt`. Move equivalent methods: `startJoinInternal` (line 526), `startWithPermissionCheck` (568), `ensureSignalingConnection` (647), `sendJoin` (658), `scheduleJoinTimeout` (845), `scheduleJoinKickstart` (853), `scheduleJoinRecovery` (861), timeout handlers. Same closure-injection pattern.

- [ ] **4.2.3** **Both platforms:** Update `SerenadaSession` to delegate join flow to `JoinFlowCoordinator`. Session calls `coordinator.beginJoin()` and receives callbacks for state transitions.

- [ ] **4.2.4** **Both platforms:** Write unit tests for `JoinFlowCoordinator` with `FakeClock`: verify timeout firing, recovery scheduling, permission gating, and kickstart behavior.

#### 4.3 Verify Post-Extraction

- [ ] **4.3.1** Verify iOS `SerenadaSession.swift` is under 600 lines after extraction.
- [ ] **4.3.2** Verify Android `SerenadaSession.kt` is under 600 lines after extraction.
- [ ] **4.3.3** Run full test suites on both platforms — all existing tests must pass unchanged.
- [ ] **4.3.4** Run cross-platform smoke test (`tools/smoke-test/smoke-test.sh`) to verify no behavioral regression.

---

### 5. Add Typed Signaling Message Parsing

**Why:** All three clients parse signaling messages with manual JSON field extraction (`payload?.optString("sdp")`, `payload?["sdp"] as? String`, `msg.payload?.sdp as string`). No schema validation. A malformed server message causes silent failures or crashes depending on platform.

**Approach:** Define typed message payloads for each `type` on each platform. Parse at the transport boundary — if parsing fails, log and drop the message rather than propagating untyped data.

#### 5.1 Web — Zod or Manual Discriminated Unions

- [ ] **5.1.1** Define typed payload interfaces in `client/packages/core/src/signaling/types.ts` for each message type:
  ```typescript
  interface JoinedPayload {
    hostCid: string;
    participants: Array<{ cid: string; joinedAt: number }>;
    turnToken?: string;
    turnTokenTTLMs?: number;
    reconnectToken?: string;
  }
  interface OfferPayload { from: string; sdp: string; timestamp?: number; }
  interface AnswerPayload { from: string; sdp: string; }
  interface IceCandidatePayload { from: string; candidates: Array<{ candidate: string; sdpMid: string; sdpMLineIndex: number }>; }
  interface ErrorPayload { code: string; message: string; }
  interface RoomStatePayload { hostCid: string; participants: Array<{ cid: string; joinedAt: number }>; }
  interface ContentStatePayload { from: string; active: boolean; contentType?: string; }
  ```

- [ ] **5.1.2** Create type-safe accessor functions (e.g., `parseJoinedPayload(raw: unknown): JoinedPayload | null`) that validate required fields and return null on malformed input. No new dependencies — manual validation is sufficient for 7 message types.

- [ ] **5.1.3** Update `SignalingEngine.ts` message handlers to use typed accessors instead of raw casts. Replace `msg.payload.turnToken as string` with `parseJoinedPayload(msg.payload)?.turnToken`.

- [ ] **5.1.4** Add unit tests for each payload parser with valid, missing-field, and wrong-type inputs.

#### 5.2 Android — Typed Payload Parsing

- [ ] **5.2.1** Create `SignalingPayloads.kt` with data classes for each message type payload. Use `JSONObject` extension functions for safe extraction:
  ```kotlin
  data class JoinedPayload(val hostCid: String, val participants: List<ParticipantInfo>, val turnToken: String?, ...)
  fun JSONObject.toJoinedPayload(): JoinedPayload? { ... }
  ```

- [ ] **5.2.2** Update `SignalingMessageRouter` (or current handlers in `SerenadaSession.kt`) to parse payloads via typed extractors before processing.

- [ ] **5.2.3** Add unit tests for payload parsing with valid and malformed JSON inputs.

#### 5.3 iOS — Typed Payload Parsing

- [ ] **5.3.1** Create `SignalingPayloads.swift` with structs conforming to `Decodable` for each message type. iOS already uses `Codable` for `SignalingMessage` — extend this to payloads:
  ```swift
  struct JoinedPayload: Decodable {
      let hostCid: String
      let participants: [ParticipantInfo]
      let turnToken: String?
      // ...
  }
  ```

- [ ] **5.3.2** Update message handlers to decode payloads via `JSONDecoder` from the `JSONValue` payload. On decode failure, log warning and skip message.

- [ ] **5.3.3** Add unit tests for payload decoding with valid and malformed inputs.

---

### 6. Move `@serenada/core` to Peer Dependency

**Why:** `@serenada/react-ui` declares `@serenada/core` as a regular `dependency`. If a host app also depends on `@serenada/core` directly (to create sessions before rendering), npm may install duplicate copies, causing `instanceof` checks and `useSyncExternalStore` subscriptions to fail silently.

- [ ] **6.1** In `client/packages/react-ui/package.json`, move `@serenada/core` from `dependencies` to `peerDependencies`:
  ```json
  "peerDependencies": {
    "@serenada/core": "^0.1.0",
    "react": "^18.0.0 || ^19.0.0",
    "react-dom": "^18.0.0 || ^19.0.0",
    "lucide-react": ">=0.300.0"
  }
  ```

- [ ] **6.2** Add `@serenada/core` to `devDependencies` so the package still builds in isolation:
  ```json
  "devDependencies": {
    "@serenada/core": "workspace:*"
  }
  ```

- [ ] **6.3** Update the web sample app (`samples/web/package.json`) to explicitly depend on both `@serenada/core` and `@serenada/react-ui`.

- [ ] **6.4** Verify `npm run build` still works for `@serenada/react-ui` in the monorepo.

- [ ] **6.5** Verify the app shell (`client/src/`) still builds and runs with `npm run dev`.

---

### 7. Define Versioning Policy

**Why:** All SDKs are at `0.1.0` with no CHANGELOG, migration guide, or stability guarantees. Third-party consumers need to know what's stable, what's experimental, and how breaking changes are communicated.

- [ ] **7.1** Create `VERSIONING.md` at the repo root documenting:
  - **Semantic versioning:** `MAJOR.MINOR.PATCH` per semver.org
  - **Pre-1.0 policy:** Minor bumps may include breaking changes; patch bumps are backward-compatible
  - **Post-1.0 policy:** Major bumps for breaking changes; minor for new features; patch for fixes
  - **Version synchronization:** All three SDKs (web, Android, iOS) share the same version number and are released together
  - **Breaking change definition:** Removal/rename of public types, methods, or properties; behavioral changes to state machine transitions; signaling protocol version bump

- [ ] **7.2** Create `CHANGELOG.md` at the repo root with an initial `0.1.0` entry documenting current SDK capabilities.

- [ ] **7.3** Add API stability annotations to public types:
  - **Web:** JSDoc `@public` / `@beta` tags on exports in `index.ts`
  - **Android:** `@PublicApi` / `@ExperimentalSerenadaApi` annotations
  - **iOS:** Mark experimental APIs with `@available(*, message: "Experimental API")` or doc comments

- [ ] **7.4** Add a version consistency check script (similar to `check-resilience-constants.mjs`) that verifies the version string matches across `package.json` (web core + react-ui), `build.gradle.kts` (Android core + call-ui), and `Package.swift` / `SerenadaConfig` (iOS).

---

## Stage 3 — P2: Quality of Life

These items improve developer experience, robustness, and polish. They can be addressed incrementally after v1.0.

---

### 8. Add Integration Test Harness

**Why:** Unit tests use fakes for speed and determinism, but they can't verify real client-server interaction. The existing smoke test (`tools/smoke-test/smoke-test.sh`) requires physical devices and full Docker. There's no middle layer that tests signaling round-trips against a real server.

**Approach:** Create a lightweight integration test that spins up the Go server in-process (or via Docker), connects two web SDK instances, and verifies the join → offer → answer → connected flow.

- [ ] **8.1** Create `tools/integration-test/` directory with a Node.js test runner.

- [ ] **8.2** Write a server bootstrap script that starts the Go server on a random port with test-mode environment variables (`ROOM_ID_SECRET`, `TURN_SECRET`, `ALLOWED_ORIGINS=*`).

- [ ] **8.3** Write a two-client signaling test:
  - Client A creates a room via `POST /api/room-id`
  - Client A connects via WebSocket, sends `join`
  - Client B connects via WebSocket, sends `join` with same `rid`
  - Verify both receive `joined` with correct participant lists
  - Client A sends `offer` → verify Client B receives it
  - Client B sends `answer` → verify Client A receives it
  - Client A sends `leave` → verify Client B receives `room_state` update

- [ ] **8.4** Write a transport fallback test:
  - Start server with `BLOCK_WEBSOCKET=block`
  - Client connects → verify automatic SSE fallback
  - Verify join flow succeeds over SSE

- [ ] **8.5** Write a reconnect test:
  - Client joins room → server restarts → verify client auto-reconnects and re-joins

- [ ] **8.6** Add to CI pipeline as a separate job (requires Go + Node.js).

---

### 9. Expose `activeTransport` on Android/iOS

**Why:** The web `CallState` exposes `activeTransport: TransportKind | null`, letting the UI show which transport is active. Android/iOS track this internally in `CallDiagnostics` but not in `CallState`. For diagnostic UIs and debugging, this should be consistently accessible.

**Approach:** Rather than adding to `CallState` (which is the public-facing state), document that `activeTransport` is available in `CallDiagnostics` on all platforms, and ensure parity there.

- [ ] **9.1** Verify Android `CallDiagnostics` includes `activeTransport: String?` (should be `"ws"` or `"sse"`). Add if missing.

- [ ] **9.2** Verify iOS `CallDiagnostics` includes `activeTransport: String?`. Add if missing.

- [ ] **9.3** Add `activeTransport` to web `CallDiagnostics` (if a diagnostics type exists) in addition to `CallState`, for consistency.

- [ ] **9.4** Document in sample apps that `session.diagnostics` (Android/iOS) or `session.state.activeTransport` (web) provides transport visibility.

---

### 10. Add WebRTC Capability Detection

**Why:** The web SDK assumes `RTCPeerConnection` exists. On environments without WebRTC (older browsers, restricted WebViews), the SDK throws deep inside `MediaEngine` with no clear error. `SerenadaDiagnostics.probeIceServers()` (line ~333) already checks `typeof RTCPeerConnection === 'undefined'`, but the main join flow doesn't.

- [ ] **10.1** Add a capability check at the top of `SerenadaSession` construction (or in `SerenadaCore.join()`):
  ```typescript
  if (typeof RTCPeerConnection === 'undefined') {
    // Immediately set error state instead of attempting to join
    this.error = { code: 'webrtcUnavailable', message: 'WebRTC is not supported in this browser' };
    this.phase = 'error';
    return;
  }
  ```

- [ ] **10.2** Add `'webrtcUnavailable'` to `CallErrorCode` type (from item 2.1.1).

- [ ] **10.3** Add a static utility `SerenadaCore.isSupported(): boolean` that checks for WebRTC availability without creating a session:
  ```typescript
  static isSupported(): boolean {
    return typeof RTCPeerConnection !== 'undefined'
      && typeof navigator?.mediaDevices?.getUserMedia === 'function';
  }
  ```

- [ ] **10.4** Document the check in the web sample app README.

---

### 11. Improve CSS Isolation in Web React UI

**Why:** `@serenada/react-ui` injects ~390 lines of CSS into `<head>` at runtime via `callFlowStyles.ts`. This causes specificity conflicts with host apps, prevents SSR, and has no tree-shaking.

**Approach:** Scope all styles under a unique data attribute to minimize conflicts. This is a low-risk incremental improvement over the current approach without requiring a build tool change.

- [ ] **11.1** Add a `data-serenada-callflow` attribute to the root element in `SerenadaCallFlow.tsx`.

- [ ] **11.2** Update all CSS selectors in `callFlowStyles.ts` to be scoped under `[data-serenada-callflow]`:
  ```css
  /* Before */
  .serenada-callflow { ... }
  .serenada-callflow .video-container { ... }

  /* After */
  [data-serenada-callflow] { ... }
  [data-serenada-callflow] .video-container { ... }
  ```

- [ ] **11.3** Add `!important` to critical layout properties (`position`, `width`, `height`, `z-index`) that host app styles are most likely to override accidentally.

- [ ] **11.4** Add a comment at the top of `callFlowStyles.ts` explaining the scoping approach and why shadow DOM was not chosen (incompatibility with video element rendering and fullscreen API).

- [ ] **11.5** Add an optional `className` prop to `SerenadaCallFlow` to allow host apps to add their own class for overrides.

---

### 12. Document Push Notification Integration

**Why:** Push notifications are deeply integrated into host apps (`client-ios/Sources/Core/Push/`, `client-android/app/src/main/java/app/serenada/android/push/`, `client/src/utils/pushCrypto.ts`) but not surfaced through the SDK. Third-party integrators who want push-to-join would need to reverse-engineer the host app implementations.

**Approach:** Create a focused integration guide rather than extracting push into the SDK (which would be a larger architectural change).

- [ ] **12.1** Create `docs/push-integration-guide.md` covering:
  - **Architecture overview:** How push snapshots work (encrypted preview image + room URL)
  - **Server endpoints:** `/api/push/subscribe`, `/api/push/notify`, `/api/push/snapshot`, `/api/push/invite`
  - **Web integration:** VAPID key fetch, `PushManager.subscribe()`, snapshot preparation
  - **Android integration:** FCM setup, `FirebaseMessagingService`, snapshot decryption
  - **iOS integration:** APNs setup, `NotificationService` extension, snapshot decryption

- [ ] **12.2** Add code snippets from host app implementations (sanitized) showing the minimum integration for each platform.

- [ ] **12.3** Document the push payload format and encryption scheme (ECDH key exchange, AES-GCM encryption for snapshot images).

- [ ] **12.4** Add a "Push Notifications" section to each sample app README noting that push is optional and linking to the integration guide.

- [ ] **12.5** Consider adding a `SerenadaPushHelper` utility to each SDK in a future version that handles subscription management and payload preparation, reducing boilerplate for integrators. File a tracking issue for this.

---

## Progress Tracker

| # | Item | Stage | Status |
|---|------|-------|--------|
| 1 | Web session contract tests | P0 | Not started |
| 2 | Unify error types | P0 | Not started |
| 3 | Type `RemoteParticipant.connectionState` | P0 | Not started |
| 4 | Extract session sub-engines | P1 | Not started |
| 5 | Typed signaling message parsing | P1 | Not started |
| 6 | Move core to peer dependency | P1 | Not started |
| 7 | Define versioning policy | P1 | Not started |
| 8 | Integration test harness | P2 | Not started |
| 9 | Expose `activeTransport` parity | P2 | Not started |
| 10 | WebRTC capability detection | P2 | Not started |
| 11 | CSS isolation | P2 | Not started |
| 12 | Push notification docs | P2 | Not started |

**Total sub-tasks:** 83 checkboxes across 12 items.
