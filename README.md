# Connected

A simple, privacy-focused 1:1 video calling application built with WebRTC. No accounts, no tracking, just instant video calls.

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)

## Features

- **Instant calls** – One tap to start, share a link to connect
- **No accounts required** – Just open and call
- **Privacy-first** – No tracking, no analytics, peer-to-peer video
- **Mobile-friendly** – Works on Android Chrome, iOS Safari, and desktop browsers
- **Self-hostable** – Run your own instance with full control

## Quick Start

### Local Development

1. Copy the environment template:
   ```bash
   cp .env.example .env
   ```

2. Start the development stack:
   ```bash
   docker-compose up -d --build
   ```

3. Open http://localhost in your browser

### Production Deployment

See [DEPLOY.md](DEPLOY.md) for detailed self-hosting instructions.

## Architecture

```
┌─────────────────┐         ┌─────────────────┐
│   Browser A     │◄───────►│   Browser B     │
│  (React SPA)    │  WebRTC │  (React SPA)    │
└────────┬────────┘         └────────┬────────┘
         │                           │
         │ WSS (signaling)           │
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
- [Protocol Specification](connected_protocol_v1.md) – WebSocket signaling protocol

## Technology

| Component | Technology |
|-----------|------------|
| Frontend | React 18, TypeScript, Vite |
| Backend | Go 1.21+ |
| Media | WebRTC, Coturn |
| Proxy | Nginx |
| Containers | Docker, Docker Compose |

## License

This project is licensed under the BSD 3-Clause License. See [LICENSE](LICENSE) for details.
