import { useEffect, useMemo, useState } from 'react';
import { RoomWatcher, parseTransportOrder, type RoomWatcherState } from '@serenada/core';
import { getConfiguredServerHost } from '../utils/serverHost';

const readWatcherState = (watcher: RoomWatcher): RoomWatcherState => ({
    isConnected: watcher.isConnected,
    activeTransport: watcher.activeTransport,
    roomStatuses: watcher.currentStatuses,
});

export function useRoomStatusWatcher(roomIds: string[]): RoomWatcherState {
    const uniqueRoomIds = useMemo(
        () => Array.from(new Set(roomIds.filter((roomId): roomId is string => typeof roomId === 'string' && roomId.length > 0))),
        [roomIds],
    );

    const watcher = useMemo(() => {
        const serverHost = getConfiguredServerHost();
        const rawTransports = import.meta.env.TRANSPORTS || import.meta.env.VITE_TRANSPORTS;

        return new RoomWatcher({
            serverHost,
            transports: parseTransportOrder(rawTransports),
        });
    }, []);

    const [state, setState] = useState<RoomWatcherState>(() => readWatcherState(watcher));

    useEffect(() => {
        const unsubscribe = watcher.subscribe(setState);

        return () => {
            unsubscribe();
            watcher.stop();
        };
    }, [watcher]);

    useEffect(() => {
        watcher.watchRooms(uniqueRoomIds);
    }, [uniqueRoomIds, watcher]);

    return state;
}
