# Broadcast Upload Extension (background screen sharing)

Screen sharing has two modes controlled by the `BROADCAST_EXTENSION` compile flag:

| Mode | Flag | Behavior |
|------|------|----------|
| **ReplayKit in-app** (default) | flag absent | Uses `RPScreenRecorder.startCapture()`. Only captures while the app is in the foreground. iOS suspends capture when backgrounded. |
| **Broadcast Upload Extension** | `BROADCAST_EXTENSION` | Uses a separate extension process (`SerenadaBroadcast`) that survives backgrounding. Captures the entire screen including other apps. Requires App Group provisioning. |

## How it works

The extension (`BroadcastUpload/SampleHandler.swift`) runs in its own process and writes video frames to a memory-mapped file in the shared App Group container. The main app (`BroadcastFrameReader` in `WebRtcEngine.swift`) polls that file at ~30fps and feeds frames into WebRTC.

IPC uses Darwin notifications via `CFNotificationCenter`:
- **`broadcastStarted`** — extension → app, signals capture has begun
- **`broadcastFinished`** — extension → app, signals capture ended
- **`requestStop`** — app → extension, asks extension to call `finishBroadcastWithError`

### Shared memory layout

64-byte header followed by raw pixel data:

```
Offset  0: UInt32 - frameSeqNo (incremented each frame, acts as change flag)
Offset  4: UInt32 - width
Offset  8: UInt32 - height
Offset 12: UInt32 - pixelFormat (kCVPixelFormatType)
Offset 16: UInt32 - planeCount
Offset 20: UInt32 - plane0BytesPerRow
Offset 24: UInt32 - plane0Height
Offset 28: UInt32 - plane1BytesPerRow
Offset 32: UInt32 - plane1Height
Offset 36: Int64  - timestampNs
Offset 44: UInt32 - rotation (RTCVideoRotation raw value)
Offset 48: [16 bytes reserved]
Offset 64: [plane 0 data] [plane 1 data]
```

## Enabling the Broadcast Extension

This requires one-time provisioning in the Apple Developer Portal.

### 1. Register the App Group

1. Go to [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list/applicationGroup)
2. Under **Identifiers → App Groups**, click **+**
3. Register `group.app.serenada.ios`

### 2. Update App IDs

For the **main app** (`app.serenada.ios`):
1. Go to **Identifiers → App IDs**, find `app.serenada.ios`
2. Enable the **App Groups** capability
3. Select `group.app.serenada.ios`

For the **broadcast extension** (`app.serenada.ios.broadcast`):
1. Register a new App ID: `app.serenada.ios.broadcast` (type: App ID)
2. Enable the **App Groups** capability
3. Select `group.app.serenada.ios`

### 3. Update the main app entitlements

Add the App Group entitlement to `Resources/SerenadaiOS.entitlements`:

```xml
<key>com.apple.security.application-groups</key>
<array>
    <string>group.app.serenada.ios</string>
</array>
```

### 4. Uncomment the target dependency

In `project.yml`, uncomment the `SerenadaBroadcast` dependency in the `SerenadaiOS` target:

```yaml
dependencies:
  # ...
  - target: SerenadaBroadcast
    embed: true
```

### 5. Add the compiler flag

Add `BROADCAST_EXTENSION` to `SWIFT_ACTIVE_COMPILATION_CONDITIONS` for the `SerenadaiOS` target. In `project.yml`:

```yaml
  SerenadaiOS:
    settings:
      base:
        # existing settings...
        SWIFT_ACTIVE_COMPILATION_CONDITIONS: BROADCAST_EXTENSION
```

Or in `LocalSigning.xcconfig`:

```
SWIFT_ACTIVE_COMPILATION_CONDITIONS = $(inherited) BROADCAST_EXTENSION
```

### 6. Regenerate and build

```bash
cd client-ios
xcodegen generate
./scripts/deploy_to_device.sh
```

## Testing on device

Broadcast extensions do not work in the iOS Simulator. Test on a physical device:

1. Start a call
2. Tap the screen share button — the system broadcast picker appears
3. Select "Serenada Broadcast" and confirm — broadcast starts (red status bar indicator)
4. Background the app — screen sharing continues
5. Return to the app — screen sharing still active, frames flowing
6. Tap the screen share button again — broadcast stops, camera restores
7. Also test stopping via the red status bar pill in iOS Control Center

## Files

| File | Purpose |
|------|---------|
| `BroadcastUpload/SampleHandler.swift` | Extension entry point — writes frames to shared memory |
| `BroadcastUpload/BroadcastUpload.entitlements` | Extension App Group entitlement |
| `BroadcastUpload/Info.plist` | Extension Info.plist |
| `Sources/UI/Components/BroadcastPickerButton.swift` | `UIViewRepresentable` wrapping `RPSystemBroadcastPickerView` |
| `Sources/Core/Call/WebRtcEngine.swift` | `BroadcastFrameReader` (reads shared memory) and `ReplayKitVideoCapturer` (fallback) |
