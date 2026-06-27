import './multicall.css'
import { useCallback, useMemo, useState, type CSSProperties } from 'react'
import type { ManagedCallState } from '@agatx/serenada-core'
import {
    SerenadaCallFlow,
    useSerenadaCallRegistry,
} from '@agatx/serenada-react-ui'

/**
 * Minimal multi-call sample.
 *
 * Shows a third-party developer how to hold several Serenada calls at once with
 * a single foreground (the registry owns which call holds the mic/camera lease):
 *
 *  - construct the registry with `useSerenadaCallRegistry({ config })`
 *  - `joinAndSwitch(room)` to join a room and bring it to the foreground
 *  - `joinHeld(room)` to join a room in the background (no capture, no lease)
 *  - render the foreground call with
 *    `<SerenadaCallFlow session={registry.activeCall.session} />`
 *  - list `heldCalls` and `switchTo(callId)` to foreground one
 *  - `hold` / `leave` / `end` a call
 *  - handle the "no active call but held calls remain" state (after `hold`,
 *    the registry never auto-promotes a held call — the host picks the next one)
 */
export function MultiCallScreen({ onExit }: { onExit: () => void }) {
    // Signaling host: defaults to the public server. Override with a `?host=`
    // query param (e.g. `?host=localhost`) to point the demo at a local dev
    // server. Memoized so the config identity is stable across renders — the
    // hook builds the registry once for a given config.
    const config = useMemo(() => {
        const host =
            new URLSearchParams(window.location.search).get('host')?.trim() ||
            'serenada.app'
        return { serverHost: host }
    }, [])
    // The registry is owned by the hook: constructed once for this config and
    // torn down on unmount (every live call is left).
    const registry = useSerenadaCallRegistry({ config })

    const {
        activeCall,
        heldCalls,
        registryOperationInProgress,
        joinAndSwitch,
        joinHeld,
        switchTo,
        hold,
        leave,
        end,
    } = registry

    const [urlText, setUrlText] = useState('')
    const [lastMessage, setLastMessage] = useState<string | null>(null)

    // The registry's preflightForeground() never opens a permission prompt (the
    // host owns the prompt). So when foregrounding reports needsPermission, the
    // host requests device access here, then retries the switch.
    const requestMediaPermission = useCallback(async (): Promise<boolean> => {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: true,
                video: true,
            })
            // We only needed the grant; the registry acquires its own tracks
            // when the call activates, so release these throwaway tracks now.
            stream.getTracks().forEach((track) => track.stop())
            return true
        } catch (err) {
            setLastMessage(`Permission denied: ${(err as Error).message}`)
            return false
        }
    }, [])

    // Foreground a held call. If the registry reports needsPermission, request
    // device access and retry the switch once.
    const activateCall = useCallback(
        async (callId: string) => {
            let result = await switchTo(callId)
            if (result.kind === 'needsPermission') {
                setLastMessage('Requesting microphone and camera access...')
                if (await requestMediaPermission()) {
                    result = await switchTo(callId)
                }
            }
            if (result.kind === 'failed') {
                setLastMessage(`Switch failed: ${result.error.message}`)
            } else if (result.kind === 'needsPermission') {
                setLastMessage('Permission still needed to foreground this call.')
            } else {
                setLastMessage(null)
            }
        },
        [switchTo, requestMediaPermission],
    )

    const join = useCallback(
        async (mode: 'switch' | 'held') => {
            const url = urlText.trim()
            if (!url) return
            setUrlText('')
            const result =
                mode === 'switch'
                    ? await joinAndSwitch({ url })
                    : await joinHeld({ url })
            if (result.kind === 'failed') {
                setLastMessage(`Join failed: ${result.error.message}`)
            } else if (result.kind === 'needsPermission') {
                // joinAndSwitch joined the room (now held); foregrounding it
                // needs a device grant. Reuse the request-then-retry flow.
                await activateCall(result.callId)
            } else {
                setLastMessage(null)
            }
        },
        [urlText, joinAndSwitch, joinHeld, activateCall],
    )

    return (
        <div style={{ maxWidth: 720, margin: '48px auto', padding: 24 }}>
            <h1>Multi-Call Sample</h1>
            <p style={{ color: '#5b6470', lineHeight: 1.5 }}>
                Hold several calls at once. Only one call is foreground (owns the
                mic/camera); the rest are held. Use the registry to switch, hold,
                leave, or end.
            </p>

            <section style={sectionStyle}>
                <h2>Join a Room</h2>
                <input
                    type="text"
                    value={urlText}
                    onChange={(e) => setUrlText(e.target.value)}
                    placeholder="Paste a call URL"
                    style={{ width: '100%', padding: 8, marginBottom: 16 }}
                />
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <button
                        onClick={() => void join('switch')}
                        disabled={!urlText.trim() || registryOperationInProgress}
                    >
                        Join &amp; Switch
                    </button>
                    <button
                        onClick={() => void join('held')}
                        disabled={!urlText.trim() || registryOperationInProgress}
                    >
                        Join Held
                    </button>
                </div>
                {lastMessage && (
                    <p style={{ color: '#a6431f', marginTop: 12 }}>{lastMessage}</p>
                )}
            </section>

            {/* Foreground call: render its live session with SerenadaCallFlow. */}
            {activeCall ? (
                <section style={sectionStyle}>
                    <h2>Active Call</h2>
                    <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
                        <button
                            onClick={() => void hold(activeCall.state.id)}
                            disabled={registryOperationInProgress}
                        >
                            Hold
                        </button>
                        <button
                            onClick={() => void leave(activeCall.state.id)}
                            disabled={registryOperationInProgress}
                        >
                            Leave
                        </button>
                        <button
                            onClick={() => void end(activeCall.state.id)}
                            disabled={registryOperationInProgress}
                        >
                            End for everyone
                        </button>
                    </div>
                    <div style={callFlowFrameStyle}>
                        <SerenadaCallFlow
                            className="sample-callflow"
                            session={activeCall.session}
                            onEndCall={() => void leave(activeCall.state.id)}
                        />
                    </div>
                </section>
            ) : (
                // "No active call but held calls remain" state: the registry does
                // NOT auto-promote a held call, so the host decides what comes next.
                <section style={sectionStyle}>
                    <h2>No Active Call</h2>
                    <p style={{ color: '#5b6470', lineHeight: 1.5 }}>
                        {heldCalls.length > 0
                            ? 'Nothing is in the foreground. Resume a held call below.'
                            : 'No calls yet — join a room above.'}
                    </p>
                </section>
            )}

            {heldCalls.length > 0 && (
                <section style={sectionStyle}>
                    <h2>Held Calls ({heldCalls.length})</h2>
                    <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
                        {heldCalls.map((call) => (
                            <HeldCallRow
                                key={call.id}
                                call={call}
                                busy={registryOperationInProgress}
                                onResume={() => void activateCall(call.id)}
                                onLeave={() => void leave(call.id)}
                            />
                        ))}
                    </ul>
                </section>
            )}

            <button onClick={onExit}>Back</button>
        </div>
    )
}

function HeldCallRow({
    call,
    busy,
    onResume,
    onLeave,
}: {
    call: ManagedCallState
    busy: boolean
    onResume: () => void
    onLeave: () => void
}) {
    return (
        <li style={rowStyle}>
            <div style={{ minWidth: 0 }}>
                <strong>{call.displayName ?? call.roomId}</strong>
                <div style={{ color: '#5b6470', fontSize: 13 }}>
                    {call.membershipPhase} · {call.participantCount} participant
                    {call.participantCount === 1 ? '' : 's'}
                    {call.activationError ? ` · ${call.activationError.message}` : ''}
                </div>
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
                <button onClick={onResume} disabled={busy}>
                    Resume
                </button>
                <button onClick={onLeave} disabled={busy}>
                    Leave
                </button>
            </div>
        </li>
    )
}

const sectionStyle: CSSProperties = {
    border: '1px solid #d9dfe7',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    background: '#ffffff',
}

const rowStyle: CSSProperties = {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
    padding: 12,
    border: '1px solid #e3e8ef',
    borderRadius: 10,
}

const callFlowFrameStyle: CSSProperties = {
    position: 'relative',
    // The packaged call UI is built for full-viewport; give it a tall frame so
    // the embedded layout (video stage + bottom controls) has room to breathe.
    // `multicall.css` re-anchors the call UI to this frame (see .sample-callflow).
    height: 'min(70vh, 560px)',
    borderRadius: 10,
    overflow: 'hidden',
    background: '#0f1720',
}
