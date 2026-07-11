# Serenada iOS Sample App

Minimal iOS host app demonstrating Serenada SDK integration with SwiftUI.

## What it does

- Accepts a call URL, creates a session, and presents `SerenadaCallFlow` using built-in Serenada signaling
- Creates a new room via `SerenadaCore.createRoom()` and joins explicitly with `join()`
- Manages several concurrent calls with `SerenadaCallRegistry` (multi-call demo: join held/foreground, switch, hold, leave, end)
- Starts a provider-mode demo backed by a local in-memory `SignalingProvider`
- Shows incremental `peerJoined` events and peer-message delivery without Serenada transport
- Demonstrates injecting a custom `SerenadaAudioCoordinator` for host-owned audio policy
- Enables system Picture in Picture so the sample covers foreground return from PiP
- Runs as a standalone XcodeGen app inside this repository
- Resolves `SerenadaCore` and `SerenadaCallUI` directly from local source in `client-ios/`

The sample intentionally hides screen sharing and waiting-room invite actions. Those features depend on first-party app wiring such as the Broadcast Upload extension and push notification plumbing, which are outside the scope of a minimal SDK host sample.

## Run in this repo

```bash
cd samples/ios
open SerenadaiOSSample.xcodeproj
```

Or build from the command line:

```bash
cd samples/ios
xcodebuild \
  -project SerenadaiOSSample.xcodeproj \
  -scheme SerenadaiOSSample \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

The simulator is enough to verify project setup and call flow wiring. Use a physical device to validate camera, microphone, and system Picture in Picture behavior.
For physical-device runs, set your Apple development team in Xcode signing settings first.

If you change [project.yml](project.yml), regenerate the checked-in project with:

```bash
cd samples/ios
xcodegen generate
```

## Standalone setup outside this repo

If you want to copy the sample into another project instead of using the repo-local packages, vendor or clone [agatx/serenada](https://github.com/agatx/serenada) and reference the iOS packages by local path:

```swift
dependencies: [
    .package(path: "../serenada/client-ios/SerenadaCore"),
    .package(path: "../serenada/client-ios/SerenadaCallUI"),
]
```

There is not currently a separate public Git URL for each iOS package.

## Integration pattern

```swift
import SerenadaCallUI
import SerenadaCore

let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

// 1. Join an existing invite link.
let session = serenada.join(url: callURL)
SerenadaCallFlow(
    session: session,
    config: .init(screenSharingEnabled: false, inviteControlsEnabled: false),
    onEndCall: {
        session.leave()
        dismiss()
    },
    onDismiss: { dismiss() }
)

// 2. Create a room, then join explicitly.
Task {
    let room = try await serenada.createRoom()
    let roomSession = serenada.join(url: room.url)
    SerenadaCallFlow(session: roomSession, config: .init(screenSharingEnabled: false, inviteControlsEnabled: false))
}
```

Provider mode uses the same SDK with an injected provider instead of `serverHost`:

```swift
let provider = SampleMockSignalingProvider()
let providerCore = SerenadaCore(config: .init(signalingProvider: provider))
let session = providerCore.join(roomId: "provider-demo-room")
let unsubscribe = session.onPeerMessage { message in
    print("provider message: \(message.type)")
}
```

A `SignalingProvider` is **single-session** (v1): one call may use it at a time. A second concurrent join on the same provider (direct, or a second `SerenadaCallRegistry` call) fails with `CallError.providerUnavailable` rather than clobbering the live session. For multi-call over a custom service (registry hold + switch), implement `MultiSessionSignalingProvider` (v2) and pass it as `multiSessionSignalingProvider`. It vends one `SignalingProvider` channel per session (`openSession(roomId:)`), each permanently bound to one room, so concurrent sessions never cross-wire CIDs or events. Provide exactly one of `serverHost`, `signalingProvider`, or `multiSessionSignalingProvider`.

The sample also includes `SampleAudioCoordinator`, which implements `SerenadaAudioCoordinator` and is passed through `SerenadaConfig.audioCoordinator`. Real host apps can use the same protocol to own `AVAudioSession`, route selection, and external-audio coexistence policy. Omit `audioCoordinator` to use the SDK's internal default coordinator.

## Multiple calls

`MultiCallSampleView` ([SampleApp/MultiCallSampleView.swift](SampleApp/MultiCallSampleView.swift)) shows how to manage several concurrent calls with `SerenadaCallRegistry`. The registry owns the single process-wide foreground media lease, so exactly one call is foreground at a time and the rest are held (connected, no capture).

```swift
import SerenadaCallUI
import SerenadaCore

// One registry per process, over a configured SerenadaCore.
let registry = SerenadaCallRegistry(core: SerenadaCore(config: .init(serverHost: "serenada.app")))

// Start a call in the foreground (join held, then switch to it).
switch await registry.joinAndSwitch(RoomRef(url: callURL, displayName: "Me")) {
case .active(let id):            break          // now foreground
case .needsPermission(let id):   break          // joined held; prompt, then switchToCall(id:)
case .failed(let id, let error): break          // inspect error / dismiss
}

// Start a second call WITHOUT taking the foreground.
_ = await registry.joinHeld(RoomRef(roomId: "second-room"))

// Render the active call. `activeCall?.session` is optional, so unwrap it.
if let session = registry.activeCall?.session {
    SerenadaCallFlow(session: session)
}

// Per-call control.
await registry.switchToCall(id: heldCallId)   // bring a held call forward
await registry.holdCall(id: activeCallId)     // hold the active call (NO auto-promote)
await registry.leaveCall(id: callId)          // leave for me
await registry.endCall(id: callId)            // end for everyone
await registry.dismissEndedCall(id: callId)   // drop an ended call's record
```

The registry is an `ObservableObject`; the demo re-derives its entire UI from the published `calls`, `activeCallId`, and `registryOperationInProgress` properties. Holding the only call clears `activeCallId` and shows the manager with the call still listed as held, the "no active call but held calls remain" state.

Only one `SerenadaCallRegistry` (or one direct `SerenadaCore.join()`) can own the process at a time. The demo runs over its own dedicated `SerenadaCore` and is launched in isolation from the single-call screens.
