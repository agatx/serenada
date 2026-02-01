# Serenada Android (Kotlin) Port - Project Plan

## 1) Goals and non-goals

### Goals (initial version)
- Native Android app in Kotlin with feature parity for core 1:1 calling flows in the React client.
- Signaling via WebSocket only (no SSE in initial build).
- WebRTC audio/video calls with basic call controls and waiting state.
- Deep link handling for https://serenada.app/call/* links when installed.
- App can continue an ongoing call in background (foreground service + notification).

### Non-goals (initial version)
- SSE fallback support.
- Push notifications.
- Multi-party calls, accounts, analytics, or chat.

## 2) Constraints and assumptions
- Follow existing signaling protocol v1 (WebSocket only in phase 1).
- No new backend changes required for MVP.
- Android minimum SDK is 26
- Production-critical repo: minimal, targeted changes.

## 3) Architecture overview

### 3.1 Proposed modules
- app (Android entry point, navigation, UI)
- core-network (HTTP + WebSocket client, retry/backoff)
- signaling (protocol v1 encoder/decoder, room state reducer)
- webrtc (peer connection, media tracks, ICE handling)
- data (repository for room/session state, persistence if needed)

### 3.2 Key components
- SignalingClient: manages WebSocket connection lifecycle and message exchange.
- RoomController: state machine for join/leave/host/end_room and negotiation triggers.
- WebRtcEngine: wraps PeerConnectionFactory, local/remote tracks, SDP offer/answer, ICE.
- CallService (Foreground Service): keeps call alive in background with ongoing notification.
- DeepLinkHandler: parses incoming /call/{rid} and launches/join flow.

### 3.3 Technology choices (to confirm)
- UI: Jetpack Compose + Navigation.
- Networking: OkHttp WebSocket (or Ktor) - pick one and standardize.
- WebRTC: org.webrtc:google-webrtc (official maven), aligned with WebRTC version used by browser.

## 4) Functional requirements

### 4.1 Signaling (WebSocket only)
- Connect to wss://{host}/ws and follow serenada_protocol_v1.
- Implement messages: join, joined, room_state, offer, answer, ice, leave, end_room, error.
- Session and client ID handling per protocol (store sid, cid after joined).
- TURN credential fetch using turnToken from joined payload.

### 4.2 Call flow
- Create/join room using /call/{rid} link. Generate room ID using /api/room-id endpoint if starting a new room.
- If alone: show waiting state; if second participant joins: initiate negotiation (host offers).
- Handle participant leave and host end_room.

### 4.3 UI parity (initial)
- Join screen (open link, show room id).
- Waiting screen (share/copy link).
- In-call screen (local/remote video, mute, camera toggle, hang up).
- Error states (room full, disconnected, invalid link).

### 4.4 Background behavior
- When call is active, start a foreground service with ongoing notification.
- Keep WebRTC and signaling alive while app is backgrounded.
- Handle audio focus and camera/mic permissions.
- If app is swiped away, call should end and resources released.

### 4.5 Deep links
- App Links for https://serenada.app/call/* with autoVerify in manifest.
- Fallback Intent filter for plain https deep links.
- Parse {rid} and launch join flow.
- For debug/staging, allow override host in settings (optional).

## 5) Non-functional requirements
- Security: TLS only, no cleartext.
- Privacy: no analytics or tracking.
- Performance: quick join, low latency, keep memory stable during calls.
- Reliability: reconnect strategies for WebSocket drop (simple retry with backoff).

## 6) Phased delivery plan

### Phase 0 - Discovery and groundwork
- Audit React client for UI flows and signaling usage.
- Identify exact signaling messages and edge cases used by current SPA.
- Decide minSdk, targetSdk, and library stack.
- Create Android project skeleton and CI build (if needed).

### Phase 1 - MVP (WebSocket only, no SSE, no push)
- Implement signaling client (WebSocket) with protocol v1.
- Implement WebRTC engine (local/remote tracks, offer/answer, ICE).
- Build Compose UI for join/waiting/in-call/error.
- Implement TURN token fetch.
- Add foreground service for background call stability.
- Add deep link interception for /call/*.
- Basic manual QA on real devices.

### Phase 2 - Hardening
- Improve reconnect logic and error handling.
- Add instrumentation tests for call flow and deep links.
- Battery and network stress testing.
- Prepare release checklist.

### Phase 3 - SSE fallback (future)
- Add SSE client for receive and POST for send.
- Feature flag to prefer WS, fallback to SSE when WS fails.
- Update protocol handling for SSE sid semantics.

### Phase 4 - Push notifications (future)
- Implement push endpoint registration in join payload.
- Add encrypted snapshot handling per push-notifications.md.
- Integrate FCM and server-side push support.

## 7) Integration points and API usage
- /ws for signaling (WebSocket).
- /api/room-id for room creation (if used by client).
- /api/turn-credentials?token=... for ICE server config.
- /call/{rid} for deep links.

## 8) Testing strategy
- Unit tests for signaling encoder/decoder and room state reducer.
- Integration tests for WebRTC negotiation (device or emulator).
- Deep link intent tests.
- Manual regression tests vs web client.

## 9) Risks and mitigations
- Background restrictions: require foreground service and user-visible notification.
- WebRTC device compatibility: test on a range of Android versions and OEMs.
- NAT traversal reliability: ensure TURN token flow is correct.
- Deep link verification requires assetlinks.json on serenada.app.

## 10) Open questions
- What minSdk is acceptable for target audience?
  - API 26
- Should the Android app allow custom host or only serenada.app?
  - Make it configurable
- Do we need to support screen sharing or audio-only modes in MVP?
  - No
- Is the current React UI a strict pixel-for-pixel requirement or functional parity only?
  - Functional parity, modern-looking native UI

## 11) Deliverables
- Android project under client-andorid/ (new module).
- MVP APK for internal testing.
- Documentation updates in README.md (link to this plan).
