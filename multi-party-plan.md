# Multi-Party Calling Support

## Context

Serenada currently supports only 1:1 calls. The room model hard-caps at 2 participants (server/signaling.go:332,344), and all clients assume a single RTCPeerConnection with a single remote stream. This plan adds optional multi-party support using full-mesh WebRTC (each client maintains N-1 peer connections), capped at 4 participants. The 1:1 UX remains untouched; multi-party uses an adaptive tiling grid.

**Compatibility requirement**: Existing Android and iOS builds must keep working unchanged. Legacy mobile clients may create and join only 1:1 rooms. They must be rejected from multi-party rooms even if the room currently has fewer than 2 participants.

**Rollout**: Server + Web client first, but not as a global room-capacity change. Multi-party becomes an explicit room mode selected by new web clients. Legacy clients continue using 1:1 rooms only. Android and iOS native multi-party support follows in a separate phase.

**Negotiation strategy**: Existing participants offer to the newcomer. When a new participant joins, every already-present participant creates a PC and sends an offer. The newcomer answers each.

---

## Phase 1: Server Changes

Small, surgical changes, but now per-room rather than hub-global. No new dependencies.

### 1.1 Add server ceiling and room metadata

**Files:**
- `server/signaling.go`
- `server/main.go`

- Add `maxParticipantsLimit int` to `Hub` as a server-wide ceiling
- Add `maxParticipants int` to `Room` as the room's effective capacity
- Persist stable participant join ordering on the room instead of synthesizing `joinedAt` during payload assembly
- Change `newHub()` signature to `newHub(maxParticipantsLimit int) *Hub`
- Guard: `if maxParticipantsLimit < 2 { maxParticipantsLimit = 2 }`
- In `main.go`, parse `MAX_ROOM_PARTICIPANTS` and pass it to `newHub`
- Use `4` as the ceiling default, but note that legacy compatibility is enforced by room mode, not by this ceiling alone

### 1.2 Extend join payload for capability + room mode

**File: `server/signaling.go`**

Extend join payload parsing to accept:

```json
{
  "capabilities": {
    "trickleIce": true,
    "maxParticipants": 4
  },
  "createMaxParticipants": 4
}
```

- `capabilities.maxParticipants` means "largest room size this client can participate in"
- Old mobile clients omit it; treat omission as `2`
- `createMaxParticipants` is used only when creating a new room
- Old clients omit it; default to `2`
- Clamp `createMaxParticipants` to `[2, capabilities.maxParticipants, h.maxParticipantsLimit]`

### 1.3 Set room capacity on create, enforce compatibility on join

**File: `server/signaling.go`**

- When a room is first created, set `room.maxParticipants = createMaxParticipants`
- For joins to an existing room:
  - if `clientSupportedMax < room.maxParticipants`, reject with a new explicit error such as `ROOM_CAPACITY_UNSUPPORTED`
  - this is the legacy-mobile safeguard: old clients can join 1:1 rooms, but not group rooms
- Replace the current hardcoded capacity checks with `room.maxParticipants`

### 1.4 Expose room capacity and stable join ordering in payloads

**File: `server/signaling.go`**

- In `broadcastRoomState`, add `"maxParticipants": room.maxParticipants`
- In `handleJoin` joined payload, add `"maxParticipants": room.maxParticipants`
- Include stable `joinedAt` in both `joined` and `room_state`

Clients can use this to know whether the room is 1:1 or multi-party. Old clients ignore unknown fields, but they should already have been filtered out of unsupported rooms by the join-time compatibility check.

### 1.5 Update test call sites

**Files:**
- `server/internal_stats_test.go` — `newHub()` → `newHub(4)` at lines 13, 28, 42, 58
- `server/push_room_id_validation_test.go` — `newHub()` → `newHub(4)` at lines 11, 68, 80

### 1.6 Add server tests for compatibility rules

**New/updated tests in `server/`**

- Legacy/unspecified client can create and join only `maxParticipants=2` rooms
- New web client can create a `maxParticipants=4` room
- Legacy client is rejected from an existing `maxParticipants=4` room with `ROOM_CAPACITY_UNSUPPORTED`
- New web client can still join an existing `maxParticipants=2` room
- Capacity enforcement uses `room.maxParticipants`, not the old hardcoded `2`
- Targeted relay behavior remains correct with more than 2 participants

### 1.7 Update `.env.example`

**File: `.env.example`**

Add: `# MAX_ROOM_PARTICIPANTS=4`

### No changes needed (already multi-party ready)

- `handleRelay` (line 525-587): iterates all participants, respects `to` field, injects `from`
- `handleEndRoom`: broadcasts to all participants
- `removeClientFromRoom`: host transfer picks any remaining participant
- Ghost eviction: evicts one specific CID, then rechecks capacity

---

## Phase 2: Web Client — Signaling / Room Mode

This is the compatibility layer that keeps old mobile clients working.

### 2.1 Extend signaling types

**Files:**
- `client/src/contexts/signaling/types.ts`
- `client/src/contexts/SignalingContext.tsx`

- Add `maxParticipants?: number` to `RoomState`
- Add join helpers/types for:
  - `capabilities.maxParticipants`
  - `createMaxParticipants`

### 2.2 Advertise web capability, request room mode explicitly

**File: `client/src/contexts/SignalingContext.tsx`**

- New web clients always advertise `capabilities.maxParticipants = 4`
- Normal join flow requests `createMaxParticipants = 2`
- Explicit group-call flow requests `createMaxParticipants = 4`
- Existing rooms ignore `createMaxParticipants`; compatibility is checked by the server against `room.maxParticipants`

### 2.3 Handle the new server error cleanly

**File: `client/src/contexts/SignalingContext.tsx`**

- Add a user-facing path for `ROOM_CAPACITY_UNSUPPORTED`
- Error copy should explain that the room is a group call and this client version only supports 1:1

---

## Phase 3: Web Client — WebRTCContext Refactoring

The heaviest change. Refactor from single-PC to a `Map<cid, PeerState>`.

### 3.1 Define `PeerState`

**File: `client/src/contexts/WebRTCContext.tsx`**

```typescript
interface PeerState {
    pc: RTCPeerConnection;
    remoteStream: MediaStream | null;
    iceBuffer: RTCIceCandidateInit[];
    isMakingOffer: boolean;
    offerTimeout: number | null;
    iceRestartTimer: number | null;
    lastIceRestartAt: number;
    pendingIceRestart: boolean;
    nonHostFallbackTimer: number | null;
    nonHostFallbackAttempts: number;
}
```

### 3.2 Replace single-PC refs with peer map

**File: `client/src/contexts/WebRTCContext.tsx`**

Replace:
- `pcRef` → `peersRef = useRef<Map<string, PeerState>>(new Map())`
- `remoteStream` state → `remoteStreams = useState<Map<string, MediaStream>>(new Map())`
- `iceBufferRef` → moves into each `PeerState`
- `isMakingOfferRef`, `pendingIceRestartRef`, `lastIceRestartAtRef`, `iceRestartTimerRef`, `offerTimeoutRef`, `nonHostFallbackTimerRef`, `nonHostFallbackAttemptsRef` — all move into `PeerState`

### 3.3 Update context interface

**File: `client/src/contexts/WebRTCContext.tsx`**

```typescript
interface WebRTCContextValue {
    localStream: MediaStream | null;
    remoteStreams: Map<string, MediaStream>;
    startLocalMedia: () => Promise<MediaStream | null>;
    stopLocalMedia: () => void;
    startScreenShare: () => Promise<void>;
    stopScreenShare: () => Promise<void>;
    isScreenSharing: boolean;
    canScreenShare: boolean;
    flipCamera: () => Promise<void>;
    facingMode: 'user' | 'environment';
    hasMultipleCameras: boolean;
    peerConnections: Map<string, RTCPeerConnection>;
    iceConnectionState: RTCIceConnectionState;
    connectionState: RTCPeerConnectionState;
    signalingState: RTCSignalingState;
    connectionStatus: ConnectionStatus;
}
```

### 3.4 Refactor PC lifecycle functions

**File: `client/src/contexts/WebRTCContext.tsx`**

- `getOrCreatePC()` → `getOrCreatePeer(remoteCid: string): PeerState`
  - creates `RTCPeerConnection` with the same config
  - adds local tracks from `localStreamRef`
  - sets `pc.ontrack` to update that peer's remote stream in `remoteStreams`
  - sets `pc.onicecandidate` to send ICE with `to: remoteCid`
  - sets connection state handlers per-peer
- `cleanupPC()` → `cleanupPeer(remoteCid: string)` + `cleanupAllPeers()`
- `createOffer()` → `createOfferTo(remoteCid: string, options?)`

### 3.5 Refactor roomState effect

**File: `client/src/contexts/WebRTCContext.tsx`**

Replace the current host-based 2-participant logic with:

```
When roomState changes:
  myId = clientId
  remotePeers = roomState.participants.filter(p => p.cid !== myId)

  for each peer in remotePeers:
    if !peersRef has peer.cid:
      getOrCreatePeer(peer.cid)
      if I was here before this peer (my joinedAt < their joinedAt, or tie-break by CID):
        createOfferTo(peer.cid)
      else:
        scheduleNonHostFallbackTo(peer.cid)

  for each [cid] in peersRef:
    if cid not in remotePeers:
      cleanupPeer(cid)

  if remotePeers.length === 0:
    cleanupAllPeers()
```

Important: this depends on stable, server-backed `joinedAt` values in both `joined` and `room_state`.

### 3.6 Route incoming signaling by `from`

**File: `client/src/contexts/WebRTCContext.tsx`**

Refactor `processSignalingMessage` to route by `payload.from`:

```typescript
case 'offer':
    handleOfferFrom(payload.from, payload.sdp);
case 'answer':
    handleAnswerFrom(payload.from, payload.sdp);
case 'ice':
    handleIceFrom(payload.from, payload.candidate);
```

If a `PeerState` does not exist for `from`, create it defensively.

### 3.7 Add `to` to outgoing relay messages

**File: `client/src/contexts/WebRTCContext.tsx`**

- `sendMessage('offer', { sdp }, remoteCid)`
- `sendMessage('answer', { sdp }, remoteCid)`
- `sendMessage('ice', { candidate }, remoteCid)`

### 3.8 Update track replacement for multi-PC

**File: `client/src/contexts/WebRTCContext.tsx`**

Refactor `startScreenShare`, `stopScreenShare`, and `flipCamera` to iterate all peers and replace the active sender track on each PC.

### 3.9 Aggregate connection state, but keep recovery ownership per-peer

**File: `client/src/contexts/WebRTCContext.tsx`**

- Compute worst-of across all peers for `iceConnectionState`, `connectionState`, `signalingState`, and `connectionStatus`
- Move offer ownership, fallback offers, and ICE restart ownership to the per-peer level
- In multi-party, the initiator of peer `A <-> B` is "who joined earlier on that edge", not always the room host

### 3.10 Update local media addition

When `startLocalMedia` is called and peers already exist, add tracks to all existing peers. When a new peer is created and local media already exists, add tracks to that PC.

---

## Phase 4: Web Client — CallRoom.tsx UI Changes

### 4.1 Add an explicit room-mode entry point

**Files:**
- `client/src/pages/CallRoom.tsx`
- any room-create / share entry UI that chooses call type

- Default flow stays 1:1 to preserve compatibility with old mobile clients
- Add an explicit "Group call" path for new web clients that sets `createMaxParticipants=4` on room creation
- Standard room joins should continue requesting `createMaxParticipants=2` unless the user explicitly chose group mode

### 4.2 Conditional layout: 1:1 vs grid

**File: `client/src/pages/CallRoom.tsx`**

```
if remoteStreams.size <= 1:
  render existing 1:1 layout (large remote + PIP local)
else:
  render VideoGrid
```

For 1:1, pull the single stream from the `Map` instead of the old scalar state. Same DOM, same CSS.

### 4.3 New component: `VideoGrid`

**File: `client/src/components/VideoGrid.tsx`** (new file)

```typescript
interface VideoGridProps {
    localStream: MediaStream | null;
    remoteStreams: Map<string, MediaStream>;
    facingMode: 'user' | 'environment';
}
```

Layout rules (total tiles = remotes + 1 local):
- **3 tiles**: 1 top spanning full width, 2 bottom
- **4 tiles**: 2x2 grid

Each tile is a `<video autoPlay playsInline>` element. Local tile is mirrored when `facingMode === 'user'`.

### 4.4 CSS grid styles

**File: `client/src/index.css`**

Add `.video-grid`, `.video-grid-3`, `.video-grid-4`, and `.video-grid-cell` styles. Reuse existing 1:1 styles unchanged.

### 4.5 Update waiting/status messages

**File: `client/src/pages/CallRoom.tsx`**

- Show "Waiting..." when `remoteStreams.size === 0`
- Remove single-peer `otherParticipant` logic and replace it with participant count / room mode checks
- Preserve the existing 1:1 waiting-room/share UX for 1:1 rooms
- For group rooms, update copy to refer to participants rather than a single "other participant"

### 4.6 Update diagnostics

**File: `client/src/pages/callDiagnostics.ts`**

`useRealtimeCallStats` currently takes a single `peerConnection`. Update it to accept `Map<string, RTCPeerConnection>` and either aggregate stats or show per-peer detail in the debug panel.

---

## Phase 5 (Future): Mobile Clients

Deferred to a separate implementation phase. Until then, legacy mobile clients remain 1:1-only by design.

### Android

- Extract `PeerConnectionSlot` from `WebRtcEngine.kt` (single PC + remote track + ICE state)
- `WebRtcEngine` narrows to factory + local media only
- `CallManager` holds `Map<String, PeerConnectionSlot>`
- `CallScreen` switches between the current 1:1 layout and a `ParticipantGrid` composable

### iOS

- Extract `PeerConnectionSlot` from `WebRtcEngine.swift`
- Same narrowing of `WebRtcEngine`
- `CallManager` holds `[String: PeerConnectionSlot]`
- `CallScreen` switches between the current layout and a grid `VStack` / `HStack` layout

Both platforms: local audio/video tracks remain singletons shared across all PCs. Camera mode switching works transparently since all PCs share the same track bound to a single `VideoSource`.

---

## Verification

### Server

1. `cd server && go test ./...`
2. Manual: create a normal 1:1 room from web, confirm legacy/mobile-compatible clients can join it
3. Manual: create a 4-party room from new web UI, confirm old mobile client is rejected with the explicit unsupported-capacity error
4. Manual: join 4 browser tabs to the same group room, confirm all receive `joined` and `room_state` with correct participant lists. Confirm 5th gets `ROOM_FULL`.

### Web Client

1. `cd client && npm run lint && npm run test`
2. **1:1 regression**: Two browser tabs in a normal room — verify identical UX to before (PIP layout, screen share, camera flip, reconnection)
3. **Legacy compatibility**: New web client creates a normal room, old mobile client joins successfully
4. **Group-room incompatibility path**: New web client creates a group room, old mobile client is rejected cleanly
5. **3-party**: Three web tabs in a group room — verify grid layout with 3 tiles, all video/audio flowing
6. **4-party**: Four web tabs in a group room — verify 2x2 grid, all streams
7. **Join/leave mid-call**: Start 3-party, one leaves — verify grid transitions back to 1:1 layout, remaining PC is unaffected
8. **Screen share in multi-party**: Verify track replacement propagates to all peers
9. **ICE restart**: Kill network on one tab, verify only that peer's connection recovers

### Cross-platform smoke test (after mobile phase)

```bash
bash tools/smoke-test/smoke-test.sh
```

### Documentation

Update the protocol and user-facing docs as part of the implementation:

- `serenada_protocol_v1.md`
  - document `capabilities.maxParticipants`
  - document `createMaxParticipants`
  - document per-room `maxParticipants`
  - document the legacy-client rejection path for group rooms
- `README.md`
  - clarify that 1:1 remains the default mode
  - clarify that group calls are web-first until native clients add support
