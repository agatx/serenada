# Serenada

A simple, privacy-focused 1:1 video calling application built with WebRTC. No accounts, no tracking, just instant video calls.

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)

## Features

- **Instant calls** – One tap to start, share a link to connect
- **No accounts required** – Just open and call
- **Privacy-first** – No tracking, no analytics, end-to-end encrypted peer-to-peer video
- **Resilient signaling** – WebSocket with SSE fallback when WS is blocked
- **Mobile-friendly** – Works on Android Chrome, iOS Safari, and desktop browsers
- **Desktop screen sharing (web)** – In-call screen share control on desktop browsers that support `getDisplayMedia` (not shown on mobile browsers)
- **Recent calls on home** – Web and Android home screens show your latest calls with live room occupancy (Android supports long-press remove)
- **Android saved rooms** – Name and pin rooms on home, choose whether they appear above or below recent calls, and create links that add named rooms on recipient devices
- **Android camera source cycle** – In-call source switch cycles through `selfie` (default) -> `world` -> `composite` (world feed with circular selfie overlay), automatically skips `composite` when unsupported, and shows a flashlight toggle in `world`/`composite` when flash hardware is available; flashlight preference is remembered during the call and reapplied when returning to supported modes
- **Android world/composite pinch zoom** – When local video is the large in-call view in `world` or `composite`, pinch gesture zooms the camera capture itself so both local preview and the remote participant see the zoomed detail
- **Android HD video toggle (experimental)** – Settings include an `HD Video (experimental)` switch for higher camera/composite quality; default mode keeps legacy `640x480` camera constraints for stability
- **iOS native client (SwiftUI)** – Native iOS app in `client-ios/` mirrors Android parity flow: saved rooms + recents ordering, structured deep-link parsing, invite push toggle, encrypted push snapshots, waiting-room invite action, diagnostics screen, mode-based camera cycle with composite fallback, world/composite pinch zoom, ReplayKit screen share toggle, and in-call realtime stats/debug panel
- **Self-hostable** – Run your own instance with full control
- **Optional join alerts** – Encrypted push notifications with snapshot previews (web + native Android + native iOS)
- **Room invite push** – In waiting state you can explicitly invite subscribers of the room; Android and iOS show these only for saved rooms and have a Settings toggle to disable invite notifications

## Quick Start

### Local Development (Docker)

1. Copy the environment template:
   ```bash
   cp .env.example .env
   ```

2. Build the frontend:
   ```bash
   cd client
   npm install
   npm run build
   ```

3. Start the development stack:
   ```bash
   docker compose up -d --build
   ```

4. Open http://localhost in your browser

### Manual Setup (No Docker)

If you prefer to run the components manually:

#### 1. Frontend (Client)
```bash
cd client
npm install
npm run dev
```

#### 2. Backend (Server)
```bash
cd server
go run .
```
Requires Go 1.24+ and a `.env` file in the root directory.

### Android Client (Kotlin)
The native Android app lives in `client-android/`.

1. Open `client-android/` in Android Studio.
2. Sync Gradle.
3. Run on a device or emulator (minSdk 26).
4. Default WebRTC provider is `local7559` (`client-android/app/libs/libwebrtc-7559_173-arm64.aar`).
5. Rebuild the patched libwebrtc AAR on Linux with `bash tools/build_libwebrtc_android_7559.sh`.
6. When updating that AAR, regenerate `client-android/app/libs/libwebrtc-7559_173-arm64.aar.sha256` (Gradle now verifies it before build).

By default the app targets `https://serenada.app`, and the server host can be changed in Settings.
The Android app language can also be set in Settings: `Auto (default)`, `English`, `Русский`, `Español`, `Français`. `Auto` follows the device language and falls back to English.
To enable native Android push receive, provide Firebase Gradle properties when building the app (`firebaseAppId`, `firebaseApiKey`, `firebaseProjectId`, `firebaseSenderId`).

### iOS Client (Swift + SwiftUI)
The native iOS app lives in `client-ios/`.

1. Install `xcodegen` (if not already installed).
2. Generate project files:
   ```bash
   cd client-ios
   xcodegen generate
   ```
3. Open `SerenadaiOS.xcodeproj` in Xcode.
4. Run `SerenadaiOS` on iOS 16+ simulator/device.
5. Build and vendor pinned WebRTC XCFramework:
   ```bash
   bash tools/build_libwebrtc_ios_7559.sh
   ```
   This script also patches `rtc_base/ssl_roots.h` from the current root bundle (same approach as Android) and strips dSYMs by default to keep repository artifact size manageable.
6. If you replace `client-ios/Vendor/WebRTC/WebRTC.xcframework` manually, regenerate checksum:
   ```bash
   cd client-ios
   ./scripts/update_webrtc_checksum.sh
   ```
7. For local-only device signing overrides (without committing team IDs), use `client-ios/LocalSigning.xcconfig`. See `client-ios/README.md`.

iOS universal links are enabled for `serenada.app` and `serenada-app.ru` via associated domains plus `/.well-known/apple-app-site-association`.
Note: iOS Simulator can run signaling and call flow, but local camera preview reliability varies by host setup; use a physical iPhone to validate local camera capture.

### Production Deployment

See [DEPLOY.md](DEPLOY.md) for detailed self-hosting instructions.

Quick setup script (downloads, installs dependencies, and provisions the stack):
```bash
curl -fsSL https://serenada.app/tools/setup.sh -o setup-serenada.sh
bash setup-serenada.sh
```

### Load Testing (WS Signaling)

The server includes an in-repo load conduit for signaling capacity validation.

Quick run (local Docker stack):
```bash
./server/loadtest/run-local.sh
```

What it does:
- Starts local services with `ENABLE_INTERNAL_STATS=1`
- Sets a local `INTERNAL_STATS_TOKEN` automatically (override via env if needed)
- Validates `/api/room-id` and `/api/internal/stats`
- Runs `go run ./cmd/loadconduit` from `server/`
- Writes a JSON report to `server/loadtest/reports/`

Common overrides:
```bash
START_CLIENTS=20 STEP_CLIENTS=20 MAX_CLIENTS=200 STEADY_SECONDS=600 ./server/loadtest/run-local.sh
```

Stabilization and join-tolerance controls:
```bash
PRE_RAMP_STABILIZE_SECONDS=10 MAX_JOIN_ERROR_RATE=0.005 ./server/loadtest/run-local.sh
```

To avoid local/NAT throttling while testing, set a bypass allowlist:
```bash
RATE_LIMIT_BYPASS_IPS=127.0.0.1,::1 ./server/loadtest/run-local.sh
```

Direct conduit usage:
```bash
cd server
go run ./cmd/loadconduit --base-url http://localhost --report-json ./loadtest/reports/manual.json
```

Detailed request/timing sequence:
- [`server/loadtest/LOAD_SIMULATION_SEQUENCE.md`](server/loadtest/LOAD_SIMULATION_SEQUENCE.md)

## Architecture

```
┌─────────────────┐         ┌─────────────────┐
│   Browser A     │◄───────►│   Browser B     │
│  (React SPA)    │  WebRTC │  (React SPA)    │
└────────┬────────┘         └────────┬────────┘
         │                           │
         │ WS/SSE (signaling)        │
         ▼                           ▼
┌─────────────────────────────────────────────┐
│              Go Signaling Server            │
└─────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────┐
│            STUN/TURN Server (Coturn)        │
└─────────────────────────────────────────────┘
```

- **Frontend**: React + TypeScript + Vite
- **Backend**: Go (signaling server)
- **Media**: WebRTC with STUN/TURN support via Coturn
- **Deployment**: Docker Compose with Nginx reverse proxy

## Documentation

- [Deployment Guide](DEPLOY.md) – Self-hosting instructions
- [Protocol Specification](serenada_protocol_v1.md) – Signaling protocol (WebSocket + SSE)
- [Push Notifications](push-notifications.md) – Encrypted snapshot notifications
- [Android Client README](client-android/README.md) – Kotlin native app setup and build notes
- [iOS Client README](client-ios/README.md) – SwiftUI native app setup and build notes
- `server/loadtest/run-local.sh` – Local signaling load sweep runner
- [`server/loadtest/LOAD_SIMULATION_SEQUENCE.md`](server/loadtest/LOAD_SIMULATION_SEQUENCE.md) – Detailed load-conduit HTTP/WS call sequence and timing

## Technology

| Component | Technology |
|-----------|------------|
| Frontend | React 19, TypeScript, Vite |
| Backend | Go 1.24+ |
| Media | WebRTC, Coturn |
| Proxy | Nginx |
| Containers | Docker, Docker Compose |

## License

This project is licensed under the BSD 3-Clause License. See [LICENSE](LICENSE) for details.
