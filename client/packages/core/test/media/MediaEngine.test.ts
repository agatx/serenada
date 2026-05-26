import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaEngine } from '../../src/media/MediaEngine.js';
import { OFFER_TIMEOUT_MS } from '../../src/constants.js';

interface SharedNegotiationScenario {
    id: string;
    localCid: string;
    remoteCid: string;
}

class FakeRtcPeerConnection {
    readonly initialConfiguration: RTCConfiguration;
    readonly configurationUpdates: RTCConfiguration[] = [];
    readonly addedIceCandidates: RTCIceCandidateInit[] = [];
    readonly setRemoteDescriptionCalls: RTCSessionDescriptionInit[] = [];
    readonly senders: FakeRtcRtpSender[] = [];
    readonly transceivers: FakeRtcRtpTransceiver[] = [];
    signalingState: RTCSignalingState = 'stable';
    remoteDescription: RTCSessionDescriptionInit | null = null;
    localDescription: RTCSessionDescriptionInit | null = null;
    createOfferCalls = 0;
    createAnswerCalls = 0;
    rollbackCalls = 0;
    failNextRemoteOffer = false;
    ontrack: ((event: RTCTrackEvent) => void) | null = null;
    oniceconnectionstatechange: (() => void) | null = null;
    onconnectionstatechange: (() => void) | null = null;
    onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
    onnegotiationneeded: (() => void) | null = null;
    onsignalingstatechange: (() => void) | null = null;

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
            this.onsignalingstatechange?.();
            return;
        }

        this.localDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';
        this.onsignalingstatechange?.();
    }
    async setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void> {
        this.setRemoteDescriptionCalls.push(description);
        if (description.type === 'offer' && this.failNextRemoteOffer) {
            this.failNextRemoteOffer = false;
            throw new Error('set remote offer failed');
        }
        this.remoteDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
        this.onsignalingstatechange?.();
    }
    async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
        this.addedIceCandidates.push(candidate);
    }
    setConfiguration(configuration: RTCConfiguration): void {
        this.configurationUpdates.push(configuration);
    }
}

class FakeRtcSessionDescription {
    type: RTCSdpType;
    sdp?: string;

    constructor(init: RTCSessionDescriptionInit) {
        this.type = init.type;
        this.sdp = init.sdp;
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

function readSharedNegotiationScenarios(): SharedNegotiationScenario[] {
    const candidates = [
        path.resolve(process.cwd(), 'test-fixtures/peer-negotiation-scenarios.json'),
        path.resolve(process.cwd(), '../test-fixtures/peer-negotiation-scenarios.json'),
        path.resolve(process.cwd(), '../../../test-fixtures/peer-negotiation-scenarios.json'),
        path.resolve(process.cwd(), '../../test-fixtures/peer-negotiation-scenarios.json'),
    ];
    const filePath = candidates.find(candidate => {
        try {
            readFileSync(candidate);
            return true;
        } catch {
            return false;
        }
    });
    if (!filePath) throw new Error('Missing shared peer negotiation scenarios');
    return JSON.parse(readFileSync(filePath, 'utf8')).scenarios as SharedNegotiationScenario[];
}

async function flushPromises(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
}

describe('MediaEngine', () => {
    const originalNavigator = globalThis.navigator;
    const originalDocument = globalThis.document;
    const originalWindow = (globalThis as Record<string, unknown>).window;
    const originalRtcPeerConnection = (globalThis as Record<string, unknown>).RTCPeerConnection;
    const originalRtcSessionDescription = (globalThis as Record<string, unknown>).RTCSessionDescription;
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
        (globalThis as Record<string, unknown>).RTCSessionDescription = FakeRtcSessionDescription;
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
        (globalThis as Record<string, unknown>).RTCSessionDescription = originalRtcSessionDescription;
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

    it('does not restore camera after stopping screen share that started from audio-only media', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream());
        const getDisplayMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: false, video: true }));
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    getDisplayMedia,
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
        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

        await engine.startScreenShare();
        expect(engine.localStream?.getVideoTracks()).toHaveLength(1);
        expect(peer?.senders.map(sender => sender.track?.kind)).toEqual(['audio', 'video']);

        await engine.stopScreenShare();

        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(getDisplayMedia).toHaveBeenCalledWith({ video: true, audio: false });
        expect(engine.localStream?.getVideoTracks()).toHaveLength(0);
        expect(peer?.senders.map(sender => sender.track?.kind)).toEqual(['audio', undefined]);
        expect(sentMessages).toContainEqual({
            type: 'content_state',
            payload: { active: false },
            to: undefined,
        });
    });

    it('restores camera after stopping screen share that started with camera video', async () => {
        const getUserMedia = vi.fn().mockImplementation(async (constraints: MediaStreamConstraints) => {
            if (constraints.video) {
                return createMediaStream({ audio: constraints.audio !== false, video: true });
            }
            return createMediaStream();
        });
        const getDisplayMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: false, video: true }));
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    getDisplayMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({}, () => {});

        await engine.startLocalMedia();
        await engine.startScreenShare();
        await engine.stopScreenShare();

        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: { facingMode: 'user' },
            audio: false,
        });
        expect(engine.localStream?.getVideoTracks()).toHaveLength(1);
        expect(engine.localStream?.getVideoTracks()[0]?.enabled).toBe(true);
    });

    it('restores camera-off video state after stopping screen share that started with disabled camera', async () => {
        const getUserMedia = vi.fn().mockImplementation(async (constraints: MediaStreamConstraints) => {
            if (constraints.video) {
                return createMediaStream({ audio: constraints.audio !== false, video: true });
            }
            return createMediaStream();
        });
        const getDisplayMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: false, video: true }));
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    getDisplayMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({}, () => {});

        await engine.startLocalMedia();
        const cameraTrack = engine.localStream?.getVideoTracks()[0];
        if (cameraTrack) {
            cameraTrack.enabled = false;
        }

        await engine.startScreenShare();
        await engine.stopScreenShare();

        expect(engine.localStream?.getVideoTracks()).toHaveLength(1);
        // Camera should remain disabled because screen share recorded
        // the previous video track's `enabled=false` and restored it.
        expect(engine.localStream?.getVideoTracks()[0]?.enabled).toBe(false);
    });

    it('startScreenShare is a no-op when there is no local stream', async () => {
        const getDisplayMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: false, video: true }));
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getDisplayMedia,
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({}, () => {});

        // No startLocalMedia → there is no localStream.
        await engine.startScreenShare();

        expect(getDisplayMedia).not.toHaveBeenCalled();
        expect(engine.isScreenSharing).toBe(false);
        expect(engine.localStream).toBeNull();
    });

    it('does not let the non-offerer create fallback offers', async () => {
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
        const baselineOffers = peer?.createOfferCalls ?? 0;

        await vi.advanceTimersByTimeAsync(OFFER_TIMEOUT_MS + 1);

        expect(peer?.createOfferCalls).toBe(baselineOffers);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(0);
    });

    it('restarts negotiation from the designated offerer when a peer reattaches', async () => {
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
        const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        const offerId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        expect(typeof offerId).toBe('string');
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId },
        });
        await flushPromises();
        const offersBefore = peer?.createOfferCalls ?? 0;

        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta', connectionStatus: 'suspended' }],
        }, 'alpha');
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta', connectionStatus: 'active' }],
        }, 'alpha');
        await new Promise(resolve => setTimeout(resolve, 0));
        await flushPromises();

        expect(peer?.createOfferCalls).toBeGreaterThan(offersBefore);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
    });

    it('renegotiates a four-party reattach from deterministic offer owners only', async () => {
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

        const peerIds = ['alpha', 'bravo', 'charlie', 'delta'];
        const engines = new Map<string, MediaEngine>();
        const sentMessages: Array<{ from: string; type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const roomState = (suspendedCid?: string) => ({
            hostCid: 'alpha',
            participants: peerIds.map(cid => ({
                cid,
                ...(cid === suspendedCid ? { connectionStatus: 'suspended' as const } : {}),
            })),
        });
        const offerMessages = () => sentMessages.filter(message => message.type === 'offer');
        const nonStablePeerStates = () => Array.from(engines.entries()).flatMap(([localCid, engine]) =>
            Array.from(engine.getPeerConnectionsMap().entries())
                .map(([remoteCid, pc]) => ({ localCid, remoteCid, state: (pc as FakeRtcPeerConnection).signalingState }))
                .filter(peer => peer.state !== 'stable')
        );
        const peerCounts = () => Array.from(engines.values()).map(engine => engine.getPeerConnectionsMap().size);

        for (const localCid of peerIds) {
            const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
                sentMessages.push({ from: localCid, type, payload, to });
                if (!to) return;
                engines.get(to)?.processSignalingMessage({
                    v: 1,
                    type,
                    payload: { ...payload, from: localCid },
                });
            });
            engines.set(localCid, engine);
        }

        for (const [localCid, engine] of engines) {
            engine.updateSignalingConnected(true);
            await engine.startLocalMedia();
            engine.updateRoomState(roomState(), localCid);
        }

        await vi.waitFor(() => {
            expect(offerMessages()).toHaveLength(6);
            expect(peerCounts()).toEqual([3, 3, 3, 3]);
            expect(nonStablePeerStates()).toEqual([]);
        });

        const baselineOfferCount = offerMessages().length;

        for (const [localCid, engine] of engines) {
            engine.updateRoomState(roomState('charlie'), localCid);
        }
        await new Promise(resolve => setTimeout(resolve, 0));
        await flushPromises();
        expect(offerMessages()).toHaveLength(baselineOfferCount);

        for (const [localCid, engine] of engines) {
            engine.updateRoomState(roomState(), localCid);
        }
        engines.get('charlie')?.handleSignalingReconnect();

        await vi.waitFor(() => {
            expect(offerMessages()).toHaveLength(baselineOfferCount + 3);
            expect(nonStablePeerStates()).toEqual([]);
        });

        const reconnectOfferRoutes = offerMessages()
            .slice(baselineOfferCount)
            .map(message => `${message.from}->${message.to}`);
        expect(new Set(reconnectOfferRoutes)).toEqual(new Set([
            'alpha->charlie',
            'bravo->charlie',
            'charlie->delta',
        ]));
        for (const message of offerMessages()) {
            expect(message.to).toBeDefined();
            expect(message.from < message.to!).toBe(true);
        }

        for (const engine of engines.values()) {
            engine.destroy();
        }
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
        };
        const iceSpy = vi.spyOn(internals, 'scheduleIceRestart');

        engine.scheduleDirtyPairRestart('zeta');

        expect(iceSpy).toHaveBeenCalledWith('zeta', 'negotiation-dirty', 0);

        iceSpy.mockRestore();
    });

    it('scheduleDirtyPairRestart is a no-op when local should not offer', () => {
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        // Local 'zeta' sorts after 'alpha', so 'alpha' (the remote) is the
        // offerer.
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');

        const offerCountBefore = sentMessages.filter((m) => m.type === 'offer').length;

        engine.scheduleDirtyPairRestart('alpha');

        expect(sentMessages.filter((m) => m.type === 'offer')).toHaveLength(offerCountBefore);
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
            expect(sentMessages).toContainEqual(expect.objectContaining({
                type: 'offer',
                to: 'zeta',
                payload: expect.objectContaining({ sdp: 'fake-offer-sdp-1' }),
            }));
        });

        localeCompareSpy.mockRestore();
    });

    it('runs every shared perfect negotiation scenario', async () => {
        vi.useFakeTimers();
        const handled = new Set<string>();

        for (const scenario of readSharedNegotiationScenarios()) {
            handled.add(scenario.id);
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
            const engine = new MediaEngine({ initialVideoEnabled: false }, (type, payload, to) => {
                sentMessages.push({ type, payload, to });
            });
            const signal = (type: string, payload: Record<string, unknown>) => {
                engine.processSignalingMessage({ v: 1, type, payload });
            };
            const updateTwoPartyRoom = () => {
                engine.updateRoomState({
                    hostCid: scenario.localCid,
                    participants: [{ cid: scenario.localCid }, { cid: scenario.remoteCid }],
                }, scenario.localCid);
            };
            const setup = async () => {
                engine.updateSignalingConnected(true);
                await engine.startLocalMedia();
                updateTwoPartyRoom();
                await flushPromises();
            };

            switch (scenario.id) {
                case 'impolite-offer-collision-ignores-offer-and-ice': {
                    await setup();
                    const peer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;
                    expect(peer?.signalingState).toBe('have-local-offer');

                    signal('offer', { from: scenario.remoteCid, sdp: 'colliding-offer', offerId: 'remote-offer-1' });
                    signal('ice', {
                        from: scenario.remoteCid,
                        offerId: 'remote-offer-1',
                        candidate: { candidate: 'candidate:ignored', sdpMid: '0', sdpMLineIndex: 0 },
                    });
                    await flushPromises();

                    expect(peer?.setRemoteDescriptionCalls.some(call => call.type === 'offer')).toBe(false);
                    expect(peer?.addedIceCandidates).toHaveLength(0);
                    expect(sentMessages.some(message => message.type === 'answer')).toBe(false);
                    break;
                }
                case 'polite-offer-collision-rolls-back-and-answers': {
                    await setup();
                    const peer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;
                    await peer?.setLocalDescription(await peer.createOffer());
                    await flushPromises();
                    expect(peer?.signalingState).toBe('have-local-offer');

                    signal('offer', { from: scenario.remoteCid, sdp: 'remote-offer', offerId: 'remote-offer-1' });
                    await flushPromises();

                    expect(peer?.rollbackCalls).toBe(1);
                    expect(peer?.setRemoteDescriptionCalls.at(-1)?.type).toBe('offer');
                    await vi.waitFor(() => {
                        expect(sentMessages.some(message =>
                            message.type === 'answer' &&
                            message.to === scenario.remoteCid &&
                            message.payload?.offerId === 'remote-offer-1'
                        )).toBe(true);
                    });
                    break;
                }
                case 'stale-answer-in-stable-is-dropped': {
                    await setup();
                    const peer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;
                    const offerId = sentMessages.find(message => message.type === 'offer')?.payload?.offerId;
                    expect(typeof offerId).toBe('string');
                    signal('answer', { from: scenario.remoteCid, sdp: 'remote-answer', offerId });
                    await flushPromises();
                    expect(peer?.signalingState).toBe('stable');
                    const answerApplies = peer?.setRemoteDescriptionCalls.filter(call => call.type === 'answer').length ?? 0;

                    signal('answer', { from: scenario.remoteCid, sdp: 'late-answer', offerId });
                    await flushPromises();

                    expect(peer?.setRemoteDescriptionCalls.filter(call => call.type === 'answer')).toHaveLength(answerApplies);
                    break;
                }
                case 'stale-answer-wrong-offer-id-is-dropped': {
                    await setup();
                    const peer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;

                    signal('answer', { from: scenario.remoteCid, sdp: 'wrong-answer', offerId: 'wrong-offer-id' });
                    await flushPromises();

                    expect(peer?.setRemoteDescriptionCalls.some(call => call.type === 'answer')).toBe(false);
                    expect(peer?.signalingState).toBe('have-local-offer');
                    break;
                }
                case 'early-ice-for-eventual-offer-is-buffered-and-flushed': {
                    await setup();
                    const peer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;

                    signal('ice', {
                        from: scenario.remoteCid,
                        offerId: 'remote-offer-1',
                        candidate: { candidate: 'candidate:future', sdpMid: '0', sdpMLineIndex: 0 },
                    });
                    await flushPromises();
                    expect(peer?.addedIceCandidates).toHaveLength(0);

                    signal('offer', { from: scenario.remoteCid, sdp: 'remote-offer', offerId: 'remote-offer-1' });
                    await flushPromises();

                    expect(peer?.addedIceCandidates).toHaveLength(1);
                    expect(peer?.addedIceCandidates[0].candidate).toBe('candidate:future');
                    break;
                }
                case 'departed-peer-signaling-is-ignored': {
                    await setup();
                    const answersBefore = sentMessages.filter(message => message.type === 'answer').length;
                    engine.updateRoomState({
                        hostCid: scenario.localCid,
                        participants: [{ cid: scenario.localCid }],
                    }, scenario.localCid);
                    await flushPromises();
                    expect(engine.getPeerConnectionsMap().has(scenario.remoteCid)).toBe(false);

                    signal('offer', { from: scenario.remoteCid, sdp: 'late-offer', offerId: 'late-offer-id' });
                    signal('answer', { from: scenario.remoteCid, sdp: 'late-answer', offerId: 'late-offer-id' });
                    signal('ice', {
                        from: scenario.remoteCid,
                        offerId: 'late-offer-id',
                        candidate: { candidate: 'candidate:late', sdpMid: '0', sdpMLineIndex: 0 },
                    });
                    await flushPromises();

                    expect(engine.getPeerConnectionsMap().has(scenario.remoteCid)).toBe(false);
                    expect(sentMessages.filter(message => message.type === 'answer')).toHaveLength(answersBefore);
                    break;
                }
                case 'self-signaling-is-ignored': {
                    await setup();
                    const answersBefore = sentMessages.filter(message => message.type === 'answer').length;
                    const peerCountBefore = engine.getPeerConnectionsMap().size;

                    signal('offer', { from: scenario.localCid, sdp: 'self-offer', offerId: 'self-offer-id' });
                    signal('answer', { from: scenario.localCid, sdp: 'self-answer', offerId: 'self-offer-id' });
                    signal('ice', {
                        from: scenario.localCid,
                        offerId: 'self-offer-id',
                        candidate: { candidate: 'candidate:self', sdpMid: '0', sdpMLineIndex: 0 },
                    });
                    await flushPromises();

                    expect(engine.getPeerConnectionsMap().has(scenario.localCid)).toBe(false);
                    expect(engine.getPeerConnectionsMap().size).toBe(peerCountBefore);
                    expect(sentMessages.filter(message => message.type === 'answer')).toHaveLength(answersBefore);
                    break;
                }
                case 'remote-offer-apply-failure-recreates-peer-and-answers': {
                    await setup();
                    const oldPeer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;
                    expect(oldPeer).toBeDefined();
                    oldPeer!.failNextRemoteOffer = true;

                    signal('ice', {
                        from: scenario.remoteCid,
                        offerId: 'remote-offer-1',
                        candidate: { candidate: 'candidate:recovered', sdpMid: '0', sdpMLineIndex: 0 },
                    });
                    await flushPromises();
                    signal('offer', { from: scenario.remoteCid, sdp: 'remote-offer', offerId: 'remote-offer-1' });
                    await flushPromises();

                    const newPeer = engine.getPeerConnectionsMap().get(scenario.remoteCid) as FakeRtcPeerConnection | undefined;
                    expect(newPeer).toBeDefined();
                    expect(newPeer).not.toBe(oldPeer);
                    expect(oldPeer?.setRemoteDescriptionCalls.at(-1)?.type).toBe('offer');
                    expect(newPeer?.setRemoteDescriptionCalls.at(-1)?.type).toBe('offer');
                    expect(newPeer?.addedIceCandidates).toHaveLength(1);
                    expect(newPeer?.addedIceCandidates[0].candidate).toBe('candidate:recovered');
                    await vi.waitFor(() => {
                        expect(sentMessages.some(message =>
                            message.type === 'answer' &&
                            message.to === scenario.remoteCid &&
                            message.payload?.offerId === 'remote-offer-1'
                        )).toBe(true);
                    });
                    break;
                }
                default:
                    throw new Error(`Unhandled shared negotiation scenario: ${scenario.id}`);
            }

            engine.destroy();
        }

        expect(handled).toEqual(new Set(readSharedNegotiationScenarios().map(scenario => scenario.id)));
    });
});
