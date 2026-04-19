# Voice-Only Call Mode

## Context

Serenada currently supports only video calls. This change adds a voice-only call mode across all layers: server, SDKs (web, Android, iOS), and app UIs. Voice rooms join audio-only by default, support a higher participant ceiling (configurable, default 8), and use a minimal list-based UI instead of the video grid. Camera sharing remains optional after join.

## Design Decisions

- **Room mode is immutable**: set by the creator's first join, never changes. Mode describes the room's character, not a hard media constraint.
- **Audio-first, camera optional**: voice rooms skip camera permission on initial join, but any participant can opt in to share camera later. Other participants are never prompted.
- **Separate capacity ceiling**: new env var `MAX_VOICE_ROOM_PARTICIPANTS` (default 8), independent of `MAX_ROOM_PARTICIPANTS` (default 4).
- **Client capability is mode-aware**: voice joins must advertise/request 8 participants, while video joins remain at 4. Server-side `MAX_VOICE_ROOM_PARTICIPANTS=8` is not enough on its own.
- **Starting a call uses a segmented button on the home screen**: primary action starts a video call. Secondary action opens a menu with `Start Video Call` and `Start Voice Call`.
  ```
  [ (icon) Start call | ... ]
  ```
- **Voice call UI uses a dedicated video tile above the list**: the main layout is a vertical participant list with mute indicators. If anyone is sharing camera, a dedicated video tile appears above the list. The list stays sorted by join order. Participants sharing camera get an icon in the list. Clicking a participant switches the dedicated tile to that participant's video.
- **No new public camera-start API**: keep the public SDK surface minimal. In voice mode, existing `toggleVideo()` / `setVideoEnabled(true)` APIs become lazy camera acquisition paths. If no local video track exists yet, enabling video requests camera permission on demand and creates the track. `setVideoEnabled(false)` / `toggleVideo()` disables or tears down that track. `flipCamera()` / `setCameraMode()` are no-ops until local camera sharing is active.
- **Mode is persisted in client metadata and links**: saved rooms and recent calls store `mode`. Generated links for voice rooms append `?mode=voice`. If local metadata is absent, empty-room creation defaults to video. Once connected, server `joined` / `room_state` is the source of truth.
- **Mute indicators require explicit participant media-state propagation**: add a lightweight peer-broadcast control message, `participant_media_state`, carrying `audioEnabled` and `videoEnabled`. SDK call state surfaces this for UI.
- **Backward compatible**: old clients default to `"video"` mode; unknown `mode` values and unknown peer control messages are ignored.

---

## Phase 1: Server

### Files to modify
- `server/signaling.go` — `Room`, `Hub`, `newHub`, `handleJoin`, `broadcastRoomState`, `broadcastRoomStatusUpdate`, `handleWatchRooms`
- `server/main.go` — parse `MAX_VOICE_ROOM_PARTICIPANTS`
- `server/multiparty_test.go` — new test cases

### Changes

1. **Room struct**: add `Mode string`.
2. **Hub struct**: add `maxVoiceParticipantsLimit int`.
3. **newHub**: accept `maxVoiceParticipantsLimit int` and store it.
4. **main.go**: parse `MAX_VOICE_ROOM_PARTICIPANTS` (default 8, min 2), pass it to `newHub`.
5. **handleJoin join payload struct**: add `Mode string`.
6. **Normalize join mode** in `handleJoin`: only `"voice"` is special; empty/unknown values become `"video"`.
7. **Room creation**: clamp `createMax` against the mode-specific ceiling:
   ```go
   ceiling := h.maxParticipantsLimit
   if normalizedMode == "voice" {
       ceiling = h.maxVoiceParticipantsLimit
   }
   if createMax > ceiling {
       createMax = ceiling
   }
   ```
   Set `room.Mode = normalizedMode` when creating a new room.
8. **Room reuse**: if the room already exists, ignore the incoming join payload `Mode` and keep `room.Mode` unchanged.
9. **joined payload**: add `"mode": room.Mode`.
10. **broadcastRoomState**: add `"mode": room.Mode`.
11. **handleWatchRooms room_statuses**: add `"mode"` to each status object.
12. **broadcastRoomStatusUpdate**: add `"mode"` to the payload.

### Tests to add
- Voice room uses the voice ceiling (8 default).
- Voice room accepts 8 participants and rejects the 9th.
- `joined` and `room_state` contain `"mode": "voice"`.
- `room_statuses` and `room_status_update` contain `"mode": "voice"`.
- Missing mode defaults to `"video"`.
- Second joiner cannot change mode.
- Video tracks are not blocked in voice rooms (server remains media-agnostic).

---

## Phase 2: Web SDK (`client/packages/core/src/`)

### Files to modify
- `types.ts` — add `CallMode`, update `SerenadaConfig`, `CallState`
- `SignalingProvider.ts` — add `callMode` to `JoinOptions`, `JoinedEvent`, `RoomStateEvent`
- `SerenadaServerProvider.ts` — pass mode through join flow, parse mode from `joined` / `room_state`, forward `participant_media_state`
- `signaling/SignalingEngine.ts` — include `mode` in join payload, use mode-aware participant capabilities
- `signaling/payloads.ts` — parse `mode` from `joined` / `room_state`
- `signaling/types.ts` — add `mode` to `RoomState`
- `signaling/roomStatuses.ts` — add `mode` to room watcher state
- `RoomWatcher.ts` — expose `mode` from watcher payloads
- `SerenadaSession.ts` — store mode, expose it in `CallState`, gate permissions/media, track remote participant media state
- `media/MediaEngine.ts` — voice-only join: `getUserMedia({ audio, video: false })`; lazy camera start later
- `SerenadaCore.ts` — pass config `callMode` to the session

### Changes

1. **types.ts**: add `export type CallMode = 'video' | 'voice'`.
2. **SerenadaConfig**: add `callMode?: CallMode` (default `'video'`).
3. **CallState**: add `callMode: CallMode`.
4. **JoinOptions**: add `callMode?: CallMode`.
5. **JoinedEvent / RoomStateEvent / RoomState**: add `callMode?: CallMode`.
6. **Mode-aware join capacity** in `SignalingEngine.joinRoom()`:
   - `video` join: capabilities/create max stay `4`
   - `voice` join: capabilities/create max become `8`
   - persist the last requested mode across reconnects
7. **Join payload**: include `mode: options.callMode ?? 'video'`.
8. **payload parsing**: parse `mode` from `joined` and `room_state`, default to `'video'`.
9. **Room watcher parsing**: parse `mode` from `room_statuses` / `room_status_update`.
10. **SerenadaServerProvider**: forward `callMode` into join options and surface parsed mode on provider events.
11. **SerenadaSession**: accept `callMode` from config, replace it with server truth from `joined` / `room_state`, and include it in `rebuildState()`.
12. **Permission gating**: for voice mode, initial join only requires `['microphone']`.
13. **MediaEngine voice behavior**:
    - initial join uses `getUserMedia({ audio, video: false })`
    - `toggleVideo()` / `setVideoEnabled(true)` lazily request camera permission and create a local video track if one does not exist
    - disabling video stops or removes that track
    - `flipCamera()` / `setCameraMode()` no-op until local camera sharing is active
14. **SerenadaSessionHandle**: add `readonly callMode: CallMode` (derived from state).
15. **Remote participant media state**:
    - add peer control message `participant_media_state`
    - broadcast local `audioEnabled` / `videoEnabled` after join and on each local audio/video state change
    - update remote participant state from this message instead of hardcoding `audioEnabled: true`
16. **Unsupported session**: include `callMode: 'video'` in the error state.

---

## Phase 3: Web App UI

### Files to modify
- `client/src/pages/Home.tsx` — segmented Start Call button with voice option
- `client/src/pages/CallRoom.tsx` — parse mode, disable camera preview for voice prejoin, persist resolved mode
- `client/packages/react-ui/src/SerenadaCallFlow.tsx` — voice call rendering path
- `client/src/components/RecentCalls.tsx` — preserve and reuse stored room mode
- `client/src/components/SavedRooms.tsx` — preserve and reuse stored room mode
- `client/src/utils/callHistory.ts` — add `mode`
- `client/src/utils/savedRooms.ts` — add `mode`
- `client/src/i18n.ts` — add voice call strings

### Changes

1. **Home.tsx**: replace the single Start Call button with a segmented button:
   - primary click: create room and navigate to `/call/${roomId}`
   - secondary menu options:
     - `Start Video Call` -> `/call/${roomId}`
     - `Start Voice Call` -> `/call/${roomId}?mode=voice`
2. **Saved room / recent call persistence**:
   - add `mode?: CallMode` to saved rooms and recent call history
   - missing stored mode defaults to video when reading old entries
3. **SavedRooms.tsx / RecentCalls.tsx**:
   - when a saved room or recent call has `mode === 'voice'`, navigate using `?mode=voice`
   - when generating share links for a voice room, append `mode=voice`
4. **CallRoom.tsx**:
   - read `mode` query param and pass `callMode` into the SDK
   - if `mode=voice` and the user has not joined yet, do not create a camera preview stream and do not request camera permission
   - show an audio-only prejoin state instead of the current camera preview
   - when join succeeds or the call ends, persist the resolved room mode into recent call history and any saved-room record for that room
5. **SerenadaCallFlow.tsx** — when `callState.callMode === 'voice'`:
   - show header: `Voice Call · N people`
   - default layout: dedicated video tile above the participant list
   - if nobody is sharing video, collapse the tile area and show only the participant list
   - participant list rows include mute indicator, CID / display name, and a video-share icon when applicable
   - clicking a participant row switches the dedicated tile to that participant
   - controls: mute toggle, end call, `Share Camera`; hide screen share
   - `Share Camera` uses existing SDK `toggleVideo()` / `setVideoEnabled(true)` behavior
   - waiting screen remains share/QR-capable, but stays audio-only
6. **i18n.ts**: add keys for segmented-button menu text, voice headers, audio-only prejoin copy, and `Share Camera`.

---

## Phase 4: Android SDK (`client-android/serenada-core/`)

### Files to modify
- `SerenadaConfig.kt` — add `callMode: CallMode`
- New enum: `CallMode.kt` (`VIDEO`, `VOICE`)
- `CallState.kt` — add `callMode: CallMode`
- `SignalingProvider.kt` — add `callMode` to `JoinOptions`, `JoinedEvent`, `RoomStateEvent`
- `call/RemoteParticipant.kt` — add `audioEnabled`
- `call/SignalingPayloads.kt` — add mode to join payload, parse from `joined` / `room_state`
- `call/SignalingClient.kt` — include mode in join message
- `call/SignalingMessageRouter.kt` — extract mode, handle `participant_media_state`
- `SerenadaSession.kt` — pass mode, update state, gate permissions/media, broadcast local media state
- `call/WebRtcEngine.kt` — voice-only join: skip video source/track/capturer creation

### Changes mirror web SDK

1. Add `CallMode` enum.
2. Add `callMode` to config, join options, joined events, room state, and public call state.
3. Use mode-aware join capability/request values:
   - `VIDEO` => `maxParticipants = 4`
   - `VOICE` => `maxParticipants = 8`
4. Include `"mode"` in join signaling payload.
5. Parse `"mode"` from `joined` and `room_state`.
6. Voice mode: only request `RECORD_AUDIO` on initial join.
7. Voice mode: initial `WebRtcEngine` join skips local video creation.
8. Existing `toggleVideo(enabled)` path becomes lazy camera acquisition when local video is absent; disabling video stops or removes the local video track.
9. Add `participant_media_state` handling so remote participants expose `audioEnabled` and `videoEnabled`.

---

## Phase 5: Android App UI (`client-android/`)

### Files to modify
- `app/src/main/java/app/serenada/android/ui/JoinScreen.kt` — segmented Start Call button
- `serenada-call-ui/.../CallScreen.kt` — voice call rendering path
- `serenada-call-ui/.../CallUiState.kt` — add `callMode`
- `app/src/main/java/app/serenada/android/call/CallManager.kt` — pass/persist `callMode`, parse deep-link mode, build voice share links
- `app/src/main/java/app/serenada/android/data/SavedRoomStore.kt` — add `mode`
- `app/src/main/java/app/serenada/android/data/RecentCallStore.kt` — add `mode`

### Changes

1. **JoinScreen.kt**: replace the single Start Call action with a segmented button:
   - primary action starts video
   - secondary menu includes `Start Video Call` and `Start Voice Call`
2. **SavedRoomStore.kt / RecentCallStore.kt**: add `mode`, default missing values to `VIDEO`.
3. **CallManager.kt**:
   - accept and forward `callMode`
   - parse `mode` from deep links
   - append `mode=voice` to generated saved-room invite links for voice rooms
   - persist resolved room mode into saved rooms and recent calls
   - when joining a saved room or recent call with known voice mode, create the session in voice mode
4. **CallUiState.kt**: add `callMode: CallMode = CallMode.VIDEO`.
5. **CallScreen.kt**: when `callMode == VOICE`:
   - dedicated video tile above the participant list
   - `LazyColumn` participant list with avatar, name/CID, mute indicator, and video-share icon
   - tapping a participant switches the dedicated video tile
   - controls: mute, end call, `Share Camera`; hide screen share

---

## Phase 6: iOS SDK (`client-ios/SerenadaCore/Sources/`)

### Files to modify
- `SerenadaConfig.swift` — add `callMode: CallMode`
- New enum in `Models/CallMode.swift`
- `Models/CallState.swift` — add `callMode: CallMode`
- `SignalingProvider.swift` — add `callMode` to `JoinOptions`, `JoinedEvent`, `RoomStateEvent`
- `Signaling/SignalingPayloads.swift` — add mode to join payload, parse from `joined` / `room_state`
- `SerenadaServerProvider.swift` — include mode in join message, surface mode on events, forward `participant_media_state`
- `SerenadaSession.swift` — pass mode, update state, gate permissions/media, broadcast local media state
- `Call/WebRtcEngine.swift` — voice-only join: skip `RTCVideoSource` / track / capturer creation

### Changes mirror web / Android SDK

1. Add `CallMode` and thread it through config, join options, room state, and public call state.
2. Use mode-aware join capability/request values: video stays 4, voice becomes 8.
3. Include `"mode"` in join signaling payload and parse it from `joined` / `room_state`.
4. Voice mode: initial join only requests microphone permission.
5. Existing `toggleVideo()` / `setVideoEnabled(true)` path becomes lazy camera acquisition when local video is absent.
6. Add `participant_media_state` handling so remote participants expose mute/video state.

---

## Phase 7: iOS App UI (`client-ios/`)

### Files to modify
- `Sources/UI/Screens/JoinScreen.swift` — segmented Start Call button
- `SerenadaCallUI/Sources/CallScreen.swift` — voice call rendering path
- `SerenadaCallUI/Sources/SerenadaCallFlow.swift` — pass `callMode`
- `Sources/Core/Call/CallManager.swift` — accept/persist `callMode`, build voice share links
- `Sources/Core/Models/SavedRoom.swift` — add `mode`
- `Sources/Core/Models/RecentCall.swift` — add `mode`
- `Sources/Core/Stores/SavedRoomStore.swift` — persist `mode`
- `Sources/Core/Stores/RecentCallStore.swift` — persist `mode`
- `client-ios/SerenadaCore/Sources/Utils/DeepLinkParser.swift` — parse normalized `mode`

### Changes

1. **JoinScreen.swift**: replace the single Start Call action with a segmented button:
   - primary action starts video
   - secondary menu includes `Start Video Call` and `Start Voice Call`
2. **SavedRoom / RecentCall models and stores**: add `mode`, default missing values to `.video`.
3. **DeepLinkParser.swift**: parse `mode=voice` and expose normalized mode on deep-link targets.
4. **CallManager.swift**:
   - forward `callMode` when starting/joining a room
   - persist resolved room mode into saved rooms and recent calls
   - append `mode=voice` to generated shared links for voice rooms
   - when joining a saved room or recent call with known voice mode, create the session in voice mode
5. **CallScreen.swift**: when `callMode == .voice`:
   - dedicated video tile above the participant list
   - participant rows show name/CID, mute indicator, and video-share icon
   - tapping a participant switches the dedicated tile
   - controls: mute, end call, `Share Camera`; hide screen share

---

## Phase 8: Documentation & Protocol

### Files to update
- `docs/serenada_protocol_v1.md` — document:
  - `mode` in join payload, joined response, `room_state`, `room_statuses`, `room_status_update`
  - new peer control message `participant_media_state`
- `.env.example` — add `MAX_VOICE_ROOM_PARTICIPANTS`
- `docs/push-notifications.md` — document invite/deep-link payloads with `?mode=voice` for voice rooms
- `CLAUDE.md` — mention voice mode in architecture overview

---

## Verification

1. **Server tests**: `cd server && go test ./...`
   - voice room ceiling tests pass
   - mode payload tests pass for `joined`, `room_state`, `room_statuses`, `room_status_update`
2. **Web tests**: `cd client && npm run test`
   - voice joins advertise/request 8 participants
   - `participant_media_state` updates remote participant mute/video state
   - saved room / recent call mode persistence tests pass
3. **Web build**: `cd client && npm run build`
4. **Integration test**: `bash tools/integration-test/run.sh`
   - signaling integration still passes
   - voice room creation and rejoin paths preserve mode
5. **Manual web test**:
   - voice prejoin does not request camera or create camera preview
   - voice join shows list UI, not video grid
   - second participant sees voice mode
   - up to 8 participants can join a voice room
   - `Share Camera` requests camera permission on demand and shows the dedicated video tile
   - muting/unmuting updates the participant list indicator for other clients
   - saving or reopening a voice room from saved rooms / recent calls preserves voice mode
   - copying a voice-room link includes `mode=voice`
6. **Android / iOS**:
   - segmented home button works
   - voice deep links / saved rooms / recent calls preserve mode
   - no camera permission on initial voice join
   - optional camera sharing works
   - mute indicators update correctly
7. **Resilience parity**: `node scripts/check-resilience-constants.mjs`
8. **Version parity**: `node scripts/check-version-parity.mjs`
