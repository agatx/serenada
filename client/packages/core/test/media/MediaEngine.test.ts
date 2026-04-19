import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaEngine } from '../../src/media/MediaEngine.js';
import { NON_HOST_FALLBACK_DELAY_MS, OFFER_TIMEOUT_MS } from '../../src/constants.js';

class FakeRtcPeerConnection {
    readonly initialConfiguration: RTCConfiguration;
    readonly configurationUpdates: RTCConfiguration[] = [];
    readonly addedIceCandidates: RTCIceCandidateInit[] = [];
    signalingState: RTCSignalingState = 'stable';
    remoteDescription: RTCSessionDescriptionInit | null = null;
    localDescription: RTCSessionDescriptionInit | null = null;
    createOfferCalls = 0;
    createAnswerCalls = 0;
    rollbackCalls = 0;
    ontrack: ((event: RTCTrackEvent) => void) | null = null;
    oniceconnectionstatechange: (() => void) | null = null;
    onconnectionstatechange: (() => void) | null = null;
    onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
    onnegotiationneeded: (() => void) | null = null;

    constructor(configuration: RTCConfiguration) {
        this.initialConfiguration = configuration;
    }

    addTrack(): void {}
    close(): void {}
    async createOffer(): Promise<RTCSessionDescriptionInit> {
        this.createOfferCalls += 1;
        return {
            type: 'offer',
            sdp: `fake-offer-sdp-${this.createOfferCalls}`,
        };
    }
    async createAnswer(): Promise<RTCSessionDescriptionInit> {
        this.createAnswerCalls += 1;
        return {
            type: 'answer',
            sdp: `fake-answer-sdp-${this.createAnswerCalls}`,
        };
    }
    async setLocalDescription(description: RTCSessionDescriptionInit): Promise<void> {
        if (description.type === 'rollback') {
            this.rollbackCalls += 1;
            this.localDescription = null;
            this.signalingState = 'stable';
            return;
        }

        this.localDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';
    }
    async setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void> {
        this.remoteDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
    }
    async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
        this.addedIceCandidates.push(candidate);
    }
    setConfiguration(configuration: RTCConfiguration): void {
        this.configurationUpdates.push(configuration);
    }
}

describe('MediaEngine', () => {
    const originalNavigator = globalThis.navigator;
    const originalDocument = globalThis.document;
    const originalWindow = (globalThis as Record<string, unknown>).window;
    const originalRtcPeerConnection = (globalThis as Record<string, unknown>).RTCPeerConnection;

    beforeEach(() => {
        Object.defineProperty(globalThis, 'navigator', {
            value: { mediaDevices: {} },
            configurable: true,
        });
        Object.defineProperty(globalThis, 'document', {
            value: { hidden: false, addEventListener() {}, removeEventListener() {} },
            configurable: true,
        });
        (globalThis as Record<string, unknown>).window = {
            addEventListener() {},
            removeEventListener() {},
            setTimeout: (...args: Parameters<typeof globalThis.setTimeout>) => globalThis.setTimeout(...args),
            clearTimeout: (...args: Parameters<typeof globalThis.clearTimeout>) => globalThis.clearTimeout(...args),
            setInterval: (...args: Parameters<typeof globalThis.setInterval>) => globalThis.setInterval(...args),
            clearInterval: (...args: Parameters<typeof globalThis.clearInterval>) => globalThis.clearInterval(...args),
        };
        (globalThis as Record<string, unknown>).RTCPeerConnection = FakeRtcPeerConnection;
    });

    afterEach(() => {
        vi.useRealTimers();
        Object.defineProperty(globalThis, 'navigator', {
            value: originalNavigator,
            configurable: true,
        });
        Object.defineProperty(globalThis, 'document', {
            value: originalDocument,
            configurable: true,
        });
        (globalThis as Record<string, unknown>).window = originalWindow;
        (globalThis as Record<string, unknown>).RTCPeerConnection = originalRtcPeerConnection;
    });

    it('applies refreshed ICE servers to existing and future peers', () => {
        const engine = new MediaEngine({}, () => {});

        engine.setIceServers([{ urls: 'turn:initial.example.com' }]);
        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }],
        }, 'zeta');

        const existingPeer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(existingPeer).toBeDefined();
        expect(existingPeer?.initialConfiguration.iceServers).toEqual([{ urls: ['turn:initial.example.com'] }]);

        engine.setIceServers([{ urls: 'turn:refreshed.example.com' }]);

        expect(existingPeer?.configurationUpdates.at(-1)?.iceServers).toEqual([{ urls: ['turn:refreshed.example.com'] }]);

        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }, { cid: 'beta' }],
        }, 'zeta');

        const futurePeer = engine.getPeerConnectionsMap().get('beta') as FakeRtcPeerConnection | undefined;
        expect(futurePeer).toBeDefined();
        expect(futurePeer?.initialConfiguration.iceServers).toEqual([{ urls: ['turn:refreshed.example.com'] }]);
    });

    it('falls back to the default STUN server when ICE servers are cleared', () => {
        const engine = new MediaEngine({}, () => {});

        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }],
        }, 'zeta');

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        expect(peer?.initialConfiguration.iceServers).toEqual([{ urls: 'stun:stun.l.google.com:19302' }]);

        engine.setIceServers([]);

        expect(peer?.configurationUpdates.at(-1)?.iceServers).toEqual([{ urls: 'stun:stun.l.google.com:19302' }]);
    });

    it('retries non-host fallback offers after the offer timeout elapses', async () => {
        vi.useFakeTimers();

        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }],
        }, 'zeta');

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        expect(sentMessages).toEqual([]);

        await vi.advanceTimersByTimeAsync(NON_HOST_FALLBACK_DELAY_MS);

        expect(peer?.createOfferCalls).toBe(1);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        expect(sentMessages.at(-1)).toMatchObject({
            type: 'offer',
            to: 'alpha',
            payload: { sdp: 'fake-offer-sdp-1' },
        });

        await vi.advanceTimersByTimeAsync(OFFER_TIMEOUT_MS);
        expect(peer?.rollbackCalls).toBe(1);

        await vi.advanceTimersByTimeAsync(NON_HOST_FALLBACK_DELAY_MS);

        expect(peer?.createOfferCalls).toBe(2);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
        expect(sentMessages.at(-1)).toMatchObject({
            type: 'offer',
            to: 'alpha',
            payload: { sdp: 'fake-offer-sdp-2' },
        });
    });

    it('uses direct string ordering for offer ownership', async () => {
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const localeCompareSpy = vi.spyOn(String.prototype, 'localeCompare').mockImplementation(() => {
            throw new Error('should not be called');
        });
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');

        await vi.waitFor(() => {
            expect(sentMessages).toContainEqual({
                type: 'offer',
                to: 'zeta',
                payload: { sdp: 'fake-offer-sdp-1' },
            });
        });

        localeCompareSpy.mockRestore();
    });
});
