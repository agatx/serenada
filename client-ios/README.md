# Serenada iOS Client

Native iOS (SwiftUI) client for Serenada 1:1 WebRTC calls.

This v1 port mirrors Android/web call flow and signaling semantics:
- 1:1 calls with host-based offer flow
- WebSocket signaling with automatic SSE fallback
- Room watch statuses for merged recent calls + saved rooms
- Saved rooms (create, rename, remove, quick-join, share link) with Android-parity host override semantics
- In-call camera mode cycle semantics (`selfie -> world -> composite`), with automatic composite skip
- World/composite pinch zoom (capture-level zoom)
- Local camera default capture profile targets 480p; enabling `HD Video (experimental)` switches to highest available mode
- ReplayKit screen-share toggle for in-call sharing
- Push subscription + encrypted join snapshots + waiting-room invite action
- In-call realtime stats model + top-left double-tap debug panel
- Diagnostics screen (permissions, media, connectivity, ICE gather probe, report export)
- Settings for server host, language, call defaults, saved-room order, invite-notification filter, and app version

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

The script fetches Chromium WebRTC (`branch-heads/7559_173`), patches
`rtc_base/ssl_roots.h` from the current root bundle, builds iOS slices, strips
dSYMs for repository-friendly size, copies the artifact into
`client-ios/Vendor/WebRTC/`, and updates checksum.

Manual checksum workflow (if you replace the artifact yourself):
```bash
cd client-ios
./scripts/update_webrtc_checksum.sh
```

Checksum generation is repository-path stable (it hashes relative framework paths),
so identical artifacts produce the same digest across different checkout locations.

Builds run `scripts/verify_webrtc_checksum.sh` pre-build.

If the WebRTC artifact is missing, the app builds in a local stub mode (UI/state/signaling scaffolding still compiles, but media transport is non-functional).

## Universal links
- Associated domains are configured for:
  - `applinks:serenada.app`
  - `applinks:serenada-app.ru`
- Server must host `/.well-known/apple-app-site-association` with `appID = U5TBRZ56DZ.app.serenada.ios`.
- Deep-link smoke test command (physical device):
```bash
xcrun devicectl device process launch \
  --device [UDID] \
  --terminate-existing \
  --activate \
  --payload-url "https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es" \
  app.serenada.ios
```

iOS Simulator may not expose a usable camera feed; verify local camera preview and media behavior on a physical iPhone.

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

Run the deep-link rejoin UI flow test only:
```bash
cd client-ios
xcodegen generate
xcodebuild \
  -project SerenadaiOS.xcodeproj \
  -scheme SerenadaiOS \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:SerenadaiOSUITests/DeepLinkRejoinFlowUITests \
  test
```

Override the test deep link (for example, to target a known active room):
```bash
cd client-ios
SERENADA_UI_TEST_REJOIN_DEEPLINK='https://serenada.app/call/<room-token>' \
xcodebuild \
  -project SerenadaiOS.xcodeproj \
  -scheme SerenadaiOS \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:SerenadaiOSUITests/DeepLinkRejoinFlowUITests \
  test
```

## Real device deploy
Build, install, and launch on a connected physical iPhone:

```bash
cd client-ios
./scripts/deploy_to_device.sh
```

Useful options:

```bash
# specific device
./scripts/deploy_to_device.sh --udid [UDID]

# install only (skip launch)
./scripts/deploy_to_device.sh --no-launch

# override signing team
./scripts/deploy_to_device.sh --team [TEAM_ID]
```

## Local-only signing override (do not commit)
To keep your team ID in this local clone only, create a private xcconfig:

```bash
cd client-ios
cat > LocalSigning.xcconfig <<'EOF'
DEVELOPMENT_TEAM = U5TBRZ56DZ
CODE_SIGN_STYLE = Automatic
EOF
```

Ignore it in this clone only:

```bash
echo "client-ios/LocalSigning.xcconfig" >> ../.git/info/exclude
```

`./scripts/deploy_to_device.sh` auto-loads `client-ios/LocalSigning.xcconfig` when present.

If needed, you can override explicitly:

```bash
./scripts/deploy_to_device.sh --xcconfig /absolute/path/to/LocalSigning.xcconfig
```
