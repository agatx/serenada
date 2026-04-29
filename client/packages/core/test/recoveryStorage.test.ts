import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';

// Provide a minimal window + sessionStorage shim for the Node test env,
// matching the pattern used by SignalingEngine.test.ts.
const store: Record<string, string> = {};
if (typeof globalThis.window === 'undefined') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).window = globalThis;
}
if (typeof (globalThis as { sessionStorage?: unknown }).sessionStorage === 'undefined') {
    Object.defineProperty(globalThis, 'sessionStorage', {
        value: {
            getItem: (k: string) => store[k] ?? null,
            setItem: (k: string, v: string) => { store[k] = v; },
            removeItem: (k: string) => { delete store[k]; },
            clear: () => { for (const k of Object.keys(store)) delete store[k]; },
        },
        configurable: true,
    });
}

import {
    loadRecoveryRecord,
    saveRecoveryRecord,
    clearRecoveryRecord,
} from '../src/recoveryStorage';

const STORAGE_KEY = 'serenada.recovery';

function freshRecord(overrides: Partial<Parameters<typeof saveRecoveryRecord>[0]> = {}) {
    return {
        roomId: 'room-1',
        cid: 'C-abc',
        reconnectToken: 'tok',
        lastEpoch: 7,
        sessionStartTs: Date.now() - 10_000,
        expiresAtMs: Date.now() + 60_000,
        ...overrides,
    };
}

describe('recoveryStorage', () => {
    beforeEach(() => {
        for (const k of Object.keys(store)) delete store[k];
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('returns null when nothing stored', () => {
        expect(loadRecoveryRecord()).toBeNull();
    });

    it('round-trips a valid record', () => {
        const rec = freshRecord();
        saveRecoveryRecord(rec);
        expect(loadRecoveryRecord()).toEqual(rec);
    });

    it('drops malformed JSON entries on read', () => {
        window.sessionStorage.setItem(STORAGE_KEY, '{not valid');
        expect(loadRecoveryRecord()).toBeNull();
        expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('drops records that are missing required fields', () => {
        window.sessionStorage.setItem(
            STORAGE_KEY,
            JSON.stringify({ roomId: 'r', cid: 'c' })
        );
        expect(loadRecoveryRecord()).toBeNull();
        // Read should also have removed the bad record.
        expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('drops expired records and clears the slot', () => {
        const rec = freshRecord({ expiresAtMs: Date.now() - 1 });
        saveRecoveryRecord(rec);
        expect(loadRecoveryRecord()).toBeNull();
        expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('clearRecoveryRecord removes any stored value', () => {
        saveRecoveryRecord(freshRecord());
        clearRecoveryRecord();
        expect(loadRecoveryRecord()).toBeNull();
    });

    it('lastEpoch may be null', () => {
        const rec = freshRecord({ lastEpoch: null });
        saveRecoveryRecord(rec);
        expect(loadRecoveryRecord()?.lastEpoch).toBeNull();
    });
});
