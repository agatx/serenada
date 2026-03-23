# Changelog

All notable changes to the Serenada SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

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
