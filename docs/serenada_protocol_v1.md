# Serenada Signaling Protocol (WebSocket + SSE) — v1

**Purpose:** Define the signaling protocol used by Serenada clients and the backend signaling service to establish and manage WebRTC call rooms with **directed 1:1 or mesh multi-party connections (up to 4 participants)** via **WebSocket or SSE**.

**Scope:**
- Room join/leave
- Host-designation and host "end call" for all participants
- Capability-aware room creation and admission
- SDP offer/answer exchange
- ICE candidate exchange (trickle ICE)
- Room capacity negotiation and enforcement
- Basic error handling

**Out of scope:** analytics, auth accounts, chat, recording, presence across devices

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
- A **call session** is the live WebRTC mesh between the room's current participants.
- Rooms start with an effective capacity of **2** participants.
- When the creator requested a higher room size, the room stays provisional at **2** until a second distinct participant joins.
- At that second join, the server locks the room's final capacity for the rest of the room lifetime:
  - `min(creatorRequestedMaxParticipants, secondParticipantSupportedMaxParticipants, serverCeiling)`
  - if that result is `2`, the room stays 1:1
  - if that result is greater than `2`, the room becomes a group-capable room

If a join would exceed the room's locked capacity:
- Server responds with `error` (code: `ROOM_FULL`) and must not add that client to the room.

If a legacy 1:1-only client tries to join a room whose locked `maxParticipants` is greater than `2`:
- Server responds with `error` (code: `ROOM_CAPACITY_UNSUPPORTED`) and must not add the participant to the room.

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
      "trickleIce": true,
      "maxParticipants": 4
    },
    "createMaxParticipants": 4,
    "displayName": "optional display name",
    "peerId": "optional host-supplied stable identity",
    "reconnectCid": "optionalPreviousClientId"
  }
}
```

**Notes**
- `peerId` is opaque to the server and forwarded verbatim in `participants` entries
  for `joined` and `room_state`. It lets host applications correlate a participant
  to their own user identity (for avatar lookup, telemetry, etc.) — distinct from
  `cid`, which is per-call and server-issued. Trimmed and truncated to 128
  characters; an empty string clears the stored value (matching `displayName`).

**Server behavior**
- Validate `rid` as a signed 27-character room token (generated via `/api/room-id`).
- If room is empty, make this participant host.
- If the room does not yet exist, clamp `createMaxParticipants` by the creator's `capabilities.maxParticipants` and the server ceiling, then create the room:
  - if the clamped value is `2`, the room is immediately locked as 1:1
  - if the clamped value is greater than `2`, the room is created provisionally with effective `maxParticipants=2`
- When a second distinct participant joins a provisional room, lock the room's final `maxParticipants` using the rule from section 3.
- If a client joins after the room capacity is locked and its `capabilities.maxParticipants` is lower than the room's locked capacity, reject with `ROOM_CAPACITY_UNSUPPORTED`.
- If room occupancy already equals the room's current effective capacity, reject with `ROOM_FULL` (unless `reconnectCid` matches a ghost session, in which case the server evicts the ghost and reuses the CID).
- On success, respond with `joined`.
- Push notifications are **not** triggered on join. Instead, clients send a separate `POST /api/push/notify` request after receiving `joined` (see push-notifications.md).

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
    "maxParticipants": 4,
    "participants": [
      { "cid": "C-a1b2...", "joinedAt": 1735171200000, "displayName": "Alice" },
      { "cid": "C-c3d4...", "joinedAt": 1735171215000 }
    ],
    "turnToken": "T-abc123yz...",
    "turnTokenExpiresAt": 1735174800,
    "reconnectToken": "ab12...c3.1735174800",
    "reconnectTokenTTLMs": 1200000,
    "epoch": 7,
    "reconnect": "reattached"
  }
}
```

**Fields in payload**
- `hostCid` *(string)*: client ID of the current host.
- `maxParticipants` *(number)*: current effective room capacity. For a newly created group-requested room, this is `2` until the second distinct participant joins and locks the final room capacity.
- `participants` *(array)*: list of current participants. Each entry has `cid` *(string)*, `joinedAt` *(number, optional)*, `displayName` *(string, optional)*, `peerId` *(string, optional)*, `audioEnabled` *(boolean, optional)*, `videoEnabled` *(boolean, optional)*, `connectionStatus` *(string, optional)*, and `contentState` *(object, optional)*. See section 4.3 for the meaning of `connectionStatus` and `contentState`.
- `turnToken` *(string, optional)*: temporary token for fetching TURN credentials from `/api/turn-credentials`. Only present on successful join.
- `turnTokenExpiresAt` *(number, optional)*: unix timestamp (seconds) when the token expires.
- `reconnectToken` *(string, optional)*: opaque proof bound to `(cid, rid, expiresAt)` that the SDK persists and presents on a future `join` to reattach or recover the same CID. Format is implementation-defined; clients should treat it as opaque.
- `reconnectTokenTTLMs` *(number, optional)*: how long (ms) the server will accept this reconnect token. Current servers issue 20-minute reconnect tokens. SDKs that persist the token across launches should clear it once the window has elapsed.
- `epoch` *(number, optional)*: monotonic room state epoch advanced by the server on every membership-mutating operation. SDKs gate ICE restart on receiving an authoritative post-reconnect snapshot rather than acting on a stale in-memory peer map.
- `reconnect` *(string, optional)*: outcome of this join. Values: `"fresh"` (server created a new participant identity — CID may be new), `"reattached"` (server attached the new transport to a still-present participant slot), `"recovered"` (the previous participant record was gone but the reconnect token still validated, so the server recreated the record with the requested CID). Older servers may omit this field; SDKs should treat absent as `"fresh"`.

**Client behavior**
- Store `sid`, `cid`, and `turnToken`.
- Immediately fetch ICE servers using the `turnToken` via the `token` query param on `/api/turn-credentials`.
- Persist `reconnectToken` (with `reconnectTokenTTLMs`) so a future join can reattach or recover identity.
- While connected, refresh `reconnectToken` 10 minutes before expiration by sending `reconnect-token-refresh`. This leaves roughly the server suspend window for reconnect if the transport drops just before the scheduled refresh.
- For `"reattached"`/`"recovered"` outcomes, keep media-active `RTCPeerConnection`s in place; renegotiate only for pairs the server flags via `negotiation_dirty` (section 4.10) or that the SDK considers stale based on local heuristics.
- For `"fresh"` outcomes, the SDK may treat the call as ground-up new; an existing `RTCPeerConnection` should still only be torn down if no media has flowed recently.
- Wait for the authoritative `room_state` snapshot the server emits immediately after `joined` before scheduling renegotiation against this transport's view of the peer set.
- If another participant is already present, proceed to WebRTC negotiation using the rules in section 5.

The server emits an authoritative `room_state` (section 4.3) immediately after every successful `joined`, even when membership did not change during the outage. SDKs use that broadcast as the reliable post-reconnect sync point.

---

### 4.2.1 `reconnect-token-refresh` (client → server) and `reconnect-token-refreshed` (server → client)
Active participants refresh reconnect authority before the current token expires. This refresh is independent of TURN refresh; TURN refresh may be skipped when all peer paths are direct.

Client request:

```json
{ "v": 1, "type": "reconnect-token-refresh", "rid": "AbC123", "cid": "C-a1b2..." }
```

Server response:

```json
{
  "v": 1,
  "type": "reconnect-token-refreshed",
  "rid": "AbC123",
  "payload": {
    "reconnectToken": "de45...f6.1735175400",
    "reconnectTokenTTLMs": 1200000
  }
}
```

The server only honors this request from the currently attached transport for the participant CID. SDKs should replace their stored reconnect token, update persisted recovery expiry, and schedule the next refresh 10 minutes before the new expiration.

---

### 4.3 `room_state` (server → client)
Sent when participants join/leave, host changes, or a participant's transport
state transitions between connected and suspended. Also delivered to a
single client immediately after every successful `joined` so the SDK has a
reliable post-reconnect sync point even when membership did not change
during the outage.

```json
{
  "v": 1,
  "type": "room_state",
  "rid": "AbC123",
  "payload": {
    "hostCid": "C-a1b2...",
    "maxParticipants": 4,
    "epoch": 7,
    "participants": [
      { "cid": "C-a1b2...", "joinedAt": 1735171200000, "displayName": "Alice" },
      { "cid": "C-c3d4...", "joinedAt": 1735171215000, "connectionStatus": "suspended", "contentState": { "active": true, "contentType": "screen" } }
    ]
  }
}
```

**Payload `epoch`**

`epoch` is a monotonic counter advanced by the server on every
membership-mutating operation (join, leave, suspend, reattach, evict, host
transfer, end_room). SDKs use it to gate ICE restart on an authoritative
post-reconnect snapshot rather than acting on a stale in-memory peer map.
Older servers may omit this field. Forward compatibility: SDKs should
tolerate `epoch` being absent and only act on epoch comparisons when the
server has supplied them.

**Participant `connectionStatus`**

Each participant entry may carry an optional `connectionStatus` field. Values:

- **Absent / `"active"`**: participant's signaling transport is currently
  attached. Peers should treat the participant as normally present.
- **`"suspended"`**: the participant's signaling transport dropped (network
  blip, app backgrounded, TCP reset, etc.) but the server is holding the
  participant's slot open for reconnect. Established WebRTC peer connections
  to this participant MUST NOT be torn down. Clients may display a
  "reconnecting" indicator. The participant's slot is released only after a
  server-side hard-eviction window elapses, at which point the participant
  will be absent from the next `room_state` broadcast.

Unknown `connectionStatus` values must be treated as `"active"` for forward
compatibility.

**Participant `contentState`**

Each participant entry may carry an optional `contentState` object that
describes the participant's latest ephemeral content metadata (screen
share, content camera mode, etc.). Persisting this on the participant
record means a peer reconnecting after a suspension reconstructs UI from
the next `room_state` without waiting for the sender to toggle content
again.

Fields:
- `active` *(boolean, required)*: whether content is currently being
  shared. When `false`, `contentType` is omitted.
- `contentType` *(string, optional)*: free-form content kind (for
  example `"screen"`).
- `updatedAtMs` *(number, optional)*: unix-ms timestamp of the last
  content state transition.
- `epoch` *(number, optional)*: room state epoch at which the latest
  content state transition was recorded.

Older servers may omit `contentState` entirely; SDKs should treat that as
"unknown — preserve current local state" rather than implicitly clearing.

**Client behavior**
- Update UI for "waiting for someone to join" vs "in call".
- Treat `maxParticipants` as the room's current effective capacity. It may increase from `2` to a higher locked value when the second participant joins a provisional room.
- Treat `joinedAt` as informational only. It may be shown in UI, but clients must not depend on it for offer ownership.
- On `connectionStatus="suspended"` for a peer: keep the existing peer connection alive; do not close tracks or release slots. Optionally surface a "reconnecting" UI state.
- On a peer transitioning from `"suspended"` back to active: do not renegotiate proactively; the returning peer is responsible for triggering an ICE restart if the path has decayed.
- If a participant disappears entirely (absent from the participants list): treat as remote left and clean up the peer connection.
- If the participant list shrinks to 1 during a call, treat as remote left.

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
- `ROOM_FULL` — current room capacity exceeded
- `ROOM_CAPACITY_UNSUPPORTED` — this client does not support the room's locked group capacity
- `NOT_HOST` — non-host attempted `end_room`
- `SERVER_NOT_CONFIGURED` — room ID secret missing on server
- `INVALID_ROOM_ID` — room ID failed validation
- `INVALID_RECONNECT_TOKEN` — supplied `reconnectToken` failed signature/expiry validation. SDKs MUST clear persisted reconnect state. If the room is still intended to be joined, SDKs SHOULD automatically retry `join` without `reconnectCid`/`reconnectToken`; otherwise they may surface a dedicated terminal error (e.g. `sessionExpired`). When an expired-but-valid token targets a suspended participant, the server removes that stale record before returning this error so the fresh retry does not create a self-ghost.
- `ROOM_ENDED` — the room was explicitly ended (host `end_room`) within the server-side tombstone window. Returned to a reconnect attempt that presents valid reconnect authority for a now-gone room. Payload may include `"reason": "ended_by_host"`. SDKs MUST treat this as terminal and clear persisted reconnect state.
- `INTERNAL` — unexpected server error

---

### 4.14 `relay_failed` (server → client)

Notifies a sender that a previously-routable peer message could not be
delivered because the target was suspended at the time. Emitted only for
negotiation traffic (`offer`, `answer`, `ice`); `content_state` is handled
via the participant content metadata in section 4.3.

```json
{
  "v": 1,
  "type": "relay_failed",
  "rid": "AbC123",
  "payload": {
    "reason": "target_suspended",
    "targets": ["C-c3d4..."],
    "of": "offer"
  }
}
```

**Client behavior**
- Suppress further negotiation toward the named CIDs while they remain
  suspended. Old offers/ICE may have already moved on; replaying them is
  worse than missing them.
- Wait for `negotiation_dirty` (section 4.15) once the peer reattaches,
  then perform glare-safe fresh negotiation/ICE restart for that pair.

---

### 4.15 `negotiation_dirty` (server → client)

Tells the sender that a previously-suspended peer has reattached AND that
the sender had pending negotiation traffic to it during the suspension.
Emitted after the authoritative post-reconnect `room_state` so SDKs can
schedule fresh negotiation against confirmed state.

```json
{
  "v": 1,
  "type": "negotiation_dirty",
  "rid": "AbC123",
  "payload": {
    "with": "C-c3d4..."
  }
}
```

**Client behavior**
- Schedule a glare-safe fresh negotiation or ICE restart for the named
  CID. Do NOT replay the previously-buffered SDP/ICE.

---

### 4.16 `media_liveness` (client → server)

Hint reported by an active client that it is currently receiving inbound
media from one or more remote CIDs. The server uses this to defer
hard-eviction of a suspended participant when at least one peer still sees
its media — a participant whose signaling transport is late to recover but
whose media is still flowing should not be removed solely because the
directory clock fired.

```json
{
  "v": 1,
  "type": "media_liveness",
  "rid": "AbC123",
  "payload": {
    "cids": ["C-c3d4...", "C-e5f6..."]
  }
}
```

**Server behavior**
- Records the most recent unix-ms timestamp at which any active peer
  reported inbound media for each CID.
- During hard-eviction, defers removal while a recent liveness report
  exists; re-evaluates after a short window. Liveness is a cleanup hint
  only — it never authorizes anything else and never extends the slot
  indefinitely.

**Client behavior**
- Send periodically while in a call (recommended every ~5 s) for any
  remote CID whose inbound media is currently flowing. Send immediately
  after a successful reconnect for any peer whose `RTCPeerConnection`
  survived the outage.
- Older servers ignore unknown message types — the hint is purely
  additive.

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

### 4.12 `participant_media_state` (client → server → clients)

Sent by a client to announce its current audio/video enabled state. The server stores the state per-participant and relays the message to other room participants as a peer message (see **Server behavior** below).

Clients should broadcast this message after joining, when a new peer joins, and whenever the local audio or video enabled state changes.

```json
{
  "v": 1,
  "type": "participant_media_state",
  "rid": "AbC123",
  "payload": {
    "audioEnabled": true,
    "videoEnabled": false
  }
}
```

**Fields in payload**
- `audioEnabled` *(boolean, optional)*: whether the sender's audio is enabled.
- `videoEnabled` *(boolean, optional)*: whether the sender's video is enabled.

**Server behavior**
- Stores the audio/video state in the room per-CID so late joiners receive the latest values via the participant list in `joined`/`room_state`.
- Relays the message to other room participants as a peer message (with a `from` field) instead of broadcasting `room_state`. This avoids participant reordering and full UI rebuilds on every toggle.

**Client behavior**
- On receiving a relayed `participant_media_state`, update the cached audio/video state for the sender. Only fields present in the payload should be updated; missing fields leave the previous value intact.
- The participant list in `joined`/`room_state` carries `audioEnabled`/`videoEnabled` for late joiners; relayed peer messages take priority over those values for already-known participants.
- Unknown message types are silently ignored by older clients, ensuring backward compatibility.

---

### 4.13 Room Status Monitoring (WebSocket/SSE)

Used to aggregate real-time occupancy for a list of rooms (e.g., recent calls list).
Currently consumed by the React web home screen and the native Android/iOS home screen recent-calls UX.

#### `watch_rooms` (client → server)
Subscribe to updates for a list of rooms.

Each `watch_rooms` message replaces the client's previous watched-room set.
Send `rids: []` to clear all room-watch subscriptions for that connection.

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
Immediate response to `watch_rooms` with current room occupancy and, when the room exists, its capacity.

```json
{
  "v": 1,
  "type": "room_statuses",
  "payload": {
    "AbC123": { "count": 1, "maxParticipants": 2 },
    "XyZ789": { "count": 0 }
  }
}
```

#### `room_status_update` (server → client)
Pushed whenever a watched room's participant count changes. `maxParticipants` is included whenever the room currently exists and reflects the room's current effective capacity.

```json
{
  "v": 1,
  "type": "room_status_update",
  "payload": {
    "rid": "AbC123",
    "count": 3,
    "maxParticipants": 4
  }
}
```

---

## 5. WebRTC negotiation rules (mesh)

### 5.1 Roles for offer/answer
To avoid "glare" (both sides sending offers), assign offer ownership per peer edge:

- Compare peer IDs lexicographically.
- The participant whose `cid` sorts first is the offerer for that pair.
- `joinedAt` does not participate in offer ownership.

**Rule:**
- For each remote participant, if your `cid` sorts before theirs, create and send `offer` to that participant.
- Otherwise wait for their `offer` and respond with `answer`.
- All `offer`, `answer`, and `ice` messages should be directed with `to`.

### 5.2 Local media
- Client may attempt to start local media before join for preview; browsers may require user gesture.
- Add tracks to `RTCPeerConnection` before creating offer/answer.

### 5.3 Trickle ICE
- Both sides send `ice` as candidates are discovered.
- Both sides add received candidates promptly.

### 5.4 Disconnect / remote leave
- If a participant leaves or a `room_ended` is received:
  - Close only the affected peer connection(s) and clear media for that participant
  - Keep remaining peer connections and local media running while waiting (stop when user leaves)

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
- Never allow more participants than the room's current `maxParticipants`.
- Group-requested rooms remain joinable as provisional 1:1 rooms until a second distinct participant joins and locks the final capacity.
- Reject clients that do not support a locked group room with `ROOM_CAPACITY_UNSUPPORTED`.

### 7.4 Cleanup
- On socket disconnect: **do not** treat as `leave`. The server must suspend
  the participant — detach the transport but keep the CID-keyed record in
  the room — so established WebRTC peer connections between clients are not
  torn down. Broadcast an updated `room_state` with
  `connectionStatus="suspended"` for that participant. If the client
  reconnects with a matching `reconnectCid` (see 4.1) within the
  implementation-defined hard-eviction window, the server reattaches the new
  transport to the existing record. If the window expires, the server
  removes the participant and broadcasts a final `room_state` so peers tear
  down. The recommended hard-eviction window is at least 10 minutes.
- If every participant in a room is suspended (no active signaling transports
  remain), the server may delete the room after a short grace period. Current
  servers use 10 seconds. This prevents ghost-only rooms from holding
  occupancy for the full hard-eviction window when nobody is alive to observe
  media liveness.
- On explicit `leave` or `end_room`: remove the participant immediately (no
  suspend window).
- If room becomes empty (including after hard eviction): delete room.

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
This is a diagnostics-only, rate-limited exception to the normal joined-session TURN token flow.

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
- **TURN Gating**: Call TURN tokens are issued only in the `joined` message after successful `rid` validation. `/api/diagnostic-token` remains available as a short-lived, rate-limited diagnostics exception.
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
→ for each remote peer where you are the deterministic offerer: create offer → **Negotiating**
→ if receive offer from a peer: set remote, create answer → **Negotiating**
→ when ICE connected: **InCall**
→ on remote leave: **Joined (Waiting)**
→ on `room_ended`: **Ended**
→ leave/home: **Disconnected**

---

## 11. Minimal conformance checklist

### Client
- [ ] Connect WS/SSE, send `join` on call page
- [ ] Show "Join Call" and only call `getUserMedia` after user gesture
- [ ] Implement deterministic per-peer offer ownership to avoid glare
- [ ] Trickle ICE send/receive with queueing before remote SDP is set
- [ ] Handle `room_state`, `room_ended`, and `error`
- [ ] Stop local tracks on explicit leave

### Backend
- [ ] Accept WS/SSE, parse JSON, validate schema
- [ ] Create room on first join
- [ ] Enforce per-room `maxParticipants` and legacy compatibility admission
- [ ] Assign hostCid and transfer host if host leaves
- [ ] Relay offer/answer/ice to correct peer
- [ ] Broadcast `room_state` updates
- [ ] Implement `end_room` and broadcast `room_ended`
