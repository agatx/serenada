import { useSyncExternalStore, useCallback } from 'react';
import type { CallState } from '@serenada/core';
import type { SerenadaSessionHandle } from '@serenada/core';
import { IDLE_STATE } from './constants.js';

export function useCallState(session: SerenadaSessionHandle | null): CallState {
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
