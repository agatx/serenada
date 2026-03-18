import { useSyncExternalStore, useCallback } from 'react';
import type { CallState } from '@serenada/core';
import type { SerenadaSession } from '@serenada/core';

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

export function useCallState(session: SerenadaSession | null): CallState {
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

    return useSyncExternalStore(subscribe, getSnapshot);
}
