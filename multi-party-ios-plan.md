# Multi-Party Calling — iOS Implementation Plan

## Context

The server and web client already support multi-party calls (up to 4 participants) using full-mesh WebRTC. The iOS client currently assumes a single `RTCPeerConnection` and a single remote video track throughout. This plan extends the iOS client to support group calls while keeping the existing 1:1 UX unchanged for two-participant rooms.

**Architecture**: Full-mesh — one `RTCPeerConnection` per remote participant. Local audio/video tracks are singletons shared across all PCs via `addTrack`.

**Negotiation**: Existing participants offer to newcomers. Determined by comparing `joinedAt` timestamps from the server's `room_state` payload, with CID as tiebreaker.

**Server contract**: The server already injects `payload.from` into all relayed offer/answer/ice messages and respects the `to` field for targeted delivery. The `joined` and `room_state` payloads include `maxParticipants` and per-participant `joinedAt`.

**Room-capacity behavior**: Native multi-party builds should match the web behavior exactly. New-capable clients advertise `capabilities.maxParticipants = 4` and request `createMaxParticipants = 4` by default, so rooms they create are group-capable. But room capacity is fixed when the room is first created: if the first joiner is an older 1:1-only client that omits these fields, the room stays capped at `2` for its lifetime. Later joiners can neither upgrade nor downgrade an existing room.

---

## Phase 1: Extract `PeerConnectionSlot` from `WebRtcEngine`

The core refactoring. Separate per-connection state from the singleton media engine.

### 1.1 New class: `PeerConnectionSlot.swift`

**File**: `client-ios/Sources/Core/Call/PeerConnectionSlot.swift`

Encapsulates everything currently tied to a single PC in `WebRtcEngine`:

```swift
@MainActor
final class PeerConnectionSlot {
    let remoteCid: String

    // Callbacks
    var onLocalIceCandidate: ((String, IceCandidatePayload) -> Void)?
    var onRemoteVideoTrack: ((String, Bool) -> Void)?
    var onConnectionStateChange: ((String, RTCPeerConnectionState) -> Void)?
    var onIceConnectionStateChange: ((String, RTCIceConnectionState) -> Void)?
    var onSignalingStateChange: ((String, RTCSignalingState) -> Void)?

    // Internal state
    private var peerConnection: RTCPeerConnection?
    private var remoteVideoTrack: RTCVideoTrack?
    private var remoteVideoTrackDelivered = false
    private var remoteRenderers: [WeakAnyBox] = []
    private var pendingRemoteIceCandidates: [IceCandidatePayload] = []
    private let factory: RTCPeerConnectionFactory
    private let iceServers: [RTCIceServer]
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoTrack: RTCVideoTrack?
    private var observerProxy: PeerConnectionObserverProxy?
}
```

**State moved from `WebRtcEngine`** (currently lines 191, 203-204, 180, 206-207):
- `peerConnection: RTCPeerConnection?`
- `remoteVideoTrack: RTCVideoTrack?`
- `remoteVideoTrackDelivered: Bool` — the guard at line 1102
- `remoteRenderers: [WeakAnyBox]`
- `pendingRemoteIceCandidates: [IceCandidatePayload]`
- `observerProxy: PeerConnectionObserverProxy?`

**Methods moved from `WebRtcEngine`** (currently lines 306-493, 982-1030, 664-694, 1058-1132):
- `createPeerConnection()` — from current `createPeerConnectionIfReady()`, uses passed-in factory + iceServers + local tracks
- `closePeerConnection()` — from line 306-316
- `createOffer(iceRestart:)` — from lines 373-417
- `createAnswer()` — from lines 419-445
- `setRemoteDescription(type:sdp:completion:)` — from lines 447-473
- `addIceCandidate(_:)` — from lines 475-493
- `rollbackLocalDescription(completion:)` — new, extracted from the rollback pattern
- `attachRemoteRenderer(_:)` / `detachRemoteRenderer(_:)` — from lines 1007-1030
- `attachRemoteTrackToRegisteredRenderers()` / `detachRemoteTrackFromRegisteredRenderers()` — from lines 1328-1374
- `flushPendingIceCandidates()` — from lines 1301-1315
- `collectRealtimeCallStats() async -> RealtimeCallStats?` — async wrapper around the existing stats callback from lines 664-694
- `isReady() -> Bool`, `hasRemoteDescription() -> Bool`, `isRemoteVideoTrackEnabled() -> Bool`, `getConnectionState() -> RTCPeerConnectionState`
- `applyVideoSenderParameters()` — operates on this slot's PC
- `attachLocalTracks(audioTrack:videoTrack:)` — new helper; when local media starts after the slot already exists, add the current tracks to this PC and cache them for later use

**PeerConnectionObserverProxy** (currently lines 1579-1647):
- Move into `PeerConnectionSlot` or keep as a shared class
- Route callbacks through the slot's closures with `remoteCid` prepended
- `peerConnection(_:didAdd stream:)` at line 1607: extract first video track, guard with `remoteVideoTrackDelivered`, notify via `onRemoteVideoTrack(remoteCid, true)`
- `peerConnection(_:didAdd rtpReceiver:)` at line 1642: same pattern
- `peerConnection(_:didGenerate:)` at line 1629: forward via `onLocalIceCandidate(remoteCid, candidate)`
- `peerConnection(_:didChange newState:)`: forward connection/ICE/signaling state changes

### 1.2 Narrow `WebRtcEngine`

**File**: `client-ios/Sources/Core/Call/WebRtcEngine.swift`

**Remove** (moved to `PeerConnectionSlot`):
- `peerConnection` (line 191)
- `remoteVideoTrack`, `remoteVideoTrackDelivered` (lines 203-204)
- `remoteRenderers` (line 207)
- `pendingRemoteIceCandidates` (line 180)
- `observerProxy` (line 209)
- `createOffer()`, `createAnswer()`, `setRemoteDescription()`, `addIceCandidate()` (lines 373-493)
- `closePeerConnection()` (lines 306-316)
- `attachRemoteRenderer()`, `detachRemoteRenderer()` (lines 1007-1030)
- `attachRemoteTrackToRegisteredRenderers()`, `detachRemoteTrackFromRegisteredRenderers()` (lines 1328-1374)
- `flushPendingIceCandidates()` (lines 1301-1315)
- `collectRealtimeCallStats() async` (lines 664-694)
- `createPeerConnectionIfReady()` (lines 1058-1132)
- `PeerConnectionObserverProxy` (lines 1579-1647)

**Keep**:
- `RTCPeerConnectionFactory` creation and lifecycle
- `localAudioTrack`, `localVideoTrack`, `localVideoSource`, `localAudioSource`
- `localRenderers` management
- All camera management: `startLocalMedia()`, `stopLocalMedia()`, `toggleVideo()`, `restartVideoCapturer()`, `switchVideoCapturer()`, composite capturer, torch, zoom, screen share
- `attachLocalRenderer()`, `detachLocalRenderer()` (lines 982-1005)
- `attachTrackToRegisteredRenderers()` (lines 1317-1326) — for local renderers only
- `rendererAttachmentQueue` (line 183)

**Add**:
- `func getFactory() -> RTCPeerConnectionFactory`
- `func getIceServers() -> [RTCIceServer]`
- `func getLocalAudioTrack() -> RTCAudioTrack?`
- `func getLocalVideoTrack() -> RTCVideoTrack?`
- `func createSlot(remoteCid: String, ...) -> PeerConnectionSlot` — factory method

### 1.3 Backward-compat checkpoint

After this phase, wire `CallManager` to create exactly one slot for the single remote peer. Verify 1:1 calls work identically before proceeding.

---

## Phase 2: Multi-slot `CallManager`

### 2.1 Replace single-PC logic with slot map

**File**: `client-ios/Sources/Core/Call/CallManager.swift`

**Replace** (currently lines 81-120):
- Single `webRtcEngine` → keep for local media
- Single `sentOffer`, `isMakingOffer`, `pendingIceRestart`, `lastIceRestartAt` → move into `PeerConnectionSlot`
- Single `offerTimeoutTask`, `iceRestartTask`, `nonHostOfferFallbackTask` → per-slot tasks

**Add**:
```swift
private var peerSlots: [String: PeerConnectionSlot] = [:]
```

Per-slot state (add to `PeerConnectionSlot`):
```swift
var sentOffer = false
var isMakingOffer = false
var pendingIceRestart = false
var lastIceRestartAt: ContinuousClock.Instant? = nil  // nil = never restarted; cooldown check must treat nil as "not in cooldown"
var offerTimeoutTask: Task<Void, Never>? = nil
var iceRestartTask: Task<Void, Never>? = nil
var nonHostFallbackTask: Task<Void, Never>? = nil
var nonHostFallbackAttempts = 0
```

### 2.2 Advertise capabilities in join payload

**File**: `client-ios/Sources/Core/Call/CallManager.swift`

In the join message construction (in `sendJoin()` or equivalent), add to the payload:
```swift
payload["capabilities"] = ["maxParticipants": 4]
payload["createMaxParticipants"] = 4
```

- This mirrors the web rollout: new iOS builds create group-capable rooms by default
- The server uses `createMaxParticipants` only when creating a new room
- If an older client joined first and created a `maxParticipants=2` room, this iOS build should join it as a normal 1:1 call rather than attempting to upgrade it
- No separate "start 1:1 vs start group" UI is needed; the room remains visually 1:1 until participant #3 joins

### 2.2a Parse and retain room capacity metadata

**Files**:
- `client-ios/Sources/Core/Models/RoomState.swift`
- `client-ios/Sources/Core/Call/CallManager.swift`

- Extend `RoomState` to include `maxParticipants: Int?`
- Parse `maxParticipants` from both `joined` and `room_state`
- Keep this metadata even if layout switching is still driven by `remoteParticipants.count`; it preserves the server-selected room mode and makes diagnostics / future UX decisions explicit

### 2.3 Refactor `updateParticipants`

**File**: `client-ios/Sources/Core/Call/CallManager.swift` (currently lines 925-974)

Replace the current binary host/non-host logic:

```
When roomState changes:
  myCid = clientId
  remotePeers = roomState.participants.filter { $0.cid != myCid }
  remoteCids = Set(remotePeers.map { $0.cid })

  // Remove slots for departed participants
  for cid in peerSlots.keys where !remoteCids.contains(cid) {
      peerSlots.removeValue(forKey: cid)?.closePeerConnection()
  }

  // Create slots for new participants
  for participant in remotePeers where peerSlots[participant.cid] == nil {
      let slot = webRtcEngine.createSlot(remoteCid: participant.cid, ...)
      peerSlots[participant.cid] = slot
      if shouldIOffer(myCid: myCid, remoteCid: participant.cid, roomState: roomState) {
          maybeSendOffer(to: slot)
      } else {
          scheduleNonHostFallback(for: slot)
      }
  }

  // Update phase and UI
  let phase: CallPhase = remotePeers.isEmpty ? .waiting : .inCall
  updateUiState(...)
```

### 2.4 Offer direction: `shouldIOffer`

```swift
private func shouldIOffer(myCid: String, remoteCid: String, roomState: RoomState) -> Bool {
    let myJoinedAt = roomState.participants.first { $0.cid == myCid }?.joinedAt ?? 0
    let theirJoinedAt = roomState.participants.first { $0.cid == remoteCid }?.joinedAt ?? 0
    return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
}
```

### 2.5 Route incoming signaling by `payload.from`

**File**: `client-ios/Sources/Core/Call/CallManager.swift`

In the signaling message handler for offer/answer/ice, extract `from`:

```swift
let fromCid = payload?["from"]?.stringValue
```

Route to the correct slot:
- **offer**: `let slot = getOrCreateSlot(fromCid)` then `slot.setRemoteDescription(.offer, sdp) { slot.createAnswer { self.sendMessage("answer", payload, to: fromCid) } }`
- **answer**: `peerSlots[fromCid]?.setRemoteDescription(.answer, sdp)`; cancel offer timeout for that slot
- **ice**: `getOrCreateSlot(fromCid).addIceCandidate(candidate)`

### 2.6 Add `to` field to outgoing offer/answer/ice

All `sendMessage("offer", ...)`, `sendMessage("answer", ...)`, `sendMessage("ice", ...)` must include `to: slot.remoteCid`.

### 2.7 Per-slot ICE restart and non-host fallback

Move `scheduleIceRestart`, `triggerIceRestart`, `maybeSendOffer`, `scheduleNonHostFallback` to operate on a specific `PeerConnectionSlot`. The patterns are the same as 1:1, just scoped to one slot.

### 2.8 Aggregate connection status

Compute worst-of across all slots for `iceConnectionState`, `connectionState`, `signalingState`. The `connectionStatus` state machine (Connected/Recovering/Retrying) uses the aggregate.

### 2.9 Track replacement across all slots

Camera mode switching (selfie/world/composite) replaces the capturer on the shared `RTCVideoSource`. Since all slots share the same `localVideoTrack` bound to that source, the switch propagates automatically. Verify this; if not, iterate slots and call `replaceTrack` on each sender.

### 2.10 Handle `ROOM_CAPACITY_UNSUPPORTED` error

In the error handler, surface the new error code with a user-facing message explaining that this room requires a newer app version.

### 2.11 Per-slot remote video state polling

**File**: `client-ios/Sources/Core/Call/CallManager.swift`

The current 1:1 model polls a single `webRtcEngine.isRemoteVideoTrackEnabled()` flag (around line 1404) and writes the result into `CallUiState.remoteVideoEnabled`. This must be replaced with per-slot polling:

- Add `isRemoteVideoTrackEnabled() -> Bool` to `PeerConnectionSlot` (moved from `WebRtcEngine`).
- In the existing stats/video-state polling task, iterate all slots and build a `[RemoteParticipant]`:
  ```swift
  let remoteParticipants = peerSlots.map { (cid, slot) in
      RemoteParticipant(
          cid: cid,
          videoEnabled: slot.isRemoteVideoTrackEnabled(),
          connectionState: String(describing: slot.getConnectionState())
      )
  }
  ```
- Write the result into `CallUiState.remoteParticipants`.
- Derive the scalar `remoteVideoEnabled` from the first entry for 1:1 backward compat: `remoteVideoEnabled = remoteParticipants.first?.videoEnabled ?? false`.
- The per-participant `videoEnabled` flag drives placeholder/avatar display per tile in the multi-party stage.

### 2.12 Update local media addition

Mirror the web plan's local-media rule:

- When `startLocalMedia()` is called and `peerSlots` already exist, iterate all slots and call `attachLocalTracks(audioTrack: currentAudioTrack, videoTrack: currentVideoTrack)`
- When a new slot is created and local media already exists, call `attachLocalTracks(...)` immediately after slot creation
- Do not rely only on constructor-time track injection; slots created before camera/mic startup must still begin sending once local media becomes available
- If local media is fully stopped and recreated later, refresh each slot's cached local track references before the next renegotiation / sender-parameter update

---

## Phase 3: UI — Multi-party layout

### 3.1 New model: `RemoteParticipant`

**File**: `client-ios/Sources/Core/Models/RemoteParticipant.swift`

```swift
struct RemoteParticipant: Identifiable, Equatable {
    let cid: String
    var videoEnabled: Bool
    var connectionState: String

    var id: String { cid }
}
```

### 3.2 Extend `CallUiState`

**File**: `client-ios/Sources/Core/Models/CallUiState.swift`

Add:
```swift
var remoteParticipants: [RemoteParticipant] = []
```

Keep `remoteVideoEnabled` derived from first remote participant for 1:1 backward compat.

### 3.3 Extend `WebRTCVideoView`

**File**: `client-ios/Sources/UI/Components/WebRTCVideoView.swift`

Extend the `Kind` enum:
```swift
enum Kind {
    case local
    case remote              // backward compat for 1:1
    case remoteForCid(String) // multi-party: specific participant
}
```

Update all code paths that branch on `Kind`, not just the happy-path renderer creation:

- In `makeUIView`, the `.remoteForCid(cid)` case creates an `RTCMTLVideoView` and calls `callManager.attachRemoteRenderer(renderer, forCid: cid)`
- In `updateUIView`, switch on `kind` rather than using `kind == .local`, so `.remote` and `.remoteForCid(_)` both receive remote-specific behavior
- In `dismantleUIView`, call `callManager.detachRemoteRenderer(renderer, forCid: cid)` for `.remoteForCid(cid)`
- In any `Coordinator` / helper logic, treat `.remoteForCid(_)` as a remote view, not as an unknown fallback
- Update the non-WebRTC stub / placeholder branch too: replace simple equality checks like `kind == .local` with a `switch` so stub builds still label `.remoteForCid(_)` as remote instead of breaking on the associated-value enum case

### 3.4 Renderer routing in CallManager

Add to `CallManager`:
```swift
func attachRemoteRenderer(_ renderer: RTCVideoRenderer, forCid cid: String) {
    peerSlots[cid]?.attachRemoteRenderer(renderer)
}

func detachRemoteRenderer(_ renderer: RTCVideoRenderer, forCid cid: String) {
    peerSlots[cid]?.detachRemoteRenderer(renderer)
}
```

### 3.5 Conditional layout in `CallScreen`

**File**: `client-ios/Sources/UI/Screens/CallScreen.swift`

```swift
var body: some View {
    if uiState.remoteParticipants.count <= 1 {
        // Existing 1:1 layout — unchanged
        existingCallLayout
    } else {
        // Multi-party stage layout
        multiPartyLayout
    }
}
```

### 3.6 Multi-party stage view

```swift
private var multiPartyLayout: some View {
    ZStack {
        // Remote-only participant stage; local video stays in PIP
        GeometryReader { geo in
            let tiles = uiState.remoteParticipants
            let layout = computeStageLayout(tiles: tiles, width: geo.size.width, height: geo.size.height)
            VStack(spacing: 12) {
                ForEach(layout.rows.indices, id: \.self) { rowIndex in
                    HStack(spacing: 12) {
                        ForEach(layout.rows[rowIndex], id: \.cid) { tile in
                            WebRTCVideoView(
                                kind: .remoteForCid(tile.cid),
                                callManager: callManager,
                                videoContentMode: .scaleAspectFit  // contain, not crop — matches web stage behavior
                            )
                            .frame(width: tile.width, height: tile.height)
                            // Placeholder/avatar remains per-participant when videoEnabled is false
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }

        // Local PIP (lower-right)
        VStack {
            Spacer()
            HStack {
                Spacer()
                WebRTCVideoView(kind: .local, callManager: callManager, ...)
                    .frame(width: 120, height: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.trailing, 16)
                    .padding(.bottom, 100)
            }
        }
    }
}
```

### 3.7 Layout computation

Port the same adaptive stage layout rules from the web client rather than inventing a native-specific grid:

- Render only remote participants inside the computed stage; keep the local stream in a separate lower-right PIP
- Clamp each remote tile's effective aspect ratio to `9:16 ... 16:9`, based on the actual incoming stream dimensions when available
- Enumerate the small set of candidate row arrangements and choose the one that maximizes usable on-screen tile dimensions for the viewer within the current viewport
- When candidate arrangements are close, prefer fewer rows instead of adding extra stacking for marginal gains
- Keep every remote tile fully contained within the stage viewport; use contain / aspect-fit rendering rather than cropping
- Use the full available stage width. Do not reserve an entire right-side safe column for the PIP; minor lower-right overlap is acceptable
- Use slightly reduced tile / PIP corner radii to match the current web stage direction rather than the older rounded grid look

---

## Phase 4: Stats and diagnostics

### 4.1 Aggregate stats across slots

In the stats polling (CallManager collects stats periodically), iterate all slots:

```swift
let allStats = await withTaskGroup(of: RealtimeCallStats?.self) { group in
    for (_, slot) in peerSlots {
        group.addTask { await slot.collectRealtimeCallStats() }
    }
    var results: [RealtimeCallStats] = []
    for await stat in group { if let stat { results.append(stat) } }
    return results
}
// Merge: sum bitrates, worst-of RTT, worst-of packet loss, etc.
```

### 4.2 Debug panel

The existing debug panel in `CallScreen` reads from `uiState.realtimeStats`. For multi-party, aggregate stats work. Optionally add a per-peer breakdown section.

---

## Phase 5: XcodeGen and project structure

### 5.1 Add new files to `project.yml`

No changes needed — the `Sources` directory is included recursively. New `.swift` files in `Sources/Core/Call/` and `Sources/Core/Models/` are picked up automatically by XcodeGen.

### 5.2 Regenerate project

```bash
cd client-ios && xcodegen generate
```

---

## Verification

1. Build: `cd client-ios && xcodegen generate && xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
2. **1:1 regression**: Two devices — verify identical UX (PIP, camera flip selfie→world→composite, screen share, reconnection)
3. **3-party**: Three devices — verify stage layout with remote tiles, local PIP, all streams flowing
4. **4-party**: Four devices — verify layout adapts, tiles stay fully visible, and the stage uses full available width rather than reserving a dedicated PIP column
5. **Join/leave mid-call**: 3-party → one leaves → verify transition back to 1:1, remaining connections unaffected
6. **Camera mode in multi-party**: Verify selfie→world→composite works, track propagates to all peers
7. **ICE restart**: Kill network on one device, verify only that peer's connection recovers
8. **Legacy compat**: Older build joining a web-created or iOS-created group room is rejected with `ROOM_CAPACITY_UNSUPPORTED`, while a room created first by an older 1:1-only client remains capped at 2 and can still be joined by the new iOS build
9. Run existing tests: `xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,name=iPhone 16' test`
10. Cross-platform smoke test: `bash tools/smoke-test/smoke-test.sh`

---

## Key files to modify

| File | Change |
|------|--------|
| `Sources/Core/Call/PeerConnectionSlot.swift` | **New** — per-peer PC, remote track, ICE, SDP, renderer management, observer proxy |
| `Sources/Core/Call/WebRtcEngine.swift` | Remove single-PC state; keep factory + local media; add `createSlot()` |
| `Sources/Core/Call/CallManager.swift` | Slot map, per-peer routing, `shouldIOffer`, capabilities in join, `maxParticipants` parsing, aggregate status, local-track attachment across slots |
| `Sources/Core/Models/CallUiState.swift` | Add `remoteParticipants: [RemoteParticipant]` |
| `Sources/Core/Models/RoomState.swift` | Add `maxParticipants` parsing/storage |
| `Sources/Core/Models/RemoteParticipant.swift` | **New** — per-participant UI model |
| `Sources/Core/Signaling/SignalingMessage.swift` | No changes needed (`to` and payload fields already exist) |
| `Sources/UI/Components/WebRTCVideoView.swift` | Add `.remoteForCid(String)` kind, per-CID renderer attachment, and update stub / fallback handling for the associated-value enum |
| `Sources/UI/Screens/CallScreen.swift` | Conditional multi-party layout, stage view, per-participant tiles |
