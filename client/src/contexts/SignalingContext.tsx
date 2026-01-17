import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
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
    turnToken: string | null;
    joinRoom: (roomId: string) => void;
    leaveRoom: () => void;
    endRoom: () => void;
    sendMessage: (type: string, payload?: any, to?: string) => void;
    lastMessage: SignalingMessage | null;
    subscribeToMessages: (cb: (msg: SignalingMessage) => void) => () => void;
    error: string | null;
    clearError: () => void;
    watchRooms: (rids: string[]) => void;
    roomStatuses: Record<string, number>;
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
    const [roomStatuses, setRoomStatuses] = useState<Record<string, number>>({});
    const [turnToken, setTurnToken] = useState<string | null>(null);
    const { showToast } = useToast();

    const listenersRef = useRef<((msg: SignalingMessage) => void)[]>([]);

    const wsRef = useRef<WebSocket | null>(null);
    const currentRoomIdRef = useRef<string | null>(null);
    const pendingJoinRef = useRef<string | null>(null);
    const clientIdRef = useRef<string | null>(null);
    const lastClientIdRef = useRef<string | null>(null);

    // Sync ref
    useEffect(() => {
        clientIdRef.current = clientId;
    }, [clientId]);

    useEffect(() => {
        const reconnectAttemptsRef = { current: 0 };
        let reconnectTimeout: number | null = null;
        let closedByUnmount = false;

        const clearReconnectTimeout = () => {
            if (reconnectTimeout !== null) {
                window.clearTimeout(reconnectTimeout);
                reconnectTimeout = null;
            }
        };

        const scheduleReconnect = () => {
            if (closedByUnmount) return;
            if (reconnectTimeout !== null) return;
            const attempt = reconnectAttemptsRef.current + 1;
            reconnectAttemptsRef.current = attempt;
            const backoff = Math.min(500 * Math.pow(2, attempt - 1), 5000);

            reconnectTimeout = window.setTimeout(() => {
                reconnectTimeout = null;
                connect();
            }, backoff);
        };

        const connect = () => {
            if (closedByUnmount) return;
            // Prevent duplicate sockets if a reconnect is already in flight
            if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
                return;
            }

            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            // Allow configuring WS URL via environment variable for production (e.g. separate backend)
            // Fallback to same-host:8080 for local development if not specified
            const wsUrl = import.meta.env.VITE_WS_URL || `${protocol}//${window.location.host}/ws`;

            const ws = new WebSocket(wsUrl);
            wsRef.current = ws;

            const handleDisconnect = (reason: string, err?: any) => {
                if (closedByUnmount) return;
                console.error(`[WS] Disconnected via ${reason}`, err);
                setIsConnected(false);
                // Keep lastClientIdRef for reconnection attempt
                if (clientIdRef.current) {
                    lastClientIdRef.current = clientIdRef.current;
                }
                setClientId(null);
                setRoomState(null);
                setTurnToken(null);
                wsRef.current = null;
                scheduleReconnect();
            };

            ws.onopen = () => {
                console.log('WS Connected');
                reconnectAttemptsRef.current = 0;
                setIsConnected(true);
                if (pendingJoinRef.current) {
                    joinRoom(pendingJoinRef.current);
                    pendingJoinRef.current = null;
                } else if (currentRoomIdRef.current) {
                    // If we lost the connection mid-call, automatically rejoin
                    console.log(`[WS] Auto-rejoining room ${currentRoomIdRef.current}`);
                    joinRoom(currentRoomIdRef.current);
                }
            };

            ws.onclose = (evt) => {
                handleDisconnect('close', evt);
            };

            ws.onerror = (err) => {
                handleDisconnect('error', err);
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
                                // TURN token is now included in joined response (gated by valid room ID)
                                if (msg.payload.turnToken) {
                                    setTurnToken(msg.payload.turnToken as string);
                                }
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
                        case 'room_statuses':
                            if (msg.payload) {
                                setRoomStatuses(prev => ({ ...prev, ...msg.payload }));
                            }
                            break;
                        case 'room_status_update':
                            if (msg.payload) {
                                setRoomStatuses(prev => ({
                                    ...prev,
                                    [msg.payload.rid]: msg.payload.count
                                }));
                            }
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
        };

        connect();

        return () => {
            closedByUnmount = true;
            clearReconnectTimeout();
            if (wsRef.current) {
                wsRef.current.close();
            }
        };
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    const sendMessage = useCallback((type: string, payload?: any, to?: string) => {
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            const realMsg = {
                v: 1,
                type,
                rid: currentRoomIdRef.current || undefined,
                cid: clientIdRef.current || undefined,
                to,
                payload
            };

            console.log('TX:', realMsg);
            wsRef.current.send(JSON.stringify(realMsg));
        } else {
            console.warn('WS not connected');
        }
    }, []);

    const clearError = useCallback(() => setError(null), []);

    const joinRoom = useCallback((roomId: string) => {
        console.log(`[Signaling] joinRoom call for ${roomId}`);
        setError(null);
        currentRoomIdRef.current = roomId;
        currentRoomIdRef.current = roomId;
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            const payload: any = { capabilities: { trickleIce: true } };
            // If we have a previous client ID, send it to help server evict ghosts
            if (lastClientIdRef.current) {
                payload.reconnectCid = lastClientIdRef.current;
            }
            sendMessage('join', payload);
        } else {
            console.log('[Signaling] WS not ready, buffering join');
            pendingJoinRef.current = roomId;
        }
    }, [sendMessage]);

    const leaveRoom = useCallback(() => {
        sendMessage('leave');
        currentRoomIdRef.current = null;
        lastClientIdRef.current = null; // Clear last ID on explicit leave
        setRoomState(null);
    }, [sendMessage]);

    const endRoom = useCallback(() => {
        sendMessage('end_room');
    }, [sendMessage]);

    const watchRooms = useCallback((rids: string[]) => {
        if (rids.length === 0) return;
        sendMessage('watch_rooms', { rids });
    }, [sendMessage]);

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
            turnToken,
            joinRoom,
            leaveRoom,
            endRoom,
            sendMessage,
            lastMessage,
            subscribeToMessages,
            error,
            clearError,
            watchRooms,
            roomStatuses
        }}>
            {children}
        </SignalingContext.Provider>
    );
};
