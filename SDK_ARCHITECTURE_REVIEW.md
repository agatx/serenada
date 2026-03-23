# Serenada SDK Architecture Review

**Date:** 2026-03-22
**Scope:** Post-refactoring review of the headless SDK + optional UI pattern across Web, Android, and iOS

---

## Executive Summary

The Serenada SDK follows a **headless core + optional UI** architecture across three platforms. Each platform ships a signaling/media SDK (`@serenada/core`, `serenada-core`, `SerenadaCore`) and a pre-built call UI component (`@serenada/react-ui`, `serenada-call-ui`, `SerenadaCallUI`). A Go signaling server ties them together via protocol v1 over WebSocket/SSE dual transport.

The refactoring has produced a **genuinely cross-platform SDK** with strong parity guarantees, clean module boundaries, and production-grade resilience. Below is a detailed breakdown of what works well, what needs attention, and what should be addressed before wider SDK distribution.

> **Note (2026-03-22):** This review was conducted before the SDK remediation work (PRs #37-#54). Many issues identified below have since been resolved. Each item is annotated with its current status: **RESOLVED**, **IMPROVED**, or **OPEN**.

---

## The Good

### 1. Headless SDK / UI Separation Is Exceptionally Clean

The core insight of the architecture — separating the signaling+media SDK from the UI layer — is executed well on all three platforms:

- **Web:** `@serenada/core` is pure TypeScript with zero DOM/React dependencies. `@serenada/react-ui` wraps it with `useSyncExternalStore` and thin React components.
- **Android:** `serenada-core` uses `StateFlow` for observation with no Compose or Android UI dependencies. `serenada-call-ui` provides Compose components.
- **iOS:** `SerenadaCore` uses `@Published` properties and delegates. `SerenadaCallUI` provides SwiftUI views.

This means third-party developers can build entirely custom UIs. The pre-built `SerenadaCallFlow` on each platform provides a zero-effort integration path (URL-first or session-first modes), while power users get full state machine access.

### 2. Cross-Platform Parity Is Verified, Not Just Claimed

34 resilience constants are shared across all three platforms and **programmatically verified** via `scripts/check-resilience-constants.mjs`. This isn't documentation — it's a CI-enforceable gate. Key constants include:

| Constant | Value | Purpose |
|----------|-------|---------|
| `RECONNECT_BACKOFF_BASE_MS` | 500 | Exponential backoff base |
| `RECONNECT_BACKOFF_CAP_MS` | 5000 | Backoff ceiling |
| `PING_INTERVAL_MS` | 12000 | Heartbeat keepalive |
| `JOIN_HARD_TIMEOUT_MS` | 15000 | Absolute join deadline |
| `ICE_RESTART_COOLDOWN_MS` | 10000 | ICE restart backoff |
| `TURN_REFRESH_TRIGGER_RATIO` | 0.8 | Proactive TURN refresh |

State models (`CallPhase`, `ConnectionStatus`, `CameraMode`, `MediaCapability`) are logically equivalent across platforms with platform-idiomatic naming.

### 3. Dual-Transport Signaling With Automatic Fallback

All three clients implement the same failover strategy:

1. Connect via WebSocket (primary)
2. On timeout (2s) or 3 consecutive WS failures → fall back to SSE
3. SSE uses `GET` for stream + `POST` for sends, with session ID persistence
4. Ping/pong heartbeat (12s interval, 2 missed pongs → reconnect)
5. Exponential backoff on reconnection (500ms base, 5s cap)

The server implements both transports identically through a single `Hub/Room/Client` abstraction, so there's no behavior difference between WS and SSE paths.

### 4. Module Dependency Graph Is Clean (No Circular Dependencies)

**Web core:**
```
SerenadaSession
├── SignalingEngine (independent)
│   └── transports/ws.ts, transports/sse.ts (leaf nodes)
├── MediaEngine (independent)
│   └── callStats.ts, localVideoRecovery.ts (leaf nodes)
└── CallStatsCollector (leaf)
```

**Android & iOS follow the same DAG.** Signaling and media are peer modules orchestrated by Session — they never reference each other directly. All utilities and transport implementations are leaf nodes.

### 5. Protocol-Based Abstractions Enable Thorough Testing

All three platforms define protocols/interfaces for their internal components:

- **iOS:** `SessionMediaEngine`, `SessionSignaling`, `PeerConnectionSlotProtocol`
- **Android:** `FakeSignaling`, `FakeMediaEngine`, `FakeAPIClient`, `FakePeerConnectionSlot`
- **Web:** (less formalized, but `SignalingEngine` and `MediaEngine` are separable)

Android has the strongest test suite: `SerenadaSessionContractTest` at ~1045 lines covers the full state machine (join flow, permissions, reconnects, offer negotiation, camera modes, leave/end). iOS has solid coverage with `SessionOrchestrationTests` and `SessionNegotiationTests`. These tests use fake/mock dependencies, making them fast and deterministic.

### 6. Resilience Is Baked Into the Architecture

The SDK handles real-world network conditions out of the box:

- **Join recovery:** Kickstart at 1.2s, recovery at 4s, hard timeout at 15s
- **ICE restart:** 2s delay for disconnect, immediate for failure, 10s cooldown between restarts
- **Non-host fallback:** If host doesn't offer within 4s, non-host initiates (max 2 attempts)
- **TURN refresh:** Proactive at 80% of TTL
- **ICE candidate buffering:** Up to 50 candidates batched before sending
- **Transport reconnect:** Exponential backoff with automatic re-join on transport recovery

### 7. Sample Apps Are Minimal and Clear

All three sample apps (`samples/web/`, `samples/android/`, `samples/ios/`) are under 200 lines and demonstrate the three primary integration patterns:

1. **URL-first joining** — paste a link, get a call
2. **Room creation** — create a room, share the link
3. **Session-first** — pre-create a session for advanced control

Each follows platform idioms (React hooks, Compose, SwiftUI) without boilerplate.

### 8. State-Driven Architecture

All platforms use a **single immutable state snapshot** (`CallState`) that the UI observes:

- **Web:** Listener callbacks + `useSyncExternalStore`
- **Android:** `StateFlow<CallState>` + `collectAsState()`
- **iOS:** `@Published var state: CallState` + SwiftUI observation

This eliminates the class of bugs where UI and business logic disagree about state. State updates are atomic — `commitSnapshot` (iOS) and `rebuildState` (web) ensure the UI always sees a consistent view.

### 9. Camera Mode System Is Well-Designed

All platforms implement a mode-based camera system (`selfie → world → composite → screenShare`) rather than a binary front/back toggle. This is more expressive and supports the composite camera feature (picture-in-picture of both cameras). The `isContentMode` computed property cleanly distinguishes rendering behavior for remote participants.

### 10. Server Architecture Is Simple and Correct

The Go server is a flat package (~10 files) with goroutine-based event loops. The `Hub` routes messages, `Room` tracks participants (max 2 for 1:1), and `Client` abstracts over WS/SSE. Room capacity negotiation elegantly handles legacy 1:1-only clients alongside future multi-party support. HMAC-signed room IDs prevent room enumeration.

---

## The Bad

### 1. Web SDK Test Coverage Is ~20%

The web core SDK tests cover utilities (room status merging, layout computation, video recovery predicates, URL resolution) but **miss the critical paths**:

- `SignalingEngine` — reconnect logic, transport fallback, join timeout state machine
- `MediaEngine` — peer lifecycle, ICE restart, offer/answer flow, track replacement
- `SerenadaSession` — state machine transitions, permission detection, error propagation

Android and iOS have contract-level session tests; the web SDK does not. This is the most significant gap in the codebase.

**Risk:** Regressions in signaling reconnect or media negotiation will not be caught by web tests.

> **Status: RESOLVED.** 45 session contract tests + 10 signaling engine tests + 47 payload parser tests added. Coverage significantly improved. (PRs #41, #42, #48)

### 2. Error Types Are Inconsistent Across Platforms

- **iOS:** `CallError` is a well-designed enum (`.signalingTimeout`, `.connectionFailed`, `.roomFull`, `.serverError(String)`, `.unknown(String)`)
- **Android:** Errors are just `errorMessage: String?` in `CallState` — no structured error type
- **Web:** Errors are `CallError | null` where `CallError` has `code` and `message` strings, but codes aren't enumerated

Third-party developers can't reliably distinguish between "room is full" and "network failed" on Android or web. This makes programmatic error handling fragile.

> **Status: RESOLVED.** All platforms now have 7 canonical error codes. Android has `CallError` sealed class. Web has typed `CallErrorCode` union. iOS added `roomEnded` and `permissionDenied` cases. (PRs #38, #40, #43)

### 3. `RemoteParticipant.connectionState` Is Untyped on All Platforms

All three platforms define `connectionState` as a raw `String` rather than a typed enum:

```typescript
// Web
connectionState: string  // "new", "connecting", "connected", "disconnected", "failed", "closed"

// Android
val connectionState: String

// iOS
public var connectionState: String
```

These map to `RTCPeerConnectionState` values, which are well-defined. Using typed enums would prevent typos and enable exhaustive switch handling.

> **Status: RESOLVED.** `PeerConnectionState` (web) and `SerenadaPeerConnectionState` (Android/iOS) typed enums replace raw String on all platforms. (PRs #37, #39, #44)

### 4. SignalingMessage Payload Is Weakly Typed

On all platforms, `payload` is a generic JSON container:

- **Web:** `Record<string, unknown>`
- **Android:** `JSONObject?`
- **iOS:** `JSONValue?`

The `type` field determines what's in `payload`, but there's no discriminated union or typed accessor pattern. Internal code uses `payload["sdp"] as? String` casts that fail silently on malformed messages. A discriminated union (or at least typed accessors for known message types) would improve safety.

> **Status: RESOLVED on web, IMPROVED on Android/iOS.** Web has 7 typed payload interfaces with parse functions replacing 15 unsafe casts. Android/iOS have typed payload data classes/structs in SignalingPayloads. (PRs #48, #49, #50)

### 5. Silent Failures in Media Error Handling (Web)

The web `MediaEngine` has several try/catch blocks that log errors but don't propagate them:

- `startLocalMedia()` catches all errors and returns null — no distinction between "permission denied" and "device unavailable"
- ICE candidate processing errors are logged but swallowed
- Offer timeout sets a global error without identifying which peer failed

The Android and iOS SDKs are somewhat better here (errors flow through state), but the web SDK's pattern of "catch, log, continue" makes debugging difficult for integrators.

> **Status: OPEN.** Not addressed in remediation — would require deeper MediaEngine refactoring.

### 6. No Formal SDK Versioning Strategy

All three SDKs are at version `0.1.0` with no visible CHANGELOG or migration guide. The package.json/build.gradle/Package.swift files reference version 0.1.0, but there's no:

- Semantic versioning policy documented
- Breaking change tracking
- API stability guarantees (which types are public-stable vs. experimental)

For third-party SDK distribution, this needs to be defined.

> **Status: RESOLVED.** VERSIONING.md, CHANGELOG.md, and `check-version-parity.mjs` script added. All 7 version sources verified at 0.1.0. (PR #46)

### 7. React UI Package Has a Hard Dependency on Core (Not Peer)

`@serenada/react-ui` declares `@serenada/core` as a regular dependency, not a peer dependency:

```json
"dependencies": {
  "@serenada/core": "0.1.0"
}
```

This means if a host app also depends on `@serenada/core` directly (e.g., to create sessions before rendering the UI), it could get duplicate copies of the core module. This should be a `peerDependency` to ensure a single instance.

> **Status: RESOLVED.** `@serenada/core` moved to peerDependencies in `@serenada/react-ui`. (PR #47)

### 8. Web `useSerenadaSession` May Leak Resources

The hook creates a new `SerenadaCore` instance and calls `join()` inside a `useEffect`. If the effect's dependency array changes, the cleanup function calls `session.destroy()`, but there's a race window where the new session starts before the old one finishes cleanup. This could cause duplicate signaling connections during rapid re-renders or hot module replacement.

> **Status: OPEN.** Not addressed in remediation.

### 9. Android Local Participant Model Is Flattened

While web and iOS use a structured `LocalParticipant` object inside `CallState`, Android flattens it:

```kotlin
// Android CallState
val localCid: String?
val isHost: Boolean
val localAudioEnabled: Boolean
val localVideoEnabled: Boolean
val localCameraMode: LocalCameraMode
```

vs.

```swift
// iOS CallState
var localParticipant = LocalParticipant()  // contains cid, audioEnabled, videoEnabled, cameraMode, isHost
```

This isn't a bug, but it's an API inconsistency that makes cross-platform documentation harder and increases cognitive load for developers targeting multiple platforms.

> **Status: OPEN.** Intentional design difference — not a bug.

### 10. `activeTransport` Exposure Is Web-Only

The web `CallState` exposes `activeTransport: TransportKind | null` (ws/sse), letting the UI show which transport is active. Android and iOS track this internally but don't expose it in `CallState`. This is available in `CallDiagnostics` on Android/iOS, but the inconsistency means transport-aware features can't be built portably.

> **Status: RESOLVED.** Verified `activeTransport` exists in `CallDiagnostics` on Android and iOS. Documented in samples. (PR #51)

---

## The Ugly

### 1. SerenadaSession.swift Is 2057 Lines

The iOS `SerenadaSession.swift` is a monolith. It handles:

- State machine transitions
- Signaling message dispatch (`handleJoined`, `handleRoomState`, `handleOffer`, etc.)
- Peer slot management (create/destroy slots)
- TURN credential fetching and refresh
- Offer/answer negotiation orchestration
- Non-host fallback logic
- Join timeout management
- Camera mode switching
- Screen sharing lifecycle
- Stats collection
- Permission detection

**The Android equivalent** (`SerenadaSession.kt`) has the same problem — it's a God Object that does everything. The web `SerenadaSession.ts` is comparatively smaller (~323 lines) because it delegates more to `SignalingEngine` and `MediaEngine`.

**Recommendation:** Extract `PeerNegotiationEngine` (already partially done on iOS) and `JoinStateMachine` into separate modules. The session should orchestrate, not implement.

> **Status: RESOLVED.** iOS reduced to 786 lines, Android to 854 lines. Extracted SignalingMessageRouter, JoinFlowCoordinator, and SignalingPayloads on both platforms. JoinTimer absorbed into JoinFlowCoordinator on iOS. (PRs #49, #50)

### 2. No Integration Tests Across the Stack

There are unit tests with fakes, but no integration tests that verify:

- Client ↔ Server signaling round-trip
- WS → SSE fallback with a real server
- Two clients joining the same room and exchanging media
- Reconnect after server restart

The `tools/smoke-test/smoke-test.sh` tests real device calls, but it's a manual/CI-only script, not a test suite. There's a gap between unit tests (fast, with fakes) and smoke tests (slow, requires devices).

> **Status: RESOLVED.** 7 integration test scenarios added (join round-trip, ICE relay, room full, end_room, invalid room ID, ping-pong). Go server bootstrapped on random port with ephemeral secrets. (PR #54)

### 3. JSON Parsing Is Manual and Fragile

All three clients parse signaling messages with manual JSON extraction:

```kotlin
// Android
val sdp = payload?.optString("sdp")
val candidates = payload?.optJSONArray("candidates")
```

```swift
// iOS
guard case .string(let sdp) = payload?["sdp"] else { return }
```

```typescript
// Web
const sdp = msg.payload?.sdp as string
```

None of these validate the full message shape. A malformed server message (missing field, wrong type) will cause a silent failure or crash depending on the platform. Using Codable (iOS), kotlinx.serialization (Android), or Zod/io-ts (Web) for typed message parsing would catch protocol violations at the transport boundary.

> **Status: RESOLVED on web, IMPROVED on Android/iOS.** Web has typed parse functions with validation. Android/iOS have typed payload data classes/structs. (PRs #48, #49, #50)

### 4. ICE Candidate Buffer Limit Is Silent

All platforms cap ICE candidate buffering at 50 (`ICE_CANDIDATE_BUFFER_MAX`), but when the buffer overflows, candidates are silently dropped:

```typescript
// Web
if (peer.iceBuffer.length >= ICE_CANDIDATE_BUFFER_MAX) return; // silent drop
```

In adversarial network conditions (many TURN relays, IPv4+IPv6 dual-stack), this limit could be hit, causing connection failures with no diagnostic signal.

> **Status: OPEN.** Not addressed in remediation.

### 5. CSS Is Injected at Runtime (Web React UI)

`@serenada/react-ui` injects its styles via JavaScript (`callFlowStyles.ts`), not via a CSS file or CSS-in-JS library. This means:

- Styles are injected into `<head>` on first render
- No server-side rendering support
- Specificity conflicts with host app styles are possible
- No tree-shaking of unused styles

For an SDK component, CSS Modules or a shadow DOM approach would provide better isolation.

> **Status: IMPROVED.** CSS now scoped via `[data-serenada-callflow]` attribute selector with `!important` on critical root layout. `className` prop added for host overrides. Shadow DOM rationale documented. (PR #53)

### 6. No Graceful Degradation for Missing WebRTC Support

None of the SDKs check for WebRTC API availability before attempting to use it. On environments without `RTCPeerConnection` (older browsers, WebView without WebRTC), the SDK will throw at runtime rather than reporting a clear capability error.

The web SDK should detect `typeof RTCPeerConnection === 'undefined'` and surface a `CallError` rather than letting the error bubble from deep in `MediaEngine`.

> **Status: RESOLVED.** `SerenadaCore.isSupported()` added. `join()` returns error-state stub session with `webrtcUnavailable` code. `createRoom()` throws. (PR #52)

### 7. Composite Camera Synchronization Is Complex and Untestable

The Android `CompositeCameraCapturer` (200+ lines) manages two simultaneous camera captures, composites frames with OpenGL, handles mirroring, cropping, and aspect ratio math. This is inherently complex, but it's tightly coupled to the WebRTC capture pipeline, making it nearly impossible to unit test without a real device.

There are basic tests (`CompositeCameraCapturerTest`), but they can only verify configuration — not actual frame composition.

> **Status: OPEN.** Inherent complexity — not addressable through refactoring.

### 8. Push Notification Integration Is Absent from SDK

Push notifications are deeply integrated into the host apps but not surfaced through the SDK. `SerenadaSession` doesn't expose hooks for push snapshot preparation, notification payload decryption, or subscription management. Third-party integrators who want push-to-join would need to reverse-engineer the host app implementations.

This is an architectural gap — if the SDK is meant for third-party consumption, push should be either an optional SDK module or thoroughly documented.

> **Status: OPEN.** Removed from remediation scope.

---

## Cross-Platform Parity Matrix

| Feature | Web | Android | iOS | Parity |
|---------|-----|---------|-----|--------|
| Core entry point (`join`/`createRoom`) | `SerenadaCore` | `SerenadaCore` | `SerenadaCore` | Full |
| State observation | Listener callbacks | `StateFlow` | `@Published` | Logical |
| Pre-built call UI | `SerenadaCallFlow` | `SerenadaCallFlow` | `SerenadaCallFlow` | Full |
| Dual transport (WS/SSE) | Yes | Yes | Yes | Full |
| Resilience constants (34) | Yes | Yes | Yes | Verified |
| Permission gating | Callback | Delegate | Delegate | Full |
| Camera modes (4) | Yes | Yes | Yes | Full |
| Screen sharing | Yes | Yes | Yes | Full |
| Room watcher | `RoomWatcher` | In-app | `RoomWatcher` | Partial |
| Diagnostics probe | `SerenadaDiagnostics` | `SerenadaDiagnostics` | `SerenadaDiagnostics` | Full |
| Typed error enum | Yes (`CallErrorCode`) | Yes (sealed class) | Yes (`CallError` enum) | Full |
| Contract-level tests | Yes | Yes (1045 lines) | Yes | Full |
| Push integration | Host app only | Host app only | Host app only | Consistent (but missing from SDK) |

---

## Priority Recommendations

### P0 — Before SDK Distribution

1. ~~**Add web session contract tests** — Port the Android `SerenadaSessionContractTest` pattern to the web SDK. This is the single highest-value testing investment.~~ DONE
2. ~~**Unify error types** — Define a `CallError` enum on all platforms (not just iOS) with codes like `signalingTimeout`, `connectionFailed`, `roomFull`, `permissionDenied`, `serverError`.~~ DONE
3. ~~**Type `RemoteParticipant.connectionState`** — Replace `String` with a typed enum on all platforms.~~ DONE

### P1 — Before v1.0

4. ~~**Extract session sub-engines** — Break up the 2000-line `SerenadaSession` (iOS/Android) into `PeerNegotiationEngine` and `JoinStateMachine`.~~ DONE
5. ~~**Add typed signaling message parsing** — Use Codable/kotlinx.serialization/Zod to validate messages at the transport boundary.~~ DONE
6. ~~**Move `@serenada/core` to peerDependency** in `@serenada/react-ui`.~~ DONE
7. ~~**Define versioning policy** — Document semantic versioning, API stability tiers, and breaking change process.~~ DONE

### P2 — Quality of Life

8. ~~**Add integration test harness** — Spin up a test server, connect two SDK instances, verify offer/answer exchange.~~ DONE
9. ~~**Expose `activeTransport` on Android/iOS CallState** (or document that it's in `CallDiagnostics`).~~ DONE
10. ~~**Add WebRTC capability detection** on web before calling `new RTCPeerConnection`.~~ DONE
11. ~~**Improve CSS isolation** in web React UI (CSS Modules or shadow DOM).~~ DONE
12. **Document push notification integration** for third-party SDK consumers.

---

## Conclusion

The Serenada SDK architecture is **strong and well-executed**. The headless SDK pattern, cross-platform resilience constants with automated verification, dual-transport signaling, and clean module boundaries represent thoughtful engineering decisions that will scale.

The main gaps are in **testing depth** (especially web), **error type consistency**, and **session class size**. These are normal post-refactoring cleanup items, not architectural flaws. The foundation is solid — the SDK is ready for beta distribution with the P0 items addressed and production-ready after P1.

> **Post-remediation update (2026-03-22):** The SDK remediation work (PRs #37-#54) resolved 11 of 12 priority recommendations and addressed 12 of 18 identified issues (9 fully resolved, 3 improved). All P0 items (web tests, unified errors, typed connectionState) and all P1 items (session decomposition, typed signaling, peerDependency, versioning) are complete. The remaining open items — silent media error handling, useSerenadaSession resource leak, flattened Android local participant model, ICE buffer overflow diagnostics, composite camera complexity, and push notification SDK integration — are either low-risk, intentional design choices, or out of scope for the current release. The SDK is production-ready for v1.0 distribution.
