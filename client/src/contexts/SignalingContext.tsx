import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useToast } from './ToastContext';

// Types (Protocol v1)
export type RoomState = {
    hostCid: string | null;
    participants: { cid: string; joinedAt?: number }[];
};

export type SignalingMessage = {
    v: number;
    type: string;
    rid?: string;
    sid?: string;
    cid?: string;
    to?: string;
    payload?: any;
};

interface SignalingContextValue {
    isConnected: boolean;
    clientId: string | null;
    roomState: RoomState | null;
    joinRoom: (roomId: string) => void;
    leaveRoom: () => void;
    endRoom: () => void;
    sendMessage: (type: string, payload?: any, to?: string) => void;
    lastMessage: SignalingMessage | null;
    subscribeToMessages: (cb: (msg: SignalingMessage) => void) => () => void;
    error: string | null;
    clearError: () => void;
}

const SignalingContext = createContext<SignalingContextValue | null>(null);

export const useSignaling = () => {
    const context = useContext(SignalingContext);
    if (!context) {
        throw new Error('useSignaling must be used within a SignalingProvider');
    }
    return context;
};

export const SignalingProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const [isConnected, setIsConnected] = useState(false);
    const [clientId, setClientId] = useState<string | null>(null);
    const [roomState, setRoomState] = useState<RoomState | null>(null);
    const [lastMessage, setLastMessage] = useState<SignalingMessage | null>(null);
    const [error, setError] = useState<string | null>(null);
    const { showToast } = useToast();

    const listenersRef = useRef<((msg: SignalingMessage) => void)[]>([]);

    const wsRef = useRef<WebSocket | null>(null);
    const currentRoomIdRef = useRef<string | null>(null);
    const pendingJoinRef = useRef<string | null>(null);

    useEffect(() => {
        // Connect to WS
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        // Allow configuring WS URL via environment variable for production (e.g. separate backend)
        // Fallback to same-host:8080 for local development if not specified
        const wsUrl = import.meta.env.VITE_WS_URL || `${protocol}//${window.location.host}/ws`;

        const ws = new WebSocket(wsUrl);
        wsRef.current = ws;

        ws.onopen = () => {
            console.log('WS Connected');
            setIsConnected(true);
            if (pendingJoinRef.current) {
                joinRoom(pendingJoinRef.current);
                pendingJoinRef.current = null;
            }
        };

        ws.onclose = () => {
            console.log('WS Disconnected');
            setIsConnected(false);
            setClientId(null);
            setRoomState(null);
        };

        ws.onerror = (err) => {
            console.error('WS Error', err);
        };

        ws.onmessage = (event) => {
            try {
                const msg: SignalingMessage = JSON.parse(event.data);
                console.log('RX:', msg);

                switch (msg.type) {
                    case 'joined':
                        if (msg.cid) setClientId(msg.cid);
                        if (msg.payload) {
                            // In Go server we send "participants" and "hostCid" in payload for joined AND room_state
                            setRoomState(msg.payload as RoomState);
                        }
                        break;
                    case 'room_state':
                        if (msg.payload) {
                            setRoomState(msg.payload as RoomState);
                        }
                        break;
                    case 'room_ended':
                        setRoomState(null);
                        currentRoomIdRef.current = null;
                        // Optional: set some "ended" state to show UI
                        break;
                    case 'error':
                        if (msg.payload && msg.payload.message) {
                            setError(msg.payload.message);
                            showToast('error', msg.payload.message);
                        }
                        break;
                }

                setLastMessage(msg);
                listenersRef.current.forEach(listener => listener(msg));
            } catch (e) {
                console.error('Failed to parse message', e);
            }
        };

        return () => {
            ws.close();
        };
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    const sendMessage = (type: string, payload?: any, to?: string) => {
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            // Removed unused 'msg' variable definition
            const realMsg = {
                v: 1,
                type,
                rid: currentRoomIdRef.current || undefined,
                cid: clientId || undefined,
                to,
                payload
            };

            console.log('TX:', realMsg);
            wsRef.current.send(JSON.stringify(realMsg));
        } else {
            console.warn('WS not connected');
        }
    };

    const clearError = () => setError(null);

    const joinRoom = (roomId: string) => {
        console.log(`[Signaling] joinRoom call for ${roomId}`);
        setError(null);
        currentRoomIdRef.current = roomId;
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            sendMessage('join', { capabilities: { trickleIce: true } });
        } else {
            console.log('[Signaling] WS not ready, buffering join');
            pendingJoinRef.current = roomId;
        }
    };

    const leaveRoom = () => {
        sendMessage('leave');
        currentRoomIdRef.current = null;
        setRoomState(null);
    };

    const endRoom = () => {
        sendMessage('end_room');
    }

    const subscribeToMessages = (cb: (msg: SignalingMessage) => void) => {
        listenersRef.current.push(cb);
        return () => {
            listenersRef.current = listenersRef.current.filter(l => l !== cb);
        };
    };

    return (
        <SignalingContext.Provider value={{
            isConnected,
            clientId,
            roomState,
            joinRoom,
            leaveRoom,
            endRoom,
            sendMessage,
            lastMessage,
            subscribeToMessages,
            error,
            clearError
        }}>
            {children}
        </SignalingContext.Provider>
    );
};
