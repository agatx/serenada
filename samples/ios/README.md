# Serenada iOS Sample App

Minimal iOS host app demonstrating Serenada SDK integration with SwiftUI.

## What it does

- Accepts a call URL and presents `SerenadaCallFlow`
- Creates a new room via `SerenadaCore.createRoom()`
- Reuses the `SerenadaSession` returned by `createRoom()` so the sample does not double-join
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

// 2. When you create a room, reuse the returned session.
Task {
    let room = try await serenada.createRoom()
    SerenadaCallFlow(session: room.session, config: .init(screenSharingEnabled: false, inviteControlsEnabled: false))
}
```
