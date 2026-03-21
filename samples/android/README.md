# Serenada Android Sample App

Minimal Android host app demonstrating Serenada SDK integration using `serenada-core` and `serenada-call-ui` directly from this repo.

## What it does

- Accepts a call URL and presents `SerenadaCallFlow` (URL-first path)
- Creates a new room via `SerenadaCore.createRoom()` and reuses the returned session (session-first path)
- Disables screen sharing and invite controls (these require app-specific service and push wiring)
- Total integration: ~80 lines of Kotlin

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
│           └── MainActivity.kt
└── README.md
```

## Integration pattern

```kotlin
// 1. Initialize core
val serenada = SerenadaCore(
    config = SerenadaConfig(serverHost = "serenada.app"),
    context = this,
)

// 2. Join via URL (URL-first — SDK creates the session internally)
SerenadaCallFlow(
    url = callUrl,
    config = SerenadaCallFlowConfig(screenSharingEnabled = false, inviteControlsEnabled = false),
    onDismiss = { /* navigate back */ },
)

// 3. Or create a room and reuse the session (session-first — avoids double-join)
scope.launch {
    val room = serenada.createRoom()
    // Share room.roomUrl with the other participant
    SerenadaCallFlow(
        session = room.session,
        config = SerenadaCallFlowConfig(screenSharingEnabled = false, inviteControlsEnabled = false),
        onDismiss = { /* navigate back */ },
    )
}
```

## Sample limitations

This sample hides screen sharing and waiting-room invite actions because those require
app-specific foreground service and push notification wiring that belongs in a full product app.
