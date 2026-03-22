import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { TestSessionHarness } from './helpers/TestSessionHarness.js';

// SerenadaSession uses `window.setTimeout` / `window.clearTimeout`.
// In Node (no jsdom), `window` is undefined. Provide a shim that
// delegates dynamically so vi.useFakeTimers() patches are picked up.
if (typeof globalThis.window === 'undefined') {
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            // Delegate timer functions to globalThis (patched by vi.useFakeTimers)
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}

// SerenadaSession's permission check reads `navigator.permissions`.
// Provide a stub so the async check resolves deterministically.
if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

describe('SerenadaSession', () => {
    let harness: TestSessionHarness;

    beforeEach(() => {
        // Fake timers — SerenadaSession uses window.setTimeout for the ending timer
        vi.useFakeTimers();
    });

    afterEach(() => {
        harness?.destroy();
        vi.useRealTimers();
    });

    // ---------------------------------------------------------------
    // Join Flow
    // ---------------------------------------------------------------
    describe('join flow', () => {
        it('starts in joining phase', () => {
            harness = new TestSessionHarness();
            expect(harness.state.phase).toBe('joining');
            expect(harness.state.roomId).toBe('test-room-id');
        });

        it('does not auto-connect or auto-join when deps are injected', () => {
            harness = new TestSessionHarness();
            expect(harness.signaling.connectCalls).toBe(0);
            expect(harness.signaling.joinRoomCalls).toHaveLength(0);
        });

        it('transitions to waiting when joined with one participant', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            // Synchronous phase is waiting or awaitingPermissions depending on
            // whether the async permission check has resolved yet.
            expect(['waiting', 'awaitingPermissions']).toContain(harness.state.phase);
            expect(harness.state.localParticipant?.cid).toBe('me');
        });

        it('transitions to inCall when joined with two participants', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });

            expect(['inCall', 'awaitingPermissions']).toContain(harness.state.phase);
            if (harness.state.phase === 'inCall') {
                expect(harness.state.remoteParticipants).toHaveLength(1);
                expect(harness.state.remoteParticipants[0].cid).toBe('peer-1');
            }
        });

        it('sets localParticipant.isHost = true when clientId matches hostCid', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }],
                hostCid: 'me',
            });

            expect(harness.state.localParticipant?.isHost).toBe(true);
        });

        it('sets localParticipant.isHost = false when clientId does not match hostCid', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'other' }],
                hostCid: 'other',
            });

            expect(harness.state.localParticipant?.isHost).toBe(false);
        });

        it('propagates activeTransport from signaling', () => {
            harness = new TestSessionHarness();
            harness.signaling.emit({ isConnected: true, activeTransport: 'sse' });

            expect(harness.state.activeTransport).toBe('sse');
        });
    });

    // ---------------------------------------------------------------
    // Permission Gating
    // ---------------------------------------------------------------
    describe('permission gating', () => {
        // Stub navigator.permissions to return 'prompt' for both camera and microphone.
        // This triggers the awaitingPermissions flow in SerenadaSession.
        function stubPermissionsPrompt(): () => void {
            const original = navigator.permissions;
            Object.defineProperty(navigator, 'permissions', {
                value: {
                    query: () => Promise.resolve({ state: 'prompt' }),
                },
                configurable: true,
            });
            return () => {
                Object.defineProperty(navigator, 'permissions', {
                    value: original,
                    configurable: true,
                });
            };
        }

        it('moves to awaitingPermissions when permissions need prompting', async () => {
            const restore = stubPermissionsPrompt();
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            // Give the async permission check a tick to complete.
            await vi.advanceTimersByTimeAsync(0);
            expect(harness.state.phase).toBe('awaitingPermissions');
            restore();
        });

        it('resumeJoin transitions back to waiting after awaitingPermissions', async () => {
            const restore = stubPermissionsPrompt();
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            expect(harness.state.phase).toBe('awaitingPermissions');

            await harness.session.resumeJoin();

            expect(harness.state.phase).toBe('waiting');
            expect(harness.media.startLocalMediaCalls).toBe(1);
            restore();
        });

        it('cancelJoin sets phase to idle and destroys', async () => {
            const restore = stubPermissionsPrompt();
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            expect(harness.state.phase).toBe('awaitingPermissions');

            harness.session.cancelJoin();
            expect(harness.state.phase).toBe('idle');
            restore();
        });

        it('fires onPermissionsRequired callback', async () => {
            const restore = stubPermissionsPrompt();
            harness = new TestSessionHarness();
            const permissionsCb = vi.fn();
            harness.session.onPermissionsRequired = permissionsCb;

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);

            expect(permissionsCb).toHaveBeenCalled();
            restore();
        });

        it('auto-starts media when permissions are not needed', async () => {
            // Default environment: navigator.permissions is undefined → auto-grants
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);

            // Permission check auto-granted, startLocalMedia was called
            expect(harness.state.phase).toBe('waiting');
            expect(harness.media.startLocalMediaCalls).toBe(1);
        });
    });

    // ---------------------------------------------------------------
    // Room State Updates
    // ---------------------------------------------------------------
    describe('room state updates', () => {
        it('transitions from waiting to inCall when second participant joins', async () => {
            harness = new TestSessionHarness();
            // Skip permission check by pre-calling resumeJoin
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('waiting');

            // Second participant joins
            harness.simulateRoomStateUpdate({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.remoteParticipants).toHaveLength(1);
        });

        it('transitions from inCall to waiting when remote participant leaves', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('inCall');

            // Remote leaves
            harness.simulateRoomStateUpdate({
                hostCid: 'me',
                participants: [{ cid: 'me' }],
            });

            expect(harness.state.phase).toBe('waiting');
            expect(harness.state.remoteParticipants).toHaveLength(0);
        });

        it('correctly lists multiple remote participants', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }, { cid: 'peer-2' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.remoteParticipants).toHaveLength(2);
        });

        it('wires signaling messages to media engine', () => {
            harness = new TestSessionHarness();
            const msg = { v: 1, type: 'offer', payload: { sdp: 'test', from: 'peer-1' } };
            harness.signaling.emitMessage(msg);

            expect(harness.media.processSignalingMessageCalls).toHaveLength(1);
            expect(harness.media.processSignalingMessageCalls[0]).toEqual(msg);
        });

        it('forwards signaling connected state to media engine', () => {
            harness = new TestSessionHarness();
            harness.signaling.emit({ isConnected: true, activeTransport: 'ws' });

            expect(harness.media.updateSignalingConnectedCalls).toContain(true);
        });

        it('forwards TURN token to media engine', () => {
            harness = new TestSessionHarness();
            harness.signaling.emit({ isConnected: true, turnToken: 'turn-abc' });

            expect(harness.media.updateTurnTokenCalls).toContain('turn-abc');
        });

        it('forwards room state to media engine', () => {
            harness = new TestSessionHarness();
            const roomState = { hostCid: 'me', participants: [{ cid: 'me' }] };
            harness.signaling.emit({ clientId: 'me', roomState });

            expect(harness.media.updateRoomStateCalls.length).toBeGreaterThan(0);
            const last = harness.media.updateRoomStateCalls[harness.media.updateRoomStateCalls.length - 1];
            expect(last.state).toEqual(roomState);
            expect(last.clientId).toBe('me');
        });
    });

    // ---------------------------------------------------------------
    // Error Handling
    // ---------------------------------------------------------------
    describe('error handling', () => {
        it('sets phase to error on signaling error', () => {
            harness = new TestSessionHarness();
            harness.simulateError('Connection refused');

            expect(harness.state.phase).toBe('error');
            expect(harness.state.error).toEqual({
                code: 'signaling_error',
                message: 'Connection refused',
            });
        });

        it('overwrites previous phase on error', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('waiting');

            harness.simulateError('Server crashed');
            expect(harness.state.phase).toBe('error');
        });

        it('clears error when signaling error is reset and room state arrives', async () => {
            harness = new TestSessionHarness();
            harness.simulateError('Temporary failure');
            expect(harness.state.phase).toBe('error');

            // Error cleared + room state restored
            harness.signaling.emit({
                error: null,
                isConnected: true,
                activeTransport: 'ws',
                clientId: 'me',
                roomState: { hostCid: 'me', participants: [{ cid: 'me' }] },
            });

            // Phase should recover to waiting or awaitingPermissions
            expect(['waiting', 'awaitingPermissions']).toContain(harness.state.phase);
            expect(harness.state.error).toBeNull();
        });
    });

    // ---------------------------------------------------------------
    // Leave / End
    // ---------------------------------------------------------------
    describe('leave and end', () => {
        it('leave sends leaveRoom, cleans up peers, sets phase to idle', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.session.leave();

            expect(harness.signaling.leaveRoomCalls).toHaveLength(1);
            expect(harness.media.cleanupAllPeersCalls).toBe(1);
            expect(harness.state.phase).toBe('idle');
        });

        it('end sends endRoom then leave', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.session.end();

            expect(harness.signaling.endRoomCalls).toBe(1);
            expect(harness.signaling.leaveRoomCalls).toHaveLength(1);
            expect(harness.state.phase).toBe('idle');
        });

        it('leave is idempotent after destroy', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.session.leave();
            harness.session.leave(); // second call should be no-op

            expect(harness.signaling.leaveRoomCalls).toHaveLength(1);
        });

        it('destroy tears down signaling and media', () => {
            harness = new TestSessionHarness();

            harness.session.destroy();

            expect(harness.signaling.destroyCalls).toBe(1);
            expect(harness.media.destroyCalls).toBe(1);
        });
    });

    // ---------------------------------------------------------------
    // Ending Screen
    // ---------------------------------------------------------------
    describe('ending screen', () => {
        it('shows ending phase for 3 seconds then transitions to idle', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('inCall');

            // Room ended — roomState cleared
            harness.simulateRoomEnded();
            expect(harness.state.phase).toBe('ending');

            // Advance 2.9 seconds — still ending
            vi.advanceTimersByTime(2900);
            expect(harness.state.phase).toBe('ending');

            // Advance past 3 seconds
            vi.advanceTimersByTime(200);
            expect(harness.state.phase).toBe('idle');
        });

        it('shows ending when going from waiting to no roomState', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('waiting');

            harness.simulateRoomEnded();
            expect(harness.state.phase).toBe('ending');

            vi.advanceTimersByTime(3100);
            expect(harness.state.phase).toBe('idle');
        });
    });

    // ---------------------------------------------------------------
    // Reconnect Behavior
    // ---------------------------------------------------------------
    describe('reconnect behavior', () => {
        it('rebuilds state when signaling reconnects with room state', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('inCall');

            // Simulate disconnect
            harness.simulateDisconnect();
            expect(harness.state.activeTransport).toBeNull();

            // Simulate reconnect with room state restored
            harness.signaling.emit({
                isConnected: true,
                activeTransport: 'ws',
                clientId: 'me',
                roomState: { hostCid: 'me', participants: [{ cid: 'me' }, { cid: 'peer-1' }] },
            });

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.activeTransport).toBe('ws');
        });

        it('connectionStatus reflects media engine status', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            harness.media.emit({ connectionStatus: 'recovering' });

            expect(harness.state.connectionStatus).toBe('recovering');
        });
    });

    // ---------------------------------------------------------------
    // State subscription
    // ---------------------------------------------------------------
    describe('state subscription', () => {
        it('records state history through subscribe', async () => {
            harness = new TestSessionHarness();
            const initialHistoryLen = harness.stateHistory.length;

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);

            expect(harness.stateHistory.length).toBeGreaterThan(initialHistoryLen);
        });

        it('unsubscribe stops receiving state updates', () => {
            harness = new TestSessionHarness();

            const states: string[] = [];
            const unsub = harness.session.subscribe((s) => states.push(s.phase));

            harness.signaling.emit({ isConnected: true, activeTransport: 'ws' });
            const countAfterEmit = states.length;

            unsub();
            harness.signaling.emit({ isConnected: false, activeTransport: null });

            expect(states.length).toBe(countAfterEmit);
        });
    });

    // ---------------------------------------------------------------
    // Media wiring
    // ---------------------------------------------------------------
    describe('media wiring', () => {
        it('media onChange triggers rebuildState', () => {
            harness = new TestSessionHarness();
            harness.signaling.emit({
                isConnected: true,
                activeTransport: 'ws',
                clientId: 'me',
                roomState: { hostCid: 'me', participants: [{ cid: 'me' }] },
            });

            const countBefore = harness.stateHistory.length;
            harness.media.emit({ connectionStatus: 'retrying' });

            expect(harness.stateHistory.length).toBeGreaterThan(countBefore);
            expect(harness.state.connectionStatus).toBe('retrying');
        });
    });

    // ---------------------------------------------------------------
    // Config defaults
    // ---------------------------------------------------------------
    describe('config defaults', () => {
        it('defaults audioEnabled/videoEnabled based on config', () => {
            harness = new TestSessionHarness({
                config: { serverHost: 'localhost', defaultAudioEnabled: false, defaultVideoEnabled: false },
            });

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            // No local stream → uses config defaults
            expect(harness.state.localParticipant?.audioEnabled).toBe(false);
            expect(harness.state.localParticipant?.videoEnabled).toBe(false);
        });

        it('defaults audioEnabled/videoEnabled to true when not specified', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            expect(harness.state.localParticipant?.audioEnabled).toBe(true);
            expect(harness.state.localParticipant?.videoEnabled).toBe(true);
        });
    });
});
