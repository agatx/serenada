import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { JOIN_HARD_TIMEOUT_MS } from '../src/constants.js';
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

            harness.signaling.emitConnected('ws');

            expect(harness.state.phase).toBe('inCall');
            expect(harness.state.activeTransport).toBe('ws');
            expect(harness.media.handleSignalingReconnectCalls).toBe(1);
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
