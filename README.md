# Serenada

A simple, privacy-focused 1:1 video calling application built with WebRTC. No accounts, no tracking, just instant video calls.

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)

## Features

- **Instant calls** – One tap to start, share a link to connect
- **No accounts required** – Just open and call
- **Privacy-first** – No tracking, no analytics, end-to-end encrypted peer-to-peer video
- **Resilient signaling** – WebSocket with SSE fallback when WS is blocked
- **Mobile-friendly** – Works on Android Chrome, iOS Safari, and desktop browsers
- **Recent calls on home** – Web and Android home screens show your latest calls with live room occupancy (Android supports long-press remove)
- **Android camera source cycle** – In-call source switch cycles through `selfie` (default) -> `world` -> `composite` (world feed with circular selfie overlay)
- **Self-hostable** – Run your own instance with full control
- **Optional join alerts** – Encrypted push notifications with snapshot previews (opt-in)

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

By default the app targets `https://serenada.app`, and the server host can be changed in Settings.
The Android app language can also be set in Settings: `Auto (default)`, `English`, `Русский`, `Español`, `Français`. `Auto` follows the device language and falls back to English.

### Production Deployment

See [DEPLOY.md](DEPLOY.md) for detailed self-hosting instructions.

Quick setup script (downloads, installs dependencies, and provisions the stack):
```bash
curl -fsSL https://serenada.app/tools/setup.sh -o setup-serenada.sh
bash setup-serenada.sh
```

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
