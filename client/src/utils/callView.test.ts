import { describe, expect, it } from 'vitest';
import type { CallPhase, ManagedCallState } from '@agatx/serenada-core';
import { selectCallView } from './callView';

// Minimal ManagedCallState factory. The decision only reads `membershipPhase`,
// `mediaRole`, `held`, `id`, so the rest are filled with inert defaults.
function makeCall(overrides: Partial<ManagedCallState> = {}): ManagedCallState {
    const held = overrides.mediaRole ? overrides.mediaRole === 'held' : (overrides.held ?? true);
    return {
        id: 'call-1',
        roomId: 'room-1',
        roomUrl: null,
        membershipPhase: 'inCall' as CallPhase,
        mediaRole: held ? 'held' : 'foreground',
        mediaActivationState: 'inactive',
        desiredAudioEnabled: true,
        desiredVideoMode: 'selfie',
        actualAudioPublished: false,
        actualVideoPublished: false,
        participantCount: 1,
        localCid: 'cid-1',
        held,
        displayName: null,
        activationError: null,
        qualitySummary: null,
        ...overrides,
        // keep `held` consistent with `mediaRole` even when only one was passed
        ...(overrides.mediaRole && overrides.held === undefined
            ? { held: overrides.mediaRole === 'held' }
            : {}),
    };
}

describe('selectCallView', () => {
    it('renders the active flow when a foreground session is available', () => {
        const view = selectCallView({
            hasActiveSession: true,
            calls: [makeCall({ id: 'a', mediaRole: 'foreground', membershipPhase: 'inCall' })],
            registryOperationInProgress: false,
        });
        expect(view).toBe('active');
    });

    it('shows idle when there are no live calls', () => {
        expect(
            selectCallView({ hasActiveSession: false, calls: [], registryOperationInProgress: false }),
        ).toBe('idle');
    });

    it('treats ending/error calls as not live (idle)', () => {
        const view = selectCallView({
            hasActiveSession: false,
            calls: [
                makeCall({ id: 'a', membershipPhase: 'ending' }),
                makeCall({ id: 'b', membershipPhase: 'error' }),
            ],
            registryOperationInProgress: false,
        });
        expect(view).toBe('idle');
    });

    // The core contract assertion (contract §5/§7; design "Remote Playback" /
    // "React UI"): when no call is foregrounded but a settled live HELD call
    // exists, the decision is 'held' — NOT 'active'. CallRoom mounts the
    // on-hold switcher, never the held session, as the active SerenadaCallFlow.
    it('shows the held placeholder (never active) when only a settled held call exists', () => {
        const view = selectCallView({
            hasActiveSession: false,
            calls: [makeCall({ id: 'held-1', mediaRole: 'held', membershipPhase: 'inCall' })],
            registryOperationInProgress: false,
        });
        expect(view).toBe('held');
        // Explicit: a held call must never be selected as the active flow.
        expect(view).not.toBe('active');
    });

    it('shows held for a settled held call in the waiting phase', () => {
        const view = selectCallView({
            hasActiveSession: false,
            calls: [makeCall({ id: 'held-1', mediaRole: 'held', membershipPhase: 'waiting' })],
            registryOperationInProgress: false,
        });
        expect(view).toBe('held');
    });

    it('shows the joining placeholder while a join is in flight (not held, not active)', () => {
        const view = selectCallView({
            hasActiveSession: false,
            calls: [makeCall({ id: 'a', mediaRole: 'held', membershipPhase: 'joining' })],
            registryOperationInProgress: false,
        });
        expect(view).toBe('joining');
    });

    it('shows joining while a registry op is settling a live call (single-call join parity)', () => {
        // Held call has reached `inCall` but the foreground-activation op is still
        // running: show "joining", not "held" (this is the initial-join window).
        const view = selectCallView({
            hasActiveSession: false,
            calls: [makeCall({ id: 'a', mediaRole: 'held', membershipPhase: 'inCall' })],
            registryOperationInProgress: true,
        });
        expect(view).toBe('joining');
    });

    it('prefers active over every other state', () => {
        const view = selectCallView({
            hasActiveSession: true,
            calls: [
                makeCall({ id: 'fg', mediaRole: 'foreground', membershipPhase: 'inCall' }),
                makeCall({ id: 'held', mediaRole: 'held', membershipPhase: 'inCall' }),
            ],
            registryOperationInProgress: true,
        });
        expect(view).toBe('active');
    });

    it('prefers a joining sibling over a settled held sibling', () => {
        // While one call is still joining, the joining placeholder wins so we
        // never briefly mount/treat a held sibling as the foreground call.
        const view = selectCallView({
            hasActiveSession: false,
            calls: [
                makeCall({ id: 'held', mediaRole: 'held', membershipPhase: 'inCall' }),
                makeCall({ id: 'joining', mediaRole: 'held', membershipPhase: 'joining' }),
            ],
            registryOperationInProgress: false,
        });
        expect(view).toBe('joining');
    });
});
