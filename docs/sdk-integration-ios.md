# Serenada SDK — iOS Quick Start

## Requirements

- iOS 16.0+
- Swift 5.10+
- Xcode 15+

## Installation

### Swift Package Manager

Add both packages to your Xcode project or `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/AleGavrilov/serenada-ios-core.git", from: "0.1.0"),
    .package(url: "https://github.com/AleGavrilov/serenada-ios-callui.git", from: "0.1.0"),
]
```

Or in Xcode: File → Add Package Dependencies → paste the repository URL.

For local development within the Serenada monorepo, use path references:

```yaml
# project.yml (XcodeGen)
packages:
  SerenadaCore:
    path: SerenadaCore
  SerenadaCallUI:
    path: SerenadaCallUI
```

## Quick Start — URL-First (Simplest)

```swift
import SerenadaCore
import SerenadaCallUI

struct CallView: View {
    let url: URL

    var body: some View {
        SerenadaCallFlow(url: url, onDismiss: { dismiss() })
    }
}
```

That's it. `SerenadaCallFlow` handles permissions, joining, the in-call UI, and cleanup.

## Session-First (Pre-Observation)

Create a session before presenting UI to observe state early:

```swift
import SerenadaCore
import SerenadaCallUI

let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

func handleDeepLink(_ url: URL) {
    let session = serenada.join(url: url)
    // Observe session.state before showing UI if needed

    presentFullScreen {
        SerenadaCallFlow(session: session, onDismiss: { dismiss() })
            .serenadaTheme(.init(accentColor: .blue))
    }
}
```

## Create a Room

```swift
serenada.createRoom { result in
    switch result {
    case .success(let room):
        let shareURL = room.url  // send to the other party
        presentFullScreen {
            SerenadaCallFlow(session: room.session, onDismiss: { dismiss() })
        }
    case .failure(let error):
        print("Failed: \(error)")
    }
}
```

## Core-Only Integration (No UI)

Use `SerenadaCore` directly for a fully custom UI:

```swift
let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))
let session = serenada.join(url: url)

// Observe state
session.$state.sink { state in
    switch state.phase {
    case .idle: break
    case .awaitingPermissions:
        // Prompt for permissions, then call session.resumeJoin()
        break
    case .joining: showSpinner()
    case .waiting: showWaitingScreen()
    case .inCall: showCallScreen()
    case .ending: showEndingScreen()
    case .error: showError(state.error)
    }
}

// Media controls
session.toggleAudio()
session.toggleVideo()
session.flipCamera()

// Video rendering
session.attachLocalRenderer(localVideoView)
session.attachRemoteRenderer(remoteVideoView, forParticipant: cid)

// Leave or end
session.leave()   // local exit, room stays open
session.end()     // terminates room for all
```

## Permissions Handling

In URL-first mode, `SerenadaCallFlow` automatically prompts for camera/microphone permissions.

In session-first or core-only mode, handle the `awaitingPermissions` phase:

```swift
session.$state.sink { state in
    if state.phase == .awaitingPermissions {
        SerenadaPermissions.request(state.requiredPermissions ?? []) { granted in
            if granted {
                session.resumeJoin()
            } else {
                session.cancelJoin()
            }
        }
    }
}
```

## Preflight Diagnostics

Run device and network checks before a call:

```swift
let diagnostics = SerenadaDiagnostics(config: serenadaConfig)
diagnostics.runAll { report in
    report.camera       // .available | .unavailable(reason) | .notAuthorized
    report.microphone   // .available | .unavailable(reason) | .notAuthorized
    report.speaker      // .available | .unavailable(reason)
    report.network      // .reachable | .unreachable(reason) | .skipped(reason)
    report.signaling    // .connected(transport:) | .failed(reason)
    report.turn         // .reachable(latencyMs:) | .unreachable(reason)
    report.devices      // [DeviceInfo]
}
```

### Connectivity Checks

Test individual server endpoints (Room API, WebSocket, SSE, diagnostic token, TURN credentials):

```swift
let report = await diagnostics.runConnectivityChecks()
// report.roomApi, .webSocket, .sse, .diagnosticToken, .turnCredentials
// Each is a CheckOutcome: .notRun | .passed(latencyMs:) | .failed(error:)
```

### ICE Probing

Verify STUN/TURN connectivity with a real WebRTC ICE gathering probe:

```swift
let iceReport = await diagnostics.runIceProbe(turnsOnly: false) { candidate in
    print("ICE candidate: \(candidate)")
}
// iceReport.stunPassed, .turnPassed, .logs
```

### Server Validation

Validate that a host is a reachable Serenada server:

```swift
try await diagnostics.validateServerHost()
```

Diagnostics never trigger OS permission prompts — if a permission is missing, the check returns `.notAuthorized`.

## Room Watching

Monitor occupancy of saved/recent rooms without joining:

```swift
let watcher = RoomWatcher()
watcher.delegate = self
watcher.watchRooms(roomIds: ["room1", "room2"], host: "serenada.app")
// watcher.currentStatuses → [String: RoomOccupancy]

// RoomWatcherDelegate
func roomWatcher(_ watcher: RoomWatcher, didUpdateStatuses statuses: [String: RoomOccupancy]) {
    // statuses["room1"]?.count, .maxParticipants
}
```

## Configuration

```swift
let config = SerenadaConfig(
    serverHost: "serenada.app",       // required
    defaultAudioEnabled: true,        // mic on at join (default)
    defaultVideoEnabled: true,        // camera on at join (default)
    transports: [.ws, .sse]           // transport priority (default)
)
```

## Next Steps

- [Feature Toggles, String Overrides & Theming](sdk-customization.md)
- [API Reference](#) — generate with `swift package generate-documentation`
