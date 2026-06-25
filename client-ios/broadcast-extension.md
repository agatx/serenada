# Broadcast Upload Extension (background screen sharing)

Screen sharing is a **runtime SDK feature**, selected by `SerenadaConfig.screenShareMode`
(no compile flags):

| Mode | Behavior |
|------|----------|
| **`.broadcast(BroadcastIPCConfig)`** | System-wide capture via a Broadcast Upload Extension that survives backgrounding. Captures the entire screen including other apps. Requires App Group provisioning. This is what the reference app and Zello use. |
| **`.inAppOnly`** | `RPScreenRecorder.startCapture()` in-process. Captures only this app's own content and is foreground-only. SDK/reference scope. |
| **`.disabled`** (default) | Screen sharing is unavailable. `screenShareExtensionBundleId` is `nil`, `isScreenShareAvailable` is `false`, and the call UI hides the screen-share control. |

Both capture paths are always compiled; the SDK picks one at runtime. A host opts in
by passing the mode at session construction:

```swift
SerenadaConfig(
    serverHost: "serenada.app",
    enableIndependentContentVideo: true,
    screenShareMode: .broadcast(
        BroadcastIPCConfig(
            appGroupIdentifier: "group.app.serenada.ios",
            extensionBundleId: "app.serenada.ios.broadcast"
        )
    )
)
```

`BroadcastIPCConfig` derives every cross-process identifier from those two inputs in one
place, so the app (frame reader) and the extension (frame writer) always agree:

| Derived | Value |
|---------|-------|
| shared frame file | `<extensionBundleId>.frame.dat` |
| session sidecar file | `<extensionBundleId>.session.json` |
| Darwin "started" | `<extensionBundleId>.started` |
| Darwin "finished" | `<extensionBundleId>.finished` |
| Darwin "request stop" | `<extensionBundleId>.requestStop` |

## Components

- **`SerenadaBroadcastExtensionSupport`** — an extension-safe SwiftPM product (no WebRTC,
  no SerenadaCore app APIs). Holds `BroadcastIPCConfig`, the shared-memory layout, the
  session sidecar, the mmap frame writer, and the open base class
  `SerenadaBroadcastSampleHandler`. The broadcast extension depends on this product only.
- **The extension's principal class** is a one-file host-owned subclass
  (`ScreenShareSampleHandler: SerenadaBroadcastSampleHandler` in `BroadcastUpload/`). The
  base class carries all behavior; the subclass exists only so
  `NSExtensionPrincipalClass = $(PRODUCT_MODULE_NAME).ScreenShareSampleHandler` resolves to
  the extension's own module. The base reads its App Group from the
  `SerenadaBroadcastAppGroupIdentifier` Info.plist key and its bundle ID from
  `Bundle.main.bundleIdentifier`.
- **`BroadcastFrameReader`** (`SerenadaCore/Sources/Call/ScreenShareCapturers.swift`) runs
  in the main app, polls the shared memory at ~30fps, and feeds frames into WebRTC.

## How it works

The call UI arms screen sharing first, then opens `RPSystemBroadcastPickerView` so the user
selects the extension. The extension runs in its own process and writes video frames to a
memory-mapped file in the shared App Group container. The main app polls that file and feeds
frames into WebRTC.

### Session lifecycle (so frames are never read from a stale or call-less session)

Before the picker can launch the extension, the reader establishes a session in a sidecar
file (`<extensionBundleId>.session.json`):

- a monotonic **generation** (also stamped into every frame header),
- an **active-call marker** + a **heartbeat** the reader refreshes while listening.

This closes the failure modes a static app-group-file design cannot:

- **Start outside a call** (system screen-recording UI with no Serenada call): the extension
  reads the sidecar on `broadcastStarted`; with no live marker it calls
  `finishBroadcastWithError` and writes no frames.
- **Stale frames** (prior session, force quit, app kill): the reader rejects any frame whose
  header generation does not match the live session; the extension self-stops if the
  heartbeat goes stale (reader gone) or the live session's generation/id no longer match.
- **Teardown**: the reader clears the marker and deletes the frame + sidecar files on share
  stop, pending-start cancel, start timeout, and call teardown.

Timing constants (documented for QA / telemetry):

| Constant | Value |
|----------|-------|
| reader heartbeat interval | 1000 ms |
| heartbeat-stale threshold (extension self-stop / start refusal) | 3000 ms (3 missed beats) |
| frame poll interval | 33 ms (~30fps) |
| first-frame / broadcast-start timeout | 30 s |

### IPC

Darwin notifications via `CFNotificationCenter` (names derived from the extension bundle ID,
so they are unique per app and never collide device-wide):

- **`<ext>.started`** — extension → app, capture began
- **`<ext>.finished`** — extension → app, capture ended
- **`<ext>.requestStop`** — app → extension, asks it to call `finishBroadcastWithError`

### Shared memory layout

64-byte header followed by raw pixel data:

```
Offset  0: UInt32 - seqNo (odd/even seqlock: odd while writing, even when published)
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
Offset 48: UInt32 - generation (per-share session; reader rejects mismatches)
Offset 52: [12 bytes reserved]
Offset 64: [plane 0 data] [plane 1 data]
```

The writer makes `seqNo` odd before touching the frame and even after publishing, with a
memory barrier on each side; a reader that sees an odd value, or a value that changes across
its read, discards the frame as torn. `timestampNs` stays at byte offset `36`, intentionally
unaligned: read and write it via byte copies (`BroadcastSharedMemoryIO`), not typed
`UnsafeRawPointer.load`/`storeBytes`.

## Provisioning (one-time setup)

Screen sharing needs no dedicated Apple capability beyond **App Groups**. First-time builders
set up provisioning in the Apple Developer Portal.

### 1. Register the App Group

1. Go to [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list/applicationGroup)
2. Under **Identifiers → App Groups**, click **+**
3. Register `group.app.serenada.ios`

### 2. Update App IDs

For the **main app** (`app.serenada.ios`) and the **broadcast extension**
(`app.serenada.ios.broadcast`): enable the **App Groups** capability and select
`group.app.serenada.ios` on each.

### 3. Entitlements

Both `Resources/SerenadaiOS.entitlements` and `BroadcastUpload/BroadcastUpload.entitlements`
declare:

```xml
<key>com.apple.security.application-groups</key>
<array>
    <string>group.app.serenada.ios</string>
</array>
```

### 4. Regenerate and build

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
8. Start a broadcast from Control Center with no active call — it must finish immediately
   with an error (no live session)

## Files

| File | Purpose |
|------|---------|
| `SerenadaCore/BroadcastSupport/BroadcastIPCConfig.swift` | Per-host IPC identifier derivation |
| `SerenadaCore/BroadcastSupport/BroadcastSharedMemory.swift` | Header layout, offsets, I/O helpers, timing constants |
| `SerenadaCore/BroadcastSupport/BroadcastSessionSidecar.swift` | Session sidecar + store (generation, marker, heartbeat) |
| `SerenadaCore/BroadcastSupport/SerenadaBroadcastSampleHandler.swift` | Open base class — the mmap frame writer |
| `BroadcastUpload/ScreenShareSampleHandler.swift` | Reference-app extension principal class (thin subclass) |
| `BroadcastUpload/BroadcastUpload.entitlements` | Extension App Group entitlement |
| `BroadcastUpload/Info.plist` | Extension Info.plist (`SerenadaBroadcastAppGroupIdentifier`, principal class) |
| `SerenadaCore/Sources/Call/ScreenShareCapturers.swift` | `BroadcastFrameReader` (reads shared memory) + `ReplayKitVideoCapturer` (in-app) |
| `SerenadaCallUI/Sources/BroadcastPickerButton.swift` | System broadcast picker bridge used by the in-call screen-share button |
