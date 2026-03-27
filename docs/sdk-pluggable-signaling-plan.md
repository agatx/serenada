# SDK Pluggable Signaling Plan

**Status:** Draft
**Date:** 2026-03-26

## Goal

Allow the Serenada SDK to operate without the Serenada signaling server by introducing a pluggable signaling interface. Third-party integrators who embed the SDK provide their own message delivery between peers, using whatever scalable messaging infrastructure they already have.

## Motivation

The Serenada SDK follows a **headless SDK + optional UI** pattern. This plan extends it to **headless SDK + optional signaling + optional UI** — making the SDK a pure WebRTC media library that can be dropped into any system with peer-to-peer messaging.

This also offers an alternative path to signaling scalability: if the integrator's messaging system handles delivery, the Serenada signaling server is not needed at all. The signaling scaling problem shifts entirely to the integrator's infrastructure.

Note: pluggable signaling does not change the media topology. The SDK uses full-mesh WebRTC (every participant connects to every other participant), which limits practical group size to ~4 participants regardless of signaling approach. Scaling beyond that would require an SFU (Selective Forwarding Unit), which is a separate architectural change.

## Current Architecture: How Signaling Is Coupled

### Session ↔ Signaling coupling by platform

| Aspect | Web | Android | iOS |
|--------|-----|---------|-----|
| Signaling reference | Direct instance variable | Injected `SessionSignaling` interface | `SignalingClientListener` protocol |
| Coupling | Tight (direct API calls) | Listener pattern | Protocol listener pattern |
| Message flow | signaling → session → media | signaling → router → negotiation engine | signaling → router → negotiation engine |
| Media → signaling | Callback injected at construction | Closure via `sendMessage()` | Closure via `sendMessage()` |
| TURN flow | `signaling.turnToken` → `media.updateTurnToken()` → HTTP fetch | router `onJoined` → `turnManager.fetchTurnCredentials()` | router `onJoined` → `turnManager.ensureIceSetupIfNeeded()` |

Android and iOS already use listener/protocol patterns between session and signaling, making them closer to pluggable. Web has tighter coupling through direct method calls on `SignalingEngine`.

### What the session actually needs from signaling

Tracing all three platforms, the session state machine depends on exactly these interactions:

**Events received (drive state transitions):**
- Transport connected / disconnected
- Joined room (with participant list, host assignment, TURN credentials)
- Peer joined / peer left (with CID and joinedAt)
- Room state updated (full participant list refresh)
- Message from peer (offer, answer, ICE candidate, content_state)
- Room ended (by host)
- Error (with code and message)
- TURN credentials refreshed

**Actions sent:**
- Join room
- Leave room
- End room (for all participants)
- Send message to specific peer (offer, answer, ICE, content_state)
- Broadcast to all peers
- Request TURN credential refresh
- Ping (keep-alive)

### What the Serenada server provides beyond transport

| Server function | Needed for third-party integration? |
|----------------|-------------------------------------|
| WS/SSE transport | No — replaced by integrator's messaging |
| Message relay (offer/answer/ICE) | No — integrator delivers between peers |
| Room creation (`POST /api/room-id`) | No — integrator has their own group/channel IDs |
| Room membership tracking | No — integrator's system is the source of truth |
| Host assignment | No — convention (e.g., group creator) or not needed |
| Reconnect tokens | No — integrator handles identity and reconnection |
| Push notifications | No — integrator handles presence |
| TURN token issuance + credential API | **Partially** — TURN credentials still needed, but source is flexible |
| Ping/pong keep-alive | No — integrator's transport handles connection health |
| Room ID HMAC validation | No — integrator uses their own identifiers |
| `watch_rooms` / watcher updates | No — integrator has their own presence/status system |

**The only hard dependency is TURN credentials** — the SDK needs ICE server configs to establish WebRTC connections. But the source is flexible: integrator's own TURN, Cloudflare API, or credentials passed inline via signaling messages.

## Design: SignalingProvider Interface

### Concept

The session state machine is decoupled from the transport and protocol by programming against a `SignalingProvider` interface. Two implementations:

```
┌─────────────────────────────────────────────┐
│  SerenadaSession                             │
│  ├── PeerNegotiationEngine (WebRTC mesh)    │
│  ├── TurnManager (ICE server setup)         │
│  └── ConnectionStatusTracker (diagnostics)  │
└──────────────┬──────────────────────────────┘
               │ uses
        ┌──────▼──────┐
        │ Signaling   │
        │ Provider    │ ← abstract interface
        └──────┬──────┘
               │
       ┌───────┴────────────────────┐
       │                            │
┌──────▼─────────┐  ┌──────────────▼──────────────┐
│ SerenadaServer │  │ Custom provider              │
│ Provider       │  │ (third-party adapter)        │
│                │  │                              │
│ Built-in WS/   │  │ Integrator implements using  │
│ SSE + Serenada │  │ their messaging system       │
│ protocol       │  │                              │
└────────────────┘  └─────────────────────────────┘
```

### Interface definition

The interface is intentionally minimal — only what the session state machine needs.

#### Web (TypeScript)

```typescript
interface SignalingProvider {
  // --- Lifecycle ---
  connect(): void
  disconnect(): void

  // --- Room actions ---
  joinRoom(roomId: string, options?: JoinOptions): void
  leaveRoom(): void
  endRoom(): void

  // --- Peer messaging ---
  sendToPeer(peerId: string, type: string, payload: unknown): void
  broadcast(type: string, payload: unknown): void

  // --- ICE servers ---
  // Provider is responsible for sourcing TURN credentials.
  // Called once after join and whenever the session needs a refresh.
  getIceServers(): Promise<RTCIceServer[]>

  // --- Events (session subscribes) ---
  on(event: 'connected', cb: (info?: ConnectionInfo) => void): void
  on(event: 'disconnected', cb: (reason?: string) => void): void
  on(event: 'joined', cb: (data: JoinedEvent) => void): void
  on(event: 'roomStateUpdated', cb: (data: RoomStateEvent) => void): void
  on(event: 'peerJoined', cb: (data: PeerEvent) => void): void
  on(event: 'peerLeft', cb: (data: PeerEvent) => void): void
  on(event: 'message', cb: (data: PeerMessage) => void): void
  on(event: 'roomEnded', cb: (data: RoomEndedEvent) => void): void
  on(event: 'error', cb: (data: ErrorEvent) => void): void
  on(event: 'iceServersChanged', cb: (servers: RTCIceServer[]) => void): void
  off(event: string, cb: Function): void
}

// Optional metadata about the underlying transport (built-in provider
// populates this; third-party adapters may omit or use custom values).
interface ConnectionInfo {
  transport?: string            // e.g., 'ws', 'sse', or adapter-specific
}

// Full participant snapshot. The session uses this to reconcile its peer
// map — critical for joinedAt-based offer ownership and reconnect recovery.
// Fired on initial join and whenever the authoritative participant list changes.
interface RoomStateEvent {
  participants: Participant[]
  hostPeerId?: string
  maxParticipants?: number
}

interface JoinOptions {
  reconnectPeerId?: string      // reuse peer identity on reconnect
  maxParticipants?: number
  capabilities?: { maxParticipants?: number }
}

interface JoinedEvent {
  peerId: string                // this client's assigned peer ID
  participants: Participant[]   // current participant list
  hostPeerId?: string           // optional host concept
  maxParticipants?: number
}

interface Participant {
  peerId: string
  joinedAt: number              // ms timestamp
}

interface PeerEvent {
  peerId: string
  joinedAt?: number
}

interface PeerMessage {
  from: string                  // sender peer ID
  type: string                  // 'offer' | 'answer' | 'ice' | 'content_state' | custom
  payload: unknown
}

interface RoomEndedEvent {
  by: string
  reason: string
}

interface ErrorEvent {
  code: string
  message: string
}
```

#### Android (Kotlin)

```kotlin
interface SignalingProvider {
    // Lifecycle
    fun connect()
    fun disconnect()

    // Room actions
    fun joinRoom(roomId: String, options: JoinOptions = JoinOptions())
    fun leaveRoom()
    fun endRoom()

    // Peer messaging
    fun sendToPeer(peerId: String, type: String, payload: JSONObject)
    fun broadcast(type: String, payload: JSONObject)

    // ICE servers
    suspend fun getIceServers(): List<PeerConnection.IceServer>

    // Listener
    var listener: Listener?

    // All listener callbacks are dispatched on the main thread.
    // Third-party providers MUST post to the main looper before invoking.
    interface Listener {
        fun onConnected(info: ConnectionInfo? = null)
        fun onDisconnected(reason: String?)
        fun onJoined(event: JoinedEvent)
        fun onRoomStateUpdated(event: RoomStateEvent)
        fun onPeerJoined(event: PeerEvent)
        fun onPeerLeft(event: PeerEvent)
        fun onMessage(event: PeerMessage)
        fun onRoomEnded(event: RoomEndedEvent)
        fun onError(event: ErrorEvent)
        fun onIceServersChanged(servers: List<PeerConnection.IceServer>)
    }
}
```

#### iOS (Swift)

```swift
protocol SignalingProvider: AnyObject {
    // Lifecycle
    func connect()
    func disconnect()

    // Room actions
    func joinRoom(roomId: String, options: JoinOptions)
    func leaveRoom()
    func endRoom()

    // Peer messaging
    func sendToPeer(peerId: String, type: String, payload: [String: Any])
    func broadcast(type: String, payload: [String: Any])

    // ICE servers
    func getIceServers() async -> [IceServerConfig]

    // Delegate
    var delegate: SignalingProviderDelegate? { get set }
}

// All delegate callbacks are dispatched on @MainActor.
// Third-party providers MUST dispatch to MainActor before invoking.
@MainActor
protocol SignalingProviderDelegate: AnyObject {
    func providerDidConnect(info: ConnectionInfo?)
    func providerDidDisconnect(reason: String?)
    func providerDidJoin(event: JoinedEvent)
    func providerRoomStateDidUpdate(event: RoomStateEvent)
    func providerPeerDidJoin(event: PeerEvent)
    func providerPeerDidLeave(event: PeerEvent)
    func providerDidReceiveMessage(event: PeerMessage)
    func providerRoomDidEnd(event: RoomEndedEvent)
    func providerDidReceiveError(event: ErrorEvent)
    func providerIceServersDidChange(servers: [IceServerConfig])
}
```

### Key design decisions

**Full room-state snapshots required.** The session state machine synchronizes its peer map from complete participant snapshots — not just incremental join/leave events. This is critical for `joinedAt`-based offer ownership (who creates the offer in a peer pair) and for recovery after reconnects or missed presence events. The `roomStateUpdated` event carries a full participant list that the session uses to reconcile its local state. Third-party adapters must fire this event whenever the authoritative participant list changes. Incremental `peerJoined`/`peerLeft` events are complementary (for fast UI updates) but the session must not rely on them alone for correctness.

**Callback threading guarantees.** The current SDK sessions are main-thread constrained: Android sessions run on the main looper, iOS signaling is `@MainActor`. The provider contract requires all listener/delegate callbacks to be dispatched on the main thread (Android) or `@MainActor` (iOS). Third-party adapters that receive events on background threads must post to the main thread before invoking the listener. The session does not add its own marshaling layer — the provider contract is the enforcement point. The Web SDK uses single-threaded JS, so this constraint is implicit.

**Transport diagnostics via `ConnectionInfo`.** The current SDK exposes `activeTransport` (`ws` vs `sse`) in call diagnostics. The `connected` event carries an optional `ConnectionInfo` with a `transport` field. The built-in provider populates this with `'ws'` or `'sse'`. Third-party adapters can provide their own transport descriptor (e.g., `'mqtt'`, `'grpc'`) or omit it — the session treats the field as diagnostic-only and does not branch on its value.

**Peer IDs, not Client IDs.** The interface uses `peerId` instead of Serenada's `cid` (client ID). The built-in provider maps `cid` → `peerId`. Third-party adapters use whatever identifier their system provides.

**ICE servers via `getIceServers()`.** The current SDK has a multi-step TURN flow: server sends `turnToken` → `TurnManager` makes HTTP call to `/api/turn-credentials`. The interface replaces this with a single `getIceServers()` method. The provider is responsible for sourcing credentials from whatever backend it uses. The built-in provider wraps the existing token + HTTP fetch flow internally.

**No protocol envelope.** The interface deals in `type` + `payload`, not the Serenada JSON envelope (`v`, `type`, `rid`, `sid`, `cid`, `to`). The built-in provider wraps/unwraps the envelope. Third-party adapters use whatever framing their system provides.

**Room actions are optional for third-party.** `joinRoom()` / `leaveRoom()` / `endRoom()` map to the integrator's group management. If their system doesn't have explicit join/leave (e.g., presence is implicit), the adapter can no-op these and fire `onJoined`/`onPeerJoined` based on their system's events.

**Host concept is optional.** `hostPeerId` in `JoinedEvent` is nullable. If the third-party system doesn't have a host concept, the adapter omits it. The UI layer treats all participants as peers.

## Built-in Provider: SerenadaServerProvider

The existing `SignalingEngine` (Web) / `SignalingClient` (Android, iOS) is wrapped as `SerenadaServerProvider`, the default implementation of `SignalingProvider`. It handles:

- WS/SSE transport setup (with `rid` on URLs for room-affinity)
- Serenada protocol envelope wrapping/unwrapping
- Room lifecycle (join → joined → room_state → leave)
- TURN token → HTTP credential fetch via TurnManager
- Reconnection logic (grace periods, reconnect tokens)
- Ping/pong keep-alive
- `watch_rooms` (exposed as a separate `RoomWatcher` API, not part of the provider interface)

This wrapper is thin — it translates between the `SignalingProvider` interface and the existing internal APIs. Existing behavior is preserved exactly.

## Third-Party Adapter: What Integrators Implement

A third-party adapter is typically ~50-100 lines per platform. Example (pseudocode):

```typescript
class MySignalingAdapter implements SignalingProvider {
  private channel: MyMessagingChannel  // integrator's messaging SDK

  connect() {
    this.channel.connect()
    this.channel.onReady(() => this.emit('connected', { transport: 'my-system' }))
  }

  joinRoom(roomId: string) {
    this.channel.joinGroup(roomId)

    // Fire joined with full participant snapshot
    const members = this.channel.getMembers()
    const participants = members.map(m => ({ peerId: m.id, joinedAt: m.joinedAt }))
    this.emit('joined', {
      peerId: this.channel.myId,
      participants,
    })

    // Incremental updates + full snapshot on membership change
    this.channel.onMemberJoined((member) => {
      this.emit('peerJoined', { peerId: member.id, joinedAt: Date.now() })
      // Fire full snapshot for session reconciliation
      this.emitRoomState()
    })
    this.channel.onMemberLeft((member) => {
      this.emit('peerLeft', { peerId: member.id })
      this.emitRoomState()
    })
    this.channel.onMessage((from, data) => {
      this.emit('message', { from: from.id, type: data.type, payload: data.payload })
    })
  }

  private emitRoomState() {
    const members = this.channel.getMembers()
    this.emit('roomStateUpdated', {
      participants: members.map(m => ({ peerId: m.id, joinedAt: m.joinedAt })),
    })
  }

  sendToPeer(peerId: string, type: string, payload: unknown) {
    this.channel.sendTo(peerId, { type, payload })
  }

  broadcast(type: string, payload: unknown) {
    this.channel.broadcast({ type, payload })
  }

  async getIceServers(): Promise<RTCIceServer[]> {
    // Integrator's own TURN, or Cloudflare, or any provider
    const creds = await myTurnService.getCredentials()
    return [{ urls: creds.urls, username: creds.username, credential: creds.credential }]
  }

  // ... disconnect, leaveRoom, endRoom, off, etc.
}
```

Usage:

```typescript
const adapter = new MySignalingAdapter(myMessagingChannel)
const serenada = createSerenadaCore({ signalingProvider: adapter })
const session = serenada.join({ roomId: 'group-123' })
```

## Public API Changes

### Core construction, not join-time injection

The provider cannot be injected only at `join()` time because several APIs (`createRoom()`, `RoomWatcher`, diagnostics) are called before or independently of `join()`. The provider must be known at SDK construction time.

**Approach: `serverHost` becomes optional in `SerenadaConfig`.** When `serverHost` is provided (current behavior), the SDK uses the built-in `SerenadaServerProvider` and all server-bound APIs work normally. When `serverHost` is omitted and a `signalingProvider` is set on the config, the SDK operates in provider mode. This makes the mode a stable, object-level property available from construction — not a runtime flag toggled at `join()`.

Current config (all platforms require `serverHost`):
```typescript
// Web
interface SerenadaConfig {
  serverHost: string    // required
  // ...
}

// Android
data class SerenadaConfig(val serverHost: String, ...)

// iOS
struct SerenadaConfig { let serverHost: String; ... }
```

New config (`serverHost` optional, `signalingProvider` added):
```typescript
// Web
interface SerenadaConfig {
  serverHost?: string                    // optional — required for built-in signaling
  signalingProvider?: SignalingProvider   // optional — alternative to serverHost
  // ...
}

// Android
data class SerenadaConfig(
    val serverHost: String? = null,
    val signalingProvider: SignalingProvider? = null,
    ...
)

// iOS
struct SerenadaConfig {
    let serverHost: String?                        // nil for custom provider
    let signalingProvider: SignalingProvider?        // nil for built-in
    ...
}
```

**Validation:** Exactly one of `serverHost` or `signalingProvider` must be provided. Both set → error. Neither set → error. This is checked at config construction time, not at `join()`.

### Entry points

Join signatures remain focused on room identity. The signaling source comes from `SerenadaConfig`, not from `join(...)` parameters.

Existing `serverHost`-based usage works identically:
```typescript
// Web, built-in signaling (unchanged)
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })
serenada.join(url)
serenada.join({ roomId })

// Custom provider (new)
const adapter = new MySignalingAdapter(myChannel)
const serenadaWithProvider = createSerenadaCore({ signalingProvider: adapter })
serenadaWithProvider.join({ roomId: 'group-123' })
```

Android/iOS follow the same model: configure the signaling source once, then call the existing `join(...)` overloads:
```kotlin
// Android
val builtIn = SerenadaCore(SerenadaConfig(serverHost = "serenada.app"), context)
builtIn.join(url)
builtIn.join(roomId)

val custom = SerenadaCore(SerenadaConfig(signalingProvider = adapter), context)
custom.join(roomId)
```

### Server-bound API availability

Because `serverHost` presence is known at config construction time, all APIs can gate consistently:

| API | `serverHost` set | `signalingProvider` set |
|-----|------------------|------------------------|
| `SerenadaCore.join()` | Built-in signaling | Uses custom provider |
| `SerenadaCore.createRoom()` / `createRoomId()` | Calls `POST /api/room-id` | Throws: "requires serverHost" |
| `RoomWatcher(serverUrl)` | Works (uses `watch_rooms` protocol) | Throws: "requires serverHost" |
| `SerenadaDiagnostics.runAll()` | Full suite (device + TURN + server probes) | **Device + TURN checks** (see below) |
| `SerenadaDiagnostics.runConnectivityChecks()` | Serenada server probes (WS, SSE, room API, diagnostic token) | Throws: "requires serverHost" |
| `SerenadaDiagnostics.runTurnProbe()` | TURN reachability via server-issued token | TURN reachability via `provider.getIceServers()` |
| `isSupported()` | Works | Works |
| `subscribeToMessages()` (Web) | Returns `SignalingMessage` objects | Throws: "unavailable with custom signalingProvider; use onPeerMessage()" |

### Diagnostics: three-tier split

Current `SerenadaDiagnostics.runAll()` performs device checks, TURN probes, and Serenada server connectivity probes in a single call. These must be split into three tiers based on what they depend on:

| Tier | Checks | Depends on | Available in provider mode? |
|------|--------|-----------|---------------------------|
| **Device** | Camera, microphone, speaker detection, network capability | Local device only | Yes — always |
| **TURN** | TURN server reachability + latency | ICE server configs (urls + username + credential) | Yes — via `provider.getIceServers()` |
| **Serenada server** | WS/SSE signaling reachability, room API, diagnostic token fetch | `serverHost` + Serenada HTTP endpoints | No — requires `serverHost` |

The TURN probe only needs ICE server configs to test connectivity. It doesn't care whether those configs came from the Serenada `/api/turn-credentials` endpoint or from `provider.getIceServers()`.

- **`runAll()`** — In server mode: runs all three tiers (current behavior). In provider mode: runs device + TURN tiers and returns the same `DiagnosticsReport` shape as today; the server-specific `signaling` field is populated as `skipped` with reason `requires serverHost`, while `turn` contains the TURN probe result. No sections are omitted.
- **`runTurnProbe()`** — New. Obtains ICE servers from whichever source is configured (`serverHost` → token + HTTP fetch, `signalingProvider` → `getIceServers()`), then tests reachability and measures latency. Works in both modes.
- **`runConnectivityChecks()`** — Serenada server-specific probes only. Throws if no `serverHost` configured.
- **Device checks** (camera, mic, speaker, network) — Always available regardless of mode.

### Web `subscribeToMessages()` in provider mode

The current Web SDK exposes `subscribeToMessages(callback: (message: SignalingMessage) => void)` on the public `SerenadaSessionHandle` interface. `SignalingMessage` uses the Serenada protocol envelope (`v`, `type`, `rid`, `cid`, `to`, `payload`).

With a custom provider, there is no Serenada envelope — messages arrive as `PeerMessage { from, type, payload }`.

**Chosen behavior:** soft-deprecate `subscribeToMessages` in favor of a new `onPeerMessage(callback)` API that works in both modes. `subscribeToMessages` continues to work with the built-in provider (returns Serenada envelope as today). In provider mode, it synchronously throws `Error("subscribeToMessages() is unavailable with custom signalingProvider; use onPeerMessage()")`.

Throwing is preferable to a silent no-op because it fails loudly for unsupported usage while preserving full backward compatibility for existing built-in-provider consumers. The method is primarily used for debug logging (`DebugPanel` in `react-ui`), not core application logic.

## Refactoring Strategy

The refactoring is internal to the SDK — no protocol changes, no server changes. One minor **API change**: `serverHost` becomes optional in `SerenadaConfig` on all platforms (breaking for callers that rely on it being non-optional, but the value is still required for server-based usage — the compiler error guides the fix).

### Phase 1: Extract interface (all platforms)

Define the `SignalingProvider` interface/protocol and the event types. No behavior change.

### Phase 2: Make `serverHost` optional, add `signalingProvider` to config

Update `SerenadaConfig` on all platforms. Add validation (exactly one of `serverHost` / `signalingProvider`). Existing code that passes `serverHost` continues to compile on Android/iOS (Kotlin/Swift default args). Web TypeScript callers that destructure the non-optional `serverHost` get a compile error and must add `!` or handle the optional — this is the one surface-level break.

### Phase 3: Wrap existing signaling as SerenadaServerProvider

Create `SerenadaServerProvider` that wraps the existing `SignalingEngine`/`SignalingClient`:
- Translates Serenada protocol messages → `SignalingProvider` events
- Translates `SignalingProvider` actions → Serenada protocol messages
- Wraps the TURN token + HTTP fetch flow inside `getIceServers()`

### Phase 4: Rewire session to use interface

Modify `SerenadaSession` on each platform to program against `SignalingProvider` instead of the concrete signaling client:
- Replace direct `signaling.connect()` / `signaling.joinRoom()` calls with provider methods
- Replace message subscription with provider event listeners
- Replace TurnManager's HTTP fetch with `provider.getIceServers()`
- Add `onPeerMessage()` to public session API (Web)
- Split `SerenadaDiagnostics` into device checks (always) + server checks (gated on `serverHost`)

### Phase 5: Gate server-bound APIs

Add `serverHost`-presence checks to `createRoom()`, `createRoomId()`, `RoomWatcher`, and `runConnectivityChecks()`. Throw clear errors when called without `serverHost`.

## Scope Estimate

| Component | Lines (est.) |
|-----------|-------------|
| **Web** | |
| `SignalingProvider` interface + event types | ~70 |
| `SerenadaServerProvider` (wraps SignalingEngine + TurnManager) | ~120 |
| `SerenadaSession` rewire to use provider + `onPeerMessage()` | ~90 |
| `SerenadaConfig` optional `serverHost` + validation | ~15 |
| `SerenadaCore` entry point changes + server-bound API gating | ~25 |
| `SerenadaDiagnostics` split device vs. server checks | ~20 |
| **Android** | |
| `SignalingProvider` interface + event types | ~70 |
| `SerenadaServerProvider` (wraps SignalingClient + TurnManager) | ~100 |
| `SerenadaSession` rewire to use provider | ~60 |
| `SerenadaConfig` optional `serverHost` + validation | ~10 |
| `SerenadaCore` entry point changes + server-bound API gating | ~20 |
| `SerenadaDiagnostics` split device vs. server checks | ~15 |
| **iOS** | |
| `SignalingProvider` protocol + event types | ~70 |
| `SerenadaServerProvider` (wraps SignalingClient + TurnManager) | ~100 |
| `SerenadaSession` rewire to use provider | ~60 |
| `SerenadaConfig` optional `serverHost` + validation | ~10 |
| `SerenadaCore` entry point changes + server-bound API gating | ~20 |
| `SerenadaDiagnostics` split device vs. server checks | ~15 |
| **Total** | **~890** |

Android and iOS are slightly smaller because their sessions already use listener/protocol patterns. Web requires more rewiring due to tighter coupling and the `subscribeToMessages` deprecation path.

## Files Affected

### Web (`client/packages/core/`)
- New: `src/SignalingProvider.ts` — interface + event types (`ConnectionInfo`, `RoomStateEvent`, etc.)
- New: `src/SerenadaServerProvider.ts` — wraps SignalingEngine + TurnManager
- `src/types.ts` — `SerenadaConfig.serverHost` becomes optional, add `signalingProvider`; add `onPeerMessage` to `SerenadaSessionHandle`
- `src/SerenadaSession.ts` — use `SignalingProvider` instead of `SignalingEngine`; add `onPeerMessage()`; soft-deprecate `subscribeToMessages()` in provider mode
- `src/SerenadaCore.ts` — config validation (exactly one of `serverHost`/`signalingProvider`); gate `createRoom()`/`createRoomId()` on `serverHost`
- `src/SerenadaDiagnostics.ts` — split `runAll()` (device + TURN in provider mode; `signaling` marked `skipped`); add `runTurnProbe()`; gate `runConnectivityChecks()` on `serverHost`
- `src/media/MediaEngine.ts` — receive ICE servers from session (via provider), not via direct HTTP fetch

### Android (`client-android/serenada-core/`)
- New: `SignalingProvider.kt` — interface + event types + threading contract
- New: `SerenadaServerProvider.kt` — wraps SignalingClient + TurnManager
- `SerenadaConfig.kt` — `serverHost` becomes `String?`, add `signalingProvider`
- `SerenadaSession.kt` — use `SignalingProvider` instead of `SessionSignaling`
- `SerenadaCore.kt` — config validation; gate `createRoom()`/`createRoomId()` on `serverHost`
- `SerenadaDiagnostics.kt` — split device vs. server checks
- `call/TurnManager.kt` — becomes internal to `SerenadaServerProvider`

### iOS (`client-ios/SerenadaCore/`)
- New: `Sources/SignalingProvider.swift` — protocol + event types + `@MainActor` delegate
- New: `Sources/SerenadaServerProvider.swift` — wraps SignalingClient + TurnManager
- `Sources/SerenadaConfig.swift` — `serverHost` becomes `String?`, add `signalingProvider`
- `Sources/SerenadaSession.swift` — use `SignalingProvider` instead of `SessionSignaling`
- `Sources/SerenadaCore.swift` — config validation; gate `createRoom()`/`createRoomId()` on `serverHost`
- `Sources/SerenadaDiagnostics.swift` — split device vs. server checks
- `Sources/Call/TurnManager.swift` — becomes internal to `SerenadaServerProvider`

### Not changed
- `SignalingEngine.ts` / `SignalingClient.kt` / `SignalingClient.swift` — existing transport code, now wrapped by `SerenadaServerProvider`
- `WebRtcEngine.kt` / `WebRtcEngine.swift` — unchanged (ICE servers applied by session)
- All UI packages (`react-ui`, `serenada-call-ui`, `SerenadaCallUI`)
- Server code
- Sample apps (updated to show third-party adapter usage)

## Relationship to Scaling Plan

This plan and the [scaling architecture plan](scaling-architecture-plan.md) are independent and complementary:

| Deployment scenario | Signaling | Scaling approach |
|-------------------|-----------|-----------------|
| **Standalone Serenada** | Built-in `SerenadaServerProvider` | Room-affinity + Redis (scaling plan) |
| **Third-party integration** | Custom `SignalingProvider` adapter | Integrator's infrastructure (no Serenada server) |
| **Hybrid** (third-party signaling + Serenada TURN) | Custom adapter, `getIceServers()` calls Serenada TURN endpoint | TURN endpoint is stateless, trivially scalable behind round-robin LB |

For standalone Serenada, the scaling plan applies as-is — the `SerenadaServerProvider` wraps the existing signaling and the `rid`-on-URL requirement from the scaling plan is internal to that provider.

For third-party integrations, the scaling problem disappears entirely — the integrator's messaging system handles delivery at whatever scale they need.

## Test Strategy

### Unit tests (per platform)
1. **Interface conformance** — `SerenadaServerProvider` implements `SignalingProvider` and produces correct events for all Serenada protocol messages
2. **Mock provider** — `SerenadaSession` works correctly with a mock `SignalingProvider` (join → joined → peer messages → leave)
3. **ICE server flow** — session calls `getIceServers()` after join and applies result to media engine
4. **Room state reconciliation** — session correctly rebuilds peer map from `roomStateUpdated` snapshots; verify `joinedAt`-based offer ownership is consistent after reconnect with missed incremental events
5. **Callback threading (Android)** — verify provider callbacks invoked on background thread cause assertion failure or are marshaled; verify main-thread callbacks work correctly
6. **Callback threading (iOS)** — verify `@MainActor` constraint is enforced on delegate callbacks
7. **Transport diagnostics** — verify `ConnectionInfo.transport` propagates to session diagnostics for built-in provider; verify omitted `transport` does not break session
8. **Server-bound API gating** — verify `createRoom()`, `createRoomId()`, `RoomWatcher`, and `runConnectivityChecks()` throw when config has no `serverHost`; verify config rejects both `serverHost` + `signalingProvider` set simultaneously, and neither set
9. **Diagnostics three-tier split** — verify `runAll()` returns device + TURN results in provider mode and marks `signaling` as `skipped`; verify `runTurnProbe()` works with custom provider's `getIceServers()`; verify `runConnectivityChecks()` throws in provider mode
10. **Web `subscribeToMessages()` deprecation** — verify returns Serenada envelope with built-in provider (unchanged); verify it throws the documented error in provider mode; verify `onPeerMessage()` works in both modes

### Integration tests
11. **Built-in provider end-to-end** — existing call flows work identically after refactoring (regression)
12. **Third-party adapter smoke test** — minimal adapter using an in-memory message bus, verify two sessions can complete offer/answer/ICE exchange and establish media

### Sample apps
13. **Update existing samples** to show both built-in and custom provider usage
14. **New sample** — minimal third-party adapter example (e.g., using a WebSocket relay as a stand-in for the integrator's system)
