# Serenada SDK — API Reference

Published API docs are available at **https://agatx.github.io/serenada/** and are automatically regenerated whenever the SDK version changes.

## Runtime API Highlights

### Configuration modes

All SDKs validate `SerenadaConfig` at construction time. Provide exactly one of:

- Built-in mode: `serverHost`
- Provider mode: `signalingProvider`

Provider mode keeps the same session/call APIs, but server-bound helpers are unavailable. `createRoom()`, native `createRoomId()`, `RoomWatcher`, `validateServerHost()`, and `runConnectivityChecks()` require `serverHost` and fail with `requires serverHost` when used in provider mode.

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

On web, `join()` accepts a URL string or an options object `{ url?, roomId?, displayName? }`. On Android and iOS, `url` and `roomId` are separate named parameters.

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
- `iceServersChanged` refreshes both existing and future peer connections.
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
