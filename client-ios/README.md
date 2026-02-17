# Serenada iOS Client

Native iOS (SwiftUI) client for Serenada 1:1 WebRTC calls.

This v1 port mirrors Android/web call flow and signaling semantics:
- 1:1 calls with host-based offer flow
- WebSocket signaling with automatic SSE fallback
- Room watch statuses for recent calls
- In-call camera mode cycle semantics (`selfie -> world -> composite`), with automatic composite skip
- Settings for server host, language, and call defaults

## Requirements
- Xcode 16+
- iOS 16+
- `xcodegen` (installed at `/opt/homebrew/bin/xcodegen` on this machine)

## Project setup
1. Generate the Xcode project:
```bash
cd client-ios
xcodegen generate
```

2. Open `SerenadaiOS.xcodeproj` and run `SerenadaiOS` on a simulator/device.

## WebRTC dependency pinning
This project expects a pinned `WebRTC.xcframework` in:
- `Vendor/WebRTC/WebRTC.xcframework`

Recommended build flow (from repository root):
```bash
bash tools/build_libwebrtc_ios_7559.sh
```

The script fetches Chromium WebRTC (`branch-heads/7559_173`), builds iOS slices,
strips dSYMs for repository-friendly size, copies the artifact into
`client-ios/Vendor/WebRTC/`, and updates checksum.

Manual checksum workflow (if you replace the artifact yourself):
```bash
cd client-ios
./scripts/update_webrtc_checksum.sh
```

Builds run `scripts/verify_webrtc_checksum.sh` pre-build.

If the WebRTC artifact is missing, the app builds in a local stub mode (UI/state/signaling scaffolding still compiles, but media transport is non-functional).

## Current v1 limitations
- Push notifications and encrypted snapshot flow are not implemented yet
- ReplayKit screen sharing is not implemented yet
- Diagnostics screen parity with Android is not implemented yet
- Universal link entitlements/provisioning are deferred (URL parsing/routing is implemented)
- iOS Simulator may not expose a usable camera feed; verify local camera preview on a physical iPhone

## Test
```bash
cd client-ios
xcodegen generate
xcodebuild \
  -project SerenadaiOS.xcodeproj \
  -scheme SerenadaiOS \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```
