# Resilience Failure Modes — Audit & Fix Plan

**Status:** Draft
**Date:** 2026-04-25
**Scope:** Web, Android, iOS SDKs and the Go signaling server

## Background

Sessions used to die on every signaling drop: when the WebSocket / SSE transport
went away, the SDK tore down peer connections and required the user to start
over. To improve perceived reliability, we introduced a keep-alive design:

- The server preserves participant records for `suspendHardEvictionTimeout = 10
  min` (`server/signaling.go:34`) after the transport drops, marking them
  `connectionStatus="suspended"` instead of removing them.
- The server's WS handler defers cleanup by `wsGracePeriod = 6 s`
  (`server/ws.go:16`), and SSE by `sseGracePeriod = 5 s` (`server/sse.go:17`).
- Clients keep their `roomState`, `clientId`, and per-peer `RTCPeerConnection`s
  in memory across signaling drops, persist a `reconnectToken`, and rejoin with
  `reconnectCid` to reattach to the same slot
  (`signaling.go:556-595`).
- On signaling reconnect, SDKs trigger an ICE restart against the cached peer
  set rather than a full rejoin.

This works for short interruptions. It introduces several new failure modes
when the interruption is long, when the server has forgotten the session, or
when the app process dies and restarts. This document enumerates each one,
explains how it manifests today, and proposes a fix.

The fixes are designed to be incremental — each can ship independently behind a
small protocol or client-side change. None of them require a redesign of the
core signaling protocol.

## Priority Summary

| # | Failure Mode                                              | User-visible Symptom                                      | Severity | Effort |
|---|-----------------------------------------------------------|-----------------------------------------------------------|----------|--------|
| 1 | Relay messages lost while peer is suspended               | Stuck call setup, missing media after reconnect           | Critical | M      |
| 2 | Silent CID change / room re-created on long reconnect     | App thinks it rejoined; server has no peers               | Critical | S      |
| 3 | Suspended-peer zombies on the client                      | UI shows a peer that is gone; resource leak               | High     | S      |
| 4 | ICE restart fires on stale peer map at reconnect          | Offers to ghost CIDs, missing offers to new peers         | High     | S      |
| 5 | Process death without clean teardown                      | Server holds a slot for a participant that's gone         | High     | M      |
| 6 | No explicit "you are suspended / about to be evicted"     | UI can't show countdown; apps can't make smart choices    | Medium   | S      |
| 7 | SSE transport hijack via SID reuse                        | Security: another connection can take over an SSE session | Critical | S      |
| 8 | iOS background suspension keeps SDK in stale "connected"  | After long background, signaling appears alive but isn't  | High     | M      |
| 9 | TURN credentials expire while signaling is down           | Relay path fails; cannot recover even when signaling back | Medium   | S      |
| 10| Push-to-rejoin races with local cleanup                   | Duplicate sessions, ghost slot on server                  | High     | M      |
| 11| `end_room` doesn't notify suspended peers                 | Suspended peer reconnect-loops a dead room                | Medium   | XS     |
| 12| WS↔SSE failover creates split SIDs server-side            | Brief duplicate-attached state, peer flicker              | Medium   | M      |
| 13| Buffered signaling payloads become stale across reconnect | SDP collisions / out-of-order offer-answer state          | Medium   | S      |
| 14| Android foreground-service lifecycle drift                | Orphaned service after process kill blocks next call      | Medium   | S      |
| 15| Reconnect-token replay can reclaim active CIDs            | Security: attacker can evict and impersonate a participant | Critical | M      |
| 16| Ephemeral content state lost while peer is suspended      | Screen share / content layout gets stale after reconnect  | High     | S      |

Severity legend: Critical = corruption or security; High = correctness break;
Medium = degraded UX or recoverable.
Effort: XS < 1d, S = 1-3d, M = 3-7d.

---

## 1. Relay messages lost while a peer is suspended

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

This violates the implicit contract suggested by suspension: that the slot
"exists" and the participant will be back. We preserve the slot but not the
mailbox.

Not every relayed message should be replayed the same way. SDP/ICE messages are
ordered negotiation traffic and need bounded queue semantics. `content_state`
and media-state style messages are latest-state signals; replaying every old
value can regress the UI after reconnect. See #16 for the separate state-sync
gap.

### Suggested fix

Introduce a per-participant **bounded outbound queue** (`pendingDelivery []json.RawMessage`)
attached to the `Participant` record, populated when relay is targeted at a
suspended CID. On successful reattach, flush the queue only after the `joined`
response / current room state has been sent, so the SDK never processes queued
SDP or ICE before it knows whether the reconnect was `fresh` or `reattached`.

Constraints:

- Cap queue depth to a small constant (e.g. 64 messages) — beyond that, drop
  the *oldest* SDP/ICE messages and log; a session that has accumulated 64
  unsent messages is effectively dead and we should not memory-balloon.
- For ICE candidates specifically, deduplicate by `candidate` string before
  enqueueing — bursts of trickle ICE can flood the buffer otherwise.
- Drop messages older than `suspendHardEvictionTimeout` on flush (defensive;
  shouldn't happen since we hard-evict at that boundary).
- For each enqueued message, record the *sender* CID. On flush, if that sender
  has since left the room, drop the message — it's pointless to deliver a
  signaling message from a non-participant.
- Keep `content_state` out of the FIFO path unless it is collapsed to one
  latest value per sender. A stale "screen share started" replay after a newer
  "screen share stopped" event is worse than dropping both.

The cleanest place to wire this is in `handleRelay` right where `byClient`
lookup currently fails:

```go
target := room.participantByCID(toCID)
if target == nil { return /* CID not in room */ }
if target.Client == nil {
    target.enqueuePendingDelivery(msg, c.cid)
    return
}
target.Client.sendMessage(msg)
```

After `handleJoin` sends `joined` for a successful reattach and the new
transport is wired up:

```go
for _, m := range p.drainPendingDelivery() {
    p.Client.sendMessage(m)
}
```

### Tests

- Unit: enqueue while suspended, reattach, assert `joined` is delivered before
  the pending queue and that flush order / contents are preserved.
- Unit: enqueue from a sender, sender leaves, reattach — assert message dropped.
- Unit: queue overflow drops oldest.
- Integration: A sends offer while P is suspended; P reattaches; verify P
  receives the offer with a P→A answer.

### Risk

Low. The change is additive to a code path that today silently drops. No
existing client behavior depends on the drop.

---

## 2. Silent CID change / room re-created empty on long reconnect

### Symptom

Client disconnects for longer than the keep-alive window (or the room was GC'd
because it became empty). It rejoins using the stored `reconnectCid` and
`reconnectToken`. The server has lost the participant record (or the room
itself). The join succeeds — but as a *fresh* participant in a fresh room —
and the server returns a different CID than the one the client requested. The
client has no way to detect this and proceeds with stale local state: peer
connections in its `peers` map for participants that aren't in this room, no
peer connections for participants who actually are.

### Root cause

Two issues conspire:

1. The `joined` payload always returns the assigned CID, but neither
   `client/packages/core/src/signaling/SignalingEngine.ts` nor
   `client-android/.../SerenadaServerProvider.kt:217-224` nor
   `client-ios/.../SerenadaServerProvider.swift:232` compares the assigned CID
   to the `reconnectCid` they sent. A silent change is treated as success.
2. When the participant record is gone, the server falls through to creating a
   fresh participant rather than returning a distinct error — the client cannot
   tell "I successfully reattached" apart from "I started a new session that
   happens to share a roomId."
3. Invalid reconnect tokens already return `INVALID_RECONNECT_TOKEN`
   (`server/signaling.go:564-568`), but SDKs map that to generic server error
   and do not clear persisted reconnect state. There is no cross-platform
   `sessionExpired` / `reconnectRejected` error code today.

### Suggested fix

Make the protocol surface this explicitly. Add a field to the `joined` payload:

```jsonc
{
  "type": "joined",
  "payload": {
    "cid": "C-abc",
    "reconnect": "reattached" | "fresh",
    // ... existing fields
  }
}
```

- `"reattached"` — server reattached to an existing participant record (the CID
  matches what the client sent in `reconnectCid`).
- `"fresh"` — client requested a reconnect but server treated it as a new join
  (room was empty / participant hard-evicted). CID is new.

Keep invalid tokens on the existing `error` path:

- `INVALID_RECONNECT_TOKEN` — `reconnectToken` was invalid. The client should
  clear its saved token/CID pair and surface a dedicated `sessionExpired`
  (or equivalently named) call error instead of a generic server failure.

On the SDK side:

- If `"fresh"`, the SDK must purge its in-memory peer map, drop all
  `RTCPeerConnection`s, reset its `roomState`, and treat the join as a
  ground-up start. The session should emit a `recoveredAsFresh` event so the
  app shell can decide whether to show a "call recovered" indicator vs.
  "rejoined".
- If `"reattached"`, the SDK proceeds with the keep-alive path: ICE-restart on
  the cached peer set after the next `room_state` arrives (see #4).
- If `INVALID_RECONNECT_TOKEN`, the SDK clears its persisted token and surfaces
  the new session-expired error on Web, Android, and iOS.

### Tests

- Server: assert payload field populated correctly for fresh room, reused CID,
  and evicted slot; assert invalid token still returns `INVALID_RECONNECT_TOKEN`.
- Each SDK: contract test that `"fresh"` triggers full peer-map reset.
- Each SDK: invalid reconnect token clears persisted reconnect state and maps to
  the dedicated session-expired error.

### Risk

Low for the protocol addition (additive field). Medium for the SDK reset
logic — needs careful audit that nothing assumes peer map continuity across
the boundary.

---

## 3. Suspended-peer zombies on the client

### Symptom

Remote peer A's transport drops. Server marks A `connectionStatus="suspended"`
and broadcasts updated `room_state`. The local SDK keeps A's
`RTCPeerConnection` open indefinitely (until the server hard-evicts A and
broadcasts again, up to 10 minutes later). UI continues to show A as if they
were active. If A never reconnects, the local peer connection is a slow leak
blocking ICE restarts and consuming bandwidth.

### Root cause

`MediaEngine.syncPeers` (`client/packages/core/src/media/MediaEngine.ts:474-507`)
considers any participant present in `roomState` as "alive" and keeps the peer
connection. The `connectionStatus` field is read into `RemoteParticipant` but
never gates peer-connection lifecycle. Same on Android
(`PeerNegotiationEngine.kt:69-93`) and iOS (mirrored).

### Suggested fix

Treat `suspended` as a soft tombstone with a client-side timeout. This is a
behavioral change to protocol v1: today `docs/serenada_protocol_v1.md` says
clients MUST keep the peer connection alive until the server removes the
participant. That protocol language must be relaxed to allow local teardown
after a shorter client timeout.

1. When a participant transitions to `suspended` in `roomState`, start a
   per-CID timer locally (e.g. `peerSuspendedClientTimeout = 30 s`). The exact
   value should be substantially shorter than `suspendHardEvictionTimeout` —
   the user-visible value of "this peer might come back" is measured in tens
   of seconds, not minutes.
2. While suspended, suppress outbound offers/ICE for that CID (no point).
3. On reattach (status transitions back to `active`), cancel the timer; the
   ICE restart pathway picks up cleanup (#4).
4. On timer fire, locally close the `RTCPeerConnection` and mark the CID as
   locally expired. The server still holds the slot until hard-eviction, but our
   local view treats the peer as gone. If the participant later reattaches, clear
   the local-expired marker and re-create the peer connection from the next
   active `room_state` change.

The local-expired marker matters: current `syncPeers` implementations recreate a
peer connection for any CID still present in `room_state`. If the server
continues to include the suspended CID for the full 10-minute window, the SDK
must not immediately rebuild the peer it just timed out.

Render the existing remote-participant signaling status in UI. The SDKs already
parse `connectionStatus="suspended"` into per-participant state, but the call
UIs mostly render aggregate reconnecting state. A remote peer that is suspended
while local signaling is healthy needs a per-participant "reconnecting" hint.

This constant must be added to `WebRtcResilienceConstants` and verified by
`scripts/check-resilience-constants.mjs` to keep parity across the three
clients.

### Tests

- SDK unit: roomState transition active → suspended → active within window:
  no PC teardown, no offers issued during suspension.
- SDK unit: roomState transition active → suspended → (timer expiry): PC
  closed, peer removed from `peers` map.
- SDK unit: roomState transition active → suspended → active after timer
  expiry: fresh PC created.

### Risk

Medium. The local timeout is small, but this changes current protocol guidance
and requires the local-expired marker so fresh `room_state` messages do not
thrash the peer connection.

---

## 4. ICE restart fires against stale peer map on reconnect

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

Gate ICE restart on a **fresh `room_state` epoch** received after reconnect:

1. Server-side: include a monotonic `roomStateEpoch` integer in every
   `room_state` (and `joined`) payload. It increments on every membership
   change (join, leave, suspend, reattach, evict). Cheap to maintain — single
   counter per room.
2. SDK-side: on disconnect, record the last-seen epoch as `epochAtDisconnect`.
   On reconnect, *do not* trigger ICE restart yet. Wait for the next
   `room_state` whose epoch is `> epochAtDisconnect` (or a `joined` payload
   that subsumes it). Only then `syncPeers` and schedule ICE restart against
   the fresh, server-confirmed peer set.
3. Add a 5 s timeout: if no fresh `room_state` arrives by then, the server
   probably forgot us — fall through to the "fresh join" handling from #2.

This also kills a class of races where late-arriving signaling payloads from
*before* the disconnect re-create torn-down peers (because the SDK assumes its
in-memory peer map is canonical).

### Tests

- Unit: epoch advances on every membership change.
- SDK unit: simulate disconnect → membership change on server → reconnect.
  Assert no offers issued before the new `room_state` is processed.
- Integration: client A drops 5 s, client B leaves during the drop, client A
  reconnects — assert A does not generate an offer to B.

### Risk

Low. The epoch is additive and ignored by older clients (they keep current
behavior). The wait-for-epoch path is additive on top of existing reconnect
flow.

---

## 5. Process death without clean teardown

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
- Web: `pagehide` / `beforeunload` is not used to send an explicit `leave`.

In all three platforms, the SDK has no graceful "I'm being killed" path that
either (a) sends a final `leave` over a fire-and-forget transport, or
(b) persists enough state to recover on next launch.

### Suggested fix

Two coordinated changes:

**A. Best-effort final leave on shutdown signals**

- iOS: subscribe to `UIApplication.willTerminateNotification` and to
  `scenePhase == .background` transitions. On terminate, send a synchronous
  HTTP `POST /api/leave` with `{rid, cid, reconnectToken}` (no WS dependency
  — the WS may already be dead, and we have at most ~5 s before the OS kills
  us).
- Android: in `CallService.onTaskRemoved` we already call `leaveCall()`. Also
  hook `onDestroy` and a `Process.killProcess` death intent if available; in
  practice the LMK path is handled by adding the same HTTP `/api/leave`
  fallback from the host app's `Application.onTerminate` or via a JobService
  scheduled on call start.
- Web: register a `pagehide` (and `beforeunload` as a fallback) handler that
  uses `navigator.sendBeacon('/api/leave', JSON.stringify({rid, cid,
  reconnectToken}))`. `sendBeacon` is the right primitive — it survives unload
  and doesn't require a response.

Server: add `POST /api/leave` that takes `{rid, cid, reconnectToken}`, validates
the token, and immediately hard-evicts the participant. Skip the suspension
hold. Idempotent.

**B. Persistent recovery state**

Persist `{roomId, cid, reconnectToken, lastSeenEpoch, sessionStartTs}` to:

- iOS: `UserDefaults` in the app group (already shared with the notification
  service for snapshot keys). Clear on clean leave.
- Android: app-private SharedPreferences. Clear on clean leave.
- Web: `sessionStorage` (per-tab). Clear on clean leave.

On app launch, if a recovery record exists and is younger than
`suspendHardEvictionTimeout`, surface a "Rejoin call?" prompt to the user. On
accept, drive the same reconnect path as a normal `reconnectCid` join.

Web specifically: `sessionStorage` is per-tab and survives reload but not tab
close — that's the right scope for "you reloaded the page mid-call."

### Tests

- Web: test that `pagehide` triggers a beacon (manual via DevTools, plus a
  vitest with a mocked `navigator.sendBeacon`).
- Server: integration test that `/api/leave` is rate-limited and validates
  reconnect token.
- iOS UI test: kill app via `XCUIApplication.terminate()`, verify on next
  launch the rejoin prompt appears (this is testable in the simulator).

### Risk

Medium. The beacon / final-leave path needs careful auditing for race with
the normal cleanup path — must be idempotent on the server. The persistent
recovery record needs a TTL audit so we don't perpetually prompt users to
rejoin dead calls.

---

## 6. No explicit "you are suspended / about to be evicted" signal

### Symptom

The SDK can't tell the difference between "I'm reconnecting and the server is
holding my slot" and "I'm reconnecting and the server already evicted me."
There's no countdown, no UX hint, no way for the app to make a smart decision
("we've been suspended for 8 minutes, fully tear down and restart").

### Root cause

The server's view of "this client is suspended" is implicit — it stops
sending messages. The client infers nothing actionable from silence.

### Suggested fix

When a transport reconnects but the server has already hard-evicted the slot,
return explicit protocol state rather than silently re-joining. If the room is
gone because the call ended, return a structured terminal error (#11). If the
room still exists but the CID was evicted, return `joined.reconnect="fresh"` so
the SDK can reset old peer state (#2).

Additionally, while suspended, the SDK should expose this state to the app
shell:

```ts
// Web SDK
type SignalingState =
  | { kind: 'connected' }
  | { kind: 'reconnecting'; attempt: number; nextRetryAtMs: number }
  | { kind: 'suspended'; suspendedSinceMs: number; hardEvictionAtMs: number }
  | { kind: 'failed'; reason: CallErrorCode };
```

The `hardEvictionAtMs` deadline is computed client-side from
`suspendedSinceMs + suspendHardEvictionTimeoutMs`. That constant does not exist
in the SDKs today; add it to `WebRtcResilienceConstants` across Web, Android,
and iOS and include it in `scripts/check-resilience-constants.mjs`. The app
shell can render a countdown ("rejoining... 4:30 until call ends").

This is mostly a state-modeling change inside the SDK plus a UX hook. The only
server-side dependency is making the terminal/fresh-rejoin outcomes explicit
enough for the SDK to know whether the countdown ended in recovery, fresh join,
or call termination.

### Tests

- SDK unit: state transitions emitted in correct order.
- SDK unit: `hardEvictionAtMs` is correct after a series of partial
  reconnections.

### Risk

Low. State surface only.

---

## 7. SSE transport hijack via SID reuse

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
`validateReconnectToken`, `signaling.go:564`) before reattaching to a CID,
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
   immediately to reduce leak surface area.
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
   server-side, `signaling.go:715`). Store
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
than starting a parallel session. If it points to the *same* room, no-op
and bring UI to foreground.

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

### Symptom

Host calls `end_room` while peer P is suspended. Server stops P's
hard-eviction timer (`signaling.go:836-839`) and sends `room_ended` only to
*active* clients (`signaling.go:861`). P reconnects later, finds the room
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
that no longer exists, return a structured `ROOM_ENDED` error rather than
the generic "room not found" / "fresh room created." The SDK treats
`ROOM_ENDED` differently from a transient failure — it terminates the
session immediately, surfaces a normal "call ended" UI, and clears
persisted recovery state.

**Better (S):** Add a short-lived (5-minute) tombstone map on the server
keyed by `rid` recording the reason a room no longer exists (`ended_by_host`,
`hard_evicted`, `host_left`). Reconnect attempts targeting a tombstoned
RID return the tombstone reason. The map is bounded by TTL and is
mutexed with `h.mu`.

The tombstone approach also benefits #2 (silent CID change) — instead of
silently creating a fresh room, the server can return `ROOM_GONE` and let
the client decide whether to start fresh.

### Tests

- Server unit: end_room then rejoin returns `ROOM_ENDED`.
- SDK: receiving `ROOM_ENDED` clears recovery state and emits clean
  termination.

### Risk

Very low. Pure server-side addition.

---

## 12. WS↔SSE failover creates split SIDs server-side

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

1. **Timestamp-tag every buffered payload** at enqueue time. On flush,
   drop any payload older than `2 * offerTimeoutMs` (~16 s). The
   counterparty has long since timed out anyway.
2. **Tag with the room-state epoch** (#4) at enqueue time. On flush, if
   the current epoch is past, drop. This catches "the membership changed
   while I was queued."
3. **Cap ICE candidate buffer with a tail-drop rather than head-drop**
   for the freshness case (newer candidates are more likely to reflect
   the current network), but keep head-drop on overflow during normal
   flow. In practice this is a non-issue if #4 is in place — we won't be
   queueing 50+ candidates for a peer we don't intend to talk to.

### Tests

- SDK unit: enqueue offer at epoch N, advance epoch, flush — assert
  drop.
- SDK unit: enqueue offer 30 s ago, flush — assert drop.

### Risk

Low. Drops are safer than misapplications.

---

## 14. Android foreground-service lifecycle drift

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
active client, evicts that client as a fast-reconnect ghost (`signaling.go:571-591`).

That active-ghost path is useful for legitimate fast reconnects during the WS /
SSE grace window, but the token is reusable for the lifetime of the room. A
leaked token is therefore a participant takeover credential, not only a recovery
hint.

### Suggested fix

Make reconnect authority short-lived and generation-bound:

1. Add a `ReconnectGeneration` (or random `ReconnectNonce`) to each participant.
   Issue reconnect tokens as HMAC over `rid|cid|generation|expiresAt` or store
   opaque random tokens server-side with an expiry.
2. Rotate the token after every successful reattach / active ghost replacement.
   The old token is immediately invalid. Include the new token in the next
   `joined` response and update SDK persistence.
3. Give reconnect tokens a TTL no longer than `suspendHardEvictionTimeout` and
   reject expired tokens with `INVALID_RECONNECT_TOKEN`.
4. Split active ghost replacement from suspended reattach. Active replacement
   should require the latest token generation and should be allowed only inside
   the short transport grace window unless a stronger transport-resume proof
   from #12 is present.
5. Avoid logging reconnect tokens, and treat stored tokens like credentials on
   clients.

This complements #7. Fixing SSE SID hijack without hardening reconnect tokens
leaves a different credential replay path that can produce the same takeover.

### Tests

- Server unit: token from generation N cannot reattach after generation N+1 is
  issued.
- Server unit: expired reconnect token returns `INVALID_RECONNECT_TOKEN`.
- Server unit: active client replacement outside the grace window is rejected
  unless the #12 transport-resume proof is valid.
- SDK unit: refreshed reconnect token overwrites persisted token after reattach.

### Risk

Medium. This changes the reconnect contract and needs a staged rollout so older
SDKs do not get stranded without a usable token.

---

## 16. Ephemeral content state lost while peer is suspended

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
   entries, or add a small `room_content_state` block keyed by CID. Keep the
   protocol additive and ignore unknown fields on older clients.
3. On `content_state` relay while a target is suspended, collapse to the latest
   value rather than enqueueing every transition. Latest wins.
4. Clear a participant's content state on explicit leave, hard eviction,
   `room_ended`, and when the participant sends `content_state.active=false`.
5. SDKs should reconcile local diagnostic/UI content state from room state after
   reconnect, not only from live peer messages.

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
  - Add `pendingDelivery` queue to `Participant` (#1).
  - Add `tombstones` map to `Hub` for ended rooms (#11).
  - Add `POST /api/leave` for explicit shutdown (#5).
  - Drop `sid` from access-log query strings (#7).
  - Add pending unauthenticated SSE sessions plus authenticated `resumeSse`
    binding (#7).
  - Add generation-bound reconnect tokens and token rotation (#15).
  - Add transport-resume proof for WS/SSE failover (#12).
  - Persist latest participant content state for reconnect state sync (#16).

- **Protocol additions:**
  - `joined.payload.reconnect: "reattached" | "fresh"` (#2).
  - `joined.payload.epoch`, `room_state.payload.epoch` (#4).
  - New `resumeSse` message type for pending SSE auth (#7).
  - New `resumeTransport` / `transportResumeId` fields for cross-transport
    failover (#12).
  - New `ROOM_ENDED` error code (#11).
  - New dedicated SDK error mapping for `INVALID_RECONNECT_TOKEN` (#2, #15).
  - Additive content-state metadata in `joined` / `room_state` (#16).

- **Shared SDK constants** (must update
  `scripts/check-resilience-constants.mjs` parity check):
  - `peerSuspendedClientTimeoutMs = 30_000` (#3).
  - `suspendHardEvictionTimeoutMs = 600_000` (#6).
  - `epochResyncTimeoutMs = 5_000` (#4).
  - `turnRefreshSafetyMarginMs = 60_000` (#9).
  - `foregroundForcePingTimeoutMs = 2_000` (#8).

- **Shared SDK responsibilities:**
  - Persist `{roomId, cid, reconnectToken, lastSeenEpoch}` and surface
    rejoin prompt (#5).
  - Compare returned CID to requested `reconnectCid` and emit
    `recoveredAsFresh` event when they differ (#2).
  - Clear persisted reconnect state and surface session-expired on
    `INVALID_RECONNECT_TOKEN` (#2).
  - Gate ICE restart on next-epoch `room_state` arrival (#4).
  - Track TURN credential expiry independently of signaling state (#9).
  - Track local-expired suspended peers so fresh `room_state` does not
    immediately recreate timed-out peer connections (#3).
  - Reconcile content-state UI from `joined` / `room_state`, not only live
    `content_state` peer messages (#16).

## Suggested ordering

Phase 1 (correctness; ship in order):

1. #15 (reconnect-token replay) + #7 (SSE hijack) — security; ship together
   so the auth primitive is not just moved from SID to token.
2. #1 (relay queue) + #16 (content-state sync) — preserve both negotiation
   traffic and latest UI state across suspension.
3. #2 (silent CID change) + #11 (room tombstone) — ship together; #11 is
   tiny and complementary.
4. #4 (epoch-gated ICE restart).
5. #3 (suspended-peer client timeout).

Phase 2 (UX & lifecycle):

6. #6 (explicit suspension state surface).
7. #5 (process death recovery) — bigger; depends on #2 / #11 having
   landed.
8. #8 (iOS background lifecycle).
9. #10 (push-to-rejoin races).

Phase 3 (cleanup):

10. #9 (TURN expiry).
11. #12 (WS↔SSE authenticated transport resume) — needs #7 / #15 already
    shipped.
12. #13 (stale buffered payloads).
13. #14 (Android service lifecycle).

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
  (`signaling.go:364-398`). This was flagged as a potential map-write
  race during the audit but is well-protected by the room mutex on all
  current paths. If we ever take `replaceClient` outside the room lock
  it deserves a fresh look.

## Change Log

### 2026-04-26

- Added #15, reconnect-token replay, because the previous security coverage
  focused on SSE SID hijack but did not cover leaked `{rid, cid,
  reconnectToken}` reclaiming an active participant slot.
- Added #16, ephemeral content-state loss, because `content_state` is relayed
  like SDP/ICE but is actually latest UI state that must be restored after a
  suspended peer reconnects.
- Corrected #2 to reflect current server behavior: invalid reconnect tokens
  already return `INVALID_RECONNECT_TOKEN`; the gap is SDK mapping and clearing
  persisted reconnect state, while missing participant records still need an
  explicit fresh-vs-reattached signal.
- Revised #3 because local suspended-peer teardown conflicts with current
  protocol v1 wording and would be immediately undone by `syncPeers` unless SDKs
  track locally expired suspended CIDs.
- Revised #6 because `suspendHardEvictionTimeoutMs` is not currently an SDK
  constant; it must be added to shared resilience constants before countdown UI
  can be computed consistently.
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
- Updated the cross-cutting summary and suggested ordering to include reconnect
  token hardening, content-state sync, pending SSE auth, and authenticated
  transport resume.
