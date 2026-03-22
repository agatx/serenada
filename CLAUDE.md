# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Serenada is a privacy-focused 1:1 WebRTC video calling application. No accounts, no tracking — just instant peer-to-peer video calls. The architecture follows a **headless SDK + optional UI** pattern across all platforms:

- **Web**: `@serenada/core` (vanilla TS) + `@serenada/react-ui` (React components) + thin app shell
- **Android**: `serenada-core` (Kotlin library) + `serenada-call-ui` (Compose) + sample app
- **iOS**: `SerenadaCore` (SPM package) + `SerenadaCallUI` (SPM/SwiftUI) + host app
- **Server**: Go signaling server

## Repository Rules (from AGENTS.md)

- This repository is **production-critical** — make minimal, targeted changes
- Do not introduce new dependencies unless explicitly requested
- Preserve existing behavior unless instructed otherwise
- Follow existing style and patterns; prioritize clarity over cleverness
- When unsure, ask for clarification instead of guessing

## Build & Development Commands

### Web Client (`client/`)
```bash
cd client
npm install          # Install dependencies
npm run dev          # Vite dev server (proxies /api, /ws, /device-check to localhost:8080)
npm run build        # TypeScript compile + Vite production build
npm run lint         # ESLint
npm run test         # Vitest
```

### Go Server (`server/`)
```bash
cd server
go run .             # Run server (requires Go 1.24+, reads ../.env)
go test ./...        # Run all tests (server + loadconduit)
```

### Full Stack (Docker)
```bash
cd client && npm run build     # Build frontend first
docker compose up -d --build   # Start server + coturn + nginx on port 80
```

### Android Client (`client-android/`)
```bash
cd client-android
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install on device/emulator
```

### iOS Client (`client-ios/`)
```bash
cd client-ios
xcodegen generate          # Generate Xcode project from project.yml
```
Build, install, and launch on a connected physical iPhone:

```bash
cd client-ios
./scripts/deploy_to_device.sh
```

### iOS Client Automated Tests
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

### Worktree Bootstrap & Validation
After creating a git worktree, bootstrap it to install all dependencies:
```bash
tools/worktree-bootstrap.sh <worktree-path>    # install deps in a worktree
tools/worktree-bootstrap.sh                     # or run in the main checkout
SKIP_ANDROID=1 SKIP_IOS=1 tools/worktree-bootstrap.sh ../my-wt  # web+server only
```

Validate that a worktree (or the main repo) is build-ready:
```bash
tools/worktree-validate.sh <worktree-path>     # full validation (builds + tests)
tools/worktree-validate.sh                      # validate the main checkout
SKIP_BUILD=1 SKIP_TEST=1 tools/worktree-validate.sh  # structure + deps only
```

### Load Testing
```bash
./server/loadtest/run-local.sh    # Run signaling load sweep against local Docker stack
```

### Cross-Platform Smoke Test
```bash
bash tools/smoke-test/smoke-test.sh                          # Full run (both devices plugged in)
SMOKE_SERVER=https://serenada.app bash tools/smoke-test/smoke-test.sh  # Against production
SMOKE_PAIRS=web+android bash tools/smoke-test/smoke-test.sh  # Android only
SMOKE_PAIRS=web+ios bash tools/smoke-test/smoke-test.sh      # iOS only
```

## Architecture

```
Browser/Mobile ←──WebRTC (P2P media)──→ Browser/Mobile
       │                                       │
       └──── WS or SSE (signaling) ──→ Go Server
                                           │
                                     Coturn (STUN/TURN)
```

### Server (`server/`)
Go signaling server — flat package structure (no deep hierarchy). Key files:
- `main.go` — entry point, route registration, middleware setup
- `signaling.go` — core Hub/Room/Client types and goroutine-based event loop
- `ws.go` / `sse.go` — WebSocket and SSE transport handlers
- `room_id.go` — HMAC-based room ID generation/validation
- `push.go` / `push_fcm.go` — Web Push (VAPID) and Firebase Cloud Messaging
- `security.go` — CORS/origin validation
- `rate_limit.go` — IP-based rate limiting
- `internal/stats/` — metrics collection
- `cmd/loadconduit/` — load testing tool

Core types: `Hub` (central event router), `Room` (call session, max 2 participants), `Client` (connection identified by CID, uses WS or SSE transport).

Dependencies: gorilla/websocket, webpush-go, godotenv, modernc.org/sqlite.

### Web Client (`client/`)
Monorepo with headless SDK + React UI layer + thin app shell. Built with React 19 + TypeScript + Vite.

**Headless SDK** (`packages/core/`):
- `src/SerenadaCore.ts` — entry point (join/createRoom)
- `src/SerenadaSession.ts` — session state machine, pub/sub state distribution
- `src/signaling/SignalingEngine.ts` — dual-transport signaling (WS + SSE)
- `src/media/MediaEngine.ts` — WebRTC peer connections, local media, ICE management
- `src/constants.ts` — shared resilience constants (cross-platform verified)
- `src/types.ts` — public type definitions (CallState, CallPhase, etc.)

**React UI** (`packages/react-ui/`):
- `src/SerenadaCallFlow.tsx` — pre-built call UI component (URL-first or session-first)
- `src/hooks/` — `useCallState` (useSyncExternalStore), `useSerenadaSession`
- `src/components/` — DebugPanel, StatusOverlay

**App shell** (`src/`):
- `src/pages/Home.tsx` — room creation/selection
- `src/pages/CallRoom.tsx` — call page with push snapshot support
- `src/i18n.ts` — internationalization (en, ru, es, fr)
- `src/utils/pushCrypto.ts` — push notification encryption

Routing: `/` → Home, `/call/:roomId` → CallRoom.

### Android Client (`client-android/`)
Kotlin + Jetpack Compose + Material3. Three-module Gradle project.

**Headless SDK** (`serenada-core/`):
- `SerenadaCore.kt` — entry point (join/createRoom)
- `SerenadaSession.kt` — session state machine, StateFlow-based state
- `SerenadaConfig.kt` / `SerenadaCoreDelegate.kt` — configuration and callbacks
- `CallState.kt` / `CallStats.kt` — public state models
- `call/WebRtcEngine.kt` — WebRTC integration
- `call/SignalingClient.kt` — protocol v1 signaling with dual transport
- `call/PeerConnectionSlot.kt` — per-peer connection management
- `call/CompositeCameraCapturer.kt` — 3-mode camera (selfie → world → composite)
- `call/WebRtcResilienceConstants.kt` — shared resilience constants
- `network/CoreApiClient.kt` — HTTP API client
- `diagnostics/` — connectivity and TURN probes

**Compose UI** (`serenada-call-ui/`):
- Pre-built Jetpack Compose call flow UI (depends on serenada-core)

**Host app** (`app/`):
- `call/CallManager.kt` — app-level call orchestrator (integrates SDK)
- `ui/` — Compose screens (JoinScreen, SettingsScreen, DiagnosticsScreen)
- `push/` — Firebase Cloud Messaging integration
- `service/` — foreground call service

WebRTC: custom-built AAR from branch-heads/7559_173 in `app/libs/`, verified with SHA-256 checksum.

### iOS Client (`client-ios/`)
SwiftUI + Swift 5.10, project generated via XcodeGen (`project.yml`). Two SPM packages + host app.

**Headless SDK** (`SerenadaCore/` — SPM package):
- `Sources/SerenadaCore.swift` — entry point (join/createRoom)
- `Sources/SerenadaSession.swift` — session state machine, @Published state
- `Sources/SerenadaConfig.swift` — configuration
- `Sources/Models/` — CallState, CallStats, CallPhase, RemoteParticipant, etc.
- `Sources/Call/WebRtcEngine.swift` — WebRTC integration
- `Sources/Call/PeerConnectionSlot.swift` — per-peer connection management
- `Sources/Call/WebRtcResilienceConstants.swift` — shared resilience constants
- `Sources/Signaling/` — SignalingClient + WS/SSE transports
- `Sources/Networking/CoreAPIClient.swift` — HTTP API client
- `Sources/RoomWatcher.swift` — room occupancy monitoring

**SwiftUI Call UI** (`SerenadaCallUI/` — SPM package):
- `Sources/SerenadaCallFlow.swift` — pre-built call flow (URL-first or session-first)
- `Sources/CallScreen.swift` — in-call UI
- `Sources/SerenadaCallFlowConfig.swift` / `SerenadaCallFlowTheme.swift` — customization

**Host app** (`Sources/`):
- `Core/Call/CallManager.swift` — app-level call orchestrator (integrates SDK)
- `Core/Push/` — push notifications (JoinSnapshotFeature, PushSubscriptionManager)
- `Core/Stores/` — settings, saved rooms, recent calls
- `UI/Screens/` — SwiftUI screens (JoinScreen, SettingsScreen, DiagnosticsScreen)
- `Shared/PushKeyStore.swift` — push encryption keys (shared with NotificationService extension)
- `NotificationService/` — push notification app extension (decrypts snapshot images)
- `BroadcastUpload/` — screen sharing broadcast extension

WebRTC: custom-built XCFramework from branch-heads/7559_173 in `Vendor/WebRTC/`.

**Sample apps** (`samples/ios/`, `samples/android/`, `samples/web/`):
- Minimal integration examples showing SDK usage for third-party developers.

## Signaling Protocol (v1)

JSON message envelope with fields: `v` (version), `type`, `rid` (room ID), `sid` (session ID), `cid` (client ID), `to` (target), `ts` (timestamp). Dual transport: WebSocket primary, SSE fallback. Message types include: join, joined, leave, offer, answer, ice-candidate, end_room, error.

## Cross-Platform Resilience Constants

WebRTC resilience timing constants (reconnect backoff, join timeout, ICE restart cooldown, etc.) are shared across all three clients. Verify parity with:
```bash
node scripts/check-resilience-constants.mjs
```
Source files: `client/packages/core/src/constants.ts`, `client-android/serenada-core/.../call/WebRtcResilienceConstants.kt`, `client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift`.

## Platform-Specific Rules

- **Camera switching** on both Android and iOS is **mode-based** (`selfie → world → composite`), not binary front/back
- **iOS deep links** must maintain parity for both `serenada.app` and `serenada-app.ru`; changes require updating `client/public/.well-known/apple-app-site-association`
- **iOS simulator** can run signaling but camera preview is unreliable — use a physical device
- **Android WebRTC AAR** changes require regenerating the SHA-256 checksum file

## Documentation to Update When Making Changes

Only update docs directly relevant to your change:
- `README.md` — end-user overview and quick start
- `AGENTS.md` — agent coding instructions
- `DEPLOY.md` — deployment procedures
- `serenada_protocol_v1.md` — signaling protocol specification
- `push-notifications.md` — push notification docs

## Environment

Server reads `.env` from the project root. See `.env.example` for all variables. Key ones:
- `TURN_SECRET` / `ROOM_ID_SECRET` — security secrets (generate with `openssl rand -hex 32`)
- `ALLOWED_ORIGINS` — CORS origins
- `BLOCK_WEBSOCKET` — test SSE fallback (`hang` or `block`)
- `TRANSPORTS` — transport priority (default: `ws,sse`)

## Testing Deep Link

Use `https://serenada.app/call/YovflsGamCygX912gb26Jeaq8Es` to join a live call for testing.

## gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.

Available skills: `/office-hours`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`, `/design-consultation`, `/review`, `/ship`, `/browse`, `/qa`, `/qa-only`, `/design-review`, `/setup-browser-cookies`, `/retro`, `/investigate`, `/document-release`, `/codex`, `/careful`, `/freeze`, `/guard`, `/unfreeze`, `/gstack-upgrade`.

If gstack skills aren't working, run `cd .claude/skills/gstack && ./setup` to build the binary and register skills.
