import { useCallback, useState, type CSSProperties } from 'react'
import {
    createSerenadaCore,
    SignalingProviderEmitter,
    type CallState,
    type JoinOptions,
    type PeerMessage,
    type SerenadaSessionHandle,
} from '@agatx/serenada-core'
import { SerenadaCallFlow } from '@agatx/serenada-react-ui'

const builtInSerenada = createSerenadaCore({ serverHost: 'serenada.app' })

interface ActiveCall {
    kind: 'built-in'
    url: string
    session?: SerenadaSessionHandle
}

interface ProviderDemoState {
    kind: 'provider-demo'
    session: SerenadaSessionHandle
    state: CallState
    provider: SampleMockProvider
    messages: PeerMessage[]
    unsubscribeState: () => void
    unsubscribeMessages: () => void
}

type ActiveScreen = ActiveCall | ProviderDemoState | null

class SampleMockProvider extends SignalingProviderEmitter {
    readonly events: string[] = []
    private readonly remotePeerId = 'sample-remote'
    private localPeerId = 'sample-local'
    private timers: number[] = []
    onEvent: ((entry: string) => void) | null = null

    connect(): void {
        this.record('connect() -> connected transport=mock')
        this.emit('connected', { transport: 'mock' })
    }

    disconnect(): void {
        this.clearTimers()
        this.record('disconnect()')
    }

    joinRoom(roomId: string, options?: JoinOptions): void {
        this.clearTimers()
        this.localPeerId = options?.reconnectPeerId ?? 'sample-local'
        this.record(`joinRoom(${roomId}) -> joined(local only)`)
        this.emit('joined', {
            peerId: this.localPeerId,
            participants: [{ peerId: this.localPeerId, joinedAt: 1 }],
            maxParticipants: 4,
        })
        this.schedule(400, () => {
            this.record(`peerJoined(${this.remotePeerId})`)
            this.emit('peerJoined', { peerId: this.remotePeerId, joinedAt: 2 })
        })
        this.schedule(700, () => {
            this.record(`message(${this.remotePeerId} -> demo_message)`)
            this.emit('message', {
                from: this.remotePeerId,
                type: 'demo_message',
                payload: { text: 'Hello from the in-memory sample provider.' },
            })
        })
    }

    leaveRoom(): void {
        this.clearTimers()
        this.record('leaveRoom()')
        this.emit('peerLeft', { peerId: this.remotePeerId, joinedAt: 2 })
    }

    endRoom(): void {
        this.clearTimers()
        this.record('endRoom() -> roomEnded')
        this.emit('roomEnded', { by: this.localPeerId, reason: 'mock provider demo ended' })
    }

    sendToPeer(peerId: string, type: string, payload: unknown): void {
        this.record(`sendToPeer(${peerId}, ${type})`)
        this.echoMessage(type, payload)
    }

    broadcast(type: string, payload: unknown): void {
        this.record(`broadcast(${type})`)
        this.echoMessage(type, payload)
    }

    async getIceServers(): Promise<RTCIceServer[]> {
        this.record('getIceServers() -> [] (STUN-only fallback)')
        return []
    }

    private echoMessage(type: string, payload: unknown): void {
        this.schedule(150, () => {
            this.record(`message(${this.remotePeerId} -> ack:${type})`)
            this.emit('message', {
                from: this.remotePeerId,
                type: `ack:${type}`,
                payload: { echoedPayload: payload ?? null },
            })
        })
    }

    private record(entry: string): void {
        this.events.push(entry)
        this.onEvent?.(entry)
    }

    private schedule(delayMs: number, action: () => void): void {
        const timerId = window.setTimeout(action, delayMs)
        this.timers.push(timerId)
    }

    private clearTimers(): void {
        for (const timerId of this.timers) {
            window.clearTimeout(timerId)
        }
        this.timers = []
    }
}

export default function App() {
    const [activeScreen, setActiveScreen] = useState<ActiveScreen>(null)

    const dismissProviderDemo = useCallback(() => {
        setActiveScreen((current) => {
            if (!current || current.kind !== 'provider-demo') {
                return null
            }
            current.unsubscribeMessages()
            current.unsubscribeState()
            current.session.leave()
            return null
        })
    }, [])

    const startProviderDemo = useCallback(() => {
        const provider = new SampleMockProvider()
        const providerCore = createSerenadaCore({ signalingProvider: provider })
        const session = providerCore.join({ roomId: 'sample-provider-room' })

        provider.onEvent = () => {
            setActiveScreen((current) => {
                if (!current || current.kind !== 'provider-demo' || current.session !== session) {
                    return current
                }
                return {
                    ...current,
                    provider,
                    messages: current.messages,
                }
            })
        }

        const unsubscribeState = session.subscribe((nextState) => {
            setActiveScreen((current) => {
                if (!current || current.kind !== 'provider-demo' || current.session !== session) {
                    return current
                }
                return { ...current, state: nextState }
            })
        })

        const unsubscribeMessages = session.onPeerMessage((message) => {
            setActiveScreen((current) => {
                if (!current || current.kind !== 'provider-demo' || current.session !== session) {
                    return current
                }
                return { ...current, messages: [...current.messages, message] }
            })
        })

        setActiveScreen({
            kind: 'provider-demo',
            session,
            state: session.state,
            provider,
            messages: [],
            unsubscribeState,
            unsubscribeMessages,
        })
    }, [])

    if (activeScreen?.kind === 'built-in') {
        return (
            <SerenadaCallFlow
                url={activeScreen.url}
                session={activeScreen.session}
                onDismiss={() => setActiveScreen(null)}
            />
        )
    }

    if (activeScreen?.kind === 'provider-demo') {
        return (
            <ProviderDemoScreen
                demo={activeScreen}
                onDismiss={dismissProviderDemo}
            />
        )
    }

    return (
        <HomeScreen
            onJoin={(call) => setActiveScreen(call)}
            onStartProviderDemo={startProviderDemo}
        />
    )
}

function HomeScreen({
    onJoin,
    onStartProviderDemo,
}: {
    onJoin: (call: ActiveCall) => void
    onStartProviderDemo: () => void
}) {
    const [urlText, setUrlText] = useState('')

    const handleCreateRoom = useCallback(async () => {
        const room = await builtInSerenada.createRoom()
        console.log('Share this URL:', room.url)
        onJoin({ kind: 'built-in', url: room.url })
    }, [onJoin])

    return (
        <div style={{ maxWidth: 560, margin: '72px auto', padding: 24 }}>
            <h1>Serenada Web Sample</h1>
            <p style={{ color: '#5b6470', lineHeight: 1.5 }}>
                This sample shows both the built-in Serenada signaling path and a custom in-memory
                `SignalingProvider` demo that uses incremental presence plus peer-message delivery.
            </p>

            <section style={sectionStyle}>
                <h2>Built-In Signaling</h2>
                <input
                    type="text"
                    value={urlText}
                    onChange={(e) => setUrlText(e.target.value)}
                    placeholder="Paste a call URL"
                    style={{ width: '100%', padding: 8, marginBottom: 16 }}
                />

                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    <button
                        onClick={() => onJoin({ kind: 'built-in', url: urlText })}
                        disabled={!urlText}
                    >
                        Join Call
                    </button>

                    <button onClick={handleCreateRoom}>
                        Create New Call
                    </button>
                </div>
            </section>

            <section style={sectionStyle}>
                <h2>Custom Provider Smoke Demo</h2>
                <p style={{ color: '#5b6470', lineHeight: 1.5 }}>
                    Starts a local provider-backed session without `serverHost`. The mock provider emits
                    `joined`, incremental `peerJoined`, and peer-message events without Serenada transport.
                </p>
                <button onClick={onStartProviderDemo}>
                    Start Mock Provider Demo
                </button>
            </section>
        </div>
    )
}

function ProviderDemoScreen({
    demo,
    onDismiss,
}: {
    demo: ProviderDemoState
    onDismiss: () => void
}) {
    return (
        <div style={{ maxWidth: 720, margin: '48px auto', padding: 24 }}>
            <h1>Custom Provider Demo</h1>
            <p style={{ color: '#5b6470', lineHeight: 1.5 }}>
                Provider mode session created with an injected `signalingProvider`.
            </p>

            <section style={sectionStyle}>
                <h2>Session State</h2>
                <dl style={gridStyle}>
                    <div>
                        <dt>Phase</dt>
                        <dd>{demo.state.phase}</dd>
                    </div>
                    <div>
                        <dt>Participant Count</dt>
                        <dd>{(demo.state.localParticipant ? 1 : 0) + demo.state.remoteParticipants.length}</dd>
                    </div>
                    <div>
                        <dt>Local CID</dt>
                        <dd>{demo.state.localParticipant?.cid ?? 'pending'}</dd>
                    </div>
                    <div>
                        <dt>Remote CIDs</dt>
                        <dd>{demo.state.remoteParticipants.map((participant) => participant.cid).join(', ') || 'none'}</dd>
                    </div>
                </dl>
            </section>

            <section style={sectionStyle}>
                <h2>Provider Event Log</h2>
                <pre style={logStyle}>{demo.provider.events.join('\n') || 'Waiting for provider events...'}</pre>
            </section>

            <section style={sectionStyle}>
                <h2>Session Peer Messages</h2>
                <pre style={logStyle}>
                    {demo.messages.map((message) => `${message.from} -> ${message.type}: ${JSON.stringify(message.payload)}`).join('\n')
                        || 'Waiting for peer messages...'}
                </pre>
            </section>

            <button onClick={onDismiss}>End Demo</button>
        </div>
    )
}

const sectionStyle: CSSProperties = {
    border: '1px solid #d9dfe7',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    background: '#ffffff',
}

const gridStyle: CSSProperties = {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
    gap: 12,
}

const logStyle: CSSProperties = {
    margin: 0,
    padding: 12,
    borderRadius: 10,
    background: '#0f1720',
    color: '#d8e1ec',
    overflowX: 'auto',
    whiteSpace: 'pre-wrap',
    lineHeight: 1.5,
}
