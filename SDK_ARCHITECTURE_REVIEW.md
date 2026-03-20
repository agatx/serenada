# Serenada SDK Architecture Review — Integration Readiness Assessment

## Context

You're evaluating the iOS (SerenadaCore + SerenadaCallUI), Android (serenada-core + serenada-call-ui), and Web (@serenada/core + @serenada/react-ui) SDKs for integration into a high-volume app. This review covers the good, the bad, and the ugly across all three platforms.

---

## THE GOOD

### 1. Clean Two-Layer SDK Design (all platforms)

All three platforms follow the same pattern: **headless core** (no UI dependency) + **optional UI layer**. This is the right architecture for SDK consumers — you can drop in the pre-built UI or wire the headless core into your own.

- **iOS**: `SerenadaCore` (SPM) + `SerenadaCallUI` (SPM, SwiftUI)
- **Android**: `serenada-core` (Gradle module) + `serenada-call-ui` (Compose)
- **Web**: `@serenada/core` (vanilla TS, zero deps) + `@serenada/react-ui`

The core packages have no framework dependency — SerenadaCore doesn't import SwiftUI, `@serenada/core` doesn't import React, etc. This is critical for embedding in apps with different UI stacks.

### 2. Minimal, Intentional Public API

The entry point on every platform is essentially three operations:

```
SerenadaCore.join(url) → Session
SerenadaCore.join(roomId) → Session
SerenadaCore.createRoom() → Session
```

Session control is equally clean: `toggleAudio()`, `toggleVideo()`, `flipCamera()`, `leave()`, `end()`. No leaky abstractions in the primary API — you don't need to know WebRTC exists.

### 3. Excellent Resilience Engineering

This is where the SDK genuinely shines. The resilience system is **cross-platform verified** (a script `check-resilience-constants.mjs` enforces parity) and handles an impressive list of failure modes:

| Mechanism | Timing | Purpose |
|-----------|--------|---------|
| Signaling reconnect | 500ms → 5s backoff | Network drops |
| WS → SSE fallback | After 3 WS failures | Firewalls/proxies |
| Join kickstart | 1.2s | Stalled signaling |
| Join recovery | 4s | Server slow to ack |
| Join hard timeout | 15s | Dead connections |
| Offer timeout | 8s | Peer never answers |
| ICE restart cooldown | 10s | Prevents restart loops |
| Non-host fallback offer | 4s, max 2 attempts | Asymmetric NAT |
| TURN refresh | 80% of TTL | Token expiry |
| Ping/pong heartbeat | 12s interval, 2 miss | Dead transports |

All constants are shared across platforms. This level of resilience engineering is rare in WebRTC SDKs.

### 4. Signaling Protocol Parity

Protocol v1 is identical across all clients and the Go server: JSON envelope with `{v, type, rid, sid, cid, to, payload}`. Dual transport (WebSocket primary, SSE fallback). This means a single server handles all platforms identically.

### 5. State Observation Patterns (platform-native)

Each platform uses idiomatic reactive patterns:
- **iOS**: `@Published` properties + Combine (`$state.sink {}`)
- **Android**: `StateFlow<CallState>` (coroutine-friendly)
- **Web**: `subscribe()` + `useSyncExternalStore` (React 18+ compatible)

The `CallState` struct/data class is an immutable snapshot on all platforms — no nested mutation, clear change boundaries.

### 6. Permission Handling Without Prompting

All three platforms probe permission state without triggering the OS prompt, then signal `onPermissionsRequired` so the host app controls when/how to ask. The host calls `resumeJoin()` after granting. This is the right pattern for high-volume apps where you want to control the permission UX.

### 7. End-to-End Encrypted Push Snapshots

The push notification flow encrypts camera snapshots client-side using ECDH + AES-GCM before uploading. The server never sees raw images. This is a strong privacy design that would be compelling for enterprise integrations.

---

## THE BAD

### 1. Dual-State Model in iOS (legacyUiState + @Published state)

`SerenadaSession` maintains **two parallel state representations**:

```swift
private var legacyUiState = CallUiState()       // internal workhorse (mutable struct)
@Published public private(set) var state = CallState()  // published facade
```

Every mutation goes: update `legacyUiState` → call `syncPublishedSnapshot()` → manually map 25+ fields into `state`. This is a maintenance hazard — miss one field and subscribers see stale data. Android and Web don't have this problem; they use a single state object.

**Risk for your integration**: The `@Published` `willSet` timing already caused a real bug (push snapshot regression we just fixed). The dual-state pattern makes more bugs like this likely.

### 2. Monolithic Session Classes (all platforms)

The core session class is too large on every platform:

| Platform | File | LOC | Responsibilities |
|----------|------|-----|------------------|
| iOS | `SerenadaSession.swift` | ~1,900 | Join flow, signaling handling, peer management, ICE restart, stats, audio routing, state sync |
| Android | `SerenadaSession.kt` | ~1,370 | Same as iOS |
| Web | `SignalingEngine.ts` + `MediaEngine.ts` | ~550 + ~800 | Better — split into two engines |

Web actually did this right by separating signaling and media into independent engines. iOS and Android lump everything into one class with 30+ private mutable fields and 8+ scheduled tasks. Testing the join flow in isolation is impossible without spinning up the entire session.

### 3. WebRTC State Exposed as Strings

On iOS and Android, low-level WebRTC states leak through the public API:

```swift
// iOS SerenadaSession
@Published public private(set) var iceConnectionState = "NEW"      // String, not enum
@Published public private(set) var peerConnectionState = "NEW"     // String, not enum
@Published public private(set) var rtcSignalingState = "STABLE"    // String, not enum
```

These are raw WebRTC enum names as strings. If WebRTC changes naming, the SDK silently breaks. Web avoids this by keeping these internal to the media engine and only exposing a computed `connectionStatus: 'connected' | 'recovering' | 'retrying'`.

### 4. Callback-Style createRoom() (iOS/Android)

```swift
// iOS
public func createRoom(completion: @escaping (Result<CreateRoomResult, Error>) -> Void)

// Android
fun createRoom(callback: (CreateRoomResult) -> Unit)
```

This is the only async operation that uses completion handlers instead of modern patterns (async/await in Swift, suspend in Kotlin). It's asymmetric with `join()` which returns synchronously. Web uses `async/await` properly. Minor, but creates friction in async call chains.

### 5. No Dependency Injection for Internal Components

On iOS and Android, `SerenadaSession` hard-codes its dependencies:

```swift
// iOS init
self.signalingClient = SignalingClient(forceSseSignaling: ...)
self.webRtcEngine = WebRtcEngine(...)
self.apiClient = CoreAPIClient()
```

No protocol abstractions, no injection points. This means:
- **You can't mock** the signaling client for integration tests
- **You can't swap** the API client for a custom backend
- **You can't stub** the WebRTC engine for UI testing

For a high-volume app with automated testing, this is a significant gap.

### 6. PeerConnectionSlot State Is Publicly Mutable (iOS/Android)

`SerenadaSession` directly mutates `PeerConnectionSlot` properties:

```swift
peerSlots[cid]?.pendingIceRestart = false
peerSlots[cid]?.isMakingOffer = true
peerSlots[cid]?.nonHostFallbackAttempts += 1
```

The slot should encapsulate its own state machine. This tight coupling means session and slot must be understood together — roughly 2,500 LOC that can't be reasoned about independently.

### 7. Limited Test Coverage (all platforms)

Tests exist but focus on utilities (layout computation, backoff, room status parsing). The critical path — session state machine, signaling reconnection, offer/answer negotiation — is untested on all platforms. There are no integration tests with a real or mock server.

---

## THE UGLY

### 1. @Published willSet Timing Trap (iOS)

This is the bug we just fixed, but the architectural issue remains. Combine's `@Published` fires subscribers during `willSet` — meaning reading `session.state` directly from within a subscriber gives the **old** value, not the new one.

The `syncPublishedSnapshot()` pattern means every state change goes through this trap zone. Any code that subscribes to `$state` and then reads `session.state` (or any other `@Published` property) directly will get stale data. This is a class of bug that's invisible until it isn't.

**The real fix**: Eliminate `legacyUiState` and the dual-state model. Use a single published state object.

### 2. 15+ Individual @Published Properties (iOS)

Beyond the `state` object, `SerenadaSession` publishes **13 additional individual properties**:

```swift
@Published var isSignalingConnected = false
@Published var iceConnectionState = "NEW"
@Published var peerConnectionState = "NEW"
@Published var rtcSignalingState = "STABLE"
@Published var isFrontCamera = true
@Published var isScreenSharing = false
@Published var cameraZoomFactor: Double = 1
@Published var isFlashAvailable = false
@Published var isFlashEnabled = false
@Published var remoteContentParticipantId: String?
@Published var remoteContentType: String?
@Published var callStats = CallStats()
@Published var realtimeStats = RealtimeCallStats.empty
```

Most of these overlap with or derive from `CallState`. The host app CallManager subscribes to `$state` but then also reads these individual properties, creating multiple observation paths that can fire at different times. Android and Web don't have this problem — they use a single `StateFlow<CallState>` / `subscribe()`.

### 3. Thread Safety Not Enforced (Android)

Android's `SerenadaSession` relies on Main handler serialization but has no precondition checks:

```kotlin
@Volatile private var connected = false        // volatile ✓
private var activeTransport: TransportKind?     // NOT volatile, NOT synchronized ✗
```

If a host app calls SDK methods from a background coroutine (easy mistake), there's no crash or warning — just silent data races. iOS avoids this with `@MainActor` (compiler-enforced). Web avoids it by being single-threaded.

### 4. Composite Camera Failure Is Permanent and Silent (iOS/Android)

If the composite camera (PiP selfie-on-world) fails once, it's disabled forever via `SharedPreferences`/`UserDefaults`:

```swift
compositeDisabledAfterFailure = true  // persisted, no expiry, no notification
```

No callback, no error state, no way for the host app to know it happened. The user just loses the feature. For a high-volume app, this means a one-time camera glitch permanently degrades the experience for that user.

### 5. Stats Executor Lifecycle (Android)

```kotlin
private val webRtcStatsExecutor = Executors.newSingleThreadExecutor()

fun resetResources() {
    webRtcStatsExecutor.shutdown()  // destroyed
}
```

Once shutdown, the executor is dead. If the session attempts to collect stats after reset (race condition), it gets a `RejectedExecutionException`. The executor should be per-session or use a shared pool.

### 6. WebRTC Engine Is a God Object (~1,500 LOC on iOS/Android)

`WebRtcEngine` handles: media tracks, camera device selection, format negotiation, zoom, flashlight, screen share via ReplayKit/MediaProjection, composite camera rendering, renderer attachment/detachment, codec configuration, and local video restart. This should be 4-5 focused classes. When you need to debug a camera issue, you're reading through flashlight code.

---

## INTEGRATION RECOMMENDATIONS FOR HIGH-VOLUME APP

### What works well out of the box
- The headless core + optional UI architecture is right for embedding
- Signaling resilience will handle real-world network conditions
- State observation patterns are idiomatic per platform
- The permission flow gives you full control of UX
- Encrypted push snapshots are a differentiator

### What to watch out for
1. **Test your state observation carefully on iOS** — the `@Published` willSet timing trap affects any code that reads session properties directly within a Combine subscriber
2. **Always call SDK methods from the main thread on Android** — there are no guards
3. **Monitor composite camera availability** — silent failure can degrade UX with no signal to your analytics
4. **Plan for the lack of integration testing** — you'll need to build your own test harness since the SDKs don't expose mockable interfaces

### What I'd ask the SDK team to address before production
1. **Consolidate iOS dual-state model** — single source of truth, eliminate `legacyUiState`
2. **Add protocol abstractions** for SignalingClient and WebRtcEngine — enables testing
3. **Add main-thread assertions** on Android public API
4. **Surface composite camera failures** through the state/delegate
5. **Fold individual @Published properties into CallState** on iOS

---

## Verification

This review was based on reading all critical source files across:
- `client-ios/SerenadaCore/Sources/` (33 files)
- `client-ios/SerenadaCallUI/Sources/` (15 files)
- `client-ios/Sources/` (host app, 20+ files)
- `client-android/serenada-core/` (SDK module)
- `client-android/app/` (host app)
- `client/packages/core/src/` (web SDK)
- `client/packages/react-ui/src/` (React UI)
- `samples/ios/` and test suites across all platforms
