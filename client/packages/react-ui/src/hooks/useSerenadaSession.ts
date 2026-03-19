import { useEffect, useMemo, useSyncExternalStore, useCallback, useState } from 'react';
import type { SerenadaConfig, CallState } from '@serenada/core';
import { SerenadaSession, SerenadaCore } from '@serenada/core';
import { IDLE_STATE } from './constants.js';

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

const EMPTY_STREAMS = new Map<string, MediaStream>();

export function useSerenadaSession(options: UseSerenadaSessionOptions): UseSerenadaSessionResult {
    const { url, roomId, config } = options;
    const [session, setSession] = useState<SerenadaSession | null>(null);
    const transportsKey = config.transports?.join('|') ?? '';

    const core = useMemo(
        () => new SerenadaCore({
            ...config,
            transports: config.transports ? [...config.transports] : undefined,
        }),
        [
            config.serverHost,
            config.defaultAudioEnabled,
            config.defaultVideoEnabled,
            transportsKey,
            config.turnsOnly,
        ],
    );

    useEffect(() => {
        if (!url && !roomId) return;

        const sess = url ? core.join(url) : core.join({ roomId: roomId! });
        setSession(sess);

        return () => {
            sess.destroy();
            setSession(null);
        };
    }, [url, roomId, core]);

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
