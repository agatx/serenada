import { useEffect, useRef, useMemo, useSyncExternalStore, useCallback } from 'react';
import type { SerenadaConfig, CallState } from '@serenada/core';
import { SerenadaSession, SerenadaCore } from '@serenada/core';

export interface UseSerenadaSessionOptions {
    url?: string;
    roomId?: string;
    config: SerenadaConfig;
}

export interface UseSerenadaSessionResult {
    session: SerenadaSession | null;
    state: CallState;
    localStream: MediaStream | null;
    remoteStreams: Map<string, MediaStream>;
}

const IDLE_STATE: CallState = {
    phase: 'idle',
    roomId: null,
    roomUrl: null,
    localParticipant: null,
    remoteParticipants: [],
    connectionStatus: 'connected',
    activeTransport: null,
    requiredPermissions: null,
    error: null,
};

const EMPTY_STREAMS = new Map<string, MediaStream>();

export function useSerenadaSession(options: UseSerenadaSessionOptions): UseSerenadaSessionResult {
    const { url, roomId, config } = options;
    const sessionRef = useRef<SerenadaSession | null>(null);

    // eslint-disable-next-line react-hooks/exhaustive-deps
    const core = useMemo(() => new SerenadaCore(config), [config.serverHost]);

    useEffect(() => {
        if (!url && !roomId) return;

        const session = url ? core.join(url) : core.join({ roomId: roomId! });
        sessionRef.current = session;

        return () => {
            session.destroy();
            sessionRef.current = null;
        };
    }, [url, roomId, core]);

    const session = sessionRef.current;

    const subscribe = useCallback(
        (onStoreChange: () => void) => {
            if (!session) return () => {};
            return session.subscribe(onStoreChange);
        },
        [session],
    );

    const getSnapshot = useCallback(
        () => session?.state ?? IDLE_STATE,
        [session],
    );

    const state = useSyncExternalStore(subscribe, getSnapshot);

    return {
        session,
        state,
        localStream: session?.localStream ?? null,
        remoteStreams: session?.remoteStreams ?? EMPTY_STREAMS,
    };
}
