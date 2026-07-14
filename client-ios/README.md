# Serenada iOS Client

Native iOS (SwiftUI) client for Serenada WebRTC calls.

This native client mirrors Android/web call flow and signaling semantics:
- 1:1 and adaptive mesh multi-party calls (up to 4 participants)
- New-capable clients create group-capable rooms by default; legacy-first rooms stay capped at 2 participants
- Calls stay in the familiar 1:1 presentation until participant `#3` joins, then switch to the adaptive remote-stage + local-PIP layout
- WebSocket signaling with automatic SSE fallback
- Room watch statuses for merged recent calls + saved rooms
- Saved rooms (create, rename, remove, quick-join, share link) with Android-parity host override semantics
- In-call camera mode cycle semantics (`selfie -> world -> composite`), with automatic composite skip and a circular mirrored selfie overlay in composite mode that stays aligned across portrait and landscape
- World/composite pinch zoom (capture-level zoom)
- Local camera default capture profile targets 480p; enabling `HD Video (experimental)` switches to highest available mode
- Broadcast Upload Extension for background screen sharing via the iOS system broadcast picker (with ReplayKit in-app fallback)
- Push subscription + encrypted join snapshots + waiting-room invite action
- In-call realtime stats model + top-left double-tap debug panel
- Diagnostics screen (permissions, media, connectivity, ICE gather probe, report export)
- Settings for server host, language, call defaults, saved-room order, invite-notification filter, and app version
- Independent content video (screen share) SDK surface in `SerenadaCore`: `cameraEnabled`/`content` on `LocalParticipant` and `RemoteParticipant` (camera-specific state alongside legacy `videoEnabled`), the signaled `mediaPolicy.videoMediaEnabled` session policy, and content renderer APIs `SerenadaSession.attachRemoteContentRenderer(_:forParticipant:)`/`detachRemoteContentRenderer(_:forParticipant:)` plus `attachLocalContentRenderer(_:)`/`detachLocalContentRenderer(_:)` (existing `attachRemoteRenderer(_:forParticipant:)` stays camera-specific). Gated behind `SerenadaConfig.enableIndependentContentVideo` (default `false` for SDK integrators, enabled by the bundled app); with the flag off, screen share behaves exactly as today. See the root README and protocol spec for the cross-platform contract.

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
WebRTC comes from the [zello-ios-web-rtc](https://github.com/zelloptt/zello-ios-web-rtc)
SPM package, declared in `SerenadaCore/Package.swift` and pinned to an exact
version. SPM resolves and unzips the prebuilt XCFramework automatically — no
vendored artifact or checksum script is needed.

To bump the WebRTC version, update the `exact:` version in
`SerenadaCore/Package.swift`, re-resolve packages, and re-verify call
resilience on physical devices. Two committed lockfiles must move in
lockstep with the manifest: `SerenadaCore/Package.resolved` and
`SerenadaiOS.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`
(both record the pinned revision). Standalone `SerenadaCallUI` builds
generate their own `Package.resolved`, which is gitignored.

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

## Broadcast Upload Extension (background screen sharing)

See [broadcast-extension.md](broadcast-extension.md) for architecture details and setup instructions.

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
