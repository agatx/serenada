# Serenada Web Sample App

Minimal web host app demonstrating Serenada SDK integration with React.

## What it does

- Accepts a call URL, creates a session, and renders `<SerenadaCallFlow>` using built-in Serenada signaling
- Creates a new room via `createSerenadaCore({ serverHost }).createRoom()` and joins explicitly with `join()`
- Starts a custom-provider demo backed by an in-memory `SignalingProvider`
- Shows provider-mode incremental `peerJoined` events plus `onPeerMessage()` delivery without Serenada transport
- Runs as a standalone Vite app inside this repository
- Resolves `@agatx/serenada-core` and `@agatx/serenada-react-ui` directly from local source in `client/packages/`

## Run in this repo

```bash
cd samples/web
npm install
npm run dev
```

Then open the local Vite URL, usually `http://localhost:5173`.

To verify a production build:

```bash
cd samples/web
npm run build
```

## Standalone setup outside this repo

If you want to copy the sample into another project instead of using the repo-local package:

```bash
npm install @agatx/serenada-core @agatx/serenada-react-ui lucide-react react react-dom react-qr-code
```

## Integration pattern

```tsx
import { createSerenadaCore } from '@agatx/serenada-core'
import { SerenadaCallFlow } from '@agatx/serenada-react-ui'

// 1. Initialize core
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

// Prefer SerenadaSessionHandle in app-facing code and component props.

// 2a. Join an existing invite link by URL
const callSession = serenada.join(callUrl)
<SerenadaCallFlow
  session={callSession}
  onEndCall={() => {
    callSession.leave()
    navigate('/')
  }}
  onDismiss={() => {
    callSession.destroy()
    navigate('/')
  }}
/>

// 2b. Create a room, then join explicitly.
const room = await serenada.createRoom()
const session = serenada.join(room.url)
<SerenadaCallFlow
  session={session}
  onDismiss={() => {
    session.destroy()
    navigate('/')
  }}
/>
```

Provider mode uses the same SDK package with an injected provider instead of `serverHost`:

```tsx
import {
  SignalingProviderEmitter,
  createSerenadaCore,
} from '@agatx/serenada-core'

class DemoProvider extends SignalingProviderEmitter {
  connect() { this.emit('connected', { transport: 'mock' }) }
  disconnect() {}
  joinRoom(roomId: string) {
    this.emit('joined', {
      peerId: 'sample-local',
      participants: [{ peerId: 'sample-local', joinedAt: 1 }],
    })
  }
  leaveRoom() {}
  endRoom() {}
  sendToPeer() {}
  broadcast() {}
  async getIceServers() { return [] }
}

const providerCore = createSerenadaCore({
  signalingProvider: new DemoProvider(),
})
const session = providerCore.join({ roomId: 'provider-demo-room' })
session.onPeerMessage((message) => console.log(message.type))
```

## Transport Visibility

The SDK exposes which signaling transport (WebSocket or SSE) is currently active.

```typescript
// Web — available on CallState
const state = session.state;
console.log(state.activeTransport); // 'ws' | 'sse' | null
```

On Android and iOS, the active transport is available on the diagnostics object:

```kotlin
// Android — available on CallDiagnostics
val diagnostics = session.diagnostics.value
println(diagnostics.activeTransport ?: "none") // "ws", "sse", or "none"
```

```swift
// iOS — available on CallDiagnostics
let diagnostics = session.diagnostics
print(diagnostics.activeTransport ?? "none") // "ws", "sse", or "none" if nil
```
