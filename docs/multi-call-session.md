# Multi-Call Session Integration Guide

Status: v1. Applies to web (`@agatx/serenada-core` + `@agatx/serenada-react-ui`), iOS (`SerenadaCore`), and Android (`serenada-core`).

This is the host-app guide for `SerenadaCallRegistry`, the layer that lets one app keep several Serenada calls joined at once and switch the foreground media owner between them. For the design rationale and invariants see [docs/multi-call-session-design.md](multi-call-session-design.md); for the prescriptive cross-platform contract see [docs/multi-call-session-contract.md](multi-call-session-contract.md). For the additive `held` wire field see [docs/serenada_protocol_v1.md](serenada_protocol_v1.md) section 4.12.

## What the registry does

`SerenadaSession` stays the single-call primitive. The registry sits above sessions and owns one thing the host should not manage itself: which call holds **foreground media**. In v1 exactly one call at a time may own the microphone, the camera, screen share, OS audio routing/focus, and the primary call UI. Every other joined call is **held**: it stays signaled and connected and keeps its reconnect identity, but owns no capture, no audible playout, and no primary renderers.

Three orthogonal axes describe each managed call (do not collapse them):

- `membershipPhase` — room membership and connection (joining / waiting / connected / ending / ended / error).
- `mediaRole` — `foreground` or `held`. At most one call is `foreground`; that call's id is `activeCallId`.
- `mediaActivationState` — how foreground activation is going (inactive / activating / active / needsPermission / failed). Only meaningful for the call being foregrounded; held calls sit at `inactive`.

The registry never hides the underlying `SerenadaSession`. The host renders the active call by handing that session to the existing `SerenadaCallFlow`, and reads per-call diagnostics straight off the session.

## Mode exclusivity (direct `join()` vs the registry)

A process integrates **either** through direct single-call `SerenadaCore.join()` **or** through a `SerenadaCallRegistry`, not both while either has live calls. This is enforced at the process level by the shared foreground arbiter, not just at the foreground lease:

- While a registry holds **any** non-ended managed call (even all-held, with no foreground call), a direct `SerenadaCore.join()` fails fast with a foreground-lease-unavailable error.
- While a direct session is live, constructing or operating a registry that needs the lease fails the same way.
- The mode clears once the owning side has zero live calls, after which the other mode may claim the process.

A failed direct `join()` does NOT throw: it returns a session whose `CallState` is `error` (code `unknown`, message describing the lease conflict), so hosts read the failure off `state` like any other call error — consistent across web/iOS/Android.

The same rule applies **between two direct joins** (migration note for existing single-call hosts): because every public `join()` now acquires the process-wide lease, a second direct `join()` while another direct session is still live fails into the same error state. Sequential flows (leave/end, then join) are unchanged, but a host that switches rooms by joining the new room first must leave/cancel the old session before the new `join()`.

Pick one integration style per app and stick with it.

## Public API

The semantics are identical across platforms; only the language idiom and the exact method names differ. The signatures below are the real ones.

### Web (`@agatx/serenada-core`)

```typescript
import { SerenadaCallRegistry, SerenadaCore } from '@agatx/serenada-core';

const registry = new SerenadaCallRegistry(new SerenadaCore(config));

// room is { url: string } | { roomId: string }, plus an optional displayName.
registry.joinHeld(room): Promise<JoinResult>;
registry.joinAndSwitch(room): Promise<JoinAndSwitchResult>;
registry.switchTo(callId): Promise<SwitchResult>;
registry.hold(callId): Promise<void>;
registry.leave(callId): Promise<void>;   // releases foreground first if active
registry.end(callId): Promise<void>;     // ends for all participants
registry.dismiss(callId): void;          // drop an ended call from the published list
registry.close(): Promise<void>;         // dispose: leave every call, refuse new ones (call on teardown)

// Observable state (useSyncExternalStore-compatible):
registry.state;                          // CallRegistryState
registry.subscribe(cb);                  // () => unsubscribe
registry.activeCall;                     // { state, session } | null
registry.sessionFor(callId);             // SerenadaSession | null
```

### iOS (`SerenadaCore`)

```swift
let registry = SerenadaCallRegistry(core: SerenadaCore(config: config))

// RoomRef(url:) or RoomRef(roomId:), each with optional displayName / peerId.
func joinHeld(_ room: RoomRef) async -> JoinResult
func joinAndSwitch(_ room: RoomRef) async -> JoinAndSwitchResult
func switchToCall(id: CallId) async -> SwitchResult
func holdCall(id: CallId) async
func leaveCall(id: CallId) async          // releases foreground first if active
func endCall(id: CallId) async
func dismissEndedCall(id: CallId) async   // drop an ended call from the list
func close() async                        // dispose: leave every call, refuse new ones (call on teardown)

// Published state (ObservableObject):
@Published var calls: [ManagedCallState]
@Published var activeCallId: CallId?
@Published var registryOperationInProgress: Bool
@Published var lastError: CallRegistryError?
var activeCall: ManagedCall?              // .session for rendering
func call(id: CallId) -> ManagedCall?
```

### Android (`serenada-core`)

```kotlin
val registry = SerenadaCallRegistry(core)   // SerenadaCore

// RoomRef.Url(url) or RoomRef.Id(roomId, serverHost?).
suspend fun joinHeld(room: RoomRef): JoinResult
suspend fun joinAndSwitch(room: RoomRef): JoinAndSwitchResult
suspend fun switchToCall(callId: CallId): SwitchResult
suspend fun holdCall(callId: CallId)
suspend fun leaveCall(callId: CallId)        // releases foreground first if active
suspend fun endCall(callId: CallId)
suspend fun dismissCall(callId: CallId)      // drop an ended call from the list
suspend fun close()                          // dispose: leave all calls (releasing the lease), free the process

// Observable state (StateFlow), main-thread only:
val state: StateFlow<CallRegistryState>
val activeSession: SerenadaSession?          // the active call's session, for rendering
fun session(callId: CallId): SerenadaSession?
```

All Android registry methods must be called on the main thread.

## Result types

The two join entry points are composites over a registry-internal join; there is no public `join(initialMediaRole)`. The host either joins in the background (`joinHeld`) or joins and foregrounds (`joinAndSwitch`).

```text
JoinResult           = joined(callId) | failed(callId?, error)
SwitchResult         = active | needsPermission | failed(error)
JoinAndSwitchResult  = active(callId) | needsPermission(callId) | failed(callId?, error)
```

Per platform the shapes are:

- **Web** — discriminated unions on a `kind` field, for example `{ kind: 'joined', callId }`, `{ kind: 'active', callId }`, `{ kind: 'needsPermission', callId }`, `{ kind: 'failed', callId?, error }`. `SwitchResult` is `{ kind: 'active' } | { kind: 'needsPermission' } | { kind: 'failed', error }`.
- **iOS** — Swift enums: `JoinResult.joined(CallId)` / `.failed(CallId?, CallActivationError)`; `SwitchResult.active` / `.needsPermission` / `.failed(CallActivationError)`; `JoinAndSwitchResult.active(CallId)` / `.needsPermission(CallId)` / `.failed(CallId?, CallActivationError)`.
- **Android** — sealed interfaces: `JoinResult.Joined(callId)` / `.Failed(callId?, error)`; `SwitchResult.Active` / `.NeedsPermission` / `.Failed(error)`; `JoinAndSwitchResult.Active(callId)` / `.NeedsPermission(callId)` / `.Failed(callId?, error)`.

`needsPermission(callId)` carries the `CallId` deliberately: the held call already exists, so the host requests the permission itself (its own prompt flow) and then retries with `switchTo`/`switchToCall`. The registry never opens a permission prompt; preflight runs **before** the current foreground call is touched, so a permission gap leaves the active call fully running.

## Rendering the active call

Render the foreground call with the existing single-session call flow. The registry exposes the active call's live session:

```typescript
// Web (React) — useSerenadaCallRegistry wraps construction + teardown:
const { activeCall, heldCalls, joinAndSwitch, switchTo, hold, leave } =
    useSerenadaCallRegistry({ config });

<SerenadaCallFlow session={activeCall?.session} />
```

```swift
// iOS — the session-first SerenadaCallFlow init takes a non-optional session,
// so unwrap the active call first and show your own empty/holding view otherwise.
if let session = registry.activeCall?.session {
    SerenadaCallFlow(session: session)
} else {
    // no active call (all held / none): render a "calls on hold" surface
}
```

```kotlin
// Android
SerenadaCallFlow(session = registry.activeSession)
```

A single-call app is just a registry with one foreground call, so the existing single-call UX is preserved. A full multi-call switcher component is deferred in v1: the SDK exposes state and primitives and the host builds its own switcher (chips, held banners, per-call controls).

## Held calls and the "no active but held remain" surface

When the active call ends, leaves, or is explicitly held, the registry releases the foreground lease and sets `activeCallId` to null. It does **not** auto-promote a held call. So a valid steady state is: no active call, but one or more held calls still connected. The host decides what to foreground next (or shows a "calls on hold" surface and lets the user pick).

Read the held calls off the published state:

- **Web** — `heldCalls` from `useSerenadaCallRegistry` (the calls with `held === true`), or filter `registry.state.calls` on `held`.
- **iOS** — filter `registry.calls` on `held` (or `mediaRole == .held`).
- **Android** — filter `registry.state.value.calls` on `held` (or `mediaRole == HELD`).

Each `ManagedCallState` carries a per-call `activationError` (failed activation, failed/timed-out release, failed/timed-out join, or the needed permission), plus `desired*` intent, `actual*` published state, participant count, local cid, display name, and a `qualitySummary` after the call ends. Use the per-call error, not just the registry-level `lastError`, once more than one call exists.

A remote peer who is on hold surfaces through the existing participant model: the remote participant carries a `held` flag (web `Participant.held`, iOS `SerenadaRemoteParticipant.held`, Android `RemoteParticipant.held`). Render that as "on hold" distinct from plain muted. Older peers never send `held`, so they appear muted/camera-off.

## Switching, holding, and teardown semantics

- `switchTo` / `switchToCall`: preflights the target's permissions first; if the target needs a grant it returns `needsPermission` and leaves the current call untouched. Otherwise it drains the old call to held, releases its lease, then activates the target. If activation fails it rolls back to the old call by default. If draining the old call times out, the old call keeps the lease and the switch aborts (you keep the call you were on).
- `hold` / `holdCall`: drains the active call's foreground resources, releases the lease, sets `activeCallId` to null. No auto-promote. Holding an already-held call (or the only call) is a no-op that keeps it connected.
- `leave` / `end` (and their `...Call` variants): for the active call, drain foreground and release the lease first, then run the normal leave/end teardown. Held calls stay connected and are not auto-promoted.

All registry operations are serialized so foreground-lease and call-map mutations never interleave (a promise-chained queue on web, a serial main-actor task on iOS, a `Mutex` on the main dispatcher on Android). `registryOperationInProgress` is published while a queued section runs.

## Call identity

The registry keeps **one live call per canonical room**. A second live join for a room that already has a non-ended managed call returns the existing `CallId` (idempotent join by room). Room URLs and ids are canonicalized to the `/call/<token>` token before comparison, host-agnostic, so `serenada.app` and `serenada-app.ru` URLs for the same room collapse to one. The `CallId` is registry-generated and stable for the managed call's life; it is not the room id and not a host correlation key.

## Custom signaling provider (multi-call)

If your app injects its own signaling instead of using built-in server mode, the provider surface is **session-scoped**. There are two versions; pick by how many concurrent calls the provider must back.

**A `SignalingProvider` (version 1) is single-session.** It has one listener/delegate slot and room-less ops (`leaveRoom`/`endRoom`/`disconnect`/TURN refresh/reconnect), so exactly one session can bind a given v1 provider object at a time. That covers single-call `join()` and **one** live registry call. A **second concurrent** join that would bind the same v1 object fails fast with a typed error rather than silently sharing it (which would cross-wire the two sessions' CIDs and events):

- **Web** — a direct `core.join()` returns a session whose state is an error `CallState` with `error.code === 'providerUnavailable'` (a `CallErrorCode`; the SDK also exports `ProviderUnavailableError`); a registry join returns `{ kind: 'failed' }` carrying that code.
- **iOS** — `CallError.providerUnavailable`, surfaced on a direct join as an error `CallState` and on a registry join as a failed `JoinResult`.
- **Android** — a direct `SerenadaCore.join(...)` throws `SingleSessionProviderInUseException` (carrying `CallError.ProviderUnavailable`); a registry join returns `JoinResult.Failed` with the same `CallError`.

Sequential reuse is fine: fully tear down one session, then join again against the same v1 provider. The guard is concurrency-only. Built-in server mode is unaffected (it already constructs a fresh internal provider per session).

**Multi-call over an app-owned provider requires a `MultiSessionSignalingProvider` (version 2).** This is an app-global service that owns the physical transport once and vends one signaling channel per session via `openSession(roomId)`. Each vended channel satisfies the existing v1 per-session surface, so `SerenadaSession` consumes it unchanged; concurrent sessions never share a listener or cross-wire rooms.

Configure it per platform:

- **Web** — pass it as `signalingProvider` (the config field is `AnySignalingProvider`); the SDK detects `version === 2` and calls `openSession` once per join.
- **iOS** — pass it as the `multiSessionSignalingProvider` init parameter on `SerenadaConfig`.
- **Android** — set `multiSessionSignalingProvider` on `SerenadaConfig`.

Provide exactly one signaling source (`serverHost`, the v1 provider field, or the v2 provider field); setting both provider fields is a configuration error.

### Migration sketch (v1 → v2)

Wrap your existing transport in a service whose `openSession(roomId)` returns a room-bound channel object that implements the existing provider surface you already have. The old v1 provider effectively becomes the per-room channel; the new service is the thin factory around it.

```typescript
// Web
class DemoService implements MultiSessionSignalingProvider {
    readonly version = 2 as const;
    openSession(roomId: string): SignalingProvider {
        // Return a v1 SignalingProvider bound to roomId (your existing provider,
        // now scoped to one room and fed only that room's events).
        return new DemoChannel(roomId, this.sharedTransport);
    }
    getIceServers() { return this.sharedTransport.iceServers(); }
}
```

The Kotlin and Swift shapes mirror this (`openSession(roomId): SignalingProvider`, `suspend`/`async getIceServers()`, `version` and `capabilities` defaulted). See the per-platform provider examples in [docs/sdk/sdk-integration-web.md](sdk/sdk-integration-web.md), [docs/sdk/sdk-integration-android.md](sdk/sdk-integration-android.md), and [samples/ios/README.md](../samples/ios/README.md).

### Channel lifecycle obligations (implementor)

The SDK vends one channel per session, never shares a channel, and calls the channel's `close()`/`disconnect()` exactly once at session teardown. Everything below is on the **implementor** of the service (the SDK cannot enforce another process's transport):

- **Room binding.** Each channel is permanently bound to the one canonical room passed to `openSession`; deliver that channel's events **only** to that channel's listener/delegate.
- **Channel-local state.** `leave`/`end`/`disconnect`, TURN refresh, and reconnect state are all scoped to the channel that triggered them, never global.
- **Transport ownership and ref-counting.** The service owns the single physical transport. Closing one channel must **not** disconnect any other channel; tear the physical transport down only **after the last** channel closes.
- **Queued events.** Tag queued events with a per-channel generation and **drop** them once that channel has closed, so a late event never lands on a reused transport or a closed channel.
- **Terminal close.** `close()`/`disconnect()` on a channel is terminal and idempotent: a second close is tolerated and does nothing.

## v1 limitation: foreground-only recovery

Recovery across app restart is **foreground-only** in v1.

- Only the **foreground** call writes a durable recovery record (web `sessionStorage`, iOS `UserDefaults`, Android `SharedPreferences`), exactly as a single call does today.
- Held calls keep their reconnect identity (CID, reconnect token, epoch) **in memory only** for the life of the process. This is what makes switching back fast: a held call rejoins without a full leave/rejoin. It is deliberately not persisted.
- After an app restart, held calls are gone. The host re-offers the **last active** call from the single durable record, the same way a single-call app does.

Multi-record recovery keyed by `CallId` is a fast-follow once the registry and arbiter are proven. Until then, do not rely on held calls surviving a process restart.
