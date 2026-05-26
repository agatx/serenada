# Resilience Failure Modes — Audit & Fix Plan

**Status:** Phase 1 implemented; Phase 2 partial (#1 dirty-pair renegotiation, #4 SDK snapshot gate, #5 process-death recovery, and #8 foreground/Doze force-ping landed end-to-end; #3/#6/#7/#10/#13 SDK pieces outstanding).
**Date:** 2026-04-26 (audit) · 2026-04-25 (Phase 1 landed) · 2026-04-26 (Phase 2 partial #5+#8 landed) · 2026-04-28 (#4 SDK snapshot gate landed) · 2026-04-29 (#1 dirty-pair renegotiation landed)
**Scope:** Web, Android, iOS SDKs and the Go signaling server

## Background

Sessions used to die on every signaling drop: when the WebSocket / SSE transport
went away, the SDK tore down peer connections and required the user to start
over. To improve perceived reliability, we introduced a keep-alive design:

- The server preserves participant records for `suspendHardEvictionTimeout = 10 min`
  (`server/signaling.go:34`) after the transport drops, marking them
  `connectionStatus="suspended"` instead of removing them.
- The server's WS handler defers cleanup by `wsGracePeriod = 6 s`
  (`server/ws.go:16`), and SSE by `sseGracePeriod = 5 s` (`server/sse.go:17`).
- Clients keep their `roomState`, `clientId`, and per-peer `RTCPeerConnection`s
  in memory across signaling drops, persist a `reconnectToken`, and rejoin with
  `reconnectCid` to reattach to the same slot
  (`server/signaling.go:556-595`).
- On signaling reconnect, SDKs trigger an ICE restart against the cached peer
  set rather than a full rejoin.

This works for short interruptions. It introduces several new failure modes
when the interruption is long, when the server has forgotten the session, or
when the app process dies and restarts. This document enumerates each one,
explains how it manifests today, and proposes a fix.

The fixes are designed to be incremental. The important constraint is that they
must preserve the core UX model:

- **Signaling loss is not media death.** If an `RTCPeerConnection` is connected
  and media is still flowing, the SDK must not close it just because signaling is
  disconnected, the peer is marked `suspended`, or the server has not heard from
  that peer recently.
- **Explicit terminal events win.** User leave, host `end_room`, a room
  tombstone, or an expired/invalid reconnect credential can close media and clear
  recovery state. Ordinary app backgrounding, page reload, or transport failure
  must not be treated as leave.
- **Reconnect preserves identity.** A valid reconnect should keep the requested
  CID whenever possible, even if the server has to recreate in-memory room state.
  This prevents duplicate callers and lets signaling recover around still-flowing
  peer media.
- **Suspended is a signaling-directory state.** It means the participant has no
  attached signaling transport; it is not proof that media stopped. Server cleanup
  should combine elapsed suspension time with recent media-liveness hints from
  active peers so media-active participants are not evicted just because their
  signaling transport is late to recover.
- **No generic SDP mailbox.** Old offer/answer/candidate payloads are often worse
  than missing payloads. Latest room metadata belongs in snapshots; missed
  negotiation traffic should mark a peer pair dirty and trigger fresh
  renegotiation after an authoritative snapshot.
- **Ghosts are bounded.** Rooms with no attached transports and no recovery
  activity expire. Suspended participants with no recent media-liveness evidence
  hard-evict after the server timeout. Explicit leaves/end-room paths remove them
  immediately.

## Priority Summary

| # | Failure Mode                                              | User-visible Symptom                                      | Severity | Effort | Status |
|---|-----------------------------------------------------------|-----------------------------------------------------------|----------|--------|--------|
| 1 | Negotiation messages missed while peer is suspended       | Stuck call setup, missing media after reconnect           | Critical | M      | Phase 1 (server) · Phase 2 ✅ (SDK consumes `negotiation_dirty` for per-CID ICE restart; `relay_failed` informational) |
| 2 | Reconnect creates a new CID instead of preserving identity | App thinks it rejoined; peers see duplicate/empty state   | Critical | S      | Phase 1 ✅ |
| 3 | Suspension conflates signaling loss with media death      | Premature teardown risk, ghost UI for dead peers          | High     | M      | Phase 2 ✅ (SDK presentation timer + periodic `media_liveness` emission landed end-to-end) |
| 4 | ICE restart fires on stale peer map at reconnect          | Offers to ghost CIDs, missing offers to new peers         | High     | S      | Phase 1 (server) · Phase 2 ✅ (SDK snapshot gate landed end-to-end) |
| 5 | Process death without clean teardown                      | Server holds a slot for a participant that's gone         | High     | M      | Phase 2 ✅ (server `POST /api/leave` + per-platform recovery store + rejoin API) |
| 6 | No explicit "you are suspended / about to be evicted"     | UI can't show countdown; apps can't make smart choices    | Medium   | S      | Phase 2 ✅ (SDK `signalingState` surface with `Suspended`/`Reconnecting`/`Failed` variants and hard-eviction estimate) |
| 7 | SSE transport hijack via SID reuse                        | Security: another connection can take over an SSE session | Critical | S      | Phase 2 — server `TODO(#7)` placed; needs SDK `resumeSse` first |
| 8 | iOS background suspension keeps SDK in stale "connected"  | After long background, signaling appears alive but isn't  | High     | M      | Phase 2 ✅ |
| 9 | TURN credentials expire while signaling is down           | Relay path fails; cannot recover even when signaling back | Medium   | S      | Phase 3 |
| 10| Push-to-rejoin races with local cleanup                   | Duplicate sessions, ghost slot on server                  | High     | M      | Phase 2 |
| 11| `end_room` doesn't notify suspended peers                 | Suspended peer reconnect-loops a dead room                | Medium   | XS     | Phase 1 ✅ (tombstone + ROOM_ENDED) |
| 12| WS↔SSE failover creates split SIDs server-side            | Brief duplicate-attached state, peer flicker              | Medium   | M      | Phase 3 — depends on #7 + #15 |
| 13| Buffered signaling payloads become stale across reconnect | SDP collisions / out-of-order offer-answer state          | Medium   | S      | Phase 1 (server epoch + dirty-pair) · SDK enqueue tagging + flush-time discard pending |
| 14| Android foreground-service lifecycle drift                | Orphaned service after process kill blocks next call      | Medium   | S      | Phase 3 |
| 15| Reconnect-token replay can reclaim active CIDs            | Security: attacker can evict and impersonate a participant | Critical | M      | Phase 1 (token expiry) · transport-resume coupling Phase 2 |
| 16| Ephemeral content state lost while peer is suspended      | Screen share / content layout gets stale after reconnect  | High     | S      | Phase 1 ✅ |

Severity legend: Critical = corruption or security; High = correctness break;
Medium = degraded UX or recoverable.
Effort: XS < 1d, S = 1-3d, M = 3-7d.

Status legend: **Phase 1 ✅** = fully landed (server + all 3 SDKs); **Phase 1
(server)** = server-side protocol in place, SDK-side consumption staged for
follow-up; **Phase 2** / **Phase 3** = not started, see *Suggested ordering*.

---

## 1. Negotiation messages missed while a peer is suspended

**Status (2026-04-29):** Landed end-to-end (server + all 3 SDKs). The
server side from Phase 1 is unchanged: `handleRelay` records a dirty pair
on the room when an offer/answer/ice targets a CID without an attached
transport, replies `relay_failed{target_suspended,targets,of}` to the
sender, and emits `negotiation_dirty{with}` to active peers right after
the reattach snapshot. SDK side: each platform now surfaces both
messages as typed provider events (`negotiationDirty`, `relayFailed`).
On `negotiation_dirty`, `SerenadaSession` calls per-CID
`scheduleIceRestart`/`scheduleDirtyPairRestart` against the existing
glare-safe ICE-restart machinery. `relay_failed` is logged but not
acted on directly — the same dirty-pair condition will surface as
`negotiation_dirty` once the suspended target reattaches.

### Symptom

A sends an SDP offer (or answer, or ICE candidate) to peer P while P's
transport is briefly down. P reconnects within the grace / hard-eviction window
and reattaches to the same slot, but never receives that message. A believes it
was delivered. Bidirectional negotiation is now stuck or proceeds against
inconsistent state.

### Root cause

`Hub.handleRelay` (`server/signaling.go:880-935`) only delivers to clients in
`room.byClient` — that index is keyed by the active `*Client` and is empty for
suspended participants. There is no per-CID outbound queue and no redelivery on
reattach. Any signaling message routed to a suspended CID is silently dropped.

This violates the implicit contract suggested by suspension: the slot still
exists, so other peers expect signaling to recover around it. But replaying old
SDP/ICE is not a safe fix. Offers, answers, and trickled candidates are tied to a
specific peer-connection state; delivering them later can create glare or apply
an offer after the local state has already moved on.

`content_state` and media-state style messages are a different class: they are
latest-state metadata, not negotiation traffic. See #16 for that state-sync gap.

### Suggested fix

Do not add a generic per-participant SDP mailbox. Instead, make missed
negotiation explicit and recover with fresh negotiation after reattach:

1. In `handleRelay`, when the target CID exists but has no attached transport,
   do not enqueue SDP/ICE for later delivery. Record a small
   `negotiationDirty[fromCID][toCID]` marker on the room and send a best-effort
   `target_suspended` / `relay_failed` response to the sender. If the sender's
   transport is gone too, the dirty marker is still enough.
2. SDKs should suppress new offers/ICE to peers currently marked `suspended` in
   `room_state`. If media is already flowing, leave it alone.
3. On target reattach, the server first sends `joined`, then an authoritative
   `room_state` snapshot with current participant and content metadata, then
   notifies the affected active peers that the pair needs renegotiation. The
   SDK schedules glare-safe fresh negotiation/ICE restart for dirty pairs only.
4. ICE candidates generated while the remote peer was suspended are allowed to
   expire. A fresh ICE restart will produce candidates for the current network.
5. Keep `content_state` out of the negotiation path entirely. Store and replay
   only the latest content metadata through `joined` / `room_state` as described
   in #16.

The cleanest place to wire the server marker is in `handleRelay` right where
`byClient` lookup currently fails:

```go
target := room.participantByCID(toCID)
if target == nil { return /* CID not in room */ }
if target.Client == nil {
    room.markNegotiationDirty(c.cid, toCID)
    c.sendRelayFailed(toCID, "target_suspended")
    return
}
target.Client.sendMessage(msg)
```

After `handleJoin` sends `joined` and the authoritative `room_state` for a
successful reattach:

```go
room.notifyDirtyNegotiationPairs(p.cid)
```

### Tests

- Unit: relay offer/answer/ICE to suspended CID records a dirty pair and does
  not enqueue SDP/ICE bytes.
- Unit: sender leaves before target reattaches; dirty pair is discarded.
- SDK unit: no offers are generated to a peer while its latest room state is
  `suspended`.
- Integration: A attempts negotiation while P is suspended; P reattaches; after
  the authoritative `room_state`, the affected pair performs fresh negotiation
  without tearing down media that was still flowing.

### Risk

Medium. The change replaces implicit silent drop with explicit fresh
renegotiation. It is simpler than buffering SDP, but it requires SDK support so
senders stop waiting forever for an answer that will never arrive.

---

## 2. Reconnect creates a new CID instead of preserving identity

**Status (2026-04-25):** Landed end-to-end. `joined.payload.reconnect` now
reports `"fresh" | "reattached" | "recovered"`, and a valid reconnect token
that targets a missing participant record recreates the slot under the
requested CID. SDKs parse the outcome (`lastReconnectOutcome` /
`JoinReconnectOutcome` / `ReconnectOutcome`) and surface `sessionExpired`
on `INVALID_RECONNECT_TOKEN`, clearing persisted reconnect state. Tombstone
checks (#11) gate the `"recovered"` path so a deliberately-ended room never
silently turns into a fresh participant.

### Symptom

Client disconnects for longer than the keep-alive window, the server restarts,
or the room was GC'd because it had no attached transports. The SDK rejoins with
the stored `reconnectCid` and `reconnectToken`, but the server has lost the
participant record. Today the join can succeed as a fresh participant with a new
CID. That creates the worst possible recovery shape: media may still be flowing
to the old CID, while signaling now believes this device is a different caller.

### Root cause

Two issues conspire:

1. The `joined` payload always returns the assigned CID, but neither
   `client/packages/core/src/signaling/SignalingEngine.ts` nor
   `client-android/.../SerenadaServerProvider.kt:217-224` nor
   `client-ios/.../SerenadaServerProvider.swift:232` compares the assigned CID
   to the `reconnectCid` they sent. A silent change is treated as success.
2. When the participant record is gone, the server falls through to creating a
   fresh participant rather than preserving the requested CID when the
   reconnect token still proves authority for that CID. The client cannot tell
   "I recovered the same participant identity" apart from "I started a new
   caller in a room that happens to share a roomId."
3. Invalid reconnect tokens already return `INVALID_RECONNECT_TOKEN`
   (`server/signaling.go:564-568`), but SDKs map that to generic server error
   and do not clear persisted reconnect state. There is no cross-platform
   `sessionExpired` / `reconnectRejected` error code today.

### Suggested fix

Prefer identity recovery over fresh identity. If a reconnect request includes a
valid, unexpired reconnect token for `{rid, cid}`, the server should preserve
that CID even if it has to recreate the room/participant record from scratch,
unless a room tombstone says the call explicitly ended (#11).

Make the protocol surface the outcome explicitly. Add a field to the `joined`
payload:

```jsonc
{
  "type": "joined",
  "payload": {
    "cid": "C-abc",
    "reconnect": "reattached" | "recovered" | "fresh",
    // ... existing fields
  }
}
```

- `"reattached"` — server reattached to an existing participant record.
- `"recovered"` — the original participant record was gone, but the reconnect
  token was still valid, so the server recreated the record with the requested
  CID. This is a signaling-directory recovery, not a reason to close active
  media.
- `"fresh"` — the server created a new participant identity because no valid
  reconnect authority was supplied, the reconnect credential expired, or the app
  explicitly chose to start fresh. CID may be new.

Keep invalid tokens on the existing `error` path:

- `INVALID_RECONNECT_TOKEN` — `reconnectToken` was invalid. The client should
  clear its saved token/CID pair and surface a dedicated `sessionExpired`
  (or equivalently named) call error instead of a generic server failure.

On the SDK side:

- If `"reattached"` or `"recovered"`, the SDK keeps any peer connection with
  currently flowing media. It reconciles peer maps from the authoritative
  post-reconnect `room_state` (#4) and schedules fresh negotiation only for
  missing/dirty pairs (#1, #13).
- If `"fresh"`, the SDK may purge stale peer state and treat the join as a
  ground-up start, but it should only close an existing `RTCPeerConnection`
  immediately if no inbound media has flowed recently or the user/app explicitly
  accepted starting a fresh call. This avoids breaking a call whose media
  survived a signaling outage.
- If `INVALID_RECONNECT_TOKEN`, the SDK clears its persisted token and surfaces
  the new session-expired error on Web, Android, and iOS.

### Tests

- Server: valid reconnect token for a missing participant recreates that same
  CID and returns `"recovered"` unless a tombstone says the room ended.
- Server: invalid or expired reconnect token still returns
  `INVALID_RECONNECT_TOKEN`.
- Each SDK: `"reattached"` / `"recovered"` preserves media-active peer
  connections and waits for the authoritative post-reconnect snapshot before
  renegotiating.
- Each SDK: `"fresh"` resets stale state only after confirming there is no
  media-active peer connection to preserve or after explicit user/app consent.
- Each SDK: invalid reconnect token clears persisted reconnect state and maps to
  the dedicated session-expired error.

### Risk

Low for the protocol addition (additive field). Medium for the SDK reset
logic — needs careful audit that nothing assumes peer map continuity across
the boundary.

---

## 3. Suspension conflates signaling loss with media death

**Status (2026-04-29):** Landed end-to-end across server and all three
SDKs. When a remote peer transitions to `signalingStatus="suspended"` in
`room_state`, each SDK starts a per-CID timer of `peerSuspendedUiTimeoutMs
= 30 s`; on expiry the SDK flips a new `presumedLost: boolean` field on
the participant so call UIs can move them out of the active grid. The
peer connection itself stays open so media can resume immediately if the
peer reattaches. Cancellation is automatic when the peer reattaches as
active or leaves the room.

Active SDKs also broadcast `media_liveness{cids:[…]}` every
`mediaLivenessIntervalMs = 10 s` for remote CIDs whose inbound RTP
`bytesReceived` advanced since the previous sample. Web reads stats
straight from each `RTCPeerConnection`; Android and iOS use a new
`PeerConnectionSlot.collectInboundBytes` callback. Emission starts when
the room transitions to `inCall` (i.e. there's at least one remote peer)
and pauses while the local transport is disconnected — baseline samples
are preserved so the next post-reconnect tick can detect flow. The
server's existing `mediaLivenessFreshnessWindow = 30s` and
`hardEvictMediaActiveDeferral = 30s` from Phase 1 do the rest of the
work; a hard-eviction is now deferred whenever any active peer reports
recent media from a suspended CID.

### Symptom

Remote peer A's signaling transport drops. Server marks A
`connectionStatus="suspended"` and broadcasts updated `room_state`. There are
two distinct cases:

- A's media is still flowing. The call should continue, and no SDK should close
  A's `RTCPeerConnection` just because signaling is gone.
- A's app or network is actually gone. The UI should stop presenting A as an
  active caller, and the server should eventually reclaim the slot.

Today those cases are not separated clearly. A client-side timeout that tears
down the peer would break the first case. Waiting only for server hard eviction
keeps dead peers visible for too long in the second case.

### Root cause

`MediaEngine.syncPeers` (`client/packages/core/src/media/MediaEngine.ts:474-507`)
considers any participant present in `roomState` as "alive" and keeps the peer
connection. The `connectionStatus` field is read into `RemoteParticipant`, but
the SDK/UI do not combine it with actual media liveness. Same on Android
(`PeerNegotiationEngine.kt:69-93`) and iOS (mirrored).

The server also only sees signaling transport state. Without a hint from active
peers, it cannot distinguish "signaling down, media still flowing" from "peer is
gone."

### Suggested fix

Treat `suspended` as signaling-only state, not a local teardown command:

1. When a participant transitions to `suspended` in `room_state`, start a
   per-CID presentation timer (e.g. `peerSuspendedUiTimeoutMs = 30 s`). The timer
   is for UI state only.
2. While suspended, suppress outbound offers/ICE for that CID unless fresh
   negotiation is explicitly requested after reattach (#1, #13).
3. If inbound media/data is still flowing for that peer, keep rendering it and
   show a per-participant "signaling reconnecting" hint. Do not close the
   `RTCPeerConnection`.
4. If the UI timer fires and no inbound media has flowed recently, move the peer
   out of the active grid or show a "connection lost" state. Still do not close
   the `RTCPeerConnection` solely because of this timer.
5. On reattach (`suspended` -> `active`), cancel the presentation timer and wait
   for the authoritative post-reconnect room snapshot before scheduling any
   restart/renegotiation (#4).

Server cleanup needs the same distinction. Active clients should periodically
send a compact media-liveness hint for remote CIDs whose inbound media is
currently flowing, and immediately after reconnect. A suspended participant is
eligible for hard eviction only when both are true:

- its signaling transport has been absent longer than
  `suspendHardEvictionTimeout`; and
- no active participant has reported recent inbound media from that CID within a
  short freshness window.

If no participants have attached signaling transports, there is nobody to report
media liveness; the room is still bounded by the existing recovery/GC timeout so
zero-active rooms do not live forever.

**Priority Rule:** Explicit terminal server events (`leave`, `end_room`,
room tombstone, future kick/moderation events) close the local PC and clear
recovery state. Timeout-based signaling eviction should normally happen only
after media-liveness evidence is stale. If an SDK ever receives a timeout
eviction for a CID that is still delivering media locally, it should keep the PC
in an "orphaned media" state and force identity recovery (#2) instead of closing
first.

Render the existing remote-participant signaling status in UI. The SDKs already
parse `connectionStatus="suspended"` into per-participant state, but the call
UIs mostly render aggregate reconnecting state. A remote peer that is suspended
while local signaling is healthy needs a per-participant "reconnecting" hint.

The UI timeout constant must be added to `WebRtcResilienceConstants` and verified by
`scripts/check-resilience-constants.mjs` to keep parity across the three
clients.

### Tests

- SDK unit: roomState transition active → suspended → active within window:
  no PC teardown, no offers issued during suspension.
- SDK unit: roomState transition active → suspended → timer expiry while inbound
  media is flowing: PC stays open and UI shows media with signaling degraded.
- SDK unit: roomState transition active → suspended → timer expiry with no
  inbound media: PC stays open, peer moves to lost/reconnecting presentation.
- Server unit: suspended participant with recent media-liveness reports is not
  hard-evicted at `suspendHardEvictionTimeout`.
- Server unit: suspended participant with no recent media-liveness report is
  hard-evicted after the timeout, and zero-active rooms are GC'd.

### Risk

Medium. This avoids premature teardown, but adds a small media-liveness signal
from SDKs to the server. The signal must be treated as a cleanup hint, not as
authorization or proof of identity.

---

## 4. ICE restart fires against stale peer map on reconnect

**Status (2026-04-28):** Landed end-to-end. Server-side: `Room.Epoch`
advances on every membership-mutating operation; `joined` and `room_state`
carry `epoch`; the server sends an authoritative `room_state` snapshot on
the new transport immediately after every successful `joined`, regardless
of whether membership changed. SDK-side: all three platforms now defer
the post-reconnect ICE restart in `SerenadaSession` until either the
authoritative `room_state` snapshot arrives or the new shared
`epochResyncTimeoutMs = 5_000` elapses. On timeout, the SDK falls back to
firing ICE restart against the last-known peer map (graceful degradation
to pre-#4 behavior).

### Symptom

Signaling reconnects after a 30 s drop. The SDK's cached `roomState` shows
peers `[B, C]`. While we were disconnected, B left and D joined. The SDK fires
ICE restarts to B and C, never to D. By the time the next `room_state` arrives
and the peer map is corrected, ICE has stalled, possibly retried, possibly
declared the call dead.

### Root cause

`SerenadaSession.handleProviderConnected` (mirrored across platforms) calls
`MediaEngine.handleSignalingReconnect()` *immediately* on transport reconnect
(`client/packages/core/src/SerenadaSession.ts:491-494`,
`client-android/.../SerenadaSession.kt:413-420`,
`client-ios/.../SerenadaSession.swift:602-619`).
That call schedules ICE restarts based on `this.peers` / `peerSlots` / etc.
But the next `room_state` from the server hasn't arrived yet. We're acting on
data we know is stale.

### Suggested fix

Gate ICE restart on an **authoritative room snapshot received after reconnect**:

1. Server-side: include a monotonic `roomStateEpoch` integer in every
   `room_state` (and `joined`) payload. It increments on every membership
   change (join, leave, suspend, reattach, evict). Cheap to maintain — single
   counter per room.
2. Server-side: every successful `joined` response for fresh, reattached, or
   recovered joins is followed by a full `room_state` snapshot on that same new
   transport, even when no membership changed and the epoch is equal to the
   client's last-seen value.
3. SDK-side: on disconnect, record the last-seen epoch as `epochAtDisconnect`.
   On reconnect, *do not* trigger ICE restart yet. Wait for the first
   authoritative `room_state` delivered on the new transport with epoch
   `>= epochAtDisconnect` and the expected CID/reconnect outcome from #2. Only
   then `syncPeers` and schedule ICE restart or renegotiation against the
   server-confirmed peer set.
4. Add a 5 s timeout: if no authoritative post-reconnect snapshot arrives by
   then, treat the reconnect as failed and retry the reconnect path. Do not
   infer "fresh join" from the timeout alone.

This also kills a class of races where late-arriving signaling payloads from
*before* the disconnect revive stale peer assumptions because the SDK treats its
in-memory peer map as canonical.

### Tests

- Unit: epoch advances on every membership change.
- SDK unit: simulate disconnect → membership change on server → reconnect.
  Assert no offers issued before the new `room_state` is processed.
- SDK unit: simulate disconnect → reconnect with no membership change. Assert a
  same-epoch post-reconnect snapshot unblocks resync.
- Integration: client A drops 5 s, client B leaves during the drop, client A
  reconnects — assert A does not generate an offer to B.

### Risk

Low. The epoch and post-reconnect snapshot are additive and ignored by older
clients (they keep current behavior). The wait-for-snapshot path is additive on
top of existing reconnect flow.

---

## 5. Process death without clean teardown

**Status (2026-04-26):** Landed end-to-end. Server exposes `POST /api/leave`
that takes `{rid, cid, reconnectToken}`, validates the token (rejects
expired or unsigned), and immediately hard-evicts via the new
`Hub.evictByLeave`. Idempotent and rate-limited (12 req/min/IP). All
three SDKs persist `{roomId, cid, reconnectToken, lastEpoch,
sessionStartTs, expiresAtMs}` across launches: Web → `sessionStorage`,
Android → app-private `SharedPreferences`, iOS → injectable
`UserDefaults` (defaults to `.standard`; host apps can pass an
app-group store). Each SDK exposes `getRecoverableSession()` and
`discardRecoverableSession()` so host apps can offer a "Rejoin call?"
prompt on relaunch. Recovery records are cleared on clean leave,
`room_ended`, and `INVALID_RECONNECT_TOKEN`; expired records are dropped
on read.

### Symptom

User is in a call. App is force-killed (jetsam, swipe-up, low-memory killer).
The server's grace period elapses with no reconnect — the participant goes to
suspended, the slot is held for 10 minutes. The other peer sees them as
"reconnecting" for the entire window. If the user relaunches the app, there's
no local memory of the call; they see the home screen. The call is effectively
dead but appears alive on the server.

### Root cause

- iOS: `deinit` is unreliable on force-quit / jetsam. No persistence of
  `roomId`, `cid`, `reconnectToken` (`client-ios/Sources/Core/Call/CallManager.swift`).
- Android: `CallService.onTaskRemoved` does call `leaveCall()`, but LMK kills
  the process without invoking it (`client-android/.../service/CallService.kt:48`).
  No persisted call state either.
- Web: explicit leave has no unload-safe fallback; generic `pagehide` /
  `beforeunload` is too ambiguous to mean leave by itself.

In all three platforms, the SDK does not persist enough state to recover on next
launch. Some shutdown paths can send an explicit `leave`, but many ordinary OS
lifecycle transitions are ambiguous: backgrounding, page reload, or process
suspension may be followed by a valid reconnect and must not be treated as a
final departure.

### Suggested fix

Two coordinated changes:

**A. Use final leave only for explicit terminal paths**

- iOS: do not send `/api/leave` from normal `scenePhase == .background`,
  background-task expiration, or `willTerminateNotification`. Those signals are
  not reliable proof that the user left, and using them as leave would drop
  callers during normal suspension. On background, persist recovery state and
  mark local signaling as unknown; #8 handles fast validation on foreground.
- Android: keep the existing explicit `leaveCall()` path for user leave and
  intentional task removal. Do not claim LMK can be solved with
  `Application.onTerminate` or a background job; those are not reliable process
  death hooks. Persist recovery state so relaunch can rejoin with the same CID.
- Web: do not send `/api/leave` from generic `pagehide` / `beforeunload`; reload
  and tab discard are recoverable cases. Use `sendBeacon('/api/leave', ...)`
  only when the app is already executing an explicit leave/end-room action and
  the page may unload before the normal signaling leave completes.

Server: add `POST /api/leave` that takes `{rid, cid, reconnectToken}`, validates
the token, and immediately hard-evicts the participant. Skip the suspension
hold. Idempotent. This endpoint is for explicit terminal intent, not ordinary
OS lifecycle cleanup.

**B. Persistent recovery state**

Persist `{roomId, cid, reconnectToken, lastSeenEpoch, sessionStartTs}` to:

- iOS: `UserDefaults` in the app group (already shared with the notification
  service for snapshot keys). Clear on clean leave.
- Android: app-private SharedPreferences. Clear on clean leave.
- Web: `sessionStorage` (per-tab). Clear on clean leave.

On app launch, if a recovery record exists and is younger than the reconnect
token TTL / `suspendHardEvictionTimeout`, surface a "Rejoin call?" prompt to the
user. On accept, drive the same reconnect path as a normal `reconnectCid` join.

Web specifically: `sessionStorage` is per-tab and survives reload but not tab
close — that's the right scope for "you reloaded the page mid-call."

### Tests

- Web: test that explicit leave during unload can use a beacon, and that generic
  `pagehide` without leave intent does not send `/api/leave`.
- Server: integration test that `/api/leave` is rate-limited and validates
  reconnect token.
- iOS unit/UI: background-task expiration persists recovery state and does not
  call `/api/leave`; foreground triggers #8 reconnect validation.
- iOS UI test: kill app via `XCUIApplication.terminate()`, verify on next
  launch the rejoin prompt appears (this is testable in the simulator).

### Risk

Medium. The beacon / final-leave path needs careful auditing for race with
the normal cleanup path — must be idempotent on the server. The persistent
recovery record needs a TTL audit so we don't perpetually prompt users to
rejoin dead calls.

---

## 6. No explicit "you are suspended / about to be evicted" signal

**Status (2026-04-29):** Landed end-to-end. Each SDK now exposes a
richer `signalingState` field on `CallState` with four variants:
`connected`, `reconnecting{attempt, nextRetryAtMs}`,
`suspended{suspendedSinceMs, estimatedHardEvictionAtMs}`, and
`failed{reason}`. The `suspended` variant is entered when the local
transport drops while a roomState is present (i.e. mid-call); the hard-
eviction estimate is computed locally from the new shared
`suspendHardEvictionTimeoutMs = 600_000` constant and mirrors the Go
server's `suspendHardEvictionTimeout`. Resilience-constants check now
validates 22 constants across platforms (was 20). The `mediaRecentlyObserved`
field from the original proposal is deferred until the matching #3
media-liveness emission lands.

### Symptom

The SDK can't tell the difference between "I'm reconnecting and the server is
holding my slot" and "I'm reconnecting and the server already evicted me."
There's no countdown, no UX hint, no way for the app to make a smart decision
("media is gone and we've been suspended for 8 minutes, show lost/rejoin UI").

### Root cause

The server's view of "this client is suspended" is implicit — it stops
sending messages. The client infers nothing actionable from silence.

### Suggested fix

When a transport reconnects but the server has already hard-evicted the slot,
return explicit protocol state rather than silently creating a second identity.
If the room is gone because the call ended, return a structured terminal error
(#11). If the server can validate the reconnect credential, prefer
`joined.reconnect="recovered"` with the original CID (#2). Only return
`"fresh"` when the credential is missing, invalid, expired, or the app chose a
new call.

Additionally, while suspended, the SDK should expose this state to the app
shell:

```ts
// Web SDK
type SignalingState =
  | { kind: 'connected' }
  | { kind: 'reconnecting'; attempt: number; nextRetryAtMs: number }
  | {
      kind: 'suspended';
      suspendedSinceMs: number;
      estimatedHardEvictionAtMs: number;
      mediaRecentlyObserved: boolean;
    }
  | { kind: 'failed'; reason: CallErrorCode };
```

The `estimatedHardEvictionAtMs` deadline is computed client-side from
`suspendedSinceMs + suspendHardEvictionTimeoutMs`, then treated as hidden or
best-effort while `mediaRecentlyObserved=true` because #3's media-liveness hints
can extend server retention. That constant does not exist in the SDKs today; add
it to `WebRtcResilienceConstants` across Web, Android, and iOS and include it in
`scripts/check-resilience-constants.mjs`. The app shell can render a countdown
when media is gone, or a degraded-signaling badge when media is still flowing.

This is mostly a state-modeling change inside the SDK plus a UX hook. The only
server-side dependency is making the terminal/fresh-rejoin outcomes explicit
enough for the SDK to know whether the countdown ended in recovery, fresh join,
or call termination, and exposing the media-liveness cleanup hint from #3.

### Tests

- SDK unit: state transitions emitted in correct order.
- SDK unit: `estimatedHardEvictionAtMs` is computed from the shared hard-eviction
  timeout and hidden/de-emphasized while media is still observed.

### Risk

Low. State surface only.

---

## 7. SSE transport hijack via SID reuse

**Status (2026-04-25):** Deferred to Phase 2. A `TODO(#7)` is placed at
`server/sse.go` next to `replaceClient` so the hardening lands in lockstep
with SDK `resumeSse` support. Shipping the server change without the SDK
piece would break legitimate in-flight SSE transport flaps.

### Symptom

Any party who learns a victim's SSE `sid` (logs, network capture, leaked
client-side state, browser history of a shared device) can issue
`GET /sse?sid=<victim-sid>` and instantly take over the SSE channel. They
inherit the victim's room membership and receive all relayed offers / ICE /
media-state messages. They can also POST signaling messages on the victim's
behalf.

### Root cause

`server/sse.go:59-74`:

```go
sid := strings.TrimSpace(r.URL.Query().Get("sid"))
if sid == "" {
    sid = generateID("S-")
}
existing := hub.getClientBySID(sid)
if existing != nil {
    hub.replaceClient(existing, client)   // No reconnect-token check
}
```

The WS join handler validates `reconnectToken` (via
`validateReconnectToken`, `server/signaling.go:564`) before reattaching to a CID,
but SSE replacement has no equivalent gate. Any SSE GET with a guessed /
leaked SID wins.

### Suggested fix

Do not allow `replaceClient` to bind an SSE connection to a participant slot
based on SID alone. The important constraint is routing: today `/sse` POSTs are
routed by `sid` through `clientsBySID`, so a replacement design needs an
unauthenticated pending-session state that cannot receive participant traffic
until identity is proven.

1. **SID continues to be the transport-session identifier**, but acquiring a SID
   does not by itself give participant authority. Move the `replaceClient`
   semantics from SSE GET to a post-connect step that requires reconnect-token
   validation.
2. **Concretely**: on `GET /sse?sid=<existing>`, do not replace the active
   client. Either reject the duplicate SID with 409/401, or allocate a new
   pending SID that is registered only in a separate `pendingSse` map. The
   pending session may accept only a fresh `join` or a small `resumeSse`
   envelope:

   ```jsonc
   {"type":"resumeSse","payload":{"reconnectToken":"...","cid":"..."}}
   ```

   The handler validates the token and only then moves the pending session into
   `clientsBySID` / calls the participant rebind path. Until that point, the
   pending session must not receive queued relay messages and must not be
   considered an active room participant.

3. The HTTP layer should drop the `sid` query parameter from server logs
   immediately to reduce leak surface area. Additionally, the SDK should
   sanitize the `Referer` header (or omit it for signaling requests) to prevent
   `sid` leaking to external services if the user navigates away mid-session.
4. Ship together with #15's reconnect-token hardening. Otherwise replacing SID
   auth with reconnect-token auth just moves the takeover primitive from "leaked
   SID" to "leaked reconnect token."

This also closes a related correctness gap: a stale browser tab navigating
to a new room can no longer poison the old room by reusing its SID.

### Tests

- Server unit: duplicate `GET /sse?sid=<victim>` does not bind or receive
  participant traffic.
- Server unit: pending SSE + `resumeSse` with valid token rebinds; invalid token
  returns 401/403 and does not rebind.
- Server unit: pending SSE can only send `join`/`resumeSse`; relay, media-state,
  and `end_room` are rejected before auth.
- Penetration smoke test: replay a captured SSE GET from a different IP and
  confirm the victim's session is undisturbed.

### Risk

Medium. Requires SDK side changes too (web SDK currently doesn't send a
`resumeSse` envelope). Backwards-compat: support both old and new behavior
behind a server feature flag for one release; clients gain `resumeSse` first.

---

## 8. iOS background suspension keeps SDK in stale "connected" state

**Status (2026-04-26):** Landed end-to-end. Both iOS and Android SDKs
auto-detect foreground transitions after a ≥ 5 s background and call a
new `signalingProvider.forceReconnectIfStale(timeoutMs:)` hook that
sends a synthetic ping, arms a `foregroundForcePingTimeoutMs = 2_000`
deadline, and force-closes the transport on miss so the existing
reconnect path runs. iOS observes `UIApplication.willEnterForeground`
notifications; Android tracks Activity start/stop counts via
`Application.ActivityLifecycleCallbacks` (no new dependency on
`lifecycle-process`). The internal `SessionSignaling.forcePingWithDeadline`
uses a monotonic pong-sequence counter so the deadline arms the close
only when no pong arrives between ping and timeout.

### Symptom

User backgrounds the iOS app during a call. iOS suspends the process, freezing
all timers and networking. After 30+ minutes the user reopens the app. The
SDK's WebSocket has been silently killed by the OS but the SDK considers
itself connected — pings haven't fired, no `onError` was raised. ICE has
likely failed too. For up to `pingIntervalMs = 12 s` after foreground, the
SDK shows "connected" while nothing is actually working.

### Root cause

`SerenadaSession.swift` doesn't observe `scenePhase` or
`UIApplicationDelegate` lifecycle. The only signal is the network-path
monitor, which fires on interface change but not on app foreground events
(`SerenadaSession.swift:1133-1143`). Audio session gets reactivated
(`CallAudioSessionController.swift:38-58`) but signaling is left alone.

### Suggested fix

In the iOS SDK, observe scene-phase transitions and treat
`active` (after a period of `background` or `inactive`) as a forced
reconnect trigger:

1. In `SerenadaSession`, subscribe to `scenePhase` via
   `NotificationCenter.default` (`UIScene.willEnterForegroundNotification`).
2. On foreground after `> 5 s` background, immediately issue a synthetic
   ping and start a `2 s` deadline. If no pong arrives, force-close the WS /
   SSE transport and trigger the normal reconnect path. This is faster and
   more decisive than waiting for `pingIntervalMs`.
3. Same hook should refresh the path-quality state (existing logic) and
   force a `room_state` request from the server (see #4 — this is the same
   epoch-based resync).

Android has an analogous case with Doze mode but the foreground service
keeps the process alive, so the immediate impact is smaller. We should still
apply the same "force ping on foreground after backgrounded" logic on
Android via `ProcessLifecycleOwner` to catch Doze releases.

### Tests

- iOS UI test: launch call, suspend simulator, resume, assert reconnect
  begins within 2 s.
- Android instrumentation: same flow using Doze simulation
  (`adb shell dumpsys deviceidle force-idle`).

### Risk

Low. Additive lifecycle observers; the synthetic ping is cheap.

---

## 9. TURN credentials expire while signaling is down

**Status (2026-04-25):** Not started — Phase 3.

### Symptom

Signaling drops. Both peers were on direct ICE paths, so the SDK's keep-alive
logic skips a scheduled TURN refresh (`SerenadaSession.ts:304-306`). Later the
network changes and ICE needs to fall back to relay. The TURN credentials are
expired (`turnTokenTTL = 15 min`, `server/signaling.go:26`). ICE allocation
fails or cannot be restarted cleanly when signaling returns.

### Root cause

TURN credentials are fetched in `joined` / explicit `turn-refresh` responses and
the clients do schedule periodic refreshes. The weak spot is the gate: after a
"all paths direct" skip, the scheduler rechecks at a fraction of the remaining
lifetime and eventually stops once the old credentials are expired. If signaling
is down when the timer fires, current schedulers also return instead of queuing a
refresh to apply on reconnect. The optimization assumes "direct now" is stable
through future network changes, which is false.

### Suggested fix

Two coordinated guards:

1. **Track TURN credential expiry** in the SDK. Each `joined` /
   `turn-refresh` response provides `turnTokenTTLMs` (already populated
   server-side, `server/signaling.go:715`). Store
   `turnExpiresAtMs = receivedAtMs + turnTokenTTLMs - 60_000` (60 s safety
   margin).
2. **Refresh TURN on a timer**, regardless of signaling state. Path mode may
   delay a refresh, but it must not let credentials pass expiry. Queue the
   refresh request if signaling is down, and apply it immediately on reconnect.
   This is a small change to the existing refresh scheduler.
3. **On any ICE state transition to `failed`**, force a TURN refresh and an
   ICE restart even if signaling has been down. If signaling is also down,
   queue both and apply when transport returns.

The current "all paths direct" optimization should be kept as a hint to defer,
not to skip until expiry. Refresh no later than `turnExpiresAtMs - 60_000`, or
earlier if any peer is on relay.

### Tests

- SDK unit: simulate `turn-refresh` request issuance at expiry boundary.
- SDK unit: ICE failure during signaling-down forces queued refresh, applied
  on reconnect.

### Risk

Low. The TTL field is already on the wire; only the scheduling logic
changes.

---

## 10. Push-to-rejoin races with local cleanup

**Status (2026-04-25):** Not started — Phase 2.

### Symptom

User is in a call. They explicitly leave (or the call ended). The server
sends a push notification to a different device they own. They tap the
notification to "rejoin." On iOS, the OS launches the app (possibly with
`NotificationService` already running); the deep-link handler creates a
fresh session while the previous session is still in `cleanupCall()` —
peer connections still tearing down. Server briefly sees two attached
clients with the same identity, or a duplicate join races a leave.

Two related races:

- **iOS** (`client-ios/Sources/Core/Push/JoinSnapshotFeature.swift`):
  notification arrives during cleanup; deep-link handler doesn't wait for
  cleanup.
- **Android** (`client-android/.../push/PushNotificationHandler.kt:38-72`):
  no check that user is already in a *different* room — push to room B
  while in room A starts a parallel session.

Server side, `POST /api/push/notify` authorizes the *sender* CID in the request
body (`server/push.go:779`) and then sends to subscribed endpoints for the room,
excluding the sender endpoint. It does not target an individual suspended peer.
Therefore "pushes to suspended participants are rejected" is not currently
established by the code path; the push-specific risk is lifecycle duplication on
the receiving device, not target authorization.

### Suggested fix

Two changes:

**A. SDK: serialize call lifecycle.**

In `CallManager` (iOS, Android), wrap session-lifecycle transitions
(start, leave, replace) in an actor / mutex. A `start` while `leave` is
in flight must wait for `leave` to complete. The leave operation should be
fast in any case since it's a fire-and-forget signaling message plus
local PC teardown.

**B. SDK: deep-link handling guard.**

Before processing a push-driven deep link, check `CallManager.activeSession`.
If it points to a *different* room, present a "switch call?" prompt rather
than starting a parallel session. If it points to the *same* room:
- If the session is `connected`, no-op and bring UI to foreground.
- If the session is `reconnecting` or `suspended`, trigger an **immediate**
  forced reconnection attempt (skip existing backoff/timers) to reduce the
  perceived time-to-reattach.

### Tests

- iOS UI test: push deep link arrives during in-progress leave. Assert
  no overlap.
- Android: same flow.
- Server regression: push-notify still authorizes only a currently active
  sender CID unless product requirements explicitly allow suspended senders.

### Risk

Medium. The lifecycle serialization needs to be carefully sequenced against UI
state to avoid deadlocks (e.g. UI waiting for cleanup, cleanup waiting for UI
dismiss). A server authorization change should be treated as a separate product
decision, not as part of this race fix.

---

## 11. `end_room` doesn't notify suspended peers

**Status (2026-04-25):** Landed end-to-end. `Hub.tombstones` records ended
rooms with a 5-minute TTL; reconnect attempts presenting valid token
authority for a tombstoned RID receive a structured
`error{code:"ROOM_ENDED", reason:"ended_by_host"}`. SDKs map ROOM_ENDED to
the existing terminal `roomEnded` error and clear persisted reconnect
state.

### Symptom

Host calls `end_room` while peer P is suspended. Server stops P's
hard-eviction timer (`server/signaling.go:836-839`) and sends `room_ended` only to
*active* clients (`server/signaling.go:861`). P reconnects later, finds the room
gone, gets a "room not found" error, and treats it as a transient network
failure — looping reconnect attempts indefinitely.

### Root cause

Suspended clients have no transport, so they can't be sent a real-time
message. The server has no mechanism to deliver a "room ended in your
absence" tombstone. Today they silently learn the room is gone only by
trying to reconnect and failing.

### Suggested fix

Two options, in increasing order of effort:

**Minimal (XS):** When a suspended participant attempts to rejoin a room
that no longer exists because it explicitly ended, return a structured
`ROOM_ENDED` error rather than the generic "room not found" / fresh join path.
The SDK treats `ROOM_ENDED` differently from a transient failure — it terminates
the session immediately, surfaces a normal "call ended" UI, and clears
persisted recovery state.

**Better (S):** Add a short-lived (5-minute) tombstone map on the server
keyed by `rid` recording the reason a room no longer exists (`ended_by_host`,
`hard_evicted`, `host_left`). Reconnect attempts targeting a tombstoned
RID return the tombstone reason. The map is bounded by TTL and is
mutexed with `h.mu`.

The tombstone approach also benefits #2. It tells the server when valid
reconnect credentials should *not* recover the original CID because the room
ended intentionally.

### Tests

- Server unit: end_room then rejoin returns `ROOM_ENDED`.
- SDK: receiving `ROOM_ENDED` clears recovery state and emits clean
  termination.

### Risk

Very low. Pure server-side addition.

---

## 12. WS↔SSE failover creates split SIDs server-side

**Status (2026-04-25):** Not started — Phase 3 (depends on #7 + #15
hardening shipping first).

### Symptom

WS transport fails; SDK falls back to SSE. SSE has its own client-generated SID
(`SseSignalingTransport.swift:115-118`, Android mirrored; web `SseTransport`
creates its own SID). Server still has the WS-side session in its 6 s grace
window. For a brief moment the server can have an old WS client draining while
the new SSE transport attempts to join and reclaim the same CID. Peers can see
momentary `active -> suspended -> active` flicker.

### Root cause

The SDKs do not have a cross-transport session identity. WebSocket SID is
server-generated and not exposed through the web transport, while SSE SID is
client-generated per SSE transport instance. The previous "derive the same SID
for WS and SSE" idea is not implementable without a protocol change because the
client does not have a stable WS SID to reuse.

### Suggested fix

Add an explicit cross-transport resume handshake rather than trying to reuse
transport SIDs:

- Introduce a participant-bound `transportResumeId` or `connectionGeneration`
  issued in `joined` and renewed on every successful transport rebind. SDKs
  persist it only in memory with `{cid, reconnectToken}` for the current call.
- On WS->SSE fallback, open SSE as a pending unauthenticated transport (same
  pending-SSE model as #7), then send `resumeTransport` with `{rid, cid,
  reconnectToken, transportResumeId}`. The server validates the tuple and
  atomically replaces the old transport without broadcasting suspended state.
- If validation fails, fall back to the normal reconnect join path from #2. Do
  not treat a plain duplicate SID as authority.

This must ship with #7 and #15 so the resume path is both authenticated and
replay-resistant.

### Tests

- Integration: simulate WS failure with active call; assert peer sees no
  `suspended` flicker in `room_state`.
- Server unit: valid `resumeTransport` atomically swaps the attached transport
  before broadcasting any membership change.
- Server unit: stale or replayed `transportResumeId` is rejected and cannot
  replace the active transport.

### Risk

Medium. This is a protocol addition, not a local SID tweak. Without #7 and #15,
it would create a new takeover path.

---

## 13. Buffered signaling payloads become stale across reconnect

**Status (2026-04-25):** Server-side support already in place via #1's
dirty-pair tracking and #4's epoch-stamped snapshots. SDK enqueue tagging
(timestamp + epoch at enqueue, discard at flush, and tail-drop on ICE
candidate overflow) is the remaining piece — staged for the same MediaEngine
slice that wires up #4's gate.

### Symptom

The SDKs buffer offer / answer / ICE messages while waiting for ICE servers
to load (`SerenadaSession.kt:886-954`, mirrored on iOS / Web). If a
disconnect happens between buffering and flush, the post-reconnect flush
applies an offer that was generated against the *old* peer map. SDP state
gets confused — possibly a glare condition where both sides have a
local offer.

ICE candidate buffers (cap 50 per peer per
`WebRtcResilienceConstants`) silently drop oldest candidates on overflow.
After a long disconnect, path discovery may be incomplete.

### Root cause

The buffer flush is unconditional on flush-time validity. There's no
TTL or epoch check; a 90-second-old offer is treated identically to a
fresh one.

### Suggested fix

1. **Timestamp-tag every buffered payload** at enqueue time. On flush, if a local
   outbound offer is older than `2 * offerTimeoutMs` (~16 s), discard that
   payload, locally roll back any still-pending local offer state if possible,
   and mark the peer pair for fresh negotiation after the authoritative room
   snapshot (#1, #4). Do not send a fake "rollback answer"; WebRTC rollback is a
   local operation, not a peer-visible SDP answer type.
2. **Tag with the room-state epoch** (#4) at enqueue time. On flush, if
   the payload's epoch no longer matches the authoritative post-reconnect
   snapshot, discard the payload and mark the pair dirty for fresh negotiation.
   This catches "the membership changed while I was queued" without silently
   leaving the sender stuck forever.
3. **Cap ICE candidate buffer with a tail-drop rather than head-drop**
   for the freshness case (newer candidates are more likely to reflect
   the current network), but keep head-drop on overflow during normal
   flow. In practice this is a non-issue if #4 is in place — we won't be
   queueing 50+ candidates for a peer we don't intend to talk to.

### Tests

- SDK unit: enqueue offer at epoch N, advance epoch, flush — assert the stale
  payload is discarded and fresh negotiation is scheduled for that pair.
- SDK unit: enqueue offer 30 s ago, flush — assert local pending-offer state is
  cleared/rolled back and fresh negotiation is scheduled; no invalid SDP answer
  is sent.

### Risk

Low to medium. Discarding stale payloads is safer than applying them, but only
if the SDK also schedules deterministic fresh negotiation so the peer does not
wait forever.

---

## 14. Android foreground-service lifecycle drift

**Status (2026-04-25):** Not started — Phase 3.

### Symptom

`SerenadaSession` crashes (uncaught exception, OOM in WebRTC native code,
etc.) without going through `leaveCall()`. `CallService` stays foreground
indefinitely. The `mediaProjectionForegroundActive` flag (used to guard
screen-share start) stays set. The next call attempt fails because the
guard thinks projection is already active. The user sees a persistent
"in call" notification with no actual call.

### Root cause

`CallService` is started and stopped from `CallManager` based on
session lifecycle, but there's no observed link between session state
and service state. If the session dies abnormally, the service is
orphaned (`client-android/.../service/CallService.kt:21-41`,
`client-android/.../call/CallManager.kt:185-189`).

### Suggested fix

1. `CallManager` should observe `SerenadaSession.callPhase`. On any
   transition to `Failed` or `Ended`, unconditionally stop the service.
2. `CallService.onStartCommand` should re-validate that there is an
   active session on every restart. If `CallManager.activeSession` is
   null, immediately stop the service (handles the "system restarted
   us with `START_STICKY`" case).
3. `mediaProjectionForegroundActive` should be reset on
   `CallService.onDestroy` defensively, even if the projection callback
   didn't fire.

This is purely Android host-app code; no SDK or protocol changes.

### Tests

- Android instrumentation: throw from `SerenadaSession` callback; assert
  service stops within 1 s.
- Manual: kill app process during call, relaunch, start new call,
  verify projection guard does not block.

### Risk

Low. Additive guards on a host-app lifecycle.

---

## 15. Reconnect-token replay can reclaim active CIDs

**Status (2026-04-25):** Token expiry landed. Reconnect tokens are now
HMAC-bound to `(cid, rid, expiresAt)` with format `<hmac>.<expiresAtUnix>`,
TTL = `suspendHardEvictionTimeout`. `validateReconnectToken` returns
`(ok, expired)` and the join handler maps both to
`INVALID_RECONNECT_TOKEN`. Coupling token authority to the
`transportResumeId` proof from #12 — so a leaked token alone cannot
replace an active transport — is Phase 2 work and ships together with #7
and #12.

### Symptom

An attacker who obtains `{rid, cid, reconnectToken}` can join the room with
`reconnectCid=cid` and a valid token. If the victim still has an active
transport, the server treats it as an active ghost, detaches the old client, and
reattaches the attacker to the victim's participant slot. Peers see the same CID
continue, but signaling messages now go to the attacker-controlled transport.

### Root cause

`reconnectToken` is an HMAC over `cid|rid` (`server/signaling.go:43-71`). It is
not bound to a transport generation, issued-at timestamp, device key, or active
session epoch. `handleJoin` validates the token and, when the participant has an
active client, evicts that client as a fast-reconnect ghost (`server/signaling.go:571-591`).

That active-ghost path is useful for legitimate fast reconnects during the WS /
SSE grace window, but the token is reusable for the lifetime of the room. A
leaked token is therefore a participant takeover credential, not only a recovery
hint.

### Suggested fix

Make reconnect authority short-lived and separate it from active transport
replacement:

1. Add `expiresAt` to reconnect authority. Issue reconnect tokens as HMAC over
   `rid|cid|expiresAt`, or store opaque random tokens in durable server state
   with an expiry. Do not rely on in-memory-only opaque tokens if #2 needs to
   recover CIDs after server memory loss.
2. Reconnect-token authority alone may reattach only a participant with no
   active signaling transport. It must not replace an active transport. Active
   transport replacement requires the short-lived `transportResumeId` proof from
   #12, and only during the transport grace window.
3. Do not rotate tokens with a reusable wall-clock grace window. If we choose to
   rotate reconnect tokens after reattach, use a two-phase handoff:
   `nextReconnectToken` is sent in `joined`, the SDK persists it, then sends an
   authenticated `reconnectTokenAck` on the same transport. Only after that ack
   does the server promote the new token and invalidate the old one. If the
   client crashes before ack, the old token remains the current token; after ack,
   the old token is not accepted.
4. Give reconnect tokens a TTL no longer than `suspendHardEvictionTimeout` and
   reject expired tokens with `INVALID_RECONNECT_TOKEN`.
5. Avoid logging reconnect tokens, and treat stored tokens like credentials on
   clients.

This complements #7. Fixing SSE SID hijack without hardening reconnect tokens
leaves a different credential replay path that can produce the same takeover.

### Tests

- Server unit: an active client cannot be replaced with reconnect token alone;
  `transportResumeId` proof is required inside the transport grace window.
- Server unit, if token rotation is implemented: an old token cannot reattach
  after `nextReconnectToken` is acknowledged and promoted.
- Server unit, if token rotation is implemented: if `nextReconnectToken` is
  issued but not acknowledged before transport loss, the old token remains valid
  and the client is not stranded.
- Server unit: expired reconnect token returns `INVALID_RECONNECT_TOKEN`.
- SDK unit, if token rotation is implemented: refreshed reconnect token is
  persisted before `reconnectTokenAck` is sent.

### Risk

Medium. This changes the reconnect contract and needs a staged rollout so older
SDKs do not get stranded without a usable token. This does not make a leaked
current reconnect token harmless for a suspended/no-transport participant; it
removes active-transport takeover, bounds the token by TTL, and keeps stronger
device-bound proof as a future hardening option if the product needs it.

---

## 16. Ephemeral content state lost while peer is suspended

**Status (2026-04-25):** Landed end-to-end. `roomParticipant.ContentState`
captures the latest `{active, contentType, updatedAtMs, epoch}` at
`handleRelay` time and the value is included in every `joined` /
`room_state` participant entry. SDKs parse `ParticipantContentState` on
all three platforms; UI reconciliation (showing a "loading media" state
while the SDP catches up to a stale-but-active share) is a follow-up that
sits on top of the existing parser.

### Symptom

Peer A starts screen share or switches into a content camera mode while peer P is
suspended. The server relays `content_state`, but P has no transport, so the
message is dropped. P reconnects successfully and keeps/rebuilds media, but its
layout still reflects the last content state it saw before suspension. A screen
share can appear missing, or a stopped share can appear stuck until the next
manual content-state change.

### Root cause

`content_state` is routed through the same relay path as SDP/ICE
(`server/signaling.go:464-466`, `handleRelay`). Unlike participant audio/video
state, the server does not store latest content state on the participant record
or include it in `joined` / `room_state`. The existing reconnect model preserves
participant identity but not ephemeral UI state.

### Suggested fix

Treat content state as latest-state room metadata, not best-effort relay only:

1. Store latest content state per participant on the server
   (`active`, `contentType`, optional timestamp / room-state epoch).
2. Include that latest content state in `joined` and `room_state` participant
   entries. Prefer extending the existing authoritative snapshots before adding
   a separate `room_content_state` message; if a separate message is introduced,
   define its ordering and payload in `docs/serenada_protocol_v1.md` first.
3. On `content_state` relay while a target is suspended, collapse to the latest
   value rather than enqueueing every transition. Latest wins.
4. Clear a participant's content state on explicit leave, hard eviction,
   `room_ended`, and when the participant sends `content_state.active=false`.
5. SDKs should reconcile local diagnostic/UI content state from room state after
   reconnect. **Critically**, the UI must reconcile the signaling `content_state`
   with the actual `RTCPeerConnection` media state. If `content_state` indicates an
   active share but no corresponding track is receiving media yet, the UI should
   display a "Loading media..." or "Reconnecting presentation..." indicator rather
   than an empty frame, to prevent aggressive state-sync from causing a broken UX
   while the underlying SDP Offer/Answer completes.

### Tests

- Server unit: content_state is stored, updated, and cleared on leave/evict.
- Integration: P suspends, A starts screen share, P reconnects; P receives the
  active content state without A toggling share again.
- Integration: P suspends, A starts then stops screen share, P reconnects; P sees
  inactive content state.
- SDK unit: room_state content metadata updates local layout state.

### Risk

Low to medium. The protocol addition is additive, but UI reconciliation must be
careful not to fight local transient state during active content toggles.

---

## Cross-cutting changes summary

Several fixes share infrastructure. To minimize churn:

- **Server changes:**
  - Add `roomStateEpoch` integer to `Room`, increment on every
    membership-mutating operation (#4).
  - Add dirty negotiation-pair tracking for SDP/ICE missed while a peer is
    suspended (#1, #13).
  - Accept valid reconnect credentials as authority to recover the requested CID
    when no room tombstone exists (#2).
  - Track recent peer-reported media-liveness hints for suspended participant
    eviction decisions (#3).
  - Add `tombstones` map to `Hub` for ended rooms (#11).
  - Add `POST /api/leave` for explicit terminal leave only (#5).
  - Drop `sid` from access-log query strings (#7).
  - Add pending unauthenticated SSE sessions plus authenticated `resumeSse`
    binding (#7).
  - Add expiring reconnect tokens and reject active transport replacement unless
    the #12 transport-resume proof is valid; add no-grace token rotation only if
    rotation is needed (#15).
  - Add transport-resume proof for WS/SSE failover (#12).
  - Persist latest participant content state for reconnect state sync (#16).

- **Protocol additions:**
  - `joined.payload.reconnect: "reattached" | "recovered" | "fresh"` (#2).
  - `joined.payload.epoch`, `room_state.payload.epoch` (#4).
  - Full authoritative `room_state` snapshot after every successful reconnect,
    even when the epoch did not advance (#4).
  - `target_suspended` / dirty-pair renegotiation signal for missed SDP/ICE (#1,
    #13).
  - Peer media-liveness hint used only for cleanup decisions (#3).
  - New `resumeSse` message type for pending SSE auth (#7).
  - New `resumeTransport` / `transportResumeId` fields for cross-transport
    failover (#12).
  - New `ROOM_ENDED` error code (#11).
  - New dedicated SDK error mapping for `INVALID_RECONNECT_TOKEN` (#2, #15).
  - Additive content-state metadata in `joined` / `room_state` (#16).

- **Shared SDK constants** (must update
  `scripts/check-resilience-constants.mjs` parity check):
  - `peerSuspendedUiTimeoutMs = 30_000` (#3).
  - `suspendHardEvictionTimeoutMs = 600_000` (#6).
  - `epochResyncTimeoutMs = 5_000` (#4) ✅ landed.
  - `turnRefreshSafetyMarginMs = 60_000` (#9).
  - `foregroundForcePingTimeoutMs = 2_000` (#8) ✅ landed.

- **Shared SDK responsibilities:**
  - Persist `{roomId, cid, reconnectToken, lastSeenEpoch}` and surface
    rejoin prompt (#5).
  - Preserve requested CID on `reattached` / `recovered` reconnect outcomes and
    avoid closing media-active peer connections just because signaling recovered
    from fresh server memory (#2).
  - Clear persisted reconnect state and surface session-expired on
    `INVALID_RECONNECT_TOKEN` (#2).
  - Gate ICE restart on an authoritative post-reconnect `room_state` snapshot,
    not on `epoch > previous` (#4).
  - Track TURN credential expiry independently of signaling state (#9).
  - Keep media-active peer connections alive across signaling loss; use the
    suspended timer for presentation only (#3).
  - Send media-liveness hints for remote CIDs with current inbound media when
    signaling is available (#3).
  - Reconcile content-state UI from `joined` / `room_state`, not only live
    `content_state` peer messages (#16).

## Suggested ordering

Phase 1 (correctness; ship in order):

1. #2 (identity-preserving reconnect) + #11 (room tombstone) — this sets the
   terminal-vs-recoverable boundary and prevents duplicate caller identities.
2. #15 (reconnect-token replay) + #7 (SSE hijack) — security; ship together so
   identity-preserving recovery does not widen the takeover surface.
3. #4 (authoritative post-reconnect snapshot) — clients need a reliable sync
   point before any renegotiation.
4. #3 (media-first suspension handling) + #6 (explicit suspension state) —
   keep active media alive while bounding dead-peer presentation and server GC.
5. #1 (dirty-pair renegotiation) + #13 (stale buffered payload handling) —
   recover missed SDP/ICE with fresh negotiation instead of SDP mailboxes.
6. #16 (content-state sync) — preserve latest UI/content metadata across
   suspension.

Phase 2 (UX & lifecycle):

7. #5 (process death recovery) — bigger; depends on #2 / #11 having
   landed.
8. #8 (iOS background lifecycle).
9. #10 (push-to-rejoin races).

Phase 3 (cleanup):

10. #9 (TURN expiry).
11. #12 (WS↔SSE authenticated transport resume) — needs #7 / #15 already
    shipped.
12. #14 (Android service lifecycle).

## What this does *not* cover

Out of scope for this document, listed for visibility:

- Multi-device session handoff (a user with two devices wanting to move
  a live call between them).
- Capacity admission control during eviction-in-flight (the tail end of
  #3 — "C tries to join while A is being hard-evicted"). Today the
  capacity check is conservative and will reject; a fairer scheme would
  require the server to publish a "slot reserving evictor" state, which
  is more work than it's worth for 1:1 / small-room calls.
- TURN allocation lifetime separate from credential lifetime — coturn
  manages its own allocation refresh; this is a coturn-side concern, not
  a signaling-server concern.
- Race-condition hardening of `replaceClient` itself
  (`server/signaling.go:364-398`). This was flagged as a potential map-write
  race during the audit but is well-protected by the room mutex on all
  current paths. If we ever take `replaceClient` outside the room lock
  it deserves a fresh look.

## Change Log

### 2026-04-29 — Phase 2: media-liveness emission completes #3

- **New shared constant**: `mediaLivenessIntervalMs = 10_000` added to
  Web, Android, and iOS (validated by
  `scripts/check-resilience-constants.mjs` — 23 shared constants now
  match). Mirrors the cadence under the server's 30s freshness window.

- **Per-platform inbound-bytes detection**: Each SDK samples cumulative
  `inbound-rtp.bytesReceived` per remote CID once per interval and
  builds the list of "flowing" CIDs (current sample > previous):
  - Web: `MediaEngine.getInboundFlowingCids()` reads each peer's
    `RTCPeerConnection.getStats()` and aggregates inbound-rtp bytes.
  - Android: new `PeerConnectionSlotProtocol.collectInboundBytes`
    callback wraps `pc.getStats { ... }`.
  - iOS: new `PeerConnectionSlotProtocol.collectInboundBytes` callback
    wraps `peerConnection.statistics { ... }`.

- **Periodic broadcast in `SerenadaSession`**: each SDK runs a
  `mediaLivenessIntervalMs` tick that calls
  `signalingProvider.broadcast("media_liveness", { cids })` whenever
  the flowing list is non-empty AND the local transport is connected.
  Timer starts when the call reaches `inCall` (at least one remote
  peer); ticks no-op while disconnected (baseline samples are preserved
  so the next post-reconnect tick can detect flow). Stopped on session
  reset/destroy.

- **Tests**:
  - Web `SerenadaSession.test.ts` — 4 new tests covering: broadcasts
    on flowing CIDs, skips when no flow, pauses while disconnected and
    resumes after reconnect, stops after destroy. 326 total (was 322).
  - Android `SessionMediaLivenessTest.kt` — 3 Robolectric tests
    covering the same matrix.
  - iOS `SessionMediaLivenessTests.swift` — 3 XCTest cases covering
    the same matrix using `FakeSessionClock` advances.
  - Server tests still green; resilience-constants and version-parity
    checks green.

- **Docs**: `docs/resilience-failure-modes.md` priority table for #3
  flips to ✅, section status updated, change-log entry added.

### 2026-04-29 — Phase 2: suspended-state surface (#6) + remote presentation timer (#3 partial)

- **New shared constants**: `peerSuspendedUiTimeoutMs = 30_000` and
  `suspendHardEvictionTimeoutMs = 600_000` added to Web
  (`client/packages/core/src/constants.ts`), Android
  (`client-android/.../call/WebRtcResilienceConstants.kt`), and iOS
  (`client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift`).
  `scripts/check-resilience-constants.mjs` now validates 22 shared constants.

- **#6 — `signalingState` surface**: All three SDKs now expose a richer
  `signalingState` field on `CallState` alongside the existing
  `connectionStatus`. Variants: `connected`,
  `reconnecting{attempt, nextRetryAtMs}`,
  `suspended{suspendedSinceMs, estimatedHardEvictionAtMs}`,
  `failed{reason}`. Mid-call transport drops produce `suspended` with a
  hard-eviction estimate computed from `suspendHardEvictionTimeoutMs`
  (mirroring the Go server's `suspendHardEvictionTimeout`). Pre-join
  drops surface as `reconnecting`. Terminal errors map to `failed`.

- **#3 partial — per-remote-CID presentation timer**: When a remote
  participant transitions to `signalingStatus="suspended"` in
  `room_state`, each SDK starts a per-CID timer of
  `peerSuspendedUiTimeoutMs`. On expiry the SDK flips a new
  `presumedLost: boolean` field on the participant so call UIs can
  move them out of the active grid. The peer connection itself stays
  open — this flag is presentation-only. Cancellation is automatic
  when the peer reattaches as active or leaves the room. The
  remaining piece of #3 (active SDKs periodically emitting
  `media_liveness` to the server so a peer whose media is still
  flowing isn't hard-evicted) is queued for the next slice.

- **iOS rename**: The internal WebRTC mirror previously called
  `SignalingState` is now `RtcSignalingState` so the new Phase 2
  surface can take the unqualified name (matching Web/Android).
  `CallDiagnostics.rtcSignalingState` keeps its name.

- **Tests**:
  - Web `SerenadaSession.test.ts` — 5 new tests covering presumedLost
    flip on timer expiry, cancellation on reattach, cancellation on
    peerLeft, suspended/connected transitions, failed mapping.
  - Android `SessionSuspendedSurfaceTest.kt` — 4 Robolectric tests
    covering the same matrix using the new
    `simulateRoomStateUpdatedWith` test helper.
  - iOS `SessionSuspendedSurfaceTests.swift` — 4 XCTest cases mirroring
    Android using `FakeSessionClock` advances.
  - Web 320 (was 315), Android `:serenada-core:testDebugUnitTest` green,
    iOS `xcodebuild test` green, resilience-constants check green.

### 2026-04-29 — Phase 2 partial: SDK dirty-pair renegotiation (#1)

- **Provider events**: Each SDK gains two new typed provider events
  surfacing the Phase 1 server messages:
  - `negotiationDirty { withCid: String }` — server tells an active peer
    that a previously-suspended peer reattached and there was pending
    negotiation traffic to it during the suspension.
  - `relayFailed { reason, targets, of? }` — server tells a sender it
    could not deliver a relay because the target had no transport.

- **Web SDK** (`SignalingProvider.ts`, `SerenadaServerProvider.ts`,
  `SerenadaSession.ts`, `MediaEngine.ts`): `SerenadaServerProvider` parses
  both messages (parsers were already present from Phase 1, dropped
  silently before) and emits the new events. `SerenadaSession` consumes
  `negotiationDirty` to call `MediaEngine.scheduleDirtyPairRestart(cid)`,
  which routes through the existing per-CID `scheduleIceRestart` only when
  the local peer is the designated offerer, so all existing glare/cooldown
  guards apply without letting non-offerers create fallback offers.

- **Android SDK** (`SignalingProvider.kt`, `SerenadaServerProvider.kt`,
  `SerenadaSession.kt`): `Listener` gains `onNegotiationDirty` and
  `onRelayFailed` (default no-op). `SerenadaServerProvider` dispatches
  via the existing `toNegotiationDirtyPayload` / `toRelayFailedPayload`
  parsers. `SerenadaSession.buildProviderListener` consumes
  `onNegotiationDirty` by calling
  `peerNegotiationEngine.scheduleIceRestart(cid, "negotiation-dirty", 0)`.

- **iOS SDK** (`SignalingProvider.swift`, `SerenadaServerProvider.swift`,
  `SerenadaSession.swift`): `SignalingProviderDelegate` gains
  `signalingProviderDidReceiveNegotiationDirty` /
  `signalingProviderDidReceiveRelayFailed` (default no-op extensions).
  `SerenadaServerProvider` dispatches using the already-existing
  `NegotiationDirtyPayload` / `RelayFailedPayload` parsers (previously
  unreachable). `SerenadaSession.handleProviderNegotiationDirty` calls
  `peerNegotiationEngine.scheduleIceRestart(remoteCid:reason:delayMs:)`.

- **`relay_failed` behavior**: All three SDKs log it but do NOT
  immediately act on it. The same dirty-pair condition will surface as
  `negotiation_dirty` once the suspended target reattaches; acting on
  `relay_failed` would duplicate the trigger. A future slice can
  optimize the in-flight offer-timeout for suspended targets.

- **Tests**:
  - Web `SerenadaSession.test.ts` — 2 new tests: `negotiation_dirty`
    schedules per-CID ICE restart; `relay_failed` does not.
  - Android `SessionDirtyPairTest.kt` — 3 new tests covering both events
    + unknown-CID no-op.
  - iOS `SessionDirtyPairTests.swift` — 3 new tests mirroring Android.
  - Web 312 (was 310), Android `:serenada-core:testDebugUnitTest` green,
    iOS `xcodebuild test` green.

### 2026-04-28 — Phase 2 partial: SDK post-reconnect snapshot gate (#4)

- **Shared resilience constants**: New `epochResyncTimeoutMs = 5_000` added
  to all three SDKs (`client/packages/core/src/constants.ts`,
  `client-android/.../call/WebRtcResilienceConstants.kt`,
  `client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift`)
  and surfaced in `scripts/check-resilience-constants.mjs`'s parity check
  (now 20 cross-platform constants).

- **Web SDK** (`SerenadaSession.ts`): Adds `pendingPostReconnectResync`
  state and a 5s timeout. On signaling reconnect with an existing room,
  defers `MediaEngine.handleSignalingReconnect()` instead of firing
  immediately. The deferred call runs when `handleRoomStateUpdated`
  processes the post-reconnect `room_state` snapshot, or after
  `EPOCH_RESYNC_TIMEOUT_MS` as a graceful-degradation fallback to pre-#4
  behavior. Existing reconnect test updated; two new tests cover the gate
  (defer + flush + timeout fallback + no double-fire).

- **Android SDK** (`SerenadaSession.kt`): Mirrors the Web shape. Reconnect
  handler arms the gate via a `Handler.postDelayed` callback;
  `onRoomStateUpdated` flushes the gate via the new
  `flushPostReconnectResync` helper. Test-only `isPostReconnectResyncPending`
  / `postReconnectResyncFireCount` accessors expose state for assertions
  (Robolectric-driven `SessionPostReconnectGateTest`, 4 tests).

- **iOS SDK** (`SerenadaSession.swift`): Mirrors the same shape via a
  `Task { try? await clock.sleep(...) }` timeout pattern aligned with the
  existing `scheduleReconnect` helper. `internal var
  isPostReconnectResyncPending` / `postReconnectResyncFireCount` for tests.
  Pre-existing `testSignalingReconnectDuringInCallTriggersIceRestart` updated
  to provide a snapshot after reconnect; new `SessionPostReconnectGateTests`
  covers all four cases.

- **Behavior**: A reconnect with stale peer-map state (e.g. a peer left
  during the outage) now waits for the server's authoritative snapshot
  before scheduling ICE restart, so offers go to confirmed-present peers
  only. If the server fails to send a snapshot within 5s, the SDK falls
  back to firing against the last-known map — strictly no worse than
  pre-#4 behavior.

- **Tests / CI**: Web 310 (was 308), Android `:serenada-core:testDebugUnitTest`
  green, iOS xcodebuild green. `check-resilience-constants.mjs` reports 20
  matching constants. `check-version-parity.mjs` green.

### 2026-04-26 — Phase 2 partial: foreground / Doze force-ping (#8)

- **Shared resilience constants**: New
  `foregroundForcePingTimeoutMs = 2_000` added to all three SDKs
  (`client/packages/core/src/constants.ts`,
  `client-android/.../call/WebRtcResilienceConstants.kt`,
  `client-ios/SerenadaCore/Sources/Call/WebRtcResilienceConstants.swift`)
  and surfaced in `scripts/check-resilience-constants.mjs`'s parity check
  (now 19 cross-platform constants).

- **iOS SDK**: `SessionSignaling` gains a `forcePingWithDeadline(timeoutMs:)`
  hook with a default no-op extension; `SignalingClient` implements it by
  sending a synthetic ping, recording a monotonic `pongSeq` snapshot, and
  arming a deadline `Task` that calls `handleTransportClosed` with reason
  `foreground_force_ping_timeout` on miss. `SignalingProvider` gets a
  matching public `forceReconnectIfStale(timeoutMs:)` (default no-op) so
  custom providers can opt out; `SerenadaServerProvider` forwards to the
  signaling client. `SerenadaSession` subscribes to
  `UIApplication.didEnterBackground` / `willEnterForeground` notifications
  in `startNetworkMonitoring`, tracks `lastBackgroundedAtMs`, and on
  foreground transitions where the app was backgrounded for
  ≥ `foregroundResumeMinBackgroundMs = 5_000` and the call is in
  `joining` / `waiting` / `inCall`, calls
  `signalingProvider.forceReconnectIfStale(timeoutMs:)`. Observers are
  removed in `deinit`.

- **Android SDK**: `SessionSignaling` gains a default-no-op
  `forcePingWithDeadline(timeoutMs: Long)`; `SignalingClient` implements it
  with the same monotonic `pongSeq` discipline and posts a `Runnable`
  through the session handler that closes the transport with
  `foreground_force_ping_timeout` on miss. `SignalingProvider` gains a
  default-no-op `forceReconnectIfStale(timeoutMs:)`;
  `SerenadaServerProvider` forwards. `SerenadaSession` registers an
  `Application.ActivityLifecycleCallbacks` (no new
  `lifecycle-process` dependency) that counts started Activities;
  transitions from 0 → 1 are treated as foreground and from 1 → 0 as
  background. Same 5_000 ms minimum-background threshold as iOS. The
  observer is registered/unregistered alongside the existing connectivity
  network callback.

- **Tests**:
  - iOS `SignalingClientForcePingTests` (6 tests, xcodebuild green) covers
    ping emission, deadline-miss closes the transport, pong-arrival
    cancels the close, no-op while disconnected, repeated calls cancel
    earlier deadlines, and post-close calls stay no-op.
  - Android `SignalingClientForcePingTest` (6 tests, Robolectric, gradle
    green) mirrors the iOS matrix with `ShadowLooper` time advancement.
  - `scripts/check-resilience-constants.mjs` and
    `scripts/check-version-parity.mjs` green.

- **Deferred (still Phase 2)**:
  - **#6 explicit suspension state surface** — needs the
    `peerSuspendedUiTimeoutMs` / `suspendHardEvictionTimeoutMs` /
    `epochResyncTimeoutMs` shared constants and the
    `SignalingState.suspended` model on top of the constants this slice
    added. Tracked for a follow-up slice.
  - **#7 SSE transport hijack** — server `TODO(#7)` still in place; the
    SDK `resumeSse` envelope and pending-session model is the remaining
    piece.
  - **#10 lifecycle serialization on `CallManager`** and **#10 deep-link
    guard ("switch call?" prompt)** — both deferred to product/UX work
    on the host apps.

### 2026-04-26 — Phase 2 partial: process-death recovery (#5)

- **Server (`server/leave_handler.go`)**: New `POST /api/leave` endpoint
  for explicit terminal-leave intent. Validates `{rid, cid,
  reconnectToken}` against the existing HMAC machinery (rejects expired
  or unsigned tokens with 401), then calls the new `Hub.evictByLeave`
  which removes the participant immediately, drops dirty/liveness state,
  rebroadcasts `room_state` (or GCs the room when empty), and is
  idempotent. Rate-limited at 12 req/min/IP via the existing
  `rateLimitMiddleware` and gated by `enableCors`. Tests cover the happy
  path, missing-field rejection, bogus and expired tokens, idempotency,
  and method-not-allowed.

- **All three SDKs**: Persistent recovery state lands per the doc.
  Web → `sessionStorage` (per-tab; survives reload, lost on tab close),
  Android → app-private `SharedPreferences`, iOS → injectable
  `UserDefaults` (defaults `.standard`; host apps can pass an
  app-group-scoped store before opening any session). Each SDK
  surfaces a public `getRecoverableSession()` / `discardRecoverableSession()`
  pair on `SerenadaCore`. The active session writes the record on every
  successful `joined` (with the original `sessionStartTs` preserved
  across reconnects) and clears it on clean leave, `room_ended`, or
  `INVALID_RECONNECT_TOKEN`. Records expire on the wire-side
  `reconnectTokenTTLMs` (Web) or the matching `suspendHardEvictionTimeout`
  (Android/iOS, where the TTL was not previously plumbed).

- **iOS-specific**: `JoinedEvent` gains `reconnectToken` and
  `reconnectTokenTTLMs` fields so the session — which previously had no
  visibility into provider-internal token state — can populate the
  recovery record from the wire payload directly.

- **Tests**:
  - Server: `server/leave_handler_test.go` (6 tests) covers token
    happy/sad paths and idempotency.
  - Web: `recoveryStorage.test.ts` (7 tests) covers round-trip,
    malformed JSON, expired entries, and missing-field rejection.
  - Android: `RecoveryStorageTest` (5 tests, Robolectric) covers the
    same matrix.
  - iOS: `RecoveryStorageTests` (6 tests) covers the same matrix
    plus injected-clock expiry semantics.
  - All four suites green (server Go, Web vitest 308, Android gradle,
    iOS xcodebuild 252).

- **Deferred (still Phase 2)**:
  - **#8 iOS scene-phase observer + foreground force-ping** — needs
    `SignalingClient`-level "fresh ping with 2s deadline" plumbing the
    SDK does not yet expose. Tracked for a follow-up slice.
  - **#10 lifecycle serialization on `CallManager`** — current
    `@MainActor` class serializes synchronous code but has interleave
    holes across `await` suspension points. Needs a Task-chain
    refactor; deferred.
  - **#10 deep-link guard ("switch call?" prompt)** — host-app UI
    territory; deferred to product/UX work.

### 2026-04-25 — Phase 1 implementation landed

- **Server (`server/signaling.go`, `server/sse.go`)**:
  - Added `Hub.tombstones` (5-minute TTL) populated by `handleEndRoom`;
    reconnect attempts presenting valid token authority for a tombstoned
    RID receive `error{code:"ROOM_ENDED", reason:"ended_by_host"}`.
  - Reworked `handleJoin` to surface
    `joined.payload.reconnect: "fresh" | "reattached" | "recovered"` and
    to recreate the participant record under the requested CID when a
    valid reconnect token's room is gone.
  - Reformatted reconnect tokens as
    `hex(HMAC-SHA256(secret, cid|rid|expiresAt)).<expiresAtUnix>` with TTL
    capped at `suspendHardEvictionTimeout`; `validateReconnectToken`
    returns `(ok, expired)` and old-format tokens are rejected.
  - Added `Room.Epoch` (monotonic, advanced on every join/leave/suspend/
    reattach/evict/end_room) plumbed into `joined` and `room_state`. The
    server now emits an authoritative `room_state` snapshot on the new
    transport immediately after every successful `joined`.
  - Added `Room.negotiationDirty` and rewrote `handleRelay` so that
    offer/answer/ice to suspended targets is no longer silently dropped:
    the dirty pair is recorded, the sender receives
    `relay_failed{target_suspended,targets,of}`, and on reattach the
    server emits `negotiation_dirty{with}` to the affected peers right
    after the post-reconnect snapshot.
  - Added new `media_liveness{cids:[...]}` message and
    `Room.mediaLiveness`. `hardEvictSuspended` now defers eviction while
    a recent liveness hint exists
    (`mediaLivenessFreshnessWindow = 30s`,
    `hardEvictMediaActiveDeferral = 30s`).
  - Persisted latest content state on `roomParticipant.ContentState`;
    `handleRelay` for `content_state` updates the record and the value
    is included in every `joined` / `room_state` participant entry.
  - SSE pending-session model (#7) deliberately deferred behind a
    `TODO(#7)` next to `replaceClient`; the proper fix needs the matching
    SDK `resumeSse` envelope and ships in Phase 2.

- **All three SDKs**:
  - Parse the new fields (`epoch`, `reconnect`, `reconnectTokenTTLMs`,
    `participants[].contentState`, `error.reason`) and the new payload
    types (`relay_failed`, `negotiation_dirty`).
  - Map `INVALID_RECONNECT_TOKEN` to a new dedicated terminal error
    (`sessionExpired` / `SessionExpired` / `.sessionExpired`) and clear
    persisted reconnect storage on either `ROOM_ENDED` or
    `INVALID_RECONNECT_TOKEN`.
  - Web `SignalingEngine` additionally tracks
    `lastReconnectOutcome`, `lastEpoch`, `epochAtDisconnect`, and
    `awaitingPostReconnectSnapshot` so `MediaEngine` can gate ICE restart
    on a confirmed post-reconnect snapshot.

- **Tests / CI**:
  - Added `server/resilience_phase1_test.go` covering reconnect outcomes,
    recovered-CID, ROOM_ENDED tombstone, expired-token rejection, epoch
    advancement, post-reconnect snapshot, dirty-pair + `relay_failed`,
    `negotiation_dirty` on reattach, content-state replay, and
    media-liveness deferral.
  - Added Web payload tests for the new fields and parsers.
  - Server (Go), Web (vitest, 301), Android (gradle), iOS
    (xcodebuild, 246) all green.
  - `scripts/check-resilience-constants.mjs` and
    `scripts/check-version-parity.mjs` green.

- **Docs**:
  - `docs/serenada_protocol_v1.md` updated with the new fields on
    `joined` / `room_state`, the new error codes
    (`ROOM_ENDED`, `INVALID_RECONNECT_TOKEN`), and three new message
    types (`relay_failed`, `negotiation_dirty`, `media_liveness`).
  - This document's priority table now carries a Status column and each
    failure-mode section has a `**Status:**` line summarizing what
    landed in Phase 1 and what remains.

- **Deferred to follow-up slices**:
  - SDK MediaEngine consumption of `negotiation_dirty` /
    `relay_failed` / post-reconnect snapshot gate (#1, #4, #13 SDK side).
  - Periodic `media_liveness` emission from SDKs (#3 SDK side) and the
    per-participant suspended UI presentation timer (#3 / #6 surface).
  - Stale buffered offer/ICE discard in the SDK enqueue/flush path (#13).
  - SSE pending-session + `resumeSse` (#7) shipped together with the
    transport-resume coupling (#12) and the active-replacement guard
    (#15 part 2).
  - Process-death recovery (#5), iOS background-foreground forced ping
    (#8), push-to-rejoin lifecycle serialization (#10).

### 2026-04-26

- Reframed the proposal around media-first continuity: signaling loss and
  `suspended` state must not close media-active `RTCPeerConnection`s, while
  explicit leave/end/tombstone events remain terminal.
- Replaced the #1 `pendingDelivery` / `room_content_state` / desync queue design
  with dirty-pair renegotiation. Stale SDP mailboxes add ordering failure modes;
  a fresh negotiation after an authoritative snapshot is simpler and safer.
- Changed #2 so a valid reconnect token preserves or recovers the requested CID,
  adding `joined.reconnect="recovered"`. This avoids duplicate callers and keeps
  media-survived calls intact after server memory loss.
- Revised #3 and #6 so the suspended-peer timer is presentation-only. Added
  peer-reported media-liveness hints for server eviction decisions so
  media-active suspended peers are not removed solely because signaling is late.
- Fixed #4 to wait for an authoritative post-reconnect snapshot, not
  `epoch > epochAtDisconnect`; same-epoch snapshots are valid when membership did
  not change during the outage.
- Revised #5 so normal iOS backgrounding, background-task expiration, web
  `pagehide`, and reload do not send `/api/leave`. `/api/leave` is only for
  explicit terminal leave/end intent.
- Corrected #13 stale-offer handling: discard stale local payloads, locally
  rollback if possible, and schedule fresh negotiation instead of inventing a
  peer-visible rollback/reject SDP answer.
- Replaced #15's token grace window with the simpler rule that reconnect tokens
  cannot replace active transports; active replacement needs the #12 transport
  proof. If token rotation is added, it must use a no-grace
  `nextReconnectToken` / `reconnectTokenAck` handoff.
- Updated the cross-cutting summary and suggested ordering to match the simpler
  model: identity recovery first, security hardening next, authoritative snapshot
  before negotiation, and no generic SDP buffering.
- Added #15, reconnect-token replay, because the previous security coverage
  focused on SSE SID hijack but did not cover leaked `{rid, cid,
  reconnectToken}` reclaiming an active participant slot.
- Added #16, ephemeral content-state loss, because `content_state` is relayed
  like SDP/ICE but is actually latest UI state that must be restored after a
  suspended peer reconnects.
- Corrected #2 to reflect current server behavior: invalid reconnect tokens
  already return `INVALID_RECONNECT_TOKEN`; SDKs still need dedicated mapping and
  persisted-state cleanup for that error.
- Revised #6 because `suspendHardEvictionTimeoutMs` is not currently an SDK
  constant; it must be added to shared resilience constants before countdown or
  degraded-signaling UI can be computed consistently.
- Tightened #7 to specify pending unauthenticated SSE routing. Simply refusing
  `replaceClient` on duplicate SID is incomplete because current SSE POSTs route
  by SID through `clientsBySID`.
- Revised #9 to match the actual scheduler behavior: clients do schedule TURN
  refreshes, but the direct-path gate and signaling-down path can still allow
  credentials to expire without a queued refresh.
- Revised #10 because `POST /api/push/notify` authorizes the sender CID and does
  not directly target a suspended participant; the actionable gap is local
  lifecycle serialization and deep-link guarding.
- Reworked #12 from deterministic SID reuse to authenticated transport resume,
  because WebSocket SID is server-generated / not exposed to all SDK transports
  and cannot simply be reused by SSE without a protocol change.
- Kept #7, #9, #10, and #12 corrections from the previous pass: pending SSE auth
  is required for SID routing, TURN refresh must be queued across signaling
  outages, push fixes are lifecycle serialization rather than target auth, and
  WS/SSE failover needs authenticated transport resume.

### 2026-04-25

- Added the initial failure-mode inventory and the first cross-platform fix plan.
- Refined #3 to clarify that explicit server-initiated leave/end signals always
  take precedence over local suspended UI state.
- Refined #7 to include `Referer` header sanitization to prevent `sid` leakage to external services.
- Refined #10 to specify that tapping a push notification during `reconnecting` or `suspended` states triggers an immediate forced reconnection attempt.
