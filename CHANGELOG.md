# Changelog

All notable changes to the Serenada SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.5.1] — 2026-04-27

### Changed
- Version bump across all SDK platforms (web `@serenada/core` + `@serenada/react-ui`, Android `core` + `call-ui` + `libwebrtc`, iOS `SerenadaCore`) and integration docs.

## [0.5.0] — 2026-04-24

### Added
- `SerenadaConfig.cameraModes` — restrict which camera modes (selfie / world / composite) are offered and in what order across web, iOS, and Android. Supports initial-mode selection, single-mode lock-in (flip control hidden), and an empty-list audio-only mode (video toggle and camera permission suppressed). Unsupported modes are dropped silently per platform.
- `LocalParticipant.availableCameraModes` on the observable `CallState` — the resolved (platform-filtered) mode list for UI consumers to gate controls.
- `SerenadaCallFlowConfig.autoHideControls` — opt out of the idle-time auto-hide of the in-call controls bar. Defaults to `true` (existing behavior); setting to `false` keeps controls visible for the duration of the call on all three platforms.

### Changed
- The flip-camera control now also hides when the local video is turned off (previously it stayed visible and reacquired the camera invisibly on tap).

### Fixed
- `scripts/check-version-parity.mjs` now matches `sdkVersion` in `serenada-core/build.gradle.kts` instead of the first `version = "..."` line it finds (which previously matched an unrelated dependency version).
- `client-ios/scripts/deploy_to_device.sh` resilient to `devicectl` bullet-character changes when translating CoreDevice UDID → xcodebuild UDID.

## [0.3.0] — 2026-03-28

### Added
- Pluggable signaling provider support across web, Android, and iOS SDKs
- Built-in server-provider adapters on all SDK platforms to preserve the existing hosted signaling flow
- Cross-platform provider-mode regression coverage for diagnostics, room watching, ICE refresh, and negotiation fallback recovery

### Changed
- SDK config now requires exactly one of built-in `serverHost` or custom `signalingProvider`
- Offer ownership now uses lexicographic peer ID ordering on web, Android, and iOS
- Web peer-message integration now uses `onPeerMessage()` instead of exposing `subscribeToMessages()`
- Provider mode now gates server-only APIs and server-bound diagnostics with clear `requires serverHost` failures
- iOS `SerenadaDiagnostics.runConnectivityChecks()` is now `async throws`, which is a source-compatible break for existing call sites

## [0.2.0] — 2026-03-23

### Added
- GitHub Actions workflow to generate and publish SDK API docs to GitHub Pages
- TSDoc, KDoc, and Swift doc comments on all public API classes, methods, and model fields
- Landing page at https://agatx.github.io/serenada/ linking all platform docs

### Changed
- Reduced public SDK surface area across all platforms:
  - Web: internal exports marked with `@internal` (105 → 54 doc pages)
  - Android: 31 implementation types changed to `internal` visibility
  - iOS: 12 implementation types changed to `internal` access
- Moved reference docs (`serenada_protocol_v1.md`, `push-notifications.md`, `serenada_prd.md`, `wifi-lock-audio-delay-postmortem.md`) from repo root to `docs/`

### Fixed
- Removed `server/server_test` binary from git tracking
- Added `samples/web/.gitignore` for `node_modules/` and `dist/`

## [0.1.0] — 2026-03-22

### Added
- Headless SDK + optional UI architecture across Web, Android, and iOS
- `SerenadaCore` entry point with `join(url)`, `join(roomId)`, and `createRoom()` on all platforms
- `SerenadaSession` state machine with observable `CallState` (phase, participants, connection status)
- Dual-transport signaling (WebSocket primary, SSE fallback) with automatic failover
- WebRTC peer connection management with ICE restart, TURN refresh, and exponential backoff
- Pre-built call UI components (`SerenadaCallFlow`) on all platforms
- Cross-platform resilience constants with automated parity verification
- Typed `CallError` enum with 7 canonical error codes (all platforms)
- Typed `PeerConnectionState` enum replacing raw strings (all platforms)
- Camera mode system (selfie, world, composite, screen share)
- Screen sharing support on all platforms
- Push notification infrastructure (host app integration)
- Room occupancy monitoring via `RoomWatcher`
- Diagnostics and connectivity probes via `SerenadaDiagnostics`
- Sample apps for Web, Android, and iOS

### Improved (post-initial release)
- Typed `CallError` sealed class on Android replacing `errorMessage: String?` — 7 canonical error codes matching iOS
- Typed `CallErrorCode` union on web (`signalingTimeout`, `connectionFailed`, `roomFull`, `roomEnded`, `permissionDenied`, `serverError`, `webrtcUnavailable`, `unknown`)
- Typed `PeerConnectionState` / `SerenadaPeerConnectionState` enums replacing raw `String` on all platforms
- Typed signaling message payloads on web (7 parse functions replacing 15 unsafe casts)
- Typed signaling payloads on Android and iOS (structured data classes/structs)
- Extracted `SignalingMessageRouter` and `JoinFlowCoordinator` from SerenadaSession on iOS (1180→786 lines) and Android (1052→854 lines)
- Moved `@serenada/core` from dependency to peer dependency in `@serenada/react-ui`
- `SerenadaCore.isSupported()` static method for WebRTC capability detection
- CSS isolation via `[data-serenada-callflow]` attribute selector with `!important` on root layout
- Optional `className` prop on `SerenadaCallFlow` for host-app style overrides
- Integration test harness with 7 signaling protocol scenarios
- Version parity verification script (`scripts/check-version-parity.mjs`)
- `VERSIONING.md` semantic versioning policy and `CHANGELOG.md`
