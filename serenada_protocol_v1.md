# Serenada Signaling Protocol (WebSocket + SSE) — v1

**Purpose:** Define the signaling protocol used by the Serenada SPA and backend signaling service to establish and manage **1:1 WebRTC calls** (rooms) via **WebSocket or SSE**.

**Scope:**
- Room join/leave
- Host-designation and host “end call” for all participants
- SDP offer/answer exchange
- ICE candidate exchange (trickle ICE)
- Room capacity enforcement (max 2)
- Basic error handling

**Out of scope:** analytics, auth accounts, multi-party calling, chat, recording, presence across devices

---

## 1. Transport

### 1.1 WebSocket endpoint
- **URL:** `wss://{host}/ws`
- **Protocol:** WebSocket over TLS (WSS)
- **Subprotocol:** *(optional)* `serenada.signaling.v1`

### 1.2 SSE endpoint
SSE is used as a fallback when WebSockets are unavailable.

- **Stream (receive):** `GET https://{host}/sse?sid={sessionId}`
- **Send (client → server):** `POST https://{host}/sse?sid={sessionId}`
- **Session ID:** clients may generate `sid` and reuse it across reconnects; if omitted, server generates one.

### 1.3 Connection lifecycle
- Client opens WS or SSE connection.
- Client sends `join` for a specific `roomId`.
- Server responds with `joined` plus room state (host/peers).
- Clients exchange SDP/ICE via server relay messages.
- Client sends `leave` when leaving a room.
- Host can send `end_room` to terminate the current call session for all.

### 1.4 Message envelope (common)
All messages are JSON objects with a consistent envelope.

```json
{
  "v": 1,
  "type": "join",
  "rid": "roomIdString",
  "sid": "sessionIdString",
  "cid": "clientIdString",
  "to": "optionalTargetClientId",
  "ts": 1735171200000,
  "payload": {}
}
```

**Fields**
- `v` *(number, required)*: protocol version. Always `1` for this spec.
- `type` *(string, required)*: message type (see below).
- `rid` *(string, required for room-scoped messages)*: room ID.
- `sid` *(string, required after join)*: session ID for this connection (server-issued for WebSocket; client-provided or server-issued for SSE).
- `cid` *(string, required after join)*: client ID for this participant (server-issued or client-provided; see 2.2).
- `to` *(string, optional)*: destination client ID for directed relay messages (offer/answer/ice). If omitted, server may infer.
- `ts` *(number, optional)*: client timestamp (ms since epoch). Server may ignore.
- `payload` *(object, optional)*: message-specific data.

**Server requirements**
- Reject non-JSON messages and unknown protocol versions.
- Ignore unknown fields (forward compatibility).
- Enforce max message size (recommended: 64KB).

---

## 2. Identity and roles

### 2.1 Client ID (`cid`)
**Recommendation:** server assigns the `cid` on join and returns it in `joined`.

### 2.2 Session ID (`sid`)
Server assigns `sid` per WebSocket connection and returns it in `joined`. For SSE, the client may pass a `sid` in the URL and the server will reuse it. Clients include it in subsequent messages.

### 2.3 Host
- The **host** is the **first successful joiner** of a room (when room has no participants).
- Host is returned in `joined` and `room_state` messages as `hostCid`.

Host privileges:
- Can issue `end_room`.

---

## 3. Room model

- A **room** is identified by `rid` and exists only while participants are connected (deleted when empty or when host ends the room).
- A **call session** is the live WebRTC connection between up to two participants in that room.
- **Capacity:** max **2** participants connected at once.

If a third participant tries to join:
- Server responds with `error` (code: `ROOM_FULL`) and must not add them to the room.

---

## 4. Message types

### 4.1 `join` (client → server)
Join a room.

```json
{
  "v": 1,
  "type": "join",
  "rid": "AbC123",
  "payload": {
    "device": "android|ios|desktop|unknown",
    "ua": "optional user agent string",
    "capabilities": {
      "trickleIce": true
    },
    "reconnectCid": "optionalPreviousClientId",
    "pushEndpoint": "optionalPushEndpointOrFcmToken",
    "snapshotId": "optionalSnapshotId"
  }
}
```

**Server behavior**
- Validate `rid` as a signed 27-character room token (generated via `/api/room-id`).
- If room is empty, make this participant host.
- If room already has 2 participants, reject with `ROOM_FULL` (unless `reconnectCid` matches a ghost session, in which case the server evicts the ghost and reuses the CID).
- `pushEndpoint` is optional and used only to suppress self-notifications on join:
  - web clients pass their Web Push endpoint URL.
  - native Android clients pass their FCM registration token.
- On success, respond with `joined`.

---

### 4.2 `joined` (server → client)
Acknowledges join success and provides room state.

```json
{
  "v": 1,
  "type": "joined",
  "rid": "AbC123",
  "sid": "S-9f0c...",
  "cid": "C-a1b2...",
  "payload": {
    "hostCid": "C-a1b2...",
    "participants": [
      { "cid": "C-a1b2...", "joinedAt": 1735171200000 },
      { "cid": "C-c3d4...", "joinedAt": 1735171215000 }
    ],
    "turnToken": "T-abc123yz...",
    "turnTokenExpiresAt": 1735174800
  }
}
```

**Fields in payload**
- `hostCid` *(string)*: client ID of the current host.
- `participants` *(array)*: list of current participants.
- `turnToken` *(string, optional)*: temporary token for fetching TURN credentials from `/api/turn-credentials`. Only present on successful join.
- `turnTokenExpiresAt` *(number, optional)*: unix timestamp (seconds) when the token expires.

**Client behavior**
- Store `sid`, `cid`, and `turnToken`.
- Immediately fetch ICE servers using the `turnToken` via the `token` query param on `/api/turn-credentials`.
- If another participant is already present, proceed to WebRTC negotiation using the rules in section 5.

---

### 4.3 `room_state` (server → client)
Sent when participants join/leave or host changes.

```json
{
  "v": 1,
  "type": "room_state",
  "rid": "AbC123",
  "payload": {
    "hostCid": "C-a1b2...",
    "participants": [
      { "cid": "C-a1b2..." },
      { "cid": "C-c3d4..." }
    ]
  }
}
```

**Client behavior**
- Update UI for “waiting for someone to join” vs “in call”.
- If participant list shrinks to 1 during a call, treat as remote left.

---

### 4.4 `leave` (client → server)
Leave the room.

```json
{
  "v": 1,
  "type": "leave",
  "rid": "AbC123",
  "sid": "S-9f0c...",
  "cid": "C-a1b2..."
}
```

**Server behavior**
- Remove participant from room.
- Broadcast `room_state` to remaining participant (if any).
- If host leaves and another participant remains, server transfers host to the remaining participant.

---

### 4.5 `end_room` (host client → server)
Host ends the call session for everyone in the room.

```json
{
  "v": 1,
  "type": "end_room",
  "rid": "AbC123",
  "sid": "S-9f0c...",
  "cid": "C-a1b2...",
  "payload": {
    "reason": "host_ended"
  }
}
```

**Server behavior**
- Validate sender is current host.
- Broadcast `room_ended` to all participants.
- Delete the room; clients must re-join to start a new session.

---

### 4.6 `room_ended` (server → client)
Notifies participants the host ended the call.

```json
{
  "v": 1,
  "type": "room_ended",
  "rid": "AbC123",
  "payload": {
    "by": "C-a1b2...",
    "reason": "host_ended"
  }
}
```

**Client behavior**
- Immediately close RTCPeerConnection.
- Reset room UI state; local media may remain active until the user leaves.
- If user reloads the link, they may `join` again.

---

### 4.7 `offer` (client → server) and `offer` relay (server → client)
Carries SDP offer from one participant to the other.

Client → server:
```json
{
  "v": 1,
  "type": "offer",
  "rid": "AbC123",
  "sid": "S-...",
  "cid": "C-a1b2...",
  "to": "C-c3d4...",
  "payload": {
    "sdp": "v=0\r\n..."
  }
}
```

Server → client (relay):
```json
{
  "v": 1,
  "type": "offer",
  "rid": "AbC123",
  "payload": {
    "from": "C-a1b2...",
    "sdp": "v=0\r\n..."
  }
}
```

---

### 4.8 `answer` (client → server) and `answer` relay (server → client)
Carries SDP answer back to offerer.

Client → server:
```json
{
  "v": 1,
  "type": "answer",
  "rid": "AbC123",
  "sid": "S-...",
  "cid": "C-c3d4...",
  "to": "C-a1b2...",
  "payload": {
    "sdp": "v=0\r\n..."
  }
}
```

Server → client (relay):
```json
{
  "v": 1,
  "type": "answer",
  "rid": "AbC123",
  "payload": {
    "from": "C-c3d4...",
    "sdp": "v=0\r\n..."
  }
}
```

---

### 4.9 `ice` (client → server) and `ice` relay (server → client)
Trickle ICE candidate exchange.

Client → server:
```json
{
  "v": 1,
  "type": "ice",
  "rid": "AbC123",
  "sid": "S-...",
  "cid": "C-a1b2...",
  "to": "C-c3d4...",
  "payload": {
    "candidate": {
      "candidate": "candidate:...",
      "sdpMid": "0",
      "sdpMLineIndex": 0,
      "usernameFragment": "abc123"
    }
  }
}
```

Server → client (relay):
```json
{
  "v": 1,
  "type": "ice",
  "rid": "AbC123",
  "payload": {
    "from": "C-a1b2...",
    "candidate": {
      "candidate": "candidate:...",
      "sdpMid": "0",
      "sdpMLineIndex": 0,
      "usernameFragment": "abc123"
    }
  }
}
```

**Notes**
- Candidates may be `null` to signal end-of-candidates (optional; many apps omit). If used:
  - `payload.candidate` may be `null`.

---

### 4.10 `error` (server → client)
Standard error message.

```json
{
  "v": 1,
  "type": "error",
  "rid": "AbC123",
  "payload": {
    "code": "ROOM_FULL",
    "message": "This call is full.",
    "retryable": false
  }
}
```

**Error codes**
- `BAD_REQUEST` — invalid JSON, missing required fields, invalid types
- `UNSUPPORTED_VERSION` — `v` not supported
- `ROOM_FULL` — capacity exceeded (2 participants)
- `NOT_HOST` — non-host attempted `end_room`
- `SERVER_NOT_CONFIGURED` — room ID secret missing on server
- `INVALID_ROOM_ID` — room ID failed validation
- `INTERNAL` — unexpected server error

---

### 4.11 `ping` (client → server)
Client keepalive. Server ignores.

```json
{
  "v": 1,
  "type": "ping",
  "payload": { "ts": 1735171200000 }
}
```

---

### 4.12 Room Status Monitoring (WebSocket/SSE)

Used to aggregate real-time occupancy for a list of rooms (e.g., recent calls list).
Currently consumed by both the React web home screen and the native Android home screen recent-calls UX.

#### `watch_rooms` (client → server)
Subscribe to updates for a list of rooms.

```json
{
  "v": 1,
  "type": "watch_rooms",
  "payload": {
    "rids": ["AbC123", "XyZ789"]
  }
}
```

#### `room_statuses` (server → client)
Immediate response to `watch_rooms` with current counts.

```json
{
  "v": 1,
  "type": "room_statuses",
  "payload": {
    "AbC123": 1,
    "XyZ789": 2
  }
}
```

#### `room_status_update` (server → client)
Pushed whenever a watched room's participant count changes.

```json
{
  "v": 1,
  "type": "room_status_update",
  "payload": {
    "rid": "AbC123",
    "count": 0
  }
}
```

---

## 5. WebRTC negotiation rules (1:1)

### 5.1 Roles for offer/answer
To avoid “glare” (both sides sending offers), assign roles deterministically:

- **Host is the offerer** when a second participant joins.
- Non-host is the answerer.

**Rule:**
- When a client receives `room_state` showing exactly 2 participants:
  - If you are host: create and send `offer` to the other participant.
  - If you are not host: wait for `offer` and respond with `answer`.

### 5.2 Local media
- Client may attempt to start local media before join for preview; browsers may require user gesture.
- Add tracks to `RTCPeerConnection` before creating offer/answer.

### 5.3 Trickle ICE
- Both sides send `ice` as candidates are discovered.
- Both sides add received candidates promptly.

### 5.4 Disconnect / remote leave
- If remote leaves (room_state goes to 1 participant) or a `room_ended` is received:
  - Close RTCPeerConnection and clear remote media
  - Keep local media running while waiting (stop when user leaves)

---

## 6. Ordering and reliability

### 6.1 Message ordering
WebSocket/SSE preserve ordering per connection, but relay messages across clients can interleave. Clients must tolerate:
- ICE arriving before SDP is set
- Answer arriving quickly after offer

**Client guidance**
- If ICE arrives before `setRemoteDescription`, queue candidates and apply after remote description is set.

### 6.2 Idempotency
- `leave` is idempotent: repeated calls should not crash server.
- `end_room` may be treated as idempotent for a short window (recommended).

---

## 7. Backend responsibilities

### 7.1 Room state management
Backend maintains:
- `rid`
- list of current participants (`cid`, socket)
- `hostCid`

### 7.2 Relay policy
For `offer`, `answer`, `ice`:
- Validate sender is in room.
- If `to` is present and matches a participant, relay only to that participant; otherwise relay to all other participants.
- Do not persist SDP/ICE long-term; keep in-memory only.

### 7.3 Capacity enforcement
- Refuse third join with `ROOM_FULL`.
- Never allow more than 2 participants present concurrently.

### 7.4 Cleanup
- On socket disconnect: treat as `leave`.
- If room becomes empty: delete room.

---

## 8. HTTP API

### 8.1 `GET|POST /api/room-id`
Generates a new room ID.

This endpoint is also suitable for a basic server-host validity probe on clients (for example, Android Settings save validation). A valid Serenada server must return JSON with a non-empty `roomId`.

**Response**
```json
{ "roomId": "AbC123..." }
```

**Errors**
- `503 Service Unavailable` if `ROOM_ID_SECRET` is not configured.

### 8.2 `GET /api/turn-credentials?token=...`
Returns TURN credentials for a valid TURN token. The token is issued by the backend after a participant joins a room and returned in the `joined` message. Alternatively, the token could be returned by /api/diagnostic-token.

**Response**
```json
{
  "username": "1700000000:client-ip",
  "password": "base64-hmac",
  "uris": ["stun:host", "turn:host", "turns:host:5349?transport=tcp"],
  "ttl": 900
}
```

**Errors**
- `401 Unauthorized` if token is missing or invalid.
- `503 Service Unavailable` if STUN/TURN is not configured.

### 8.3 `GET|POST /api/diagnostic-token`
Issues a short-lived diagnostic TURN token (5 seconds).

**Response**
```json
{ "token": "payload.signature", "expires": 1735174800 }
```

### 8.4 `POST /api/push/invite?roomId=...`
Triggers a room invite push notification to subscribers of the room.

**Request body**
```json
{ "endpoint": "optionalSenderEndpointOrFcmToken" }
```

**Behavior**
- Validates `roomId`.
- Sends push payload with `kind: "invite"`, `url: "/call/{roomId}"`, and localized `title/body`.
- If `endpoint` is provided, the server excludes that endpoint from delivery to avoid self-notifications.

---

## 9. Security requirements

- **HTTPS for APIs, WebSocket/SSE for signaling**.
- **TURN Gating**: TURN tokens are only issued in the `joined` message after successful `rid` validation, preventing unauthorized use of the TURN relay by unauthenticated clients.
- Rate limit:
  - new WS connections per IP
  - SSE requests per IP
  - TURN credentials, room-id, and push API endpoints
- Validate message sizes and required fields.
- Room IDs are unguessable; do not expose sequential identifiers.
- Do not log SDP bodies in plaintext at info level (they can include network details). If needed, log only lengths or hashed summaries.

---

## 10. Client state machine (recommended)

**Disconnected**
→ connect WS/SSE
→ **SocketConnected**
→ send `join`
→ **Joined (Waiting)** (1 participant)
→ if 2 participants & host: create offer → **Negotiating**
→ if receive offer: set remote, create answer → **Negotiating**
→ when ICE connected: **InCall**
→ on remote leave: **Joined (Waiting)**
→ on `room_ended`: **Ended**
→ leave/home: **Disconnected**

---

## 11. Minimal conformance checklist

### Client
- [ ] Connect WS/SSE, send `join` on call page
- [ ] Show “Join Call” and only call `getUserMedia` after user gesture
- [ ] Implement host-as-offerer rule to avoid glare
- [ ] Trickle ICE send/receive with queueing before remote SDP is set
- [ ] Handle `room_state`, `room_ended`, and `error`
- [ ] Stop local tracks on explicit leave

### Backend
- [ ] Accept WS/SSE, parse JSON, validate schema
- [ ] Create room on first join
- [ ] Enforce max 2 participants
- [ ] Assign hostCid and transfer host if host leaves
- [ ] Relay offer/answer/ice to correct peer
- [ ] Broadcast `room_state` updates
- [ ] Implement `end_room` and broadcast `room_ended`
