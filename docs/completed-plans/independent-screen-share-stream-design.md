# Independent Screen Share Stream Design

Status: Draft
Last updated: 2026-06-20

## Summary

Screen sharing currently reuses the camera video path. The SDK labels the local
participant as `screenShare` through `content_state`, but the actual WebRTC
media is still carried by the single video track/transceiver that otherwise
represents camera video.

This design makes screen sharing a first-class content video stream:

- audio remains one audio stream
- camera remains one camera video stream
- screen share becomes an independent content video stream

The change is additive and capability-gated. Two new SDKs can negotiate camera
and screen share independently. A new SDK talking to an old SDK falls back to
the current single-video replacement behavior **per peer**. Strict audio-only
mode continues to negotiate no video at all.

Four architectural decisions anchor the design and are explained inline below:

- **Per-peer routing, not room-wide fallback.** The design already has per-peer
  connection slots and a per-peer capability flag, so each peer is routed
  independently. One legacy peer no longer forces every capable peer back to the
  single-video model. See [Mixed Mesh Rooms](#mixed-mesh-rooms).
- **Pre-negotiated content m-line, no per-share renegotiation.** For capable
  peers the content transceiver is created send-capable up front when video is
  allowed. Screen-share start/stop is then `replaceTrack()` plus `content_state`,
  not an SDP renegotiation on every toggle. See [Direction Rules](#direction-rules).
- **Video policy is signaled, capability is static.** `independentContentVideo`
  is a static build capability; whether a participant accepts any video is a
  separate, signaled per-session policy (`mediaPolicy.videoMediaEnabled`) so that
  offerers never create video m-lines toward an audio-only peer. See
  [Capability vs Session Policy](#capability-vs-session-policy).
- **Global participant state stays truthful; per-peer wire reality is a routing
  detail.** Public state (`cameraEnabled`, `content.active`) reflects user
  intent globally and is never bent to match the least-capable peer. The fact
  that a legacy peer can only carry one video at a time is handled at that peer's
  slot, not by lying in global state. See
  [Participant State in Mixed Rooms](#participant-state-in-mixed-rooms). This
  relies on a load-bearing legacy-receiver assumption that must be verified
  before rollout, with a defined contingency shim — see
  [Legacy Receiver Dependency](#legacy-receiver-dependency).

## Problem

The current one-video-slot design was useful for initial implementation, but it
couples unrelated concepts:

- camera availability and screen-share availability
- camera preference and content video enablement
- camera renderer and content renderer
- camera lifecycle and ReplayKit / MediaProjection / `getDisplayMedia`
- local camera state and remote content presentation

This coupling already showed up in the PSTN work. `cameraModes: []` should mean
"no local camera capture", but screen share still needs to send video when
video media is otherwise enabled. Because screen share uses the camera track,
the implementation had to make special-case preference fixes to avoid disabling
the screen-share track.

The current design also prevents simultaneous camera plus screen share. Starting
screen share replaces the camera track; stopping screen share has to restore or
drop the camera track.

## Current State

### Web

`MediaEngine.startScreenShare()` calls `getDisplayMedia()`, extracts the display
track, and calls `swapLocalVideoTrack(displayTrack, previousVideoTrack)`. The
screen-share track replaces the camera track in the existing video sender.

The session derives local `cameraMode` as `screenShare` while
`media.isScreenSharing` is true.

### Android

`WebRtcEngine.startScreenShare()` ensures the existing local video track exists,
starts `ScreenShareController`, enables that local video track, and reattaches
the same local video track to peer slots. The screen-share capturer feeds the
same local video source/track used by camera mode switching.

`PeerConnectionSlot.ensureReceiveTransceivers()` creates at most one video
receive transceiver.

### iOS

`ScreenShareController.startScreenShare()` creates a `BroadcastFrameReader`
using the existing local video source provider. Broadcast frames are delivered
to the same local video source/track used by camera capture. The session marks
the local participant's camera mode as `.screenShare`.

`PeerConnectionSlot.ensureReceiveTransceivers()` creates at most one video
receive transceiver.

### Signaling

`content_state` is a lightweight peer message and participant-room metadata. It
communicates that a participant is sharing content, but it does not identify a
WebRTC m-line, transceiver, stream ID, or track ID. Today that is acceptable
because there is only one video slot. It is not sufficient by itself once camera
and content are separate video streams.

**Load-bearing legacy-receiver assumption (must verify).** This design assumes
that current/old SDKs present a participant's incoming single video track as
content based on `content_state.active=true`, rather than on a global
`cameraMode=screenShare` or `videoEnabled=true` flag. The local sender code above
*derives* `cameraMode=screenShare`, but what matters for this design is the
*receive* path of old clients, which is not yet confirmed here. If the assumption
holds, a new SDK need not emit global legacy fields while sharing. Because the
whole mixed-room state model depends on it, the assumption is treated as a
blocking precondition with a verification gate and a contingency shim — see
[Legacy Receiver Dependency](#legacy-receiver-dependency).

## Goals

1. Preserve the three desired call modes:
   - PSTN mode: no video, no screen share, strict audio-only.
   - Video-disabled or no-camera P2P mode: no local camera video, but screen
     sharing can be sent if enabled; remote camera and remote screen share can
     be received.
   - Video-enabled P2P mode: camera and screen share can be sent independently;
     remote camera and remote screen share can be received independently.
2. Allow simultaneous camera and screen share between SDKs that support the new
   model, on a per-peer basis.
3. Keep screen share independent from camera preference, camera permissions,
   camera capturer restarts, and camera mode switching.
4. Preserve compatibility with older SDKs through a per-peer fallback path.
5. Keep the server relay model simple. The server may store and forward
   capabilities/content metadata, but it should not understand SDP or WebRTC
   track internals.
6. Keep SDK packages headless. UI packages consume new state and renderers, but
   core packages do not depend on UI frameworks.

## Non-Goals

1. Sending system audio with screen share.
2. More than one simultaneous content stream **per participant**. (Multiple
   different participants sharing at once is supported; see
   [Multiple Sharers](#multiple-sharers).)
3. Full SFU-style media routing. This remains mesh WebRTC.
4. Changing offer ownership, ICE restart, reconnect token, or media liveness
   semantics except where a new local track requires renegotiation.
5. Removing the legacy one-video-slot fallback in the first release.

## Proposed Model

Introduce explicit media roles:

```text
audio   - microphone / call audio
camera  - participant camera video
content - screen share video
```

Each peer connection can have these transceivers:

```text
audio transceiver       always present when audio is enabled
camera video transceiver present when both peers' videoMediaEnabled=true
content video transceiver present when both peers' videoMediaEnabled=true and
                         both peers support independent content video
```

The important distinction is that `videoMediaEnabled` controls whether any video
media is allowed, while `cameraModes` controls only local camera capture.

### Capability vs Session Policy

There are two independent axes plus a signaling requirement that connects them:

- **`independentContentVideo` (static capability)** means: this build's media
  engine *and its consuming UI/API surface* can negotiate, send, receive,
  classify, expose, and render a separate content video stream. It is a static
  property of the deployed client (see [Capability Gate](#capability-gate)) and
  never changes within a session.
- **`videoMediaEnabled` (session policy)** comes from `SerenadaConfig` and is
  **immutable for the lifetime of the session**. It controls whether *any* video
  m-line (camera or content) is negotiated at all for this participant.

A remote peer cannot honor "PSTN negotiates no video" unless it knows this
participant's policy. A capable, video-enabled peer that owns the offer would
otherwise create camera/content m-lines toward an audio-only participant.

The fix is to **signal video policy**, not to overload the static capability.
Each participant advertises `mediaPolicy.videoMediaEnabled` (see
[Signaling Changes](#signaling-changes)). The combined contract:

- A camera m-line is created toward a peer only when **both** participants'
  `videoMediaEnabled=true`.
- A content m-line is additionally created only when **both** advertise
  `independentContentVideo=true`.
- Defense in depth: regardless of signaling, a participant with
  `videoMediaEnabled=false` answers any offered video m-line `inactive` (and
  attaches no sender), so a stale or misbehaving offerer cannot force video onto
  an audio-only participant.

Because `videoMediaEnabled` is immutable and signaled at `join`, there is no race
between "feature supported", "session allows it", and "remote knows it".

**Audio-only compatibility boundary.** Strict audio-only / PSTN mode is a feature
introduced alongside this work and the recent audio-only call support; only SDKs
new enough to support strict audio-only emit `mediaPolicy`. Any client old enough
to omit `mediaPolicy` predates strict audio-only and has always negotiated video.
Therefore defaulting a missing `mediaPolicy.videoMediaEnabled` to `true` is safe:
there is no deployed old audio-only client that the default would mishandle. If
that assumption ever changes (an older audio-only client appears), the default
cannot remain `true` without an additional legacy signal, so the assumption is
called out explicitly here and in [Backward Compatibility](#backward-compatibility).

### Mode Matrix

| Mode | `videoMediaEnabled` | `cameraModes` | Camera send | Content send | Camera receive | Content receive |
| --- | --- | --- | --- | --- | --- | --- |
| PSTN | `false` | any | no | no | no | no |
| No camera P2P | `true` | `[]` | no | yes | yes | yes |
| Camera P2P | `true` | non-empty | yes | yes | yes | yes |

For strict audio-only, the SDK must not add camera or content transceivers and
must answer any video m-line offered by a remote peer `inactive`.

For no-camera P2P, the SDK still creates/answers video transceivers for receive
and content, but never requests camera permission or starts a camera capturer.

## Capability Gate

Add an SDK capability and a session media policy to `join`:

```json
{
  "capabilities": {
    "trickleIce": true,
    "maxParticipants": 4,
    "independentContentVideo": true
  },
  "mediaPolicy": {
    "videoMediaEnabled": true
  }
}
```

**A client must advertise `independentContentVideo=true` only when its entire
local stack — media engine negotiation, send/receive, track classification,
public state, renderer APIs, *and* the bundled/host UI path — can consume content
video correctly.** Advertising the capability is a promise that remote content
will actually render, not just negotiate. Until that is true on a given platform
and app, the client parses remote capabilities but continues to advertise
`false`.

To make this controllable during rollout, gate advertisement behind a config
flag (`enableIndependentContentVideo`, default `false` until the platform's media
engine and UI/API surface ship). This decouples "the protocol/server understands
the field" from "this client turns it on", and lets a host app that has not yet
adopted content renderers stay on legacy behavior safely. A client with the flag
off must behave exactly like today, including the legacy `cameraMode=screenShare`
screen-share UI behavior.

Server behavior:

- Store participant `capabilities` and `mediaPolicy` from `join` using an
  **allowlist** of known keys (`capabilities`: `trickleIce`, `maxParticipants`,
  `independentContentVideo`; `mediaPolicy`: `videoMediaEnabled`). Unknown keys are
  dropped, not stored. This is the consistent rule for this release; opaque
  pass-through of arbitrary future keys is deferred.
- Forward stored values in `joined.payload.participants[]` and
  `room_state.payload.participants[]`.
- Treat a missing capability as absent (clients default it to `false`); treat a
  missing `mediaPolicy.videoMediaEnabled` as `true` (safe per the audio-only
  compatibility boundary above).

Client behavior:

- Treat missing `independentContentVideo` as `false`.
- Treat missing `mediaPolicy.videoMediaEnabled` as `true`.
- Create a camera m-line toward a peer only when both ends'
  `videoMediaEnabled=true`; add a content m-line only when both ends also
  advertise `independentContentVideo=true`.
- Use the legacy single-video replacement model for any video-enabled peer that
  does not advertise `independentContentVideo`.

This avoids sending a second video m-line to old SDKs that would interpret it as
ordinary camera video, and avoids sending any video m-line to an audio-only peer.

## Transceiver Role Contract

When both peers support independent content video, video m-lines have a fixed
role order:

```text
first video m-line  -> camera
second video m-line -> content
additional video m-lines -> answered inactive, never reassigned
```

### Who creates transceivers

m-line creation is tied to the **existing deterministic offer owner** (the same
perfect-negotiation ownership already used for the connection). This prevents
duplicate or mismatched m-lines under glare:

- The **offer owner** pre-creates the ordered transceivers (audio, camera, then
  content for capable + video-enabled peers) before generating its offer. The
  non-owner does **not** pre-create video transceivers.
- The **non-owner (answerer)** materializes its transceivers from
  `setRemoteDescription(offer)`, mapping the incoming video m-lines in order, and
  then attaches senders/answers per its own policy.
- Both sides therefore end up with exactly one camera and (if applicable) one
  content m-line, created once, in the same order.

Because the non-owner has no content sender until the first offer is applied,
local screen share started before that point uses the **pending local track**
mechanism (see [Starting Screen Share](#starting-screen-share)).

### Role binding invariants

These are correctness-critical and must hold across glare rollback, reconnect,
and re-offers:

1. **Bind once, by object/`mid`.** On the first applied description that
   introduces the video m-lines, map the first to camera and the second to
   content, then persist the binding **by transceiver object identity / `mid`**.
2. **Never recompute from media.** Role is never re-derived from active-track
   state, track enabled state, camera mode, source object identity, or
   `content_state`. Once a transceiver/`mid` is bound to a role it keeps that
   role for the connection's life.
3. **Glare/rollback safe.** If an offer is rolled back during glare, role
   bindings established by a previously applied description are preserved; only
   newly introduced (still-unbound) m-lines are mapped after the winning offer
   applies.
4. **Reconnect.** A fresh peer connection re-runs binding from scratch in m-line
   order. `room_state` content metadata (see below) restores presentation; it
   does not drive role binding.
5. **Extra m-lines.** Any video m-line beyond the second is answered `inactive`
   and is never promoted to a role.

Do not infer roles from `content_state`. `content_state` is presentation state,
not the media role binding.

## Signaling Changes

### `join.capabilities` and `join.mediaPolicy`

Add optional `capabilities.independentContentVideo: true` and
`mediaPolicy.videoMediaEnabled: boolean` to `join`. These are the only
server-visible protocol additions. The server stores and forwards both verbatim
(allowlisted); it does not interpret them for media routing.

### `content_state`

Keep `content_state` as the source of truth for presentation metadata, now with
a generation marker so quick stop/start and reconnect are unambiguous:

```json
{
  "active": true,
  "contentType": "screenShare",
  "revision": 7
}
```

`revision` is a per-participant monotonically increasing integer **scoped to the
sender's current session** — i.e. interpreted within the envelope's `(cid, sid)`.
It is opaque to the server (stored and relayed verbatim).

Receiver rules:

- A `content_state` with a **new `sid`** for a given `cid` always supersedes any
  prior state for that participant, regardless of `revision`. The receiver resets
  its tracked revision to the incoming value. This is what makes a rejoin that
  starts again at `revision: 1` correct: the `sid` is new, so the old
  `revision: 7` is discarded by identity, not by comparison.
- Within the same `(cid, sid)`, the receiver keeps only the highest `revision`
  and discards lower-or-equal ones (handles out-of-order delivery, e.g. a stale
  `active:false` arriving after a newer `active:true`). Every state change —
  including a start-time rollback — uses a strictly greater `revision` than the
  message it supersedes, so a rollback is never discarded as stale.

What `revision` does **not** do: it does not bind RTP media to a particular
share. RTP tracks carry no revision, and with a stable pre-negotiated content
m-line the receiver sees the same receiver track mute/unmute across successive
shares. `revision` therefore orders **presentation state only**. A true
media-to-share marker would require an explicit in-band signal and is not worth
it for v1; presentation ordering plus the mute/unmute transition on the content
receiver track is sufficient.

Server behavior:

- relay the peer message verbatim (including `revision`)
- persist latest participant `contentState` (including `revision` and the owning
  `sid`)
- include latest content state in `room_state` for reconnecting peers
- **lifecycle (reconnect-aware):** content state is scoped to the owning `sid`
  and aligned with the existing participant/session lifecycle:
  - clear it on an **explicit leave** or on **session expiry/removal**;
  - do **not** clear it on a merely recoverable transport disconnect inside the
    existing reconnect window — if the same `sid` reconnects, its content state
    (and `revision`) survive, so peers do not flicker the share off and on;
  - if reconnect establishes a **new `sid`**, the new-`sid` supersede rule
    applies and the old `sid`'s persisted state is removed when that old session
    expires;
  - a subsequent `active=false` for an already-cleared session is a no-op.

Client behavior:

- `content_state.active=true` means "show content UI when content media is
  flowing or expected".
- `content_state.active=false` means "hide content UI and clear remote content
  track state for that participant".
- A content receiver track that unmutes before the matching `content_state`
  arrives should be held (not promoted to active content) until state with
  `active=true` arrives. This avoids briefly showing stale content after a
  renegotiation or reconnect, and is also how new-SDK receivers close the
  signaling-vs-media ordering gap (see [Starting Screen Share](#starting-screen-share)).

### `participant_media_state`

Clarify semantics (see [Public SDK State](#public-sdk-state) for the matching
field contract):

- `audioEnabled` remains microphone state.
- `videoEnabled` mirrors `cameraEnabled` when the independent flag is on (it
  reports camera-active), and retains today's "video active via the legacy
  single-video sender" meaning only when the flag is off. The contingency shim in
  [Legacy Receiver Dependency](#legacy-receiver-dependency) is the one exception.
- Camera-specific state for new consumers is `cameraEnabled`.
- Content activity is represented by `content_state`, not `videoEnabled`.

## Public SDK State

Add explicit content state and an explicit camera-enabled field.

```ts
export interface Participant {
  cid: string;
  audioEnabled: boolean;
  cameraEnabled: boolean;          // camera video specifically (new, precise)
  videoEnabled: boolean;           // legacy "video active"; mirrors cameraEnabled when flag on
  content?: {
    active: boolean;
    type: 'screenShare' | (string & {});
    revision: number;
  };
}

export interface LocalParticipant {
  audioEnabled: boolean;
  cameraEnabled: boolean;          // camera video specifically
  videoEnabled: boolean;           // legacy "video active"; mirrors cameraEnabled when flag on
  cameraMode: CameraMode;          // selfie | world | composite
  availableCameraModes: ConfigurableCameraMode[];
  content?: {
    active: boolean;
    type: 'screenShare' | (string & {});
    revision: number;
  };
}
```

Field semantics, by config flag (this is the resolution to the mixed-room
state-coherence concern):

- **Flag off (`enableIndependentContentVideo=false`):** exactly today's behavior.
  Single video sender; while sharing, `cameraMode=screenShare` and
  `videoEnabled=true`. Provided for hosts that have not adopted content rendering.
  No new state is required to interpret such a client.
- **Flag on (independent mode):** global public state is always truthful and is
  **the same regardless of room composition**:
  - `cameraEnabled` reflects whether the camera is genuinely captured/intended,
  - `content.active` reflects whether the user is screen sharing,
  - `cameraMode` reflects the real camera mode and is **never** `screenShare`,
  - `videoEnabled` mirrors `cameraEnabled`.

  Crucially, this holds even when the same local user is sending camera+content
  to capable peers and content-only to a legacy peer. Global state describes user
  intent; the per-peer fact that a legacy connection can only carry one video is
  a routing detail (see [Mixed Mesh Rooms](#mixed-mesh-rooms)), not a reason to
  set `cameraMode=screenShare` globally and lie to capable peers. This depends on
  the legacy-receiver assumption; if verification forces the contingency shim,
  the legacy fields are additionally emitted but capable peers still ignore them.

`LocalCameraMode.screenShare` is retained in the enum only for source
compatibility and for the flag-off path; new SDKs in independent mode do not set
it.

Native SDKs expose equivalent `cameraEnabled` and `content` fields. New UI should
prefer `participant.content.active`.

Long-term target (a future **major** release, after the deprecation window):

- `LocalCameraMode.screenShare` is removed.
- `videoEnabled` is removed; `cameraEnabled` is the only camera field.
- screen share is rendered from `content.active` and content renderer/stream
  APIs.

The `videoEnabled` deprecation window is at least one **major** release after the
legacy fallback is removed, not the next minor release. Public SDK consumers get
a clear compatibility window.

## Public Rendering APIs

Keep existing camera renderer APIs and add content-specific APIs.

Web:

```ts
session.getRemoteStream(cid)              // legacy camera stream
session.getRemoteCameraStream(cid)        // new explicit camera stream
session.getRemoteContentStream(cid)       // new content stream
session.getLocalContentStream()           // optional for local preview
```

Android:

```kotlin
fun attachRemoteRenderer(renderer: VideoSink, participantCid: String)
fun detachRemoteRenderer(renderer: VideoSink, participantCid: String)

fun attachRemoteContentRenderer(renderer: VideoSink, participantCid: String)
fun detachRemoteContentRenderer(renderer: VideoSink, participantCid: String)

fun attachLocalContentRenderer(renderer: VideoSink)
fun detachLocalContentRenderer(renderer: VideoSink)
```

iOS:

```swift
public func attachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String)
public func detachRemoteRenderer(_ renderer: AnyObject, forParticipant cid: String)

public func attachRemoteContentRenderer(_ renderer: AnyObject, forParticipant cid: String)
public func detachRemoteContentRenderer(_ renderer: AnyObject, forParticipant cid: String)

public func attachLocalContentRenderer(_ renderer: AnyObject)
public func detachLocalContentRenderer(_ renderer: AnyObject)
```

The existing renderer APIs remain camera APIs. Legacy UI can continue to use
them. New UI can render camera and content simultaneously.

## Media Engine Design

### Shared Abstractions

Each SDK introduces a small internal role model:

```text
enum VideoRole { camera, content }

LocalVideoTracks
  cameraTrack: platform video track?
  contentTrack: platform video track?   // also the "pending" track for not-yet-bound peers

PeerMediaSlot
  audio sender/receiver
  camera sender/receiver/transceiver
  content sender/receiver/transceiver
  supportsIndependentContentVideo: bool   // per-peer
  peerVideoMediaEnabled: bool             // per-peer, from signaled mediaPolicy
```

Do not encode role by track enabled state, camera mode, source object identity,
or `content_state`. Role is explicit in SDK state and bound by transceiver/`mid`.

### Direction Rules

This design avoids renegotiating on every screen-share toggle. The previous draft
started the content transceiver `recvonly` and renegotiated on each start; that
pays an SDP round-trip and a glare window every time.

Instead, for capable + video-enabled peers the content m-line is
**pre-negotiated send-capable up front**:

```text
camera transceiver:
  created sendrecv when both peers' videoMediaEnabled=true
  local camera track attached when camera is live/enabled, detached (replaceTrack null) otherwise
  inactive when this peer is not video-enabled

content transceiver:
  created sendrecv when both peers' videoMediaEnabled=true and peer is independent-capable
  starts with NO sender track (sender present, track null -> sends nothing)
```

Creating a `sendrecv` transceiver with a null sender track **sends no RTP until a
track is attached**. It is not "free", though: it adds an SDP m-line, receiver
state, codec negotiation, and possibly an early remote receiver track. That cost
is paid once at connection setup (or on a structural change), not per share.
Screen-share start then becomes a plain `replaceTrack(displayTrack)` and stop a
`replaceTrack(null)` — **no SDP renegotiation in the steady state**.
Renegotiation is only required for structural changes (initial connection, ICE
restart, a peer transitioning capability/policy via reconnect), which go through
the existing perfect-negotiation ownership path.

The same applies to camera: attach/detach the camera track via `replaceTrack`
against the pre-negotiated camera transceiver rather than re-creating m-lines.

#### Content encoding profile

The content sender uses a screen-content-oriented profile rather than the camera
profile. Initial conservative default target (tunable per platform, refined in
the encoding open question): cap at roughly 1920×1080, ~5 fps, with a modest
bitrate ceiling, prioritizing legibility of mostly-static content over motion
smoothness. Setting these sender parameters on the pre-negotiated content
transceiver also makes a typical display track fit the negotiated envelope so
`replaceTrack` stays on the no-renegotiation path.

#### `replaceTrack()` feasibility and fallback

`replaceTrack()` is not guaranteed to succeed: a new track whose
codec/resolution envelope is incompatible with the negotiated parameters can be
rejected and require renegotiation, which is especially relevant for
high-resolution screen content.

- Apply the content encoding profile above so a typical display track fits.
- If `replaceTrack(contentTrack)` throws or is rejected, **fall back to a
  renegotiation** for that peer's content m-line (treat it as a structural
  change) rather than failing the share. This keeps the common path
  renegotiation-free while remaining correct for edge cases.
- Test large display tracks (e.g. 4K, high-DPI) on Web, Android, and iOS to
  confirm the steady-state path holds in practice.

### Starting Screen Share

Screen share uses a **single global start sequence regardless of room
composition** (this resolves the previous contradiction between an independent
"attach then signal" order and a legacy "signal then attach" order):

1. Acquire screen-share frames (`getDisplayMedia`, MediaProjection, or ReplayKit
   broadcast reader). Subscribe to the source's ended/interrupted event (see
   [Stopping from Outside the SDK](#stopping-from-outside-the-sdk)).
2. Create/reuse the local content video track/source and record it as the
   **pending local content track**.
3. Increment `revision`; set local `content.active=true`.
4. **Broadcast `content_state { active: true, contentType: "screenShare",
   revision }` once, participant-wide, before touching any sender.** Signaling
   and RTP travel on independent paths, so this reduces but cannot by itself
   *guarantee* that a receiver processes the state before media arrives. The
   design deliberately avoids an ACK/barrier. The ordering gap is closed
   deterministically only on the **receiving** side, and only for new SDKs: a
   new SDK holds/reclassifies any video on the content or legacy path until the
   matching `content_state` is known, so it never shows the screen as camera.
   Old-SDK behavior here is **best-effort** and is validated by interop tests
   rather than asserted — see [Legacy Receiver Dependency](#legacy-receiver-dependency).
5. Apply per peer:
   - **capable peer with a bound content transceiver:** `replaceTrack(contentTrack)`
     on the content sender (renegotiation fallback if rejected). Camera senders to
     this peer are untouched.
   - **capable peer whose content transceiver is not yet bound** (non-owner, slot
     still negotiating, or peer mid-join): leave the track pending. It is attached
     automatically when that peer's content transceiver binds.
   - **legacy peer:** replace the camera track with the display track for that
     peer's video sender only.
6. Roll back only if the share can never flow anywhere: if **zero** peers
   attached and **no** peer is pending, increment `revision` again and broadcast
   `content_state { active: false, revision }` (strictly greater than the
   `active:true` from step 4 so receivers order the rollback after the start),
   restore any legacy senders, release capture resources, and clear local
   `content.active`.

Camera tracks to capable peers are never touched by start/stop of screen share.

### Stopping Screen Share

The stop path is shared by the SDK API, rollback, and external-stop events, and
**must be idempotent**: a single in-progress/stopped latch ensures capture is
stopped once, senders are restored once, resources are released once, and
`revision` increments exactly once per logical stop. (Programmatic
`stopScreenShare()` stops the capture track, which itself fires
`onended` / MediaProjection `onStop` / ReplayKit termination and re-enters this
path; the latch makes the second entry a no-op.)

1. Stop screen-share capture and clear the pending local content track.
2. For capable peers: `replaceTrack(null)` on the content sender.
3. For legacy peers: restore/drop the camera track exactly as today.
4. Increment `revision`; broadcast `content_state { active: false, revision }`.
5. Clear local content presentation state; remote observers clear their view of
   this participant's content.
6. **Release capture resources explicitly.** Clearing track references is not
   enough — the OS capture session must be torn down or the platform's
   share-indicator persists: Web `MediaStreamTrack.stop()` on the display track;
   Android `MediaProjection.stop()` plus capturer dispose/release; iOS broadcast
   reader teardown. This applies equally on stop, rollback, and failure.

Camera track state is not changed. No renegotiation in the common case.

### Stopping from Outside the SDK

The user can stop sharing without calling the SDK: the browser's native "Stop
sharing" control, Android revoking/stopping MediaProjection, or iOS terminating
the ReplayKit broadcast. Each platform surfaces this as the content source track
ending or the broadcast being interrupted. The SDK **must** listen and run the
identical (idempotent) stop path:

- Web: `displayTrack.onended`.
- Android: `MediaProjection.Callback.onStop` (and capturer error callbacks).
- iOS: broadcast finished/interrupted notification from the broadcast extension.

On any of these, run [Stopping Screen Share](#stopping-screen-share): stop
capture, `replaceTrack(null)` on capable peers / restore camera on legacy peers,
bump `revision` once, broadcast `content_state { active: false }`, release
resources. Camera is left untouched. Without this, remote peers would keep
showing a frozen content tile and local `content.active` would be stuck true.

### Failure Semantics for `startScreenShare()`

Failure handling is **per peer**, not global. In a mesh room one bad peer
connection must not block sharing to healthy peers.

- **Permission/capture denied** (user cancels picker, OS denies broadcast):
  `startScreenShare()` returns false/throws a typed error, no `content_state` is
  sent, no transceiver state changes, camera is untouched, no capture resources
  remain held. This is the only whole-operation failure, because there is nothing
  to share.
- **No eligible video peer:** if local policy allows video but every remote peer
  is audio-only, or there are no peers yet, `startScreenShare()` still succeeds
  locally — capture starts, `content.active=true`, `content_state` is broadcast —
  but no media is sent until an eligible peer appears. The pending content track
  attaches when an eligible peer joins/negotiates. This "start and wait" behavior
  is consistent with the pending-track model. It is an **intentional,
  user-visible** product decision with a privacy implication: the screen is being
  captured even though no one can see it yet, so the UI **must** indicate
  "sharing, waiting for participants" rather than implying media is flowing.
- **Capture starts; attach fails on a subset of peers:** keep capture running.
  Mark the failed peers for the existing connection-recovery path and surface
  them in diagnostics. `content_state.active=true` stands as long as **at least
  one** peer is sharing or pending; do not tear down healthy peers.
- **Capture starts; attach fails on every peer and none is pending:** stop
  capture and release resources, do not leave `active:true` (roll back per step 6
  above), report failure.
- **`videoMediaEnabled=false`:** `startScreenShare()` is a no-op returning false.

## Web Implementation Plan

### Data Structures

Refactor `MediaEngine` from one local stream into role-specific tracks:

```ts
private localAudioTrack: MediaStreamTrack | null;
private localCameraTrack: MediaStreamTrack | null;
private localContentTrack: MediaStreamTrack | null;   // also the pending track
```

```ts
private remoteCameraStreams = new Map<string, MediaStream>();
private remoteContentStreams = new Map<string, MediaStream>();
```

The existing `localStream` and `remoteStreams` can remain as compatibility
accessors during migration. `localStream` should contain audio plus camera, not
content. New content accessors expose content separately.

Add per-peer role storage:

```ts
interface PeerState {
  pc: RTCPeerConnection;
  mediaRoles: {
    audio?: RTCRtpTransceiver;
    camera?: RTCRtpTransceiver;
    content?: RTCRtpTransceiver;
  };
  supportsIndependentContentVideo: boolean;
  peerVideoMediaEnabled: boolean;
}
```

### Transceiver Setup

Replace `findTransceiver(pc, 'video')` with role-specific helpers, created only by
the offer owner:

```ts
ensureCameraTransceiver(peer): RTCRtpTransceiver | null   // offer owner, sendrecv up front
ensureContentTransceiver(peer): RTCRtpTransceiver | null  // offer owner, sendrecv up front, capable peers
findTransceiverByRole(peer, role): RTCRtpTransceiver | undefined
assignRemoteVideoRoles(peer): void                        // answerer maps once by mid, in order
attachPendingLocalTracks(peer): void                      // attach pending content/camera once bound
```

For capable + video-enabled peers, the offer owner at connection setup:

- adds camera video transceiver first (sendrecv, no track yet unless camera live)
- adds content video transceiver second (sendrecv, no track)

The answerer creates nothing pre-emptively; it maps the m-lines from the offer
and then calls `attachPendingLocalTracks` so a share started before the offer
arrived gets attached. For legacy peers, only the existing single video
transceiver is used. Add a test asserting no duplicate camera/content m-lines
under simulated glare.

### `ontrack`

Classify incoming tracks by **bound** transceiver role:

```ts
pc.ontrack = event => {
  const role = getRoleForTransceiver(peer, event.transceiver); // from persisted binding
  if (event.track.kind === 'video' && role === 'content') {
    storeRemoteContentTrack(remoteCid, event.track, event.streams[0]);
    return;
  }
  if (event.track.kind === 'video' && role === 'camera') {
    storeRemoteCameraTrack(remoteCid, event.track, event.streams[0]);
    return;
  }
  // audio path unchanged
};
```

For strict audio-only, keep answering offered video `inactive` before answer
creation as the PSTN work already does.

### Negotiation

The existing local-track negotiation path is reused but role-aware. With
pre-negotiated transceivers, steady-state share toggles use `replaceTrack` and do
not schedule renegotiation:

- attaching/detaching the content track is `replaceTrack` only (no negotiation),
  falling back to renegotiation if `replaceTrack` is rejected
- attaching/detaching the camera track is `replaceTrack` only (no negotiation)
- `hasUnnegotiatedLocalTracks()` returns true only for structural changes (new
  peer, capability/policy transition, `replaceTrack` fallback), not for ordinary
  track swaps
- `replaceVideoTrackOnAllPeers()` becomes `replaceTrackOnAllPeers(role, track)`

### Screen Share

`startScreenShare()` should no longer call `swapLocalVideoTrack()` on capable
peers, and follows the global sequence (signal before attach):

```ts
const displayStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
const displayTrack = displayStream.getVideoTracks()[0];
this.localContentTrack = displayTrack;              // also the pending track
displayTrack.onended = () => this.stopScreenShare(); // user stops via browser UI; stop path is idempotent
this.contentRevision += 1;
this.isScreenSharing = true;
// 1) signal first so capable + legacy receivers classify incoming video as content
this.sendSignalingMessage('content_state', {
  active: true, contentType: 'screenShare', revision: this.contentRevision,
});
// 2) then attach per peer: capable -> content sender (or pending); legacy -> swap legacy sender
await this.attachContentTrackPerPeer(displayTrack, displayStream);
```

`stopScreenShare()` replaces the content track with `null` on capable peers,
restores the camera track only on legacy peers, calls `displayTrack.stop()`,
clears the pending track, and bumps `revision` (guarded by the idempotency latch
so the `onended` re-entry is a no-op).

## Android Implementation Plan

### Track and Source Split

Refactor `WebRtcEngine` local video state:

```kotlin
private var localCameraVideoSource: VideoSource? = null
private var localCameraVideoTrack: VideoTrack? = null

private var localContentVideoSource: VideoSource? = null
private var localContentVideoTrack: VideoTrack? = null   // also the pending track
```

`CameraCaptureController` owns camera capture and writes to
`localCameraVideoSource`. `ScreenShareController` owns MediaProjection capture and
writes to `localContentVideoSource`, and reports MediaProjection stop via
`MediaProjection.Callback.onStop` into the shared (idempotent) stop path. On stop
it releases the capturer and calls `MediaProjection.stop()`.

### PeerConnectionSlot

Change slot attachment from one optional local video track to role-specific
tracks, with the per-peer capability and policy flags:

```kotlin
fun attachLocalTracks(
    audioTrack: AudioTrack?,
    cameraTrack: VideoTrack?,
    contentTrack: VideoTrack?,
    supportsIndependentContentVideo: Boolean,
    peerVideoMediaEnabled: Boolean,
)
```

For independent-capable, video-enabled peers (offer owner pre-creates):

- ensure camera video transceiver first (sender present up front)
- ensure content video transceiver second (sender present up front)
- attach/detach camera and content tracks via the senders (no renegotiation for
  steady-state swaps; renegotiate if `setTrack` is rejected)
- when a slot finishes negotiating, attach any pending local content track

For legacy peers:

- attach only one video track
- while screen sharing, attach content track through the legacy video sender
  (after `content_state` has been broadcast)
- otherwise attach camera track

### Remote Tracks

Store separate remote tracks:

```kotlin
private var remoteCameraVideoTrack: VideoTrack? = null
private var remoteContentVideoTrack: VideoTrack? = null
```

Existing `attachRemoteRenderer()` continues to target camera. Add content
renderer methods for `remoteContentVideoTrack`.

`onTrack` classifies video tracks by **persisted** transceiver role. With capable
peers, role comes from the once-bound video m-line order. With legacy peers, role
is camera unless `content_state.active=true`, in which case the legacy track is
presented as content for UI compatibility.

### Session State

`SerenadaSession` should:

- add `cameraEnabled`; keep `localVideoEnabled` mirroring it in independent mode
- add `localContentActive` and `localContentRevision`
- keep `diagnostics.isScreenSharing`
- broadcast `content_state` (with `revision`) on content start/stop, including
  stops triggered by MediaProjection teardown
- not call `applyLocalVideoPreference()` to enable content
- not set a global `cameraMode=screenShare` in independent mode (unless the
  verified contingency shim is enabled)

Screen share start should no longer set `userPreferredVideoEnabled=true`. That
was a workaround for the shared camera track. Content track enablement is
independent.

## iOS Implementation Plan

### Track and Source Split

Refactor `WebRtcEngine` local video state:

```swift
private var localCameraVideoSource: RTCVideoSource?
private var localCameraVideoTrack: RTCVideoTrack?

private var localContentVideoSource: RTCVideoSource?
private var localContentVideoTrack: RTCVideoTrack?   // also the pending track
```

`CameraCaptureController` uses the camera source. `ScreenShareController` creates
`BroadcastFrameReader(delegate: localContentVideoSource)` instead of using the
camera source provider, reports broadcast finished/interrupted into the shared
(idempotent) stop path, and tears the reader down on stop.

### PeerConnectionSlot

Add role-specific sender/transceiver management with the per-peer capability and
policy flags:

```swift
func attachLocalTracks(
    audioTrack: RTCAudioTrack?,
    cameraTrack: RTCVideoTrack?,
    contentTrack: RTCVideoTrack?,
    supportsIndependentContentVideo: Bool,
    peerVideoMediaEnabled: Bool
)
```

The offer owner creates camera and content video transceivers in fixed order for
capable + video-enabled peers, send-capable up front. When a slot finishes
negotiating, attach any pending local content track. Classify remote tracks by
persisted transceiver role in the peer connection delegate path.

### Renderer APIs

Existing remote renderer APIs remain camera APIs. Add content-specific renderer
registration for remote and local content tracks.

### Session State

`SerenadaSession.startScreenShare()` should:

- guard `config.videoMediaEnabled`
- call the screen-share controller
- mark content active and bump `revision` on completion
- broadcast `content_state`
- not mutate `userPreferredVideoEnabled`
- not set `localParticipant.cameraMode = .screenShare` in independent mode

`stopScreenShare()` clears content only (and runs on broadcast termination from
the extension, via the idempotent stop path). It should not restore camera
capture unless a peer is using the legacy fallback.

## Server Implementation Plan

The server does not inspect SDP. It preserves enough participant capability and
policy data for SDKs to decide whether to use the independent content
transceiver and whether to offer video at all.

Server changes:

1. Parse `join.payload.capabilities` and `join.payload.mediaPolicy` against an
   **allowlist** of known keys (including `independentContentVideo` and
   `videoMediaEnabled`). Drop unknown keys.
2. Store allowlisted capabilities and policy on the participant record.
3. Include participant `capabilities` and `mediaPolicy` in `joined` and
   `room_state`.
4. Preserve current `content_state` relay and participant metadata behavior, now
   storing/relaying the opaque `revision` field and the owning `sid` verbatim.
5. **Content-state lifecycle (reconnect-aware):** scope persisted content state to
   the owning `sid`. Clear it on **explicit leave** or **session expiry/removal**,
   aligned with the existing participant lifecycle. Do **not** clear it on a
   recoverable transport disconnect within the existing reconnect window — a
   `sid`-preserving reconnect keeps the content state so peers do not see the
   share flicker off; a reconnect that creates a new `sid` relies on the
   new-`sid` supersede rule, and the old `sid`'s state is removed when that
   session expires. (This is metadata bookkeeping, not media routing.)

No server-side media routing changes are required. (Opaque forwarding of
arbitrary future keys is intentionally deferred to keep this release's storage
rule consistent and auditable.)

## UI and Layout Changes

React UI, Android Compose UI, and iOS SwiftUI consume content state separately
from camera mode.

Rules:

- Camera tile renders camera video.
- Content tile renders screen-share video.
- If a participant has both camera and content, show both according to layout
  policy. Recommended default: content as primary, camera as a smaller tile.
- If `content_state.active=true` but content media has not arrived yet, show a
  loading/connecting content tile.
- If a content receiver track unmutes but `content_state.active` is false or
  unknown, keep it hidden until state is active (matched by `revision`). This is
  the receiver-side hold that closes the signaling-vs-media ordering gap for new
  SDKs.
- **Local "waiting for participants" state:** when the local user is sharing but
  no peer is yet receiving media (start-and-wait), the UI must show that capture
  is live and waiting, not that media is flowing.
- **Per-peer pending/failed content:** participant-wide `content_state.active=true`
  may be true while a specific peer has no content media yet (still negotiating,
  pending attach, or attach failed and recovering). Show that peer's content as a
  loading tile and surface the affected peer in diagnostics; do not leave a silent
  permanent empty tile. If recovery ultimately fails, diagnostics must make the
  per-peer failure visible.
- **Audio-only receivers suppress content UI.** A participant with
  `videoMediaEnabled=false` never negotiated content receive and has nothing to
  render, so it must not show content UI even when room state says another
  participant is sharing.
- For legacy peers, the single video track is presented as content based on
  `content_state.active=true`.

The shared layout algorithm gains a content role input rather than inferring
content from camera mode.

**UI readiness is part of capability gating.** A client must not advertise
`independentContentVideo=true` until its bundled UI (or, for host apps, the host
integration) attaches content renderers. Core negotiating content separately
while UI only attaches the legacy camera renderer would make remote screen share
disappear. The `enableIndependentContentVideo` config flag is the single switch
that should be flipped only after both core and UI are ready on that platform/app.

## Mixed Mesh Rooms

The room model supports mesh calls up to four participants, and capability can be
mixed. The design adopts **per-peer routing** rather than a room-wide fallback.

Rationale: the design already has per-peer connection slots and a per-peer
`supportsIndependentContentVideo` flag, so per-peer routing is the natural model
and avoids letting a single legacy peer degrade every capable peer. Room-wide
fallback ("if any peer is legacy, everyone uses replacement") was simpler to
describe but strictly worse for the common case and inconsistent with the slot
architecture.

Behavior:

- For each capable peer, the local client sends camera and content on independent
  m-lines (simultaneous camera + screen share works).
- For each legacy peer, the local client uses the single-video replacement model
  for that peer only. While the local user is sharing, the legacy peer receives
  **content** through the legacy video sender (screen share takes priority over
  camera to that peer, matching today's behavior); when not sharing, that peer
  receives camera.
- A capability/policy transition (legacy peer joins, or a peer reconnects with a
  different capability/policy) is handled at that peer's slot: its connection
  (re)negotiates with the appropriate m-line layout. Other peers' media is not
  disturbed. A peer that joins while the local user is already sharing picks up
  the share via the pending-track mechanism (capable) or the legacy swap (legacy).

### Participant State in Mixed Rooms

Per-peer media routing interacts with room-global participant state. The rule is:
**public state describes user intent and is identical regardless of room
composition; per-peer wire reality is a routing detail, not part of public
state.** Concretely, with the independent flag on:

| Scenario (camera on + sharing) | `cameraEnabled` | `cameraMode` | `content.active` | Wire to capable peer | Wire to legacy peer |
| --- | --- | --- | --- | --- | --- |
| All-capable room | `true` | real mode | `true` | camera m-line + content m-line | n/a |
| All-legacy peers | `true` | real mode | `true` | n/a | single sender carries content; camera preempted on that connection |
| Mixed room | `true` | real mode | `true` | camera + content | content only; camera preempted on that connection |

The key decision: `cameraMode` is **never** `screenShare` in independent-mode
public state, and `cameraEnabled`/`content.active` always tell the truth about
what the user is doing. A legacy peer that can only carry one video learns the
single track is content from `content_state` (which it already consumes, pending
the [Legacy Receiver Dependency](#legacy-receiver-dependency) verification), so no
global `cameraMode=screenShare` is needed and capable peers are never lied to.
Where a legacy peer's connection cannot carry the camera while content is active,
that limitation appears in **per-peer diagnostics**, not in global state.

This means per-peer fallback has essentially no global state-model cost: the only
place the legacy `cameraMode=screenShare` convention survives is the flag-off
build (today's behavior unchanged) and, if verification requires it, the
contingency shim — which capable peers ignore.

### Multiple Sharers

Because content is a per-participant stream, more than one participant may share
at once. The SDK supports this directly: each remote participant has its own
content track and `content_state`/`revision`. The non-goal of "one content stream
per participant" still holds (a single participant cannot open two screen
shares). UI layout policy decides how to present multiple simultaneous content
tiles; the recommended first-release default is to surface as primary the
participant whose `content_state.active=true` was **received most recently by this
client** (local receive order). There is no server-stamped ordering field, so the
default is an explicitly local UI heuristic, not a wall-clock or global ordering.
A shared primary-presenter ordering is out of scope for v1 unless product needs
it.

## Backward Compatibility

### Legacy Receiver Dependency

The mixed-room model assumes old SDKs render a participant's incoming single video
track as content from `content_state.active=true` alone — not from a global
`cameraMode=screenShare` or `videoEnabled=true` flag. This is load-bearing and is
**not assumed proven**. It must be confirmed against the actual receive code of
every supported old Web/Android/iOS client before flipping any platform's flag.

**Verification gate (blocks Phase 2+ per platform):**

- new flag-on SDK, camera on, sharing to each supported old client → old client
  renders content;
- new flag-on SDK, `cameraModes=[]` (camera off, `videoEnabled` mirrors camera =
  `false`), sharing to each supported old client → old client still renders
  content even though global `cameraMode` is a real mode and `videoEnabled` is
  false. This no-camera case is the sharpest test and the most likely to expose a
  hidden dependency.

**Contingency shim (only if a client fails the gate):** while sharing and at
least one legacy peer is present, the new SDK additionally emits the legacy
`participant_media_state.videoEnabled=true` and/or `cameraMode=screenShare`
**globally**. This is safe for capable peers because they classify content from
the content-transceiver role binding and read `cameraEnabled`/`content.active`;
they ignore the legacy `videoEnabled`/`cameraMode` fields. The only cost is that
the legacy global fields no longer perfectly describe camera state during a share
— which is exactly today's behavior, so old clients already tolerate it. The shim
is enabled per platform based on the verification result and is off by default.

### New SDK to New SDK

- Both advertise `independentContentVideo=true` and `videoMediaEnabled=true`.
- Negotiate camera and content video m-lines (content pre-negotiated
  send-capable).
- Camera and screen share can be active simultaneously.
- `content_state` (with `revision`, scoped to `sid`) drives content presentation.

### New SDK to Old SDK

- Old SDK does not advertise `independentContentVideo` (and may omit
  `mediaPolicy`; treated as video-enabled per the audio-only compatibility
  boundary).
- New SDK uses the legacy one-video replacement model **for that peer only**,
  broadcasting `content_state` before swapping the legacy sender track (the global
  start sequence already does this; old-SDK receive ordering is best-effort).
- Screen share still works to that peer; camera and screen share remain mutually
  exclusive on that specific connection.
- Other capable peers in the same mesh are unaffected; global state stays
  truthful (no global `cameraMode=screenShare`, unless the contingency shim is in
  effect, which capable peers ignore).

### Old SDK to New SDK

- New SDK sees no remote capability and treats the single video m-line as legacy
  video.
- If `content_state.active=true`, new SDK presents the legacy video track as
  content (holding the track until that state is known).
- No second video m-line is expected.

### Strict Audio-Only

- Strict audio-only is a new-SDK feature; such clients always emit
  `mediaPolicy.videoMediaEnabled=false`.
- `videoMediaEnabled=false` is signaled, so capable peers do not offer video
  m-lines toward this participant.
- The audio-only client adds no camera or content transceivers and answers any
  offered video m-line `inactive` (defense in depth), and suppresses all content
  UI.
- `startScreenShare()` returns false/no-ops.
- A missing `mediaPolicy` is interpreted as video-enabled. This is safe only
  because no deployed old client supports strict audio-only; that assumption is
  stated in [Capability vs Session Policy](#capability-vs-session-policy).

## Testing Plan

### Protocol and Server

- `join` stores allowlisted `independentContentVideo` and
  `mediaPolicy.videoMediaEnabled`; unknown keys are dropped.
- `joined` and `room_state` include participant capabilities and `mediaPolicy`.
- Missing `mediaPolicy.videoMediaEnabled` defaults to `true`.
- Existing clients without the capability continue to join.
- `content_state` persistence and replay still works, including `revision` and
  owning `sid`.
- Out-of-order `content_state` revisions within a `sid` are ignored in favor of
  the highest; a new `sid` for the same `cid` supersedes prior state regardless
  of `revision`.
- Content state is cleared on explicit leave / session expiry, but **not** on a
  recoverable disconnect that preserves `sid`; a `sid`-preserving reconnect keeps
  active content; a new-`sid` reconnect supersedes it.

### Web Unit Tests

- `videoMediaEnabled=false` creates no camera/content transceivers and answers
  offered video `inactive`.
- A peer signaling `videoMediaEnabled=false` is offered no video m-lines.
- `cameraModes=[]`, `videoMediaEnabled=true` creates receive camera and content
  transceivers but does not request camera permission.
- Capable peers get two video transceivers in camera/content order, created once
  by the offer owner; no duplicates under simulated glare.
- Legacy peers get one video transceiver.
- Starting screen share broadcasts `content_state` before attaching/swapping, and
  attaches content via `replaceTrack` with **no renegotiation**, leaving camera
  untouched for capable peers.
- Rollback broadcasts `active=false` with a `revision` strictly greater than the
  failed `active=true`.
- `replaceTrack` rejection falls back to renegotiation rather than failing the
  share.
- Share started before a peer's content transceiver is bound: track is held
  pending and attaches on binding.
- `displayTrack.onended` (browser "Stop sharing") runs the full stop path and
  emits `content_state.active=false`.
- **Idempotent stop:** programmatic `stopScreenShare()` plus the resulting
  `onended` produce exactly one `active=false` and one `revision` increment, and
  `displayTrack.stop()` is called once.
- Receiver holds a content/legacy video track until `content_state` is known
  (never shows it as camera).
- Remote `ontrack` routes camera/content tracks to separate streams by persisted
  role binding.
- Role binding survives a simulated glare rollback (roles not recomputed).
- In independent mode, `cameraMode` is never `screenShare` even with a legacy peer
  present.
- `content_state` toggles content presentation without altering camera state.

### Android Unit Tests

- `SerenadaSessionContractTest` covers the mode matrix.
- Fake media engine tracks camera start count and content start count
  separately.
- `WebRtcEngine` does not require camera modes to start content.
- Screen share no longer sets `userPreferredVideoEnabled`.
- Offer owner creates two video transceivers only when the peer supports the
  capability and is video-enabled (per-peer); no duplicates under glare.
- `MediaProjection.Callback.onStop` runs the stop path and broadcasts
  `active=false`; combined programmatic-stop + callback yields one increment and
  one `MediaProjection.stop()`/capturer release.
- Pending content track attaches when a slot finishes negotiating.
- Remote content renderer receives content track, not camera track.

### iOS Tests

- `SerenadaSessionTests` covers the mode matrix.
- Fake media engine tracks camera and content separately.
- `startScreenShare()` does not mutate camera preference in independent mode.
- Broadcast reader writes to content source.
- Broadcast finished/interrupted runs the stop path and broadcasts `active=false`;
  combined programmatic-stop + termination yields one increment and one reader
  teardown.
- Peer slot creates and classifies camera/content transceivers; binding persists.
- Content renderer receives content track, not camera track.

### Interop Tests

- **Legacy receiver gate (blocking):** new flag-on SDK sharing to each supported
  old Web/Android/iOS client renders content (a) with camera on and (b) with
  `cameraModes=[]` / camera off. Confirms the content_state-only dependency or
  triggers the contingency shim.
- New web to new Android: simultaneous camera and screen share.
- New iOS to new web: no-camera config can screen share and receive camera.
- New Android to old web: per-peer fallback to legacy replacement.
- **Mixed mesh**: one capable + one legacy peer — capable peer gets camera +
  content simultaneously while legacy peer gets content via replacement, in the
  same room. Attach failure on the legacy peer does not stop the capable peer.
  Local global state shows `cameraEnabled=true`, `cameraMode` ≠ `screenShare`.
- New capable peer joins while local screen share is already active: pending
  content attaches and renders.
- Legacy peer joins while local screen share is already active: legacy fallback
  engages for that peer; existing capable peers are undisturbed.
- **Legacy peer joins mid-share with the camera off:** the sharpest case for the
  global-state compatibility model — confirm the legacy peer still receives
  content.
- New web to PSTN/audio-only: no video m-lines offered to the audio-only peer;
  audio-only peer shows no content UI even when another participant shares.
- Reconnect while remote content is active: `room_state.contentState` (with
  `revision` + `sid`) restores content UI before or as media resumes; a
  `sid`-preserving reconnect does not flicker the share off.
- Sharer leaves explicitly without `active=false`: reconnecting peer does not see
  a stale active share (server lifecycle clear).
- Rejoin restarts `revision` at a low value with a new `sid`: receiver accepts
  the new state instead of discarding it.
- Quick stop/start share within a session: stale `active:false` does not clear
  the newer share (revision discrimination).
- Large/high-DPI display track: `replaceTrack` path or renegotiation fallback
  both deliver content.

### Manual QA

- Start camera, start screen share, verify both remote camera and remote content
  render at once.
- Stop screen share, verify camera remains live (no renegotiation glitch).
- Stop sharing via the browser/system control (not the app button), verify remote
  content clears, camera is unaffected, and the platform share indicator goes away
  (resources released).
- Start screen share with no camera modes configured, verify content sends.
- Start screen share as the first/only participant, verify the UI shows "sharing,
  waiting for participants", then have a peer join and verify content appears.
- Toggle camera while screen share is active, verify content is unaffected.
- Revoke camera permission and verify screen sharing still works where platform
  allows it.
- Background/reconnect during screen share, verify content state and media
  recover.
- Cancel the screen-share picker / deny broadcast, verify clean no-op and
  untouched camera (failure semantics), no lingering capture session.
- Host app with `enableIndependentContentVideo=false`: verify behavior is
  byte-for-byte today's legacy screen-share experience, including
  `cameraMode=screenShare`.

## Rollout Plan

The order below ensures no client advertises `independentContentVideo` before its
full local stack (media + UI) can consume content, per the capability contract,
and before the legacy-receiver dependency is verified.

### Phase 1: Server + Client Parsing (no advertisement)

- Server: allowlisted capability + `mediaPolicy` storage and forwarding;
  `content_state.revision` (+ `sid`) relay/persist; reconnect-aware content-state
  lifecycle clear.
- All clients: parse remote participant capabilities, `mediaPolicy`, and
  `revision`, and signal their own `mediaPolicy.videoMediaEnabled`, but continue
  to advertise `independentContentVideo=false` (config flag default off).
- Add SDK state fields (`cameraEnabled`, `content`) without changing media
  behavior.
- Update docs/protocol.

### Phase 2: Web Media + UI, verify, then enable

- Implement role-aware pre-negotiated transceivers, pending-track handling,
  idempotent + outside-stop handling, resource cleanup, and per-peer routing in
  web.
- Add content streams, accessors, and React UI content rendering (including
  pending/failed, waiting-for-participants, and audio-only suppression rules).
- Keep per-peer legacy fallback.
- Update web tests.
- **Run the legacy receiver gate** against supported old clients; enable the
  contingency shim if any client fails.
- Flip `enableIndependentContentVideo` for web only once core + UI are verified
  and the gate passes (or the shim is in place).

### Phase 3: Android Media + UI, verify, then enable

- Split camera/content tracks and sources; add content transceivers, pending
  attach, idempotent MediaProjection-stop handling + release, and renderer APIs;
  update Compose UI.
- Update Android tests; run the legacy receiver gate.
- Flip the flag for Android once core + UI are verified and the gate passes.

### Phase 4: iOS Media + UI, verify, then enable

- Split camera/content tracks and sources; route ReplayKit frames to content
  source; add content transceivers, pending attach, idempotent
  broadcast-termination handling + teardown, and renderer APIs; update SwiftUI UI.
- Update iOS tests; run the legacy receiver gate.
- Flip the flag for iOS once core + UI are verified and the gate passes.

### Phase 5: Cleanup

- Remove the legacy per-peer fallback (and any contingency shim) only after
  supported deployed clients have aged out.
- Remove `LocalCameraMode.screenShare` and the `videoEnabled` field only in a
  future **major** release, after the legacy fallback is gone; keep
  `cameraEnabled` as the precise field throughout.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Old SDK actually needs `videoEnabled`/`cameraMode` to render content | Mixed-room content invisible on old clients | Treat as unverified: blocking legacy receiver gate before enable; contingency shim emits legacy fields (capable peers ignore them) |
| Old SDK treats second video m-line as camera | Incorrect rendering or black video | Per-peer capability gate; never send second video m-line to old peers |
| Capable peer offers video to audio-only peer | Audio-only call gets unwanted video m-lines | Signal `mediaPolicy.videoMediaEnabled`; offerer skips video toward audio-only peers; answerer rejects offered video inactive |
| Mixed-room global state lies to capable peers | Wrong camera/content UI | Global state describes user intent; `cameraMode` never `screenShare` in independent mode; shim fields ignored by capable peers; per-peer limits in diagnostics |
| Signaling/media ordering race | Receiver shows content as camera | Signal before attach (best-effort); new-SDK receivers hold video until state known; old-SDK best-effort, interop-tested; no false "never" guarantee |
| Client advertises capability before UI consumes content | Remote screen share invisible | Capability requires media + UI ready; single `enableIndependentContentVideo` flag flipped per platform after verification |
| Role assignment drift across platforms | Camera/content swapped | Fixed m-line order, offer-owner creation, bind-once by transceiver/`mid`, never recompute from media, shared tests |
| Duplicate/mismatched m-lines under glare | Broken negotiation | Only the deterministic offer owner pre-creates transceivers; answerer maps from offer; duplicate-prevention test |
| Share before transceiver bound (non-owner / mid-join) | No content sender to attach to | Pending local content track attaches when the peer's content transceiver binds |
| `replaceTrack` rejected for large display track | Share fails to start | Content encoding profile + renegotiation fallback; test 4K/high-DPI |
| Per-share renegotiation glare | Stalled media on every toggle | Pre-negotiate content m-line send-capable; start/stop is `replaceTrack` only |
| Revision counter reset on rejoin | Valid new state discarded | Scope `revision` to `(cid, sid)`; new `sid` supersedes prior state by identity |
| Double stop (API + source-ended) | Double `active=false` / state churn | Idempotent stop latch: one capture stop, one restore, one release, one revision increment |
| Capture resources leak on stop/fail | Platform share indicator persists, battery drain | Explicit `MediaStreamTrack.stop()` / `MediaProjection.stop()` + capturer release / ReplayKit teardown on stop, rollback, failure |
| One peer attach failure aborts share for all | Healthy peers lose share | Per-peer failure handling; share proceeds if ≥1 peer succeeds/pending; failed peers go to recovery + diagnostics |
| Stale active content after sharer leaves | Reconnecting peer shows phantom share | Server clears content state on explicit leave/expiry, not on recoverable `sid`-preserving disconnect |
| Pending share before viewers | Screen captured with no viewers (privacy) | Intentional; UI must show "sharing, waiting for participants" |
| Mesh bandwidth increase | Higher CPU/network load | Content only sends while active; conservative content profile (~1080p/~5fps default) |
| UI assumes one video per participant | Broken layouts | Add content state/renderer APIs and layout content role before enabling |
| Content stalls while camera/audio healthy | User sees frozen share, no recovery | v1 exposes the stalled role in diagnostics; recovery stays connection-level |
| Platform capture constraints | Screen share failure on native platforms | Defined per-peer failure semantics; never alter camera state on failure |

## Open Questions

1. **`contentType` vocabulary.** Standardize on `"screenShare"` everywhere and
   update older docs that mention `"screen"`? (Leaning: yes, `"screenShare"`.)
2. **Content sender encoding tuning.** v1 ships a conservative default (~1080p,
   ~5 fps, modest bitrate ceiling) so `replaceTrack` stays in the steady-state
   path. Exact per-platform bitrate/FPS numbers are still to be tuned with real
   content (text-heavy vs video-heavy screens).
3. **Multiple-sharer layout policy.** First release surfaces most-recently-
   received-active content as primary (local receive order). A shared
   primary-presenter ordering is deferred unless product asks for it.

Resolved since previous revisions: per-peer vs room-wide routing → per-peer;
`videoEnabled` redefinition → `cameraEnabled` precise field, `videoEnabled`
mirrors it in independent mode; mixed-room state coherence → global state is
truthful, `cameraMode` never `screenShare` in independent mode, per-peer limits in
diagnostics; content_state ordering → single global signal-before-attach with
receiver-side hold (no strict-ordering guarantee asserted); screen-share
renegotiation → pre-negotiated send-capable content m-line with `replaceTrack`
fallback; `content_state` epoch → `revision` scoped to `(cid, sid)` with strictly
greater revisions on every change including rollback; content-state lifecycle →
reconnect-aware (clear on explicit leave/expiry, not on `sid`-preserving
disconnect); audio-only signaling → signaled `mediaPolicy.videoMediaEnabled` with
a stated compatibility boundary; non-owner/mid-join share → pending local track;
outside (browser/system) stop → shared **idempotent** stop path with explicit
resource release; per-peer failure → share proceeds if ≥1 peer succeeds,
audio-only receivers suppress content UI; pending-share privacy → intentional,
UI shows "waiting for participants"; per-role liveness → v1 exposes the stalled
role in diagnostics; legacy-receiver dependency → unverified, gated, with a
contingency shim.

## Acceptance Criteria

The implementation is complete when:

- The legacy receiver gate has been run for each supported old client and either
  passes (content renders from `content_state` alone, camera on and camera off)
  or the contingency shim is enabled for the failing platforms.
- PSTN mode negotiates no video (capable peers do not offer video toward it) and
  screen share cannot start.
- No-camera P2P mode can send screen share and receive remote camera/content.
- Camera P2P mode can send camera and screen share simultaneously to capable
  peers.
- In a mixed mesh, capable peers get simultaneous camera + content while legacy
  peers fall back per-peer, in the same room; a single peer's attach failure does
  not stop sharing to healthy peers; and global participant state stays truthful
  (`cameraEnabled` accurate, `cameraMode` never `screenShare` in independent mode).
- Screen share start follows one global signal-before-attach sequence; receivers
  on new SDKs hold video until `content_state` is known so content is never shown
  as camera; start/stop uses `replaceTrack` without renegotiation in the steady
  state (with renegotiation fallback when rejected) and does not alter camera
  state.
- A share started before a peer is ready attaches via the pending-track mechanism
  when that peer negotiates, including peers that join mid-share.
- The stop path is idempotent and releases capture resources: stopping via the
  SDK, via the browser/system control, or via rollback yields exactly one
  `active=false` (strictly greater revision than the start), one resource release,
  and an untouched camera.
- New SDKs interoperate with old SDKs through the per-peer legacy one-video
  fallback, with `content_state` broadcast before the legacy sender swap.
- A client advertises `independentContentVideo` only when both its media engine
  and UI/API surface can consume content video. A client with
  `enableIndependentContentVideo=false` behaves exactly like today.
- `content_state.revision` (scoped to `sid`) disambiguates stale state on quick
  toggles; rejoin with a reset counter is handled by `sid`; the server clears
  content state on explicit leave/expiry but preserves it across a
  `sid`-preserving reconnect.
- Audio-only receivers suppress content UI even when room state reports a sharer.
- Starting a share with no eligible peers shows a "waiting for participants" state
  rather than implying media is flowing.
- Web, Android, and iOS expose separate camera (`cameraEnabled`) and content
  (`content.active`) state and render paths, and diagnostics identify whether
  camera or content media stalled and which peer.
- Protocol docs and SDK integration docs describe the capability, signaled media
  policy, and state model.
- Cross-platform tests cover strict audio-only, no-camera P2P, camera P2P,
  per-peer old-peer fallback in mixed mesh (including a legacy peer joining
  mid-share with the camera off), mid-share peer join, idempotent outside-stop,
  reconnect with active content, sharer-leaves cleanup, and rejoin with a reset
  revision counter.