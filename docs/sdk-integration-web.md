# Serenada SDK — Web Quick Start

## Requirements

- Node.js 18+
- React 18 or 19 (for `@serenada/react-ui`)
- TypeScript 5+ (recommended)

## Installation

```bash
npm install @serenada/core @serenada/react-ui lucide-react
```

`@serenada/core` is framework-agnostic vanilla TypeScript. `@serenada/react-ui` provides ready-made React components.

`react`, `react-dom`, and `lucide-react` are peer dependencies of `@serenada/react-ui`.

For local development within the Serenada monorepo, both packages are configured as npm workspaces under `client/packages/`.

## Quick Start — URL-First (Simplest)

```tsx
import { SerenadaCallFlow } from '@serenada/react-ui'

function CallPage() {
    const { roomId } = useParams()
    return (
        <SerenadaCallFlow
            url={`https://serenada.app/call/${roomId}`}
            onDismiss={() => navigate('/')}
        />
    )
}
```

That's it. `SerenadaCallFlow` handles permissions, joining, the in-call UI, and cleanup.

## Session-First (Host-Owned Setup)

Create a session before rendering UI to observe state early:

```tsx
import { createSerenadaCore } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

function CallPage() {
    const { roomId } = useParams()
    const [session] = useState(() =>
        serenada.join(`https://serenada.app/call/${roomId}`)
    )

    return (
        <SerenadaCallFlow
            session={session}
            onDismiss={() => navigate('/')}
        />
    )
}
```

## Create a Room

```typescript
const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

const room = await serenada.createRoom()
const shareUrl = room.url   // send to the other party
const session = room.session // already joining
```

## Core-Only Integration (No UI)

Use `@serenada/core` directly for a fully custom UI:

```typescript
import { createSerenadaCore } from '@serenada/core'

const serenada = createSerenadaCore({ serverHost: 'serenada.app' })
const session = serenada.join(callUrl)

// Observe state
session.subscribe((state) => {
    switch (state.phase) {
        case 'idle': break
        case 'awaitingPermissions':
            // Prompt for permissions, then call session.resumeJoin()
            break
        case 'joining': showSpinner(); break
        case 'waiting': showWaitingScreen(); break
        case 'inCall': showCallScreen(); break
        case 'ending': showEndingScreen(); break
        case 'error': showError(state.error); break
    }
})

// Media controls
session.toggleAudio()
session.toggleVideo()
session.flipCamera()

// Video streams
session.localStream       // MediaStream
session.remoteStreams      // Map<cid, MediaStream>

// Leave or end
session.leave()   // local exit, room stays open
session.end()     // terminates room for all
```

## React Hooks

For custom React UIs, use the provided hooks:

```tsx
import { useSerenadaSession, useCallState } from '@serenada/react-ui'

function CustomCallUI({ url }: { url: string }) {
    const { session } = useSerenadaSession({
        url,
        config: { serverHost: 'serenada.app' },
    })
    const state = useCallState(session)

    if (state.phase === 'waiting') return <WaitingScreen />
    if (state.phase === 'inCall') return <InCallScreen session={session} state={state} />
    return <LoadingScreen />
}
```

## Permissions Handling

In URL-first mode, `SerenadaCallFlow` automatically prompts for camera/microphone permissions.

In session-first or core-only mode, the host app owns the permission prompt. Set `session.onPermissionsRequired` before rendering:

```typescript
import { SerenadaPermissions } from '@serenada/react-ui'

session.onPermissionsRequired = async (permissions) => {
    const granted = await SerenadaPermissions.request(permissions)
    if (granted) session.resumeJoin()
    else session.cancelJoin()
}
```

## Waiting-Screen Host Actions

Use `waitingActions` to render host-app-specific actions below the built-in QR/share UI:

```tsx
<SerenadaCallFlow
    session={session}
    waitingActions={
        <button type="button" onClick={notifyInvitees}>
            Notify invitees
        </button>
    }
    onDismiss={() => navigate('/')}
/>
```

`inviteControlsEnabled` only affects the built-in invite UI. `waitingActions` still render when provided.

## Watching Room Status

For home screens or recent-room presence indicators, use `RoomWatcher`:

```typescript
import { RoomWatcher, getRoomStatusState } from '@serenada/core'

const watcher = new RoomWatcher({
    serverHost: 'serenada.app',
    transports: ['ws', 'sse'],
})

const unsubscribe = watcher.subscribe(({ roomStatuses }) => {
    const roomAState = getRoomStatusState(roomStatuses['room-a'])
    console.log(roomAState) // 'hidden' | 'waiting' | 'full'
})

watcher.watchRooms(['room-a', 'room-b'])

// later
unsubscribe()
watcher.stop()
```

## Preflight Diagnostics

Run device and network checks before a call:

```typescript
import { createSerenadaDiagnostics } from '@serenada/core'

const diagnostics = createSerenadaDiagnostics({ serverHost: 'serenada.app' })
const report = await diagnostics.runAll()  // never prompts

report.camera.status       // 'available' | 'unavailable' | 'notAuthorized' | 'skipped'
report.microphone.status   // same shape
report.speaker.status      // same shape
report.network.status      // same shape
report.signaling.status    // same shape
report.signaling.transport // optional, when signaling is reachable
report.turn.status         // same shape
report.turn.latencyMs      // optional, when TURN is reachable
report.devices             // MediaDeviceInfo[]
```

Diagnostics never call `getUserMedia()` — if a permission is missing, the check returns `notAuthorized`.

### Connectivity Checks

Test the core server endpoints individually:

```typescript
const connectivity = await diagnostics.runConnectivityChecks()

connectivity.roomApi.status          // 'passed' | 'failed'
connectivity.webSocket.status        // 'passed' | 'failed'
connectivity.sse.status              // 'passed' | 'failed'
connectivity.diagnosticToken.status  // 'passed' | 'failed'
connectivity.turnCredentials.status  // 'passed' | 'failed'
```

### ICE Probing

Verify STUN/TURN reachability with a real browser ICE gather:

```typescript
const iceReport = await diagnostics.runIceProbe(false, (line) => {
    console.log(line)
})

iceReport.stunPassed
iceReport.turnPassed
iceReport.logs
```

### Server Validation

Validate that a host is a reachable Serenada server:

```typescript
await diagnostics.validateServerHost()
```

## Logging

By default, the SDK is silent — no log output. To enable logging, pass a `logger` in the config:

```typescript
import { createSerenadaCore, ConsoleSerenadaLogger } from '@serenada/core'

const serenada = createSerenadaCore({
    serverHost: 'serenada.app',
    logger: new ConsoleSerenadaLogger(),  // routes to console.debug/info/warn/error
})
```

`ConsoleSerenadaLogger` is a built-in convenience logger. For production apps, implement the `SerenadaLogger` interface to route SDK logs to your own system:

```typescript
import type { SerenadaLogLevel, SerenadaLogger } from '@serenada/core'

const logger: SerenadaLogger = {
    log(level: SerenadaLogLevel, tag: string, message: string) {
        // Route to your logging backend
        // level: 'debug' | 'info' | 'warning' | 'error'
        // tag: 'Session', 'Signaling', 'Transport', 'WebRTC',
        //       'PeerConnection', 'Negotiation', 'Audio', 'Camera',
        //       'ScreenShare', 'Stats'
    }
}

const serenada = createSerenadaCore({ serverHost: 'serenada.app', logger })
```

The logger is passed to all internal SDK components (signaling, media engine, transports). All sessions created from this core instance inherit the logger.

## Configuration

```typescript
const serenada = createSerenadaCore({
    serverHost: 'serenada.app',    // required; bare host or full origin
    defaultAudioEnabled: true,     // mic on at join (default)
    defaultVideoEnabled: true,     // camera on at join (default)
    transports: ['ws', 'sse'],     // transport priority (default)
    turnsOnly: false,              // optional; only use TURN relays when true
})
```

`serverHost` accepts either a bare host like `serenada.app` or a full origin like `http://qa-box:8080`.

## Next Steps

- [Feature Toggles, String Overrides & Theming](sdk-customization.md)
- [API Reference Generation](sdk-api-reference.md)
