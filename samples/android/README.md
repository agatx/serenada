# Serenada Android Sample App

Minimal Android host app demonstrating Serenada SDK integration.

## What it does

- Accepts a call URL and presents `SerenadaCallFlow`
- Creates a new room via `SerenadaCore.createRoom()`
- Total integration: ~50 lines of Kotlin

## Setup

1. Add `app.serenada:core` and `app.serenada:call-ui` as Gradle dependencies
2. Build and run on a physical device (camera requires real hardware)

## Integration pattern

```kotlin
// 1. Initialize core
val serenada = SerenadaCore(config = SerenadaConfig(serverHost = "serenada.app"))

// 2. Show call UI when you have a URL
SerenadaCallFlow(url = callUrl, onDismiss = { /* navigate back */ })
```
