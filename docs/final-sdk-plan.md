# Serenada SDK — Final Architecture & Implementation Plan

Synthesized from six independent and cross-pollinated analyses (Claude v1/v2, Codex v1/v2, Gemini v1/v2). This document resolves all remaining disagreements and provides an implementation-ready plan.

---

## Core Principle

> The reusable boundary is the **call flow**, not the product shell. Give the SDK a URL, it handles the rest.

---

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                          HOST APP                              │
│                                                                │
│  Push notifications      Deep link routing     App navigation  │
│  Room management UI      Settings UI           Persistence     │
│  Foreground service      App extensions        Firebase init   │
│  Room sharing UX         Diagnostics screen    Branding        │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │               serenada-call-ui (call flow)                │ │
│  │                                                           │ │
│  │  Permission gate       Joining screen     Waiting screen  │ │
│  │  In-call screen        Error recovery     Ended screen    │ │
│  │  Video tiles/layout    Status overlay     Debug overlay    │ │
│  │  Theming               Feature toggles    English strings  │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │              serenada-core (headless)                │  │ │
│  │  │                                                     │  │ │
│  │  │  SerenadaSession     CallState        Signaling     │  │ │
│  │  │  WebRTC engine       ICE / TURN       Media ctrl    │  │ │
│  │  │  Camera modes        Reconnection     Resilience    │  │ │
│  │  │  URL parsing         Layout algo      Room creation │  │ │
│  │  │  Lifecycle hooks     Permission preflight            │  │ │
│  │  │  Preflight diag      Live call stats               │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

---

## Resolved Decisions

These resolve every disagreement that remained across the v2 proposals.

### 1. Room creation: convenience method in core

Core includes a `createRoom()` convenience method. Someone has to create the room, and bundling this in core makes integration easier — especially for third-party apps that don't have their own backend. The method calls `POST /api/room-id` on the configured `serverHost` and returns a joinable URL + session.

This is a convenience, not the primary path. The primary contract remains URL-in. Host apps that create rooms through their own backend can ignore this method entirely.

### 2. Permissions: core signals, call-ui or host prompts

- **core** detects when camera or microphone permissions are missing and exposes that as a structured blocked state or callback
- **core** never shows OS permission UI directly
- **call-ui** exposes `SerenadaPermissions.request()` as a convenience helper and uses it automatically in URL-first flows
- Host apps using **session-first** or **core-only** integration can call the same helper from the structured callback, or use their own custom rationale + permission flow
- After permissions are granted, the host app or call-ui resumes the pending join

```swift
// iOS — session-first flow
let session = serenada.join(url: url)
session.onPermissionsRequired = { permissions in
    SerenadaPermissions.request(permissions) { granted in
        if granted {
            session.resumeJoin()
        } else {
            session.cancelJoin()
        }
    }
}
```

```kotlin
// Android — session-first flow
session.onPermissionsRequired = { permissions ->
    SerenadaPermissions.request(activity, permissions) { granted ->
        if (granted) session.resumeJoin() else session.cancelJoin()
    }
}
```

```typescript
// Web — session-first flow
session.onPermissionsRequired = async (permissions) => {
    const granted = await SerenadaPermissions.request(permissions)
    if (granted) session.resumeJoin()
    else session.cancelJoin()
}
```

### 3. i18n: English-only default, host provides additional locales

- **core** exposes structured state enums and error codes (e.g., `CallPhase.waiting`, `CallError.signalingTimeout`)
- **call-ui** bundles **English strings only** as the default
- Host apps provide additional localizations via a strings configuration object on `SerenadaCallFlow`
- Host apps can also override any default English string through the same mechanism
- Core-only integrators map states/errors to their own copy

### 4. Layout helpers: in core as optional public utility

`computeLayout` is a pure function with no UI dependency. It belongs in core so that both call-ui and core-only integrators can use it. Exported as an optional utility, not part of the session API.

### 5. Diagnostics: two separate data products in core

Core provides two distinct diagnostics surfaces. Both are headless — structured data, no UI.

**A. Preflight diagnostics (`SerenadaDiagnostics`)** — a one-shot pre-call device/network checker that host apps use to build their own "Device Check" screen. This belongs in core because it depends on WebRTC internals (device enumeration, TURN probing, signaling connectivity) that core bundles. Without this utility, core-only integrators would have no clean way to implement a device check.

**B. Live call stats (`SerenadaSession.callStats`)** — real-time telemetry exposed as an observable on the session during an active call (bitrate, packet loss, jitter, codec, ICE candidate pair, etc.). call-ui's debug overlay consumes this. Host apps building custom in-call UIs can also observe it.

These are separate products with separate models. Preflight diagnostics run before a call exists; live call stats require an active `SerenadaSession`. They share no types.

**Preflight diagnostics contract: no prompts, no side effects.**

`SerenadaDiagnostics` never triggers OS permission prompts or mutates any state. If camera or microphone permissions have not been granted, the check reports `.notAuthorized` — it does not attempt to request access. This preserves the headless-core boundary established for permissions.

The result model distinguishes "check could not run" from "check ran and failed":
- `.available` — device/service is reachable and usable
- `.unavailable(reason)` — check ran, device/service is not usable
- `.notAuthorized` — check could not run because OS permission is missing
- `.skipped(reason)` — check could not run for another reason (e.g., no network)

```swift
// Swift — preflight diagnostics
let diagnostics = SerenadaDiagnostics(config: serenadaConfig)

diagnostics.runAll { report in
    report.camera         // .available | .unavailable(reason) | .notAuthorized
    report.microphone     // .available | .unavailable(reason) | .notAuthorized
    report.speaker        // .available | .unavailable(reason)
    report.network        // .reachable | .unreachable(reason) | .skipped(reason)
    report.signaling      // .connected(transport: "ws"|"sse") | .failed(reason)
    report.turn           // .reachable(latencyMs: Int) | .unreachable(reason)
    report.devices        // [DeviceInfo] — cameras, mics enumerated (empty if notAuthorized)
}

// Or run individual checks
diagnostics.checkCamera { result in ... }
diagnostics.checkTurn { result in ... }
```

```kotlin
// Kotlin — preflight diagnostics
val diagnostics = SerenadaDiagnostics(config)
val report = diagnostics.runAll()  // suspend function, never prompts
```

```typescript
// TypeScript — preflight diagnostics
const diagnostics = createSerenadaDiagnostics(config)
const report = await diagnostics.runAll()  // never prompts
```

```swift
// Swift — live call stats (during active call)
session.callStats  // @Published CallStats — updated periodically while in-call
```

**Which diagnostic endpoints move into core vs stay in host:**

The existing `APIClient` / `ApiClient` / `roomApi` on each platform touches several endpoints. The split:
- **Core's call-only client**: TURN credential fetch, room creation, signaling connectivity probe — these are needed by `SerenadaSession` and `SerenadaDiagnostics`
- **Host-only client**: push subscription/unsubscription endpoints, invite-link generation, snapshot upload, any app-analytics endpoints — these stay in the host app

call-ui does not include a diagnostics screen (that's a product feature, not a call-flow concern). The Serenada app's existing diagnostics/device-check screens become host-app screens that consume `SerenadaDiagnostics`.

### 6. WebRTC binary: bundled

The custom WebRTC build (branch-heads/7559_173) is bundled with core. This is standard practice for video SDKs and eliminates integration complexity. The custom build is essential for composite camera and other Serenada-specific features.

### 7. Versioning: core and call-ui ship together

Same version number, released together. call-ui declares an exact dependency on core. This stays simple until the API stabilizes. Semantic versioning from 0.1.0 onward.

---

## Core API Surface

Consistent across all three platforms, adapted to each platform's idioms.

### Initialization

```swift
// Swift
let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

// Kotlin
val serenada = SerenadaCore(config = SerenadaConfig(serverHost = "serenada.app"))

// TypeScript
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })
```

**`SerenadaConfig`**:

| Field | Type | Default | Notes |
|---|---|---|---|
| `serverHost` | `String` | required | Default server for URL resolution |
| `defaultAudioEnabled` | `Bool` | `true` | Mic on/off at join |
| `defaultVideoEnabled` | `Bool` | `true` | Camera on/off at join |
| `transports` | `[.ws, .sse]` | both | Allows override for testing |

### Call Lifecycle

```
// Join by URL (primary path)
let session = serenada.join(url: "https://serenada.app/call/ABC123")

// Join by room ID (uses configured serverHost)
let session = serenada.join(roomId: "ABC123")

// Create a new room (convenience — calls POST /api/room-id on serverHost)
serenada.createRoom { result in
    let url = result.url          // "https://serenada.app/call/XYZ789"
    let session = result.session  // already joining
    // share url with the other party
}

// Leave (local participant exits, room stays open)
session.leave()

// End (terminates room for all — if permitted)
session.end()
```

### Observable Call State

Single observable state object, same shape on all platforms:

```
CallState {
    phase: .idle | .awaitingPermissions | .joining | .waiting | .inCall | .ending | .error
    roomId: String?
    roomUrl: URL?
    localParticipant: Participant {
        cid: String
        audioEnabled: Bool
        videoEnabled: Bool
        cameraMode: .selfie | .world | .composite | .screenShare
        isHost: Bool
    }
    remoteParticipants: [Participant] {
        cid: String
        audioEnabled: Bool
        videoEnabled: Bool
        connectionState: String
    }
    connectionStatus: .connected | .recovering | .retrying
    activeTransport: "ws" | "sse"
    requiredPermissions: [MediaCapability]?
    error: CallError?
}
```

**Platform-specific observation**:
- **iOS**: `@Published` / Combine / `AsyncSequence` on `SerenadaSession`
- **Android**: `StateFlow<CallState>` on `SerenadaSession`
- **Web**: `session.subscribe(callback)` — framework-agnostic; call-ui wraps in React hook

### Media Controls

```
session.toggleAudio()
session.toggleVideo()
session.flipCamera()              // cycles: selfie → world → composite → selfie
session.setAudioEnabled(true)
session.setVideoEnabled(false)
session.setCameraMode(.world)
session.startScreenShare(intent)  // Android: MediaProjection; iOS: requires host extension
session.stopScreenShare()
```

### Video Rendering

Core provides renderer attachment points, not views:

```
// iOS: RTCVideoRenderer
session.attachLocalRenderer(renderer)
session.attachRemoteRenderer(renderer, forParticipant: cid)

// Android: SurfaceViewRenderer
session.attachLocalRenderer(renderer)
session.attachRemoteRenderer(renderer, cid)

// Web: MediaStream
session.localStream                // MediaStream
session.remoteStreams               // Map<cid, MediaStream>
```

### Delegate / Lifecycle Hooks

For host apps that need to handle permissions, respond to call events, or integrate with platform services:

```
protocol SerenadaCoreDelegate {
    // Permissions — structured signal only; host app or call-ui decides how to prompt
    func sessionRequiresPermissions(_ session: SerenadaSession,
                                    permissions: [MediaCapability])

    // Lifecycle
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState)
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason)
}
```

The permissions callback is optional. If no delegate is set, `call-ui` handles prompting in URL-first flows; core-only integrators prompt however they want and then call `resumeJoin()`.

---

## Call-UI API Surface

The call-ui component is named `SerenadaCallFlow` — it handles the entire visual sequence from joining through call end.

### URL-first (simplest integration)

```swift
// iOS
SerenadaCallFlow(url: serenadaURL, onDismiss: { dismiss() })

// Android
SerenadaCallFlow(url = serenadaUrl, onDismiss = { navController.popBackStack() })

// Web
<SerenadaCallFlow url={serenadaUrl} onDismiss={() => navigate('/')} />
```

### Session-first (for pre-observation)

```swift
// iOS
let session = serenada.join(url: url)
SerenadaCallFlow(session: session)
    .serenadaTheme(.init(accentColor: .blue))
    .onCallEnded { reason in dismiss() }
```

### Feature Toggles

Host apps can hide optional call-ui features via `SerenadaCallFlowConfig`:

```swift
// Swift
SerenadaCallFlow(url: url, config: .init(
    screenSharingEnabled: false,   // hides screen share button (default: true)
    inviteControlsEnabled: false   // hides QR code + invite/share buttons (default: true)
))

// Kotlin
SerenadaCallFlow(
    url = url,
    config = SerenadaCallFlowConfig(
        screenSharingEnabled = false,
        inviteControlsEnabled = false
    )
)
```

```tsx
// Web
<SerenadaCallFlow
    url={url}
    config={{
        screenSharingEnabled: false,
        inviteControlsEnabled: false,
    }}
/>
```

**`SerenadaCallFlowConfig`**:

| Field | Type | Default | Notes |
|---|---|---|---|
| `screenSharingEnabled` | `Bool` | `true` | Show/hide screen share control |
| `inviteControlsEnabled` | `Bool` | `true` | Show/hide QR code and invite/share buttons |
| `debugOverlayEnabled` | `Bool` | `false` | Show/hide the debug stats overlay toggle |

When a feature is disabled, the corresponding control is removed from the UI entirely (not just greyed out). Core still supports the underlying functionality — these toggles only affect call-ui's presentation.

### Theming

```swift
SerenadaCallFlowTheme {
    accentColor: Color
    backgroundColor: Color
    controlBarStyle: ControlBarStyle
    // platform-specific additions
}
```

### String Overrides

Host apps provide localizations or override default English strings via a strings map:

```swift
// Swift
SerenadaCallFlow(url: url, strings: [
    .waitingForOther: "Esperando al otro participante...",
    .reconnecting: "Reconectando...",
    .hangUp: "Colgar"
])

// Kotlin
SerenadaCallFlow(url = url, strings = mapOf(
    SerenadaString.WaitingForOther to "Ожидание другого участника...",
    SerenadaString.Reconnecting to "Переподключение..."
))
```

```tsx
// Web
<SerenadaCallFlow url={url} strings={{
    waitingForOther: "En attente de l'autre participant...",
    reconnecting: "Reconnexion..."
}} />
```

Any string not overridden falls back to the bundled English default.

---

## What Lives Where

### serenada-core

| Current code | In core? | Visibility |
|---|---|---|
| `SignalingClient` + transports (WS, SSE) | Yes | internal |
| `WebRtcEngine` + `PeerConnectionSlot` | Yes | internal |
| Call orchestration logic (from `CallManager`) | Yes | public facade (`SerenadaSession`) |
| SDK-native `CallState`, `CallPhase`, `Participant`, models | Yes | public |
| Call-only API client (TURN credentials + room creation) | Yes | internal (room creation exposed via public `createRoom()`) |
| `SignalingMessage`, protocol v1 envelope | Yes | internal |
| Audio session controller | Yes | internal |
| Camera modes (selfie/world/composite) | Yes | internal |
| `CompositeCameraCapturer` | Yes | internal |
| Layout computation (`computeLayout`) | Yes | public utility |
| Resilience constants & retry logic | Yes | internal |
| Screen sharing engine | Yes | internal, exposed via session controls |
| URL parsing / `DeepLinkParser` | Yes | public utility |
| Permission preflight + blocked-state signaling | Yes | public — core checks on `join()`, then pauses |
| `SerenadaDiagnostics` (preflight) | Yes | public utility — headless device/network/TURN checks, no prompts |
| `CallStats` (live telemetry) | Yes | public observable on `SerenadaSession` — bitrate, loss, jitter, codec |

### serenada-call-ui

| Component | Notes |
|---|---|
| Call flow container | Awaiting permissions → joining → waiting → in-call → error → ended |
| Video renderers / tiles | Platform-native rendering with layout engine |
| Control bar | Mute, camera, hang up, flip, screen share |
| Connection status overlay | "Connecting...", "Reconnecting..." |
| Debug overlay (optional) | Toggle-able in-call stats panel — renders `session.callStats` from core |
| Participant pinning | Multi-party layout with focus |
| Join chime | Audio feedback on participant arrival |
| Default English strings | Host provides additional locales via config |
| Feature toggles | Screen sharing, invite controls — hideable per-config |
| Theming | Colors, fonts via config object |

### Host app (stays out of SDK)

| Component | Why |
|---|---|
| Push notifications (FCM, APNs, Web Push) | App-level infrastructure, different per app |
| Push snapshot encryption | Coupled with push infrastructure |
| Deep link / Universal Link registration | OS-level, bound to host bundle ID |
| Home screen / room management UI | App-specific UX |
| Room sharing UX / custom creation flows | App-level UX (core provides `createRoom()` convenience) |
| Settings UI | App-specific preferences |
| Foreground service (Android) | Must be declared by host app |
| Notification service extension (iOS) | App extension target |
| Broadcast upload extension (iOS) | App extension target |
| Firebase / third-party SDK init | App-level dependency |
| Persistence (recent calls, saved rooms) | App-level data |
| Diagnostics screen (standalone) | Product feature, not call-flow |

---

## Platform-Specific Packaging

### iOS

| Library | Format |
|---|---|
| `SerenadaCore` | Swift Package (SPM). Embeds WebRTC.xcframework as binary target. |
| `SerenadaCallUI` | Swift Package. Depends on `SerenadaCore`. Pure SwiftUI. |

```
File mapping:

Sources/Core/Call/CallManager.swift         → SerenadaCore: split into SerenadaSession (public)
                                              + internal orchestration
Sources/Core/Call/WebRtcEngine.swift         → SerenadaCore: internal
Sources/Core/Call/PeerConnectionSlot.swift   → SerenadaCore: internal
Sources/Core/Call/CallAudioSessionController → SerenadaCore: internal
Sources/Core/Signaling/*                     → SerenadaCore: internal
Sources/Core/Models/*                        → SerenadaCore: public models
Sources/Core/Networking/APIClient.swift      → split first:
                                              call-only client (TURN + room creation +
                                              signaling probe for diagnostics) → SerenadaCore
                                              push / invite / snapshot / analytics endpoints → host app
Sources/Core/Utils/DeepLinkParser.swift      → SerenadaCore: public
Sources/Core/Layout/ComputeLayout.swift      → SerenadaCore: public utility

Sources/UI/Screens/CallScreen.swift          → SerenadaCallUI
Sources/UI/Components/WebRTCVideoView.swift  → SerenadaCallUI
Sources/UI/Components/* (call-related)       → SerenadaCallUI

Sources/Core/Push/*                          → stays in host app
Sources/Core/Stores/*                        → stays in host app
Sources/App/*                                → stays in host app
NotificationService/*                        → stays in host app
BroadcastUpload/*                            → stays in host app
Sources/UI/Screens/JoinScreen.swift          → stays in host app
Sources/UI/Screens/SettingsScreen.swift       → stays in host app
Sources/UI/Screens/DiagnosticsScreen.swift    → stays in host app (uses SerenadaDiagnostics from core)
```

### Android

| Library | Format |
|---|---|
| `app.serenada:core` | Android library module (AAR). Bundles WebRTC AAR. |
| `app.serenada:call-ui` | Android library module (AAR). Depends on core. Pure Compose. |

```
File mapping:

call/CallManager.kt               → core: split into SerenadaSession (public)
                                     + internal orchestration
                                     Strip: saved rooms, recent calls, settings,
                                     push sync, room-status watching, snapshot upload
call/WebRtcEngine.kt               → core: internal
call/SignalingClient.kt             → core: internal
call/CompositeCameraCapturer.kt     → core: internal
call/PeerConnectionSlot.kt          → core: internal
network/ApiClient.kt                → split first:
                                      call-only client (TURN + room creation +
                                      signaling probe for diagnostics) → core
                                      push / invite / snapshot / analytics endpoints → :app
layout/ComputeLayout.kt             → core: public utility
i18n/*                              → call-ui: English strings only; host provides other locales

ui/CallScreen.kt                    → call-ui (break into: ParticipantGrid,
                                      ControlBar, StatusOverlay sub-composables)
ui/Theme.kt                         → call-ui (with customization API)

push/*                              → stays in host app
data/*                              → stays in host app
service/CallService.kt              → stays in host app (core provides lifecycle hooks)
ui/JoinScreen.kt                    → stays in host app
ui/SettingsScreen.kt                → stays in host app
ui/DiagnosticsScreen.kt             → stays in host app (uses SerenadaDiagnostics from core)
ui/SerenadaAppRoot.kt               → stays in host app
SerenadaApp.kt, MainActivity.kt    → stays in host app
```

Update `settings.gradle.kts` to add `:serenada-core` and `:serenada-call-ui` modules.

### Web

| Library | Format |
|---|---|
| `@serenada/core` | npm package. Vanilla TypeScript. No React dependency. |
| `@serenada/react-ui` | npm package. React components. Depends on `@serenada/core`. |

```
File mapping:

contexts/SignalingContext.tsx        → @serenada/core: SerenadaSignaling class (vanilla TS)
contexts/WebRTCContext.tsx           → @serenada/core: SerenadaMedia class (vanilla TS)
contexts/signaling/transports/*     → @serenada/core: internal
contexts/localVideoRecovery.ts      → @serenada/core: internal
layout/computeLayout.ts             → @serenada/core: public export
constants/webrtcResilience.ts       → @serenada/core: internal
utils/roomApi.ts                    → @serenada/core: call-only client
                                      (TURN + room creation + signaling probe for diagnostics)
                                      push / invite / snapshot / analytics endpoints → host app

pages/CallRoom.tsx                  → @serenada/react-ui: <SerenadaCallFlow>
                                      Break into: ParticipantGrid, ControlBar,
                                      StatusOverlay, DebugPanel sub-components
pages/callDiagnostics.ts            → @serenada/react-ui: internal (debug overlay)
i18n.ts + translations              → @serenada/react-ui: English only; host provides other locales

utils/pushCrypto.ts                 → stays in host app
utils/callHistory.ts                → stays in host app
utils/savedRooms.ts                 → stays in host app
pages/Home.tsx                      → stays in host app
components/RecentCalls.tsx          → stays in host app
components/SavedRooms.tsx           → stays in host app
components/SavedRoomDialog.tsx      → stays in host app
public/sw.js                        → stays in host app
App.tsx, main.tsx                   → stays in host app
```

Use npm workspaces (`client/packages/core`, `client/packages/react-ui`) to develop alongside the host app.

---

## Implementation Plan

### Phase 0: Pre-work

**Goal**: Prepare the ground without changing any behavior.

#### CallManager Audit

- [x] Read `CallManager.swift` (iOS) — list every public method and property
- [x] Read `CallManager.kt` (Android) — list every public method and property
- [x] Read `SignalingContext.tsx` + `WebRTCContext.tsx` (Web) — list every exported function/hook
- [x] Classify each method as **SDK** (signaling, media, call state, TURN, reconnection, room creation) or **host** (push sync, saved rooms, recent calls, settings persistence, room-status watching, snapshot upload)
- [x] Document the classification in a shared spreadsheet or markdown table
- [x] Identify methods that straddle both categories and plan how to split them

#### Module Scaffolding — iOS

- [x] Create a `SerenadaCore` Swift Package directory under `client-ios/`
- [x] Add `SerenadaCore` as a local package product in `project.yml`
- [x] Create a `SerenadaCallUI` Swift Package directory under `client-ios/`
- [x] Add `SerenadaCallUI` as a local package product in `project.yml` with dependency on `SerenadaCore`
- [x] Add `WebRTC.xcframework` as a binary target in the `SerenadaCore` package
- [x] Update the app target in `project.yml` to depend on both local packages
- [x] Run `xcodegen generate` and verify the project opens cleanly
- [x] Build the app — should compile with empty library modules

#### Module Scaffolding — Android

- [x] Create `:serenada-core` Android library module directory
- [x] Create `serenada-core/build.gradle.kts` with library plugin and WebRTC AAR dependency
- [x] Create `:serenada-call-ui` Android library module directory
- [x] Create `serenada-call-ui/build.gradle.kts` with library plugin and dependency on `:serenada-core`
- [x] Update `settings.gradle.kts` to include both new modules
- [x] Update `:app` module to depend on `:serenada-core` and `:serenada-call-ui`
- [x] Run `./gradlew assembleDebug` — should compile with empty library modules

#### Module Scaffolding — Web

- [x] Create `client/packages/core/` directory with `package.json` and `tsconfig.json`
- [x] Create `client/packages/react-ui/` directory with `package.json` and `tsconfig.json`
- [x] Configure npm workspaces in the root `client/package.json`
- [x] Add `@serenada/core` as dependency of `@serenada/react-ui`
- [x] Add both as dependencies of the Serenada host app
- [x] Run `npm install` and `npm run build` — should succeed with empty packages
- [x] Run `npm run test` — existing tests should still pass

### Phase 1: Extract Core — iOS

**Why iOS first**: The folder structure (`Sources/Core/` vs `Sources/UI/`) already suggests the target modules. Least resistance.

#### Move files into SerenadaCore

- [x] Move `Sources/Core/Call/WebRtcEngine.swift` → `SerenadaCore/Sources/` (mark `internal`)
- [x] Move `Sources/Core/Call/PeerConnectionSlot.swift` → `SerenadaCore/Sources/` (mark `internal`)
- [x] Move `Sources/Core/Call/CallAudioSessionController.swift` → `SerenadaCore/Sources/` (mark `internal`)
- [x] Move `Sources/Core/Signaling/*` → `SerenadaCore/Sources/` (mark `internal`)
- [x] Move only SDK-native models into `SerenadaCore/Sources/` (mark `public`) — do NOT publish current UI-flavored state objects as-is
- [x] Split `Sources/Core/Networking/APIClient.swift` into:
  - [x] call-only client (TURN + room creation + signaling probe for diagnostics) → `SerenadaCore/Sources/`
  - [x] host-only client (push / invite / snapshot / analytics) → app target
- [x] Move `Sources/Core/Utils/DeepLinkParser.swift` → `SerenadaCore/Sources/` (mark `public`)
- [x] Move `Sources/Core/Layout/ComputeLayout.swift` → `SerenadaCore/Sources/` (mark `public`)
- [x] Fix all import paths in moved files
- [x] Build — should compile

#### Split CallManager.swift

- [x] Create `SerenadaSession.swift` in `SerenadaCore/Sources/` as a `public class`
- [x] Define a new public `CallState` struct with SDK-native fields only (do not reuse current app `CallUiState`)
- [x] Move signaling, WebRTC orchestration, media control, and reconnection logic from `CallManager` into internal classes that `SerenadaSession` delegates to
- [x] Expose public methods on `SerenadaSession`: `leave()`, `end()`, `toggleAudio()`, `toggleVideo()`, `flipCamera()`, `setCameraMode()`, `startScreenShare()`, `stopScreenShare()`
- [x] Expose `resumeJoin()` and `cancelJoin()` for blocked preconditions such as permissions
- [x] Expose renderer attachment: `attachLocalRenderer()`, `attachRemoteRenderer()`
- [x] Expose `@Published var state: CallState` for observation
- [x] Strip host-app concerns out of the session engine:
  - [x] Remove saved rooms logic → move to host app
  - [x] Remove recent calls logic → move to host app
  - [x] Remove settings persistence → move to host app
  - [x] Remove push subscription sync → move to host app
  - [x] Remove room-status watching → move to host app
  - [x] Remove snapshot upload → move to host app
- [x] Build — should compile

#### Create SerenadaCore entry point

- [x] Create `SerenadaCore.swift` with `public init(config: SerenadaConfig)`
- [x] Define `SerenadaConfig` struct: `serverHost`, `defaultAudioEnabled`, `defaultVideoEnabled`, `transports`
- [x] Implement `public func join(url: URL) -> SerenadaSession`
- [x] Implement `public func join(roomId: String) -> SerenadaSession`
- [x] Implement `public func createRoom(completion: (CreateRoomResult) -> Void)`
- [x] Define `SerenadaCoreDelegate` protocol:
  - [x] `sessionRequiresPermissions` callback (optional — core pauses join until host or call-ui resumes)
  - [x] `sessionDidChangeState` callback
  - [x] `sessionDidEnd` callback
- [x] Implement permission preflight in `join()` — detect missing camera/mic, set `state.phase = .awaitingPermissions`, populate `requiredPermissions`, and invoke delegate if set
- [x] Create `SerenadaDiagnostics` preflight utility:
  - [x] Implement `runAll(completion:)` — runs all checks, returns `DiagnosticsReport`
  - [x] Implement individual checks: `checkCamera()`, `checkMicrophone()`, `checkSpeaker()`, `checkNetwork()`, `checkSignaling()`, `checkTurn()`
  - [x] Define `DiagnosticsReport` struct with structured results per check
  - [x] Define result enums: `.available`, `.unavailable(reason)`, `.notAuthorized`, `.skipped(reason)`
  - [x] Implement device enumeration (cameras, microphones) via WebRTC APIs
  - [x] Enforce no-prompts contract: camera/mic checks must return `.notAuthorized` if permission not granted, never trigger OS prompt
  - [x] Signaling probe uses core's call-only client (same endpoint used by `join()`)
  - [x] TURN probe uses core's TURN credential fetch
- [x] Add `callStats: CallStats` observable on `SerenadaSession`:
  - [x] Define `CallStats` struct: bitrate, packet loss, jitter, codec, ICE candidate pair, round-trip time
  - [x] Populate from WebRTC `getStats()` on a periodic interval during active call
  - [x] Expose as `@Published` for observation
- [x] Build — should compile

#### Rewire iOS host app

- [x] Update the Serenada iOS app to `import SerenadaCore`
- [x] Replace direct `CallManager` usage with `SerenadaCore` / `SerenadaSession` API
- [x] Move push subscription logic into the app target (calling through session state observation)
- [x] Move saved rooms / recent calls management into the app target
- [x] Move settings persistence into the app target
- [x] Verify `Sources/Core/Push/*` stays in the app target and does NOT import from `SerenadaCore` internals

#### Verify

- [x] App builds with `xcodegen generate && xcodebuild`
- [x] App runs and behaves identically to before the refactor
- [x] Run existing test suite — all tests pass
- [x] Grep `SerenadaCore` sources for any host-app references (push, saved rooms, settings) — zero results
- [x] Verify `SerenadaCore` has no UIKit/SwiftUI imports

### Phase 2: Extract Core — Web

**Why web second**: Proves the API shape works framework-agnostically. Heaviest refactor (React → vanilla TS).

#### Create headless session engine

- [x] Create `packages/core/src/SerenadaSession.ts` class
- [x] Extract signaling transport logic from `SignalingContext.tsx` into `packages/core/src/signaling/` as vanilla TS classes
- [x] Extract WS transport from `contexts/signaling/transports/` → `packages/core/src/signaling/transports/`
- [x] Extract SSE transport → `packages/core/src/signaling/transports/`
- [x] Extract WebRTC peer connection logic from `WebRTCContext.tsx` into `packages/core/src/media/` as vanilla TS class
- [x] Extract reconnection and resilience logic from `constants/webrtcResilience.ts` → `packages/core/src/media/`
- [x] Extract `localVideoRecovery.ts` → `packages/core/src/media/`
- [x] Move `layout/computeLayout.ts` → `packages/core/src/layout/`
- [x] Move call-relevant parts of `utils/roomApi.ts` → `packages/core/src/api/` (TURN + room creation + signaling probe for diagnostics)
- [x] Keep push / invite / snapshot / analytics endpoints in host app
- [x] Implement `SerenadaSession` with: `subscribe(callback)`, `join()`, `leave()`, `end()`, `toggleAudio()`, `toggleVideo()`, `flipCamera()`, media stream getters
- [x] Implement `createSerenadaCore()` factory with `SerenadaConfig`
- [x] Implement `createRoom()` convenience method
- [x] Implement `onPermissionsRequired` callback on `SerenadaSession`
- [x] Implement permission preflight in `join()` — detect missing camera/mic, set `phase = 'awaitingPermissions'`, expose `requiredPermissions`, and pause
- [x] Create `createSerenadaDiagnostics()` factory:
  - [x] Implement `runAll()` — async, returns `DiagnosticsReport`, never prompts
  - [x] Implement individual checks: `checkCamera()`, `checkMicrophone()`, `checkNetwork()`, `checkSignaling()`, `checkTurn()`
  - [x] Device enumeration via `navigator.mediaDevices.enumerateDevices()`
  - [x] Camera/mic checks return `notAuthorized` if permission not granted — no `getUserMedia()` calls
  - [x] Signaling/TURN probes use core's call-only client
- [x] Add `callStats` observable on `SerenadaSession`:
  - [x] Define `CallStats` type: bitrate, packet loss, jitter, codec, ICE candidate pair, round-trip time
  - [x] Populate from `RTCPeerConnection.getStats()` periodically during active call
- [x] Export public types: `CallState`, `Participant`, `CallPhase`, `CallError`, `DiagnosticsReport`, `CallStats`
- [x] Build `@serenada/core` — should compile
- [x] Verify zero React/ReactDOM imports in `@serenada/core`

#### Create React bindings

- [x] Create `packages/react-ui/src/hooks/useSerenadaSession.ts` — wraps `SerenadaSession` for React state
- [x] Create `packages/react-ui/src/hooks/useCallState.ts` — subscribes to `CallState` changes
- [x] Verify hooks re-render correctly on state changes

#### Rewire web host app

- [x] Update `CallRoom.tsx` to use `useSerenadaSession()` + `useCallState()` instead of raw contexts
- [x] Keep push subscription, saved rooms, recent calls, room creation UX in the host app
- [x] Update `SignalingContext.tsx` and `WebRTCContext.tsx` to be thin wrappers around `@serenada/core` (or remove if fully replaced)

#### Verify

- [x] Run `npm run build` — all packages compile
- [x] Run `npm run dev` — app works identically
- [x] Run `npm run test` — all Vitest tests pass
- [x] Run `npm run lint` — no new lint errors
- [x] Verify `packages/core/` has zero `react` or `react-dom` imports

### Phase 3: Extract Core — Android

#### Move files into :serenada-core

- [x] Move `call/WebRtcEngine.kt` → `:serenada-core` (mark `internal`)
- [x] Move `call/SignalingClient.kt` → `:serenada-core` (mark `internal`)
- [x] Move `call/CompositeCameraCapturer.kt` → `:serenada-core` (mark `internal`)
- [x] Move `call/PeerConnectionSlot.kt` → `:serenada-core` (mark `internal`)
- [x] Split `network/ApiClient.kt` into:
  - [x] call-only client (TURN + room creation + signaling probe for diagnostics) → `:serenada-core`
  - [x] host-only client (push / invite / snapshot / analytics) → `:app`
- [x] Move `layout/ComputeLayout.kt` → `:serenada-core` (mark `public`)
- [x] Move signaling-related models → `:serenada-core` (mark `public` where needed)
- [x] Fix all import paths
- [x] Build — should compile

#### Split CallManager.kt

- [x] Create `SerenadaSession.kt` in `:serenada-core` as a public class
- [x] Define a new `CallState` data class with SDK-native fields only (do not reuse current app `CallUiState`)
- [x] Expose `StateFlow<CallState>` for observation
- [x] Move signaling, WebRTC orchestration, media control, and reconnection logic from `CallManager` into internal classes
- [x] Expose public methods: `leave()`, `end()`, `toggleAudio()`, `toggleVideo()`, `flipCamera()`, `setCameraMode()`, `startScreenShare()`, `stopScreenShare()`
- [x] Expose `resumeJoin()` and `cancelJoin()`
- [x] Expose renderer attachment: `attachLocalRenderer()`, `attachRemoteRenderer()`
- [x] Strip host-app concerns:
  - [x] Remove saved rooms logic → move to `:app`
  - [x] Remove recent calls logic → move to `:app`
  - [x] Remove settings persistence → move to `:app`
  - [x] Remove push subscription sync → move to `:app`
  - [x] Remove room-status watching → move to `:app`
  - [x] Remove snapshot upload → move to `:app`
- [x] Build — should compile

#### Create SerenadaCore entry point

- [x] Create `SerenadaCore.kt` with `SerenadaConfig` constructor
- [x] Implement `fun join(url: String): SerenadaSession`
- [x] Implement `fun join(roomId: String): SerenadaSession`
- [x] Implement `fun createRoom(callback: (CreateRoomResult) -> Unit)`
- [x] Define `SerenadaCoreDelegate` interface:
  - [x] `onPermissionsRequired` callback (optional — core pauses join until host or call-ui resumes)
  - [x] `onSessionStateChanged` callback
  - [x] `onSessionEnded` callback
- [x] Implement permission preflight in `join()` — detect missing camera/mic, set `CallState.phase = AwaitingPermissions`, expose `requiredPermissions`, and pause
- [x] Create `SerenadaDiagnostics` preflight utility:
  - [x] Implement `suspend fun runAll(): DiagnosticsReport` — never prompts
  - [x] Implement individual checks: `checkCamera()`, `checkMicrophone()`, `checkSpeaker()`, `checkNetwork()`, `checkSignaling()`, `checkTurn()`
  - [x] Device enumeration via WebRTC APIs
  - [x] Camera/mic checks return `NotAuthorized` if permission not granted — no runtime permission requests
  - [x] Signaling/TURN probes use core's call-only client
- [x] Add `callStats: StateFlow<CallStats>` on `SerenadaSession`:
  - [x] Define `CallStats` data class: bitrate, packet loss, jitter, codec, ICE candidate pair, round-trip time
  - [x] Populate from WebRTC `getStats()` periodically during active call
- [x] Build — should compile

#### Rewire Android host app

- [x] Update `:app` to import from `:serenada-core`
- [x] Replace direct `CallManager` usage with `SerenadaCore` / `SerenadaSession`
- [x] Wire foreground service to session state:
  ```kotlin
  session.state.collect { state ->
      when (state.phase) {
          CallPhase.InCall -> startForegroundService()
          CallPhase.Idle -> stopForegroundService()
          else -> {}
      }
  }
  ```
- [x] Move push subscription logic into `:app`
- [x] Move saved rooms / recent calls into `:app`
- [x] Move settings persistence into `:app`

#### Verify

- [x] Run `./gradlew assembleDebug` — compiles
- [x] Install and run on device — app works identically (build verified; manual device test pending)
- [x] Run `./gradlew test` — all tests pass
- [x] Grep `:serenada-core` sources for host-app references — zero results

### Phase 4: Build Call-UI Libraries

With core extracted and stable on all platforms, build the call-ui layer.

#### iOS — SerenadaCallUI

- [x] Move `Sources/UI/Screens/CallScreen.swift` → `SerenadaCallUI/Sources/`
- [x] Move `Sources/UI/Components/WebRTCVideoView.swift` → `SerenadaCallUI/Sources/`
- [x] Move other call-related UI components → `SerenadaCallUI/Sources/`
- [x] Create `SerenadaCallFlow.swift` SwiftUI view:
  - [x] Accept `url: URL` or `session: SerenadaSession`
  - [x] Accept `config: SerenadaCallFlowConfig` (feature toggles)
  - [x] Accept `strings: [SerenadaString: String]` (optional overrides)
  - [x] Accept `onDismiss` callback
  - [x] Implement state-driven flow: awaiting permissions → joining → waiting → in-call → error → ended
- [x] Create `SerenadaPermissions` helper in `SerenadaCallUI`
- [x] In URL-first mode, automatically prompt via `SerenadaPermissions` when session enters `awaitingPermissions`
- [x] In session-first mode, expose `SerenadaPermissions` for host apps to call from `sessionRequiresPermissions`
- [x] Define `SerenadaCallFlowConfig`:
  - [x] `screenSharingEnabled: Bool` (default `true`)
  - [x] `inviteControlsEnabled: Bool` (default `true`)
  - [x] `debugOverlayEnabled: Bool` (default `false`)
- [x] Define `SerenadaString` enum with all user-facing string keys
- [x] Bundle default English strings
- [x] Implement `.serenadaTheme()` view modifier
- [x] Implement feature toggle logic — hide controls when disabled
- [x] Build — should compile

#### Android — serenada-call-ui

- [x] Move `ui/CallScreen.kt` → `:serenada-call-ui`
- [x] Move `ui/Theme.kt` → `:serenada-call-ui`
- [x] Break `CallScreen.kt` into sub-composables: `ParticipantGrid`, `ControlBar`, `StatusOverlay`
- [x] Create `SerenadaCallFlow` composable:
  - [x] Accept `url: String` or `session: SerenadaSession`
  - [x] Accept `config: SerenadaCallFlowConfig` (feature toggles)
  - [x] Accept `strings: Map<SerenadaString, String>` (optional overrides)
  - [x] Accept `onDismiss` callback
  - [x] Implement state-driven flow, including `AwaitingPermissions`
- [x] Create `SerenadaPermissions` helper in `:serenada-call-ui`
- [x] In URL-first mode, automatically prompt via `SerenadaPermissions` when session enters `AwaitingPermissions`
- [x] In session-first mode, expose `SerenadaPermissions` for host apps to call from `onPermissionsRequired`
- [x] Define `SerenadaCallFlowConfig` data class with same fields as iOS
- [x] Define `SerenadaString` enum
- [x] Bundle default English string resources
- [x] Implement theme customization API
- [x] Implement feature toggle logic — hide controls when disabled
- [x] Move English-only i18n strings from `:app` into `:serenada-call-ui`
- [x] Build — should compile

#### Web — @serenada/react-ui

- [x] Extract call rendering from `CallRoom.tsx` into `packages/react-ui/src/`
- [x] Break into sub-components: `ParticipantGrid`, `ControlBar`, `StatusOverlay`, `DebugPanel`
- [x] Create `<SerenadaCallFlow>` React component:
  - [x] Accept `url` or `session` prop
  - [x] Accept `config` prop (feature toggles)
  - [x] Accept `strings` prop (optional overrides)
  - [x] Accept `onDismiss` callback
  - [x] Implement state-driven flow, including `awaitingPermissions`
- [x] Export `SerenadaPermissions.request()` helper from `@serenada/react-ui`
- [x] In URL-first mode, automatically prompt via `SerenadaPermissions` when session enters `awaitingPermissions`
- [x] In session-first mode, expose `SerenadaPermissions` for host apps to call from `onPermissionsRequired`
- [x] Define `SerenadaCallFlowConfig` type with same fields
- [x] Define string key types
- [x] Bundle default English strings
- [x] Export `useSerenadaSession()` hook
- [x] Implement theme/config props
- [x] Implement feature toggle logic — hide controls when disabled
- [x] Extract English-only i18n strings; leave other locales in host app
- [x] Build — should compile

#### Verify all platforms

- [x] iOS: Serenada app works identically using `SerenadaCallFlow`
- [x] Android: Serenada app works identically using `SerenadaCallFlow`
- [x] Web: Serenada app works identically using `<SerenadaCallFlow>`
- [x] Test feature toggles: set `screenSharingEnabled: false` — screen share button hidden
- [x] Test feature toggles: set `inviteControlsEnabled: false` — QR/invite buttons hidden
- [x] Test string overrides: provide a non-English string map — UI renders overridden strings
- [x] Test core-only integration (without call-ui) still works on at least one platform

### Phase 5: Rewire Serenada Apps as Host Apps

Final cleanup to prove the SDK boundary is real.

- [x] Serenada iOS app uses `SerenadaCallFlow` for all call presentation
- [x] Serenada Android app uses `SerenadaCallFlow` for all call presentation
- [x] Serenada web app uses `<SerenadaCallFlow>` for all call presentation
- [x] Serenada apps pass their own locale strings via the strings config (ru, es, fr)
- [x] Serenada apps set feature toggles as appropriate (all enabled for first-party)
- [x] No host-app concerns remain in any SDK module
- [x] No SDK modules reference host-app code
- [x] All push, persistence, room management, and settings remain in host apps
- [x] All tests pass on all platforms

### Phase 6: Publish & Document

- [x] Package `SerenadaCore` for external distribution via SPM Git URL
- [x] Package `SerenadaCallUI` for external distribution via SPM Git URL
- [x] Package `app.serenada:core` for Maven Central or GitHub Packages
- [x] Package `app.serenada:call-ui` for Maven Central or GitHub Packages
- [x] Publish `@serenada/core` to npm registry
- [x] Publish `@serenada/react-ui` to npm registry
- [x] Write integration guide: iOS quick start
- [x] Write integration guide: Android quick start
- [x] Write integration guide: Web quick start
- [x] Document `SerenadaCallFlowConfig` feature toggles
- [x] Document string override mechanism with examples per platform
- [x] Document theming API per platform
- [x] Create sample iOS host app (bare-bones: receives URL, shows `SerenadaCallFlow`)
- [x] Create sample Android host app
- [x] Create sample Web host app
- [x] Generate API reference docs from source (Swift DocC / Dokka / TypeDoc)

---

## Anti-patterns to Avoid

- Do not put push notifications in core
- Do not put recent calls / saved rooms in core
- Do not expose `CallManager` classes as-is as the public API
- Do not make core persist global host settings from incoming URLs
- Do not make call-ui depend on the full Serenada app shell
- Do not make core depend on React (web) — keep it vanilla TypeScript
- Do not use Activity / UIViewController as the call-ui entry point — use composable / view
- Do not add i18n display strings to core — expose structured enums, let call-ui localize
- Do not bundle non-English strings in call-ui — host app provides additional locales
- Do not require host apps to subclass SDK types — use composition and delegates
- Do not hard-code call-ui features — use feature toggles so host apps can hide what they don't need
- Do not show OS permission UI directly from core — signal blocked state, let call-ui or host prompt

---

## Minimal Integration Examples

### iOS
```swift
import SerenadaCore
import SerenadaCallUI

let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

func handleURL(_ url: URL) {
    presentFullScreen {
        SerenadaCallFlow(url: url, onDismiss: { dismiss() })
    }
}

// With feature toggles and localization
func handleURLCustomized(_ url: URL) {
    presentFullScreen {
        SerenadaCallFlow(
            url: url,
            config: .init(screenSharingEnabled: false, inviteControlsEnabled: false),
            strings: [.waitingForOther: "Ожидание..."],
            onDismiss: { dismiss() }
        )
    }
}

// Create a room and join it
func startNewCall() {
    serenada.createRoom { result in
        let shareURL = result.url  // send to the other party
        presentFullScreen {
            SerenadaCallFlow(session: result.session, onDismiss: { dismiss() })
        }
    }
}
```

### Android
```kotlin
import app.serenada.core.SerenadaCore
import app.serenada.callui.SerenadaCallFlow

val serenada = SerenadaCore(config = SerenadaConfig(serverHost = "serenada.app"))

fun handleDeepLink(uri: Uri) {
    SerenadaCallFlow(url = uri.toString(), onDismiss = { navController.popBackStack() })
}

// With feature toggles
fun handleDeepLinkCustomized(uri: Uri) {
    SerenadaCallFlow(
        url = uri.toString(),
        config = SerenadaCallFlowConfig(
            screenSharingEnabled = false,
            inviteControlsEnabled = false
        ),
        onDismiss = { navController.popBackStack() }
    )
}
```

### Web
```tsx
import { createSerenadaCore } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

function CallPage() {
    const { roomId } = useParams()
    return (
        <SerenadaCallFlow
            url={`https://serenada.app/call/${roomId}`}
            onDismiss={() => navigate('/')}
        />
    )
}

// With feature toggles and localization
function CallPageCustomized() {
    const { roomId } = useParams()
    return (
        <SerenadaCallFlow
            url={`https://serenada.app/call/${roomId}`}
            config={{ screenSharingEnabled: false, inviteControlsEnabled: false }}
            strings={{ waitingForOther: 'En attente...' }}
            onDismiss={() => navigate('/')}
        />
    )
}
```

---

## Success Criteria

The SDK extraction is complete when:

1. A new iOS/Android/Web app can join a Serenada call with < 10 lines of integration code
2. The Serenada apps work identically to today, consuming their own SDK
3. Core has zero references to push, persistence, room management, or settings
4. Core (web) has zero React imports
5. call-ui works with any host app navigation stack (no forced Activity/ViewController)
6. A sample host app exists per platform demonstrating the minimal integration
