import { describe, expect, it, afterEach } from 'vitest';
import {
    foregroundArbiter,
    __resetForegroundArbiterForTests,
} from '../src/foregroundArbiter.js';
import { ForegroundLeaseUnavailable } from '../src/types.js';

describe('foregroundArbiter (process singleton)', () => {
    afterEach(() => {
        __resetForegroundArbiterForTests();
    });

    it('grants one lease; a second acquire while live fails', () => {
        foregroundArbiter.acquireForeground('call-a');
        expect(() => foregroundArbiter.acquireForeground('call-b')).toThrow(ForegroundLeaseUnavailable);
    });

    it('release then reacquire succeeds', () => {
        const token = foregroundArbiter.acquireForeground('call-a');
        foregroundArbiter.releaseLease(token);
        // A fresh acquire works once the prior lease was released.
        const next = foregroundArbiter.acquireForeground('call-a');
        expect(foregroundArbiter.isCurrentOwner(next)).toBe(true);
    });

    it('releaseLease with a wrong (non-current) token throws', () => {
        const token = foregroundArbiter.acquireForeground('call-a');
        const forged = { ownerId: 'call-x' } as unknown as typeof token;
        expect(() => foregroundArbiter.releaseLease(forged)).toThrow(ForegroundLeaseUnavailable);
        // The real owner can still release.
        expect(() => foregroundArbiter.releaseLease(token)).not.toThrow();
    });

    it('releaseLease is idempotent for the same already-released token', () => {
        const token = foregroundArbiter.acquireForeground('call-a');
        foregroundArbiter.releaseLease(token);
        // Re-releasing the same token (nothing new acquired) is a safe no-op.
        expect(() => foregroundArbiter.releaseLease(token)).not.toThrow();
    });

    it('does not grant a new lease while a prior release is pending (contract §2 rule 2)', () => {
        foregroundArbiter.acquireForeground('call-a');
        // A registry that began draining the old owner marks the release pending
        // until it confirms fully-held; no new lease may be granted meanwhile so
        // two owners can never exist.
        foregroundArbiter.markReleasePending();
        expect(() => foregroundArbiter.acquireForeground('call-b')).toThrow(ForegroundLeaseUnavailable);
    });

    it('nextOperationGeneration is monotonic and separate from the token', () => {
        const g1 = foregroundArbiter.nextOperationGeneration();
        const g2 = foregroundArbiter.nextOperationGeneration();
        const g3 = foregroundArbiter.nextOperationGeneration();
        expect(g2).toBeGreaterThan(g1);
        expect(g3).toBeGreaterThan(g2);
    });

    it('cross-mode acquisition fails while the other mode has a live owner', () => {
        const registryRef = {};
        // Registry claims the process (Phase 3 registry would do this).
        foregroundArbiter.claimMode('registry', registryRef);
        // A direct single-call join now fails: the process is owned in registry mode.
        expect(() => foregroundArbiter.acquireForeground('call-direct', 'direct', {}))
            .toThrow(ForegroundLeaseUnavailable);
        // After the registry releases its mode, a direct acquire works.
        foregroundArbiter.releaseMode(registryRef);
        const token = foregroundArbiter.acquireForeground('call-direct', 'direct', {});
        expect(foregroundArbiter.isCurrentOwner(token)).toBe(true);
        expect(foregroundArbiter.currentMode).toBe('direct');
    });

    it('cross-mode fails in the other direction too (direct then registry)', () => {
        const directRef = {};
        foregroundArbiter.acquireForeground('call-direct', 'direct', directRef);
        expect(() => foregroundArbiter.claimMode('registry', {})).toThrow(ForegroundLeaseUnavailable);
    });

    it('__resetForegroundArbiterForTests clears lease and mode', () => {
        foregroundArbiter.acquireForeground('call-a', 'direct', {});
        __resetForegroundArbiterForTests();
        expect(foregroundArbiter.currentMode).toBeNull();
        // A fresh acquire after reset works (no leaked lease).
        const token = foregroundArbiter.acquireForeground('call-b');
        expect(foregroundArbiter.isCurrentOwner(token)).toBe(true);
    });
});
