/**
 * Persistent recovery state — surfaced to host apps so a relaunched tab can
 * prompt the user to rejoin an in-flight call instead of silently dropping
 * them on the home screen.
 *
 * Per the Phase 2 spec (`docs/resilience-failure-modes.md`, #5), the web
 * scope is `sessionStorage`: per-tab, survives reload, lost on tab close —
 * the right scope for "you reloaded the page mid-call".
 *
 * The record carries the same `reconnectToken` that `SignalingEngine`
 * persists for in-tab reconnects. The two stores are kept in lockstep:
 * a fresh `joined` writes both, a clean leave / `room_ended` /
 * `INVALID_RECONNECT_TOKEN` clears both.
 */

export interface RecoveryRecord {
    roomId: string;
    cid: string;
    reconnectToken: string;
    /** Server room state epoch at the moment the record was last refreshed. */
    lastEpoch: number | null;
    /** Unix-ms timestamp of the original join (NOT the latest reconnect). */
    sessionStartTs: number;
    /**
     * Unix-ms after which the host app should NOT offer the rejoin prompt.
     * Computed as `now + reconnectTokenTTLMs` at write time so the SDK does
     * not need to know server clocks.
     */
    expiresAtMs: number;
}

const STORAGE_KEY = 'serenada.recovery';

function getStorage(): Storage | null {
    try {
        return typeof window !== 'undefined' ? window.sessionStorage : null;
    } catch {
        return null;
    }
}

export function loadRecoveryRecord(): RecoveryRecord | null {
    const store = getStorage();
    if (!store) return null;
    try {
        const raw = store.getItem(STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (
            typeof parsed !== 'object' ||
            parsed === null ||
            typeof parsed.roomId !== 'string' ||
            typeof parsed.cid !== 'string' ||
            typeof parsed.reconnectToken !== 'string' ||
            typeof parsed.sessionStartTs !== 'number' ||
            typeof parsed.expiresAtMs !== 'number'
        ) {
            store.removeItem(STORAGE_KEY);
            return null;
        }
        if (Date.now() > parsed.expiresAtMs) {
            store.removeItem(STORAGE_KEY);
            return null;
        }
        return {
            roomId: parsed.roomId,
            cid: parsed.cid,
            reconnectToken: parsed.reconnectToken,
            lastEpoch: typeof parsed.lastEpoch === 'number' ? parsed.lastEpoch : null,
            sessionStartTs: parsed.sessionStartTs,
            expiresAtMs: parsed.expiresAtMs,
        };
    } catch {
        try {
            store.removeItem(STORAGE_KEY);
        } catch {
            // Ignore cleanup failures and preserve best-effort behavior.
        }
        return null;
    }
}

export function saveRecoveryRecord(record: RecoveryRecord): void {
    const store = getStorage();
    if (!store) return;
    try {
        store.setItem(STORAGE_KEY, JSON.stringify(record));
    } catch {
        // Quota / private mode — best-effort persistence.
    }
}

export function clearRecoveryRecord(): void {
    const store = getStorage();
    if (!store) return;
    try {
        store.removeItem(STORAGE_KEY);
    } catch {
        // Ignore.
    }
}
