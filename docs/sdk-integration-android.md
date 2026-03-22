# Serenada SDK — Android Quick Start

## Requirements

- Android API 26+ (Android 8.0)
- Kotlin 1.9+
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
    implementation("app.serenada:core:0.1.0")
    implementation("app.serenada:call-ui:0.1.0")
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
        onDismiss = { navController.popBackStack() }
    )
}
```

## Create a Room

```kotlin
scope.launch {
    runCatching { serenada.createRoom() }
        .onSuccess { room ->
            val shareUrl = room.roomUrl  // send to the other party
            val session = room.session   // already joining
            // Navigate to call screen with session
        }
        .onFailure { error ->
            Log.e("Serenada", "Failed", error)
        }
}
```

`SerenadaCore` and `SerenadaSession` must be used from the Android main thread. The SDK now fails fast if these entry points are invoked from a background thread.

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

### Server Validation

Validate that a host is a reachable Serenada server:

```kotlin
val diagnostics = SerenadaDiagnostics(config, applicationContext)
diagnostics.validateServerHost()
```

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
    transports = listOf(SerenadaTransport.WS, SerenadaTransport.SSE) // transport priority (default)
)
```

## Next Steps

- [Feature Toggles, String Overrides & Theming](sdk-customization.md)
- [API Reference](#) — generate with `./gradlew dokkaHtml`
