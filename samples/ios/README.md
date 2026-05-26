# Serenada iOS Sample App

Minimal iOS host app demonstrating Serenada SDK integration with SwiftUI.

## What it does

- Accepts a call URL and presents `SerenadaCallFlow` using built-in Serenada signaling
- Creates a new room via `SerenadaCore.createRoom()` and joins explicitly with `join()`
- Starts a provider-mode demo backed by a local in-memory `SignalingProvider`
- Shows incremental `peerJoined` events and peer-message delivery without Serenada transport
- Demonstrates injecting a custom `SerenadaAudioCoordinator` for host-owned audio policy
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

The simulator is enough to verify project setup and call flow wiring. Use a physical device to validate camera and microphone behavior.
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
SerenadaCallFlow(url: callURL, config: .init(screenSharingEnabled: false, inviteControlsEnabled: false))

// 2. Create a room, then join explicitly.
Task {
    let room = try await serenada.createRoom()
    let session = serenada.join(url: room.url)
    SerenadaCallFlow(session: session, config: .init(screenSharingEnabled: false, inviteControlsEnabled: false))
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

The sample also includes `SampleAudioCoordinator`, which implements `SerenadaAudioCoordinator` and is passed through `SerenadaConfig.audioCoordinator`. Real host apps can use the same protocol to own `AVAudioSession`, route selection, and external-audio coexistence policy. Omit `audioCoordinator` to use the SDK's internal default coordinator.
