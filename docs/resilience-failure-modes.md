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
| 12| WS↔SSE failover creates split SIDs server-side            | Brief duplicate-attached state, peer flicker              | Medium   | S      |
| 13| Buffered signaling payloads become stale across reconnect | SDP collisions / out-of-order offer-answer state          | Medium   | S      |
| 14| Android foreground-service lifecycle drift                | Orphaned service after process kill blocks next call      | Medium   | S      |

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

### Suggested fix

Introduce a per-participant **bounded outbound queue** (`pendingDelivery []json.RawMessage`)
attached to the `Participant` record, populated when relay is targeted at a
suspended CID. On `reattachClient`, flush the queue to the new transport before
returning.

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

And in `reattachClient` after the new transport is wired up:

```go
for _, m := range p.drainPendingDelivery() {
    p.Client.sendMessage(m)
}
```

### Tests

- Unit: enqueue while suspended, reattach, assert flush order and contents.
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
2. When `reconnectToken` is invalid or the participant record is gone, the
   server falls through to creating a fresh participant rather than returning a
   distinct error
   — the client cannot tell "I successfully reattached" apart from "I started a
   new session that happens to share a roomId."

### Suggested fix

Make the protocol surface this explicitly. Add a field to the `joined` payload:

```jsonc
{
  "type": "joined",
  "payload": {
    "cid": "C-abc",
    "reconnect": "reattached" | "fresh" | "rejected_token",
    // ... existing fields
  }
}
```

- `"reattached"` — server reattached to an existing participant record (the CID
  matches what the client sent in `reconnectCid`).
- `"fresh"` — client requested a reconnect but server treated it as a new join
  (room was empty / participant hard-evicted). CID is new.
- `"rejected_token"` — `reconnectToken` was invalid; client should reset its
  saved token and decide whether to retry as a fresh join.

On the SDK side:

- If `"fresh"`, the SDK must purge its in-memory peer map, drop all
  `RTCPeerConnection`s, reset its `roomState`, and treat the join as a
  ground-up start. The session should emit a `recoveredAsFresh` event so the
  app shell can decide whether to show a "call recovered" indicator vs.
  "rejoined".
- If `"reattached"`, the SDK proceeds with the keep-alive path: ICE-restart on
  the cached peer set after the next `room_state` arrives (see #4).
- If `"rejected_token"`, the SDK clears its persisted token and surfaces
  `CallErrorCode.SessionExpired`.

### Tests

- Server: assert payload field populated correctly for each branch (fresh
  room, reused CID, evicted slot, invalid token).
- Each SDK: contract test that `"fresh"` triggers full peer-map reset.

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

Treat `suspended` as a soft tombstone with a client-side timeout:

1. When a participant transitions to `suspended` in `roomState`, start a
   per-CID timer locally (e.g. `peerSuspendedClientTimeout = 30 s`). The exact
   value should be substantially shorter than `suspendHardEvictionTimeout` —
   the user-visible value of "this peer might come back" is measured in tens
   of seconds, not minutes.
2. While suspended, suppress outbound offers/ICE for that CID (no point).
3. On reattach (status transitions back to `active`), cancel the timer; the
   ICE restart pathway picks up cleanup (#4).
4. On timer fire, locally close the `RTCPeerConnection` and remove the peer
   from the map. The server still holds the slot until hard-eviction, but our
   local view treats the peer as gone. If the participant later reattaches,
   we'll re-create the peer connection from the next `room_state` change
   (cheap).

Add a `RemoteParticipant.connectionStatus` UI hint so the call screen can show
"reconnecting…" overlay during the suspension window — which we already
emit but don't render anywhere.

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

Low. The timeout is a local, additive guard; it's safe to fire even when the
server later sends a contradictory `room_state` (we just rebuild).

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
return a structured error during the rejoin attempt rather than silently
re-joining. This is the `"rejected_token"` branch from #2 in protocol terms.

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
`suspendedSinceMs + suspendHardEvictionTimeout` (a constant the SDK already
has in `WebRtcResilienceConstants`). The app shell can render a countdown
("rejoining… 4:30 until call ends").

This is mostly a state-modeling change inside the SDK plus a UX hook —
no server changes needed.

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
based on SID alone. Two-part fix:

1. **SID continues to be the transport-session identifier**, but acquiring
   a SID does not by itself give you participant authority. Move the
   `replaceClient` semantics from SSE GET to a separate, post-connect step
   that requires `reconnectToken` validation.
2. **Concretely**: on `GET /sse?sid=<existing>`, do *not* invoke
   `replaceClient`. Instead, treat it as a fresh SSE session that needs to
   re-prove its identity. The client's first POST to `/sse?sid=...` must be
   either a fresh `join` (with `reconnectCid` and `reconnectToken`) or a
   small `resumeSse` envelope:

   ```jsonc
   {"type":"resumeSse","payload":{"reconnectToken":"...","cid":"..."}}
   ```

   The handler validates the token (HMAC over `cid|rid|sse-session-epoch`)
   and only then calls into `replaceClient` to rebind the participant slot.

3. The HTTP layer should drop the `sid` query parameter from server logs
   immediately to reduce leak surface area.

This also closes a related correctness gap: a stale browser tab navigating
to a new room can no longer poison the old room by reusing its SID.

### Tests

- Server unit: GET `/sse?sid=<victim>` does not bind participant.
- Server unit: GET + POST resumeSse with valid token rebinds; with invalid
  token returns 401 and does not rebind.
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

Signaling drops. Both peers were on direct ICE paths, so the SDK's
keep-alive logic correctly skipped TURN refresh
(`SerenadaSession.ts:304-306`). 16 minutes later the network changes and
ICE needs to fall back to relay. The TURN credentials are now expired
(`turnTokenTTL = 15 min`, `server/signaling.go:26`). ICE allocation fails
silently. The call cannot recover even when signaling comes back, because
the SDK doesn't refresh TURN until something explicit triggers it.

### Root cause

TURN credentials are fetched in `joined` / on explicit `turn-refresh` request,
not on a TTL-driven schedule. The "refresh only if needed" optimization
inside the keep-alive path assumes "if my paths are direct now, they will be
direct later" — which is false on network change.

### Suggested fix

Two coordinated guards:

1. **Track TURN credential expiry** in the SDK. Each `joined` /
   `turn-refresh` response provides `turnTokenTTLMs` (already populated
   server-side, `signaling.go:715`). Store
   `turnExpiresAtMs = receivedAtMs + turnTokenTTLMs - 60_000` (60 s safety
   margin).
2. **Refresh TURN on a timer**, regardless of signaling state. The signaling
   layer shouldn't gate on path mode; refresh credentials when they're about
   to expire, queue the refresh request if signaling is down, and apply on
   reconnect. This is a small change to the existing refresh scheduler.
3. **On any ICE state transition to `failed`**, force a TURN refresh and an
   ICE restart even if signaling has been down. If signaling is also down,
   queue both and apply when transport returns.

The current "all paths direct" optimization should be kept as a hint to *defer
slightly*, not to skip — refresh at `turnExpiresAtMs - 5 min` if all direct,
or earlier if any peer is on relay.

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

Server side, push notifications are also rejected for participants who are
suspended (#11 below) — `IsClientInRoom` requires active attachment
(`server/signaling.go:334-345`), so a peer trying to wake a suspended
participant gets HTTP 403 silently.

### Suggested fix

Three changes:

**A. Server: separate "active or suspended" check from "active only" check.**

`IsClientInRoom` is used both for relay-message authorization (where
"active only" is correct) and for push-notification authorization (where
"active or suspended" is what we want — pushes to a slot-holding peer make
sense). Add `IsClientSlotInRoom(roomID, cid string) bool` that returns
true for both states; switch the push handler to use it (`push.go:779`).

**B. SDK: serialize call lifecycle.**

In `CallManager` (iOS, Android), wrap session-lifecycle transitions
(start, leave, replace) in an actor / mutex. A `start` while `leave` is
in flight must wait for `leave` to complete. The leave operation should be
fast in any case since it's a fire-and-forget signaling message plus
local PC teardown.

**C. SDK: deep-link handling guard.**

Before processing a push-driven deep link, check `CallManager.activeSession`.
If it points to a *different* room, present a "switch call?" prompt rather
than starting a parallel session. If it points to the *same* room, no-op
and bring UI to foreground.

### Tests

- iOS UI test: push deep link arrives during in-progress leave. Assert
  no overlap.
- Android: same flow.
- Server unit: push-notify handler accepts suspended-but-not-evicted
  participants.

### Risk

Medium. The lifecycle serialization needs to be carefully sequenced
against UI state to avoid deadlocks (e.g. UI waiting for cleanup, cleanup
waiting for UI dismiss).

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

WS transport fails; SDK falls back to SSE; SSE generates a new SID
(`SseSignalingTransport.swift:115-118`). Server still has the WS-side
session in its 6 s grace window. For a brief moment the server has *two*
client objects pointing at the same participant's CID — one ghost (WS,
draining), one new (SSE). Peers can see momentary
`active → suspended → active` flicker.

### Root cause

The SDKs reset the SSE session ID on every fallback rather than
deterministically deriving it from the previous WS session. The server
treats the new SSE session as a fully independent client and only
reconciles via the slow eviction path.

### Suggested fix

Make SID derivation deterministic across transports:

- Both WS and SSE use the same `sid` value, generated once at SDK
  initialization. On WS→SSE failover, the SDK opens an SSE connection
  with the *same* SID. The server's `replaceClient` path then atomically
  swaps the transport, eliminating the grace-period overlap window for
  the same client.
- The `resumeSse` envelope from #7 also carries the participant's
  `reconnectToken`, which authorizes the SID swap. (Without #7's auth gate,
  this fix would amplify #7's hijack risk — they need to ship together.)

### Tests

- Integration: simulate WS failure with active call; assert peer sees no
  flicker in `room_state`.
- Server unit: SID swap closes old transport before broadcasting any
  membership change.

### Risk

Low to medium. Coupled with #7. Without #7, this fix would actually make
hijacking *easier* (you'd only need a SID, not a token).

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

## Cross-cutting changes summary

Several fixes share infrastructure. To minimize churn:

- **Server changes:**
  - Add `roomStateEpoch` integer to `Room`, increment on every
    membership-mutating operation (#4).
  - Add `pendingDelivery` queue to `Participant` (#1).
  - Add `IsClientSlotInRoom` helper distinct from `IsClientInRoom` (#10).
  - Add `tombstones` map to `Hub` for ended rooms (#11).
  - Add `POST /api/leave` for explicit shutdown (#5).
  - Drop `sid` from access-log query strings (#7).
  - SSE join requires `resumeSse` envelope with token (#7, #12).

- **Protocol additions:**
  - `joined.payload.reconnect: "reattached" | "fresh" | "rejected_token"` (#2).
  - `joined.payload.epoch`, `room_state.payload.epoch` (#4).
  - New `resumeSse` message type (#7).
  - New `ROOM_ENDED` error code (#11).

- **Shared SDK constants** (must update
  `scripts/check-resilience-constants.mjs` parity check):
  - `peerSuspendedClientTimeoutMs = 30_000` (#3).
  - `epochResyncTimeoutMs = 5_000` (#4).
  - `turnRefreshSafetyMarginMs = 60_000` (#9).
  - `foregroundForcePingTimeoutMs = 2_000` (#8).

- **Shared SDK responsibilities:**
  - Persist `{roomId, cid, reconnectToken, lastSeenEpoch}` and surface
    rejoin prompt (#5).
  - Compare returned CID to requested `reconnectCid` and emit
    `recoveredAsFresh` event when they differ (#2).
  - Gate ICE restart on next-epoch `room_state` arrival (#4).
  - Track TURN credential expiry independently of signaling state (#9).

## Suggested ordering

Phase 1 (correctness; ship in order):

1. #7 (SSE hijack) — security, ship first; small.
2. #1 (relay queue) — prevents the worst kind of silent corruption.
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
11. #12 (WS↔SSE deterministic SID) — needs #7 already shipped.
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
