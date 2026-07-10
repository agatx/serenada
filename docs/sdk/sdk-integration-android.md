# Serenada SDK — Android Quick Start

## Requirements

- Android API 24+ (Android 7.0)
- Gradle 9.5+ with Android Gradle Plugin 9.2+
- Kotlin 2.3.21+
- Jetpack Compose (BOM 2024.10.00+)

## Installation

### Gradle (GitHub Packages)

Add the repository and dependencies to your app module:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/agatx/serenada")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("app.serenada:core:0.9.1")
    implementation("app.serenada:call-ui:0.9.1")
}
```

For local development within the Serenada monorepo, use project references:

```kotlin
// settings.gradle.kts
include(":serenada-core")
include(":serenada-call-ui")

// app/build.gradle.kts
dependencies {
    implementation(project(":serenada-core"))
    implementation(project(":serenada-call-ui"))
}
```

When you construct `SerenadaConfig` directly, provide exactly one of `serverHost` or `signalingProvider`.

## Quick Start — URL-First (Simplest)

```kotlin
import app.serenada.callui.SerenadaCallFlow

@Composable
fun CallScreen(url: String) {
    SerenadaCallFlow(
        url = url,
        onDismiss = { navController.popBackStack() }
    )
}
```

That's it. `SerenadaCallFlow` handles permissions, joining, the in-call UI, and cleanup.

### Optional Frontline UI

Android can opt into a frontline call layout while keeping the same call-flow API:

```kotlin
import app.serenada.callui.SerenadaCallFlow
import app.serenada.callui.SerenadaCallFlowConfig
import app.serenada.callui.SerenadaCallUiVariant

@Composable
fun FrontlineCallScreen(url: String) {
    SerenadaCallFlow(
        url = url,
        config = SerenadaCallFlowConfig(
            uiVariant = SerenadaCallUiVariant.Frontline,
            snapshotEnabled = true,
        ),
        onDismiss = { navController.popBackStack() }
    )
}
```

URL-first frontline calls start audio-first and use the camera order `world -> selfie -> composite`. For session-first usage, set `defaultVideoEnabled = false` and `cameraModes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE, LocalCameraMode.COMPOSITE)` on the `SerenadaConfig` used to create the session. When `Frontline` is selected, Android keeps Frontline styling for lifecycle states, 1:1 calls, and multi-party calls. The More sheet shows the current audio route first and opens a checkmarked route list for routes published by the session audio coordinator. Invite/share actions remain in the Frontline More sheet; the standard waiting-screen QR code is not shown.

### Optional System Picture-in-Picture

The prebuilt call UI can configure Android system Picture-in-Picture for waiting and active calls. Host apps must also opt their call activity into PiP in the manifest:

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation">
    ...
</activity>
```

Then enable the call UI flag:

```kotlin
SerenadaCallFlow(
    url = url,
    config = SerenadaCallFlowConfig(systemPictureInPictureEnabled = true),
    onDismiss = { navController.popBackStack() }
)
```

When enabled on a supported device, the SDK keeps the active video or avatar visible in system PiP and auto-enters PiP on Home/gesture leave where Android supports it. Android custom PiP actions are intentionally not used, so call controls remain available after returning to the app. If the call ends while the activity is still in PiP, the SDK finishes that activity so the system closes the PiP window instead of showing non-call UI inside it.

## Session-First (Pre-Observation)

Create a session before presenting UI to observe state early:

```kotlin
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaConfig
import app.serenada.callui.SerenadaCallFlow

val serenada = SerenadaCore(
    config = SerenadaConfig(serverHost = "serenada.app"),
    context = applicationContext,
)

fun handleDeepLink(uri: Uri) {
    val session = serenada.join(url = uri.toString())
    // Observe session.state before showing UI if needed

    // In your Composable:
    SerenadaCallFlow(
        session = session,
        onEndCall = {
            session.leave()
            session.close()
            navController.popBackStack()
        },
        onDismiss = {
            session.close()
            navController.popBackStack()
        }
    )
}
```

If `onEndCall` is omitted, the prebuilt UI calls `session.leave()` before
dismissing. When you provide `onEndCall`, your host owns the end button behavior,
including leaving or closing the session and updating navigation state.

## Create a Room

```kotlin
scope.launch {
    runCatching { serenada.createRoom() }
        .onSuccess { room ->
            val shareUrl = room.roomUrl  // send to the other party
            val session = serenada.join(url = room.roomUrl)  // join explicitly
            // Navigate to call screen with session
        }
        .onFailure { error ->
            Log.e("Serenada", "Failed", error)
        }
}
```

`createRoom()` returns `CreateRoomResult(roomUrl, roomId)` only. It does not join the room or create a session. Call `join()` with the returned URL to start the call.

`SerenadaCore` and `SerenadaSession` must be used from the Android main thread. The SDK now fails fast if these entry points are invoked from a background thread.

`createRoom()` is server mode only. In provider mode there is no Serenada room API, so join by your own room ID instead.

## Provider Mode (Custom Signaling)

Provider mode uses the same `SerenadaCore`, but you inject a `SignalingProvider` instead of `serverHost`:

```kotlin
class DemoProvider : SignalingProvider {
    override var listener: SignalingProvider.Listener? = null

    override fun connect() {
        listener?.onConnected(ConnectionInfo(transport = "mock"))
    }

    override fun disconnect() = Unit

    override fun joinRoom(roomId: String, options: JoinOptions) {
        listener?.onJoined(
            JoinedEvent(
                peerId = "local-peer",
                participants = listOf(SignalingProviderParticipant(peerId = "local-peer", joinedAt = 1L)),
            )
        )
    }

    override fun leaveRoom() = Unit
    override fun endRoom() = Unit
    override fun sendToPeer(peerId: String, type: String, payload: JSONObject?) = Unit
    override fun broadcast(type: String, payload: JSONObject?) = Unit
    override suspend fun getIceServers(): List<PeerConnection.IceServer> = emptyList()
}

val serenada = SerenadaCore(
    config = SerenadaConfig(signalingProvider = DemoProvider()),
    context = applicationContext,
)

val session = serenada.join(roomId = "group-123")
```

Provider callbacks may be invoked from any thread. The SDK marshals them onto the main looper before updating session state. Host-app calls into `SerenadaCore` and `SerenadaSession` still belong on the main thread.

If your provider already owns reconnect logic, set `ProviderCapabilities(handlesReconnection = true)`. Otherwise leave it at the default `false` and let the session rejoin with `reconnectPeerId`.

## Core-Only Integration (No UI)

Use `SerenadaCore` directly for a fully custom UI:

```kotlin
val serenada = SerenadaCore(
    config = SerenadaConfig(serverHost = "serenada.app"),
    context = applicationContext,
)
val session = serenada.join(url = url)

// Observe app-facing state
lifecycleScope.launch {
    session.state.collect { state ->
        when (state.phase) {
            CallPhase.Idle -> { }
            CallPhase.AwaitingPermissions -> {
                // Prompt for permissions, then call session.resumeJoin()
            }
            CallPhase.Joining -> showSpinner()
            CallPhase.Waiting -> showWaitingScreen()
            CallPhase.InCall -> showCallScreen()
            CallPhase.Ending -> showEndingScreen()
            CallPhase.Error -> showError(state.error)
        }
    }
}

// Observe low-level diagnostics separately
lifecycleScope.launch {
    session.diagnostics.collect { diagnostics ->
        Log.d("Serenada", "Transport=${diagnostics.activeTransport} ICE=${diagnostics.iceConnectionState}")
    }
}

// Media controls
session.toggleAudio()
session.toggleVideo()
session.flipCamera()

// Video rendering
session.attachLocalRenderer(localSurfaceView)
session.attachRemoteRenderer(remoteSurfaceView)          // primary remote in 1:1
session.attachRemoteRendererForCid(cid, remoteSurfaceView) // specific remote in group calls

// Leave or end
session.leave()   // local exit, room stays open
session.end()     // terminates room for all
```

`SerenadaSession` exposes two flows:
- `state` for lifecycle, participants, permissions, and errors
- `diagnostics` for transport state, low-level WebRTC state, stats, and feature degradation details

## Pluggable Audio Coordinators

By default, `serenada-core` manages Android audio focus, `MODE_IN_COMMUNICATION`, route changes, and proximity behavior with an internal coordinator. Apps that already own process-wide audio state, such as apps with an existing audio engine, can inject a custom `SerenadaAudioCoordinator`:

```kotlin
val serenada = SerenadaCore(
    config = SerenadaConfig(
        serverHost = "serenada.app",
        audioCoordinator = MyAudioCoordinator(),
        audioIntent = AudioIntent(
            requiresCapture = true,
            requiresPlayback = true,
            muteDuringExternalAudio = true,
            duckDuringExternalAudio = true,
        ),
    ),
    context = applicationContext,
)
```

Custom coordinators implement `SerenadaAudioCoordinator`. They activate and deactivate call audio, apply route selections, publish `availableDevices`, `effectiveInputDevice`, `effectiveOutputDevice`, and emit `AudioCoordinatorEvent.ExternalAudioStarted` / `ExternalAudioEnded` when host-owned audio should temporarily mute local capture or duck playback. For duck-only interruptions that should not mute capture, emit `PlaybackDuckingStarted` / `PlaybackDuckingEnded`.

The concrete default coordinator is internal SDK behavior, not a supported public class to instantiate. Leave `audioCoordinator = null` to use it.

Custom UIs can observe and control the active coordinator through the session:

```kotlin
lifecycleScope.launch {
    session.availableAudioDevices.collect { devices ->
        // Render route picker.
    }
}

lifecycleScope.launch {
    session.isMicMutedByExternalAudio.collect { mutedByExternalAudio ->
        // Show a distinct external-audio mute state if needed.
    }
}

session.selectAudioDevice(device)
session.setMicMuted(true)
```

`isMicMuted` is the effective mute state: user mute, coordinator-driven external mute, and missing input route all count as muted. `isMicMutedByExternalAudio` isolates the coordinator-driven portion so the host can distinguish user mute from other external audio.

## Permissions Handling

In URL-first mode, `SerenadaCallFlow` automatically prompts for camera/microphone permissions.

In session-first or core-only mode, handle the `AwaitingPermissions` phase:

```kotlin
session.state.collect { state ->
    if (state.phase == CallPhase.AwaitingPermissions) {
        SerenadaPermissions.request(activity, state.requiredPermissions.orEmpty()) { granted ->
            if (granted) session.resumeJoin() else session.cancelJoin()
        }
    }
}
```

## Preflight Diagnostics

Run device and network checks before a call:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
val report = diagnostics.runAll()  // suspend function, never prompts

report.camera       // Available | Unavailable(reason) | NotAuthorized
report.microphone   // Available | Unavailable(reason) | NotAuthorized
report.speaker      // Available | Unavailable(reason)
report.network      // Reachable | Unreachable(reason) | Skipped(reason)
report.signaling    // Connected(transport) | Failed(reason)
report.turn         // Reachable(latencyMs) | Unreachable(reason)
```

In provider mode, `runAll()` still runs local device/network checks and TURN probing, but signaling is reported as skipped because there is no Serenada server to validate.

Callback-based usage is also available:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
diagnostics.runAll { report ->
    // inspect report
}
```

Diagnostics never trigger permission prompts — if a permission is missing, the check returns `NotAuthorized`.

### Connectivity Checks

Test Room API, WebSocket, SSE, diagnostic token, and TURN credentials separately:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
val report = diagnostics.runConnectivityChecks()

// report.roomApi, .webSocket, .sse, .diagnosticToken, .turnCredentials
// Each is CheckOutcome.NotRun | CheckOutcome.Passed(latencyMs) | CheckOutcome.Failed(error)
```

`runConnectivityChecks()` requires `serverHost`.

### ICE Probing

Verify STUN/TURN reachability with a real ICE gather:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
val report = diagnostics.runIceProbe(turnsOnly = false) { line ->
    Log.d("Diagnostics", line)
}

report.stunPassed
report.turnPassed
report.logs
```

`runTurnProbe()` is the primary TURN/STUN probe and `runIceProbe()` remains as a compatibility alias:

```kotlin
val turnReport = diagnostics.runTurnProbe(turnsOnly = false) { line ->
    Log.d("Diagnostics", line)
}
```

### Server Validation

Validate that a host is a reachable Serenada server:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
diagnostics.validateServerHost()
```

`validateServerHost()` requires `serverHost`.

## Room Watching

Monitor occupancy of saved/recent rooms without joining:

```kotlin
class RoomsViewModel : RoomWatcherDelegate {
    private val watcher = RoomWatcher()

    init {
        watcher.delegate = this
        watcher.watchRooms(roomIds = listOf("room1", "room2"), host = "serenada.app")
    }

    override fun roomWatcher(
        watcher: RoomWatcher,
        didUpdateStatuses: Map<String, RoomOccupancy>
    ) {
        // didUpdateStatuses["room1"]?.count, ?.maxParticipants
    }
}

// watcher.currentStatuses -> Map<String, RoomOccupancy>
```

`RoomWatcher` is server mode only and throws `requires serverHost` when no host is supplied.

## Foreground Service

Wire your foreground service to session state:

```kotlin
session.state.collect { state ->
    when (state.phase) {
        CallPhase.InCall -> startForegroundService()
        CallPhase.Idle -> stopForegroundService()
        else -> {}
    }
}
```

The foreground service must be declared by the host app — the SDK does not include one.

## Logging

By default, the SDK is silent — no log output. To enable logging, set a `SerenadaLogger` on the core instance before creating sessions:

```kotlin
val serenada = SerenadaCore(
    config = SerenadaConfig(serverHost = "serenada.app"),
    context = applicationContext,
)
serenada.logger = AndroidSerenadaLogger()  // routes to android.util.Log
```

`AndroidSerenadaLogger` is a built-in convenience logger that maps to `Log.d`/`Log.i`/`Log.w`/`Log.e`. For production apps, implement the `SerenadaLogger` interface to route SDK logs to your own system:

```kotlin
class MyLogger : SerenadaLogger {
    override fun log(level: SerenadaLogLevel, tag: String, message: String) {
        // Route to your logging backend
        // level: DEBUG, INFO, WARNING, ERROR
        // tag: "Session", "Signaling", "Transport", "WebRTC",
        //       "PeerConnection", "Negotiation", "Audio", "Camera",
        //       "ScreenShare", "Stats"
    }
}

serenada.logger = MyLogger()
```

The logger is passed to all internal SDK components (signaling, WebRTC, audio, camera). Set it once on `SerenadaCore` before calling `join()` or `createRoom()`.

## Configuration

```kotlin
val config = SerenadaConfig(
    serverHost = "serenada.app",      // required
    defaultAudioEnabled = true,       // mic on at join (default)
    defaultVideoEnabled = true,       // camera on at join (default)
    videoMediaEnabled = true,          // set false for strict audio-only/PSTN calls
    cameraModes = DEFAULT_CAMERA_MODES, // available camera modes & cycle order; empty = no camera capture
    deferInitialAnswer = false,        // set true when a provider may delay the first answer
    transports = listOf(SerenadaTransport.WS, SerenadaTransport.SSE), // transport priority (default)
    audioCoordinator = null,          // custom SerenadaAudioCoordinator, or internal default
    audioIntent = AudioIntent(),      // audio policy passed to the coordinator
)
```

See [Camera Modes](sdk-customization.md#camera-modes) for how `cameraModes` interacts with the call-flow controls, and [Frontline Variant](sdk-customization.md#android-and-ios-frontline-variant) for the audio-first frontline call UI.

## Next Steps

- [Feature Toggles, String Overrides & Theming](sdk-customization.md)
- [API Reference](https://agatx.github.io/serenada/android/core/) — also available for [serenada-call-ui](https://agatx.github.io/serenada/android/call-ui/)
