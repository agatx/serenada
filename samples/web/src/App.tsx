import { useState, useCallback } from 'react'
import { createSerenadaCore } from '@serenada/core'
import { SerenadaCallFlow } from '@serenada/react-ui'

const serenada = createSerenadaCore({ serverHost: 'serenada.app' })

export default function App() {
    const [callUrl, setCallUrl] = useState<string | null>(null)

    if (callUrl) {
        return (
            <SerenadaCallFlow
                url={callUrl}
                onDismiss={() => setCallUrl(null)}
            />
        )
    }

    return <HomeScreen onJoin={setCallUrl} />
}

function HomeScreen({ onJoin }: { onJoin: (url: string) => void }) {
    const [urlText, setUrlText] = useState('')

    const handleCreateRoom = useCallback(async () => {
        const room = await serenada.createRoom()
        // In a real app, share room.url with the other party
        console.log('Share this URL:', room.url)
        onJoin(room.url)
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
                    onClick={() => onJoin(urlText)}
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
