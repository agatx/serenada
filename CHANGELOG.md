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
