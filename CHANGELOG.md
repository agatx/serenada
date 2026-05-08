# Changelog

All notable changes to the Serenada SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.6.11] — 2026-05-08

First version published to public registries, plus Android and web
catch-up on the audio-only-join story that started in 0.6.9 (iOS) and
0.6.10 (React UI).

### Added
- Android: audio-only join. A session created with `defaultVideoEnabled
  = false` (or with the user toggling video off pre-join) now requests
  only `MICROPHONE` and starts the call without acquiring the camera.
  Mirrors `SerenadaConfig.allowAudioOnlyJoin` behavior on iOS.
- Android: in-call video toggle requests `CAMERA` on demand. Users who
  joined audio-only — or who denied camera at join — can flip video on
  later in the same session; the SDK fires the standard
  `onPermissionsRequired` delegate, and a denied grant leaves the call
  intact instead of canceling it. Mirrors the React UI behavior added
  in 0.6.10.

### Changed
- Web: npm packages renamed `@serenada/core` → `@agatx/serenada-core`
  and `@serenada/react-ui` → `@agatx/serenada-react-ui`. Consumers
  upgrading must update their import paths and `package.json`
  dependencies. The new names match the GitHub org and remove the
  unscoped-namespace conflict that previously blocked publishing.
- Web: packages now publish to public npm (`registry.npmjs.org`) on
  every `web-release-*` git tag, via the new `Publish web packages`
  GitHub Actions workflow. GitHub Packages remains available as a
  mirror for internal consumers. Build pipeline runs `npm run clean`
  before `tsc -p tsconfig.build.json` so stale `dist/` artifacts can't
  sneak into a release.

### Fixed
- Web: stopping a screen share that started from an audio-only session
  no longer triggers a surprise `getUserMedia` camera prompt. The
  engine now records whether a real camera track was live at the
  moment the share started; if there wasn't one, `stopScreenShare`
  swaps the display track out for null instead of acquiring a camera
  the user never asked for.
- Web: starting or stopping a screen share now broadcasts
  `participant_media_state` to peers and rebuilds the local state
  snapshot, so the remote tile and local UI flip immediately when an
  audio-only host begins sharing instead of waiting for the next
  unrelated media event.

## [0.6.10] — 2026-05-05

### Added
- React UI: `SerenadaCallFlowConfig.requestPermissions` lets host apps supply
  their own permission UI (custom modals, OS-style prompts, deep links into
  Settings). When unset, the SDK falls back to the built-in
  `SerenadaPermissions.request` flow.
- React UI: the in-call video toggle now requests camera permission on demand.
  Users who joined audio-only (or denied camera at join) can re-enable video
  later in the call without leaving and rejoining.
- Web: the SDK reserves a video transceiver on audio-only calls so desktop
  peers can renegotiate video later in the same session without forcing a
  full ICE restart.

### Changed
- Web: call-flow avatar sizes shrunk — the large-view avatar is reduced by
  20% and the compact (PiP) avatar is now exactly half the size of the large
  one. Initials and font sizes scale with the circle so they stay centered.
- Web: in 1:1 calls the remote camera-off overlay now switches to compact
  styling whenever it lives in the PiP slot (after a local-large swap), and
  the camera-off label scales down with the avatar instead of dominating the
  small tile.

### Fixed
- Android, iOS: ICE candidate handling hardened. Inbound candidates with
  blank SDP are dropped at the sanitizer boundary, missing `sdpMid` is
  preserved as null (so WebRTC falls back to `sdpMLineIndex` natively
  instead of mismatching named mids like `audio`/`video`), and the same
  sanitizer runs on the buffered-candidate path. Direct unit tests cover
  the sanitizer on both platforms.
- Web: the 1:1 waiting overlay (QR + share-link) and the camera-off avatar
  no longer stack on top of each other when a remote participant is in the
  room with `videoEnabled=false` but their media stream hasn't arrived yet.
  The avatar is suppressed while the waiting overlay is showing and takes
  over once the stream arrives.

## [0.6.9] — 2026-05-02

### Added
- iOS: `SerenadaConfig.allowAudioOnlyJoin` (default `true`) lets the SDK join
  an audio-only call when the user has denied or not yet granted camera
  permission. The session proceeds without a local video track and the local
  preview falls back to the audio-only placeholder.

### Changed
- iOS: world / composite camera modes now render the local preview as the
  primary surface (matching the existing 1:1 video layout) so the world view
  is the user's main visual context, and pinch-to-zoom is reachable in both
  the Waiting and InCall phases.

### Fixed
- iOS: avatar cache now invalidates correctly when a remote participant's
  avatar URL changes mid-call, so stale images no longer linger after a peer
  switches accounts in the same room.

## [0.6.8] — 2026-05-02

### Fixed
- Android: pinch-to-zoom on the local camera in 1:1 calls (world / composite
  modes) was being absorbed by the participant-name/mute badge overlay added
  with the call-ergonomics work. The badge sat in a fullscreen sibling Box
  on top of the local video, and its `clickable` modifier intercepted pinch
  gestures before they reached the `Modifier.transformable` on the
  local-large Box. The badge now renders as a child of the same Box that
  holds the video, mirroring the multi-party tile pattern, so pinch
  gestures reach the zoom controller. Multi-party was unaffected.

## [0.6.7] — 2026-05-01

Audio activity indicator now animates while the user is alone in the room
(Android and iOS), not just after a peer joins. Sensitivity matches the
in-call behavior because the same WebRTC `media-source.audioLevel` stat
drives both phases.

### Added
- Android, iOS: `LocalAudioPipelinePrimer` — a self-loopback peer-connection
  pair that holds the local audio track so WebRTC's audio capture, AEC/NS/AGC,
  and `media-source` stat stay active continuously while the user is in a
  room. The receiver PC's incoming audio is silenced (volume 0 + disabled)
  so the loopback doesn't echo through the speaker.
- `SessionMediaEngine.collectLocalAudioLevel(onComplete)` (Android + iOS) —
  async fetch of the primer's `media-source.audioLevel` stat for the
  audio-level poller.

### Changed
- Android, iOS: `AudioLevelPoller` now sources the local mic level from the
  primer's `media-source` stat (via `collectLocalLevel`) and only consumes
  inbound levels from peer slots. This unifies the local-level path across
  Waiting and InCall — the indicator no longer freezes while alone, and
  resumes immediately after a peer leaves and we're back to alone.
- Android, iOS: `AudioLevelPoller`'s `isActivePhase` widened from `InCall`
  only to `InCall || Waiting` so the poller runs during both phases.

### Fixed
- iOS: audio activity indicator stayed frozen at zero after a peer left the
  call and the user returned to the Waiting phase.

## [0.6.6] — 2026-04-30

Resilience hardening release. The SDK now degrades gracefully across long
signaling drops, post-reconnect peer-set churn, and process death; suspended
peers are surfaced explicitly to the UI with timing details, and active peers
report media-liveness so the server can defer hard-eviction while media is
still flowing locally. See `docs/resilience-failure-modes.md` for the full
audit and per-failure-mode design notes.

### Added
- Web, Android, iOS: `CallState.signalingState` (richer transport state with
  `connected | reconnecting{attempt, nextRetryAtMs} | suspended{suspendedSinceMs, estimatedHardEvictionAtMs} | failed{reason}`).
  Mid-call transport drops produce `suspended` with a hard-eviction estimate
  computed from the new shared `suspendHardEvictionTimeoutMs` constant
  (mirrors the Go server's `suspendHardEvictionTimeout`). `connectionStatus`
  remains the simpler tri-value summary for apps that don't need the detail.
- Web, Android, iOS: `Participant.presumedLost: boolean` flag on remote
  participants. Flipped to `true` after a peer has been
  `signalingStatus="suspended"` for `peerSuspendedUiTimeoutMs = 30 s` so call
  UIs can move them out of the active grid. The peer connection itself stays
  open so media can resume immediately if the peer reattaches; the flag
  clears when the peer transitions back to active or leaves the room.
- Web, Android, iOS: periodic `media_liveness{cids}` broadcast every
  `mediaLivenessIntervalMs = 10 s` for remote CIDs whose inbound RTP
  `bytesReceived` advanced. Server uses these hints to defer hard-eviction
  of suspended peers whose media is still being received locally.
- Web, Android, iOS: `getRecoverableSession()` / `discardRecoverableSession()`
  on `SerenadaCore` for app-relaunch recovery. Each SDK persists
  `{roomId, cid, reconnectToken, lastEpoch, sessionStartTs, expiresAtMs}` —
  Web uses `sessionStorage` (per-tab, survives reload), Android uses
  app-private `SharedPreferences`, iOS uses an injectable `UserDefaults`
  (defaults to `.standard`; host apps can pass an app-group store).
- Server: new `POST /api/leave` endpoint for explicit terminal leave (skips
  the suspension hold). Validates the reconnect token, idempotent,
  rate-limited at 12 req/min/IP. Used by the recovery flow on relaunch and
  by deliberate teardown paths.
- Server: dirty-pair tracking. When a relay targets a suspended CID,
  `relay_failed{reason: "target_suspended", targets, of}` is returned to the
  sender and the pair is marked dirty. On the suspended target's reattach,
  `negotiation_dirty{with}` notifies active peers to schedule glare-safe
  ICE restart for that pair only (each SDK maps the wire `with` field into
  an internal `withCid` event property). SDKs consume both messages.
- Server: room-state epoch + post-reconnect snapshot. Every successful
  `joined` is followed by an authoritative `room_state` snapshot on the new
  transport regardless of whether membership changed, so SDKs can gate ICE
  restart on a server-confirmed peer set.
- Server: room tombstones (`ROOM_ENDED`) so peers reconnecting to an ended
  room get a structured terminal error instead of a fresh-join attempt.
  Reconnect tokens are now HMAC-bound to `(cid, rid, expiresAt)` and expire
  with `suspendHardEvictionTimeout`; replay is rejected with
  `INVALID_RECONNECT_TOKEN` (mapped to a new `sessionExpired` SDK error).
- iOS, Android: foreground / Doze release force-ping. When the app returns
  from background, the SDK issues a synthetic ping with a 2 s deadline
  (`foregroundForcePingTimeoutMs`); on miss it force-closes the transport
  and runs the normal reconnect path. Fixes the "still shows connected for
  up to `pingIntervalMs` after suspension" gap on iOS background suspension
  and Android Doze.

### Changed
- iOS: the WebRTC-mirror `SignalingState` enum (in `CallDiagnostics`) is now
  `RtcSignalingState` so the new Phase 2 surface can take the unqualified
  name (matches Web/Android naming). `CallDiagnostics.rtcSignalingState`
  keeps its name.

## [0.6.2] — 2026-04-28

### Added
- Web, Android, iOS: host-supplied avatars in the call UI. Hosts can pass an opaque `peerId` on `join()` (alongside `displayName`) and supply an `AvatarProvider` (`avatarProvider` config on the call flow). The remote video-off placeholder renders a circle avatar above the name; null/error falls back to initials. Resolution is lazy and cached for the call's lifetime.
- Server forwards a new `peerId` field in `joined` / `room_state` participant entries (trimmed, max 128 chars). Wire-compatible — older clients ignore the new field.

### Fixed
- Initials derivation skips non-alphanumeric characters per word, so display names like `{Admin}` or `(CEO) John` produce sensible initials instead of punctuation.

## [0.6.1] — 2026-04-27

### Fixed
- Android: in-call layout now uses `localCameraMode` (instead of `isFrontCamera`) to decide which video is large vs PIP, so configs that start in `WORLD` or `COMPOSITE` mode correctly show the local camera as the main surface from the first frame.
- Web, Android, iOS: when the local camera is off, the remote video is always the main surface (the user's swap preference is preserved and reapplied when video resumes) — no more giant "Camera off" placeholder when the call starts in `WORLD`/`COMPOSITE` with video disabled.
- Web, Android, iOS: hide the participant name from the bottom-left pill when the remote video-off placeholder is already showing the name; the mic-muted icon still appears when applicable.
- Android: `inviteControlsEnabled = false` now also hides the QR code and Share button in the waiting overlay (previously only the "Invite to room" button was gated), so invite controls no longer flash during phase transitions.

## [0.6.0] — 2026-04-27

### Changed
- Version bump across all SDK platforms (web `@serenada/core` + `@serenada/react-ui`, Android `core` + `call-ui` + `libwebrtc`, iOS `SerenadaCore`) and integration docs.

## [0.5.0] — 2026-04-24

### Added
- `SerenadaConfig.cameraModes` — restrict which camera modes (selfie / world / composite) are offered and in what order across web, iOS, and Android. Supports initial-mode selection, single-mode lock-in (flip control hidden), and an empty-list audio-only mode (video toggle and camera permission suppressed). Unsupported modes are dropped silently per platform.
- `LocalParticipant.availableCameraModes` on the observable `CallState` — the resolved (platform-filtered) mode list for UI consumers to gate controls.
- `SerenadaCallFlowConfig.autoHideControls` — opt out of the idle-time auto-hide of the in-call controls bar. Defaults to `true` (existing behavior); setting to `false` keeps controls visible for the duration of the call on all three platforms.

### Changed
- The flip-camera control now also hides when the local video is turned off (previously it stayed visible and reacquired the camera invisibly on tap).

### Fixed
- `scripts/check-version-parity.mjs` now matches `sdkVersion` in `serenada-core/build.gradle.kts` instead of the first `version = "..."` line it finds (which previously matched an unrelated dependency version).
- `client-ios/scripts/deploy_to_device.sh` resilient to `devicectl` bullet-character changes when translating CoreDevice UDID → xcodebuild UDID.

## [0.3.0] — 2026-03-28

### Added
- Pluggable signaling provider support across web, Android, and iOS SDKs
- Built-in server-provider adapters on all SDK platforms to preserve the existing hosted signaling flow
- Cross-platform provider-mode regression coverage for diagnostics, room watching, ICE refresh, and negotiation fallback recovery

### Changed
- SDK config now requires exactly one of built-in `serverHost` or custom `signalingProvider`
- Offer ownership now uses lexicographic peer ID ordering on web, Android, and iOS
- Web peer-message integration now uses `onPeerMessage()` instead of exposing `subscribeToMessages()`
- Provider mode now gates server-only APIs and server-bound diagnostics with clear `requires serverHost` failures
- iOS `SerenadaDiagnostics.runConnectivityChecks()` is now `async throws`, which is a source-compatible break for existing call sites

## [0.2.0] — 2026-03-23

### Added
- GitHub Actions workflow to generate and publish SDK API docs to GitHub Pages
- TSDoc, KDoc, and Swift doc comments on all public API classes, methods, and model fields
- Landing page at https://agatx.github.io/serenada/ linking all platform docs

### Changed
- Reduced public SDK surface area across all platforms:
  - Web: internal exports marked with `@internal` (105 → 54 doc pages)
  - Android: 31 implementation types changed to `internal` visibility
  - iOS: 12 implementation types changed to `internal` access
- Moved reference docs (`serenada_protocol_v1.md`, `push-notifications.md`, `serenada_prd.md`, `wifi-lock-audio-delay-postmortem.md`) from repo root to `docs/`

### Fixed
- Removed `server/server_test` binary from git tracking
- Added `samples/web/.gitignore` for `node_modules/` and `dist/`

## [0.1.0] — 2026-03-22

### Added
- Headless SDK + optional UI architecture across Web, Android, and iOS
- `SerenadaCore` entry point with `join(url)`, `join(roomId)`, and `createRoom()` on all platforms
- `SerenadaSession` state machine with observable `CallState` (phase, participants, connection status)
- Dual-transport signaling (WebSocket primary, SSE fallback) with automatic failover
- WebRTC peer connection management with ICE restart, TURN refresh, and exponential backoff
- Pre-built call UI components (`SerenadaCallFlow`) on all platforms
- Cross-platform resilience constants with automated parity verification
- Typed `CallError` enum with 7 canonical error codes (all platforms)
- Typed `PeerConnectionState` enum replacing raw strings (all platforms)
- Camera mode system (selfie, world, composite, screen share)
- Screen sharing support on all platforms
- Push notification infrastructure (host app integration)
- Room occupancy monitoring via `RoomWatcher`
- Diagnostics and connectivity probes via `SerenadaDiagnostics`
- Sample apps for Web, Android, and iOS

### Improved (post-initial release)
- Typed `CallError` sealed class on Android replacing `errorMessage: String?` — 7 canonical error codes matching iOS
- Typed `CallErrorCode` union on web (`signalingTimeout`, `connectionFailed`, `roomFull`, `roomEnded`, `permissionDenied`, `serverError`, `webrtcUnavailable`, `unknown`)
- Typed `PeerConnectionState` / `SerenadaPeerConnectionState` enums replacing raw `String` on all platforms
- Typed signaling message payloads on web (7 parse functions replacing 15 unsafe casts)
- Typed signaling payloads on Android and iOS (structured data classes/structs)
- Extracted `SignalingMessageRouter` and `JoinFlowCoordinator` from SerenadaSession on iOS (1180→786 lines) and Android (1052→854 lines)
- Moved `@serenada/core` from dependency to peer dependency in `@serenada/react-ui`
- `SerenadaCore.isSupported()` static method for WebRTC capability detection
- CSS isolation via `[data-serenada-callflow]` attribute selector with `!important` on root layout
- Optional `className` prop on `SerenadaCallFlow` for host-app style overrides
- Integration test harness with 7 signaling protocol scenarios
- Version parity verification script (`scripts/check-version-parity.mjs`)
- `VERSIONING.md` semantic versioning policy and `CHANGELOG.md`
