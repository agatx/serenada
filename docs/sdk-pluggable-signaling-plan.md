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
  // --- Interface version ---
  // Current version: 1. The session checks this at construction and rejects
  // providers with an unsupported version. Future SDK releases may add optional
  // methods and bump the version — providers at an older version continue to
  // work as long as the session supports that version.
  readonly version: number  // must be 1

  // --- Lifecycle ---
  connect(): void
  disconnect(): void

  // --- Room actions ---
  joinRoom(roomId: string, options?: JoinOptions): void
  leaveRoom(): void
  endRoom(): void

  // --- Peer messaging ---
  // Fire-and-forget: no delivery confirmation. The provider is not expected
  // to guarantee delivery — WebRTC negotiation tolerates message loss
  // (the session retries offers/ICE internally).
  sendToPeer(peerId: string, type: string, payload: unknown): void
  broadcast(type: string, payload: unknown): void

  // --- ICE servers ---
  // Provider is responsible for sourcing TURN credentials.
  // Called once after join to obtain initial ICE server configs.
  // On failure: the session retries with exponential backoff (1s, 2s, 4s)
  // up to 3 attempts. If all attempts fail, the session transitions to
  // 'failed' state with error code 'ice_server_fetch_failed'. Providers
  // should reject the promise with an Error — do not resolve with an
  // empty array to indicate failure.
  //
  // Credential refresh: the provider owns the TTL. The session does NOT
  // poll this method. When credentials rotate, the provider emits
  // 'iceServersChanged' with fresh configs. The session applies them
  // to existing and future peer connections. See the event below.
  getIceServers(): Promise<RTCIceServer[]>

  // --- Capabilities ---
  // Declares optional features the provider supports. The session adapts
  // its behavior based on these flags. All flags default to false if omitted.
  // Currently only contains handlesReconnection; the capabilities object
  // exists as an extension point for future flags.
  readonly capabilities?: ProviderCapabilities

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
  // Provider emits this when TURN credentials rotate (provider owns the TTL).
  // The session applies the new configs to all peer connections.
  on(event: 'iceServersChanged', cb: (servers: RTCIceServer[]) => void): void
  off(event: string, cb: Function): void
}

// Provider capabilities. All flags default to false when omitted.
interface ProviderCapabilities {
  // When true, the provider handles transport-level reconnection internally
  // and fires disconnected/connected events to signal interruptions. The
  // session treats disconnected → connected as a transport blip and
  // re-sends offers to known peers to re-validate connections.
  //
  // When false (default), the session assumes the transport is stable once
  // connected. If the provider fires 'disconnected', the session transitions
  // to reconnecting state and calls joinRoom() again with
  // reconnectPeerId set to re-establish presence.
  handlesReconnection?: boolean
}

// Optional metadata about the underlying transport (built-in provider
// populates this; third-party adapters may omit or use custom values).
interface ConnectionInfo {
  transport?: string            // e.g., 'ws', 'sse', or adapter-specific
}

// Optional full participant snapshot. When fired, the session reconciles its
// peer map against the snapshot, cleaning up stale peers and discovering new
// ones. Useful for reconnect recovery and systems with reliable membership
// lists. Not required — the session also builds state from incremental
// peerJoined/peerLeft events. Providers that support snapshots should fire
// this on initial join and whenever the authoritative participant list changes.
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
  joinedAt?: number             // ms timestamp — informational only (e.g., UI
                                // "joined 2m ago"); not used for offer ownership
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
    // Interface version — must be 1
    val version: Int

    // Lifecycle
    fun connect()
    fun disconnect()

    // Room actions
    fun joinRoom(roomId: String, options: JoinOptions = JoinOptions())
    fun leaveRoom()
    fun endRoom()

    // Peer messaging (fire-and-forget, no delivery confirmation)
    fun sendToPeer(peerId: String, type: String, payload: JSONObject)
    fun broadcast(type: String, payload: JSONObject)

    // ICE servers (session retries 3x with backoff on failure)
    suspend fun getIceServers(): List<PeerConnection.IceServer>

    // Capabilities (all default to false)
    val capabilities: ProviderCapabilities get() = ProviderCapabilities()

    // Listener
    var listener: Listener?

    // Callbacks may be invoked on any thread. The session wraps the listener
    // with a trampoline that dispatches to the main looper if needed.
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

data class ProviderCapabilities(
    val handlesReconnection: Boolean = false,
)
```

#### iOS (Swift)

```swift
protocol SignalingProvider: AnyObject {
    // Interface version — must be 1
    var version: Int { get }

    // Lifecycle
    func connect()
    func disconnect()

    // Room actions
    func joinRoom(roomId: String, options: JoinOptions)
    func leaveRoom()
    func endRoom()

    // Peer messaging (fire-and-forget, no delivery confirmation)
    func sendToPeer(peerId: String, type: String, payload: [String: Any])
    func broadcast(type: String, payload: [String: Any])

    // ICE servers (session retries 3x with backoff on failure)
    func getIceServers() async throws -> [IceServerConfig]

    // Capabilities (all default to false)
    var capabilities: ProviderCapabilities { get }

    // Delegate
    var delegate: SignalingProviderDelegate? { get set }
}

struct ProviderCapabilities {
    var handlesReconnection: Bool = false
}

// Delegate callbacks may be invoked on any thread/actor. The session wraps
// the delegate with a trampoline that dispatches to @MainActor if needed.
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

**Lexicographic offer ownership.** In a WebRTC peer pair, one side must create the offer and the other must answer. The SDK uses **lexicographic peer ID comparison** to determine this: the peer whose ID sorts first (string comparison) creates the offer. This is deterministic, requires no server state, and works identically regardless of whether the provider is built-in or third-party.

The existing codebase uses `joinedAt`-based ordering (earlier joiner offers) with lexicographic CID as a tie-breaker. Since the tie-breaker already works correctly in isolation, this refactoring switches all platforms to lexicographic-only. The `joinedAt` timestamp on `Participant` becomes purely informational (e.g., for "joined 2m ago" UI). This eliminates a provider contract dependency on synchronized timestamps and removes a code path that would otherwise need to branch per-provider.

The existing fallback offer mechanism (500-2000ms timeout where the non-offerer sends an offer anyway if one doesn't arrive) provides resilience regardless of the ownership scheme.

**Room-state snapshots are optional.** The `roomStateUpdated` event carries a full participant list that the session can use to reconcile its peer map — cleaning up stale peers and discovering new ones. This is useful for reconnect recovery and for providers with reliable membership APIs. However, it is not required: the session also builds and maintains state from incremental `peerJoined`/`peerLeft` events. Stale peers (from missed `peerLeft` events) are naturally cleaned up when their WebRTC connections fail. Providers that support snapshots should fire `roomStateUpdated` on membership changes; providers that only have incremental presence can omit it entirely.

**Defensive callback threading.** The current SDK sessions are main-thread constrained: Android sessions run on the main looper, iOS signaling is `@MainActor`. Rather than requiring third-party providers to marshal callbacks to the correct thread (and crashing deep in `PeerNegotiationEngine` when they forget), the session wraps the provider's listener/delegate with a thin trampoline that dispatches to the main thread (Android) or `@MainActor` (iOS) if the callback arrives on a background thread. This is cheap (`Handler.post` / `MainActor.run`) and dramatically improves developer experience — the most common third-party integration mistake (forgetting `runOnUiThread` when using OkHttp, gRPC, or MQTT callbacks) results in silent correct behavior instead of a crash blamed on the SDK. The Web SDK uses single-threaded JS, so this is implicit.

**Transport diagnostics via `ConnectionInfo`.** The current SDK exposes `activeTransport` (`ws` vs `sse`) in call diagnostics. The `connected` event carries an optional `ConnectionInfo` with a `transport` field. The built-in provider populates this with `'ws'` or `'sse'`. Third-party adapters can provide their own transport descriptor (e.g., `'mqtt'`, `'grpc'`) or omit it — the session treats the field as diagnostic-only and does not branch on its value.

**Peer IDs, not Client IDs.** The interface uses `peerId` instead of Serenada's `cid` (client ID). The built-in provider maps `cid` → `peerId`. Third-party adapters use whatever identifier their system provides.

**ICE servers via `getIceServers()` with defined failure contract.** The current SDK has a multi-step TURN flow: server sends `turnToken` → `TurnManager` makes HTTP call to `/api/turn-credentials`. The interface replaces this with a single `getIceServers()` method. The provider is responsible for sourcing credentials from whatever backend it uses. The built-in provider wraps the existing token + HTTP fetch flow internally.

On failure: providers should reject/throw with an `Error`. The session retries with exponential backoff (1s, 2s, 4s) up to 3 attempts. If all attempts fail, the session transitions to `failed` state with error code `ice_server_fetch_failed`. Providers must not resolve with an empty array to indicate failure — an empty array means "no TURN servers needed" (STUN-only), which is a valid configuration.

**ICE credential refresh: provider owns the TTL.** The session calls `getIceServers()` once after join to obtain initial configs. It does not poll or re-call this method on a timer — WebRTC itself cannot detect credential expiration until an ICE restart fails. Instead, the provider owns the credential lifecycle: when the backend signals rotation (or when the provider's internal TTL timer fires), the provider emits `iceServersChanged` with fresh configs. The session applies these to all existing and future peer connections. The built-in `SerenadaServerProvider` handles this internally (the Serenada server pushes `turn_credentials` messages when credentials rotate). Third-party providers should set up their own refresh timer based on the TTL returned by their TURN credential API.

**No protocol envelope.** The interface deals in `type` + `payload`, not the Serenada JSON envelope (`v`, `type`, `rid`, `sid`, `cid`, `to`). The built-in provider wraps/unwraps the envelope. Third-party adapters use whatever framing their system provides.

**Room actions are optional for third-party.** `joinRoom()` / `leaveRoom()` / `endRoom()` map to the integrator's group management. If their system doesn't have explicit join/leave (e.g., presence is implicit), the adapter can no-op these and fire `onJoined`/`onPeerJoined` based on their system's events.

**Host concept is optional.** `hostPeerId` in `JoinedEvent` is nullable. If the third-party system doesn't have a host concept, the adapter omits it. The UI layer treats all participants as peers.

**Reconnection ownership is explicit via capabilities.** The `capabilities.handlesReconnection` flag determines who drives reconnection:

- **Provider-managed reconnection** (`handlesReconnection: true`): The provider handles transport-level reconnection internally (retries, backoff, etc.). When the transport drops and recovers, the provider fires `disconnected` followed by `connected`. The session treats this as a transport blip: it does not call `joinRoom()` again, but re-sends offers to known peers to re-validate connections. If the provider fires a `roomStateUpdated` snapshot after reconnecting, the session also reconciles its peer map against it.

- **Session-managed reconnection** (`handlesReconnection: false`, the default): The session owns reconnection. When the provider fires `disconnected`, the session transitions to `reconnecting` state and calls `joinRoom()` again with `reconnectPeerId` set to the current peer ID, allowing the provider to re-establish presence with the same identity. The session applies its standard reconnection backoff timing from `WebRtcResilienceConstants`.

The built-in `SerenadaServerProvider` sets `handlesReconnection: true` because it wraps the existing reconnection logic (grace periods, reconnect tokens). Most third-party adapters should use `false` (the default) and let the session drive reconnection.

**Interface versioning.** The `version` field (currently `1`) allows the SDK to detect incompatible providers at construction time rather than failing at runtime. Future SDK releases that add optional methods will bump the supported version range. Providers at an older version continue to work as long as the SDK still supports that version. This avoids compile-time breaks for existing adapters when new optional capabilities are added.

**`subscribeToMessages()` removed.** The Web SDK previously exposed `subscribeToMessages()` on `SerenadaSessionHandle`, which returned raw Serenada protocol envelopes. Since the SDK is not yet publicly used, this method is removed entirely rather than deprecated. The replacement is `onPeerMessage(callback)`, which works in both built-in and custom provider modes and uses the transport-agnostic `PeerMessage` type.

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

A third-party adapter is typically ~50-100 lines per platform. The adapter must implement the event emitter pattern (`on`/`off`) — the SDK exports a `SignalingProviderEmitter` base class that provides this (or integrators can use any EventEmitter implementation).

Example (minimal integration — no snapshots needed, lexicographic offer ownership is automatic):

```typescript
class MySignalingAdapter extends SignalingProviderEmitter implements SignalingProvider {
  readonly version = 1
  private channel: MyMessagingChannel  // integrator's messaging SDK

  // handlesReconnection defaults to false — session drives reconnection

  connect() {
    this.channel.connect()
    this.channel.onReady(() => this.emit('connected', { transport: 'my-system' }))
  }

  joinRoom(roomId: string) {
    this.channel.joinGroup(roomId)

    // Fire joined with current members
    const members = this.channel.getMembers()
    this.emit('joined', {
      peerId: this.channel.myId,
      participants: members.map(m => ({ peerId: m.id })),
    })

    // Incremental presence updates
    this.channel.onMemberJoined((member) => {
      this.emit('peerJoined', { peerId: member.id })
    })
    this.channel.onMemberLeft((member) => {
      this.emit('peerLeft', { peerId: member.id })
    })
    this.channel.onMessage((from, data) => {
      this.emit('message', { from: from.id, type: data.type, payload: data.payload })
    })
  }

  sendToPeer(peerId: string, type: string, payload: unknown) {
    this.channel.sendTo(peerId, { type, payload })
  }

  broadcast(type: string, payload: unknown) {
    this.channel.broadcast({ type, payload })
  }

  async getIceServers(): Promise<RTCIceServer[]> {
    // Integrator's own TURN, or Cloudflare, or any provider.
    // Throw on failure — do NOT return [] to indicate error.
    const creds = await myTurnService.getCredentials()
    return [{ urls: creds.urls, username: creds.username, credential: creds.credential }]
  }

  // ... disconnect, leaveRoom, endRoom, etc.
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
| `RoomWatcher(serverUrl)` | Works (uses `watch_rooms` protocol) | Throws: "requires serverHost" (integrators use their own presence/status system) |
| `SerenadaDiagnostics.runAll()` | Full suite (device + TURN + server probes) | **Device + TURN checks** (see below) |
| `SerenadaDiagnostics.runConnectivityChecks()` | Serenada server probes (WS, SSE, room API, diagnostic token) | Throws: "requires serverHost" |
| `SerenadaDiagnostics.runTurnProbe()` | TURN reachability via server-issued token | TURN reachability via `provider.getIceServers()` |
| `isSupported()` | Works | Works |
| `onPeerMessage()` (Web) | Works (translates from Serenada envelope) | Works (passes through `PeerMessage`) |

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

## Refactoring Strategy

The refactoring is mostly internal to the SDK — no server changes. **Changes:**

- **Protocol**: Section 5.1 of `serenada_protocol_v1.md` ("Roles for offer/answer") is updated to specify lexicographic peer ID comparison instead of `(joinedAt, cid)` ordering. The `joinedAt` field remains in the protocol for informational purposes but is no longer referenced by the offer ownership rule. This is a behavioral change but transparent to users — offer/answer assignment is an internal mechanism, and the fallback offer timer (500-2000ms) ensures correct WebRTC setup regardless of which peer initiates.
- **API**: `serverHost` becomes optional in `SerenadaConfig` on all platforms; `subscribeToMessages()` is removed from the Web SDK (replaced by `onPeerMessage()`). Since the SDK is not yet publicly used, these are non-breaking in practice.

All three platforms must land the `shouldIOffer()` change simultaneously to avoid glare between mismatched clients. The fallback offer mechanism provides resilience during any rollout window.

### Phase 1: Extract interface (all platforms)

Define the `SignalingProvider` interface/protocol, the event types, `ProviderCapabilities`, and `SignalingProviderEmitter` base class (Web). No behavior change yet.

### Phase 2: Make `serverHost` optional, add `signalingProvider` to config

Update `SerenadaConfig` on all platforms. Add validation (exactly one of `serverHost` / `signalingProvider`). Existing code that passes `serverHost` continues to compile on Android/iOS (Kotlin/Swift default args). Web TypeScript callers that destructure the non-optional `serverHost` get a compile error and must add `!` or handle the optional — this is the one surface-level break.

### Phase 3: Wrap existing signaling as SerenadaServerProvider

Create `SerenadaServerProvider` that wraps the existing `SignalingEngine`/`SignalingClient`:
- Translates Serenada protocol messages → `SignalingProvider` events
- Translates `SignalingProvider` actions → Serenada protocol messages
- Wraps the TURN token + HTTP fetch flow inside `getIceServers()`
- Sets `capabilities: { handlesReconnection: true }`

### Phase 4: Rewire session to use interface

Modify `SerenadaSession` on each platform to program against `SignalingProvider` instead of the concrete signaling client:
- Replace direct `signaling.connect()` / `signaling.joinRoom()` calls with provider methods
- Replace message subscription with provider event listeners
- Replace TurnManager's HTTP fetch with `provider.getIceServers()` + retry logic (3 attempts, exponential backoff)
- Switch `shouldIOffer()` on all platforms from `joinedAt`-based to lexicographic peer ID comparison (one code path, no branching)
- Branch reconnection logic: transport-blip handling when `handlesReconnection`, session-driven `joinRoom()` retry otherwise
- Make `roomStateUpdated` handling opportunistic: reconcile peer map when received, but don't require it
- Remove `subscribeToMessages()`; add `onPeerMessage()` to public session API (Web)
- Update `SerenadaCallFlow` in `react-ui` to use `onPeerMessage()` instead of `subscribeToMessages()`
- Split `SerenadaDiagnostics` into device checks (always) + server checks (gated on `serverHost`)

### Phase 5: Gate server-bound APIs

Add `serverHost`-presence checks to `createRoom()`, `createRoomId()`, `RoomWatcher`, and `runConnectivityChecks()`. Throw clear errors when called without `serverHost`.

## Scope Estimate

| Component | Lines (est.) |
|-----------|-------------|
| **Web** | |
| `SignalingProvider` interface + event types + `ProviderCapabilities` | ~80 |
| `SignalingProviderEmitter` base class | ~30 |
| `SerenadaServerProvider` (wraps SignalingEngine + TurnManager) | ~130 |
| `SerenadaSession` rewire (provider, `getIceServers` retry, reconnection branching) | ~130 |
| `SerenadaSession` remove `subscribeToMessages()` + add `onPeerMessage()` | ~20 |
| `MediaEngine` / `PeerNegotiationEngine` switch `shouldIOffer` to lexicographic | ~10 |
| `SerenadaConfig` optional `serverHost` + validation | ~15 |
| `SerenadaCore` entry point changes + server-bound API gating | ~25 |
| `SerenadaDiagnostics` split device vs. server checks | ~20 |
| `react-ui` `SerenadaCallFlow` update (`subscribeToMessages` → `onPeerMessage`) | ~10 |
| **Android** | |
| `SignalingProvider` interface + event types + `ProviderCapabilities` | ~70 |
| `SerenadaServerProvider` (wraps SignalingClient + TurnManager) | ~110 |
| `SerenadaSession` rewire (provider, `getIceServers` retry, reconnection branching) | ~80 |
| `PeerNegotiationEngine` switch `shouldIOffer` to lexicographic | ~5 |
| `SerenadaConfig` optional `serverHost` + validation | ~10 |
| `SerenadaCore` entry point changes + server-bound API gating | ~20 |
| `SerenadaDiagnostics` split device vs. server checks | ~15 |
| **iOS** | |
| `SignalingProvider` protocol + event types + `ProviderCapabilities` | ~70 |
| `SerenadaServerProvider` (wraps SignalingClient + TurnManager) | ~110 |
| `SerenadaSession` rewire (provider, `getIceServers` retry, reconnection branching) | ~80 |
| `PeerNegotiationEngine` switch `shouldIOffer` to lexicographic | ~5 |
| `SerenadaConfig` optional `serverHost` + validation | ~10 |
| `SerenadaCore` entry point changes + server-bound API gating | ~20 |
| `SerenadaDiagnostics` split device vs. server checks | ~15 |
| **Total** | **~1,090** |

Web is the largest due to tighter coupling, the `subscribeToMessages` removal, the `SignalingProviderEmitter` base class, and the `SerenadaCallFlow` update. Android and iOS are slightly smaller because their sessions already use listener/protocol patterns. The `shouldIOffer` change is small on each platform (~5-10 lines) because it simplifies from a two-criterion comparison to a single lexicographic check. The `getIceServers()` retry logic and reconnection branching account for the remaining scope increase.

## Files Affected

### Web (`client/packages/core/`)
- New: `src/SignalingProvider.ts` — interface + event types + `ProviderCapabilities` + `SignalingProviderEmitter` base class
- New: `src/SerenadaServerProvider.ts` — wraps SignalingEngine + TurnManager
- `src/types.ts` — `SerenadaConfig.serverHost` becomes optional, add `signalingProvider`; remove `subscribeToMessages` from `SerenadaSessionHandle`; add `onPeerMessage`
- `src/SerenadaSession.ts` — use `SignalingProvider` instead of `SignalingEngine`; add `onPeerMessage()`; remove `subscribeToMessages()`; reconnection branching; `getIceServers()` retry logic
- `src/media/MediaEngine.ts` — switch `shouldIOffer()` from `joinedAt`-based to lexicographic peer ID; receive ICE servers from session (via provider), not via direct HTTP fetch
- `src/SerenadaCore.ts` — config validation (exactly one of `serverHost`/`signalingProvider`); gate `createRoom()`/`createRoomId()` on `serverHost`
- `src/SerenadaDiagnostics.ts` — split `runAll()` (device + TURN in provider mode; `signaling` marked `skipped`); add `runTurnProbe()`; gate `runConnectivityChecks()` on `serverHost`

### Web (`client/packages/react-ui/`)
- `src/SerenadaCallFlow.tsx` — replace `subscribeToMessages()` with `onPeerMessage()` (used for `content_state` message handling)

### Android (`client-android/serenada-core/`)
- New: `SignalingProvider.kt` — interface + event types + threading contract
- New: `SerenadaServerProvider.kt` — wraps SignalingClient + TurnManager
- `SerenadaConfig.kt` — `serverHost` becomes `String?`, add `signalingProvider`
- `SerenadaSession.kt` — use `SignalingProvider` instead of `SessionSignaling`
- `call/PeerNegotiationEngine.kt` — switch `shouldIOffer()` from `joinedAt`-based to lexicographic peer ID
- `SerenadaCore.kt` — config validation; gate `createRoom()`/`createRoomId()` on `serverHost`
- `SerenadaDiagnostics.kt` — split device vs. server checks
- `call/TurnManager.kt` — becomes internal to `SerenadaServerProvider`

### iOS (`client-ios/SerenadaCore/`)
- New: `Sources/SignalingProvider.swift` — protocol + event types + `@MainActor` delegate
- New: `Sources/SerenadaServerProvider.swift` — wraps SignalingClient + TurnManager
- `Sources/SerenadaConfig.swift` — `serverHost` becomes `String?`, add `signalingProvider`
- `Sources/SerenadaSession.swift` — use `SignalingProvider` instead of `SessionSignaling`
- `Sources/Call/PeerNegotiationEngine.swift` — switch `shouldIOffer()` from `joinedAt`-based to lexicographic peer ID
- `Sources/SerenadaCore.swift` — config validation; gate `createRoom()`/`createRoomId()` on `serverHost`
- `Sources/SerenadaDiagnostics.swift` — split device vs. server checks
- `Sources/Call/TurnManager.swift` — becomes internal to `SerenadaServerProvider`

### Documentation (`docs/`)
- `serenada_protocol_v1.md` — update section 5.1 ("Roles for offer/answer") from `(joinedAt, cid)` ordering to lexicographic peer ID comparison; note `joinedAt` is informational

### Not changed
- `SignalingEngine.ts` / `SignalingClient.kt` / `SignalingClient.swift` — existing transport code, now wrapped by `SerenadaServerProvider`
- `WebRtcEngine.kt` / `WebRtcEngine.swift` — unchanged (ICE servers applied by session)
- Native UI packages (`serenada-call-ui`, `SerenadaCallUI`) — no signaling dependency
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
1. **Interface conformance** — `SerenadaServerProvider` implements `SignalingProvider`, reports `version: 1` and correct capabilities, and produces correct events for all Serenada protocol messages
2. **Mock provider (with snapshots)** — `SerenadaSession` works correctly with a mock provider that fires `roomStateUpdated` (join → joined → roomStateUpdated → peer messages → leave); verify session reconciles peer map from snapshots
3. **Mock provider (incremental only)** — `SerenadaSession` works correctly with a mock provider that never fires `roomStateUpdated` (join → joined → peerJoined/peerLeft → peer messages → leave); verify session builds peer map from incremental events alone
4. **Lexicographic offer ownership** — verify `shouldIOffer()` produces consistent, deterministic results: the peer whose ID sorts first always creates the offer, regardless of join order or `joinedAt` values
5. **ICE server flow — success** — session calls `getIceServers()` after join and applies result to media engine
6. **ICE server flow — retry** — mock provider `getIceServers()` fails twice then succeeds; verify session retries with backoff and ultimately applies servers
7. **ICE server flow — exhausted retries** — mock provider `getIceServers()` fails 3 times; verify session transitions to `failed` state with `ice_server_fetch_failed` error code
8. **ICE server flow — empty array** — mock provider returns `[]`; verify session treats this as valid STUN-only config (no error)
9. **ICE credential refresh** — mock provider emits `iceServersChanged` with new configs mid-call; verify session applies them to existing peer connections; verify session does not re-call `getIceServers()`
10. **Reconnection (provider-managed)** — mock provider with `handlesReconnection: true` fires `disconnected` → `connected`; verify session does not call `joinRoom()` again; verify session re-sends offers to known peers
11. **Reconnection (session-managed)** — mock provider with `handlesReconnection: false` fires `disconnected`; verify session transitions to `reconnecting` and calls `joinRoom()` with `reconnectPeerId`; verify backoff timing matches `WebRtcResilienceConstants`
12. **Version check** — verify session rejects a provider with `version: 0` or `version: 2` at construction time with a clear error
13. **Callback threading (Android)** — verify provider callbacks invoked on a background thread are correctly trampolined to the main looper; verify main-thread callbacks work without double-dispatch
14. **Callback threading (iOS)** — verify provider delegate callbacks invoked off `@MainActor` are correctly trampolined to `MainActor`; verify on-actor callbacks work without double-dispatch
15. **Transport diagnostics** — verify `ConnectionInfo.transport` propagates to session diagnostics for built-in provider; verify omitted `transport` does not break session
16. **Server-bound API gating** — verify `createRoom()`, `createRoomId()`, `RoomWatcher`, and `runConnectivityChecks()` throw when config has no `serverHost`; verify config rejects both `serverHost` + `signalingProvider` set simultaneously, and neither set
17. **Diagnostics three-tier split** — verify `runAll()` returns device + TURN results in provider mode and marks `signaling` as `skipped`; verify `runTurnProbe()` works with custom provider's `getIceServers()`; verify `runConnectivityChecks()` throws in provider mode
18. **Web `onPeerMessage()`** — verify works in both built-in and custom provider modes; verify `subscribeToMessages` is removed from the public API

### Integration tests
19. **Built-in provider end-to-end** — existing call flows work identically after refactoring (regression); verify lexicographic offer ownership produces working calls
20. **Third-party adapter smoke test** — minimal adapter using an in-memory message bus (incremental presence only, no snapshots), verify two sessions can complete offer/answer/ICE exchange and establish media

### Sample apps
21. **Update existing samples** to show both built-in and custom provider usage
22. **New sample** — minimal third-party adapter example (e.g., using a WebSocket relay as a stand-in for the integrator's system)
