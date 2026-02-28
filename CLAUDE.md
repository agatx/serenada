# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Serenada is a privacy-focused 1:1 WebRTC video calling application. No accounts, no tracking — just instant peer-to-peer video calls. The project has four codebases: a React web client, a Go signaling server, a Kotlin/Compose Android client, and a SwiftUI iOS client.

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

### Load Testing
```bash
./server/loadtest/run-local.sh    # Run signaling load sweep against local Docker stack
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
React 19 + TypeScript + Vite SPA. Key structure:
- `src/contexts/SignalingContext.tsx` — signaling state management
- `src/contexts/WebRTCContext.tsx` — media streams, peer connection (~987 LOC)
- `src/contexts/signaling/transports/` — pluggable WS and SSE transports
- `src/pages/Home.tsx` — room creation/selection
- `src/pages/CallRoom.tsx` — main video call UI (~991 LOC)
- `src/i18n.ts` — internationalization (en, ru, es, fr)
- `src/utils/pushCrypto.ts` — push notification encryption

Routing: `/` → Home, `/call/:roomId` → CallRoom.

### Android Client (`client-android/`)
Kotlin + Jetpack Compose + Material3. Key classes:
- `call/CallManager.kt` — call orchestrator
- `call/WebRtcEngine.kt` — WebRTC integration
- `call/SignalingClient.kt` — protocol v1 signaling
- `call/CompositeCameraCapturer.kt` — 3-mode camera (selfie → world → composite)
- `ui/` — Compose screens (CallScreen, JoinScreen, SettingsScreen, DiagnosticsScreen)
- `push/` — Firebase Cloud Messaging integration

WebRTC: custom-built AAR from branch-heads/7559_173 in `app/libs/`, verified with SHA-256 checksum.

### iOS Client (`client-ios/`)
SwiftUI + Swift 5.10, project generated via XcodeGen (`project.yml`). Key classes:
- `Sources/Core/Call/CallManager.swift` — call orchestrator
- `Sources/Core/Call/WebRtcEngine.swift` — WebRTC integration
- `Sources/Core/Signaling/` — protocol v1 signaling
- `Sources/UI/Screens/` — SwiftUI screens (mirrors Android screen parity)
- `Sources/Core/Push/` — Firebase Messaging
- `NotificationService/` — push notification app extension

WebRTC: custom-built XCFramework from branch-heads/7559_173 in `Vendor/WebRTC/`.

## Signaling Protocol (v1)

JSON message envelope with fields: `v` (version), `type`, `rid` (room ID), `sid` (session ID), `cid` (client ID), `to` (target), `ts` (timestamp). Dual transport: WebSocket primary, SSE fallback. Message types include: join, joined, leave, offer, answer, ice-candidate, end_room, error.

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
