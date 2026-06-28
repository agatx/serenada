# Multi-Call Session — Cross-Platform Implementation Contract

Status: Authoritative spec for implementation. Derived from `docs/multi-call-session-design.md`
and a full grounding pass over the current Web / iOS / Android SDKs (branch
`feature/multi-call-session`).

This document is **prescriptive**. Every platform implements the same semantics; only the
language idiom and the OS resource layer differ. When this contract and the design doc
disagree, the design doc's **Core Invariants** win and this contract is fixed to match.

---

## 0. Current-state anchors (verified)

The three SDKs are deliberately symmetric. Shared truths:

- `SerenadaCore.join()` constructs a session and starts it; the start order is
  **activate audio coordinator → activate audio controller → `startLocalMedia` → connect
  signaling**. (web: `SerenadaSession.checkPermissionsAndStartMedia`; iOS:
  `prepareMediaAndConnect` SerenadaSession.swift:1086; Android: `startJoinInternal`
  SerenadaSession.kt:1265.)
- Mic mute today only flips `track.enabled`/`isEnabled`/`setEnabled` — **capture stays
  live**. Hold MUST actually release/replace the audio sender track so the OS stops
  reporting capture.
- Video-off already stops + reacquires the camera track (web `releaseVideoTrack`/
  `reacquireVideoTrack`; iOS/Android capturer stop/restart). Reuse this for hold.
- `replaceTrack` / `setTrack` / `sender.track=` all exist and swap a same-kind track
  **without renegotiation** on all three. Invariant 3 (stable senders, no capture) is
  feasible: independent-content owner paths already pre-create transceivers via
  `addTransceiver`.
- Remote audio playout has **no full mute** today — only a 0.15 duck (iOS `source.volume`,
  Android `setVolume`) and web mounts an `<audio>` sink per remote stream in the UI. Hold
  needs a real suppression primitive.
- `participant_media_state` decoders are **lenient** on all three (web typeof-key, iOS
  JSONValue allowlist, Android `org.json` opt*). Adding `held` is wire-safe; builders +
  parsers each need extending.
- Audio coordinators have **no token / generation / owner concept** — iOS serializes via a
  single lifecycle-task chain (+10s timeout), Android via a mutex + async deactivation job
  (+10s timeout) and an `audioSessionActive` bool. This is the #1 fragile boundary.
- Recovery is a **single durable record** per platform (web `sessionStorage`
  `serenada.recovery`; iOS UserDefaults `serenada.recovery.record_v1`; Android SharedPrefs
  `serenada_recovery`/`record_v1`).
- DI seams are clean and fake-backed on every platform (web `FakeMediaEngine`/
  `FakeSignalingProvider`/`TestSessionHarness`; iOS `SessionMediaEngine`/
  `SessionAudioController`/`SerenadaAudioCoordinator` + `SessionTestHarness`; Android
  `SessionMediaEngine`/`SessionAudioController` + `TestSessionFactory`, Robolectric).
- Room-token canonicalization already exists and is host-agnostic (web
  `parseRoomIdFromUrl`, iOS `DeepLinkParser.extractRoomId`, Android `resolveRoomUrl`):
  extract the segment after `/call/`. Registry dedup reuses this.

---

## 1. Shared vocabulary (type names per platform)

| Concept | Web (TS) | iOS (Swift) | Android (Kotlin) |
|---|---|---|---|
| Call id | `CallId = string` | `CallId = String` (typealias) | `CallId = String` (typealias) |
| Media role | `CallMediaRole = 'foreground' \| 'held'` | `enum CallMediaRole { foreground, held }` | `enum class CallMediaRole { FOREGROUND, HELD }` |
| Activation state | `MediaActivationState = 'inactive'\|'activating'\|'active'\|'needsPermission'\|'failed'` | `enum MediaActivationState` (same cases) | `enum class MediaActivationState` (same cases) |
| Membership phase | existing `CallPhase` | existing `SerenadaCallPhase` | existing `CallPhase` |
| Owner token | `ForegroundOwnerToken` (opaque, `{ id }` or branded string) | `struct ForegroundOwnerToken: Equatable` | `class ForegroundOwnerToken` (identity) |
| Op generation | `number` (monotonic) | `Int` | `Long` |
| Video mode | reuse existing camera mode (`off`/`selfie`/`world`/`composite`) | existing `LocalCameraMode` + an `off` notion | existing `LocalCameraMode` + an `off` notion |

`membershipPhase`, `mediaRole`, `mediaActivationState` are **three orthogonal axes** (design
"Orthogonal State Model"). Do not collapse them. `held` is NOT a membership phase.

`activeCallId` is **derived**: the id of the single call whose `mediaRole == foreground`.
At most one. When none is foreground, `activeCallId == null/nil`.

---

## 2. Process-Wide Foreground Media Arbiter (Phase 2)

**Exactly one instance per process / JS execution context.** A true process singleton shared
by all `SerenadaCore` and all `SerenadaCallRegistry` instances. Not per-core, not
per-registry.

- Web: a module-level singleton in a new `foregroundArbiter.ts` (one per JS context).
- iOS: a `final class ForegroundMediaArbiter` exposed as `static let shared` (MainActor).
- Android: an `object`/singleton (process-global) guarded on `Dispatchers.Main.immediate`.

### Interface (semantics identical; idiom differs)

```
acquireForeground(ownerId: CallId) -> ForegroundOwnerToken      // throws/rejects ForegroundLeaseUnavailable if a lease is live OR a prior release is pending/failed OR cross-mode conflict
releaseLease(token: ForegroundOwnerToken)                       // only the current owner's token; mismatched token is an error; idempotent for the same token
nextOperationGeneration() -> Generation                        // monotonic; separate from the token
// owning-mode enforcement (Core Invariant 6):
claimMode(mode: registry | direct, ownerRef)                   // first user wins; while owner side has live sessions/calls, the other mode's acquire fails with ForegroundLeaseUnavailable
releaseMode(ownerRef)                                           // clears mode when the owning side has no live sessions/calls
```

### Rules

1. At most one live lease. A second `acquireForeground` while a lease is live →
   `ForegroundLeaseUnavailable`.
2. **Never grant a new lease while a previous owner's release is pending or failed.** This
   is what makes "old-release failure" safe (no two owners).
3. `releaseLease` rejects a token that is not the current owner's.
4. **Owning mode** (`registry` vs `direct`) is tracked separately from the lease. While a
   registry holds *any* non-ended managed call (even all-held, no foreground), a direct
   `SerenadaCore.join()` fails with `ForegroundLeaseUnavailable`, and vice versa. Mode
   clears when the owning side has zero live sessions/calls.
5. `nextOperationGeneration()` is a monotonic counter, bumped for **every** activation
   attempt including rollback. It is NOT the lease identity.
6. The arbiter also serializes screen-share ownership and (native) pairs audio-coordinator
   activate/deactivate. On web it still mints a capture lease so only one session holds
   `getUserMedia()` tracks.
7. Errors are diagnostic: every failure/timeout is logged with owner id + generation.

**Existing single-call path must route through the arbiter** (Phase 2): `SerenadaCore.join()`
acquires the lease (mode `direct`) before activating media; a second concurrent direct join
fails fast. The arbiter's `acquireForeground` throws `ForegroundLeaseUnavailable`, but the
session **catches it and surfaces an error `CallState`** (it does NOT propagate the throw out
of the non-throwing public `join()`) — parity across web/iOS/Android. This preserves
single-call behavior for one call and makes the invariant real.

---

## 3. Session Internal Foreground Contract (Phases 1–2)

Role mutation is **registry-owned and token-gated** — never a casual public setter. Public
single-call integrations keep using `join()/leave()/end()` and the existing audio/video
toggles and never see these methods.

```
session.preflightForeground() -> ok | needsPermission | failed     // PURE CHECK: no prompt, no capture
session.activateForeground(ownerToken, operationGeneration)         // -> foreground; throws on failure
session.abortForegroundActivation(ownerToken)                       // undo a partial/failed activation -> held
session.releaseForeground(ownerToken)                              // -> fully held; idempotent; MUST NOT throw after partial release
```

### Fencing (both checks required)

A late async activation callback is honored **only if it matches BOTH the current operation
generation AND the current lease owner token**. Failing either → discard. Generation alone is
insufficient (rollback re-activates the old call under a fresh generation; a stuck callback
from the failed new activation could otherwise still match). The owner token is the second,
independent fence. This is implemented **inside the session and inside the native audio
coordinator** (see §6).

### `preflightForeground()` semantics (pure)

- `needsPermission` if a *required* device permission for the call's **desired** media is not
  already granted. On web, treat `prompt`/unknown as not-granted → `needsPermission`.
- `ok` if desired media needs no permission: when `desiredAudioEnabled == false` AND
  `desiredVideoMode == off`, missing mic/camera permission does **not** block (a fully muted,
  camera-off call can foreground with no prompt).
- `failed` for non-permission preconditions (e.g. no audio route).
- Must NOT open a permission prompt and must NOT start capture. The host owns the prompt.

### `releaseForeground` / `abortForegroundActivation` guarantees

- `releaseForeground` is **idempotent** and **must not throw after partial release**. Once
  called it drives the session to fully-held (capture stopped, senders nulled, playout muted,
  renderers detached) and is safe to call again.
- `abortForegroundActivation` undoes an `activateForeground` that began capture/audio before
  failing, leaving the session held.
- **Lease release is registry-owned.** The session NEVER calls `arbiter.releaseLease`.
  `releaseForeground(token)` uses the token only to prove it is draining the current owner and
  to fence callbacks; after the session confirms fully-held, the **registry** calls
  `arbiter.releaseLease(token)`.

### Foreground media means

audio coordinator active; audio controller active; mic capture available iff
`desiredAudioEnabled` and policy allow; camera capture available iff `desiredVideoMode != off`
and policy allow; screen share allowed; remote audio playout enabled; renderers attachable.

### Held media means

screen share stopped; mic capture released/replaced so the OS no longer reports this session
capturing; camera capture released; `actualAudioPublished`/`actualVideoPublished` broadcast as
`false`; remote audio playout disabled; visible renderers detached/paused; signaling stays
connected; **peer-connection identity + stable transceivers/senders + reconnect token
preserved.**

---

## 4. Managed Call state + desired/actual (Phase 1 fields, Phase 3 ownership)

```
ManagedCall (registry-owned)             ManagedCallState (published, value type)
  id: CallId                               id, roomId, roomUrl
  roomId, roomUrl                          membershipPhase, mediaRole, mediaActivationState
  session: SerenadaSession                 desiredAudioEnabled, desiredVideoMode
  membershipPhase (from session phase)     actualAudioPublished, actualVideoPublished
  mediaRole, mediaActivationState          participantCount, localCid, held(flag)
  desiredAudioEnabled: Bool                displayName
  desiredVideoMode: VideoMode              activationError: CallActivationError?   // per-call
  actualAudioPublished, actualVideoPublished   qualitySummary? (after end)
  foregroundToken: ForegroundOwnerToken?
  joinedAtMs, lastActivatedAtMs?, displayName
```

**Desired vs actual.** Holding a call MUST NOT mutate user intent. `desired*` are changed
only by explicit user action (toggles) and survive hold. `actual*` reflect what peers observe
now; a held call always has `actualAudioPublished == false` and
`actualVideoPublished == false` regardless of desired intent. Resume restores intent.

`mediaActivationState` is only meaningful for the call being foregrounded; held calls sit at
`inactive`.

These fields are added to `SerenadaSession` in **Phase 1** (role + desired/actual + the
suspend/resume mechanics behind internal methods). The owner-token gating wrapping them is
added in **Phase 2** when the arbiter exists. So Phase 1 internal methods may be
`applyHeldRoleInternal()` / `applyForegroundRoleInternal()`; Phase 2 renames/wraps them as the
token-gated `releaseForeground`/`activateForeground`.

---

## 5. Local media suspend/resume primitives (Phase 1, in the media engine)

```
suspendLocalMediaForHold()                         // stop screenshare; stop+replace mic & camera senders with null/inactive
resumeLocalMediaFromHold(desiredAudio, desiredVideoMode)  // reacquire per desired; replace senders with fresh tracks
setRemotePlaybackEnabled(enabled: Bool)            // gate audible remote playout
detachOrPauseRenderersForHold()                    // detach/pause visible renderers
```

Shared contract: after suspension the session owns no mic, camera, screen share, or audible
remote playback, and preserves desired intent for resume. Sender swap uses `replaceTrack`/
`setTrack`/`sender.track=` with **no SDP renegotiation on the common path**. If any platform
path needs renegotiation, document it in that platform's section of the design doc; none is
expected.

### Remote playout suppression — concrete mechanism (this is a real bug area)

- **Web:** registry exposes `activeCallId`; React UI mounts the audible `<audio>`/`<video>`
  element only for the active session. **Defense in depth:** held session also sets
  `receiver.track.enabled = false` on remote audio receivers (so a host hand-mounting streams
  still gets silence). `setRemotePlaybackEnabled(false)` does the receiver-disable;
  resume re-enables + UI re-mounts.
- **iOS:** set remote `RTCAudioTrack.isEnabled = false` on the held session's receivers AND
  rely on foreground-only `AVAudioSession` activation (ADM not driving output for held).
  Both, not either. (Note: today only `source.volume` ducking exists — add a real
  `isEnabled=false` deafen path on receivers, distinct from the duck.)
- **Android:** set remote `AudioTrack.setEnabled(false)` on the held session's receivers;
  foreground-only audio focus / `MODE_IN_COMMUNICATION` ownership means held calls don't route
  audio. (Today only `setVolume(0.15)` ducking exists — add `setEnabled(false)`.)

### Stable senders without capture (Invariant 3) — held join

A session joined `held` still creates stable audio + video transceivers/senders during
negotiation but attaches **no local capture tracks** (senders carry `null` track). Resume
attaches freshly acquired tracks to those existing senders. Add a "create senders, defer
capture" path: today `startLocalMedia` always captures. Either (a) split `startLocalMedia`
into `createSenders()` + `startCapture()`, or (b) call `startLocalMedia` then immediately
suspend — but (a) is preferred (no transient mic/cam grab). Independent-content owner paths
already pre-create transceivers via `addTransceiver`; legacy `addTrack` needs a `addTransceiver`
+ `replaceTrack(null)` variant for held.

---

## 6. Native audio-coordinator lease awareness (Phase 4 hardening; seam added Phase 2)

The default audio coordinators are process-global and currently un-fenced. Add **lease-token +
generation awareness** so a delayed deactivation from an old session is ignored once it is no
longer the lease owner:

- iOS `DefaultAudioCoordinator` / `SerenadaSession` audio lifecycle: thread the owner token +
  generation through `activateCallSession`/`deactivateCallSession`; a callback whose generation
  or owner token is stale is dropped (do not re-drive `AVAudioSession`/`RTCAudioSession`).
- Android `DefaultAudioCoordinator`: same — a stale `audioManager` mode/focus restore from an
  old session is a no-op when it is not the current lease owner. Guard the delayed
  `postDelayed` route-refresh / ducking-fallback runnables and the async deactivation job with
  the lease token.
- Web: capture lease only; no OS audio session, but `getUserMedia` ownership is leased.

The arbiter owns the activate/deactivate *sequencing* (release old fully before acquiring new);
the session+coordinator own the *fencing* of late callbacks.

---

## 7. Registry (Phase 3)

Files: web `client/packages/core/src/SerenadaCallRegistry.ts`; iOS
`client-ios/SerenadaCore/Sources/SerenadaCallRegistry.swift`; Android
`client-android/serenada-core/src/main/java/app/serenada/core/SerenadaCallRegistry.kt`.

### Public API

```
joinHeld(room) -> JoinResult                  // join without taking foreground
joinAndSwitch(room) -> JoinAndSwitchResult    // join held, then switch to it
switchTo(callId) -> SwitchResult
hold(callId)
leave(callId)                                 // releases foreground first if active
end(callId)
// observable: state: CallRegistryState  (calls[], activeCallId, registryOperationInProgress, lastError)
```

There is **no** public `join(initialMediaRole)`. The two entry points are composites over a
registry-internal join.

### Result types

```
JoinResult          = joined(callId) | failed(callId?, error)
SwitchResult        = active | needsPermission | failed(error)
JoinAndSwitchResult = active(callId) | needsPermission(callId) | failed(callId?, error)
```

`needsPermission(callId)` MUST carry the CallId (the held call already exists; host prompts,
then `switchTo(callId)`).

### Operation serialization

ALL ops (`joinHeld`, `joinAndSwitch`, `switchTo`, `hold`, `leave`, `end`, permission
completion, app backgrounding, remote-ended) run through ONE serialization mechanism:

- Web: a promise-chained operation queue on the registry.
- iOS: `@MainActor` + a single serial task/flag.
- Android: a `Mutex` on `Dispatchers.Main.immediate`.

The queue serializes **foreground-lease + call-map mutations**, NOT slow network I/O. A
composite join runs in three parts: (A) short queued section creating+registering the managed
call (mode guard, dedup); (B) the held room join **outside** the queue, bounded by
`HELD_JOIN_TIMEOUT_MS`, cancellable; (C) a second queued section for the switch (preflight,
release old, activate new). Section C re-reads `activeCallId` (the world may have changed
between A and B).

### Switch algorithm (the contract; see design "Switch Operation" pseudocode)

```
switchTo(next):
  if next == activeCallId: return
  enqueue:
    gen = arbiter.nextOperationGeneration()
    // 0. PREFLIGHT inside the queued op, before touching old
    if next.preflightForeground() == needsPermission:
        next.mediaActivationState = needsPermission; return needsPermission   // old untouched
    // 1. drain old with ITS token, bounded by RELEASE timeout; releaseForeground is idempotent/no-throw
    stop old screenshare; ok = withTimeout(FOREGROUND_RELEASE_TIMEOUT_MS) { old.releaseForeground(old.token) }
    if !ok: old.mediaActivationState = failed (lease NOT released); return switchFailed   // old stays foreground, single-lease preserved
    arbiter.releaseLease(old.token); old.token = nil; old.mediaRole = held
    // 2. acquire fresh token; activate bounded
    try withTimeout(FOREGROUND_ACTIVATE_TIMEOUT_MS) {
        newToken = arbiter.acquireForeground(next.id); next.token = newToken
        next.activateForeground(newToken, gen); next.mediaRole = foreground; activeCallId = next.id
    } catch {
        next.abortForegroundActivation(next.token); arbiter.releaseLease(next.token); next.token = nil
        // 3. roll back to old under a FRESH generation
        rollbackGen = arbiter.nextOperationGeneration()
        try withTimeout(FOREGROUND_ACTIVATE_TIMEOUT_MS) {
            old.token = arbiter.acquireForeground(old.id); old.activateForeground(old.token, rollbackGen)
            old.mediaRole = foreground; activeCallId = old.id; surface recoverable error on next
        } catch {
            if old.token: old.abortForegroundActivation(old.token); activeCallId = nil; surface both errors
        }
    }
    publish state
```

Key rules: preflight before releasing old (Invariant 4); old-release timeout → keep old
foreground, never grant next lease (Invariant 1); activation failure → roll back to old by
default (Invariant 5 applies to *call-end*, not switch-failure — switch failure rolls back).

### hold / leave / end

- `hold(active)`: drain session foreground resources → registry `releaseLease` →
  `activeCallId = nil`. **No auto-promote** (Invariant 5).
- `hold(held)`: no-op. `hold(only call)`: keeps membership, no active UI session.
- `leave/end(active)`: drain + release lease, then existing leave/end teardown;
  `activeCallId = nil`; held calls stay connected, not auto-promoted.
- `leave/end(held)`: signaling/room cleanup only; must NOT touch the arbiter beyond asserting
  the held call holds no lease.

### Call identity policy

- One live call per canonical `roomId`. A second live join for an existing non-ended room
  returns the existing CallId (idempotent join by room).
- Canonicalize room URL/id before comparison via the existing `/call/<token>` extraction
  (host-agnostic; `serenada.app` and `serenada-app.ru` collapse to one token). Reuse
  `parseRoomIdFromUrl` / `DeepLinkParser.extractRoomId` / `resolveRoomUrl`.
- CallId is registry-generated, stable for the managed call's life. Host correlation/display
  keys are not identity.
- Recovering a live call resolves to the existing call (no duplicate).

---

## 8. Held-state signaling (Phase 1)

Additive field on the existing `participant_media_state` peer message. **No server change.**

On hold (after local capture stopped):
```json
{ "audioEnabled": false, "videoEnabled": false, "held": true }
```
On resume (after tracks attached):
```json
{ "audioEnabled": "<desiredAudio && route>", "videoEnabled": "<desiredVideo!=off && cam>", "held": false }
```

Rules (part of the contract):
- All clients ignore unknown fields (verified lenient on all three — keep it that way; add an
  explicit unknown-field decode test).
- Send ordering on hold = local-stop-then-broadcast. On resume = attach-then-broadcast. This
  guarantees LOCAL ordering only, not remote atomicity. Because held also sets
  `audioEnabled:false`/`videoEnabled:false`, an old peer that races degrades to muted/cam-off,
  never a wrong "live" state.
- New peer joining a held participant: existing participants re-broadcast media state on
  `peerJoined` (already the behavior) — ensure the re-broadcast includes `held`.
- Reconnect: re-broadcast current media state (incl. `held`) in post-reconnect resync.

Decoder/cache changes: extend the `remoteMediaStates` cache with a `held` field; surface it on
the remote participant model so new clients can render "on hold" distinctly. Builders
(`broadcastLocalMediaState` / `broadcastMediaState`) gain the `held` argument.

---

## 9. New resilience constants (Phase 3; checked by parity script)

Add to all three constant files with identical values (parity check requires presence in ≥2):

| Constant | Value | Rationale |
|---|---|---|
| `FOREGROUND_RELEASE_TIMEOUT_MS` | `5000` | bound draining old session's foreground resources |
| `FOREGROUND_ACTIVATE_TIMEOUT_MS` | `12000` | bound new activation; > `AUDIO_COORDINATOR_TIMEOUT_MS` (10000) so the inner audio timeout fires first |
| `HELD_JOIN_TIMEOUT_MS` | `15000` | bound a held room join; mirrors `JOIN_HARD_TIMEOUT_MS` |

Names normalize to UPPER_SNAKE (web `export const`, Kotlin `const val ... L`, Swift
`static let foregroundReleaseTimeoutMs` → `FOREGROUND_RELEASE_TIMEOUT_MS`). Verify with
`node scripts/check-resilience-constants.mjs`.

---

## 10. Recovery (foreground-only for v1)

`RecoveryRecord` shape is unchanged. Only the **foreground** call writes the durable record.
Held calls keep their **in-memory** reconnect identity (CID, reconnect token, epoch) for the
process life (this is what makes switch-back fast) but write NO durable record. After app
restart, held calls are gone; the host re-offers the last active call. Multi-record recovery
is deferred. Critically: a held call must not clobber the single durable record.

---

## 11. State + diagnostics (Phase 3)

```
CallRegistryState { calls: [ManagedCallState], activeCallId, registryOperationInProgress, lastError }
```
Each `ManagedCallState` carries per-call `activationError` (failed activation, failed release,
failed/timed-out join, or the needed permission) — registry-level `lastError` is not enough
once multiple calls exist. Do not hide the underlying `SerenadaSession`.

---

## 12. Phasing + per-phase test matrix

Implement in the design doc's order. Each phase: implement all 3 platforms → codex review →
simplify → codex review → reconcile cross-platform → build + unit tests green → commit.

- **Phase 1 — session primitives:** mediaRole + desired/actual fields; media engine
  suspend/resume + remote-playout suppression + renderer detach; held-state broadcast with
  ordering; fake-media-engine tests. (No arbiter/registry yet; internal methods.)
- **Phase 2 — join initial role + arbiter:** registry-internal join with `initialMediaRole`
  (public `join()` stays foreground-only); stable-sender-without-capture path; the process
  singleton arbiter (tokens + generation + owning-mode); route existing single-call join
  through the arbiter; token-gate the session foreground methods.
- **Phase 3 — registry:** `SerenadaCallRegistry`, op queue (all ops), rollback switch, call
  identity, aggregate state, constants. Single-call APIs unchanged.
- **Phase 4 — resource arbitration hardening:** web capture lease; iOS/Android audio
  coordinator lease+generation fencing; device-validation hooks.
- **Phase 5 — bundled app integration:** migrate iOS/Android app managers to registry-backed
  state; keep existing call screen as active-call renderer; minimal switcher; foreground
  service invariants (Android); push snapshot paths.
- **Phase 6 — docs + samples:** API reference, integration docs, the foreground-only recovery
  limitation, simple multi-call samples per platform.

Test matrix (minimum) is enumerated in the design doc's Web/iOS/Android "Tests" sections plus
the unknown-field decode test on all three decoders and the constants-parity check. Honor all
of them.
