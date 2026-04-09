import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { SerenadaCore } from '../src/SerenadaCore.js';
import { SerenadaServerProvider } from '../src/SerenadaServerProvider.js';
import type { SerenadaConfig } from '../src/types.js';
import { FakeSignalingProvider } from './helpers/FakeSignalingProvider.js';

// Provide a comprehensive window shim: SerenadaSession creates MediaEngine
// which uses window.addEventListener, window.removeEventListener, document.addEventListener,
// document.removeEventListener, and the timer functions.
if (typeof globalThis.window === 'undefined') {
    const noop = () => {};
    const fakeLocation = {
        host: '',
        protocol: 'https:',
        hostname: '',
        href: '',
    };
    const handler: ProxyHandler<Record<string, unknown>> = {
        get(_target, prop) {
            if (prop === 'setTimeout') return globalThis.setTimeout.bind(globalThis);
            if (prop === 'clearTimeout') return globalThis.clearTimeout.bind(globalThis);
            if (prop === 'setInterval') return globalThis.setInterval.bind(globalThis);
            if (prop === 'clearInterval') return globalThis.clearInterval.bind(globalThis);
            if (prop === 'location') return fakeLocation;
            if (prop === 'addEventListener') return noop;
            if (prop === 'removeEventListener') return noop;
            return undefined;
        },
    };
    (globalThis as Record<string, unknown>).window = new Proxy({}, handler);
}

if (typeof globalThis.document === 'undefined') {
    const noop = () => {};
    (globalThis as Record<string, unknown>).document = {
        addEventListener: noop,
        removeEventListener: noop,
        visibilityState: 'visible',
    };
}

if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

const testConfig: SerenadaConfig = { serverHost: 'serenada.app' };

describe('SerenadaCore', () => {
    let originalRTC: unknown;

    beforeEach(() => {
        originalRTC = (globalThis as Record<string, unknown>).RTCPeerConnection;
        vi.useFakeTimers();
    });

    afterEach(() => {
        (globalThis as Record<string, unknown>).RTCPeerConnection = originalRTC;
        vi.useRealTimers();
        vi.restoreAllMocks();
    });

    describe('isSupported', () => {
        it('returns true when RTCPeerConnection is defined', () => {
            (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
            expect(SerenadaCore.isSupported()).toBe(true);
        });

        it('returns false when RTCPeerConnection is undefined', () => {
            delete (globalThis as Record<string, unknown>).RTCPeerConnection;
            expect(SerenadaCore.isSupported()).toBe(false);
        });
    });

    describe('join(url)', () => {
        beforeEach(() => {
            (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        });

        it('extracts room ID from /call/<id> path', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ABC123');
            expect(session.state.roomId).toBe('ABC123');
            session.destroy();
        });

        it('falls back to last path segment when /call/ is absent', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/room/XYZ789');
            expect(session.state.roomId).toBe('XYZ789');
            session.destroy();
        });

        it('returns raw string for invalid URL', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('not-a-url');
            expect(session.state.roomId).toBe('not-a-url');
            session.destroy();
        });

        it('stores the original URL as roomUrl', () => {
            const core = new SerenadaCore(testConfig);
            const url = 'https://serenada.app/call/ROOM1';
            const session = core.join(url);
            expect(session.state.roomUrl).toBe(url);
            session.destroy();
        });

        it('passes displayName to provider joins when joining by URL', () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });
            const session = core.join('https://serenada.app/call/ROOM1', { displayName: 'Alice' });

            provider.emitConnected();

            expect(provider.joinRoomCalls).toEqual([
                {
                    roomId: 'ROOM1',
                    options: { displayName: 'Alice' },
                },
            ]);
            session.destroy();
        });

    });

    describe('join({ roomId })', () => {
        beforeEach(() => {
            (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        });

        it('builds room URL from serverHost and roomId', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join({ roomId: 'MY_ROOM' });
            expect(session.state.roomId).toBe('MY_ROOM');
            expect(session.state.roomUrl).toBe('https://serenada.app/call/MY_ROOM');
            session.destroy();
        });

        it('passes displayName to provider joins when joining by roomId', () => {
            const provider = new FakeSignalingProvider();
            const core = new SerenadaCore({ signalingProvider: provider });
            const session = core.join({ roomId: 'MY_ROOM', displayName: 'Alice' });

            provider.emitConnected();

            expect(provider.joinRoomCalls).toEqual([
                {
                    roomId: 'MY_ROOM',
                    options: { displayName: 'Alice' },
                },
            ]);
            session.destroy();
        });
    });

    describe('join when WebRTC unavailable', () => {
        beforeEach(() => {
            delete (globalThis as Record<string, unknown>).RTCPeerConnection;
        });

        it('returns an unsupported session stub', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ROOM1');

            expect(session.state.phase).toBe('error');
            expect(session.state.error?.code).toBe('webrtcUnavailable');
        });

        it('stub methods are no-ops and do not throw', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ROOM1');
            session.leave();
            session.end();
            session.onPeerMessage(() => {});
            session.toggleAudio();
            session.toggleVideo();
            session.cancelJoin();
            session.destroy();
            session.setAudioEnabled(true);
            session.setVideoEnabled(false);
        });

        it('stub properties return expected defaults', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ROOM1');

            expect(session.localStream).toBeNull();
            expect(session.remoteStreams.size).toBe(0);
            expect(session.callStats).toBeNull();
            expect(session.hasMultipleCameras).toBe(false);
            expect(session.canScreenShare).toBe(false);
            expect(session.isSignalingConnected).toBe(false);
        });

        it('subscribe returns a no-op unsubscribe', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ROOM1');
            const cb = vi.fn();
            const unsubscribe = session.subscribe(cb);
            expect(typeof unsubscribe).toBe('function');
            unsubscribe(); // should not throw
            expect(cb).not.toHaveBeenCalled();
        });

        it('onPeerMessage returns a no-op unsubscribe', () => {
            const core = new SerenadaCore(testConfig);
            const session = core.join('https://serenada.app/call/ROOM1');
            const cb = vi.fn();
            const unsubscribe = session.onPeerMessage(cb);
            expect(typeof unsubscribe).toBe('function');
            unsubscribe();
            expect(cb).not.toHaveBeenCalled();
        });
    });

    describe('createRoom', () => {
        it('creates a room and returns url and roomId', async () => {
            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                json: async () => ({ roomId: 'NEW_ROOM_ID' }),
            });
            vi.stubGlobal('fetch', mockFetch);

            const core = new SerenadaCore(testConfig);
            const result = await core.createRoom();

            expect(result.roomId).toBe('NEW_ROOM_ID');
            expect(result.url).toBe('https://serenada.app/call/NEW_ROOM_ID');
            expect(result).not.toHaveProperty('session');
        });
    });

    describe('config validation', () => {
        it('throws when both serverHost and signalingProvider are provided', () => {
            expect(() => new SerenadaCore({
                serverHost: 'serenada.app',
                signalingProvider: new FakeSignalingProvider(),
            })).toThrow('Provide exactly one of serverHost or signalingProvider');
        });

        it('throws when neither serverHost nor signalingProvider is provided', () => {
            expect(() => new SerenadaCore({})).toThrow('Provide exactly one of serverHost or signalingProvider');
        });

        it('throws when the signalingProvider version is unsupported', () => {
            const provider = new FakeSignalingProvider();
            Object.defineProperty(provider, 'version', { value: 2 });
            expect(() => new SerenadaCore({ signalingProvider: provider })).toThrow('Unsupported signalingProvider version: 2');
        });
    });

    describe('provider mode', () => {
        beforeEach(() => {
            (globalThis as Record<string, unknown>).RTCPeerConnection = class {};
        });

        it('joins by roomId without building a server-backed room URL', () => {
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            const session = core.join({ roomId: 'ROOM42' });

            expect(session.state.roomId).toBe('ROOM42');
            expect(session.state.roomUrl).toBeNull();
            session.destroy();
        });

        it('rejects createRoom without serverHost', async () => {
            const core = new SerenadaCore({ signalingProvider: new FakeSignalingProvider() });
            await expect(core.createRoom()).rejects.toThrow('requires serverHost');
        });

        it('built-in server provider owns reconnect handling', () => {
            const provider = new SerenadaServerProvider({ serverHost: 'serenada.app' });
            expect(provider.capabilities.handlesReconnection).toBe(true);
            provider.disconnect();
        });
    });
});
