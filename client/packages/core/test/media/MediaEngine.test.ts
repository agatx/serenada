import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaEngine } from '../../src/media/MediaEngine.js';
import { NON_HOST_FALLBACK_DELAY_MS, OFFER_TIMEOUT_MS } from '../../src/constants.js';

class FakeRtcPeerConnection {
    readonly initialConfiguration: RTCConfiguration;
    readonly configurationUpdates: RTCConfiguration[] = [];
    readonly addedIceCandidates: RTCIceCandidateInit[] = [];
    readonly senders: FakeRtcRtpSender[] = [];
    readonly transceivers: FakeRtcRtpTransceiver[] = [];
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

    addTrack(track: MediaStreamTrack): RTCRtpSender {
        const sender = new FakeRtcRtpSender(track);
        this.senders.push(sender);
        this.transceivers.push(new FakeRtcRtpTransceiver(track.kind as 'audio' | 'video', sender, 'sendrecv'));
        return sender as unknown as RTCRtpSender;
    }
    addTransceiver(kind: 'audio' | 'video', init?: RTCRtpTransceiverInit): RTCRtpTransceiver {
        const sender = new FakeRtcRtpSender(null);
        const transceiver = new FakeRtcRtpTransceiver(kind, sender, init?.direction ?? 'sendrecv');
        this.senders.push(sender);
        this.transceivers.push(transceiver);
        return transceiver as unknown as RTCRtpTransceiver;
    }
    close(): void {}
    getSenders(): RTCRtpSender[] { return this.senders as unknown as RTCRtpSender[]; }
    getTransceivers(): RTCRtpTransceiver[] { return this.transceivers as unknown as RTCRtpTransceiver[]; }
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

class FakeRtcRtpSender {
    readonly replaceTrackCalls: Array<MediaStreamTrack | null> = [];

    constructor(public track: MediaStreamTrack | null) {}

    async replaceTrack(track: MediaStreamTrack | null): Promise<void> {
        this.track = track;
        this.replaceTrackCalls.push(track);
    }
}

class FakeRtcRtpTransceiver {
    readonly receiver: RTCRtpReceiver;

    constructor(
        kind: 'audio' | 'video',
        public sender: FakeRtcRtpSender,
        public direction: RTCRtpTransceiverDirection,
    ) {
        this.receiver = {
            track: createMediaTrack(kind),
        } as RTCRtpReceiver;
    }
}

class FakeMediaStream {
    private tracks: MediaStreamTrack[];

    constructor(tracks: MediaStreamTrack[] = []) {
        this.tracks = [...tracks];
    }

    addTrack(track: MediaStreamTrack): void {
        this.tracks.push(track);
    }

    getAudioTracks(): MediaStreamTrack[] {
        return this.tracks.filter(track => track.kind === 'audio');
    }

    getVideoTracks(): MediaStreamTrack[] {
        return this.tracks.filter(track => track.kind === 'video');
    }

    getTracks(): MediaStreamTrack[] {
        return [...this.tracks];
    }
}

let trackId = 0;

function createMediaTrack(kind: 'audio' | 'video'): MediaStreamTrack {
    trackId += 1;
    return {
        id: `${kind}-${trackId}`,
        kind,
        enabled: true,
        muted: false,
        readyState: 'live',
        stop() {},
    } as MediaStreamTrack;
}

function createMediaStream(options: { audio?: boolean; video?: boolean } = { audio: true }): MediaStream {
    const tracks: MediaStreamTrack[] = [];
    if (options.audio !== false) tracks.push(createMediaTrack('audio'));
    if (options.video) tracks.push(createMediaTrack('video'));
    return new FakeMediaStream(tracks) as unknown as MediaStream;
}

describe('MediaEngine', () => {
    const originalNavigator = globalThis.navigator;
    const originalDocument = globalThis.document;
    const originalWindow = (globalThis as Record<string, unknown>).window;
    const originalRtcPeerConnection = (globalThis as Record<string, unknown>).RTCPeerConnection;
    const originalMediaStream = (globalThis as Record<string, unknown>).MediaStream;

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
        (globalThis as Record<string, unknown>).MediaStream = FakeMediaStream;
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
        (globalThis as Record<string, unknown>).MediaStream = originalMediaStream;
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

    it('starts with audio-only media when initial video is disabled', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream());
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});

        await engine.startLocalMedia();

        expect(getUserMedia).toHaveBeenCalledWith({
            video: false,
            audio: expect.objectContaining({
                echoCancellation: { ideal: true },
            }),
        });
    });

    it('uses the reserved video transceiver when video is enabled after an audio-only start', async () => {
        const getUserMedia = vi.fn().mockImplementation(async (constraints: MediaStreamConstraints) => {
            if (constraints.video) {
                return createMediaStream({ audio: false, video: true });
            }
            return createMediaStream();
        });
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await engine.startLocalMedia();

        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });
        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(peer?.senders.map(sender => sender.track?.kind)).toEqual(['audio', undefined]);
        expect(peer?.transceivers.map(transceiver => `${transceiver.receiver.track.kind}:${transceiver.direction}`)).toEqual(['audio:sendrecv', 'video:sendrecv']);
        if (peer) {
            peer.remoteDescription = { type: 'answer', sdp: 'fake-answer-sdp' };
            peer.signalingState = 'stable';
        }

        await engine.reacquireVideoTrack();

        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: { facingMode: 'user' },
            audio: false,
        });
        expect(peer?.senders.map(sender => sender.track?.kind)).toEqual(['audio', 'video']);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
    });

    it('retries non-host fallback offers after the offer timeout elapses', async () => {
        vi.useFakeTimers();

        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
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

    it('scheduleDirtyPairRestart is a no-op for an unknown CID', () => {
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');

        const offerCountBefore = sentMessages.filter((m) => m.type === 'offer').length;

        // Unknown CID — should not throw and should not produce any offers.
        engine.scheduleDirtyPairRestart('stranger');

        const offerCountAfter = sentMessages.filter((m) => m.type === 'offer').length;
        expect(offerCountAfter).toBe(offerCountBefore);
        expect(engine.getPeerConnectionsMap().has('stranger')).toBe(false);
    });

    it('scheduleDirtyPairRestart dispatches to scheduleIceRestart when local should offer', () => {
        const engine = new MediaEngine({}, () => {});

        engine.updateSignalingConnected(true);
        // Local 'alpha' (host) sorts before 'zeta', so local should offer.
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');

        // Spy on the private routing methods to verify dispatch without
        // fighting the FakeRtcPeerConnection's signaling-state guards
        // (the actual ICE-restart machinery is exercised by existing tests).
        const internals = engine as unknown as {
            scheduleIceRestart: (cid: string, reason: string, delay: number) => void;
            scheduleNonHostFallback: (cid: string) => void;
        };
        const iceSpy = vi.spyOn(internals, 'scheduleIceRestart');
        const fallbackSpy = vi.spyOn(internals, 'scheduleNonHostFallback');

        engine.scheduleDirtyPairRestart('zeta');

        expect(iceSpy).toHaveBeenCalledWith('zeta', 'negotiation-dirty', 0);
        expect(fallbackSpy).not.toHaveBeenCalled();

        iceSpy.mockRestore();
        fallbackSpy.mockRestore();
    });

    it('scheduleDirtyPairRestart dispatches to non-host fallback when local should not offer', () => {
        const engine = new MediaEngine({}, () => {});

        engine.updateSignalingConnected(true);
        // Local 'zeta' sorts after 'alpha', so 'alpha' (the remote) is the
        // offerer; local takes the non-host fallback path.
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');

        const internals = engine as unknown as {
            scheduleIceRestart: (cid: string, reason: string, delay: number) => void;
            scheduleNonHostFallback: (cid: string) => void;
        };
        const iceSpy = vi.spyOn(internals, 'scheduleIceRestart');
        const fallbackSpy = vi.spyOn(internals, 'scheduleNonHostFallback');

        engine.scheduleDirtyPairRestart('alpha');

        expect(fallbackSpy).toHaveBeenCalledWith('alpha');
        expect(iceSpy).not.toHaveBeenCalled();

        iceSpy.mockRestore();
        fallbackSpy.mockRestore();
    });

    it('uses direct string ordering for offer ownership', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream());
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const localeCompareSpy = vi.spyOn(String.prototype, 'localeCompare').mockImplementation(() => {
            throw new Error('should not be called');
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
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
