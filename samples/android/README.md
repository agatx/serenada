# Serenada Android Sample App

Minimal Android host app demonstrating Serenada SDK integration using `serenada-core` and `serenada-call-ui` directly from this repo.

## What it does

- Accepts a call URL, creates a session, and presents `SerenadaCallFlow` (session-first path)
- Creates a new room via `SerenadaCore.createRoom()` and joins explicitly with `join()` (session-first path)
- Starts a provider-mode demo backed by a local in-memory `SignalingProvider`
- Shows incremental `peerJoined` events and peer-message delivery without Serenada server transport
- Demonstrates injecting a custom `SerenadaAudioCoordinator` for host-owned audio policy
- Keeps several calls joined at once with `SerenadaCallRegistry` and switches the foreground media owner between them (multi-call)
- Disables screen sharing and invite controls (these require app-specific service and push wiring)

## Build & run

The sample references `serenada-core` and `serenada-call-ui` as local Gradle project dependencies
via `settings.gradle.kts`, so no Maven publishing step is needed.

```bash
cd samples/android
./gradlew installDebug      # Build and install on a connected device
```

> **Note:** Camera preview requires a physical device — the emulator will connect but the video
> feed is unreliable.

## Project structure

```
samples/android/
├── build.gradle.kts          # Root build config (plugin versions)
├── settings.gradle.kts       # Includes :serenada-core and :serenada-call-ui from ../../client-android/
├── gradle.properties         # JVM args, AndroidX
├── app/
│   ├── build.gradle.kts      # App config (Compose, SDK versions, dependencies)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/themes.xml
│       └── java/app/serenada/sample/
│           ├── MainActivity.kt
│           ├── MultiCallScreen.kt        # SerenadaCallRegistry multi-call demo
│           └── SampleAudioCoordinator.kt
└── README.md
```

## Integration pattern

```kotlin
// 1. Initialize core
val serenada = SerenadaCore(
    config = SerenadaConfig(serverHost = "serenada.app"),
    context = this,
)

// 2. Join via URL and present the host-owned session.
val session = serenada.join(url = callUrl)
SerenadaCallFlow(
    session = session,
    config = SerenadaCallFlowConfig(screenSharingEnabled = false, inviteControlsEnabled = false),
    onEndCall = {
        session.leave()
        session.close()
        // navigate back
    },
    onDismiss = {
        session.close()
        // navigate back
    },
)

// 3. Or create a room, then join explicitly
scope.launch {
    val room = serenada.createRoom()
    // Share room.roomUrl with the other participant
    val session = serenada.join(url = room.roomUrl)
    SerenadaCallFlow(
        session = session,
        config = SerenadaCallFlowConfig(screenSharingEnabled = false, inviteControlsEnabled = false),
        onDismiss = {
            session.close()
            // navigate back
        },
    )
}
```

Provider mode uses the same SDK entry point with a custom `SignalingProvider`:

```kotlin
val provider = SampleMockSignalingProvider()
val providerCore = SerenadaCore(
    config = SerenadaConfig(signalingProvider = provider),
    context = this,
)
val session = providerCore.join(roomId = "provider-demo-room")
session.onPeerMessage { message ->
    println("provider message: ${message.type}")
}
```

The sample also includes `SampleAudioCoordinator`, which implements `SerenadaAudioCoordinator` and is passed through `SerenadaConfig.audioCoordinator`. Real host apps can use the same protocol to own audio focus, route selection, and external-audio coexistence policy. Omit `audioCoordinator` to use the SDK's internal default coordinator.

Multi-call (see `MultiCallScreen.kt`) uses one `SerenadaCallRegistry` over a `SerenadaCore`. The registry keeps every call signaled and connected, but only one call at a time owns local capture and process-wide audio (the foreground/active call); the rest sit held. The registry owns the foreground lease, so a host integrates through the registry OR direct `SerenadaCore.join`, not both.

```kotlin
val registry = SerenadaCallRegistry(serenada)

// Join held, then take foreground (the common new-call flow).
when (val r = registry.joinAndSwitch(RoomRef.Url(callUrl))) {
    is JoinAndSwitchResult.Active -> { /* r.callId is now the active call */ }
    is JoinAndSwitchResult.NeedsPermission -> { /* prompt, then registry.switchToCall(r.callId) */ }
    is JoinAndSwitchResult.Failed -> { /* inspect r.error.message */ }
}

// Join another room in the background without taking foreground.
registry.joinHeld(RoomRef.Id("ROOM2"))

// Render the active call's session with the prebuilt UI (the session is exposed, not hidden).
registry.activeSession?.let { session -> SerenadaCallFlow(session = session, /* ... */) }

// Move the foreground lease to a held call; hold/leave/end an existing call.
registry.switchToCall(callId)
registry.holdCall(callId)   // keep joined, drop foreground (no auto-promote of held calls)
registry.leaveCall(callId)  // graceful leave (other participant stays)
registry.endCall(callId)    // end for everyone

// Observe aggregate state (active call id, per-call role/phase/errors).
registry.state.collect { state -> /* state.activeCallId, state.calls, state.lastError */ }
```

After holding or leaving the active call the registry does NOT auto-promote a held call: `activeCallId` becomes null while held calls remain, and the host decides which (if any) to `switchToCall`. The demo screen renders that "no active call but held calls remain" state.

## Sample limitations

This sample hides screen sharing and waiting-room invite actions because those require
app-specific foreground service and push notification wiring that belongs in a full product app.
