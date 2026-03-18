# Serenada iOS Sample App

Minimal iOS host app demonstrating Serenada SDK integration.

## What it does

- Accepts a call URL and presents `SerenadaCallFlow`
- Creates a new room via `SerenadaCore.createRoom()`
- Total integration: ~40 lines of Swift

## Setup

1. Add `SerenadaCore` and `SerenadaCallUI` as SPM dependencies
2. Build and run on a physical device (camera requires real hardware)

## Integration pattern

```swift
// 1. Initialize core
let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

// 2. Show call UI when you have a URL
SerenadaCallFlow(url: callURL, onDismiss: { /* navigate back */ })
```
