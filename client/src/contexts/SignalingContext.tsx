import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useToast } from './ToastContext';
import { createSignalingTransport } from './signaling/transports';
import type { TransportKind } from './signaling/transports';
import type { RoomState, SignalingMessage } from './signaling/types';

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
    const isConnectedRef = useRef(false);
    const roomStateRef = useRef<RoomState | null>(null);
    const activeTransportKindRef = useRef<TransportKind>('ws');

    const transportRef = useRef<ReturnType<typeof createSignalingTransport> | null>(null);
    const transportKindRef = useRef<TransportKind>('ws');
    const forceSseRef = useRef(false);
    const wsConnectedOnceRef = useRef(false);
    const transportIdRef = useRef(0);
    const currentRoomIdRef = useRef<string | null>(null);
    const pendingJoinRef = useRef<string | null>(null);
    const clientIdRef = useRef<string | null>(null);
    const lastClientIdRef = useRef<string | null>(null);

    // Sync ref
    useEffect(() => {
        clientIdRef.current = clientId;
    }, [clientId]);

    useEffect(() => {
        isConnectedRef.current = isConnected;
    }, [isConnected]);

    useEffect(() => {
        roomStateRef.current = roomState;
    }, [roomState]);

    const handleIncomingMessage = useCallback((msg: SignalingMessage) => {
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
    }, [showToast]);

    const sendMessage = useCallback((type: string, payload?: any, to?: string) => {
        if (transportRef.current && transportRef.current.isOpen()) {
            const realMsg = {
                v: 1,
                type,
                rid: currentRoomIdRef.current || undefined,
                cid: clientIdRef.current || undefined,
                to,
                payload
            };

            console.log('TX:', realMsg);
            transportRef.current.send(realMsg);
        } else {
            console.warn('Signaling transport not connected');
        }
    }, []);

    useEffect(() => {
        if (!isConnected) return;
        if (activeTransportKindRef.current !== 'sse') return;

        const interval = window.setInterval(() => {
            sendMessage('ping', { ts: Date.now() });
        }, 20000);

        return () => {
            window.clearInterval(interval);
        };
    }, [isConnected, sendMessage]);

    const joinRoom = useCallback((roomId: string) => {
        console.log(`[Signaling] joinRoom call for ${roomId}`);
        setError(null);
        currentRoomIdRef.current = roomId;
        currentRoomIdRef.current = roomId;
        if (transportRef.current && transportRef.current.isOpen()) {
            const payload: any = { capabilities: { trickleIce: true } };
            // If we have a previous client ID, send it to help server evict ghosts
            const reconnectCid = clientIdRef.current || lastClientIdRef.current;
            if (reconnectCid) {
                payload.reconnectCid = reconnectCid;
            }
            sendMessage('join', payload);
        } else {
            console.log('[Signaling] Transport not ready, buffering join');
            pendingJoinRef.current = roomId;
        }
    }, [sendMessage]);

    useEffect(() => {
        const reconnectAttemptsRef = { current: 0 };
        let reconnectTimeout: number | null = null;
        let closedByUnmount = false;
        const connectingRef = { current: false };
        const params = new URLSearchParams(window.location.search);
        const forceSse = params.get('sse') === '1' || params.get('signaling') === 'sse';
        forceSseRef.current = forceSse;

        const clearReconnectTimeout = () => {
            if (reconnectTimeout !== null) {
                window.clearTimeout(reconnectTimeout);
                reconnectTimeout = null;
            }
        };

        const scheduleReconnect = (kind?: TransportKind) => {
            if (closedByUnmount) return;
            if (reconnectTimeout !== null) return;
            const attempt = reconnectAttemptsRef.current + 1;
            reconnectAttemptsRef.current = attempt;
            const backoff = Math.min(500 * Math.pow(2, attempt - 1), 5000);

            reconnectTimeout = window.setTimeout(() => {
                reconnectTimeout = null;
                connect(kind);
            }, backoff);
        };

        const switchToSSE = () => {
            if (forceSseRef.current) return;
            if (transportKindRef.current === 'sse') return;
            if (typeof EventSource === 'undefined') {
                console.error('[Signaling] SSE not supported in this browser');
                scheduleReconnect('ws');
                return;
            }
            console.warn('[Signaling] WS failed, falling back to SSE');
            reconnectAttemptsRef.current = 0;
            connect('sse');
        };

        const connect = (kind?: TransportKind) => {
            if (closedByUnmount) return;
            if (connectingRef.current) return;

            const targetKind = kind || transportKindRef.current;
            transportKindRef.current = targetKind;
            connectingRef.current = true;

            if (transportRef.current) {
                transportRef.current.close();
            }

            const connectionId = transportIdRef.current + 1;
            transportIdRef.current = connectionId;

            const transport = createSignalingTransport(targetKind, {
                onOpen: () => {
                    if (connectionId !== transportIdRef.current) return;
                    connectingRef.current = false;
                    reconnectAttemptsRef.current = 0;
                    activeTransportKindRef.current = targetKind;
                    const wasConnected = isConnectedRef.current;
                    setIsConnected(true);
                    if (targetKind === 'ws') {
                        wsConnectedOnceRef.current = true;
                    }
                    if (!wasConnected) {
                        if (pendingJoinRef.current) {
                            joinRoom(pendingJoinRef.current);
                            pendingJoinRef.current = null;
                        } else if (currentRoomIdRef.current && !roomStateRef.current) {
                            // If we lost the connection mid-call, automatically rejoin
                            console.log(`[Signaling] Auto-rejoining room ${currentRoomIdRef.current}`);
                            joinRoom(currentRoomIdRef.current);
                        }
                    }
                },
                onClose: (reason, err) => {
                    if (connectionId !== transportIdRef.current) return;
                    connectingRef.current = false;
                    if (closedByUnmount) return;
                    console.error(`[Signaling] Disconnected via ${reason}`, err);
                    setIsConnected(false);
                    // Keep lastClientIdRef for reconnection attempt
                    if (clientIdRef.current) {
                        lastClientIdRef.current = clientIdRef.current;
                    }
                    if (targetKind === 'ws') {
                        setClientId(null);
                        setRoomState(null);
                        setTurnToken(null);
                    }
                    transportRef.current = null;

                    if (targetKind === 'ws' && !wsConnectedOnceRef.current) {
                        switchToSSE();
                        return;
                    }

                    scheduleReconnect(targetKind);
                },
                onMessage: (msg) => {
                    if (connectionId !== transportIdRef.current) return;
                    handleIncomingMessage(msg);
                }
            });

            transportRef.current = transport;
            transport.connect();
        };

        connect(forceSse ? 'sse' : 'ws');

        return () => {
            closedByUnmount = true;
            clearReconnectTimeout();
            if (transportRef.current) {
                transportRef.current.close();
            }
        };
    }, [handleIncomingMessage, joinRoom]);

    const clearError = useCallback(() => setError(null), []);

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
