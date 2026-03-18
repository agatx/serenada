# CallManager Audit — SDK vs Host Classification

Audit of all three platform call managers to classify every public method/property as SDK (belongs in `serenada-core`) or Host (stays in the app).

---

## iOS — `CallManager.swift`

### Properties

| Name | Classification | Notes |
|------|---------------|-------|
| `uiState` | Straddle | Contains both SDK call state and host UI state — needs splitting into SDK `CallState` + host-only UI state |
| `serverHost` | Host | Settings persistence |
| `selectedLanguage` | Host | Settings persistence |
| `isDefaultCameraEnabled` | Host | Settings persistence |
| `isDefaultMicrophoneEnabled` | Host | Settings persistence |
| `isHdVideoExperimentalEnabled` | Host | Settings persistence |
| `areSavedRoomsShownFirst` | Host | Settings persistence |
| `areRoomInviteNotificationsEnabled` | Host | Settings persistence |
| `appVersion` | Host | App info for UI |
| `recentCalls` | Host | Recent call history |
| `savedRooms` | Host | Saved room persistence |
| `roomStatuses` | Host | Room occupancy watching |
| `locale` | Host | Computed from language setting |

### Public Methods — SDK

| Method | Notes |
|--------|-------|
| `validateServerHost(_:)` | Validates server connectivity |
| `startNewCall()` | Creates room via API and joins |
| `joinRoom(_:oneOffHost:)` | Core join — media + signaling |
| `joinSavedRoom(_:)` | Joins a saved room |
| `joinRecentCall(_:)` | Rejoins a recent call |
| `leaveCall()` | Sends leave, cleans up |
| `endCall()` | Initiates call end |
| `toggleAudio()` | Toggles local audio |
| `toggleVideo()` | Toggles local video |
| `toggleFlashlight()` | Toggles flashlight |
| `flipCamera()` | Cycles camera mode (selfie → world → composite) |
| `toggleScreenShare()` | Starts/stops screen share |
| `adjustCameraZoom(scaleDelta:)` | Camera zoom |
| `resetCameraZoom()` | Reset zoom |
| `attachLocalRenderer(_:)` | Attach local video renderer |
| `detachLocalRenderer(_:)` | Detach local video renderer |
| `attachRemoteRenderer(_:)` | Attach remote video renderer (auto peer) |
| `detachRemoteRenderer(_:)` | Detach remote video renderer |
| `attachRemoteRenderer(_:forCid:)` | Attach renderer to specific peer |
| `detachRemoteRenderer(_:forCid:)` | Detach renderer from specific peer |

### Public Methods — Host

| Method | Notes |
|--------|-------|
| `updateLanguage(_:)` | Settings persistence |
| `updateDefaultCamera(_:)` | Settings persistence |
| `updateDefaultMicrophone(_:)` | Settings persistence |
| `updateSavedRoomsShownFirst(_:)` | Settings persistence |
| `updateRoomInviteNotifications(_:)` | Settings persistence |
| `dismissError()` | Dismisses error UI, refreshes history |
| `removeRecentCall(roomId:)` | Removes from history |
| `saveRoom(roomId:name:host:)` | Saves room |
| `removeSavedRoom(roomId:)` | Removes saved room |

### Public Methods — Straddle (need splitting)

| Method | SDK Part | Host Part |
|--------|----------|-----------|
| `init(...)` | WebRTC engine, signaling client | Settings store, recent/saved stores, push observer |
| `updateServerHost(_:)` | Reconnects signaling | Persists setting |
| `updateHdVideoExperimental(_:)` | Applies WebRTC config | Persists setting |
| `inviteToCurrentRoom()` | Current room awareness | Push invite API call |
| `handleDeepLink(_:)` | URL parsing, join room | Save room to persistence |
| `joinFromInput(_:)` | Parse input, join room | Save room to persistence |
| `createSavedRoomInviteLink(...)` | Create room via API | Save to persistence |
| `handleJoined(_:)` | Join ack, WebRTC setup | Push subscription, snapshot upload |
| `cleanupCall(...)` | Reset SDK resources | Save to call history |

---

## Android — `CallManager.kt`

### Properties

| Name | Classification | Notes |
|------|---------------|-------|
| `uiState` | Straddle | Same as iOS — mixed SDK + host state |
| `serverHost` | Host | Settings persistence |
| `selectedLanguage` | Host | Settings persistence |
| `isDefaultCameraEnabled` | Host | Settings persistence |
| `isDefaultMicrophoneEnabled` | Host | Settings persistence |
| `isHdVideoExperimentalEnabled` | Host | Settings persistence |
| `recentCalls` | Host | Recent call history |
| `savedRooms` | Host | Saved room persistence |
| `areSavedRoomsShownFirst` | Host | Settings persistence |
| `areRoomInviteNotificationsEnabled` | Host | Settings persistence |
| `roomStatuses` | Host | Room occupancy watching |

### Public Methods — SDK

| Method | Notes |
|--------|-------|
| `startNewCall()` | Creates room via API and joins |
| `joinRoom(roomId:oneOffHost:)` | Core join — media + signaling |
| `joinSavedRoom(_:)` | Joins a saved room |
| `joinRecentCall(_:)` | Rejoins a recent call |
| `joinFromInput(input:)` | Parses input and joins |
| `leaveCall()` | Sends leave, cleans up |
| `endCall()` | Alias for leave |
| `toggleAudio()` | Toggles local audio |
| `toggleVideo()` | Toggles local video |
| `toggleFlashlight()` | Toggles flashlight |
| `flipCamera()` | Cycles camera mode |
| `adjustLocalCameraZoom(scaleFactor:)` | Camera zoom |
| `startScreenShare(intent:)` | Starts screen share |
| `stopScreenShare()` | Stops screen share |
| `attachLocalRenderer(...)` | Attach local video renderer |
| `detachLocalRenderer(...)` | Detach local video renderer |
| `attachRemoteRenderer(...)` | Attach remote video renderer |
| `detachRemoteRenderer(...)` | Detach remote video renderer |
| `attachLocalSink(...)` | Attach local video sink |
| `detachLocalSink(...)` | Detach local video sink |
| `attachRemoteSink(...)` | Attach remote video sink |
| `detachRemoteSink(...)` | Detach remote video sink |
| `attachRemoteRendererForCid(...)` | Per-peer renderer |
| `detachRemoteRendererForCid(...)` | Per-peer renderer |
| `attachRemoteSinkForCid(...)` | Per-peer sink |
| `detachRemoteSinkForCid(...)` | Per-peer sink |
| `eglContext()` | EGL context for rendering |

### Public Methods — Host

| Method | Notes |
|--------|-------|
| `updateServerHost(host:)` | Settings persistence |
| `validateServerHost(host:onResult:)` | Settings validation |
| `updateLanguage(language:)` | Settings persistence |
| `updateDefaultCamera(enabled:)` | Settings persistence |
| `updateDefaultMicrophone(enabled:)` | Settings persistence |
| `updateHdVideoExperimental(enabled:)` | Settings persistence |
| `updateSavedRoomsShownFirst(enabled:)` | Settings persistence |
| `updateRoomInviteNotifications(enabled:)` | Settings persistence |
| `saveRoom(roomId:name:host:)` | Saves room |
| `removeSavedRoom(roomId:)` | Removes saved room |
| `removeRecentCall(roomId:)` | Removes from history |
| `dismissError()` | Dismisses error UI |

### Public Methods — Straddle

| Method | SDK Part | Host Part |
|--------|----------|-----------|
| `inviteToCurrentRoom(...)` | Current room awareness | Push invite API call |
| `createSavedRoomInviteLink(...)` | Room creation via API | Save to persistence |
| `handleDeepLink(uri:)` | URL parsing, join | Save room, host policy |
| `roomStatuses` | Signaling connection | Room watching feature |

---

## Web — `SignalingContext.tsx` + `WebRTCContext.tsx`

All exports from these two files are SDK-appropriate. No host concerns are present in these context files (push, saved rooms, settings live in other parts of the app).

### SignalingContext.tsx — SDK

| Export | Type | Notes |
|--------|------|-------|
| `SignalingProvider` | Component | Wraps signaling transport, reconnection, fallback (WS→SSE), TURN refresh, keepalive |
| `useSignaling` | Hook | Returns signaling state, transport, join/leave/end, message sending |
| `SignalingContextValue` | Interface | `isConnected`, `activeTransport`, `clientId`, `roomState`, `turnToken`, `joinRoom()`, `leaveRoom()`, `endRoom()`, `sendMessage()`, `subscribeToMessages()`, `watchRooms()` |

### WebRTCContext.tsx — SDK

| Export | Type | Notes |
|--------|------|-------|
| `WebRTCProvider` | Component | Peer connection lifecycle, offer/answer, ICE, local media, TURN fetching, recovery |
| `useWebRTC` | Hook | Media streams, peer connections, media control, connection status |
| `WebRTCContextValue` | Interface | `localStream`, `remoteStreams`, `startLocalMedia()`, `stopLocalMedia()`, `startScreenShare()`, `stopScreenShare()`, `flipCamera()`, `connectionStatus` |
| `ConnectionStatus` | Type | `'connected' | 'recovering' | 'retrying'` |
| `DEFAULT_RTC_CONFIG` | Constant | Default STUN config |

---

## Summary: Straddling Methods That Need Splitting

Across platforms, the same patterns emerge for methods that mix SDK and host concerns:

1. **Initialization** — constructor wires both SDK (WebRTC engine, signaling) and host (settings stores, push observers). Split into SDK init + host wiring.
2. **Deep link handling** — parses URL (SDK) + may save room (host). SDK provides URL parser; host calls it then decides what to do.
3. **Join completion** — SDK handles join ack + WebRTC setup; host subscribes to push and uploads snapshot. SDK emits lifecycle callback; host reacts.
4. **Call cleanup** — SDK resets resources; host saves to history. SDK emits `sessionDidEnd`; host saves history.
5. **Invite creation** — creates room via API (SDK) + saves to persistence (host). SDK exposes `createRoom()`; host saves result.
6. **Settings that affect SDK** — HD video, server host. Host persists; SDK accepts config updates via method calls.
