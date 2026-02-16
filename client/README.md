# Serenada Client

React single-page app built with Vite. The client connects to the Go signaling server over WebSocket with SSE fallback and calls the same backend for room IDs, TURN credentials, and optional push notifications.

## Requirements
- Node.js + npm

## Environment
- `VITE_WS_URL` (optional): override the signaling URL (e.g. `ws://localhost:8080/ws` for local dev).
- `VITE_TRANSPORTS` or `TRANSPORTS` (optional): comma-separated transport priority (default `ws,sse`).

## Scripts
- `npm run dev`: start the Vite dev server.
- `npm run build`: typecheck and build production assets.
- `npm run preview`: preview the production build.
- `npm run lint`: run ESLint.
- `npm run test`: run unit tests.

## Local development
1. Start the Go server in `server` (see root README).
2. From `client`:
   ```bash
   npm install
   VITE_WS_URL=ws://localhost:8080/ws npm run dev
   ```
3. Open `http://localhost:5173`.

## Notes
- Push notifications require a service worker (`public/sw.js`) and HTTPS (or localhost).
- The UI exposes a per-room notification toggle when the browser supports Push.
- In-call screen sharing is available on desktop browsers that implement `navigator.mediaDevices.getDisplayMedia`; it is intentionally hidden on mobile browsers.
