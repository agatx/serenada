import { beforeEach, describe, expect, it } from 'vitest';
import { getPersistedRemoteVideoFit, persistRemoteVideoFit } from '../../src/utils/remoteVideoFit';

class LocalStorageMock {
    private store = new Map<string, string>();

    getItem(key: string) {
        return this.store.get(key) ?? null;
    }

    setItem(key: string, value: string) {
        this.store.set(key, value);
    }

    clear() {
        this.store.clear();
    }
}

const localStorageMock = new LocalStorageMock();

Object.defineProperty(globalThis, 'localStorage', {
    value: localStorageMock,
    configurable: true
});

describe('remoteVideoFit', () => {
    beforeEach(() => {
        localStorageMock.clear();
    });

    it('defaults to cover when nothing is stored', () => {
        expect(getPersistedRemoteVideoFit()).toBe('cover');
    });

    it('persists and restores a valid fit mode', () => {
        persistRemoteVideoFit('contain');

        expect(getPersistedRemoteVideoFit()).toBe('contain');
    });

    it('ignores invalid stored values', () => {
        localStorage.setItem('serenada_remote_video_fit', 'stretch');

        expect(getPersistedRemoteVideoFit()).toBe('cover');
    });
});
