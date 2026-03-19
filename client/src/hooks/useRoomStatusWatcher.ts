import { useEffect, useMemo, useState } from 'react';
import { SignalingEngine, parseTransportOrder, type RoomStatuses, type TransportKind } from '@serenada/core';
import { getConfiguredServerHost, resolveServerUrls } from '../utils/serverHost';

interface RoomStatusWatcherState {
    isConnected: boolean;
    activeTransport: TransportKind | null;
    roomStatuses: RoomStatuses;
}

const readWatcherState = (engine: SignalingEngine): RoomStatusWatcherState => ({
    isConnected: engine.isConnected,
    activeTransport: engine.activeTransport,
    roomStatuses: engine.roomStatuses,
});

export function useRoomStatusWatcher(roomIds: string[]): RoomStatusWatcherState {
    const uniqueRoomIds = useMemo(
        () => Array.from(new Set(roomIds.filter((roomId): roomId is string => typeof roomId === 'string' && roomId.length > 0))),
        [roomIds],
    );
    const roomIdsKey = uniqueRoomIds.join('|');

    const watcher = useMemo(() => {
        const serverHost = getConfiguredServerHost();
        const urls = resolveServerUrls(serverHost);
        const rawTransports = import.meta.env.TRANSPORTS || import.meta.env.VITE_TRANSPORTS;

        return new SignalingEngine({
            wsUrl: urls.wsUrl,
            httpBaseUrl: urls.httpBaseUrl,
            transports: parseTransportOrder(rawTransports),
        });
    }, []);

    const [state, setState] = useState<RoomStatusWatcherState>(() => readWatcherState(watcher));

    useEffect(() => {
        const unsubscribe = watcher.onStateChange(() => {
            setState(readWatcherState(watcher));
        });

        watcher.connect();

        return () => {
            unsubscribe();
            watcher.destroy();
        };
    }, [watcher]);

    useEffect(() => {
        if (!state.isConnected || uniqueRoomIds.length === 0) return;
        watcher.watchRooms(uniqueRoomIds);
    }, [roomIdsKey, state.isConnected, uniqueRoomIds, watcher]);

    return state;
}
