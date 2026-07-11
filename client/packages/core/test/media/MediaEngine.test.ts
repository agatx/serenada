import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaEngine } from '../../src/media/MediaEngine.js';
import { ICE_RESTART_COOLDOWN_MS, OFFER_TIMEOUT_MS, OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS, OUTBOUND_MEDIA_STALL_SAMPLES } from '../../src/constants.js';

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
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    remoteDescription: RTCSessionDescriptionInit | null = null;
    localDescription: RTCSessionDescriptionInit | null = null;
    statsReports: RTCStatsReport[] = [];
    createOfferCalls = 0;
    createAnswerCalls = 0;
    getStatsCalls = 0;
    rollbackCalls = 0;
    failNextRemoteOffer = false;
    failNextRemoteAnswer = false;
    closed = false;
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
    close(): void {
        this.closed = true;
        this.connectionState = 'closed';
        this.iceConnectionState = 'closed';
        this.signalingState = 'closed';
    }
    getSenders(): RTCRtpSender[] { return this.senders as unknown as RTCRtpSender[]; }
    getReceivers(): RTCRtpReceiver[] {
        return this.transceivers.map(t => t.receiver);
    }
    getTransceivers(): RTCRtpTransceiver[] { return this.transceivers as unknown as RTCRtpTransceiver[]; }
    async getStats(): Promise<RTCStatsReport> {
        this.getStatsCalls += 1;
        return this.statsReports.shift() ?? this.statsReports.at(-1) ?? new Map<string, RTCStats>() as RTCStatsReport;
    }
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

        if (description.type === 'offer') {
            this.ensureLocalOfferTransceiver('audio', '0');
            this.ensureLocalOfferTransceiver('video', '1');
        }
        this.localDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';
        if (description.type === 'answer') {
            this.finalizeNegotiatedDirections();
        }
        this.onsignalingstatechange?.();
    }
    async setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void> {
        this.setRemoteDescriptionCalls.push(description);
        if (description.type === 'offer' && this.failNextRemoteOffer) {
            this.failNextRemoteOffer = false;
            throw new Error('set remote offer failed');
        }
        if (description.type === 'answer' && this.failNextRemoteAnswer) {
            this.failNextRemoteAnswer = false;
            throw new Error('set remote answer failed');
        }
        if (description.type === 'offer') {
            this.ensureRemoteOfferTransceiver('audio', '0');
            this.ensureRemoteOfferTransceiver('video', '1');
        }
        this.remoteDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
        if (description.type === 'answer') {
            this.finalizeNegotiatedDirections();
        }
        this.onsignalingstatechange?.();
    }
    async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
        this.addedIceCandidates.push(candidate);
    }
    setConfiguration(configuration: RTCConfiguration): void {
        this.configurationUpdates.push(configuration);
    }

    private ensureRemoteOfferTransceiver(kind: 'audio' | 'video', mid: string): void {
        if (this.transceivers.some(transceiver => transceiver.mid === mid)) {
            return;
        }
        const sender = new FakeRtcRtpSender(null);
        this.senders.push(sender);
        this.transceivers.push(new FakeRtcRtpTransceiver(kind, sender, 'recvonly', mid));
    }

    private ensureLocalOfferTransceiver(kind: 'audio' | 'video', mid: string): void {
        if (this.transceivers.some(transceiver => transceiver.mid === mid)) {
            return;
        }
        const transceiver = this.transceivers.find(candidate => (
            candidate.mid === null &&
            (candidate.receiver.track?.kind === kind || candidate.sender.track?.kind === kind)
        ));
        if (transceiver) {
            transceiver.mid = mid;
            return;
        }
        const sender = new FakeRtcRtpSender(null);
        this.senders.push(sender);
        this.transceivers.push(new FakeRtcRtpTransceiver(kind, sender, 'recvonly', mid));
    }

    private finalizeNegotiatedDirections(): void {
        for (const transceiver of this.transceivers) {
            if (transceiver.mid !== null) {
                transceiver.currentDirection = transceiver.direction;
            }
        }
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
    // Opt-in gating for the capture-generation ATTACHMENT-await race tests.
    // When a track is registered as deferred, `replaceTrack(track)` parks until
    // `releaseParked()` and only sets `this.track` once released — modelling a
    // `replaceTrack` promise that resolves LATE (after a concurrent hold/resume).
    // Inert by default (empty set), so every other test is unaffected.
    static deferredTracks = new Set<MediaStreamTrack>();
    // Opt-in gating for the DETACH-await race tests: when true, `replaceTrack(null)`
    // (the hold/handoff release path) parks until `releaseParked()`, modelling a
    // sender detachment that resolves LATE (after a concurrent re-enable). Inert by
    // default so every other test is unaffected.
    static deferNullReplace = false;
    static parked: Array<() => void> = [];
    static releaseParked(): void {
        const pending = [...FakeRtcRtpSender.parked];
        FakeRtcRtpSender.parked.length = 0;
        pending.forEach(apply => apply());
    }
    static resetGating(): void {
        FakeRtcRtpSender.deferredTracks.clear();
        FakeRtcRtpSender.deferNullReplace = false;
        FakeRtcRtpSender.parked.length = 0;
    }

    constructor(public track: MediaStreamTrack | null) {}

    async replaceTrack(track: MediaStreamTrack | null): Promise<void> {
        this.replaceTrackCalls.push(track);
        if (track && FakeRtcRtpSender.deferredTracks.has(track)) {
            await new Promise<void>((resolve) => {
                FakeRtcRtpSender.parked.push(() => { this.track = track; resolve(); });
            });
            return;
        }
        if (track === null && FakeRtcRtpSender.deferNullReplace) {
            await new Promise<void>((resolve) => {
                FakeRtcRtpSender.parked.push(() => { this.track = null; resolve(); });
            });
            return;
        }
        this.track = track;
    }
}

class FakeRtcRtpTransceiver {
    readonly receiver: RTCRtpReceiver;
    currentDirection: RTCRtpTransceiverDirection | null = null;

    constructor(
        kind: 'audio' | 'video',
        public sender: FakeRtcRtpSender,
        public direction: RTCRtpTransceiverDirection,
        public mid: string | null = null,
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

    removeTrack(track: MediaStreamTrack): void {
        this.tracks = this.tracks.filter(t => t !== track);
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

function createMediaTrack(kind: 'audio' | 'video', settings: MediaTrackSettings = {}): MediaStreamTrack {
    trackId += 1;
    return {
        id: `${kind}-${trackId}`,
        kind,
        enabled: true,
        muted: false,
        readyState: 'live',
        getSettings: () => settings,
        stop() {},
    } as MediaStreamTrack;
}

function createMediaStream(options: { audio?: boolean; video?: boolean; audioSettings?: MediaTrackSettings; videoSettings?: MediaTrackSettings } = { audio: true }): MediaStream {
    const tracks: MediaStreamTrack[] = [];
    if (options.audio !== false) tracks.push(createMediaTrack('audio', options.audioSettings));
    if (options.video) tracks.push(createMediaTrack('video', options.videoSettings));
    return new FakeMediaStream(tracks) as unknown as MediaStream;
}

function createMediaDevice(kind: MediaDeviceKind, deviceId: string, groupId: string, label: string): MediaDeviceInfo {
    return {
        kind,
        deviceId,
        groupId,
        label,
        toJSON: () => ({ kind, deviceId, groupId, label }),
    } as MediaDeviceInfo;
}

function createOutboundStats(audioBytesSent: number, videoBytesSent: number, videoFramesSent: number): RTCStatsReport {
    return new Map<string, RTCStats>([
        ['audio-out', {
            id: 'audio-out',
            timestamp: Date.now(),
            type: 'outbound-rtp',
            kind: 'audio',
            bytesSent: audioBytesSent,
        } as unknown as RTCStats],
        ['video-out', {
            id: 'video-out',
            timestamp: Date.now(),
            type: 'outbound-rtp',
            kind: 'video',
            bytesSent: videoBytesSent,
            framesSent: videoFramesSent,
        } as unknown as RTCStats],
    ]) as RTCStatsReport;
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
        FakeRtcRtpSender.resetGating();
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

    it('records the DOMException name when getUserMedia rejects and clears it on a later success', async () => {
        const getUserMedia = vi.fn().mockRejectedValueOnce(new DOMException('denied', 'NotAllowedError'))
            .mockResolvedValue(createMediaStream());
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

        const failed = await engine.startLocalMedia();
        expect(failed).toBeNull();
        expect(engine.lastLocalMediaError?.name).toBe('NotAllowedError');

        const stream = await engine.startLocalMedia();
        expect(stream).not.toBeNull();
        expect(engine.lastLocalMediaError).toBeNull();
    });

    it('records NotSupportedError when getUserMedia is unavailable', async () => {
        Object.defineProperty(globalThis, 'navigator', {
            value: { mediaDevices: {} },
            configurable: true,
        });
        const engine = new MediaEngine({}, () => {});

        const stream = await engine.startLocalMedia();

        expect(stream).toBeNull();
        expect(engine.lastLocalMediaError?.name).toBe('NotSupportedError');
    });

    it('starts local media with the default audio input when it is available before capture', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audioSettings: { deviceId: 'bt-mic', groupId: 'bluetooth' } }));
        const devices = [
            createMediaDevice('audioinput', 'default', 'bluetooth', 'Default - Headset Microphone'),
            createMediaDevice('audioinput', 'built-in-mic', 'built-in', 'MacBook Pro Microphone'),
            createMediaDevice('audioinput', 'bt-mic', 'bluetooth', 'Headset Microphone'),
            createMediaDevice('audiooutput', 'default', 'bluetooth', 'Default - Headset'),
            createMediaDevice('audiooutput', 'bt-speakers', 'bluetooth', 'Headset'),
        ];
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices: vi.fn().mockResolvedValue(devices),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});

        await engine.startLocalMedia();

        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(getUserMedia).toHaveBeenCalledWith({
            video: false,
            audio: expect.objectContaining({
                deviceId: { exact: 'bt-mic' },
            }),
        });
        expect(engine.localStream?.getAudioTracks()[0]?.getSettings().groupId).toBe('bluetooth');

        engine.destroy();
    });

    it('refreshes the local audio input to match the default output route without renegotiating active peers', async () => {
        const initialStream = createMediaStream({ audioSettings: { deviceId: 'built-in-mic', groupId: 'built-in' } });
        const refreshedStream = createMediaStream({ audioSettings: { deviceId: 'bt-mic', groupId: 'bluetooth' } });
        const getUserMedia = vi.fn()
            .mockResolvedValueOnce(initialStream)
            .mockResolvedValueOnce(refreshedStream);
        let route: 'built-in' | 'bluetooth-output' = 'built-in';
        const enumerateDevices = vi.fn().mockImplementation(async () => {
            const outputGroup = route === 'bluetooth-output' ? 'bluetooth' : 'built-in';
            return [
                createMediaDevice('audioinput', 'default', 'built-in', 'Default - MacBook Pro Microphone'),
                createMediaDevice('audioinput', 'built-in-mic', 'built-in', 'MacBook Pro Microphone'),
                createMediaDevice('audioinput', 'bt-mic', 'bluetooth', 'Headset Microphone'),
                createMediaDevice('audiooutput', 'default', outputGroup, 'Default - Output'),
                createMediaDevice('audiooutput', 'built-in-speakers', 'built-in', 'MacBook Pro Speakers'),
                createMediaDevice('audiooutput', 'bt-speakers', 'bluetooth', 'Headset'),
            ];
        });
        let deviceChangeHandler: (() => void) | undefined;
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices,
                    addEventListener: vi.fn((event: string, handler: () => void) => {
                        if (event === 'devicechange') {
                            deviceChangeHandler = handler;
                        }
                    }),
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

        const offerId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId },
        });
        await flushPromises();
        sentMessages.length = 0;

        const initialAudioTrack = initialStream.getAudioTracks()[0];
        const refreshedAudioTrack = refreshedStream.getAudioTracks()[0];
        if (initialAudioTrack) {
            initialAudioTrack.enabled = false;
        }

        route = 'bluetooth-output';
        deviceChangeHandler?.();
        await vi.waitFor(() => {
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getAudioTracks()[0]).toBe(refreshedAudioTrack);
        });

        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: false,
            audio: expect.objectContaining({
                deviceId: { exact: 'bt-mic' },
            }),
        });
        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        const audioSender = peer?.senders.find(sender => sender.track?.kind === 'audio');
        expect(refreshedAudioTrack?.enabled).toBe(false);
        expect(audioSender?.track).toBe(refreshedAudioTrack);
        expect(audioSender?.replaceTrackCalls.at(-1)).toBe(refreshedAudioTrack);
        expect(sentMessages).toEqual([]);

        engine.destroy();
    });

    it('prefers the default audio input route when refreshing after a device change', async () => {
        const initialStream = createMediaStream({ audioSettings: { deviceId: 'built-in-mic', groupId: 'built-in' } });
        const refreshedStream = createMediaStream({ audioSettings: { deviceId: 'bt-mic', groupId: 'bluetooth' } });
        const getUserMedia = vi.fn()
            .mockResolvedValueOnce(initialStream)
            .mockResolvedValueOnce(refreshedStream);
        let route: 'built-in' | 'bluetooth-input' = 'built-in';
        const enumerateDevices = vi.fn().mockImplementation(async () => {
            const inputGroup = route === 'bluetooth-input' ? 'bluetooth' : 'built-in';
            return [
                createMediaDevice('audioinput', 'default', inputGroup, 'Default - Microphone'),
                createMediaDevice('audioinput', 'built-in-mic', 'built-in', 'MacBook Pro Microphone'),
                createMediaDevice('audioinput', 'bt-mic', 'bluetooth', 'Headset Microphone'),
                createMediaDevice('audiooutput', 'default', 'built-in', 'Default - MacBook Pro Speakers'),
                createMediaDevice('audiooutput', 'built-in-speakers', 'built-in', 'MacBook Pro Speakers'),
                createMediaDevice('audiooutput', 'bt-speakers', 'bluetooth', 'Headset'),
            ];
        });
        let deviceChangeHandler: (() => void) | undefined;
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices,
                    addEventListener: vi.fn((event: string, handler: () => void) => {
                        if (event === 'devicechange') {
                            deviceChangeHandler = handler;
                        }
                    }),
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});

        await engine.startLocalMedia();
        route = 'bluetooth-input';
        deviceChangeHandler?.();

        await vi.waitFor(() => {
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getAudioTracks()[0]).toBe(refreshedStream.getAudioTracks()[0]);
        });
        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: false,
            audio: expect.objectContaining({
                deviceId: { exact: 'bt-mic' },
            }),
        });

        engine.destroy();
    });

    it('keeps the current input when the default output route has no matching microphone', async () => {
        const initialStream = createMediaStream({ audioSettings: { deviceId: 'built-in-mic', groupId: 'built-in' } });
        const getUserMedia = vi.fn().mockResolvedValue(initialStream);
        let route: 'built-in' | 'speaker-output' = 'built-in';
        const enumerateDevices = vi.fn().mockImplementation(async () => {
            const outputGroup = route === 'speaker-output' ? 'speaker' : 'built-in';
            return [
                createMediaDevice('audioinput', 'default', 'built-in', 'Default - Microphone'),
                createMediaDevice('audioinput', 'built-in-mic', 'built-in', 'MacBook Pro Microphone'),
                createMediaDevice('audiooutput', 'default', outputGroup, 'Default - Output'),
                createMediaDevice('audiooutput', 'built-in-speakers', 'built-in', 'MacBook Pro Speakers'),
                createMediaDevice('audiooutput', 'speaker', 'speaker', 'External Speaker'),
            ];
        });
        let deviceChangeHandler: (() => void) | undefined;
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices,
                    addEventListener: vi.fn((event: string, handler: () => void) => {
                        if (event === 'devicechange') {
                            deviceChangeHandler = handler;
                        }
                    }),
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});

        await engine.startLocalMedia();
        route = 'speaker-output';
        deviceChangeHandler?.();
        await flushPromises();

        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(engine.localStream?.getAudioTracks()[0]).toBe(initialStream.getAudioTracks()[0]);

        engine.destroy();
    });

    it('does not refresh audio repeatedly when current and default input group identity is unknown', async () => {
        const initialStream = createMediaStream({ audioSettings: { deviceId: 'default' } });
        const getUserMedia = vi.fn().mockResolvedValue(initialStream);
        const devices = [
            createMediaDevice('audioinput', 'default', '', 'Default - Microphone'),
            createMediaDevice('audioinput', 'built-in-mic', '', 'MacBook Pro Microphone'),
            createMediaDevice('audiooutput', 'default', '', 'Default - Output'),
        ];
        let deviceChangeHandler: (() => void) | undefined;
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia,
                    enumerateDevices: vi.fn().mockResolvedValue(devices),
                    addEventListener: vi.fn((event: string, handler: () => void) => {
                        if (event === 'devicechange') {
                            deviceChangeHandler = handler;
                        }
                    }),
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});

        await engine.startLocalMedia();
        deviceChangeHandler?.();
        await flushPromises();

        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: false,
            audio: expect.objectContaining({
                deviceId: { exact: 'default' },
            }),
        });

        engine.destroy();
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
            peer.transceivers.forEach(transceiver => {
                transceiver.currentDirection = transceiver.direction;
            });
        }

        await engine.reacquireVideoTrack();

        expect(getUserMedia).toHaveBeenLastCalledWith({
            video: { facingMode: 'user' },
            audio: false,
        });
        expect(peer?.senders.map(sender => sender.track?.kind)).toEqual(['audio', 'video']);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
    });

    it('moves answerer local tracks to negotiated transceivers before answering remote offers', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');
        await engine.startLocalMedia();

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        expect(peer?.transceivers.filter(transceiver => transceiver.mid === null).map(transceiver => transceiver.sender.track?.kind)).toEqual(['audio', 'video']);

        engine.processSignalingMessage({
            v: 1,
            type: 'offer',
            payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'offer-1' },
        });
        await flushPromises();

        await vi.waitFor(() => {
            expect(sentMessages.filter(message => message.type === 'answer')).toHaveLength(1);
        });
        expect(peer?.transceivers.find(transceiver => transceiver.mid === '0')?.sender.track?.kind).toBe('audio');
        expect(peer?.transceivers.find(transceiver => transceiver.mid === '1')?.sender.track?.kind).toBe('video');
        expect(peer?.transceivers.filter(transceiver => transceiver.mid === null).map(transceiver => transceiver.sender.track)).toEqual([null, null]);
    });

    it('asks the offerer to renegotiate when non-offer late video needs a new m-line direction', async () => {
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
        }, 'zeta');
        await engine.startLocalMedia();
        engine.processSignalingMessage({
            v: 1,
            type: 'offer',
            payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'offer-1' },
        });
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        let videoTransceiver: FakeRtcRtpTransceiver | undefined;
        await vi.waitFor(() => {
            videoTransceiver = peer?.transceivers.find(transceiver => transceiver.mid === '1');
            expect(videoTransceiver?.currentDirection).toBe('recvonly');
        });
        if (videoTransceiver) {
            videoTransceiver.direction = 'sendrecv';
        }
        sentMessages.length = 0;

        await engine.reacquireVideoTrack();
        await flushPromises();

        expect(peer?.closed).toBe(false);
        expect(sentMessages.filter(message => message.type === 'offer')).toHaveLength(0);
        expect(sentMessages.filter(message => message.type === 'media_restart_request')).toEqual([
            { type: 'media_restart_request', payload: { reason: 'local track negotiation' }, to: 'alpha' },
        ]);
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
        // Outgoing content_state now carries a per-session incrementing
        // revision (start=1, stop=2) per the screen-share wire contract.
        expect(sentMessages).toContainEqual({
            type: 'content_state',
            payload: { active: false, revision: 2 },
            to: undefined,
        });
        const contentStates = sentMessages.filter((m) => m.type === 'content_state');
        expect(contentStates.map((m) => m.payload?.revision)).toEqual([1, 2]);
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

    it('drops a legacy screen share whose picker resolves after the call goes held', async () => {
        // Race regression: getDisplayMedia resolves only AFTER a hold latches
        // mid-picker. A held call must not attach the display surface or broadcast
        // content_state {active:true} (Core Invariant 2).
        let resolveDisplay!: (stream: MediaStream) => void;
        const displayTrack = createMediaTrack('video');
        const displayStop = vi.spyOn(displayTrack, 'stop');
        const getDisplayMedia = vi.fn().mockReturnValue(
            new Promise<MediaStream>((resolve) => { resolveDisplay = resolve; }),
        );
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown> }> = [];
        const engine = new MediaEngine({}, (type, payload) => { sentMessages.push({ type, payload }); });

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await engine.startLocalMedia();
        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

        const share = engine.startScreenShare();   // picker pending
        await engine.suspendLocalMediaForHold();    // hold latches mid-picker
        resolveDisplay(new FakeMediaStream([displayTrack]) as unknown as MediaStream);
        await share;

        expect(getDisplayMedia).toHaveBeenCalledTimes(1);
        expect(displayStop).toHaveBeenCalled();   // freshly captured surface released
        expect(engine.isScreenSharing).toBe(false);
        expect(sentMessages.some(m => m.type === 'content_state' && m.payload?.active === true)).toBe(false);
        // The held call's senders never receive the display track.
        expect(peer?.senders.some(sender => sender.track === displayTrack)).toBe(false);
    });

    it('does not mark/broadcast a legacy screen share active when a hold latches during the attach await', async () => {
        // Residual race regression: the picker resolves (post-picker guard passes),
        // but a hold latches WHILE swapLocalVideoTrack is attaching to peers. The
        // start path must not mark isScreenSharing or broadcast content_state
        // active:true from the now-held call (Core Invariant 2).
        const displayTrack = createMediaTrack('video');
        const displayStop = vi.spyOn(displayTrack, 'stop');
        const getDisplayMedia = vi.fn().mockResolvedValue(new FakeMediaStream([displayTrack]) as unknown as MediaStream);
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown> }> = [];
        const engine = new MediaEngine({}, (type, payload) => { sentMessages.push({ type, payload }); });
        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await engine.startLocalMedia();

        // Interpose a full hold DURING the display-track attach await — after the
        // post-picker guard passes, before the share is marked active.
        const internals = engine as unknown as {
            swapLocalVideoTrack: (next: MediaStreamTrack | null, prev: MediaStreamTrack | null) => Promise<void>;
        };
        const originalSwap = internals.swapLocalVideoTrack.bind(engine);
        let raced = false;
        internals.swapLocalVideoTrack = async (next, prev) => {
            await originalSwap(next, prev);
            if (next === displayTrack && !raced) {
                raced = true;
                await engine.suspendLocalMediaForHold();
            }
        };

        await engine.startScreenShare();

        expect(raced).toBe(true);                  // the attach-await race was exercised
        expect(engine.isScreenSharing).toBe(false);
        expect(displayStop).toHaveBeenCalled();    // display surface released
        expect(sentMessages.some(m => m.type === 'content_state' && m.payload?.active === true)).toBe(false);
    });

    it('drops a reacquired camera track when a hold latches during getUserMedia', async () => {
        // Race regression: a hold racing a resume re-latches held while the
        // reacquire's getUserMedia is pending. The freshly captured camera track
        // must be dropped, never attached to peer senders (Core Invariant 2).
        const cameraTrack = createMediaTrack('video');
        const cameraStop = vi.spyOn(cameraTrack, 'stop');
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, () => {});
        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await engine.startLocalMedia();
        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

        // Drop the camera track so reacquireVideoTrack runs a fresh getUserMedia.
        await engine.releaseVideoTrack();
        expect(engine.localStream?.getVideoTracks()).toHaveLength(0);

        // Arm a deferred getUserMedia for the reacquire's acquireCameraTrack call.
        let resolveCamera!: (stream: MediaStream) => void;
        getUserMedia.mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveCamera = resolve; }));

        const reacquire = engine.reacquireVideoTrack();   // getUserMedia pending
        await engine.suspendLocalMediaForHold();           // hold latches mid-acquire
        resolveCamera(new FakeMediaStream([cameraTrack]) as unknown as MediaStream);
        await reacquire;

        expect(cameraStop).toHaveBeenCalled();                        // fresh track released
        expect(engine.localStream?.getVideoTracks()).toHaveLength(0); // not attached locally
        expect(peer?.senders.some(sender => sender.track === cameraTrack)).toBe(false);
    });

    // --- Capture-generation ABA fences (hold → resume clears held → a capture
    // continuation started BEFORE the hold resolves). `heldNoCapture` alone is
    // false again by the time these resolve; only the capture generation catches
    // them, so the stale track must be dropped and never attached. ---
    describe('capture-generation ABA fences', () => {
        function setupNavigator(getUserMedia: ReturnType<typeof vi.fn>): void {
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
        }

        async function joinedEngine(getUserMedia: ReturnType<typeof vi.fn>): Promise<{
            engine: MediaEngine;
            peer: FakeRtcPeerConnection | undefined;
            sent: Array<{ type: string; payload?: Record<string, unknown> }>;
        }> {
            setupNavigator(getUserMedia);
            const sent: Array<{ type: string; payload?: Record<string, unknown> }> = [];
            const engine = new MediaEngine({}, (type, payload) => { sent.push({ type, payload }); });
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await engine.startLocalMedia();
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
            return { engine, peer, sent };
        }

        it('drops a reacquired camera track that resolves AFTER hold+resume (ABA)', async () => {
            const cameraTrack = createMediaTrack('video');
            const cameraStop = vi.spyOn(cameraTrack, 'stop');
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
            const { engine, peer } = await joinedEngine(getUserMedia);

            await engine.releaseVideoTrack();
            let resolveCamera!: (stream: MediaStream) => void;
            getUserMedia.mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveCamera = resolve; }));

            const reacquire = engine.reacquireVideoTrack();       // getUserMedia pending (gen G)
            await engine.suspendLocalMediaForHold();               // gen -> G+1, held
            // Resume with video OFF clears heldNoCapture (ABA) but does NOT reacquire
            // the camera, so ONLY the generation fence can catch the stale track.
            await engine.resumeLocalMediaFromHold(false, 'off');
            resolveCamera(new FakeMediaStream([cameraTrack]) as unknown as MediaStream);
            await reacquire;

            expect(cameraStop).toHaveBeenCalled();
            expect(engine.localStream?.getVideoTracks()).toHaveLength(0);
            expect(peer?.senders.some(sender => sender.track === cameraTrack)).toBe(false);
        });

        it('drops a reacquired mic track superseded by a re-hold during resume (ABA)', async () => {
            const micTrack = createMediaTrack('audio');
            const micStop = vi.spyOn(micTrack, 'stop');
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
            const { engine, peer } = await joinedEngine(getUserMedia);

            await engine.suspendLocalMediaForHold();               // release mic, gen G1
            let resolveMic!: (stream: MediaStream) => void;
            getUserMedia.mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveMic = resolve; }));
            const resume1 = engine.resumeLocalMediaFromHold(true, 'off');  // mic reacquire pending (gen G1)
            await flushPromises();
            await engine.suspendLocalMediaForHold();               // re-hold: gen -> G2 (fences the pending mic)
            await engine.resumeLocalMediaFromHold(false, 'off');   // resume muted: clears held (ABA)
            resolveMic(new FakeMediaStream([micTrack]) as unknown as MediaStream);
            await resume1;

            expect(micStop).toHaveBeenCalled();
            expect(engine.localStream?.getAudioTracks().some(t => t === micTrack)).toBe(false);
            expect(peer?.senders.some(sender => sender.track === micTrack)).toBe(false);
        });

        it('a resume that coalesces onto a fenced mic acquire retries a fresh acquire (not mic-less)', async () => {
            const staleMic = createMediaTrack('audio');
            const staleMicStop = vi.spyOn(staleMic, 'stop');
            // Fresh stream per call: the engine mutates localStream (which IS the
            // getUserMedia stream) on hold, so a shared mock object would be empty
            // by the time the fresh retry acquires.
            const getUserMedia = vi.fn().mockImplementation(async () => createMediaStream({ audio: true, video: false }));
            const { engine } = await joinedEngine(getUserMedia);

            await engine.suspendLocalMediaForHold();               // release mic, gen G1
            let resolveStale!: (stream: MediaStream) => void;
            getUserMedia.mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveStale = resolve; }));
            const resume1 = engine.resumeLocalMediaFromHold(true, 'off');  // mic acquire A pending (gen G1)
            await flushPromises();
            await engine.suspendLocalMediaForHold();               // re-hold: gen -> G2 (fences acquire A)
            // resume2 wants a mic; it coalesces onto acquire A, which will be fenced.
            const resume2 = engine.resumeLocalMediaFromHold(true, 'off');
            await flushPromises();
            resolveStale(new FakeMediaStream([staleMic]) as unknown as MediaStream); // acquire A fences (dropped)
            await Promise.all([resume1, resume2]);

            // The fenced pre-hold mic was stopped, but resume2 did NOT end mic-less:
            // it fell through to a fresh acquire that landed a live mic.
            expect(staleMicStop).toHaveBeenCalled();
            expect(engine.localStream?.getAudioTracks()).toHaveLength(1);
            expect(engine.localStream?.getAudioTracks()[0]).not.toBe(staleMic);
        });

        it('drops initial media whose getUserMedia resolves AFTER hold+resume (ABA)', async () => {
            const audioTrack = createMediaTrack('audio');
            const videoTrack = createMediaTrack('video');
            const audioStop = vi.spyOn(audioTrack, 'stop');
            const videoStop = vi.spyOn(videoTrack, 'stop');
            let resolveInitial!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn().mockReturnValue(
                new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }),
            );
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // initial getUserMedia pending (gen 1)
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(false, 'off');   // clears held (ABA)
            resolveInitial(new FakeMediaStream([audioTrack, videoTrack]) as unknown as MediaStream);
            const result = await start;

            expect(result).toBeNull();                             // fenced: no media published
            expect(audioStop).toHaveBeenCalled();
            expect(videoStop).toHaveBeenCalled();
            expect(peer?.senders.some(sender => sender.track === audioTrack || sender.track === videoTrack)).toBe(false);
        });

        it('hands off to resumed capture when a stale initial-media start blocked the resume reacquire', async () => {
            // The resume's mic/camera reacquire early-returns on the `requestingMedia`
            // guard because a parked initial-media start still holds the media latch
            // (and the route refresh is a no-op). If that stale start just bailed out
            // it would leave the resumed foreground call permanently without mic or
            // camera; the stale-exit must instead hand off to the resumed capture.
            const initialAudio = createMediaTrack('audio');
            const initialVideo = createMediaTrack('video');
            const initialAudioStop = vi.spyOn(initialAudio, 'stop');
            const initialVideoStop = vi.spyOn(initialVideo, 'stop');
            let resolveInitial!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))
                .mockImplementation(async (constraints: MediaStreamConstraints) => {
                    const tracks: MediaStreamTrack[] = [];
                    if (constraints.audio) tracks.push(createMediaTrack('audio'));
                    if (constraints.video) tracks.push(createMediaTrack('video'));
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
                });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // initial getUserMedia (gen 1, latch held)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            // Resume wants mic + camera but its reacquire(s) early-return: the parked
            // initial start still holds `requestingMedia`, so nothing reacquires.
            await engine.resumeLocalMediaFromHold(true, 'selfie');
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);

            resolveInitial(new FakeMediaStream([initialAudio, initialVideo]) as unknown as MediaStream);
            const result = await start;

            // Stale start published nothing and stopped its OWN captured tracks.
            expect(result).toBeNull();
            expect(initialAudioStop).toHaveBeenCalled();
            expect(initialVideoStop).toHaveBeenCalled();
            expect(peer?.senders.some(s => s.track === initialAudio || s.track === initialVideo)).toBe(false);

            // The foreground call ends with live mic + camera (the reacquired tracks),
            // attached to the peer's senders — the handoff replayed the resume intent.
            const resumedAudio = engine.localStream?.getAudioTracks()[0];
            const resumedVideo = engine.localStream?.getVideoTracks()[0];
            expect(resumedAudio).toBeTruthy();
            expect(resumedVideo).toBeTruthy();
            expect(resumedAudio).not.toBe(initialAudio);
            expect(resumedVideo).not.toBe(initialVideo);
            expect(peer?.senders.some(s => s.track === resumedAudio)).toBe(true);
            expect(peer?.senders.some(s => s.track === resumedVideo)).toBe(true);
        });

        it('acquires nothing when only a hold (no resume) interleaves a parked initial-media start', async () => {
            // Hold-only variant: no resume armed a handoff, so the stale initial-media
            // bail-out must NOT reacquire anything — the call is still held.
            const initialAudio = createMediaTrack('audio');
            const initialVideo = createMediaTrack('video');
            let resolveInitial!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))
                .mockImplementation(async () => createMediaStream({ audio: true, video: true }));
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // hold, NO resume
            resolveInitial(new FakeMediaStream([initialAudio, initialVideo]) as unknown as MediaStream);
            const result = await start;

            expect(result).toBeNull();
            // Still held: no capture reacquired, senders carry no live track, and the
            // handoff triggered no further getUserMedia (only the initial acquire ran).
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.every(s => s.track === null)).toBe(true);
            expect(getUserMedia).toHaveBeenCalledTimes(1);
        });

        it('does not reacquire a media kind the user disabled after the handoff was armed (intent stays current)', async () => {
            // Finding 1: the handoff intent snapshots resume-time desired media. If
            // the user toggles the mic OFF (via the session, `disarmResumeHandoff`)
            // AFTER resume armed the handoff but BEFORE the parked start consumes it,
            // the handoff must NOT reacquire the mic — otherwise it would transmit
            // audio the user just disabled. The still-desired camera is reacquired.
            const initialAudio = createMediaTrack('audio');
            const initialVideo = createMediaTrack('video');
            let resolveInitial!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))
                .mockImplementation(async (constraints: MediaStreamConstraints) => {
                    const tracks: MediaStreamTrack[] = [];
                    if (constraints.audio) tracks.push(createMediaTrack('audio'));
                    if (constraints.video) tracks.push(createMediaTrack('video'));
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
                });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // initial getUserMedia (gen 1, latch held)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}
            // User mutes the mic before the parked start releases the latch.
            engine.disarmResumeHandoff('audio');

            resolveInitial(new FakeMediaStream([initialAudio, initialVideo]) as unknown as MediaStream);
            const result = await start;

            expect(result).toBeNull();
            // Mic was withdrawn from the handoff: no live audio track, no audio
            // getUserMedia after the initial acquire, no audio on the peer senders.
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'audio')).toBe(false);
            // Camera was still desired, so the handoff reacquired and attached it.
            const resumedVideo = engine.localStream?.getVideoTracks()[0];
            expect(resumedVideo).toBeTruthy();
            expect(peer?.senders.some(s => s.track === resumedVideo)).toBe(true);
        });

        it('does not clear the media latch a newer start now owns (stop + newer start supersedes the parked op)', async () => {
            // Finding 2: a stale start continuation must release the `requestingMedia`
            // latch ONLY if it still owns it. If a `stopLocalMedia` + a NEWER
            // `startLocalMedia` took the latch while the old acquire was parked, the
            // stale op clearing the latch would let recovery/toggle paths launch a
            // concurrent getUserMedia under the newer start.
            const oldAudio = createMediaTrack('audio');
            const oldVideo = createMediaTrack('video');
            let resolveOld!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveOld = resolve; }))  // start A (parked)
                .mockReturnValueOnce(new Promise<MediaStream>(() => {}))                                  // start B (parked, owns latch)
                .mockImplementation(async () => createMediaStream({ audio: true, video: true }));         // any concurrent acquire = bug
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const internals = engine as unknown as { requestingMedia: boolean };

            const startA = engine.startLocalMedia();               // gen 1, latch owner A
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            engine.stopLocalMedia();                                // gen -> 2, latch released
            const startB = engine.startLocalMedia();               // gen 3, latch owner B (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));

            resolveOld(new FakeMediaStream([oldAudio, oldVideo]) as unknown as MediaStream);  // A stale, bails
            const resultA = await startA;

            expect(resultA).toBeNull();
            // B still owns the latch: A's stale bail-out did NOT clear it.
            expect(internals.requestingMedia).toBe(true);
            // A recovery/toggle path finds the latch held and does NOT double-acquire.
            await engine.reacquireLocalAudioCapture();
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            void startB;
        });

        it('does not start camera capture when a hold interleaves the handoff audio reacquire', async () => {
            // Finding 3: the handoff rechecks held/destroyed BEFORE EACH kind. If a
            // hold starts while the handoff's AUDIO reacquire is awaiting
            // getUserMedia, the VIDEO branch must NOT proceed — otherwise it starts
            // camera capture on a now-held call (dropped only post-acquisition).
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))    // handoff audio (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));           // camera acquire = bug
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            await engine.suspendLocalMediaForHold();               // hold AGAIN while handoff audio is awaiting
            resolveAudio(createMediaStream({ audio: true, video: false }));   // fenced (held): dropped
            const result = await start;

            expect(result).toBeNull();
            // The video branch never ran: no camera getUserMedia (still 2 calls) and
            // no live camera track on the (held) call.
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
        });

        it('aborts the resume-handoff pass when stopLocalMedia tears media down mid-audio-reacquire (terminal reset)', async () => {
            // P1: `stopLocalMedia` is the session's COMMON terminal reset path
            // (remote-ended, signaling error, join timeout — `resetSessionResources`)
            // and tears media down WITHOUT destroying the engine. It bumps the
            // capture generation, but a RUNNING handoff pass captured its `intent`
            // object up front and never re-checked. So a parked audio reacquire that
            // resolves after the stop would let the pass proceed into VIDEO
            // reconciliation and reacquire the camera AFTER the call ended
            // (videoReacquiresAfterStop). The pass must abort on the generation move.
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))    // handoff audio (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));           // any camera acquire = bug
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            engine.stopLocalMedia();                               // TERMINAL reset: bumps gen, engine NOT destroyed
            resolveAudio(createMediaStream({ audio: true, video: false }));   // audio lands stale (dropped); pass must abort before video
            const result = await start;

            expect(result).toBeNull();
            // The video branch never ran: no camera getUserMedia (still 2 calls),
            // no live tracks, and no sender left carrying a track.
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.every(sender => sender.track === null)).toBe(true);
        });

        it('aborts the resume-handoff pass when stopLocalMedia tears media down mid-video-reacquire', async () => {
            // Same P1, but the terminal stop lands while the handoff's VIDEO reacquire
            // is parked in getUserMedia (audio already reconciled). No camera track may
            // survive the teardown: the reacquire's own post-acquire generation fence
            // drops the freshly captured track, and the reconcile loop aborts without
            // re-reacquiring.
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveVideo!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockImplementationOnce(async () => createMediaStream({ audio: true, video: false }))       // handoff audio (resolves)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveVideo = resolve; }))    // handoff video (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));           // any further camera acquire = bug
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff: audio resolves, video parks
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(3));
            engine.stopLocalMedia();                               // TERMINAL reset during the video await
            resolveVideo(createMediaStream({ audio: false, video: true }));   // camera lands stale -> dropped, no re-reacquire
            const result = await start;

            expect(result).toBeNull();
            // No 4th getUserMedia (no re-reacquire) and no camera track survives.
            expect(getUserMedia).toHaveBeenCalledTimes(3);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(sender => sender.track?.kind === 'video')).toBe(false);
        });

        it('retries capture via the handoff when the initial acquire rejects with a handoff armed', async () => {
            // Finding 4: the catch path must consume the handoff too. A transient
            // initial-acquire failure with an armed handoff would otherwise leave the
            // resumed foreground call permanently capture-less.
            let rejectInitial!: (err: unknown) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((_, reject) => { rejectInitial = reject; }))  // initial (rejects)
                .mockImplementation(async (constraints: MediaStreamConstraints) => {
                    const tracks: MediaStreamTrack[] = [];
                    if (constraints.audio) tracks.push(createMediaTrack('audio'));
                    if (constraints.video) tracks.push(createMediaTrack('video'));
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
                });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // initial acquire (audio-only, parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            rejectInitial(new DOMException('denied', 'NotAllowedError'));  // catch -> handoff replays capture
            const result = await start;

            expect(result).toBeNull();
            // The foreground call ends with the desired mic + camera (via the handoff).
            const resumedAudio = engine.localStream?.getAudioTracks()[0];
            const resumedVideo = engine.localStream?.getVideoTracks()[0];
            expect(resumedAudio).toBeTruthy();
            expect(resumedVideo).toBeTruthy();
            expect(peer?.senders.some(s => s.track === resumedAudio)).toBe(true);
            expect(peer?.senders.some(s => s.track === resumedVideo)).toBe(true);
        });

        it('does not reacquire a kind disarmed WHILE the handoff audio reacquire is in flight', async () => {
            // Finding 1 (in-flight disarm): the handoff must keep the armed intent
            // disarmable until capture finishes. If the user toggles the camera OFF
            // (session -> `disarmResumeHandoff('video')`) AFTER the handoff has begun
            // but WHILE its audio reacquire is still awaiting getUserMedia, the video
            // branch that runs after the audio await must honor the disarm and NOT
            // reacquire the camera. The mic (still desired) is reacquired.
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))    // handoff audio (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));           // camera acquire = bug
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // User disables the camera WHILE the handoff's audio reacquire is awaiting.
            engine.disarmResumeHandoff('video');
            resolveAudio(createMediaStream({ audio: true, video: false }));   // audio lands; video branch must skip
            const result = await start;

            expect(result).toBeNull();
            // Camera was withdrawn mid-flight: no video getUserMedia (still 2 calls),
            // no live camera track, nothing on the peer's video senders.
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'video')).toBe(false);
            // Mic was still desired: reacquired and attached.
            const resumedAudio = engine.localStream?.getAudioTracks()[0];
            expect(resumedAudio).toBeTruthy();
            expect(peer?.senders.some(s => s.track === resumedAudio)).toBe(true);
        });

        it('releases the mic when audio is disarmed WHILE the handoff audio reacquire is in flight', async () => {
            // Finding (same-kind in-flight disarm): the handoff's audio reacquire
            // checks the intent BEFORE `getUserMedia`, but a `disarmResumeHandoff`
            // ('audio') (the session's mute path) can fire WHILE that acquire is
            // awaiting. The in-flight reacquire then attaches the live mic anyway,
            // and the session's toggle-off saw no existing track to release — so
            // without a post-await re-check the mic transmits after mute. The
            // handoff must release the just-acquired mic when its kind was withdrawn.
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))    // handoff audio (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));           // camera acquire
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // User mutes WHILE the handoff's audio reacquire is awaiting getUserMedia.
            engine.disarmResumeHandoff('audio');
            resolveAudio(createMediaStream({ audio: true, video: false }));   // mic lands, but must be released
            const result = await start;

            expect(result).toBeNull();
            // Mic was withdrawn mid-flight: even though the reacquire attached it, the
            // handoff released it — no live audio track, nothing on the audio senders.
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'audio')).toBe(false);
            // Camera was still desired: reacquired and attached (video per intent).
            const resumedVideo = engine.localStream?.getVideoTracks()[0];
            expect(resumedVideo).toBeTruthy();
            expect(peer?.senders.some(s => s.track === resumedVideo)).toBe(true);
        });

        it('releases the camera when video is disarmed WHILE the handoff video reacquire is in flight', async () => {
            // Symmetric same-kind in-flight disarm for video: a `disarmResumeHandoff`
            // ('video') fired while the handoff's camera reacquire is awaiting
            // getUserMedia must not leave a live camera track sending after the user
            // turned the camera off. Audio (still desired) lands normally.
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveVideo!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockResolvedValueOnce(createMediaStream({ audio: true, video: false }))                     // handoff audio (lands)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveVideo = resolve; }))     // handoff video (parked)
                .mockImplementation(async () => createMediaStream({ audio: false, video: true }));
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff: audio lands, then camera reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(3));
            // User turns the camera off WHILE the handoff's video reacquire is awaiting.
            engine.disarmResumeHandoff('video');
            resolveVideo(createMediaStream({ audio: false, video: true }));   // camera lands, but must be released
            const result = await start;

            expect(result).toBeNull();
            // Camera was withdrawn mid-flight: the reacquired track was released — no
            // live video track, nothing on the video senders.
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'video')).toBe(false);
            // Mic was still desired: reacquired and attached.
            const resumedAudio = engine.localStream?.getAudioTracks()[0];
            expect(resumedAudio).toBeTruthy();
            expect(peer?.senders.some(s => s.track === resumedAudio)).toBe(true);
        });

        it('re-acquires a live mic when audio is RE-ENABLED while the handoff disarm-release is in flight', async () => {
            // Stale-release vs re-enable race: the handoff's audio reacquire attached
            // a live mic, then a `disarmResumeHandoff('audio')` (mute) made the
            // handoff begin releasing it. If the user UNMUTES while that release is
            // awaiting `replaceTrack(null)`, the session's enable-with-track-present
            // path only flips `track.enabled` (the track is still attached) and
            // schedules NO reacquire. The stale release then stops+removes the track,
            // leaving the call silent-unmuted (desired audio on, no live mic). The
            // handoff must reconcile against the foreground intent and reacquire.
            const firstMic = createMediaTrack('audio');
            const firstMicStop = vi.spyOn(firstMic, 'stop');
            const secondMic = createMediaTrack('audio');
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))    // handoff audio reacquire (parked)
                .mockImplementation(async () => new FakeMediaStream([secondMic]) as unknown as MediaStream); // reconcile reacquire
            setupNavigator(getUserMedia);
            // Park the RELEASE detach (`replaceTrack(null)`) so the unmute can race it.
            FakeRtcRtpSender.deferNullReplace = true;
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'off');     // arms handoff {audio}
            engine.setForegroundCaptureIntent('audio', true);       // resume desired audio on

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // User mutes WHILE the handoff's audio reacquire is awaiting getUserMedia.
            engine.disarmResumeHandoff('audio');
            engine.setForegroundCaptureIntent('audio', false);
            resolveAudio(new FakeMediaStream([firstMic]) as unknown as MediaStream);  // mic lands -> handoff begins releasing it
            // The disarm-release parks on `replaceTrack(null)`; the mic is still attached.
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            // User UNMUTES while the release detach is parked.
            engine.setForegroundCaptureIntent('audio', true);
            FakeRtcRtpSender.releaseParked();                       // release lands: first mic stopped+removed
            const result = await start;

            expect(result).toBeNull();
            // The call ends with a LIVE mic (the reconcile reacquire), NOT silent:
            // a fresh track, attached to the peer's audio sender.
            const liveMic = engine.localStream?.getAudioTracks()[0];
            expect(liveMic).toBe(secondMic);
            expect(liveMic).not.toBe(firstMic);
            expect(peer?.senders.some(s => s.track === secondMic)).toBe(true);
            // The disarm-released first mic was stopped and detached.
            expect(firstMicStop).toHaveBeenCalled();
            expect(peer?.senders.some(s => s.track === firstMic)).toBe(false);
            // Initial + handoff reacquire + reconcile reacquire = 3 getUserMedia calls.
            expect(getUserMedia).toHaveBeenCalledTimes(3);
        });

        it('re-acquires a live camera when video is RE-ENABLED while the handoff disarm-release is in flight', async () => {
            // Symmetric video variant of the stale-release vs re-enable race: a camera
            // re-enable during the handoff's disarm-release must not leave the call
            // silent-unmuted for video — the handoff reconciles and reacquires.
            const firstCam = createMediaTrack('video');
            const firstCamStop = vi.spyOn(firstCam, 'stop');
            const secondCam = createMediaTrack('video');
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveVideo!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))  // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveVideo = resolve; }))    // handoff video reacquire (parked)
                .mockImplementation(async () => new FakeMediaStream([secondCam]) as unknown as MediaStream); // reconcile reacquire
            setupNavigator(getUserMedia);
            FakeRtcRtpSender.deferNullReplace = true;
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(false, 'selfie'); // arms handoff {selfie}
            engine.setForegroundCaptureIntent('video', true);       // resume desired video on

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> video reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // User turns the camera off WHILE the handoff's video reacquire is awaiting.
            engine.disarmResumeHandoff('video');
            engine.setForegroundCaptureIntent('video', false);
            resolveVideo(new FakeMediaStream([firstCam]) as unknown as MediaStream);  // camera lands -> handoff begins releasing it
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            // User re-enables the camera while the release detach is parked.
            engine.setForegroundCaptureIntent('video', true);
            FakeRtcRtpSender.releaseParked();                       // release lands: first camera stopped+removed
            const result = await start;

            expect(result).toBeNull();
            const liveCam = engine.localStream?.getVideoTracks()[0];
            expect(liveCam).toBe(secondCam);
            expect(liveCam).not.toBe(firstCam);
            expect(peer?.senders.some(s => s.track === secondCam)).toBe(true);
            expect(firstCamStop).toHaveBeenCalled();
            expect(peer?.senders.some(s => s.track === firstCam)).toBe(false);
            expect(getUserMedia).toHaveBeenCalledTimes(3);
        });

        it('releases the mic when audio is DISABLED while the handoff RECONCILE reacquire is in flight', async () => {
            // The finding: the post-release RECONCILE reacquire (which reacquires a
            // mic after a re-enable raced the disarm-release) was itself an UNFENCED
            // one-shot. If the user disables audio AGAIN while that reconcile
            // reacquire is awaiting getUserMedia, the session's toggle-off sees no
            // track to release (the reacquire has not attached yet), so nothing
            // detaches the mic it then attaches — silent transmit after mute. The
            // bounded fixed-point loop re-reads the intent AFTER the reconcile
            // reacquire await and releases the just-attached track.
            const firstMic = createMediaTrack('audio');
            const secondMic = createMediaTrack('audio');
            const secondMicStop = vi.spyOn(secondMic, 'stop');
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            let resolveReconcile!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))    // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))      // handoff audio reacquire (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveReconcile = resolve; })); // reconcile reacquire (parked)
            setupNavigator(getUserMedia);
            FakeRtcRtpSender.deferNullReplace = true;               // park the disarm-release detach
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'off');     // arms handoff {audio}
            engine.setForegroundCaptureIntent('audio', true);       // resume desired audio on

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // Mute while the first handoff reacquire is awaiting.
            engine.disarmResumeHandoff('audio');
            engine.setForegroundCaptureIntent('audio', false);
            resolveAudio(new FakeMediaStream([firstMic]) as unknown as MediaStream);  // mic lands -> loop releases it (parks on replaceTrack null)
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            // Unmute while the release detach is parked -> the reconcile reacquire runs next.
            engine.setForegroundCaptureIntent('audio', true);
            FakeRtcRtpSender.releaseParked();                      // release lands (firstMic gone) -> reconcile reacquire starts
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(3));
            // DISABLE again WHILE the reconcile reacquire is parked in getUserMedia (the finding).
            engine.disarmResumeHandoff('audio');
            engine.setForegroundCaptureIntent('audio', false);
            FakeRtcRtpSender.deferNullReplace = false;             // let the final safe-side release settle
            resolveReconcile(new FakeMediaStream([secondMic]) as unknown as MediaStream);  // reconcile mic lands, must be released
            const result = await start;

            expect(result).toBeNull();
            // The loop observed the disable after the reconcile reacquire await and
            // released the just-attached mic: no live audio, nothing on the senders.
            expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'audio')).toBe(false);
            expect(secondMicStop).toHaveBeenCalled();
            expect(getUserMedia).toHaveBeenCalledTimes(3);
        });

        it('releases the camera when video is DISABLED while the handoff RECONCILE reacquire is in flight', async () => {
            // Symmetric video variant of the finding: the post-release reconcile
            // reacquire for the camera must also be fenced against a LATER disable.
            const firstCam = createMediaTrack('video');
            const secondCam = createMediaTrack('video');
            const secondCamStop = vi.spyOn(secondCam, 'stop');
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveVideo!: (stream: MediaStream) => void;
            let resolveReconcile!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))    // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveVideo = resolve; }))      // handoff video reacquire (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveReconcile = resolve; })); // reconcile reacquire (parked)
            setupNavigator(getUserMedia);
            FakeRtcRtpSender.deferNullReplace = true;
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(false, 'selfie'); // arms handoff {selfie}
            engine.setForegroundCaptureIntent('video', true);       // resume desired video on

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> video reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            engine.disarmResumeHandoff('video');
            engine.setForegroundCaptureIntent('video', false);
            resolveVideo(new FakeMediaStream([firstCam]) as unknown as MediaStream);  // camera lands -> loop releases it (parks)
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            engine.setForegroundCaptureIntent('video', true);      // re-enable while release parked
            FakeRtcRtpSender.releaseParked();                      // release lands -> reconcile reacquire starts
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(3));
            // DISABLE again WHILE the reconcile reacquire is parked in getUserMedia.
            engine.disarmResumeHandoff('video');
            engine.setForegroundCaptureIntent('video', false);
            FakeRtcRtpSender.deferNullReplace = false;
            resolveReconcile(new FakeMediaStream([secondCam]) as unknown as MediaStream);
            const result = await start;

            expect(result).toBeNull();
            expect(engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            expect(peer?.senders.some(s => s.track?.kind === 'video')).toBe(false);
            expect(secondCamStop).toHaveBeenCalled();
            expect(getUserMedia).toHaveBeenCalledTimes(3);
        });

        it('ends with a live mic when enable/disable churn races the handoff across parked awaits', async () => {
            // Enable -> disable -> enable churn across the reacquire AND the
            // disarm-release parked awaits must converge, on the fixed point, to the
            // FINAL intent: audio desired on -> a live mic attached, never left
            // silent-unmuted. Bounded getUserMedia (no thrash).
            const firstMic = createMediaTrack('audio');
            const firstMicStop = vi.spyOn(firstMic, 'stop');
            const secondMic = createMediaTrack('audio');
            let resolveInitial!: (stream: MediaStream) => void;
            let resolveAudio!: (stream: MediaStream) => void;
            let resolveReconcile!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveInitial = resolve; }))    // initial (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveAudio = resolve; }))      // handoff audio reacquire (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveReconcile = resolve; })); // reconcile reacquire (parked)
            setupNavigator(getUserMedia);
            FakeRtcRtpSender.deferNullReplace = true;
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const start = engine.startLocalMedia();                // gen 1, latch held
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            await engine.suspendLocalMediaForHold();               // gen -> 2, held
            await engine.resumeLocalMediaFromHold(true, 'off');     // arms handoff {audio} (ENABLE)
            engine.setForegroundCaptureIntent('audio', true);

            resolveInitial(createMediaStream({ audio: true, video: true }));  // stale exit -> handoff -> audio reacquire (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            // DISABLE while the reacquire is parked.
            engine.disarmResumeHandoff('audio');
            engine.setForegroundCaptureIntent('audio', false);
            resolveAudio(new FakeMediaStream([firstMic]) as unknown as MediaStream);  // mic lands -> loop releases (parks)
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            // ENABLE again while the release detach is parked -> reconcile reacquires.
            engine.setForegroundCaptureIntent('audio', true);
            FakeRtcRtpSender.deferNullReplace = false;             // reconcile + convergence settle synchronously
            FakeRtcRtpSender.releaseParked();                      // release lands (firstMic gone) -> reconcile reacquire starts
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(3));
            resolveReconcile(new FakeMediaStream([secondMic]) as unknown as MediaStream);  // reconcile mic lands and STAYS (desired on)
            const result = await start;

            expect(result).toBeNull();
            // Converged to the final intent: a live mic (the reconcile track),
            // attached to the peer's audio sender, never silent-unmuted.
            const liveMic = engine.localStream?.getAudioTracks()[0];
            expect(liveMic).toBe(secondMic);
            expect(peer?.senders.some(s => s.track === secondMic)).toBe(true);
            expect(firstMicStop).toHaveBeenCalled();               // the disarm-released first mic was torn down
            expect(getUserMedia).toHaveBeenCalledTimes(3);         // bounded: no thrash
        });

        it('terminates a pathological enable/disable alternation on the safe side (iteration bound)', async () => {
            // The bounded fixed-point reconcile must not spin forever if the desired
            // intent flips on every pass. With `wanted` always the opposite of the
            // current track state the loop never reaches a fixed point; it must stop
            // at the pass bound and, on stopping, prefer the SAFE side — release a
            // live track the current intent says should be off.
            const engine = new MediaEngine({}, () => {});
            let live = false;
            let reacquires = 0;
            let releases = 0;
            const engineInternals = engine as unknown as {
                reconcileHandoffCapture: (
                    gen: number,
                    wanted: () => boolean,
                    hasLiveTrack: () => boolean,
                    reacquire: () => Promise<void>,
                    release: () => Promise<void>,
                ) => Promise<void>;
                mediaRequestId: number;
            };
            const reconcile = engineInternals.reconcileHandoffCapture.bind(engine);

            await reconcile(
                engineInternals.mediaRequestId,                   // stable generation: no stop/hold in this test
                () => !live,                                      // never agrees with the track state
                () => live,
                async () => { reacquires += 1; live = true; },
                async () => { releases += 1; live = false; },
            );

            // Bounded: a handful of corrective passes, not an infinite spin.
            expect(reacquires + releases).toBeLessThanOrEqual(6);
            expect(reacquires + releases).toBeGreaterThan(0);
            // Safe side: the intent said off for the final live track, so it was released.
            expect(live).toBe(false);
        });

        it('reschedules a fresh handoff pass when an ENABLE races the bound-side safe release (never silent-unmuted)', async () => {
            // The finding: at the iteration bound the safe-side cleanup releases a live
            // unwanted track, but its `replaceTrack(null)` awaits — and a user ENABLE of
            // that kind can land during the await (the session flips `track.enabled`
            // with the track still attached and schedules no reacquire). The release
            // then removes the track: desired ON, no live track (silent-unmuted). The
            // after-every-await fixed point does not hold AT the bound, so termination
            // must be quiescence-based: after the bound release, re-read the intent and
            // reschedule a fresh handoff pass that reacquires a live track.
            const getUserMedia = vi.fn().mockImplementation(async (constraints: MediaStreamConstraints) => {
                const tracks: MediaStreamTrack[] = [];
                if (constraints.audio) tracks.push(createMediaTrack('audio'));
                if (constraints.video) tracks.push(createMediaTrack('video'));
                return new FakeMediaStream(tracks) as unknown as MediaStream;
            });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
            const internals = engine as unknown as {
                foregroundAudioIntent: boolean;
                reconcileHandoffCapture: (
                    gen: number,
                    wanted: () => boolean,
                    hasLiveTrack: () => boolean,
                    reacquire: () => Promise<void>,
                    release: () => Promise<void>,
                ) => Promise<void>;
                mediaRequestId: number;
            };

            // Drive the alternation to the bound with `wanted` always the opposite of
            // the track state (as the pathological-alternation test does). The
            // bound-side safe release flips the fake track off, which flips `wanted`
            // back ON — modelling the enable that races the release — and mirrors the
            // real re-enable into the engine's foreground intent so the rescheduled
            // real handoff reacquires a live mic.
            let live = false;
            await internals.reconcileHandoffCapture.bind(engine)(
                internals.mediaRequestId,                         // stable generation: no stop/hold in this test
                () => !live,
                () => live,
                async () => { live = true; },
                async () => { live = false; internals.foregroundAudioIntent = true; },
            );

            // The rescheduled pass runs on a microtask and reacquires the mic. It ends
            // with a LIVE audio track attached to the peer's audio sender, not silent.
            await vi.waitFor(() => expect(engine.localStream?.getAudioTracks()[0]).toBeTruthy());
            const liveMic = engine.localStream?.getAudioTracks()[0];
            expect(peer?.senders.some(s => s.track === liveMic)).toBe(true);
        });

        it('ends released when a DISABLE races the rescheduled reacquire (quiescence, not thrash)', async () => {
            // Symmetric to the reschedule test, but the user DISABLES the kind again
            // while the rescheduled pass is reacquiring. The fresh pass must observe
            // the disable after its reacquire await and release the just-attached
            // track: the call ends with no live track, and the reschedule does not
            // spin.
            let resolveReacquire!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveReacquire = resolve; }));
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
            const internals = engine as unknown as {
                foregroundAudioIntent: boolean;
                reconcileHandoffCapture: (
                    gen: number,
                    wanted: () => boolean,
                    hasLiveTrack: () => boolean,
                    reacquire: () => Promise<void>,
                    release: () => Promise<void>,
                ) => Promise<void>;
                mediaRequestId: number;
            };

            let live = false;
            await internals.reconcileHandoffCapture.bind(engine)(
                internals.mediaRequestId,                         // stable generation: no stop/hold in this test
                () => !live,
                () => live,
                async () => { live = true; },
                async () => { live = false; internals.foregroundAudioIntent = true; },
            );

            // The rescheduled pass reacquires: its getUserMedia parks. Disable while it
            // is in flight (mute path: clear the foreground intent + disarm), then let
            // the reacquire land.
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            internals.foregroundAudioIntent = false;
            engine.disarmResumeHandoff('audio');
            resolveReacquire(createMediaStream({ audio: true }));

            // The fresh pass re-read the disable after the reacquire await and released
            // the just-attached mic: no live audio, nothing on the peer's audio sender.
            await vi.waitFor(() => expect(engine.localStream?.getAudioTracks() ?? []).toHaveLength(0));
            expect(peer?.senders.some(s => s.track?.kind === 'audio')).toBe(false);
            // Bounded: the single rescheduled reacquire, no thrash.
            expect(getUserMedia).toHaveBeenCalledTimes(1);
        });

        it('does not stack reschedules (one pending handoff pass at a time)', async () => {
            // The reschedule latch must ensure a burst of bound-side releases queues at
            // most ONE fresh handoff pass. Two back-to-back reschedule requests must
            // result in a single `handOffToResumedCapture` entry.
            const engine = new MediaEngine({}, () => {});
            const internals = engine as unknown as {
                foregroundAudioIntent: boolean;
                rescheduleResumeHandoff: () => void;
                handOffToResumedCapture: () => Promise<void>;
            };
            internals.foregroundAudioIntent = true; // so the re-arm has a kind to want
            let entries = 0;
            internals.handOffToResumedCapture = () => { entries += 1; return Promise.resolve(); };

            internals.rescheduleResumeHandoff(); // queues one pass
            internals.rescheduleResumeHandoff(); // latched: no-op, does not stack
            await Promise.resolve();
            await Promise.resolve();

            expect(entries).toBe(1);
        });

        it('serializes a mid-pass reschedule so the original pass cannot strand a disabled camera', async () => {
            // The finding: an audio-bound reschedule fires while the ORIGINAL handoff
            // is still parked in its video reacquire. If the reschedule starts a fresh
            // pass concurrently, the original pass — still holding its stale intent —
            // publishes the camera even though the user disabled it mid-reacquire, and
            // if the fresh pass then blocks on its own reacquire the camera stays
            // transmitting. With passes serialized, the follow-up runs strictly AFTER
            // the original completes and observes the disable, so the camera ends
            // released. Leaf sinks are stubbed; the handoff/reconcile/reschedule
            // serialization under test is real.
            const engine = new MediaEngine({}, () => {});
            const internals = engine as unknown as {
                handOffToResumedCapture: () => Promise<void>;
                rescheduleResumeHandoff: () => void;
                pendingResumeHandoff: { audio: boolean; videoMode: string } | null;
                foregroundVideoIntent: boolean;
                foregroundAudioIntent: boolean;
                localStream: FakeMediaStream | null;
                reacquireVideoTrack: () => Promise<void>;
                releaseVideoTrack: () => Promise<void>;
                reacquireLocalAudioCapture: () => Promise<void>;
                releaseLocalAudioCapture: () => Promise<void>;
            };

            // A live mic is already present (as after the audio-bound pass that
            // triggers the reschedule), so the audio reconcile is an instant no-op and
            // the armed intent keeps a wanted kind (audio) — a video-only disarm must
            // NOT null the whole intent, or the follow-up would have nothing to run.
            const stream = new FakeMediaStream([createMediaTrack('audio')]);
            internals.localStream = stream;
            let senderHasCamera = false; // models the camera attached to a peer video sender
            const parkedReacquires: Array<() => void> = [];
            internals.reacquireVideoTrack = () => new Promise<void>((resolve) => {
                parkedReacquires.push(() => {
                    stream.addTrack(createMediaTrack('video'));
                    senderHasCamera = true;
                    resolve();
                });
            });
            internals.releaseVideoTrack = async () => {
                const track = stream.getVideoTracks()[0];
                if (track) stream.removeTrack(track);
                senderHasCamera = false;
            };
            // Audio stays satisfied (live mic present), so these are never exercised.
            internals.reacquireLocalAudioCapture = async () => {};
            internals.releaseLocalAudioCapture = async () => {};

            // Arm the ORIGINAL pass: camera wanted (selfie), mic wanted (already live).
            internals.foregroundVideoIntent = true;
            internals.foregroundAudioIntent = true;
            internals.pendingResumeHandoff = { audio: true, videoMode: 'selfie' };

            // Start the original pass; it reaches the video reacquire and parks.
            void internals.handOffToResumedCapture();
            await vi.waitFor(() => expect(parkedReacquires.length).toBe(1));

            // A reschedule is requested mid-pass (models the audio-bound safe-release
            // path re-arming a fresh pass). Let any (buggy) overlapping pass start and
            // park its own reacquire before we disable.
            internals.rescheduleResumeHandoff();
            await new Promise((resolve) => setTimeout(resolve, 0));

            // User DISABLES the camera while the original's reacquire is still parked.
            internals.foregroundVideoIntent = false;
            engine.disarmResumeHandoff('video');

            // Resolve ONLY the original pass's reacquire: it publishes the camera. A
            // buggy overlapping pass is left parked on its own reacquire and never
            // releases; the serialized follow-up releases instead.
            parkedReacquires[0]();
            await new Promise((resolve) => setTimeout(resolve, 0));

            // No camera remains published or attached.
            expect(stream.getVideoTracks()).toHaveLength(0);
            expect(senderHasCamera).toBe(false);
        });

        it('runs a reschedule promptly when no handoff pass is in flight', async () => {
            // The prompt (microtask) path must survive: a reschedule requested while
            // NO pass is running still fires on the next microtask, not deferred to a
            // (nonexistent) running loop's tail.
            const engine = new MediaEngine({}, () => {});
            const internals = engine as unknown as {
                foregroundAudioIntent: boolean;
                handoffInFlight: Promise<void> | null;
                rescheduleResumeHandoff: () => void;
                handOffToResumedCapture: () => Promise<void>;
            };
            internals.foregroundAudioIntent = true; // so the re-arm has a kind to want
            let entries = 0;
            internals.handOffToResumedCapture = () => { entries += 1; return Promise.resolve(); };

            expect(internals.handoffInFlight).toBeNull();
            internals.rescheduleResumeHandoff();
            expect(entries).toBe(0);          // queued, not synchronous
            await Promise.resolve();
            await Promise.resolve();
            expect(entries).toBe(1);          // fired promptly on the microtask
        });

        it('runs overlapping handoff requests strictly sequentially (no concurrent passes)', async () => {
            // Two overlap-triggering requests must run their pass bodies one at a time:
            // the second defers until the first fully completes (entry/exit ordering
            // never interleaves).
            const engine = new MediaEngine({}, () => {});
            const internals = engine as unknown as {
                handOffToResumedCapture: () => Promise<void>;
                runResumedCapturePass: () => Promise<void>;
            };
            const events: string[] = [];
            const parks: Array<() => void> = [];
            let n = 0;
            internals.runResumedCapturePass = () => {
                const id = ++n;
                events.push(`enter${id}`);
                return new Promise<void>((resolve) => {
                    parks.push(() => { events.push(`exit${id}`); resolve(); });
                });
            };

            const first = internals.handOffToResumedCapture();
            await vi.waitFor(() => expect(events).toEqual(['enter1']));
            // A second request arrives WHILE the first pass is in flight: it must not
            // start a concurrent pass — it defers to a tail rerun.
            void internals.handOffToResumedCapture();
            await Promise.resolve();
            expect(events).toEqual(['enter1']); // no 'enter2' while pass 1 runs

            // Finish pass 1 -> the deferred follow-up (pass 2) runs next.
            parks[0]();
            await vi.waitFor(() => expect(events).toEqual(['enter1', 'exit1', 'enter2']));
            parks[1]();
            await first;
            expect(events).toEqual(['enter1', 'exit1', 'enter2', 'exit2']);
        });

        it('does not consume the handoff at a stale exit that no longer owns the latch (B replays it)', async () => {
            // Finding 2 (consume only with the active latch): start A parks owning the
            // latch, a stop + start B supersedes it (B now owns the latch, parked), a
            // hold+resume arms the handoff (blocked by B). If stale A's continuation
            // exits FIRST, it must NOT consume the handoff: its reacquire sinks would
            // early-return on B's `requestingMedia` guard, stranding the intent. A
            // returns without consuming; B's later exit (which releases the active
            // latch) replays the handoff and the resumed call ends with the media.
            const oldAudio = createMediaTrack('audio');
            const oldVideo = createMediaTrack('video');
            const newAudio = createMediaTrack('audio');
            const newVideo = createMediaTrack('video');
            let resolveA!: (stream: MediaStream) => void;
            let resolveB!: (stream: MediaStream) => void;
            const getUserMedia = vi.fn()
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveA = resolve; }))  // start A (parked)
                .mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveB = resolve; }))  // start B (parked, owns latch)
                .mockImplementation(async (constraints: MediaStreamConstraints) => {                  // handoff reacquires
                    const tracks: MediaStreamTrack[] = [];
                    if (constraints.audio) tracks.push(newAudio);
                    if (constraints.video) tracks.push(newVideo);
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
                });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
            const internals = engine as unknown as { pendingResumeHandoff: unknown };

            const startA = engine.startLocalMedia();               // gen 1, latch owner A (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(1));
            engine.stopLocalMedia();                                // gen -> 2, latch released
            const startB = engine.startLocalMedia();               // gen 3, latch owner B (parked)
            await vi.waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2));
            await engine.suspendLocalMediaForHold();               // gen -> 4, held (B still parked)
            await engine.resumeLocalMediaFromHold(true, 'selfie');  // arms handoff {audio, selfie}, blocked by B

            resolveA(new FakeMediaStream([oldAudio, oldVideo]) as unknown as MediaStream);  // A stale, exits FIRST
            const resultA = await startA;

            expect(resultA).toBeNull();
            // A did not own the latch, so it must have left the intent armed for B.
            expect(internals.pendingResumeHandoff).not.toBeNull();

            resolveB(new FakeMediaStream([createMediaTrack('audio'), createMediaTrack('video')]) as unknown as MediaStream);  // B stale, releases latch, replays handoff
            const resultB = await startB;

            expect(resultB).toBeNull();
            // The handoff replayed under B: the resumed call ends with mic + camera
            // attached to the peer's senders.
            expect(engine.localStream?.getAudioTracks()[0]).toBe(newAudio);
            expect(engine.localStream?.getVideoTracks()[0]).toBe(newVideo);
            expect(peer?.senders.some(s => s.track === newAudio)).toBe(true);
            expect(peer?.senders.some(s => s.track === newVideo)).toBe(true);
        });

        it('undoes a stale initial-media sender attachment when hold+resume interleaves before the replaceTrack resolves', async () => {
            // Attachment-await race: the initial getUserMedia resolves, but the
            // per-peer `replaceTrack` that attaches the initial tracks is still in
            // flight when a hold (then a resume that attaches fresh tracks)
            // interleaves. When the OLD `replaceTrack` finally lands it points the
            // sender at a stopped pre-hold track; the post-attach fence must RESTORE
            // the resumed (current-generation) track on that sender — not null it,
            // which would strip the resumed foreground call of its mic/camera.
            const audioA = createMediaTrack('audio');
            const videoA = createMediaTrack('video');
            const audioAStop = vi.spyOn(audioA, 'stop');
            const videoAStop = vi.spyOn(videoA, 'stop');
            const getUserMedia = vi.fn()
                .mockResolvedValueOnce(new FakeMediaStream([audioA, videoA]) as unknown as MediaStream)
                .mockImplementation(async (constraints: MediaStreamConstraints) => {
                    const tracks: MediaStreamTrack[] = [createMediaTrack('audio')];
                    if (constraints.video) tracks.push(createMediaTrack('video'));
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
                });
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            // Park the initial-media attach `replaceTrack(A)` so it resolves LATE.
            FakeRtcRtpSender.deferredTracks.add(audioA);
            FakeRtcRtpSender.deferredTracks.add(videoA);
            const start = engine.startLocalMedia();                // acquires A, parks at attach
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            await engine.suspendLocalMediaForHold();               // gen bump, releases capture
            await engine.resumeLocalMediaFromHold(true, 'selfie'); // attaches fresh (resumed) tracks
            // Let the OLD initial `replaceTrack(A)` land last (clobbering the resumed track).
            FakeRtcRtpSender.releaseParked();
            const result = await start;

            expect(result).toBeNull();                             // stale start publishes nothing
            // The resumed foreground call keeps its mic: the audio sender that
            // received the stale pre-hold attach (the legacy attach awaits the
            // parked audio `replaceTrack`, so the audio sender is the one that
            // actually races here) ends at EXACTLY the resumed mic track — never
            // null and never the stale stopped track. (The independent-peer resume
            // test in MediaEngineIndependentContent.test.ts covers the camera/video
            // sender restore, which this legacy path never reaches.)
            const resumedAudio = engine.localStream?.getAudioTracks()[0];
            expect(resumedAudio).toBeTruthy();
            expect(resumedAudio).not.toBe(audioA);
            const audioSender = peer?.senders.find(s => s.replaceTrackCalls.includes(audioA));
            expect(audioSender).toBeTruthy();
            expect(audioSender?.track).toBe(resumedAudio);
            expect(peer?.senders.some(sender => sender.track === audioA || sender.track === videoA)).toBe(false);
            expect(audioAStop).toHaveBeenCalled();
            expect(videoAStop).toHaveBeenCalled();
        });

        it('nulls a stale initial-media sender when a hold with no resume interleaves before the replaceTrack resolves', async () => {
            // Hold, no resume: the stale initial `replaceTrack` lands on a held call
            // (no newer op installed anything). The fence detaches it, leaving the
            // sender null — never the stale stopped track.
            const audioA = createMediaTrack('audio');
            const audioAStop = vi.spyOn(audioA, 'stop');
            const getUserMedia = vi.fn().mockResolvedValue(new FakeMediaStream([audioA]) as unknown as MediaStream);
            setupNavigator(getUserMedia);
            const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            FakeRtcRtpSender.deferredTracks.add(audioA);
            const start = engine.startLocalMedia();
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            await engine.suspendLocalMediaForHold();               // hold, no resume
            FakeRtcRtpSender.releaseParked();                      // OLD replaceTrack lands on a held call
            const result = await start;

            expect(result).toBeNull();
            expect(peer?.senders.every(sender => sender.track === null)).toBe(true);
            expect(audioAStop).toHaveBeenCalled();
        });

        it('detaches a legacy display track from a sender when a hold latches while the attach replaceTrack is in flight', async () => {
            // Attachment-await race (legacy screen share): the picker resolves and the
            // post-picker guard passes, but a hold latches while the display track's
            // `replaceTrack` is still in flight. When it lands late the sender points
            // at the stopped display track; the stale branch must detach it and never
            // mark the share active or broadcast content_state active:true.
            const displayTrack = createMediaTrack('video');
            const displayStop = vi.spyOn(displayTrack, 'stop');
            const getDisplayMedia = vi.fn().mockResolvedValue(new FakeMediaStream([displayTrack]) as unknown as MediaStream);
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
            const sent: Array<{ type: string; payload?: Record<string, unknown> }> = [];
            const engine = new MediaEngine({}, (type, payload) => { sent.push({ type, payload }); });
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await engine.startLocalMedia();
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            // Park the display track's attach `replaceTrack` so it resolves LATE.
            FakeRtcRtpSender.deferredTracks.add(displayTrack);
            const share = engine.startScreenShare();
            await vi.waitFor(() => expect(FakeRtcRtpSender.parked.length).toBeGreaterThan(0));
            await engine.suspendLocalMediaForHold();               // hold latches mid-attach
            FakeRtcRtpSender.releaseParked();                      // OLD replaceTrack lands after hold
            await share;

            expect(engine.isScreenSharing).toBe(false);
            expect(sent.some(m => m.type === 'content_state' && m.payload?.active === true)).toBe(false);
            expect(peer?.senders.some(sender => sender.track === displayTrack)).toBe(false);
            expect(displayStop).toHaveBeenCalled();
        });

        it('drops a legacy screen share whose picker resolves AFTER hold+resume (ABA)', async () => {
            const displayTrack = createMediaTrack('video');
            const displayStop = vi.spyOn(displayTrack, 'stop');
            let resolveDisplay!: (stream: MediaStream) => void;
            const getDisplayMedia = vi.fn().mockReturnValue(
                new Promise<MediaStream>((resolve) => { resolveDisplay = resolve; }),
            );
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
            const sent: Array<{ type: string; payload?: Record<string, unknown> }> = [];
            const engine = new MediaEngine({}, (type, payload) => { sent.push({ type, payload }); });
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await engine.startLocalMedia();
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            const share = engine.startScreenShare();               // picker pending (gen G)
            await engine.suspendLocalMediaForHold();               // gen -> G+1, held
            await engine.resumeLocalMediaFromHold(false, 'off');   // clears held (ABA)
            resolveDisplay(new FakeMediaStream([displayTrack]) as unknown as MediaStream);
            await share;

            expect(displayStop).toHaveBeenCalled();
            expect(engine.isScreenSharing).toBe(false);
            expect(sent.some(m => m.type === 'content_state' && m.payload?.active === true)).toBe(false);
            expect(peer?.senders.some(sender => sender.track === displayTrack)).toBe(false);
        });

        it('drops a flipCamera track when a hold latches during its getUserMedia', async () => {
            const flippedTrack = createMediaTrack('video');
            const flippedStop = vi.spyOn(flippedTrack, 'stop');
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
            setupNavigator(getUserMedia);
            // Two cameras so flipCamera proceeds.
            (globalThis.navigator.mediaDevices.enumerateDevices as ReturnType<typeof vi.fn>).mockResolvedValue([
                createMediaDevice('videoinput', 'cam-1', 'g1', 'Front'),
                createMediaDevice('videoinput', 'cam-2', 'g2', 'Back'),
            ]);
            const sent: Array<{ type: string; payload?: Record<string, unknown> }> = [];
            const engine = new MediaEngine({}, (type, payload) => { sent.push({ type, payload }); });
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await engine.startLocalMedia();
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
            expect(engine.hasMultipleCameras).toBe(true);

            let resolveFlip!: (stream: MediaStream) => void;
            getUserMedia.mockReturnValueOnce(new Promise<MediaStream>((resolve) => { resolveFlip = resolve; }));
            const flip = engine.flipCamera();                      // getUserMedia pending (gen G)
            await engine.suspendLocalMediaForHold();               // gen -> G+1, held
            resolveFlip(new FakeMediaStream([flippedTrack]) as unknown as MediaStream);
            await flip;

            expect(flippedStop).toHaveBeenCalled();
            expect(peer?.senders.some(sender => sender.track === flippedTrack)).toBe(false);
        });

        it('initial-media route-refresh window: a hold+resume during device detection does not stop or detach the resumed tracks', async () => {
            // Finding part 2: after assigning `this.localStream`, startLocalMedia awaits
            // device detection / route refresh, then RE-READS `this.localStream`. A
            // hold+resume during that window installs newer-generation (resumed) tracks;
            // the stale initial op must clean up its OWN captured tracks and re-check the
            // generation BEFORE re-reading, so it never stops/detaches the resumed call's
            // tracks. Interpose the race by overriding the private route-refresh await
            // (mirrors the swapLocalVideoTrack interpose pattern above); at that point
            // `requestingMedia` is already false, so resume's reacquire can run.
            const micA = createMediaTrack('audio');
            const camA = createMediaTrack('video');
            const micAStop = vi.spyOn(micA, 'stop');
            const camAStop = vi.spyOn(camA, 'stop');
            const getUserMedia = vi.fn()
                .mockResolvedValueOnce(new FakeMediaStream([micA, camA]) as unknown as MediaStream)
                .mockImplementation(async (constraints: MediaStreamConstraints) => {
                    const tracks: MediaStreamTrack[] = [createMediaTrack('audio')];
                    if (constraints.video) tracks.push(createMediaTrack('video'));
                    return new FakeMediaStream(tracks) as unknown as MediaStream;
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
            const engine = new MediaEngine({}, () => {});
            engine.updateSignalingConnected(true);
            engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;

            // Interpose a hold+resume inside the route-refresh await of the in-flight
            // initial start (gen 1). Resume installs fresh (gen 2) mic/camera.
            const internals = engine as unknown as {
                refreshLocalAudioTrack: (reason: string, devices?: unknown) => Promise<boolean>;
            };
            internals.refreshLocalAudioTrack = async () => {
                await engine.suspendLocalMediaForHold();               // gen -> 2, stop micA/camA
                await engine.resumeLocalMediaFromHold(true, 'selfie'); // install resumed mic/camera
                return false;
            };

            const result = await engine.startLocalMedia();

            expect(result).toBeNull();                                 // stale initial op publishes nothing
            // Its OWN captured tracks are stopped.
            expect(micAStop).toHaveBeenCalled();
            expect(camAStop).toHaveBeenCalled();
            // The RESUMED tracks survive on their senders (the pre-fix stale cleanup,
            // reading the re-read stream, would stop + detach them instead).
            const resumedMic = engine.localStream?.getAudioTracks()[0];
            const resumedCam = engine.localStream?.getVideoTracks()[0];
            expect(resumedMic).toBeTruthy();
            expect(resumedCam).toBeTruthy();
            expect(resumedMic).not.toBe(micA);
            expect(resumedCam).not.toBe(camA);
            expect(peer?.senders.some(sender => sender.track === resumedMic)).toBe(true);
            expect(peer?.senders.some(sender => sender.track === resumedCam)).toBe(true);
            expect(peer?.senders.some(sender => sender.track === micA || sender.track === camA)).toBe(false);
        });
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

    it('lets the deferred two-party host offer even when its peer ID sorts later', async () => {
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia: vi.fn().mockResolvedValue(createMediaStream()),
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({ initialVideoEnabled: false, deferInitialAnswer: true }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }],
        }, 'zeta');

        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });
    });

    it('retries ICE restart when a deferred first answer fails to apply', async () => {
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia: vi.fn().mockResolvedValue(createMediaStream()),
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({ initialVideoEnabled: false, deferInitialAnswer: true }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }, { cid: 'alpha' }],
        }, 'zeta');
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection;
        expect(peer).toBeDefined();
        const offerId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        expect(typeof offerId).toBe('string');
        peer.failNextRemoteAnswer = true;

        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'alpha', sdp: 'remote-answer', offerId },
        });

        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
        });
        expect(peer.rollbackCalls).toBe(1);
        expect(peer.createOfferCalls).toBe(2);
    });

    it('keeps the deferred two-party non-host from offering even when its peer ID sorts earlier', async () => {
        Object.defineProperty(globalThis, 'navigator', {
            value: {
                mediaDevices: {
                    getUserMedia: vi.fn().mockResolvedValue(createMediaStream()),
                    enumerateDevices: vi.fn().mockResolvedValue([]),
                    addEventListener() {},
                    removeEventListener() {},
                },
            },
            configurable: true,
        });
        const sentMessages: Array<{ type: string; payload?: Record<string, unknown>; to?: string }> = [];
        const engine = new MediaEngine({ initialVideoEnabled: false, deferInitialAnswer: true }, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await flushPromises();

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

    it('recreates the offerer peer when connected outbound media is stalled', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });

        const firstOfferId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        expect(typeof firstOfferId).toBe('string');
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId: firstOfferId },
        });
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        peer!.connectionState = 'connected';
        peer!.iceConnectionState = 'connected';
        peer!.statsReports = [
            createOutboundStats(0, 0, 0),
            createOutboundStats(0, 0, 0),
            createOutboundStats(0, 0, 0),
        ];

        const internals = engine as unknown as { recoverStalledOutboundMedia: () => Promise<void> };
        await internals.recoverStalledOutboundMedia();
        await internals.recoverStalledOutboundMedia();
        await internals.recoverStalledOutboundMedia();
        await flushPromises();

        const replacement = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(replacement).toBeDefined();
        expect(replacement).not.toBe(peer);
        expect(peer?.closed).toBe(true);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
    });

    it('requests media restart from the offer owner when non-offerer outbound media is stalled', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');

        engine.processSignalingMessage({
            v: 1,
            type: 'offer',
            payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'offer-1' },
        });
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'answer')).toHaveLength(1);
        });

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        peer!.connectionState = 'connected';
        peer!.iceConnectionState = 'connected';
        peer!.statsReports = [
            createOutboundStats(0, 0, 0),
            createOutboundStats(0, 0, 0),
            createOutboundStats(0, 0, 0),
        ];

        const internals = engine as unknown as { recoverStalledOutboundMedia: () => Promise<void> };
        await internals.recoverStalledOutboundMedia();
        await internals.recoverStalledOutboundMedia();
        await internals.recoverStalledOutboundMedia();
        await flushPromises();

        expect(engine.getPeerConnectionsMap().get('alpha')).toBe(peer);
        expect(peer?.closed).toBe(false);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(0);
        expect(sentMessages.filter((message) => message.type === 'answer')).toHaveLength(1);
        expect(sentMessages.filter((message) => message.type === 'media_restart_request')).toEqual([
            { type: 'media_restart_request', payload: { reason: 'stalled outbound media' }, to: 'alpha' },
        ]);
    });

    it('does not request media restart from a departed peer', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');

        const internals = engine as unknown as { requestPeerMediaRecovery: (remoteCid: string, reason: string) => void };
        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }],
        }, 'zeta');
        internals.requestPeerMediaRecovery('alpha', 'departed peer');

        expect(sentMessages.filter((message) => message.type === 'media_restart_request')).toHaveLength(0);
    });

    it('drops a rolled-back offer when the peer is replaced during rollback', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        await peer!.setLocalDescription(await peer!.createOffer());
        expect(peer!.signalingState).toBe('have-local-offer');

        const originalSetLocalDescription = peer!.setLocalDescription.bind(peer);
        let resolveRollback: (() => void) | null = null;
        peer!.setLocalDescription = vi.fn(async (description: RTCSessionDescriptionInit) => {
            if (description.type === 'rollback') {
                await new Promise<void>(resolve => { resolveRollback = resolve; });
            }
            await originalSetLocalDescription(description);
        });

        engine.processSignalingMessage({
            v: 1,
            type: 'offer',
            payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'remote-offer' },
        });
        await flushPromises();
        expect(resolveRollback).toBeDefined();

        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }],
        }, 'zeta');
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');
        const replacement = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(replacement).toBeDefined();
        expect(replacement).not.toBe(peer);

        resolveRollback?.();
        await flushPromises();

        expect(sentMessages.filter(message =>
            message.type === 'answer' &&
            message.to === 'alpha' &&
            message.payload?.offerId === 'remote-offer'
        )).toHaveLength(0);
    });

    it('drops outbound stats results when the peer is replaced while stats are in flight', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');
        engine.processSignalingMessage({
            v: 1,
            type: 'offer',
            payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'remote-offer' },
        });
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('alpha') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        peer!.connectionState = 'connected';
        peer!.iceConnectionState = 'connected';
        const internals = engine as unknown as {
            recoverStalledOutboundMedia: () => Promise<void>;
            peers: Map<string, {
                lastOutboundMediaSample: { audioBytesSent: number; videoBytesSent: number; videoFramesSent: number } | null;
                outboundMediaStallSamples: number;
            }>;
        };
        const peerState = internals.peers.get('alpha');
        expect(peerState).toBeDefined();
        peerState!.lastOutboundMediaSample = { audioBytesSent: 0, videoBytesSent: 0, videoFramesSent: 0 };
        peerState!.outboundMediaStallSamples = OUTBOUND_MEDIA_STALL_SAMPLES - 1;

        let resolveStats: (() => void) | null = null;
        peer!.getStats = vi.fn(async () => {
            await new Promise<void>(resolve => { resolveStats = resolve; });
            return createOutboundStats(0, 0, 0);
        });

        const pendingRecovery = internals.recoverStalledOutboundMedia();
        await flushPromises();
        expect(resolveStats).toBeDefined();

        engine.updateRoomState({
            hostCid: 'zeta',
            participants: [{ cid: 'zeta' }],
        }, 'zeta');
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'zeta');
        expect(engine.getPeerConnectionsMap().get('alpha')).not.toBe(peer);

        resolveStats?.();
        await pendingRecovery;
        await flushPromises();

        expect(sentMessages.filter((message) => message.type === 'media_restart_request')).toHaveLength(0);
    });

    it('recreates the offerer peer when a media restart request is received', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });

        const firstOfferId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        expect(typeof firstOfferId).toBe('string');
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId: firstOfferId },
        });
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        engine.processSignalingMessage({
            v: 1,
            type: 'media_restart_request',
            payload: { from: 'zeta', reason: 'stalled outbound media' },
        });
        await flushPromises();

        const replacement = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(replacement).toBeDefined();
        expect(replacement).not.toBe(peer);
        expect(peer?.closed).toBe(true);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
    });

    it('renegotiates without recreating the offerer peer for local track negotiation requests', async () => {
        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });

        const firstOfferId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId: firstOfferId },
        });
        await flushPromises();

        const peer = engine.getPeerConnectionsMap().get('zeta') as FakeRtcPeerConnection | undefined;
        expect(peer).toBeDefined();
        engine.processSignalingMessage({
            v: 1,
            type: 'media_restart_request',
            payload: { from: 'zeta', reason: 'local track negotiation' },
        });
        await flushPromises();

        expect(engine.getPeerConnectionsMap().get('zeta')).toBe(peer);
        expect(peer?.closed).toBe(false);
        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);
    });

    it('rate limits repeated media restart requests from the same peer', async () => {
        vi.useFakeTimers();
        vi.setSystemTime(1_000_000);

        const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ audio: true, video: true }));
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
        const engine = new MediaEngine({}, (type, payload, to) => {
            sentMessages.push({ type, payload, to });
        });

        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await vi.waitFor(() => {
            expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(1);
        });

        const firstOfferId = sentMessages.find((message) => message.type === 'offer')?.payload?.offerId;
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId: firstOfferId },
        });
        await flushPromises();

        engine.processSignalingMessage({
            v: 1,
            type: 'media_restart_request',
            payload: { from: 'zeta', reason: 'stalled outbound media' },
        });
        engine.processSignalingMessage({
            v: 1,
            type: 'media_restart_request',
            payload: { from: 'zeta', reason: 'stalled outbound media' },
        });
        await flushPromises();

        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(2);

        const restartOfferId = sentMessages.filter((message) => message.type === 'offer').at(-1)?.payload?.offerId;
        engine.processSignalingMessage({
            v: 1,
            type: 'answer',
            payload: { from: 'zeta', sdp: 'remote-answer', offerId: restartOfferId },
        });
        await flushPromises();
        vi.setSystemTime(Date.now() + OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS + 1);

        engine.processSignalingMessage({
            v: 1,
            type: 'media_restart_request',
            payload: { from: 'zeta', reason: 'stalled outbound media' },
        });
        await flushPromises();

        expect(sentMessages.filter((message) => message.type === 'offer')).toHaveLength(3);
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

    it('caps deferred ICE restart cooldown when the wall clock moves backwards', async () => {
        vi.useFakeTimers();
        vi.setSystemTime(1_000_000);
        const engine = new MediaEngine({}, () => {});

        engine.updateSignalingConnected(true);
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');

        const internals = engine as unknown as {
            peers: Map<string, { lastIceRestartAt: number; iceRestartTimer: number | null }>;
            scheduleIceRestart: (cid: string, reason: string, delay: number) => void;
            triggerIceRestart: (cid: string, reason: string) => Promise<void>;
        };
        const peer = internals.peers.get('zeta');
        expect(peer).toBeDefined();
        peer!.lastIceRestartAt = Date.now() + ICE_RESTART_COOLDOWN_MS * 10;
        const restartSpy = vi.spyOn(internals, 'triggerIceRestart').mockResolvedValue(undefined);

        internals.scheduleIceRestart('zeta', 'clock-regressed', 0);

        expect(peer!.iceRestartTimer).not.toBeNull();
        await vi.advanceTimersByTimeAsync(ICE_RESTART_COOLDOWN_MS - 1);
        expect(restartSpy).not.toHaveBeenCalled();
        await vi.advanceTimersByTimeAsync(1);
        expect(restartSpy).toHaveBeenCalledWith('zeta', 'clock-regressed');

        restartSpy.mockRestore();
        engine.destroy();
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

    // FIX W2: remote-playout deafen must be STICKY. A held call stores the gate
    // and applies it to receivers/tracks that appear AFTER hold (new peers,
    // renegotiation, late `ontrack`), not just the receivers present at hold time.
    describe('sticky remote-playout deafen (multi-call hold)', () => {
        function audioReceiverTracks(pc: FakeRtcPeerConnection): MediaStreamTrack[] {
            return pc.getReceivers().map(r => r.track).filter((t): t is MediaStreamTrack => !!t && t.kind === 'audio');
        }

        it('deafens existing receivers and is reversed on resume', () => {
            const engine = new MediaEngine({}, () => {});
            engine.updateRoomState({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            }, 'me');
            const peer1 = engine.getPeerConnectionsMap().get('peer-1') as unknown as FakeRtcPeerConnection;

            engine.setRemotePlaybackEnabled(false);
            expect(audioReceiverTracks(peer1).every(t => t.enabled === false)).toBe(true);

            engine.setRemotePlaybackEnabled(true);
            expect(audioReceiverTracks(peer1).every(t => t.enabled === true)).toBe(true);

            engine.destroy();
        });

        it('silences a peer that JOINS while the call is held', () => {
            const engine = new MediaEngine({}, () => {});
            engine.updateRoomState({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            }, 'me');

            // Hold: deafen current receivers.
            engine.setRemotePlaybackEnabled(false);

            // A second peer joins WHILE held (renegotiation/new participant). Its
            // receivers must inherit the gate, not become audible.
            engine.updateRoomState({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }, { cid: 'peer-2' }],
            }, 'me');
            const peer2 = engine.getPeerConnectionsMap().get('peer-2') as unknown as FakeRtcPeerConnection;

            expect(audioReceiverTracks(peer2).length).toBeGreaterThan(0);
            expect(audioReceiverTracks(peer2).every(t => t.enabled === false)).toBe(true);

            engine.destroy();
        });

        it('silences a fresh audio track surfaced via ontrack while held', () => {
            const engine = new MediaEngine({}, () => {});
            engine.updateRoomState({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            }, 'me');
            const peer1 = engine.getPeerConnectionsMap().get('peer-1') as unknown as FakeRtcPeerConnection;

            engine.setRemotePlaybackEnabled(false);

            // A new remote audio track arrives (e.g. peer reacquired its mic /
            // renegotiated) AFTER hold. The late `ontrack` must deafen it.
            const lateTrack = createMediaTrack('audio');
            expect(lateTrack.enabled).toBe(true);
            peer1.ontrack?.({
                track: lateTrack,
                streams: [createMediaStream({ audio: true })],
            } as unknown as RTCTrackEvent);

            expect(lateTrack.enabled).toBe(false);

            // After resume, a subsequent ontrack track is audible again.
            engine.setRemotePlaybackEnabled(true);
            const liveTrack = createMediaTrack('audio');
            peer1.ontrack?.({
                track: liveTrack,
                streams: [createMediaStream({ audio: true })],
            } as unknown as RTCTrackEvent);
            expect(liveTrack.enabled).toBe(true);

            engine.destroy();
        });

        // Core Invariant 2: a held call owns NO capture via ANY path. A
        // devicechange (preferred-route change) would normally reacquire the mic
        // via `refreshLocalAudioTrack`. After hold, the local stream is empty (but
        // non-null), so without the held guard the route refresh would call
        // `getUserMedia` and restart capture on a held call. This is the Web
        // analog of the Android non-toggle reacquire gap.
        it('does NOT reacquire the mic on a device change while held', async () => {
            const initialStream = createMediaStream({ audioSettings: { deviceId: 'built-in-mic', groupId: 'built-in' } });
            const refreshedStream = createMediaStream({ audioSettings: { deviceId: 'bt-mic', groupId: 'bluetooth' } });
            const getUserMedia = vi.fn()
                .mockResolvedValueOnce(initialStream)
                .mockResolvedValueOnce(refreshedStream);
            // A route change that WOULD trigger a refresh if not held: the default
            // output flips to the bluetooth group so the preferred input changes.
            let route: 'built-in' | 'bluetooth-output' = 'built-in';
            const enumerateDevices = vi.fn().mockImplementation(async () => {
                const outputGroup = route === 'bluetooth-output' ? 'bluetooth' : 'built-in';
                return [
                    createMediaDevice('audioinput', 'default', 'built-in', 'Default - MacBook Pro Microphone'),
                    createMediaDevice('audioinput', 'built-in-mic', 'built-in', 'MacBook Pro Microphone'),
                    createMediaDevice('audioinput', 'bt-mic', 'bluetooth', 'Headset Microphone'),
                    createMediaDevice('audiooutput', 'default', outputGroup, 'Default - Output'),
                    createMediaDevice('audiooutput', 'built-in-speakers', 'built-in', 'MacBook Pro Speakers'),
                    createMediaDevice('audiooutput', 'bt-speakers', 'bluetooth', 'Headset'),
                ];
            });
            let deviceChangeHandler: (() => void) | undefined;
            Object.defineProperty(globalThis, 'navigator', {
                value: {
                    mediaDevices: {
                        getUserMedia,
                        enumerateDevices,
                        addEventListener: vi.fn((event: string, handler: () => void) => {
                            if (event === 'devicechange') {
                                deviceChangeHandler = handler;
                            }
                        }),
                        removeEventListener() {},
                    },
                },
                configurable: true,
            });
            const engine = new MediaEngine({ initialVideoEnabled: false }, () => {});
            engine.updateRoomState({
                hostCid: 'me',
                participants: [{ cid: 'me' }, { cid: 'peer-1' }],
            }, 'me');
            await engine.startLocalMedia();
            expect(getUserMedia).toHaveBeenCalledTimes(1);

            // Hold: release capture. The stream is now empty (non-null).
            await engine.suspendLocalMediaForHold();
            expect(engine.localStream?.getAudioTracks()[0]).toBeUndefined();

            // A device-change fires while held. It must NOT reacquire the mic.
            route = 'bluetooth-output';
            deviceChangeHandler?.();
            await flushPromises();
            expect(getUserMedia).toHaveBeenCalledTimes(1);
            expect(engine.localStream?.getAudioTracks()[0]).toBeUndefined();

            // Resume reacquires the mic per desired intent (sink unblocked).
            await engine.resumeLocalMediaFromHold(true, 'off');
            expect(getUserMedia).toHaveBeenCalledTimes(2);
            expect(engine.localStream?.getAudioTracks().length).toBe(1);

            engine.destroy();
        });

        // Defense in depth for the offer-driven reacquire: `applyRemoteOffer`
        // calls `startLocalMedia` when `localStream` is null. A held call must not
        // restart `getUserMedia` from that path.
        it('startLocalMedia is a no-op while held (offer-driven reacquire guard)', async () => {
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
            expect(getUserMedia).toHaveBeenCalledTimes(1);

            await engine.suspendLocalMediaForHold();
            // Force the null-stream branch the offer path can hit while held.
            engine.stopLocalMedia();
            expect(engine.localStream).toBeNull();

            const result = await engine.startLocalMedia();
            expect(result).toBeNull();
            expect(getUserMedia).toHaveBeenCalledTimes(1);

            engine.destroy();
        });

        // Core Invariant 2 / Phase 4 backstop: `flipCamera` acquires a fresh
        // camera track via getUserMedia. The session gates the toggle on
        // mediaRole==='held', but the engine must also refuse so a held call can
        // never grab the camera (defense in depth — parity with the
        // startLocalMedia / refreshLocal*Track backstops).
        it('flipCamera is a no-op while held (does NOT call getUserMedia)', async () => {
            const getUserMedia = vi.fn().mockResolvedValue(createMediaStream({ video: true }));
            const enumerateDevices = vi.fn().mockResolvedValue([
                createMediaDevice('videoinput', 'cam-front', 'front', 'Front Camera'),
                createMediaDevice('videoinput', 'cam-back', 'back', 'Back Camera'),
            ]);
            Object.defineProperty(globalThis, 'navigator', {
                value: {
                    mediaDevices: {
                        getUserMedia,
                        enumerateDevices,
                        addEventListener() {},
                        removeEventListener() {},
                    },
                },
                configurable: true,
            });
            const engine = new MediaEngine({}, () => {});
            await engine.startLocalMedia();
            // Two cameras detected -> a flip WOULD normally reacquire the camera.
            expect(engine.hasMultipleCameras).toBe(true);
            const callsAfterStart = getUserMedia.mock.calls.length;
            const facingBefore = engine.facingMode;

            await engine.suspendLocalMediaForHold();

            await engine.flipCamera();
            // No new getUserMedia, and facing intent is untouched at the engine
            // (the held session tracks desired facing; resume reapplies it).
            expect(getUserMedia.mock.calls.length).toBe(callsAfterStart);
            expect(engine.facingMode).toBe(facingBefore);
            expect(engine.localStream?.getVideoTracks()[0]).toBeUndefined();

            engine.destroy();
        });

        // Core Invariant 2 / Phase 4 backstop: a held call owns no capture,
        // including the display surface. `startScreenShare` must not reach
        // getDisplayMedia while held (the arbiter serializes screen-share
        // ownership to the foreground call).
        it('startScreenShare is a no-op while held (does NOT call getDisplayMedia)', async () => {
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
            const engine = new MediaEngine({}, () => {});
            await engine.startLocalMedia();
            // Sanity: a share WOULD be startable when not held.
            expect(engine.canScreenShare).toBe(true);

            await engine.suspendLocalMediaForHold();

            await engine.startScreenShare();
            expect(getDisplayMedia).not.toHaveBeenCalled();
            expect(engine.isScreenSharing).toBe(false);

            engine.destroy();
        });
    });
});
