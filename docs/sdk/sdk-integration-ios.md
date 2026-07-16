# Serenada SDK — iOS Quick Start

## Requirements

- iOS 16.0+
- Swift 5.10+
- Xcode 15+

## Installation

### Swift Package Manager

The iOS SDK ships as a single `Serenada` package whose manifest lives at the repo root and exposes the `SerenadaCore` and `SerenadaCallUI` products. Depend on it via the repo's Git URL:

```swift
dependencies: [
    .package(url: "https://github.com/agatx/serenada", branch: "main"),
]
```

or vendor/clone [agatx/serenada](https://github.com/agatx/serenada) and point Xcode or `Package.swift` at the repo root by local path:

```swift
dependencies: [
    .package(path: "../serenada"),
]
```

Then add the `SerenadaCore` and `SerenadaCallUI` products from that package to your target.

For local development within the Serenada monorepo, use a path reference to the repo root:

```yaml
# project.yml (XcodeGen)
packages:
  Serenada:
    path: <path to repo root>
targets:
  MyApp:
    dependencies:
      - package: Serenada
        product: SerenadaCore
      - package: Serenada
        product: SerenadaCallUI
```

When you construct `SerenadaConfig` directly, provide exactly one of `serverHost` or `signalingProvider`.

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

### Optional Frontline UI

iOS can opt into the frontline call layout while keeping the same call-flow API:

```swift
SerenadaCallFlow(
    url: url,
    config: SerenadaCallFlowConfig(
        uiVariant: .frontline,
        snapshotEnabled: true
    ),
    onDismiss: { dismiss() }
)
```

URL-first frontline calls start audio-first and use the camera order `world -> selfie -> composite`. For session-first usage, set `defaultVideoEnabled = false` and `cameraModes = [.world, .selfie, .composite]` on the `SerenadaConfig` used to create the session. When `frontline` is selected, iOS keeps Frontline styling for lifecycle states, 1:1 calls, and multi-party calls. The More sheet shows the current audio route first and opens the SDK route picker backed by `availableAudioDevices`, `currentAudioDevice`, and `selectAudioDevice(...)`; Phone is hidden while Bluetooth audio is present. Invite/share actions remain in the Frontline More sheet; the standard waiting-screen QR code is not shown.

### Optional System Picture in Picture

The prebuilt call UI can configure iOS video-call Picture in Picture for waiting and active calls. Host apps must include the background audio mode in their app `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
    <string>audio</string>
</array>
```

Then enable the call UI flag:

```swift
SerenadaCallFlow(
    url: url,
    config: SerenadaCallFlowConfig(systemPictureInPictureEnabled: true),
    onDismiss: { dismiss() }
)
```

When enabled on a supported iOS device, the SDK keeps the active large video feed or avatar visible in system PiP and lets the system return the user to the app. As the app returns to the foreground, the SDK stops the system PiP session and restores the inline call UI. The SDK enables multitasking camera access on its capture sessions when iOS reports support, so local camera video can continue in PiP on supported devices. iOS does not support custom in-window PiP actions for video calls, so End Call remains available only after returning to the app.

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
        SerenadaCallFlow(
            session: session,
            onEndCall: {
                session.leave()
                dismiss()
            },
            onDismiss: { dismiss() }
        )
            .serenadaTheme(.init(accentColor: .blue))
    }
}
```

If `onEndCall` is omitted, the prebuilt UI calls `session.leave()` before
dismissing. When you provide `onEndCall`, your host owns the end button behavior,
including leaving the session and updating navigation state.

## Create a Room

```swift
Task {
    do {
        let room = try await serenada.createRoom()
        let shareURL = room.url  // send to the other party
        let session = serenada.join(url: room.url)  // join explicitly
        presentFullScreen {
            SerenadaCallFlow(session: session, onDismiss: { dismiss() })
        }
    } catch {
        print("Failed: \(error)")
    }
}
```

`createRoom()` returns `CreateRoomResult(url, roomId)` only. It does not join the room or create a session. Call `join()` with the returned URL to start the call.

`createRoom()` is server mode only. In provider mode there is no Serenada room API, so join by your own room ID instead.

## Provider Mode (Custom Signaling)

Provider mode uses the same `SerenadaCore`, but you inject a `SignalingProvider` instead of `serverHost`:

```swift
final class DemoProvider: SignalingProvider {
    weak var delegate: SignalingProviderDelegate?

    func connect() {
        delegate?.signalingProviderDidConnect(ConnectionInfo(transport: "mock"))
    }

    func disconnect() {}

    func joinRoom(_ roomId: String, options: JoinOptions) {
        delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: "local-peer",
                participants: [SignalingProviderParticipant(peerId: "local-peer", joinedAt: 1)],
                hostPeerId: nil,
                maxParticipants: 4
            )
        )
    }

    func leaveRoom() {}
    func endRoom() {}
    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {}
    func broadcast(type: String, payload: SignalingPayload?) {}
    func getIceServers() async throws -> [IceServerConfig] { [] }
}

let serenada = SerenadaCore(config: .init(signalingProvider: DemoProvider()))
let session = serenada.join(roomId: "group-123")
```

Provider delegates may call back from any thread or actor. The session re-enters `MainActor` before it mutates SDK state, so adapter code does not need to add its own `MainActor.run` wrapper just to satisfy Serenada internals. Host-app calls into public SDK APIs should still stay on `MainActor`.

If your provider already owns reconnect behavior, set `ProviderCapabilities(handlesReconnection: true)`. Otherwise keep the default `false` and let the session rejoin with `reconnectPeerId`.

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

session.$diagnostics.sink { diagnostics in
    print("Transport:", diagnostics.activeTransport ?? "n/a")
    print("ICE:", diagnostics.iceConnectionState.rawValue)
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

`SerenadaSession` exposes two observable snapshots:
- `state` for app-facing lifecycle, participants, permissions, and errors
- `diagnostics` for transport state, low-level WebRTC state, stats, and feature degradation details

## Pluggable Audio Coordinators

By default, `SerenadaCore` manages `AVAudioSession`, route changes, and proximity behavior with an internal coordinator. Apps that already own process-wide audio state, such as apps with an existing audio engine, can inject a custom `SerenadaAudioCoordinator`:

```swift
let serenada = SerenadaCore(
    config: SerenadaConfig(
        serverHost: "serenada.app",
        audioCoordinator: MyAudioCoordinator(),
        audioIntent: AudioIntent(
            requiresCapture: true,
            requiresPlayback: true,
            muteDuringExternalAudio: true,
            duckDuringExternalAudio: true
        )
    )
)
```

Custom coordinators implement `SerenadaAudioCoordinator`. They activate and deactivate call audio, apply route selections, publish `availableDevices`, `effectiveInputDevice`, `effectiveOutputDevice`, and emit `AudioCoordinatorEvent.externalAudioStarted` / `externalAudioEnded` when host-owned audio should temporarily mute local capture or duck playback. For duck-only interruptions that should not mute capture, emit `playbackDuckingStarted` / `playbackDuckingEnded`.

The concrete default coordinator is internal SDK behavior, not a supported public class to instantiate. Leave `audioCoordinator` as `nil` to use it.

Custom UIs can observe and control the active coordinator through the session:

```swift
session.$availableAudioDevices.sink { devices in
    // Render route picker.
}

session.$isMicMutedByExternalAudio.sink { mutedByExternalAudio in
        // Show a distinct external-audio mute state if needed.
}

session.selectAudioDevice(device)
session.setMicMuted(true)
```

`isMicMuted` is the effective mute state: user mute, coordinator-driven external mute, and missing input route all count as muted. `isMicMutedByExternalAudio` isolates the coordinator-driven portion so the host can distinguish user mute from host-owned external audio.

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

In provider mode, `runAll()` still runs local device/network/TURN checks, but `report.signaling` is `.skipped(reason: "requires serverHost")`.

### Connectivity Checks

Test individual server endpoints (Room API, WebSocket, SSE, diagnostic token, TURN credentials):

```swift
let report = await diagnostics.runConnectivityChecks()
// report.roomApi, .webSocket, .sse, .diagnosticToken, .turnCredentials
// Each is a CheckOutcome: .notRun | .passed(latencyMs:) | .failed(error:)
```

`runConnectivityChecks()` requires `serverHost`.

### ICE Probing

Verify STUN/TURN connectivity with a real WebRTC ICE gathering probe:

```swift
let iceReport = await diagnostics.runIceProbe(turnsOnly: false) { candidate in
    print("ICE candidate: \(candidate)")
}
// iceReport.stunPassed, .turnPassed, .logs
```

`runTurnProbe()` is the primary TURN/STUN probe and `runIceProbe()` remains as the compatibility alias:

```swift
let turnReport = await diagnostics.runTurnProbe(turnsOnly: false, onCandidateLog: print)
```

### Server Validation

Validate that a host is a reachable Serenada server:

```swift
try await diagnostics.validateServerHost()
```

`validateServerHost()` requires `serverHost`.

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

`RoomWatcher` is server mode only and throws `requires serverHost` when no host is supplied.

## Logging

By default, the SDK is silent — no log output. To enable logging, set a `SerenadaLogger` on the core instance before creating sessions:

```swift
let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))
serenada.logger = PrintSerenadaLogger()  // logs to stdout via print()
```

`PrintSerenadaLogger` is a built-in convenience logger. For production apps, implement the `SerenadaLogger` protocol to route SDK logs to your own logging system:

```swift
final class MyLogger: SerenadaLogger {
    func log(_ level: SerenadaLogLevel, tag: String, _ message: String) {
        // Route to your logging backend
        // level: .debug, .info, .warning, .error
        // tag: "Session", "Signaling", "Transport", "WebRTC",
        //       "PeerConnection", "Negotiation", "Audio", "Camera",
        //       "ScreenShare", "Stats"
    }
}

serenada.logger = MyLogger()
```

The logger is passed to all internal SDK components (signaling, WebRTC, audio, camera). Set it once on `SerenadaCore` before calling `join()` or `createRoom()`.

## Configuration

```swift
let config = SerenadaConfig(
    serverHost: "serenada.app",       // required
    defaultAudioEnabled: true,        // mic on at join (default)
    defaultVideoEnabled: true,        // camera on at join (default)
    videoMediaEnabled: true,        // set false for strict audio-only/PSTN calls
    cameraModes: [.selfie, .world, .composite], // available camera modes & cycle order; empty = no camera capture
    deferInitialAnswer: false,      // set true when a provider may delay the first answer
    transports: [.ws, .sse],          // transport priority (default)
    audioCoordinator: nil,            // custom SerenadaAudioCoordinator, or internal default
    audioIntent: AudioIntent()        // audio policy passed to the coordinator
)
```

See [Camera Modes](sdk-customization.md#camera-modes) for how `cameraModes` interacts with the call-flow controls, and [Frontline Variant](sdk-customization.md#android-and-ios-frontline-variant) for the audio-first frontline call UI.

## Next Steps

- [Feature Toggles, String Overrides & Theming](sdk-customization.md)
- [API Reference](https://agatx.github.io/serenada/ios/core/documentation/serenadacore/) — also available for [SerenadaCallUI](https://agatx.github.io/serenada/ios/call-ui/documentation/serenadacallui/)
