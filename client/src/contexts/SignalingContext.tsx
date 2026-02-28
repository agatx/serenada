import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useToast } from './ToastContext';
import { createSignalingTransport } from './signaling/transports';
import type { TransportKind } from './signaling/transports';
import type { RoomState, SignalingMessage } from './signaling/types';
import { getConfiguredTransportOrder, parseTransportOrder } from './signaling/transportConfig';
import { mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './signaling/roomStatuses';
import { useTranslation } from 'react-i18next';
import {
    RECONNECT_BACKOFF_BASE_MS,
    RECONNECT_BACKOFF_CAP_MS,
    PING_INTERVAL_MS,
    PONG_MISS_THRESHOLD,
    WS_FALLBACK_CONSECUTIVE_FAILURES,
    JOIN_CONNECT_KICKSTART_MS,
    JOIN_RECOVERY_MS,
    JOIN_HARD_TIMEOUT_MS,
    TURN_REFRESH_TRIGGER_RATIO,
} from '../constants/webrtcResilience';

interface SignalingContextValue {
    isConnected: boolean;
    activeTransport: TransportKind | null;
    clientId: string | null;
    roomState: RoomState | null;
    turnToken: string | null;
    turnTokenTTLMs: number | null;
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
    const [activeTransport, setActiveTransport] = useState<TransportKind | null>(null);
    const [clientId, setClientId] = useState<string | null>(null);
    const [roomState, setRoomState] = useState<RoomState | null>(null);
    const [lastMessage, setLastMessage] = useState<SignalingMessage | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [roomStatuses, setRoomStatuses] = useState<Record<string, number>>({});
    const [turnToken, setTurnToken] = useState<string | null>(null);
    const [turnTokenTTLMs, setTurnTokenTTLMs] = useState<number | null>(null);
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
    const clientIdRef = useRef<string | null>(null);
    const lastClientIdRef = useRef<string | null>(null);
    const needsRejoinRef = useRef(false);
    const reconnectTokenRef = useRef<string | null>(null);
    const reconnectTokenRoomIdRef = useRef<string | null>(null);
    const turnRefreshTimerRef = useRef<number | null>(null);
    const lastPongAtRef = useRef<number>(Date.now());
    const missedPongsRef = useRef<number>(0);
    const wsConsecutiveFailuresRef = useRef<number>(0);
    const sseSidRef = useRef<string | null>(null);
    const joinAttemptIdRef = useRef(0);
    const joinAckedRef = useRef(false);
    const joinKickstartTimerRef = useRef<number | null>(null);
    const joinRecoveryTimerRef = useRef<number | null>(null);
    const joinHardTimeoutRef = useRef<number | null>(null);
    const reconnectStorageKey = 'serenada.reconnectCid';
    const reconnectTokenStorageKey = 'serenada.reconnectToken';
    const reconnectTokenRoomStorageKey = 'serenada.reconnectTokenRoom';

    const clearReconnectStorage = useCallback(() => {
        try {
            window.sessionStorage.removeItem(reconnectStorageKey);
            window.sessionStorage.removeItem(reconnectTokenStorageKey);
            window.sessionStorage.removeItem(reconnectTokenRoomStorageKey);
        } catch (err) {
            console.warn('[Signaling] Failed to clear reconnectCid', err);
        }
        reconnectTokenRef.current = null;
        reconnectTokenRoomIdRef.current = null;
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
            const storedToken = window.sessionStorage.getItem(reconnectTokenStorageKey);
            if (storedToken && !reconnectTokenRef.current) {
                reconnectTokenRef.current = storedToken;
            }
            const storedTokenRoom = window.sessionStorage.getItem(reconnectTokenRoomStorageKey);
            if (storedTokenRoom && !reconnectTokenRoomIdRef.current) {
                reconnectTokenRoomIdRef.current = storedTokenRoom;
            }
        } catch (err) {
            console.warn('[Signaling] Failed to load reconnectCid', err);
        }
    }, []);

    useEffect(() => {
        isConnectedRef.current = isConnected;
    }, [isConnected]);


    const clearJoinTimers = useCallback(() => {
        if (joinKickstartTimerRef.current !== null) {
            window.clearTimeout(joinKickstartTimerRef.current);
            joinKickstartTimerRef.current = null;
        }
        if (joinRecoveryTimerRef.current !== null) {
            window.clearTimeout(joinRecoveryTimerRef.current);
            joinRecoveryTimerRef.current = null;
        }
        if (joinHardTimeoutRef.current !== null) {
            window.clearTimeout(joinHardTimeoutRef.current);
            joinHardTimeoutRef.current = null;
        }
    }, []);

    const handleIncomingMessage = useCallback((msg: SignalingMessage) => {
        console.log('RX:', msg);

        switch (msg.type) {
            case 'joined':
                clearJoinTimers();
                joinAckedRef.current = true;
                if (msg.cid) setClientId(msg.cid);
                if (msg.payload) {
                    setRoomState(msg.payload as RoomState);
                    if (msg.payload.turnToken) {
                        setTurnToken(msg.payload.turnToken as string);
                    }
                    if (msg.payload.turnTokenTTLMs) {
                        setTurnTokenTTLMs(msg.payload.turnTokenTTLMs as number);
                    }
                    // Store reconnect token for authenticated reconnection
                    if (msg.payload.reconnectToken) {
                        reconnectTokenRef.current = msg.payload.reconnectToken as string;
                        reconnectTokenRoomIdRef.current = msg.rid || currentRoomIdRef.current;
                        try {
                            window.sessionStorage.setItem(reconnectTokenStorageKey, msg.payload.reconnectToken as string);
                            if (reconnectTokenRoomIdRef.current) {
                                window.sessionStorage.setItem(reconnectTokenRoomStorageKey, reconnectTokenRoomIdRef.current);
                            }
                        } catch (err) {
                            console.warn('[Signaling] Failed to persist reconnectToken', err);
                        }
                    }
                }
                break;
            case 'turn-refreshed':
                if (msg.payload) {
                    if (msg.payload.turnToken) {
                        setTurnToken(msg.payload.turnToken as string);
                    }
                    if (msg.payload.turnTokenTTLMs) {
                        setTurnTokenTTLMs(msg.payload.turnTokenTTLMs as number);
                    }
                    console.log('[Signaling] TURN credentials refreshed');
                }
                break;
            case 'pong':
                lastPongAtRef.current = Date.now();
                missedPongsRef.current = 0;
                break;
            case 'room_state':
                if (msg.payload) {
                    setRoomState(msg.payload as RoomState);
                }
                break;
            case 'room_ended':
                clearJoinTimers();
                setRoomState(null);
                currentRoomIdRef.current = null;
                needsRejoinRef.current = false;
                clearReconnectStorage();
                // Optional: set some "ended" state to show UI
                break;
            case 'room_statuses':
                if (msg.payload) {
                    setRoomStatuses(prev => mergeRoomStatusesPayload(prev, msg.payload));
                }
                break;
            case 'room_status_update':
                if (msg.payload) {
                    setRoomStatuses(prev => mergeRoomStatusUpdatePayload(prev, msg.payload));
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
        // Copy array before iteration to prevent mutation during callback dispatch
        [...listenersRef.current].forEach(listener => listener(msg));
    }, [clearJoinTimers, clearReconnectStorage, showToast]);

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

        lastPongAtRef.current = Date.now();
        missedPongsRef.current = 0;

        const interval = window.setInterval(() => {
            // Check for missed pongs before sending next ping
            const elapsed = Date.now() - lastPongAtRef.current;
            if (elapsed > PING_INTERVAL_MS) {
                missedPongsRef.current++;
                if (missedPongsRef.current >= PONG_MISS_THRESHOLD) {
                    console.warn(`[Signaling] ${missedPongsRef.current} missed pongs, treating connection as dead`);
                    missedPongsRef.current = 0;
                    if (transportRef.current) {
                        if (transportRef.current.forceClose) {
                            transportRef.current.forceClose('ping-timeout');
                        } else {
                            transportRef.current.close();
                        }
                    }
                    return;
                }
            }
            sendMessage('ping', { ts: Date.now() });
        }, PING_INTERVAL_MS);

        return () => {
            window.clearInterval(interval);
        };
    }, [isConnected, sendMessage]);

    const joinRoom = useCallback((roomId: string) => {
        console.log(`[Signaling] joinRoom call for ${roomId}`);
        setError(null);
        clearJoinTimers();
        needsRejoinRef.current = false;
        currentRoomIdRef.current = roomId;
        joinAttemptIdRef.current += 1;
        const attemptId = joinAttemptIdRef.current;
        joinAckedRef.current = false;

        if (transportRef.current && transportRef.current.isOpen()) {
            const payload: any = { capabilities: { trickleIce: true } };
            // If we have a previous client ID, send it to help server evict ghosts
            const reconnectCid = clientIdRef.current || lastClientIdRef.current;
            if (reconnectCid) {
                payload.reconnectCid = reconnectCid;
                if (reconnectTokenRef.current && reconnectTokenRoomIdRef.current === roomId) {
                    payload.reconnectToken = reconnectTokenRef.current;
                }
            }

            const doSendJoin = () => {
                if (joinAttemptIdRef.current !== attemptId) return;
                sendMessage('join', payload);
            };

            doSendJoin();

            // Join kickstart: re-send join if no ack after 1.2s
            joinKickstartTimerRef.current = window.setTimeout(() => {
                joinKickstartTimerRef.current = null;
                if (joinAttemptIdRef.current !== attemptId || joinAckedRef.current) return;
                console.log('[Signaling] Join kickstart: re-sending join');
                doSendJoin();
            }, JOIN_CONNECT_KICKSTART_MS);

            // Join recovery: re-send join if still no ack after 4s
            joinRecoveryTimerRef.current = window.setTimeout(() => {
                joinRecoveryTimerRef.current = null;
                if (joinAttemptIdRef.current !== attemptId || joinAckedRef.current) return;
                console.log('[Signaling] Join recovery: re-sending join');
                doSendJoin();
            }, JOIN_RECOVERY_MS);

            // Hard timeout: give up after 15s
            joinHardTimeoutRef.current = window.setTimeout(() => {
                joinHardTimeoutRef.current = null;
                if (joinAttemptIdRef.current !== attemptId || joinAckedRef.current) return;
                console.error('[Signaling] Join hard timeout reached');
                clearJoinTimers();
                setError('Join timed out');
            }, JOIN_HARD_TIMEOUT_MS);
        } else {
            console.log('[Signaling] Transport not ready, buffering join');
            pendingJoinRef.current = roomId;
        }
    }, [clearJoinTimers, sendMessage]);

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

        const resetTransportState = () => {
            transportIndexRef.current = 0;
            transportConnectedOnceRef.current = { ws: false, sse: false };
        };
        resetTransportState();

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
            const backoff = Math.min(RECONNECT_BACKOFF_BASE_MS * Math.pow(2, attempt - 1), RECONNECT_BACKOFF_CAP_MS);

            reconnectTimeout = window.setTimeout(() => {
                reconnectTimeout = null;
                resetTransportState();
                connect(0);
            }, backoff);
        };

        const shouldFallback = (kind: TransportKind, reason: string) => {
            const order = transportOrderRef.current;
            if (order.length <= 1) return false;
            if (transportIndexRef.current >= order.length - 1) return false;
            if (reason === 'unsupported' || reason === 'timeout') return true;
            // Allow SSE fallback if WS hasn't connected even once
            if (!transportConnectedOnceRef.current[kind]) return true;
            // Allow SSE fallback after consecutive WS failures even if WS connected before
            if (kind === 'ws' && wsConsecutiveFailuresRef.current >= WS_FALLBACK_CONSECUTIVE_FAILURES) {
                console.warn(`[Signaling] ${wsConsecutiveFailuresRef.current} consecutive WS failures, allowing SSE fallback`);
                return true;
            }
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
                // Save SSE sid before closing for reuse on reconnect
                if (transportRef.current.getSessionId) {
                    sseSidRef.current = transportRef.current.getSessionId();
                }
                transportRef.current.close();
            }

            const connectionId = transportIdRef.current + 1;
            transportIdRef.current = connectionId;

            const transport = createSignalingTransport(targetKind, {
                onOpen: () => {
                    if (connectionId !== transportIdRef.current) return;
                    connectingRef.current = false;
                    reconnectAttemptsRef.current = 0;
                    if (targetKind === 'ws') {
                        wsConsecutiveFailuresRef.current = 0;
                    }
                    const wasConnected = isConnectedRef.current;
                    setIsConnected(true);
                    setActiveTransport(targetKind);
                    transportConnectedOnceRef.current[targetKind] = true;
                    if (!wasConnected) {
                        if (pendingJoinRef.current) {
                            joinRoom(pendingJoinRef.current);
                            pendingJoinRef.current = null;
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
                    if (targetKind === 'ws') {
                        wsConsecutiveFailuresRef.current++;
                    }
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
            }, { sseSid: sseSidRef.current || undefined });

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

    // Proactive TURN credential refresh at 80% of TTL
    useEffect(() => {
        if (turnRefreshTimerRef.current) {
            window.clearTimeout(turnRefreshTimerRef.current);
            turnRefreshTimerRef.current = null;
        }
        if (!isConnected || !turnTokenTTLMs || !currentRoomIdRef.current) return;

        const refreshDelay = turnTokenTTLMs * TURN_REFRESH_TRIGGER_RATIO;
        console.log(`[Signaling] Scheduling TURN refresh in ${Math.round(refreshDelay / 1000)}s`);
        turnRefreshTimerRef.current = window.setTimeout(() => {
            turnRefreshTimerRef.current = null;
            if (isConnectedRef.current && currentRoomIdRef.current) {
                console.log('[Signaling] Sending turn-refresh request');
                sendMessage('turn-refresh');
            }
        }, refreshDelay);

        return () => {
            if (turnRefreshTimerRef.current) {
                window.clearTimeout(turnRefreshTimerRef.current);
                turnRefreshTimerRef.current = null;
            }
        };
    }, [isConnected, turnTokenTTLMs, sendMessage]);

    const clearError = useCallback(() => setError(null), []);

    const leaveRoom = useCallback(() => {
        clearJoinTimers();
        sendMessage('leave');
        currentRoomIdRef.current = null;
        lastClientIdRef.current = null;
        needsRejoinRef.current = false;
        clearReconnectStorage();
        setRoomState(null);
        setTurnToken(null);
        setTurnTokenTTLMs(null);
    }, [clearJoinTimers, clearReconnectStorage, sendMessage]);

    const endRoom = useCallback(() => {
        clearJoinTimers();
        sendMessage('end_room');
    }, [clearJoinTimers, sendMessage]);

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
            turnTokenTTLMs,
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
