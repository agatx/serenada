# Voice-Only Call Mode

## Context

Serenada currently supports only video calls. This change adds a voice-only call mode across all layers — server, SDKs (web, Android, iOS), and app UIs. Voice rooms are strictly audio-only (no video, camera never requested), support a higher participant ceiling (configurable, default 8), and present a minimal list-based UI instead of the video grid.

## Design Decisions

- **Room mode is immutable**: set by the creator's first join, never changes. Mode describes the room's *character*, not a hard media constraint.
- **Audio-first, camera optional**: voice rooms skip camera permission on join, but any participant can opt-in to share their camera at any time. Other participants are never prompted.
- **Separate capacity ceiling**: new env var `MAX_VOICE_ROOM_PARTICIPANTS` (default 8), independent of `MAX_ROOM_PARTICIPANTS` (default 4).
- **Starting a call** is done by clicking on the ... icon on the right side of the Start call button and selecting "Start Voice Call" from the menu the pops up (the second option in the menu is "Start Video Call"). Effectively the Start call button becomes a segmented button with primary single click action starting a video call and the secondary button opening a menu. Button layout:
[ (icon) Start call | ... ]

- **Voice call UI**: minimal vertical scrollable participant list with mute indicators; participants sharing video get a dedicated video tile above the list (similar layout to the focused video in video calls, but with room participants list below). The person sharing a video is always the first in the list with an icon indicating that they are sharing a video. The list is sorted by the order in which participants joined the room. If multiple people share video, the first person who joined is always the first in the list, and the rest are sorted by the order in which they joined. You can switch video feeds by clicking on the participant's name in the list.
- **Backward compatible**: old clients default to `"video"` mode; unknown `mode` field is ignored.

---

## Phase 1: Server

### Files to modify
- `server/signaling.go` — Room struct, Hub struct, newHub, handleJoin, broadcastRoomState, broadcastRoomStatusUpdate, handleWatchRooms
- `server/main.go` — parse `MAX_VOICE_ROOM_PARTICIPANTS` env var
- `server/multiparty_test.go` — new test cases

### Changes

1. **Room struct** (signaling.go:88): add `Mode string` field
2. **Hub struct** (signaling.go:79): add `maxVoiceParticipantsLimit int` field
3. **newHub** (signaling.go:111): accept second param `maxVoiceParticipantsLimit int`, store it
4. **main.go** (line 22-29): parse `MAX_VOICE_ROOM_PARTICIPANTS` env var (default 8, min 2), pass to `newHub`
5. **handleJoin join payload struct** (signaling.go:287): add `Mode string` field
6. **handleJoin room creation** (signaling.go:308-317): after clamping `createMax`, choose ceiling based on mode:
   ```
   ceiling := h.maxParticipantsLimit
   if normalizedMode == "voice" { ceiling = h.maxVoiceParticipantsLimit }
   ```
   Set `room.Mode` when creating room (default `"video"` if empty/unknown)
7. **joined payload** (signaling.go:449): add `"mode": room.Mode`
8. **broadcastRoomState** (signaling.go:768): add `"mode": room.Mode`
9. **handleWatchRooms room_statuses** (signaling.go:899): add `"mode"` to status map
10. **broadcastRoomStatusUpdate** (signaling.go:939): add `"mode"` to payload

### Tests to add
- Voice room uses voice ceiling (8 default)
- Voice room accepts 8 participants, rejects 9th
- `joined` and `room_state` contain `"mode": "voice"`
- Missing mode defaults to `"video"`
- Second joiner cannot change mode
- Video tracks are not blocked in voice rooms (server is media-agnostic)

---

## Phase 2: Web SDK (`client/packages/core/src/`)

### Files to modify
- `types.ts` — add `CallMode`, update `SerenadaConfig`, `CallState`, `JoinOptions`, `JoinedEvent`, `RoomStateEvent`
- `SignalingProvider.ts` — add `callMode` to `JoinOptions`, `JoinedEvent`, `RoomStateEvent`
- `SerenadaServerProvider.ts` — pass mode through joinRoom, parse mode from joined/roomState
- `signaling/SignalingEngine.ts` — include `mode` in join payload
- `signaling/payloads.ts` — parse `mode` from joined and room_state payloads
- `signaling/types.ts` — add `mode` to `RoomState`
- `SerenadaSession.ts` — store mode, expose in CallState, gate media/permissions
- `media/MediaEngine.ts` — voice-only mode: `getUserMedia({ audio, video: false })`, skip video ops
- `SerenadaCore.ts` — pass config.callMode to session
- `api/roomApi.ts` — no change needed (room ID is mode-agnostic)

### Changes

1. **types.ts**: add `export type CallMode = 'video' | 'voice'`
2. **SerenadaConfig**: add `callMode?: CallMode` (default `'video'`)
3. **CallState**: add `callMode: CallMode`
4. **JoinOptions** (SignalingProvider.ts): add `callMode?: CallMode`
5. **JoinedEvent / RoomStateEvent**: add `callMode?: CallMode`
6. **SignalingEngine.joinRoom** (line 140): add `mode: options.callMode ?? 'video'` to payload
7. **payloads.ts parseJoinedPayload**: parse `mode` field, default `'video'`
8. **payloads.ts parseRoomStatePayload**: parse `mode` field
9. **SerenadaServerProvider.joinRoom**: forward `callMode` from options
10. **SerenadaServerProvider** joined/roomState handlers: extract `mode` and pass as `callMode`
11. **SerenadaSession**: accept `callMode` from config, update from joined response, include in `rebuildState()`
12. **SerenadaSession permission check**: for voice mode, only require `['microphone']` on join (skip camera)
13. **MediaEngine**: voice mode: `getUserMedia({ audio, video: false })` on join; `enableCamera()` still works when called later (requests camera permission on demand); no-op for flipCamera/setCameraMode only when camera is off
14. **SerenadaSessionHandle**: add `readonly callMode: CallMode` (derived from state)
15. **Unsupported session** (SerenadaCore.ts:61): add `callMode: 'video'` to error state

---

## Phase 3: Web App UI

### Files to modify
- `client/src/pages/Home.tsx` — add "Start Voice Call" button
- `client/packages/react-ui/src/SerenadaCallFlow.tsx` — voice call rendering path
- `client/src/pages/CallRoom.tsx` — pass callMode to SDK
- `client/src/i18n.ts` — add voice call strings (all locales)

### Changes

1. **Home.tsx**: add a second button below "Start Call":
   - Icon: `Mic` (from lucide-react) instead of `Video`
   - Label: `t('start_voice_call')`
   - Navigate to `/call/${roomId}?mode=voice`
2. **CallRoom.tsx**: read `mode` query param, pass `callMode: 'voice'` to `SerenadaConfig`
3. **SerenadaCallFlow.tsx** — when `callState.callMode === 'voice'`:
   - Default layout: `VoiceCallParticipantList` — vertical list with mute indicators and CID labels
   - Participants sharing video: their list row expands to show an inline video tile
   - Show header: "Voice Call · N people"
   - Controls: mute toggle + end call + "Share Camera" button (world camera icon); no flip/screen share
   - When local user shares camera: show local video preview above controls
   - Waiting screen: "Waiting for others to join..." with share/QR controls
4. **i18n.ts**: add keys: `start_voice_call`, `voice_call_header`, `voice_call_waiting`, `share_camera`

---

## Phase 4: Android SDK (`client-android/serenada-core/`)

### Files to modify
- `SerenadaConfig.kt` — add `callMode: CallMode`
- New enum: `CallMode.kt` (`VIDEO`, `VOICE`)
- `CallState.kt` — add `callMode: CallMode`
- `call/SignalingPayloads.kt` — add mode to join payload, parse from joined/room_state
- `call/SignalingClient.kt` — include mode in join message
- `call/SignalingMessageRouter.kt` — extract mode from joined/room_state
- `SerenadaSession.kt` — pass mode, update state, gate permissions/media
- `call/WebRtcEngine.kt` — voice-only: skip video source/track/capturer creation

### Changes mirror web SDK:
1. Add `CallMode` enum
2. Add `callMode` to config and state
3. Include `"mode"` in join signaling payload
4. Parse `"mode"` from `joined` and `room_state`
5. Voice mode: only request `RECORD_AUDIO` permission on join (skip `CAMERA`)
6. Voice mode: `WebRtcEngine` skips video source/track/capturer on join; `enableCamera()` requests `CAMERA` permission on demand and creates video track when called later

---

## Phase 5: Android App UI (`client-android/`)

### Files to modify
- `app/.../ui/JoinScreen.kt` — add "Start Voice Call" FAB or button
- `serenada-call-ui/.../CallScreen.kt` — voice call rendering path
- `serenada-call-ui/.../CallUiState.kt` — add `callMode`
- `app/.../call/CallManager.kt` — pass callMode through

### Changes
1. **JoinScreen.kt**: add second FAB or button with mic icon for voice calls
2. **CallManager.kt**: accept and forward `callMode` parameter
3. **CallUiState.kt**: add `callMode: CallMode = CallMode.VIDEO`
4. **CallScreen.kt**: when `callMode == VOICE`:
   - Default: `VoiceCallParticipantList` composable — `LazyColumn` of participant rows with circle avatar + CID + mute icon
   - Participants sharing video: row expands to show inline `SurfaceViewRenderer`
   - Header: "Voice Call · N people"
   - Controls: mute + end call + "Share Camera" button; hide flip, screen share, flash
   - When local user shares camera: show local camera preview above controls

---

## Phase 6: iOS SDK (`client-ios/SerenadaCore/Sources/`)

### Files to modify
- `SerenadaConfig.swift` — add `callMode: CallMode`
- New enum in `Models/CallMode.swift`
- `Models/CallState.swift` — add `callMode: CallMode`
- `Signaling/SignalingPayloads.swift` — add mode to join payload, parse from joined/room_state
- `Signaling/SignalingClient.swift` — include mode in join message
- `Call/SignalingMessageRouter.swift` — extract mode
- `SerenadaSession.swift` — pass mode, update state, gate permissions/media
- `Call/WebRtcEngine.swift` — voice-only: skip RTCVideoSource/Track/Capturer

### Changes mirror web/Android SDK:
- Voice mode: only request microphone on join; camera permission requested on demand when participant opts in to share video

---

## Phase 7: iOS App UI (`client-ios/`)

### Files to modify
- `Sources/UI/Screens/JoinScreen.swift` — add "Start Voice Call" button
- `SerenadaCallUI/Sources/CallScreen.swift` — voice call rendering path
- `SerenadaCallUI/Sources/SerenadaCallFlow.swift` — pass callMode
- `Sources/Core/Call/CallManager.swift` — accept and forward callMode

### Changes
1. **JoinScreen.swift**: add second button with mic icon for voice calls
2. **CallManager.swift**: forward `callMode`
3. **CallScreen.swift**: when `callMode == .voice`:
   - Default: `VoiceCallParticipantList` SwiftUI view — `ScrollView`/`List` of participant rows with circle + CID + mic status
   - Participants sharing video: row expands to show inline `RTCMTLVideoView`
   - Header: "Voice Call · N people"
   - Controls: mute + end call + "Share Camera" button; hide flip, screen share
   - When local user shares camera: show local camera preview above controls

---

## Phase 8: Documentation & Protocol

### Files to update
- `docs/serenada_protocol_v1.md` — document `mode` field in join payload, joined response, room_state, room_statuses
- `.env.example` — add `MAX_VOICE_ROOM_PARTICIPANTS`
- `CLAUDE.md` — mention voice mode in architecture overview

---

## Verification

1. **Server tests**: `cd server && go test ./...` — new voice mode tests pass
2. **Web tests**: `cd client && npm run test` — SDK tests pass
3. **Web build**: `cd client && npm run build` — no type errors
4. **Integration test**: `bash tools/integration-test/run.sh` — signaling integration passes
5. **Manual web test**: start voice call from home page, verify:
   - No camera permission requested on join
   - List UI shown (not video grid)
   - Second participant sees voice mode
   - Up to 8 participants can join
   - "Share Camera" button works: requests camera permission on demand, shows inline video tile
   - Other participants see the video tile but are not prompted for camera
   - Stopping camera returns to audio-only list row
6. **Android/iOS**: build and verify voice call button, voice UI, no camera on join, optional camera sharing works
7. **Resilience parity**: `node scripts/check-resilience-constants.mjs`
8. **Version parity**: `node scripts/check-version-parity.mjs`
