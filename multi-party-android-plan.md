# Multi-Party Calling — Android Implementation Plan

## Context

The server and web client already support multi-party calls (up to 4 participants) using full-mesh WebRTC. The Android client currently assumes a single `PeerConnection` and a single remote video track throughout. This plan extends the Android client to support group calls while keeping the existing 1:1 UX unchanged for two-participant rooms.

**Architecture**: Full-mesh — one `PeerConnection` per remote participant. Local audio/video tracks are singletons shared across all PCs via `addTrack`.

**Negotiation**: Existing participants offer to newcomers. Determined by comparing `joinedAt` timestamps from the server's `room_state` payload, with CID as tiebreaker.

**Server contract**: The server already injects `payload.from` into all relayed offer/answer/ice messages and respects the `to` field for targeted delivery. The `joined` and `room_state` payloads include `maxParticipants` and per-participant `joinedAt`.

**Room-capacity behavior**: Native multi-party builds should match the web behavior exactly. New-capable clients advertise `capabilities.maxParticipants = 4` and request `createMaxParticipants = 4` by default, so rooms they create are group-capable. But room capacity is fixed when the room is first created: if the first joiner is an older 1:1-only client that omits these fields, the room stays capped at `2` for its lifetime. Later joiners can neither upgrade nor downgrade an existing room.

---

## Phase 1: Extract `PeerConnectionSlot` from `WebRtcEngine`

The core refactoring. Separate per-connection state from the singleton media engine.

### 1.1 New class: `PeerConnectionSlot.kt`

**File**: `client-android/app/src/main/java/app/serenada/android/call/PeerConnectionSlot.kt`

Encapsulates everything currently tied to a single PC in `WebRtcEngine`:

```kotlin
class PeerConnectionSlot(
    val remoteCid: String,
    private val factory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    private var localAudioTrack: AudioTrack?,
    private var localVideoTrack: VideoTrack?,
    private val onLocalIceCandidate: (String, IceCandidate) -> Unit,
    private val onRemoteVideoTrack: (String, VideoTrack?) -> Unit,
    private val onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
    private val onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
)
```

**State moved from `WebRtcEngine`** (currently lines 168, 193-194, 199-200):
- `peerConnection: PeerConnection?`
- `remoteVideoTrack: VideoTrack?`
- `remoteSink: VideoSink?`
- `remoteVideoStateSink` (frame state monitor)
- `remoteDescriptionSet: Boolean`
- `pendingIceCandidates: MutableList<IceCandidate>`

**Methods moved from `WebRtcEngine`** (currently lines 466-567, 689-755, 364-380):
- `createPeerConnection()` — same as current `createPeerConnectionIfReady()` minus factory init, uses passed-in factory + iceServers + local tracks
- `closePeerConnection()`
- `createOffer(iceRestart: Boolean, onSdp, onComplete)`
- `createAnswer(onSdp, onComplete)`
- `setRemoteDescription(type, sdp, onComplete)`
- `addIceCandidate(candidate)`
- `rollbackLocalDescription(onComplete)`
- `attachRemoteRenderer(renderer)` / `detachRemoteRenderer(renderer)` / `attachRemoteSink(sink)` / `detachRemoteSink(sink)`
- `collectWebRtcStats(onComplete)`
- `isReady()`, `getSignalingState()`, `hasRemoteDescription()`, `isRemoteVideoTrackEnabled()`
- `applyVideoSenderParameters()` — operates on this slot's PC
- `attachLocalTracks(audioTrack, videoTrack)` — new helper; when local media starts after the slot already exists, add the current tracks to this PC and cache them for later use

**PeerConnection.Observer** (currently lines 779-839 of WebRtcEngine):
- Move into `PeerConnectionSlot`, routing callbacks through the constructor-injected lambdas with `remoteCid` as first argument
- `onTrack`: extract video track, replace previous `remoteVideoTrack`, notify via `onRemoteVideoTrack(remoteCid, track)`
- `onIceCandidate`: forward via `onLocalIceCandidate(remoteCid, candidate)`
- `onConnectionStateChange`: forward via `onConnectionStateChange(remoteCid, newState)`
- `onIceConnectionChange`: forward via `onIceConnectionStateChange(remoteCid, newState)`
- `onSignalingChange`: forward via `onSignalingStateChange(remoteCid, newState)`

### 1.2 Narrow `WebRtcEngine`

**File**: `client-android/app/src/main/java/app/serenada/android/call/WebRtcEngine.kt`

**Remove** (moved to `PeerConnectionSlot`):
- Single `peerConnection` field (line 168)
- `remoteSink`, `remoteVideoTrack`, `remoteVideoStateSink` (lines 193-196)
- `remoteDescriptionSet`, `pendingIceCandidates` (lines 199-200)
- `createOffer()`, `createAnswer()`, `setRemoteDescription()`, `addIceCandidate()` (lines 466-567)
- `closePeerConnection()` (lines 364-380)
- `attachRemoteRenderer()`, `detachRemoteRenderer()`, `attachRemoteSink()`, `detachRemoteSink()` (lines 709-755)
- `collectWebRtcStats(onComplete)` (lines 689-707)
- PC observer callbacks (lines 779-839)

**Keep**:
- `PeerConnectionFactory`, `EglBase`, `AudioDeviceModule` — singleton, shared across all slots
- `localVideoTrack`, `localAudioTrack`, `videoSource`, `audioSource` — local media is singleton
- `localSinks` management — for local preview renderers
- All camera management: `startLocalMedia()`, `stopLocalMedia()`, `flipCamera()`, `restartVideoCapturer()`, composite capturer, torch, zoom, screen share
- `initRenderer(renderer)` — EGL context init for any renderer

**Add**:
- `fun getFactory(): PeerConnectionFactory`
- `fun getIceServers(): List<PeerConnection.IceServer>?`
- `fun getLocalAudioTrack(): AudioTrack?`
- `fun getLocalVideoTrack(): VideoTrack?`
- `fun createSlot(remoteCid: String, callbacks): PeerConnectionSlot` — factory method that wires up the slot with the engine's factory, ICE servers, and local tracks

### 1.3 Backward-compat checkpoint

After this phase, wire `CallManager` to create exactly one slot for the single remote peer. Verify 1:1 calls work identically before proceeding.

---

## Phase 2: Multi-slot `CallManager`

### 2.1 Replace single-PC logic with slot map

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

**Replace** (currently lines 139-142, 175):
- Single `webRtcEngine` instance → keep for local media
- Single `sentOffer`, `isMakingOffer`, `pendingIceRestart`, `lastIceRestartAt` → move into `PeerConnectionSlot`
- Single `offerTimeoutRunnable`, `iceRestartRunnable`, `nonHostOfferFallbackRunnable` → per-slot timers

**Add**:
```kotlin
private val peerSlots = mutableMapOf<String, PeerConnectionSlot>()
```

Per-slot timer/state (embed in `PeerConnectionSlot` or in a wrapper):
```kotlin
// Per-slot offer/ICE state (add to PeerConnectionSlot)
var sentOffer: Boolean = false
var isMakingOffer: Boolean = false
var pendingIceRestart: Boolean = false
var lastIceRestartAt: Long = 0  // 0 = never restarted; cooldown check treats 0 as "not in cooldown" since elapsedRealtime() - 0 always exceeds cooldown
var offerTimeoutTask: Runnable? = null
var iceRestartTask: Runnable? = null
var nonHostFallbackTask: Runnable? = null
var nonHostFallbackAttempts: Int = 0
```

### 2.2 Advertise capabilities in join payload

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

In `sendJoin()` (currently lines 1024-1042), add to the payload:
```kotlin
payload.put("capabilities", JSONObject().apply {
    put("maxParticipants", 4)
})
payload.put("createMaxParticipants", 4)
```

- This mirrors the web rollout: new Android builds create group-capable rooms by default
- The server uses `createMaxParticipants` only when creating a new room
- If an older client joined first and created a `maxParticipants=2` room, this Android build should join it as a normal 1:1 call rather than attempting to upgrade it
- No separate "start 1:1 vs start group" UI is needed; the room remains visually 1:1 until participant #3 joins

### 2.2a Parse and retain room capacity metadata

**Files**:
- `client-android/app/src/main/java/app/serenada/android/call/RoomState.kt`
- `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

- Extend `RoomState` to include `maxParticipants: Int?`
- Parse `maxParticipants` from both `joined` and `room_state`
- Keep this metadata even if layout switching is still driven by `remoteParticipants.size`; it preserves the server-selected room mode and makes diagnostics / future UX decisions explicit

### 2.3 Refactor `updateParticipants`

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt` (currently lines 1251-1294)

Replace the current binary host/non-host logic with:

```
When roomState changes:
  myCid = clientId
  remotePeers = roomState.participants.filter { it.cid != myCid }
  remoteCids = remotePeers.map { it.cid }.toSet()

  // Remove slots for departed participants
  val departing = peerSlots.keys - remoteCids
  departing.forEach { cid ->
      peerSlots.remove(cid)?.closePeerConnection()
  }

  // Create slots for new participants
  remotePeers.forEach { participant ->
      if (participant.cid !in peerSlots) {
          val slot = webRtcEngine.createSlot(participant.cid, callbacks)
          peerSlots[participant.cid] = slot
          if (shouldIOffer(myCid, participant.cid, roomState)) {
              maybeSendOfferTo(slot)
          } else {
              scheduleNonHostFallbackFor(slot)
          }
      }
  }

  // Update phase and UI
  phase = if (remotePeers.isEmpty()) CallPhase.Waiting else CallPhase.InCall
  updateUiState(...)
```

### 2.4 Offer direction: `shouldIOffer`

```kotlin
private fun shouldIOffer(myCid: String, remoteCid: String, roomState: RoomState): Boolean {
    val myJoinedAt = roomState.participants.find { it.cid == myCid }?.joinedAt ?: 0
    val theirJoinedAt = roomState.participants.find { it.cid == remoteCid }?.joinedAt ?: 0
    return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
}
```

### 2.5 Route incoming signaling by `payload.from`

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

In `processSignalingPayload` (currently lines 1219-1249), extract `from` from the payload:

```kotlin
val fromCid = payload?.optString("from")?.ifBlank { null }
```

Route to the correct slot:
- **offer**: `val slot = getOrCreateSlot(fromCid)` then `slot.setRemoteDescription(OFFER, sdp) { slot.createAnswer { sendMessage("answer", it, to = fromCid) } }`
- **answer**: `peerSlots[fromCid]?.setRemoteDescription(ANSWER, sdp)`; clear offer timeout for that slot
- **ice**: `getOrCreateSlot(fromCid).addIceCandidate(candidate)`

### 2.6 Add `to` field to outgoing offer/answer/ice

All `sendMessage("offer", ...)`, `sendMessage("answer", ...)`, `sendMessage("ice", ...)` must include `to = slot.remoteCid`.

### 2.7 Per-slot ICE restart and non-host fallback

Move `scheduleIceRestart`, `triggerIceRestart`, `maybeSendOffer`, `scheduleNonHostFallback` to operate on a specific `PeerConnectionSlot` rather than globally. The patterns are the same as 1:1, just scoped to one slot.

### 2.8 Aggregate connection status

Compute worst-of across all slots for `iceConnectionState`, `connectionState`, `signalingState`. The `connectionStatus` state machine (Connected/Recovering/Retrying) uses the aggregate.

### 2.9 Track replacement across all slots

When `flipCamera()` or screen share toggles, the local video track is replaced on the `VideoSource` level — since all slots share the same `localVideoTrack` object, the track replacement on the source propagates automatically. Verify this works; if not, iterate slots and call `replaceTrack` on each sender.

### 2.10 Handle `ROOM_CAPACITY_UNSUPPORTED` error

In the error handler (currently in `handleSignalingMessage`), surface the new error code with a user-facing message explaining that this room requires a newer app version.

### 2.11 Per-slot remote video state polling

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`

The current 1:1 model polls a single `webRtcEngine.isRemoteVideoTrackEnabled()` flag every 500ms (lines 1535-1580) and writes the result into `CallUiState.remoteVideoEnabled`. This must be replaced with per-slot polling:

- Add `isRemoteVideoTrackEnabled()` to `PeerConnectionSlot` (moved from `WebRtcEngine`).
- In the existing `remoteVideoStatePollRunnable`, iterate all slots and build a `List<RemoteParticipant>`:
  ```kotlin
  val remoteParticipants = peerSlots.map { (cid, slot) ->
      RemoteParticipant(
          cid = cid,
          videoEnabled = slot.isRemoteVideoTrackEnabled(),
          connectionState = slot.getConnectionState().name
      )
  }
  ```
- Write the result into `CallUiState.remoteParticipants`.
- Derive the scalar `remoteVideoEnabled` from the first entry for 1:1 backward compat: `remoteVideoEnabled = remoteParticipants.firstOrNull()?.videoEnabled ?: false`.
- The per-participant `videoEnabled` flag drives placeholder/avatar display per tile in the multi-party grid.

### 2.12 Update local media addition

Mirror the web plan's local-media rule:

- When `startLocalMedia()` is called and `peerSlots` already exist, iterate all slots and call `attachLocalTracks(currentAudioTrack, currentVideoTrack)`
- When a new slot is created and local media already exists, call `attachLocalTracks(...)` immediately after slot creation
- Do not rely on constructor-time track injection alone; slots may be created before local media starts
- Camera flip and screen-share changes keep using the shared local track object, but a full stop/start of local media must refresh each slot's cached tracks

---

## Phase 3: UI — Multi-party grid

### 3.1 New data model: `RemoteParticipant`

**File**: `client-android/app/src/main/java/app/serenada/android/call/RemoteParticipant.kt`

```kotlin
data class RemoteParticipant(
    val cid: String,
    val videoEnabled: Boolean,
    val connectionState: String
)
```

### 3.2 Extend `CallUiState`

**File**: `client-android/app/src/main/java/app/serenada/android/call/CallUiState.kt`

Add:
```kotlin
val remoteParticipants: List<RemoteParticipant> = emptyList()
```

Keep `remoteVideoEnabled` derived from first remote participant for 1:1 backward compat.

### 3.3 Conditional layout in `CallScreen`

**File**: `client-android/app/src/main/java/app/serenada/android/ui/CallScreen.kt`

```kotlin
val isMultiParty = uiState.remoteParticipants.size > 1

if (isMultiParty) {
    MultiPartyStage(...)
} else {
    // Existing 1:1 layout unchanged
    ExistingCallLayout(...)
}
```

### 3.4 Multi-party stage composable

**File**: `client-android/app/src/main/java/app/serenada/android/ui/CallScreen.kt` (or a new file)

```kotlin
@Composable
fun MultiPartyStage(
    remoteParticipants: List<RemoteParticipant>,
    callManager: CallManager,
    eglContext: EglBase.Context?,
    localRenderer: SurfaceViewRenderer,
    shouldMirrorLocal: Boolean,
    isScreenSharing: Boolean
) {
    // Remote tiles in adaptive layout
    // Local video in PIP overlay
}
```

Each remote tile creates a `SurfaceViewRenderer` via `remember(cid)` and attaches it to the corresponding slot:

```kotlin
@Composable
fun RemoteParticipantTile(
    cid: String,
    callManager: CallManager,
    eglContext: EglBase.Context?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val renderer = remember(cid) {
        SurfaceViewRenderer(context).also {
            it.init(eglContext, null)
            it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)  // contain, not crop — matches web stage behavior
        }
    }
    DisposableEffect(cid) {
        callManager.attachRemoteRendererForCid(cid, renderer)
        onDispose {
            callManager.detachRemoteRendererForCid(cid, renderer)
            renderer.release()
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
```

### 3.5 Layout strategy

- Match the shipped web layout rules, not a generic tablet grid
- Multi-party renders a remote-only adaptive stage; local video always stays in a separate lower-right PIP
- Remote tile aspect ratio should follow the actual stream aspect ratio, clamped to `9:16 ... 16:9`
- Use the same candidate-row approach as web: enumerate the small set of possible row arrangements and choose the one that maximizes usable on-screen tile dimensions
- When candidate arrangements are close, prefer fewer rows instead of adding extra stacking for marginal gains
- Keep every remote tile fully contained within the stage viewport; use `SCALE_ASPECT_FIT` / contain semantics for remote video
- Use the full stage width instead of reserving an entire right-side safe column for the PIP; minor lower-right overlap is acceptable
- Use slightly reduced corner radii to match the current web stage direction rather than the older rounded grid look
- Tile placeholders / avatars should be driven per participant from `RemoteParticipant.videoEnabled`

### 3.6 Renderer routing in CallManager

Add to `CallManager`:
```kotlin
fun attachRemoteRendererForCid(cid: String, renderer: SurfaceViewRenderer) {
    peerSlots[cid]?.attachRemoteRenderer(renderer)
}

fun detachRemoteRendererForCid(cid: String, renderer: SurfaceViewRenderer) {
    peerSlots[cid]?.detachRemoteRenderer(renderer)
}
```

---

## Phase 4: Stats and diagnostics

### 4.1 Aggregate stats across slots

In the stats polling loop (currently lines 1535-1580), iterate all slots and merge stats using the existing callback-based API:

```kotlin
val slots = peerSlots.values.toList()
if (slots.isEmpty()) {
    updateAggregateStats(emptyList())
    return
}

val stats = mutableListOf<RealtimeCallStats>()
var remaining = slots.size
slots.forEach { slot ->
    slot.collectWebRtcStats { _, realtimeStats ->
        realtimeStats?.let(stats::add)
        remaining -= 1
        if (remaining == 0) {
            updateAggregateStats(stats)
        }
    }
}
```

Merge rules stay the same: sum bitrates where appropriate, use worst-of RTT / packet loss / freeze metrics, and keep the aggregate summary/debug view aligned with web.

### 4.2 Update `RealtimeCallStats`

The existing model works for aggregate stats. For per-peer detail, optionally add `peerStats: Map<String, RealtimeCallStats>` to the UI state.

---

## Verification

1. `./gradlew assembleDebug` — builds successfully
2. **1:1 regression**: Two devices in a call — verify identical UX (PIP, camera flip selfie→world→composite, screen share, reconnection)
3. **3-party**: Three devices — verify grid/stage layout with 3 tiles, all video/audio flowing
4. **4-party**: Four devices — verify all streams, layout adapts
5. **Join/leave mid-call**: Start 3-party, one leaves — verify layout transitions back to 1:1, remaining connections unaffected
6. **Camera flip in multi-party**: Verify track propagates to all peers (since tracks are shared via source)
7. **ICE restart**: Kill network on one device, verify only that peer's connection recovers
8. **Legacy compat**: Older APK joining a web-created or Android-created group room is rejected with `ROOM_CAPACITY_UNSUPPORTED`, while a room created first by an older 1:1-only client remains capped at 2 and can still be joined by the new Android build
9. Cross-platform smoke test: `bash tools/smoke-test/smoke-test.sh`

---

## Key files to modify

| File | Change |
|------|--------|
| `call/PeerConnectionSlot.kt` | **New** — per-peer PC, remote track, ICE, SDP, renderer management |
| `call/WebRtcEngine.kt` | Remove single-PC state; keep factory + local media; add `createSlot()` |
| `call/CallManager.kt` | Slot map, per-peer routing, `shouldIOffer`, capabilities in join, aggregate status |
| `call/CallUiState.kt` | Add `remoteParticipants: List<RemoteParticipant>` |
| `call/RoomState.kt` | Add `maxParticipants` parsing/storage |
| `call/RemoteParticipant.kt` | **New** — per-participant UI model |
| `call/SignalingMessage.kt` | No changes needed (`to` and payload fields already exist) |
| `ui/CallScreen.kt` | Conditional multi-party layout, `RemoteParticipantTile` composable |
