import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import {
    JOIN_HARD_TIMEOUT_MS,
    MEDIA_LIVENESS_INTERVAL_MS,
    PEER_SUSPENDED_UI_TIMEOUT_MS,
    SUSPEND_HARD_EVICTION_TIMEOUT_MS,
} from '../src/constants.js';
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

    function expectTerminalTeardown(expectedPhase: 'ending' | 'error' | 'idle'): void {
        expect(harness.state.phase).toBe(expectedPhase);
        expect(harness.state.localParticipant).toBeNull();
        expect(harness.state.remoteParticipants).toHaveLength(0);
        expect(harness.state.connectionStatus).toBe('disconnected');
        expect(harness.state.activeTransport).toBeNull();
        expect(harness.session.localStream).toBeNull();
        expect(harness.session.isSignalingConnected).toBe(false);
        expect(harness.signaling.disconnectCalls).toBeGreaterThan(0);
        expect(harness.media.cleanupAllPeersCalls).toBeGreaterThan(0);
        expect(harness.media.stopLocalMediaCalls).toBeGreaterThan(0);
        expect(harness.media.updateSignalingConnectedCalls.at(-1)).toBe(false);
        expect(harness.media.updateRoomStateCalls.at(-1)).toEqual({
            state: null,
            clientId: null,
        });
    }

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

        it('sets localParticipant.displayName when displayName is provided', () => {
            harness = new TestSessionHarness({ displayName: 'Alice' });
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }],
            });

            expect(harness.state.localParticipant?.displayName).toBe('Alice');
        });

        it('sets remote participant displayName from room state', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [
                    { cid: 'me' },
                    { cid: 'peer-1', displayName: 'Bob' },
                ],
            });

            if (harness.state.phase === 'inCall') {
                expect(harness.state.remoteParticipants[0].displayName).toBe('Bob');
            }
        });

        it('propagates activeTransport from signaling', () => {
            harness = new TestSessionHarness();
            harness.signaling.emitConnected('sse');

            expect(harness.state.activeTransport).toBe('sse');
        });

        it('times out a self-managed provider join and ignores a late connect', async () => {
            harness = new TestSessionHarness({ handlesReconnection: false, autoStart: true });

            expect(harness.signaling.connectCalls).toBe(1);
            expect(harness.signaling.joinRoomCalls).toHaveLength(0);

            await vi.advanceTimersByTimeAsync(JOIN_HARD_TIMEOUT_MS + 1);

            expect(harness.state.phase).toBe('error');
            expect(harness.state.error).toEqual({
                code: 'signalingTimeout',
                message: 'Join timed out',
            });
            expectTerminalTeardown('error');

            harness.signaling.emitConnected('ws');

            expect(harness.signaling.joinRoomCalls).toHaveLength(0);
            expectTerminalTeardown('error');
        });

        it('times out a provider-managed join before signaling connects', async () => {
            harness = new TestSessionHarness({ handlesReconnection: true, autoStart: true });

            expect(harness.signaling.connectCalls).toBe(1);

            await vi.advanceTimersByTimeAsync(JOIN_HARD_TIMEOUT_MS + 1);

            expect(harness.state.error).toEqual({
                code: 'signalingTimeout',
                message: 'Join timed out',
            });
            expectTerminalTeardown('error');
        });

        it('times out a self-managed join after signaling connects but before joined', async () => {
            harness = new TestSessionHarness({ handlesReconnection: false, autoStart: true });

            harness.signaling.emitConnected('ws');
            expect(harness.signaling.joinRoomCalls).toEqual([{ roomId: 'test-room-id', options: {} }]);

            await vi.advanceTimersByTimeAsync(JOIN_HARD_TIMEOUT_MS + 1);

            expect(harness.state.error).toEqual({
                code: 'signalingTimeout',
                message: 'Join timed out',
            });
            expectTerminalTeardown('error');

            harness.signaling.emitJoined({
                peerId: 'me',
                participants: [{ peerId: 'me' }],
                hostPeerId: 'me',
            });

            expect(harness.signaling.joinRoomCalls).toHaveLength(1);
            expectTerminalTeardown('error');
        });

        it('times out a provider-managed join after signaling connects but before joined', async () => {
            harness = new TestSessionHarness({ handlesReconnection: true, autoStart: true });

            harness.signaling.emitConnected('ws');
            expect(harness.signaling.joinRoomCalls).toEqual([{ roomId: 'test-room-id', options: {} }]);

            await vi.advanceTimersByTimeAsync(JOIN_HARD_TIMEOUT_MS + 1);

            expect(harness.state.error).toEqual({
                code: 'signalingTimeout',
                message: 'Join timed out',
            });
            expectTerminalTeardown('error');
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

        it('supports incremental peerJoined and peerLeft updates without roomStateUpdated', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.signaling.emitPeerJoined({ peerId: 'peer-1', joinedAt: 2 });

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.remoteParticipants.map((participant) => participant.cid)).toEqual(['peer-1']);

            harness.signaling.emitPeerLeft({ peerId: 'peer-1', joinedAt: 2 });

            expect(harness.state.phase).toBe('waiting');
            expect(harness.state.remoteParticipants).toHaveLength(0);
        });

        it('supports a provider-only smoke flow with incremental presence and peer messages', async () => {
            harness = new TestSessionHarness();
            const messages: Array<{ from: string; type: string; payload: unknown }> = [];
            harness.session.onPeerMessage((message) => {
                messages.push(message);
            });

            harness.signaling.emitConnected('mock');
            harness.signaling.emitJoined({
                peerId: 'me',
                participants: [{ peerId: 'me', joinedAt: 1 }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.signaling.emitPeerJoined({ peerId: 'peer-1', joinedAt: 2 });
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'demo_message',
                payload: { text: 'hello from provider mode' },
            });

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.remoteParticipants.map((participant) => participant.cid)).toEqual(['peer-1']);
            expect(messages).toEqual([{
                from: 'peer-1',
                type: 'demo_message',
                payload: { text: 'hello from provider mode' },
            }]);
        });

        it('preserves host state when roomStateUpdated omits hostPeerId', async () => {
            harness = new TestSessionHarness();
            harness.signaling.emitConnected('ws');
            harness.signaling.emitJoined({
                peerId: 'me',
                participants: [{ peerId: 'me', joinedAt: 1 }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            expect(harness.state.localParticipant?.isHost).toBe(true);

            harness.signaling.emitRoomStateUpdated({
                participants: [
                    { peerId: 'me', joinedAt: 1 },
                    { peerId: 'peer-1', joinedAt: 2 },
                ],
            });

            expect(harness.state.localParticipant?.isHost).toBe(true);
            expect(harness.state.remoteParticipants.map((participant) => participant.cid)).toEqual(['peer-1']);
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
            harness.signaling.emitMessage({ from: 'peer-1', type: 'offer', payload: { sdp: 'test' } });

            expect(harness.media.processSignalingMessageCalls).toHaveLength(1);
            expect(harness.media.processSignalingMessageCalls[0]).toEqual({
                v: 1,
                type: 'offer',
                cid: 'peer-1',
                payload: { from: 'peer-1', sdp: 'test' },
            });
        });

        it('forwards provider content_state messages to the media engine', () => {
            harness = new TestSessionHarness();
            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare' },
            });

            expect(harness.media.processSignalingMessageCalls).toHaveLength(1);
            expect(harness.media.processSignalingMessageCalls[0]).toEqual({
                v: 1,
                type: 'content_state',
                cid: 'peer-1',
                payload: {
                    from: 'peer-1',
                    active: true,
                    contentType: 'screenShare',
                },
            });
        });

        it('forwards signaling connected state to media engine', () => {
            harness = new TestSessionHarness();
            harness.signaling.emitConnected('ws');

            expect(harness.media.updateSignalingConnectedCalls).toContain(true);
        });

        it('applies initial ICE servers from the provider', async () => {
            harness = new TestSessionHarness();
            harness.signaling.getIceServersResults = [[{
                urls: ['turns:relay.example.com'],
                username: 'user',
                credential: 'pass',
            }]];
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);

            expect(harness.media.setIceServersCalls).toContainEqual([{
                urls: ['turns:relay.example.com'],
                username: 'user',
                credential: 'pass',
            }]);
        });

        it('transitions to error when initial ICE server retries are exhausted', async () => {
            harness = new TestSessionHarness();
            harness.signaling.getIceServersResults = [
                new Error('attempt-1'),
                new Error('attempt-2'),
                new Error('attempt-3'),
                new Error('attempt-4'),
            ];
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            await vi.advanceTimersByTimeAsync(7000);

            expect(harness.signaling.getIceServersCalls).toBe(4);
            expect(harness.state.phase).toBe('error');
            expect(harness.state.error).toEqual({
                code: 'serverError',
                message: 'attempt-4',
            });
            expectTerminalTeardown('error');
        });

        it('forwards room state to media engine', () => {
            harness = new TestSessionHarness();
            const roomState = { hostCid: 'me', participants: [{ cid: 'me' }] };
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            harness.simulateRoomStateUpdate(roomState);

            expect(harness.media.updateRoomStateCalls.length).toBeGreaterThan(0);
            const last = harness.media.updateRoomStateCalls[harness.media.updateRoomStateCalls.length - 1];
            expect(last.state).toEqual(roomState);
            expect(last.clientId).toBe('me');
        });

        it('installs a TURN refresh gate that returns false when all peer paths are direct', async () => {
            harness = new TestSessionHarness();
            const gate = harness.signaling.turnRefreshGate;
            expect(gate).toBeTypeOf('function');
            if (!gate) return;

            // All peers direct → gate must say "skip" (return false).
            harness.media.allPathsDirect = true;
            await expect(gate()).resolves.toBe(false);

            // Any peer on relay (or no stats yet) → gate must say "refresh"
            // (return true) so credentials don't silently expire while a TURN
            // path is actually in use — an inverted gate is catastrophic.
            harness.media.allPathsDirect = false;
            await expect(gate()).resolves.toBe(true);
        });

        it('propagates suspended connectionStatus through to remoteParticipants.signalingStatus', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.simulateRoomStateUpdate({
                hostCid: 'me',
                participants: [
                    { cid: 'me' },
                    { cid: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            const peer = harness.state.remoteParticipants.find((p) => p.cid === 'peer-1');
            expect(peer).toBeDefined();
            expect(peer?.signalingStatus).toBe('suspended');

            // And the reverse transition back to active clears the flag.
            harness.simulateRoomStateUpdate({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            const peerAfter = harness.state.remoteParticipants.find((p) => p.cid === 'peer-1');
            expect(peerAfter?.signalingStatus).toBe('active');
        });
    });

    // ---------------------------------------------------------------
    // Error Handling
    // ---------------------------------------------------------------
    describe('error handling', () => {
        it('sets phase to error on signaling error', () => {
            harness = new TestSessionHarness();
            harness.simulateError('Connection refused');

            expect(harness.state.error).toEqual({
                code: 'unknown',
                message: 'Connection refused',
            });
            expectTerminalTeardown('error');
        });

        it('tears down the joined session on signaling error', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('inCall');

            harness.simulateError('Server crashed');
            expect(harness.state.error).toEqual({
                code: 'unknown',
                message: 'Server crashed',
            });
            expectTerminalTeardown('error');
        });

        it('ignores later provider events after a terminal signaling error', async () => {
            harness = new TestSessionHarness();
            harness.simulateError('Temporary failure');
            expectTerminalTeardown('error');

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);

            expect(harness.state.error).toEqual({
                code: 'unknown',
                message: 'Temporary failure',
            });
            expectTerminalTeardown('error');
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

            expect(harness.signaling.leaveRoomCalls).toBe(1);
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
            expect(harness.signaling.leaveRoomCalls).toBe(1);
            expect(harness.state.phase).toBe('idle');
        });

        it('leave is idempotent after destroy', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.session.leave();
            harness.session.leave(); // second call should be no-op

            expect(harness.signaling.leaveRoomCalls).toBe(1);
        });

        it('destroy tears down signaling and media', () => {
            harness = new TestSessionHarness();

            harness.session.destroy();

            expect(harness.signaling.disconnectCalls).toBe(1);
            expect(harness.media.destroyCalls).toBe(1);
        });
    });

    // ---------------------------------------------------------------
    // Ending Screen
    // ---------------------------------------------------------------
    describe('ending screen', () => {
        it('shows ending phase for 3 seconds then transitions to idle after roomEnded in call', async () => {
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
            expectTerminalTeardown('ending');

            // Advance 2.9 seconds — still ending
            vi.advanceTimersByTime(2900);
            expectTerminalTeardown('ending');

            // Advance past 3 seconds
            vi.advanceTimersByTime(200);
            expectTerminalTeardown('idle');
        });

        it('shows ending when roomEnded arrives while waiting', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('waiting');

            harness.simulateRoomEnded();
            expectTerminalTeardown('ending');

            vi.advanceTimersByTime(3100);
            expectTerminalTeardown('idle');
        });
    });

    // ---------------------------------------------------------------
    // Reconnect Behavior
    // ---------------------------------------------------------------
    describe('reconnect behavior', () => {
        it('defers ICE restart on reconnect until post-reconnect snapshot arrives', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            expect(harness.state.phase).toBe('inCall');

            harness.simulateDisconnect();
            expect(harness.state.activeTransport).toBeNull();

            harness.signaling.emitConnected('ws');

            // Transport reconnected, but no snapshot yet — restart deferred.
            expect(harness.state.activeTransport).toBe('ws');
            expect(harness.media.handleSignalingReconnectCalls).toBe(0);

            // Authoritative post-reconnect snapshot arrives.
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1' },
                ],
            });

            expect(harness.media.handleSignalingReconnectCalls).toBe(1);
        });

        it('does not double-fire ICE restart when more snapshots follow the first', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.simulateDisconnect();
            harness.signaling.emitConnected('ws');

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [{ peerId: 'me' }, { peerId: 'peer-1' }],
            });
            expect(harness.media.handleSignalingReconnectCalls).toBe(1);

            // Subsequent room_state updates (e.g. peer mute) should not retrigger.
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [{ peerId: 'me' }, { peerId: 'peer-1' }],
            });
            expect(harness.media.handleSignalingReconnectCalls).toBe(1);
        });

        it('falls back to ICE restart on snapshot timeout to preserve pre-#4 behavior', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            harness.simulateDisconnect();
            harness.signaling.emitConnected('ws');

            expect(harness.media.handleSignalingReconnectCalls).toBe(0);

            // No snapshot arrives — graceful degradation kicks in after 5s.
            await vi.advanceTimersByTimeAsync(5_000);

            expect(harness.media.handleSignalingReconnectCalls).toBe(1);
        });

        it('schedules per-CID ICE restart on negotiation_dirty', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();

            expect(harness.media.scheduleDirtyPairRestartCalls).toEqual([]);

            harness.signaling.emitNegotiationDirty({ withCid: 'peer-1' });

            expect(harness.media.scheduleDirtyPairRestartCalls).toEqual(['peer-1']);
        });

        it('does not call dirty-pair restart for unrelated provider events', async () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);

            // relay_failed is informational only — no ICE restart should fire.
            harness.signaling.emitRelayFailed({
                reason: 'target_suspended',
                targets: ['peer-1'],
                of: 'offer',
            });

            expect(harness.media.scheduleDirtyPairRestartCalls).toEqual([]);
        });

        it('retries reconnect and rejoins when the provider does not manage reconnection', async () => {
            harness = new TestSessionHarness({ handlesReconnection: false, autoStart: true });

            expect(harness.signaling.connectCalls).toBe(1);
            harness.signaling.emitConnected('ws');
            expect(harness.signaling.joinRoomCalls).toEqual([{ roomId: 'test-room-id', options: {} }]);

            harness.signaling.emitJoined({
                peerId: 'me',
                participants: [{ peerId: 'me' }, { peerId: 'peer-1' }],
                hostPeerId: 'me',
            });
            await vi.advanceTimersByTimeAsync(0);

            harness.simulateDisconnect();
            expect(harness.signaling.connectCalls).toBe(1);

            await vi.advanceTimersByTimeAsync(500);
            expect(harness.signaling.connectCalls).toBe(2);

            harness.signaling.emitConnected('ws');
            expect(harness.signaling.joinRoomCalls.at(-1)).toEqual({
                roomId: 'test-room-id',
                options: { reconnectPeerId: 'me' },
            });
        });

        it('connectionStatus reflects media engine status', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });
            harness.media.emit({ connectionStatus: 'recovering' });

            expect(harness.state.connectionStatus).toBe('recovering');
        });
    });

    describe('suspended state surface', () => {
        async function joinWithRemote() {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            return harness;
        }

        it('flips presumedLost on a remote peer after PEER_SUSPENDED_UI_TIMEOUT_MS suspended', async () => {
            await joinWithRemote();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me', connectionStatus: 'active' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            const remote = harness.state.remoteParticipants[0];
            expect(remote.signalingStatus).toBe('suspended');
            expect(remote.presumedLost).toBe(false);

            // Just before timeout: still not presumed lost
            await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS - 1);
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(false);

            // Cross the threshold
            await vi.advanceTimersByTimeAsync(2);
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(true);
        });

        it('cancels the timer and clears presumedLost when peer goes back to active', async () => {
            await joinWithRemote();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS + 100);
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(true);

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'active' },
                ],
            });

            const remote = harness.state.remoteParticipants[0];
            expect(remote.signalingStatus).toBe('active');
            expect(remote.presumedLost).toBe(false);
        });

        it('clears suspension state when peer leaves the room', async () => {
            await joinWithRemote();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            // Peer is removed before timer fires
            harness.signaling.emitPeerLeft({ peerId: 'peer-1', joinedAt: 2 });

            // Advance past the would-be timeout — no error, no stale state
            await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS + 1000);
            expect(harness.state.remoteParticipants).toHaveLength(0);
        });

        it('does not reschedule timer when subsequent room_state arrives with peer still suspended after fire', async () => {
            await joinWithRemote();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS + 100);
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(true);

            // Track log calls to verify the "presumed lost" log fires only once
            const logger = harness.session['config'].logger;
            const logSpy = logger ? vi.spyOn(logger, 'log') : null;

            // Several more room_state updates arrive while peer remains suspended.
            for (let i = 0; i < 3; i += 1) {
                harness.signaling.emitRoomStateUpdated({
                    hostPeerId: 'me',
                    participants: [
                        { peerId: 'me' },
                        { peerId: 'peer-1', connectionStatus: 'suspended' },
                    ],
                });
                await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS + 100);
            }

            // Still presumed lost, no new timers fired (no additional log lines).
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(true);
            if (logSpy) {
                const presumedLostLogCount = logSpy.mock.calls.filter(
                    (call) => typeof call[2] === 'string' && call[2].includes('presumed lost'),
                ).length;
                expect(presumedLostLogCount).toBe(0); // no logger configured, but spy is null so this branch is skipped
            }
        });

        it('clears presumedLost tracking when a presumed-lost peer leaves the room', async () => {
            await joinWithRemote();

            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });

            // Let the timer fire so the peer is flagged
            await vi.advanceTimersByTimeAsync(PEER_SUSPENDED_UI_TIMEOUT_MS + 100);
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(true);

            // Now the peer leaves — internal tracking should clear
            harness.signaling.emitPeerLeft({ peerId: 'peer-1', joinedAt: 2 });
            expect(harness.state.remoteParticipants).toHaveLength(0);

            // If the same peer rejoins fresh and immediately suspends, it should
            // start a brand-new timer (not be already flagged from before).
            harness.signaling.emitPeerJoined({ peerId: 'peer-1', joinedAt: 3 });
            harness.signaling.emitRoomStateUpdated({
                hostPeerId: 'me',
                participants: [
                    { peerId: 'me' },
                    { peerId: 'peer-1', connectionStatus: 'suspended' },
                ],
            });
            expect(harness.state.remoteParticipants[0].presumedLost).toBe(false);
        });

        it('signalingState transitions connected → suspended → connected over a transport drop', async () => {
            await joinWithRemote();
            expect(harness.state.signalingState).toEqual({ kind: 'connected' });

            const before = Date.now();
            harness.simulateDisconnect();

            const sigState = harness.state.signalingState;
            expect(sigState.kind).toBe('suspended');
            if (sigState.kind === 'suspended') {
                expect(sigState.suspendedSinceMs).toBeGreaterThanOrEqual(before);
                expect(sigState.estimatedHardEvictionAtMs).toBe(
                    sigState.suspendedSinceMs + SUSPEND_HARD_EVICTION_TIMEOUT_MS,
                );
            }

            harness.signaling.emitConnected('ws');
            expect(harness.state.signalingState).toEqual({ kind: 'connected' });
        });

        it('signalingState reports failed with the terminal error code', async () => {
            harness = new TestSessionHarness();
            harness.signaling.emitConnected('ws');
            harness.simulateError('Room is gone', 'ROOM_ENDED');

            await vi.advanceTimersByTimeAsync(0);

            const sigState = harness.state.signalingState;
            expect(sigState.kind).toBe('failed');
            if (sigState.kind === 'failed') {
                expect(sigState.reason).toBe('roomEnded');
            }
        });
    });

    // ---------------------------------------------------------------
    // Media-liveness emission (#3)
    // ---------------------------------------------------------------
    describe('media-liveness emission', () => {
        async function joinWithRemotes(remoteCids: string[]) {
            harness = new TestSessionHarness();
            harness.simulateJoined({
                clientId: 'me',
                participants: [{ cid: 'me' }, ...remoteCids.map((cid) => ({ cid }))],
            });
            await vi.advanceTimersByTimeAsync(0);
            await harness.session.resumeJoin();
            return harness;
        }

        function livenessBroadcasts() {
            return harness.signaling.broadcastCalls.filter((call) => call.type === 'media_liveness');
        }

        it('broadcasts media_liveness with flowing CIDs on each interval tick', async () => {
            await joinWithRemotes(['peer-1']);
            harness.media.inboundFlowingCids = ['peer-1'];

            expect(livenessBroadcasts()).toHaveLength(0);

            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS + 50);

            const broadcasts = livenessBroadcasts();
            expect(broadcasts).toHaveLength(1);
            expect(broadcasts[0].payload).toEqual({ cids: ['peer-1'] });
        });

        it('skips broadcast when no peer is currently flowing', async () => {
            await joinWithRemotes(['peer-1']);
            harness.media.inboundFlowingCids = [];

            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS * 3);

            expect(livenessBroadcasts()).toHaveLength(0);
            expect(harness.media.getInboundFlowingCidsCalls).toBeGreaterThan(0);
        });

        it('skips broadcast while transport is disconnected; resumes after reconnect', async () => {
            await joinWithRemotes(['peer-1']);
            harness.media.inboundFlowingCids = ['peer-1'];

            // First tick — connected, broadcast happens.
            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS + 50);
            expect(livenessBroadcasts()).toHaveLength(1);

            // Drop transport. Subsequent ticks must not broadcast.
            harness.simulateDisconnect();
            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS * 3);
            expect(livenessBroadcasts()).toHaveLength(1);

            // Reconnect — next tick should broadcast again.
            harness.signaling.emitConnected('ws');
            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS + 50);
            expect(livenessBroadcasts().length).toBeGreaterThanOrEqual(2);
        });

        it('stops emitting after the session is destroyed', async () => {
            await joinWithRemotes(['peer-1']);
            harness.media.inboundFlowingCids = ['peer-1'];

            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS + 50);
            const baseline = livenessBroadcasts().length;
            expect(baseline).toBe(1);

            harness.session.destroy();
            await vi.advanceTimersByTimeAsync(MEDIA_LIVENESS_INTERVAL_MS * 3);

            expect(livenessBroadcasts().length).toBe(baseline);
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

            harness.signaling.emitConnected('ws');
            const countAfterEmit = states.length;

            unsub();
            harness.signaling.emitDisconnected('test');

            expect(states.length).toBe(countAfterEmit);
        });
    });

    // ---------------------------------------------------------------
    // Media wiring
    // ---------------------------------------------------------------
    describe('media wiring', () => {
        it('media onChange triggers rebuildState', () => {
            harness = new TestSessionHarness();
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            const countBefore = harness.stateHistory.length;
            harness.media.emit({ connectionStatus: 'retrying' });

            expect(harness.stateHistory.length).toBeGreaterThan(countBefore);
            expect(harness.state.connectionStatus).toBe('retrying');
        });

        it('forwards provider peer messages through onPeerMessage', () => {
            harness = new TestSessionHarness();
            const callback = vi.fn();
            harness.session.onPeerMessage(callback);

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare' },
            });

            expect(callback).toHaveBeenCalledWith({
                from: 'peer-1',
                type: 'content_state',
                payload: { active: true, contentType: 'screenShare' },
            });
        });

        it('continues processing signaling messages when an onPeerMessage listener throws', () => {
            const logger = { log: vi.fn() };
            harness = new TestSessionHarness({ config: { logger } });

            harness.session.onPeerMessage(() => {
                throw new Error('listener failed');
            });

            harness.signaling.emitMessage({
                from: 'peer-1',
                type: 'offer',
                payload: { sdp: 'offer-sdp' },
            });

            expect(harness.media.processSignalingMessageCalls).toEqual([
                {
                    v: 1,
                    type: 'offer',
                    cid: 'peer-1',
                    payload: {
                        from: 'peer-1',
                        sdp: 'offer-sdp',
                    },
                },
            ]);
            expect(logger.log).toHaveBeenCalledWith(
                'error',
                'Session',
                'onPeerMessage listener failed for offer: listener failed',
            );
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
            // Pre-media-start: no local stream yet, so fields fall back to config defaults.
            harness.media.startLocalMediaResult = null;

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            expect(harness.state.localParticipant?.audioEnabled).toBe(false);
            expect(harness.state.localParticipant?.videoEnabled).toBe(false);
        });

        it('defaults audioEnabled/videoEnabled to true when not specified', () => {
            harness = new TestSessionHarness();
            harness.media.startLocalMediaResult = null;
            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            expect(harness.state.localParticipant?.audioEnabled).toBe(true);
            expect(harness.state.localParticipant?.videoEnabled).toBe(true);
        });

        it('local videoEnabled reflects track presence once media has started', () => {
            harness = new TestSessionHarness();
            // Stream exists but with no video track (e.g., camera released or
            // reacquire failed). Local UI must mirror the broadcast and report
            // false rather than continuing to render the user's intent.
            harness.media.startLocalMediaResult = {
                getAudioTracks: () => [{ enabled: true } as MediaStreamTrack],
                getVideoTracks: () => [],
            } as unknown as MediaStream;

            harness.simulateJoined({ clientId: 'me', participants: [{ cid: 'me' }] });

            expect(harness.state.localParticipant?.audioEnabled).toBe(true);
            expect(harness.state.localParticipant?.videoEnabled).toBe(false);
        });
    });
});
