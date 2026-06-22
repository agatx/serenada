# Serenada SDK — API Reference

Published API docs are available at **https://agatx.github.io/serenada/** and are automatically regenerated whenever the SDK version changes.

## Runtime API Highlights

### Configuration modes

All SDKs validate `SerenadaConfig` at construction time. Provide exactly one of:

- Built-in mode: `serverHost`
- Provider mode: `signalingProvider`

Provider mode keeps the same session/call APIs, but server-bound helpers are unavailable. `createRoom()`, native `createRoomId()`, `RoomWatcher`, `validateServerHost()`, and `runConnectivityChecks()` require `serverHost` and fail with `requires serverHost` when used in provider mode.

### Media and negotiation configuration

All SDKs expose the same media/negotiation flags on `SerenadaConfig`:

- `defaultVideoEnabled`: controls whether camera capture starts enabled when video capture is available.
- `cameraModes`: restricts local camera capture modes and order. An empty list disables camera capture and hides camera controls, but does not disable screen sharing or remote video by itself.
- `videoMediaEnabled`: set to `false` for strict audio-only calls. The SDK disables camera capture, screen sharing, video transceivers, and remote video.
- `deferInitialAnswer`: set to `true` for provider-owned calls where the host peer may wait longer than the normal offer timeout for the first answer, such as PSTN pickup. The SDK suppresses first-answer offer-timeout/ICE-restart churn until the first answer is applied; later renegotiations use normal timing.

### `createRoom()`

Create a new room. Returns the room URL and ID. Call `join()` to start the call.

`createRoom()` is an async operation (suspending on Android/iOS, `Promise` on web) that contacts the Serenada server to allocate a room. It returns a `CreateRoomResult` containing:

| Field | Type | Description |
|-------|------|-------------|
| `url` | `URL` / `String` | The shareable room URL (e.g. `https://serenada.app/call/abc123`) |
| `roomId` | `String` | The room identifier extracted from the URL |

`createRoom()` does **not** join the room or create a session. To start the call, pass the returned URL to `join()`:

```typescript
// Web
const room = await serenada.createRoom()
const session = serenada.join(room.url)
```

```kotlin
// Android
val room = serenada.createRoom()
val session = serenada.join(url = room.roomUrl)
```

```swift
// iOS
let room = try await serenada.createRoom()
let session = serenada.join(url: room.url)
```

`createRoom()` is server mode only. In provider mode there is no Serenada room API.

### `join()`

Join a room and return a `SerenadaSession`. Accepts either a URL or a room ID:

| Parameter | Type | Description |
|-----------|------|-------------|
| `url` | `URL` / `String` | A full room URL (e.g. `https://serenada.app/call/abc123`) |
| `roomId` | `String` | A bare room ID (provider mode) |
| `displayName` | `String` (optional) | Display name for the local participant, sent to peers on join |

On web, `join()` accepts a URL string with an optional second argument `{ displayName? }`, or an options object `{ roomId, displayName? }`. On Android and iOS, `url` and `roomId` are separate named parameters.

### `SignalingProvider`

The public provider contract is available on all three SDKs:

- Lifecycle: `connect()`, `disconnect()`
- Room actions: `joinRoom(...)`, `leaveRoom()`, `endRoom()`
- Peer messaging: `sendToPeer(...)`, `broadcast(...)`
- ICE sourcing: `getIceServers()`
- Capability flags: `ProviderCapabilities`

Key contract rules:

- Public/provider-facing identifiers use `peerId`. Built-in `cid` naming stays internal to `SerenadaServerProvider`.
- `hostPeerId` is optional in `JoinedEvent` and `RoomStateEvent`.
- `roomStateUpdated` is optional. Incremental `peerJoined` / `peerLeft` is enough for a valid adapter.
- `iceServersChanged` refreshes both existing and future peer connections. Providers with time-limited TURN credentials must push fresh servers through it before expiry (the SDK fetches `getIceServers()` only once, at join). See "ICE-server sourcing and refresh" in the customization guide.
- `version` must be `1`.

### Reconnection ownership

`ProviderCapabilities.handlesReconnection` tells the session who owns reconnect:

- `true`: the provider owns transport reconnect and the session treats `disconnected -> connected` as a transport blip.
- `false` (default): the session performs its normal rejoin flow with `reconnectPeerId`.

This flag only changes reconnect ownership after the session has already started joining. The SDK still enforces the initial join hard-timeout on all platforms, including when `handlesReconnection = true`.

The built-in `SerenadaServerProvider` sets `handlesReconnection = true` on all platforms.

### Peer-message hooks

The web SDK exposes `session.onPeerMessage(callback)` for transport-agnostic peer messages. Use this for built-in signaling messages such as `content_state` and for custom provider-delivered message types.

`subscribeToMessages()` is not part of the public web API surface anymore. The supported public hook is `onPeerMessage(...)`.

### Call quality telemetry

The SDK computes aggregate call quality for host analytics and exposes it additively (no existing signatures changed):

- `session.callQualitySummary: CallQualitySummary | null` — an immutable snapshot (MOS estimate, audio `packetLossPct`, `medianLatencyMs` / `medianJitterMs`, `countDisconnects` / `countReconnects`, `totalDropoutDurationMs`). Updated live and finalized at call end; remains readable after the session stops. `mosScore` is `null` unless all of latency, jitter, and loss are available.
- `session.onConnectionEvent(callback)` (web; the equivalent delegate/observer callback on Android and iOS) — emits `ConnectionEvent`s: `reconnected` (with `downtimeMs` and a `networkLost` / `unknown` reason) and `reconnectFailed` (`timeout` / `networkConnectivity`).
- `CallStats` adds cumulative inbound counters `videoFramesDecoded`, `videoFramesDropped`, `audioPacketsLost`, `audioPacketsReceived`, so hosts can derive per-segment frame-drop and call-level packet loss from deltas (rather than averaging cumulative percentages).

The MOS heuristic and the reconnect-reason mapping are identical across web, Android, and iOS, and are guarded by `scripts/check-telemetry-parity.mjs`.

### Audio coordinator APIs

Android and iOS expose a pluggable audio coordination surface for hosts that need to own process-wide audio policy. The supported public surface is:

- `SerenadaConfig.audioCoordinator`: optional custom `SerenadaAudioCoordinator`.
- `SerenadaConfig.audioIntent`: `AudioIntent` passed to the coordinator during call activation.
- `SerenadaAudioCoordinator`: protocol/interface for activation, deactivation, route selection, mic mute notification, route streams, and external-audio events.
- Model types: `AudioDevice`, `AudioDeviceKind`, `AudioDeviceDirection`, `AudioDeviceStatus`, `BluetoothProfile`, `AudioIntent`, and `AudioCoordinatorEvent`.
- `SerenadaSession.availableAudioDevices`: coordinator-published route list.
- `SerenadaSession.currentAudioDevice`: selected or active output route.
- `SerenadaSession.isMicMuted`: effective microphone mute state.
- `SerenadaSession.isMicMutedByExternalAudio`: external-audio portion of the mute state.
- `SerenadaSession.selectAudioDevice(...)`: route selection request.
- `SerenadaSession.setMicMuted(...)`: user-requested microphone mute state.

The SDK's default audio coordinator is internal implementation detail. To use default behavior, leave `audioCoordinator` unset. To customize behavior, implement `SerenadaAudioCoordinator` and inject it through `SerenadaConfig`.

`AudioCoordinatorEvent.externalAudioStarted` / `ExternalAudioStarted` applies the configured external-audio mute and ducking policy. Use `playbackDuckingStarted` / `PlaybackDuckingStarted` for duck-only interruptions that should lower playback without muting capture.

`isMicMuted` is composed from user mute, external-audio mute, and input-route availability. `isMicMutedByExternalAudio` lets host UIs distinguish coordinator-driven external audio from a manual user mute.

### Diagnostics

Diagnostics now distinguish between provider-safe TURN probing and server-only connectivity checks:

- `runAll()`: runs device/network checks in both modes. In provider mode, signaling is reported as skipped and TURN uses provider ICE servers.
- `runTurnProbe(...)`: probes STUN/TURN reachability using built-in TURN credentials in server mode or `signalingProvider.getIceServers()` in provider mode.
- `runIceProbe(...)`: compatibility alias for `runTurnProbe(...)`.
- `runConnectivityChecks()`: server mode only.
- `validateServerHost()`: server mode only.

## Generate Docs Locally

### iOS (Swift DocC)

Swift Package Manager has built-in DocC support:

```bash
cd client-ios
swift package --package-path SerenadaCore generate-documentation --target SerenadaCore
swift package --package-path SerenadaCallUI generate-documentation --target SerenadaCallUI
```

Or from Xcode: Product → Build Documentation.

### Android (Dokka)

Dokka is configured in both `:serenada-core` and `:serenada-call-ui`:

```bash
cd client-android
./gradlew :serenada-core:dokkaHtml
./gradlew :serenada-call-ui:dokkaHtml
```

Output: `serenada-core/build/dokka/html/` and `serenada-call-ui/build/dokka/html/`.

### Web (TypeDoc)

TypeDoc configs live in each package:

```bash
cd client
npx typedoc --options packages/core/typedoc.json
npx typedoc --options packages/react-ui/typedoc.json
```

Output: `packages/core/docs/` and `packages/react-ui/docs/`.
