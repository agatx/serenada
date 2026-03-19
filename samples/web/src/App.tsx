import { useState, useCallback } from 'react'
import { createSerenadaCore, type SerenadaSessionHandle } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

interface ActiveCall {
    url: string
    session?: SerenadaSessionHandle
}

export default function App() {
    const [activeCall, setActiveCall] = useState<ActiveCall | null>(null)

    if (activeCall) {
        return (
            <SerenadaCallFlow
                url={activeCall.url}
                session={activeCall.session}
                onDismiss={() => setActiveCall(null)}
            />
        )
    }

    return <HomeScreen onJoin={setActiveCall} />
}

function HomeScreen({ onJoin }: { onJoin: (call: ActiveCall) => void }) {
    const [urlText, setUrlText] = useState('')

    const handleCreateRoom = useCallback(async () => {
        const room = await serenada.createRoom()
        // In a real app, share room.url with the other party
        console.log('Share this URL:', room.url)
        onJoin({ url: room.url, session: room.session })
    }, [onJoin])

    return (
        <div style={{ maxWidth: 400, margin: '100px auto', textAlign: 'center' }}>
            <h1>Serenada Sample</h1>

            <input
                type="text"
                value={urlText}
                onChange={(e) => setUrlText(e.target.value)}
                placeholder="Paste a call URL"
                style={{ width: '100%', padding: 8, marginBottom: 16 }}
            />

            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <button
                    onClick={() => onJoin({ url: urlText })}
                    disabled={!urlText}
                >
                    Join Call
                </button>

                <button onClick={handleCreateRoom}>
                    Create New Call
                </button>
            </div>
        </div>
    )
}
