import type { RoomWatcherState, SerenadaConfig } from './types.js';
import { resolveServerUrls } from './serverUrls.js';
import { SignalingEngine, type SignalingEngineConfig } from './signaling/SignalingEngine.js';
import type { RoomStatuses } from './signaling/roomStatuses.js';

type RoomWatcherListener = (state: RoomWatcherState) => void;

interface RoomWatcherDependencies {
    createSignalingEngine?: (config: SignalingEngineConfig) => SignalingEngine;
}

export class RoomWatcher {
    private readonly signaling: SignalingEngine;
    private readonly unsubscribeStateChange: () => void;
    private listeners = new Set<RoomWatcherListener>();
    private watchedRoomIds: string[] = [];
    private hasConnected = false;
    private stopped = false;
    private wasConnected = false;

    constructor(config: SerenadaConfig, dependencies: RoomWatcherDependencies = {}) {
        const urls = resolveServerUrls(config.serverHost);
        this.signaling = (dependencies.createSignalingEngine ?? ((engineConfig) => new SignalingEngine(engineConfig)))({
            wsUrl: urls.wsUrl,
            httpBaseUrl: urls.httpBaseUrl,
            transports: config.transports,
        });
        this.unsubscribeStateChange = this.signaling.onStateChange(() => {
            const nowConnected = this.signaling.isConnected;
            if (nowConnected && !this.wasConnected && this.watchedRoomIds.length > 0) {
                this.signaling.watchRooms(this.watchedRoomIds);
            }
            this.wasConnected = nowConnected;
            this.notify();
        });
    }

    get isConnected(): boolean {
        return !this.stopped && this.signaling.isConnected;
    }

    get activeTransport() {
        return this.stopped ? null : this.signaling.activeTransport;
    }

    get currentStatuses(): RoomStatuses {
        return filterRoomStatuses(this.signaling.roomStatuses, this.watchedRoomIds);
    }

    subscribe(listener: RoomWatcherListener): () => void {
        this.listeners.add(listener);
        listener(this.snapshot());
        return () => {
            this.listeners.delete(listener);
        };
    }

    watchRooms(roomIds: string[]): void {
        if (this.stopped) return;

        this.watchedRoomIds = Array.from(new Set(roomIds.filter((roomId): roomId is string => typeof roomId === 'string' && roomId.length > 0)));
        this.notify();

        if (this.watchedRoomIds.length === 0) {
            return;
        }

        if (!this.hasConnected) {
            this.hasConnected = true;
            this.signaling.connect();
            return;
        }

        if (this.signaling.isConnected) {
            this.signaling.watchRooms(this.watchedRoomIds);
        }
    }

    stop(): void {
        if (this.stopped) return;

        this.stopped = true;
        this.watchedRoomIds = [];
        this.unsubscribeStateChange();
        this.signaling.destroy();
        this.notify();
        this.listeners.clear();
    }

    private notify(): void {
        const state = this.snapshot();
        for (const listener of this.listeners) {
            listener(state);
        }
    }

    private snapshot(): RoomWatcherState {
        return {
            isConnected: this.isConnected,
            activeTransport: this.activeTransport,
            roomStatuses: this.currentStatuses,
        };
    }
}

function filterRoomStatuses(roomStatuses: RoomStatuses, watchedRoomIds: string[]): RoomStatuses {
    if (watchedRoomIds.length === 0) {
        return {};
    }

    const watched = new Set(watchedRoomIds);
    return Object.fromEntries(
        Object.entries(roomStatuses).filter(([roomId]) => watched.has(roomId)),
    );
}
