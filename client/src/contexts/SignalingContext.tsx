import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useToast } from './ToastContext';
import { createSignalingTransport } from './signaling/transports';
import type { TransportKind } from './signaling/transports';
import type { RoomState, SignalingMessage } from './signaling/types';
import { getConfiguredTransportOrder, parseTransportOrder } from './signaling/transportConfig';
import { useTranslation } from 'react-i18next';

interface SignalingContextValue {
    isConnected: boolean;
    activeTransport: TransportKind | null;
    clientId: string | null;
    roomState: RoomState | null;
    turnToken: string | null;
    joinRoom: (roomId: string, opts?: { snapshotId?: string }) => void;
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
    const [activeTransport, setActiveTransport] = useState<TransportKind | null>(null);
    const [clientId, setClientId] = useState<string | null>(null);
    const [roomState, setRoomState] = useState<RoomState | null>(null);
    const [lastMessage, setLastMessage] = useState<SignalingMessage | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [roomStatuses, setRoomStatuses] = useState<Record<string, number>>({});
    const [turnToken, setTurnToken] = useState<string | null>(null);
    const { showToast } = useToast();
    const { t } = useTranslation();

    const listenersRef = useRef<((msg: SignalingMessage) => void)[]>([]);
    const isConnectedRef = useRef(false);

    const transportRef = useRef<ReturnType<typeof createSignalingTransport> | null>(null);
    const transportOrderRef = useRef<TransportKind[]>(getConfiguredTransportOrder());
    const transportIndexRef = useRef(0);
    const transportConnectedOnceRef = useRef<Record<TransportKind, boolean>>({ ws: false, sse: false });
    const transportIdRef = useRef(0);
    const currentRoomIdRef = useRef<string | null>(null);
    const pendingJoinRef = useRef<string | null>(null);
    const pendingJoinPayloadRef = useRef<{ snapshotId?: string } | null>(null);
    const clientIdRef = useRef<string | null>(null);
    const lastClientIdRef = useRef<string | null>(null);
    const needsRejoinRef = useRef(false);
    const reconnectStorageKey = 'serenada.reconnectCid';

    const clearReconnectStorage = useCallback(() => {
        try {
            window.sessionStorage.removeItem(reconnectStorageKey);
        } catch (err) {
            console.warn('[Signaling] Failed to clear reconnectCid', err);
        }
    }, []);

    // Sync ref
    useEffect(() => {
        clientIdRef.current = clientId;
        if (clientId) {
            try {
                window.sessionStorage.setItem(reconnectStorageKey, clientId);
            } catch (err) {
                console.warn('[Signaling] Failed to persist reconnectCid', err);
            }
        }
    }, [clientId]);

    useEffect(() => {
        try {
            const stored = window.sessionStorage.getItem(reconnectStorageKey);
            if (stored && !lastClientIdRef.current) {
                lastClientIdRef.current = stored;
            }
        } catch (err) {
            console.warn('[Signaling] Failed to load reconnectCid', err);
        }
    }, []);

    useEffect(() => {
        isConnectedRef.current = isConnected;
    }, [isConnected]);


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
                needsRejoinRef.current = false;
                clearReconnectStorage();
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
    }, [clearReconnectStorage, showToast]);

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

        const interval = window.setInterval(() => {
            sendMessage('ping', { ts: Date.now() });
        }, 12000);

        return () => {
            window.clearInterval(interval);
        };
    }, [isConnected, sendMessage]);

    const joinRoom = useCallback((roomId: string, opts?: { snapshotId?: string }) => {
        console.log(`[Signaling] joinRoom call for ${roomId}`);
        setError(null);
        needsRejoinRef.current = false;
        currentRoomIdRef.current = roomId;
        if (transportRef.current && transportRef.current.isOpen()) {
            const payload: any = { capabilities: { trickleIce: true } };
            if (opts?.snapshotId) {
                payload.snapshotId = opts.snapshotId;
            }
            // If we have a previous client ID, send it to help server evict ghosts
            const reconnectCid = clientIdRef.current || lastClientIdRef.current;
            if (reconnectCid) {
                payload.reconnectCid = reconnectCid;
            }
            let sent = false;
            const sendJoin = (endpoint?: string) => {
                if (sent) return;
                if (endpoint) {
                    payload.pushEndpoint = endpoint;
                }
                sendMessage('join', payload);
                sent = true;
            };

            const hasPushSupport =
                typeof window !== 'undefined' &&
                'serviceWorker' in navigator &&
                'PushManager' in window;

            if (hasPushSupport) {
                const fallbackTimer = window.setTimeout(() => sendJoin(), 250);
                navigator.serviceWorker.ready
                    .then((reg) => reg.pushManager.getSubscription())
                    .then((sub) => {
                        window.clearTimeout(fallbackTimer);
                        sendJoin(sub?.endpoint);
                    })
                    .catch(() => {
                        window.clearTimeout(fallbackTimer);
                        sendJoin();
                    });
            } else {
                sendJoin();
            }
        } else {
            console.log('[Signaling] Transport not ready, buffering join');
            pendingJoinRef.current = roomId;
            pendingJoinPayloadRef.current = opts ?? null;
        }
    }, [sendMessage]);

    useEffect(() => {
        const reconnectAttemptsRef = { current: 0 };
        let reconnectTimeout: number | null = null;
        let closedByUnmount = false;
        const connectingRef = { current: false };
        const params = new URLSearchParams(window.location.search);
        const paramTransports = params.get('transports');
        transportOrderRef.current = paramTransports
            ? parseTransportOrder(paramTransports)
            : getConfiguredTransportOrder();
        transportIndexRef.current = 0;
        transportConnectedOnceRef.current = { ws: false, sse: false };

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

        const shouldFallback = (kind: TransportKind, reason: string) => {
            const order = transportOrderRef.current;
            if (order.length <= 1) return false;
            if (transportIndexRef.current >= order.length - 1) return false;
            if (reason === 'unsupported' || reason === 'timeout') return true;
            if (!transportConnectedOnceRef.current[kind]) return true;
            return false;
        };

        const tryNextTransport = (reason: string) => {
            const order = transportOrderRef.current;
            const nextIndex = transportIndexRef.current + 1;
            if (nextIndex >= order.length) return false;
            console.warn(`[Signaling] ${order[transportIndexRef.current]} failed (${reason}), trying ${order[nextIndex]}`);
            showToast('info', t('toast_connection_fallback'));
            reconnectAttemptsRef.current = 0;
            connect(nextIndex);
            return true;
        };

        const connect = (index?: number) => {
            if (closedByUnmount) return;
            if (connectingRef.current) return;

            const order = transportOrderRef.current;
            const targetIndex = index ?? transportIndexRef.current;
            const targetKind = order[targetIndex];
            if (!targetKind) return;
            transportIndexRef.current = targetIndex;
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
                    const wasConnected = isConnectedRef.current;
                    setIsConnected(true);
                    setActiveTransport(targetKind);
                    transportConnectedOnceRef.current[targetKind] = true;
                    if (!wasConnected) {
                        if (pendingJoinRef.current) {
                            joinRoom(pendingJoinRef.current, pendingJoinPayloadRef.current ?? undefined);
                            pendingJoinRef.current = null;
                            pendingJoinPayloadRef.current = null;
                        } else if (needsRejoinRef.current && currentRoomIdRef.current) {
                            // If we lost the connection mid-call, automatically rejoin
                            console.log(`[Signaling] Auto-rejoining room ${currentRoomIdRef.current}`);
                            needsRejoinRef.current = false;
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
                    setActiveTransport(null);
                    // Keep lastClientIdRef for reconnection attempt
                    if (clientIdRef.current) {
                        lastClientIdRef.current = clientIdRef.current;
                    }
                    transportRef.current = null;
                    needsRejoinRef.current = !!currentRoomIdRef.current;

                    if (shouldFallback(targetKind, reason) && tryNextTransport(reason)) {
                        return;
                    }

                    scheduleReconnect();
                },
                onMessage: (msg) => {
                    if (connectionId !== transportIdRef.current) return;
                    handleIncomingMessage(msg);
                }
            });

            transportRef.current = transport;
            transport.connect();
        };

        connect(0);

        return () => {
            closedByUnmount = true;
            clearReconnectTimeout();
            if (transportRef.current) {
                transportRef.current.close();
            }
        };
    }, [handleIncomingMessage, joinRoom, showToast, t]);

    const clearError = useCallback(() => setError(null), []);

    const leaveRoom = useCallback(() => {
        sendMessage('leave');
        currentRoomIdRef.current = null;
        lastClientIdRef.current = null; // Clear last ID on explicit leave
        needsRejoinRef.current = false;
        clearReconnectStorage();
        setRoomState(null);
    }, [clearReconnectStorage, sendMessage]);

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
            activeTransport,
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
