import { describe, expect, it } from 'vitest';
import { RoomWatcher } from '../../src/RoomWatcher';
import type { RoomWatcherState } from '../../src/types';
import type { SignalingEngine } from '../../src/signaling/SignalingEngine';

class FakeSignalingEngine {
    isConnected = false;
    activeTransport: 'ws' | 'sse' | null = null;
    roomStatuses = {};
    connectCalls = 0;
    destroyCalls = 0;
    watchCalls: string[][] = [];
    private listener: (() => void) | null = null;

    connect(): void {
        this.connectCalls += 1;
    }

    destroy(): void {
        this.destroyCalls += 1;
    }

    watchRooms(roomIds: string[]): void {
        this.watchCalls.push(roomIds);
    }

    onStateChange(listener: () => void): () => void {
        this.listener = listener;
        return () => {
            if (this.listener === listener) {
                this.listener = null;
            }
        };
    }

    emit(partial: Partial<FakeSignalingEngine>): void {
        Object.assign(this, partial);
        this.listener?.();
    }
}

describe('RoomWatcher', () => {
    it('connects once, filters statuses to watched rooms, and tears down cleanly', () => {
        const signaling = new FakeSignalingEngine();
        const watcher = new RoomWatcher(
            { serverHost: 'serenada.app' },
            { createSignalingEngine: () => signaling as unknown as SignalingEngine },
        );
        const snapshots: RoomWatcherState[] = [];

        const unsubscribe = watcher.subscribe((state) => {
            snapshots.push(state);
        });

        watcher.watchRooms(['alpha', 'alpha', '', 'beta']);

        expect(signaling.connectCalls).toBe(1);
        expect(snapshots.at(-1)).toEqual({
            isConnected: false,
            activeTransport: null,
            roomStatuses: {},
        });

        signaling.emit({
            isConnected: true,
            activeTransport: 'ws',
            roomStatuses: {
                alpha: { count: 1, maxParticipants: 4 },
                gamma: { count: 2, maxParticipants: 4 },
            },
        });

        expect(signaling.watchCalls).toEqual([['alpha', 'beta']]);
        expect(snapshots.at(-1)).toEqual({
            isConnected: true,
            activeTransport: 'ws',
            roomStatuses: {
                alpha: { count: 1, maxParticipants: 4 },
            },
        });

        // Subsequent state changes while connected should NOT re-send watch_rooms
        signaling.watchCalls = [];
        signaling.emit({
            isConnected: true,
            roomStatuses: { alpha: { count: 2, maxParticipants: 4 } },
        });
        expect(signaling.watchCalls).toEqual([]);

        watcher.stop();
        unsubscribe();

        expect(signaling.destroyCalls).toBe(1);
    });
});
