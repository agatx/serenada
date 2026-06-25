import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MediaEngine } from '../../src/media/MediaEngine.js';

/**
 * Phase 2 independent-content (screen share) media-engine tests. These use a
 * richer fake RTCPeerConnection than the legacy MediaEngine.test.ts fake: it
 * mints a mid for EVERY pre-added transceiver in array order on a local offer
 * (so two video m-lines get distinct mids), materializes a configurable number
 * of remote video recv transceivers on a remote offer, and supports a glare
 * rollback hook.
 */

let trackSeq = 0;
function makeTrack(kind: 'audio' | 'video', label = ''): MediaStreamTrack {
    trackSeq += 1;
    return {
        id: `${kind}-${label || trackSeq}`,
        kind,
        enabled: true,
        muted: false,
        readyState: 'live',
        label,
        getSettings: () => ({}),
        applyConstraints: async () => {},
        stop() { (this as { readyState: string }).readyState = 'ended'; },
    } as unknown as MediaStreamTrack;
}

class FakeStream {
    private tracks: MediaStreamTrack[];
    constructor(tracks: MediaStreamTrack[] = []) { this.tracks = [...tracks]; }
    addTrack(t: MediaStreamTrack): void { this.tracks.push(t); }
    getAudioTracks(): MediaStreamTrack[] { return this.tracks.filter(t => t.kind === 'audio'); }
    getVideoTracks(): MediaStreamTrack[] { return this.tracks.filter(t => t.kind === 'video'); }
    getTracks(): MediaStreamTrack[] { return [...this.tracks]; }
}

class FakeSender {
    readonly replaceTrackCalls: Array<MediaStreamTrack | null> = [];
    failNextReplace = false;
    private params: RTCRtpSendParameters = { encodings: [{}] } as RTCRtpSendParameters;
    constructor(public track: MediaStreamTrack | null) {}
    async replaceTrack(track: MediaStreamTrack | null): Promise<void> {
        if (this.failNextReplace) {
            this.failNextReplace = false;
            throw new Error('replaceTrack rejected');
        }
        this.track = track;
        this.replaceTrackCalls.push(track);
    }
    getParameters(): RTCRtpSendParameters { return this.params; }
    async setParameters(p: RTCRtpSendParameters): Promise<void> { this.params = p; }
}

class FakeTransceiver {
    readonly receiver: RTCRtpReceiver;
    currentDirection: RTCRtpTransceiverDirection | null = null;
    constructor(
        public kind: 'audio' | 'video',
        public sender: FakeSender,
        public direction: RTCRtpTransceiverDirection,
        public mid: string | null = null,
        receiverTrackKind: 'audio' | 'video' | null = null,
    ) {
        this.receiver = { track: receiverTrackKind ? makeTrack(receiverTrackKind) : null } as RTCRtpReceiver;
    }
}

interface FakeOptions {
    /** Number of video m-lines a remote offer materializes (default 2). */
    remoteVideoMLines?: number;
}

class FakePeerConnection {
    readonly senders: FakeSender[] = [];
    readonly transceivers: FakeTransceiver[] = [];
    signalingState: RTCSignalingState = 'stable';
    iceConnectionState: RTCIceConnectionState = 'new';
    connectionState: RTCPeerConnectionState = 'new';
    remoteDescription: RTCSessionDescriptionInit | null = null;
    localDescription: RTCSessionDescriptionInit | null = null;
    createOfferCalls = 0;
    createAnswerCalls = 0;
    rollbackCalls = 0;
    closed = false;
    ontrack: ((event: RTCTrackEvent) => void) | null = null;
    oniceconnectionstatechange: (() => void) | null = null;
    onconnectionstatechange: (() => void) | null = null;
    onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
    onnegotiationneeded: (() => void) | null = null;
    onsignalingstatechange: (() => void) | null = null;

    constructor(_config: RTCConfiguration, private readonly opts: FakeOptions) {}

    addTrack(track: MediaStreamTrack): RTCRtpSender {
        const sender = new FakeSender(track);
        this.senders.push(sender);
        this.transceivers.push(new FakeTransceiver(track.kind as 'audio' | 'video', sender, 'sendrecv', null, track.kind as 'audio' | 'video'));
        return sender as unknown as RTCRtpSender;
    }
    addTransceiver(trackOrKind: MediaStreamTrack | 'audio' | 'video', init?: RTCRtpTransceiverInit): RTCRtpTransceiver {
        const isTrack = typeof trackOrKind !== 'string';
        const kind = (isTrack ? (trackOrKind as MediaStreamTrack).kind : trackOrKind) as 'audio' | 'video';
        const sender = new FakeSender(isTrack ? (trackOrKind as MediaStreamTrack) : null);
        const transceiver = new FakeTransceiver(kind, sender, init?.direction ?? 'sendrecv', null, kind);
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
    getTransceivers(): RTCRtpTransceiver[] { return this.transceivers as unknown as RTCRtpTransceiver[]; }
    /**
     * Injectable stats report. Tests push `inbound-rtp` entries (keyed by the
     * receiver track identity) to drive per-role liveness sampling. Defaults to
     * an empty report (matches the legacy fake behavior).
     */
    statsReport: Map<string, RTCStats> = new Map();
    getStatsCalls = 0;
    async getStats(): Promise<RTCStatsReport> {
        this.getStatsCalls += 1;
        return this.statsReport as unknown as RTCStatsReport;
    }

    /**
     * Set the cumulative inbound video `bytesReceived` for a role by writing an
     * `inbound-rtp` stat whose `trackIdentifier` is the role transceiver's
     * receiver track id. `role` index: video transceivers sorted by mid →
     * [0]=camera, [1]=content. `legacy` writes the single video transceiver.
     */
    setInboundVideoBytes(
        opts: { camera?: number; content?: number; legacy?: number },
        statOpts: { omitTrackIdentifier?: boolean } = {},
    ): void {
        const videoTransceivers = this.transceivers
            .filter(t => t.kind === 'video')
            .sort((a, b) => Number(a.mid) - Number(b.mid));
        const write = (transceiver: FakeTransceiver | undefined, bytes: number, key: string) => {
            const trackId = transceiver?.receiver?.track?.id;
            if (!trackId) return;
            const stat: Record<string, unknown> = {
                type: 'inbound-rtp', id: key, timestamp: Date.now(),
                kind: 'video', mid: transceiver?.mid, bytesReceived: bytes,
            };
            if (!statOpts.omitTrackIdentifier) {
                stat.trackIdentifier = trackId;
            }
            this.statsReport.set(key, stat as unknown as RTCStats);
        };
        if (opts.camera !== undefined) write(videoTransceivers[0], opts.camera, 'in-camera');
        if (opts.content !== undefined) write(videoTransceivers[1], opts.content, 'in-content');
        if (opts.legacy !== undefined) write(videoTransceivers[0], opts.legacy, 'in-legacy');
    }
    async createOffer(): Promise<RTCSessionDescriptionInit> {
        this.createOfferCalls += 1;
        return { type: 'offer', sdp: `offer-${this.createOfferCalls}` };
    }
    async createAnswer(): Promise<RTCSessionDescriptionInit> {
        this.createAnswerCalls += 1;
        return { type: 'answer', sdp: `answer-${this.createAnswerCalls}` };
    }
    async setLocalDescription(description: RTCSessionDescriptionInit): Promise<void> {
        if (description.type === 'rollback') {
            this.rollbackCalls += 1;
            this.localDescription = null;
            this.signalingState = 'stable';
            this.onsignalingstatechange?.();
            return;
        }
        if (description.type === 'offer') this.assignMidsInOrder();
        this.localDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable';
        if (description.type === 'answer') this.finalizeDirections();
        this.onsignalingstatechange?.();
    }
    async setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void> {
        if (description.type === 'offer') this.materializeRemoteOffer();
        this.remoteDescription = description;
        this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable';
        if (description.type === 'answer') this.finalizeDirections();
        this.onsignalingstatechange?.();
    }
    async addIceCandidate(): Promise<void> {}
    setConfiguration(): void {}

    /** Mint mids for every transceiver missing one, in array order. */
    private assignMidsInOrder(): void {
        let next = this.transceivers.filter(t => t.mid !== null).length;
        for (const transceiver of this.transceivers) {
            if (transceiver.mid === null) {
                transceiver.mid = String(next);
                next += 1;
            }
        }
    }

    /** Materialize recv transceivers for an inbound offer (audio + N video). */
    private materializeRemoteOffer(): void {
        const ensure = (kind: 'audio' | 'video', mid: string) => {
            if (this.transceivers.some(t => t.mid === mid)) return;
            // Reuse a matching null-mid local transceiver (the offer owner's
            // pre-created ones) so role binding maps onto the same objects.
            const existing = this.transceivers.find(t => t.mid === null && t.kind === kind);
            if (existing) { existing.mid = mid; return; }
            const sender = new FakeSender(null);
            this.senders.push(sender);
            this.transceivers.push(new FakeTransceiver(kind, sender, 'recvonly', mid, kind));
        };
        ensure('audio', '0');
        const videoCount = this.opts.remoteVideoMLines ?? 2;
        for (let i = 0; i < videoCount; i += 1) ensure('video', String(i + 1));
    }

    private finalizeDirections(): void {
        for (const transceiver of this.transceivers) {
            if (transceiver.mid !== null) transceiver.currentDirection = transceiver.direction;
        }
    }

    /**
     * Emit an inbound remote track on the transceiver bound to the given mid.
     * When `withStream` is false the track arrives with empty `event.streams`
     * (as remote audio commonly does), forcing the legacy `ontrack` aggregate
     * path to build/reuse the stream itself.
     */
    emitRemoteTrack(mid: string, withStream = true): void {
        const transceiver = this.transceivers.find(t => t.mid === mid);
        if (!transceiver) throw new Error(`no transceiver mid=${mid}`);
        const track = makeTrack(transceiver.kind, `remote-${mid}`);
        const streams = withStream
            ? [new FakeStream([track]) as unknown as MediaStream]
            : [];
        this.ontrack?.({ track, streams, transceiver } as unknown as RTCTrackEvent);
    }
}

async function flush(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
}

type Sent = { type: string; payload?: Record<string, unknown>; to?: string };

interface Harness {
    engine: MediaEngine;
    sent: Sent[];
    getDisplayMedia: ReturnType<typeof vi.fn>;
    pcOptions: FakeOptions;
}

function setupNavigator(opts: {
    video?: boolean;
    getDisplayMedia?: ReturnType<typeof vi.fn>;
} = {}): { getDisplayMedia: ReturnType<typeof vi.fn> } {
    const getDisplayMedia = opts.getDisplayMedia
        ?? vi.fn().mockResolvedValue(new FakeStream([makeTrack('video', 'display')]) as unknown as MediaStream);
    const getUserMedia = vi.fn().mockImplementation(async (constraints: MediaStreamConstraints) => {
        const tracks: MediaStreamTrack[] = [makeTrack('audio', 'mic')];
        if (constraints.video) tracks.push(makeTrack('video', 'cam'));
        return new FakeStream(tracks) as unknown as MediaStream;
    });
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
    return { getDisplayMedia };
}

describe('MediaEngine independent content', () => {
    const originalNavigator = globalThis.navigator;
    const originalDocument = globalThis.document;
    const originalWindow = (globalThis as Record<string, unknown>).window;
    const originalRtc = (globalThis as Record<string, unknown>).RTCPeerConnection;
    const originalSdp = (globalThis as Record<string, unknown>).RTCSessionDescription;
    const originalStream = (globalThis as Record<string, unknown>).MediaStream;
    let pcOptions: FakeOptions;

    beforeEach(() => {
        pcOptions = {};
        Object.defineProperty(globalThis, 'document', {
            value: { hidden: false, addEventListener() {}, removeEventListener() {} },
            configurable: true,
        });
        (globalThis as Record<string, unknown>).window = {
            addEventListener() {}, removeEventListener() {},
            setTimeout: (...a: Parameters<typeof setTimeout>) => setTimeout(...a),
            clearTimeout: (...a: Parameters<typeof clearTimeout>) => clearTimeout(...a),
            setInterval: (...a: Parameters<typeof setInterval>) => setInterval(...a),
            clearInterval: (...a: Parameters<typeof clearInterval>) => clearInterval(...a),
        };
        (globalThis as Record<string, unknown>).RTCPeerConnection = class extends FakePeerConnection {
            constructor(config: RTCConfiguration) { super(config, pcOptions); }
        };
        (globalThis as Record<string, unknown>).RTCSessionDescription = class {
            type: RTCSdpType; sdp?: string;
            constructor(init: RTCSessionDescriptionInit) { this.type = init.type; this.sdp = init.sdp; }
        };
        (globalThis as Record<string, unknown>).MediaStream = FakeStream;
    });

    afterEach(() => {
        vi.useRealTimers();
        Object.defineProperty(globalThis, 'navigator', { value: originalNavigator, configurable: true });
        Object.defineProperty(globalThis, 'document', { value: originalDocument, configurable: true });
        (globalThis as Record<string, unknown>).window = originalWindow;
        (globalThis as Record<string, unknown>).RTCPeerConnection = originalRtc;
        (globalThis as Record<string, unknown>).RTCSessionDescription = originalSdp;
        (globalThis as Record<string, unknown>).MediaStream = originalStream;
    });

    function makeEngine(config: Record<string, unknown>): Harness {
        const sent: Sent[] = [];
        const { getDisplayMedia } = setupNavigator();
        const engine = new MediaEngine(config, (type, payload, to) => sent.push({ type, payload, to }));
        return { engine, sent, getDisplayMedia, pcOptions };
    }

    /** Bring an offer-owner engine to a connected, role-bound peer. */
    async function joinAsOwnerCapable(h: Harness, remoteCid = 'zeta'): Promise<FakePeerConnection> {
        // local 'alpha' < 'zeta' so alpha is the offerer.
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha' },
                { cid: remoteCid, capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
            ],
        }, 'alpha');
        await vi.waitFor(() => {
            expect(h.sent.filter(m => m.type === 'offer')).toHaveLength(1);
        });
        const peer = h.engine.getPeerConnectionsMap().get(remoteCid) as unknown as FakePeerConnection;
        const offerId = h.sent.find(m => m.type === 'offer')?.payload?.offerId;
        h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: remoteCid, sdp: 'remote-answer', offerId } });
        await flush();
        return peer;
    }

    it('videoMediaEnabled=false negotiates no camera/content transceivers and answers offered video inactive', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true, videoMediaEnabled: false });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha' },
                { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
            ],
        }, 'alpha');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(0);

        // An offered video m-line is answered inactive.
        pcOptions.remoteVideoMLines = 1;
        h.engine.processSignalingMessage({ v: 1, type: 'offer', payload: { from: 'zeta', sdp: 'remote-offer', offerId: 'o1' } });
        await flush();
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video');
        expect(videoTransceivers.every(t => t.direction === 'inactive')).toBe(true);
    });

    it('a peer signaling videoMediaEnabled=false is treated as legacy (no independent path)', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha' },
                { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: false } },
            ],
        }, 'alpha');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        // Legacy single-video path → exactly one video transceiver.
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);
    });

    it('offer owner pre-creates exactly two video transceivers in camera/content order, no glare duplicates', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video');
        expect(videoTransceivers).toHaveLength(2);
        // First video transceiver is camera (created first), second is content.
        const sortedByMid = [...videoTransceivers].sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraMid = sortedByMid[0].mid;
        const contentMid = sortedByMid[1].mid;
        expect(cameraMid).not.toBe(contentMid);

        // Simulated glare: a remote offer arrives; role binding must not create
        // duplicate camera/content m-lines.
        h.engine.processSignalingMessage({ v: 1, type: 'offer', payload: { from: 'zeta', sdp: 'remote-offer', offerId: 'glare-1' } });
        await flush();
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(2);
    });

    it('legacy (non-capable) peer gets one video transceiver', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);
    });

    it('cameraModes=[] + videoMediaEnabled creates camera+content recv transceivers without requesting camera permission', async () => {
        const sent: Sent[] = [];
        const { getDisplayMedia } = setupNavigator();
        void getDisplayMedia;
        const getUserMedia = (globalThis.navigator.mediaDevices as { getUserMedia: ReturnType<typeof vi.fn> }).getUserMedia;
        const engine = new MediaEngine(
            { enableIndependentContentVideo: true, videoCaptureSupported: false, initialVideoEnabled: false },
            (type, payload, to) => sent.push({ type, payload, to }),
        );
        engine.updateSignalingConnected(true);
        await engine.startLocalMedia();
        engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha' },
                { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
            ],
        }, 'alpha');
        await flush();

        const peer = engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        // Offer owner still creates two video transceivers (camera + content)
        // for receive/content, but with no camera track and no camera request.
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(2);
        // getUserMedia was never called with a video constraint.
        const videoCalls = getUserMedia.mock.calls.filter((c: unknown[]) => (c[0] as MediaStreamConstraints).video);
        expect(videoCalls).toHaveLength(0);
        engine.destroy();
    });

    it('starting screen share broadcasts content_state after a viable attach, via replaceTrack with no renegotiation, leaving camera untouched', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraSender = videoTransceivers[0].sender;
        const contentSender = videoTransceivers[1].sender;
        const cameraTrackBefore = cameraSender.track;
        const offersBefore = peer.createOfferCalls;
        h.sent.length = 0;

        await h.engine.startScreenShare();
        await flush();

        const contentStates = h.sent.filter(m => m.type === 'content_state');
        expect(contentStates[0]?.payload).toMatchObject({ active: true, contentType: 'screenShare' });
        // Content sender got the display track via replaceTrack.
        expect(contentSender.replaceTrackCalls.at(-1)).toBe(contentSender.track);
        expect(contentSender.track).not.toBeNull();
        // Camera sender untouched, no renegotiation.
        expect(cameraSender.track).toBe(cameraTrackBefore);
        expect(peer.createOfferCalls).toBe(offersBefore);
        expect(h.engine.isScreenSharing).toBe(true);
    });

    it('full attach failure rolls back without a transient content_state flicker', async () => {
        // One legacy peer whose single-video swap fails → zero attached, none
        // pending → rollback. No active:true is sent, so receivers never show a
        // false sharing indicator.
        const h = makeEngine({ enableIndependentContentVideo: true });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await flush();
        const legacyPeer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        const videoSender = legacyPeer.senders.find(s => s.track?.kind === 'video');
        expect(videoSender).toBeDefined();
        if (videoSender) videoSender.failNextReplace = true;
        h.sent.length = 0;

        await h.engine.startScreenShare();
        await flush();

        const states = h.sent.filter(m => m.type === 'content_state');
        expect(states).toHaveLength(0);
        expect(h.engine.isScreenSharing).toBe(false);
    });

    it('replaceTrack rejection falls back to renegotiation instead of failing the share', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const contentSender = videoTransceivers[1].sender;
        contentSender.failNextReplace = true;
        const offersBefore = peer.createOfferCalls;
        h.sent.length = 0;

        await h.engine.startScreenShare();
        await flush();

        // Share still active (not failed) and a renegotiation offer was sent.
        expect(h.engine.isScreenSharing).toBe(true);
        expect(h.sent.find(m => m.type === 'content_state' && m.payload?.active === true)).toBeDefined();
        expect(peer.createOfferCalls).toBeGreaterThan(offersBefore);
    });

    it('share started before a peer content transceiver binds is held pending and attaches on bind', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        // local 'zeta' is the NON-owner ('alpha' < 'zeta' so alpha offers).
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                { cid: 'zeta' },
            ],
        }, 'zeta');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('alpha') as unknown as FakePeerConnection;
        // Non-owner has no pre-created content transceiver yet.
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(0);

        // Start share before the offer arrives → pending.
        await h.engine.startScreenShare();
        await flush();
        expect(h.engine.isScreenSharing).toBe(true);
        expect(h.sent.find(m => m.type === 'content_state' && m.payload?.active === true)).toBeDefined();

        // Now the remote offer arrives → roles bind, pending content attaches.
        h.engine.processSignalingMessage({ v: 1, type: 'offer', payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'o1' } });
        await flush();
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        expect(videoTransceivers).toHaveLength(2);
        const contentSender = videoTransceivers[1].sender;
        expect(contentSender.track).not.toBeNull();
    });

    it('displayTrack.onended runs the full stop path and emits content_state active:false', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const display = makeTrack('video', 'display');
        const getDisplayMedia = vi.fn().mockResolvedValue(new FakeStream([display]) as unknown as MediaStream);
        setupNavigator({ getDisplayMedia });
        const peer = await joinAsOwnerCapable(h);
        void peer;
        h.sent.length = 0;
        await h.engine.startScreenShare();
        await flush();
        expect(h.engine.isScreenSharing).toBe(true);

        // Simulate the browser "Stop sharing" control.
        (display.onended as () => void)?.();
        await flush();

        expect(h.engine.isScreenSharing).toBe(false);
        expect(h.sent.find(m => m.type === 'content_state' && m.payload?.active === false)).toBeDefined();
    });

    it('idempotent stop: programmatic stop + onended yield one active:false, one revision bump, one displayTrack.stop', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const display = makeTrack('video', 'display');
        const stopSpy = vi.spyOn(display, 'stop');
        const getDisplayMedia = vi.fn().mockResolvedValue(new FakeStream([display]) as unknown as MediaStream);
        setupNavigator({ getDisplayMedia });
        await joinAsOwnerCapable(h);
        await h.engine.startScreenShare();
        await flush();
        const revisionAfterStart = h.engine.lastContentRevision;
        h.sent.length = 0;

        await h.engine.stopScreenShare();
        // The onended handler (fired by display.stop()) re-enters the stop path.
        (display.onended as (() => void) | null)?.();
        await flush();

        const activeFalse = h.sent.filter(m => m.type === 'content_state' && m.payload?.active === false);
        expect(activeFalse).toHaveLength(1);
        expect(h.engine.lastContentRevision).toBe(revisionAfterStart + 1);
        expect(stopSpy).toHaveBeenCalledTimes(1);
    });

    it('ontrack routes camera and content tracks to separate streams by persisted role', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraMid = videoTransceivers[0].mid as string;
        const contentMid = videoTransceivers[1].mid as string;

        peer.emitRemoteTrack(cameraMid);
        peer.emitRemoteTrack(contentMid);
        await flush();

        const cameraStream = h.engine.getRemoteCameraStream('zeta');
        const contentStream = h.engine.getRemoteContentStream('zeta');
        expect(cameraStream).toBeDefined();
        expect(contentStream).toBeDefined();
        expect(cameraStream).not.toBe(contentStream);
        // Legacy getRemoteStream returns the audio+camera aggregate (the camera
        // track is merged into it, never the content track).
        const aggregate = h.engine.getRemoteStream('zeta');
        const cameraTrackId = cameraStream?.getVideoTracks()[0]?.id;
        const contentTrackId = contentStream?.getVideoTracks()[0]?.id;
        expect(aggregate?.getTracks().some(t => t.id === cameraTrackId)).toBe(true);
        expect(aggregate?.getTracks().some(t => t.id === contentTrackId)).toBe(false);
    });

    it('role binding survives a simulated glare rollback (roles not recomputed)', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraTransceiver = videoTransceivers[0];
        const contentTransceiver = videoTransceivers[1];

        // A late remote offer (glare). Role binding must keep the same objects.
        h.engine.processSignalingMessage({ v: 1, type: 'offer', payload: { from: 'zeta', sdp: 'remote-offer', offerId: 'glare' } });
        await flush();

        peer.emitRemoteTrack(cameraTransceiver.mid as string);
        peer.emitRemoteTrack(contentTransceiver.mid as string);
        await flush();

        // Camera mid still routes to camera stream, content mid to content.
        expect(h.engine.getRemoteCameraStream('zeta')).toBeDefined();
        expect(h.engine.getRemoteContentStream('zeta')).toBeDefined();
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(2);
    });

    it('videoMediaEnabled=false → startScreenShare is a no-op returning without content_state', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true, videoMediaEnabled: false });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta', capabilities: { independentContentVideo: true } }],
        }, 'alpha');
        await flush();
        h.sent.length = 0;

        await h.engine.startScreenShare();
        await flush();

        expect(h.engine.isScreenSharing).toBe(false);
        expect(h.sent.filter(m => m.type === 'content_state')).toHaveLength(0);
    });

    it('capable ANSWERER whose content transceiver binds mid-share attaches the active content track', async () => {
        // Finding 1 regression: a capable peer created AFTER the local user is
        // already screen sharing, on the ANSWERER side. `setupIndependentPeerTracks`
        // early-returns for the answerer so `pendingContentAttach` is never set;
        // the content track must still attach when the peer's offer binds its
        // content transceiver. local 'zeta' answers ('alpha' < 'zeta' offers).
        const h = makeEngine({ enableIndependentContentVideo: true });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        // Start the share with NO peers present (start-and-wait). This is the key
        // difference from the existing "held pending" test, where the peer already
        // exists when the share starts (so the START path sets pendingContentAttach).
        await h.engine.startScreenShare();
        await flush();
        expect(h.engine.isScreenSharing).toBe(true);
        expect(h.engine.getPeerConnectionsMap().size).toBe(0);

        // A capable peer now appears (mid-share). As the answerer we pre-create
        // nothing, so pendingContentAttach stays false through peer creation.
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [
                { cid: 'alpha', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                { cid: 'zeta' },
            ],
        }, 'zeta');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('alpha') as unknown as FakePeerConnection;
        expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(0);

        // The peer's offer arrives → roles bind → content track must attach even
        // though pendingContentAttach was never set on this answerer peer.
        h.engine.processSignalingMessage({ v: 1, type: 'offer', payload: { from: 'alpha', sdp: 'remote-offer', offerId: 'o1' } });
        await flush();
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        expect(videoTransceivers).toHaveLength(2);
        const contentSender = videoTransceivers[1].sender;
        // The active share's display track is attached to the content sender.
        expect(contentSender.track).not.toBeNull();
        expect(contentSender.replaceTrackCalls.at(-1)).toBe(contentSender.track);
    });

    it('replaceTrack-reject fallback re-attaches the content track after the forced renegotiation (sender non-empty)', async () => {
        // Finding 2 regression: when replaceTrack(displayTrack) rejects for a
        // capable peer, forcing renegotiation alone leaves the content sender
        // empty. A durable pendingContentAttach + post-negotiation attach must
        // re-fill the content sender once the m-line re-binds.
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const contentSender = videoTransceivers[1].sender;
        // First replaceTrack(displayTrack) rejects → forced renegotiation.
        contentSender.failNextReplace = true;
        const offersBefore = peer.createOfferCalls;
        h.sent.length = 0;

        await h.engine.startScreenShare();
        await flush();

        // Share stayed active and a renegotiation offer was forced; the content
        // sender is still empty at this point (the rejected replace left it null).
        expect(h.engine.isScreenSharing).toBe(true);
        expect(peer.createOfferCalls).toBeGreaterThan(offersBefore);
        expect(contentSender.track).toBeNull();

        // The remote answer to the forced renegotiation arrives → the owner
        // post-negotiation attach path re-attaches the content track (the
        // sender's failNextReplace was already consumed, so this replace succeeds).
        const renegOffer = h.sent.filter(m => m.type === 'offer').at(-1);
        const renegOfferId = renegOffer?.payload?.offerId;
        expect(renegOfferId).toBeDefined();
        h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: 'zeta', sdp: 'reneg-answer', offerId: renegOfferId } });
        await flush();

        // Content sender now carries the display track (no longer empty).
        expect(contentSender.track).not.toBeNull();
        expect(contentSender.track?.kind).toBe('video');
    });

    it('stop does not touch camera tracks for capable peers', async () => {
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraSender = videoTransceivers[0].sender;
        await h.engine.startScreenShare();
        await flush();
        const cameraTrackDuringShare = cameraSender.track;
        cameraSender.replaceTrackCalls.length = 0;

        await h.engine.stopScreenShare();
        await flush();

        // Content detached (replaceTrack(null)), camera never touched.
        expect(videoTransceivers[1].sender.replaceTrackCalls.at(-1)).toBeNull();
        expect(cameraSender.replaceTrackCalls).toHaveLength(0);
        expect(cameraSender.track).toBe(cameraTrackDuringShare);
    });

    it('independent peer: incoming audio then camera keeps audio in the aggregate (not dropped) and camera in remoteCameraStreams', async () => {
        // BUG 1 regression: for an independent peer, audio arrives via the legacy
        // ontrack path (empty event.streams) and builds peer.remoteStream holding
        // the audio track. When the camera track later arrives on the independent
        // path it must MERGE into that aggregate, not REPLACE it — otherwise
        // RemoteAudioSink (which follows remoteStreams) loses the audio track.
        const h = makeEngine({ enableIndependentContentVideo: true });
        const peer = await joinAsOwnerCapable(h);
        const videoTransceivers = peer.transceivers.filter(t => t.kind === 'video').sort((a, b) => Number(a.mid) - Number(b.mid));
        const cameraMid = videoTransceivers[0].mid as string;
        const audioTransceiver = peer.transceivers.find(t => t.kind === 'audio');
        const audioMid = audioTransceiver?.mid as string;
        expect(audioMid).toBeDefined();

        // 1) Audio arrives first with empty event.streams (legacy aggregate path).
        peer.emitRemoteTrack(audioMid, false);
        await flush();
        const audioTrackId = h.engine.getRemoteStream('zeta')?.getAudioTracks()[0]?.id;
        expect(audioTrackId).toBeDefined();

        // 2) Camera arrives on the independent path.
        peer.emitRemoteTrack(cameraMid);
        await flush();

        // Aggregate (remoteStreams / getRemoteStream) still contains the audio
        // track AND now also the camera track — audio is never dropped.
        const aggregate = h.engine.getRemoteStream('zeta');
        const cameraStream = h.engine.getRemoteCameraStream('zeta');
        const cameraTrackId = aggregate?.getVideoTracks()[0]?.id;
        expect(aggregate?.getTracks().some(t => t.id === audioTrackId)).toBe(true);
        expect(aggregate?.getVideoTracks()).toHaveLength(1);
        // remoteCameraStreams holds the camera-specific stream (the camera track).
        expect(cameraStream?.getVideoTracks().some(t => t.id === cameraTrackId)).toBe(true);
    });

    it('legacy peer joining AFTER an independent share started carries the content (display) track, not the camera', async () => {
        // BUG 2 regression: with the flag on locally and a share already active, a
        // late NON-capable peer gets localStream (camera) added in getOrCreatePeer.
        // startScreenShareIndependent already ran, so it never swaps this late
        // peer's single video sender. The peer-creation path must route the active
        // share onto its single video sender via the legacy swap.
        const h = makeEngine({ enableIndependentContentVideo: true });
        h.engine.updateSignalingConnected(true);
        await h.engine.startLocalMedia();
        // Start the share with no peers present (start-and-wait), so the late
        // legacy joiner is created strictly after the share is active.
        await h.engine.startScreenShare();
        await flush();
        expect(h.engine.isScreenSharing).toBe(true);
        const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
        expect(displayTrackId).toBeDefined();

        // A legacy (non-capable) peer now appears mid-share.
        h.engine.updateRoomState({
            hostCid: 'alpha',
            participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
        }, 'alpha');
        await flush();
        const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
        const videoSender = peer.senders.find(s => s.track?.kind === 'video');
        expect(videoSender).toBeDefined();
        // The single video sender carries the display/content track, not the camera.
        expect(videoSender?.track?.id).toBe(displayTrackId);
        expect(videoSender?.track?.label).toBe('display');
    });

    // --- Camera controls remain live during an INDEPENDENT content share ---
    //
    // Root cause (Codex P1): in legacy mode the camera track IS the display
    // track, so MediaEngine suppresses camera controls (release/reacquire/flip/
    // stall-recovery) while sharing. In independent mode camera and content are
    // separate tracks/transceivers, so camera controls MUST keep working during
    // an active content share — and toggling the camera off MUST actually stop
    // sending camera to capable peers (privacy), while content is unaffected.
    describe('camera controls during an independent content share', () => {
        /** Bound video senders for the capable owner peer, [camera, content]. */
        function videoSenders(peer: FakePeerConnection): { camera: FakeSender; content: FakeSender } {
            const sorted = peer.transceivers
                .filter(t => t.kind === 'video')
                .sort((a, b) => Number(a.mid) - Number(b.mid));
            return { camera: sorted[0].sender, content: sorted[1].sender };
        }

        it('setVideoEnabled(false) (releaseVideoTrack) stops the camera to capable peers; content sender untouched', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const peer = await joinAsOwnerCapable(h);
            await h.engine.startScreenShare();
            await flush();
            const { camera, content } = videoSenders(peer);
            // Sanity: sharing, camera carries a live track, content carries display.
            expect(h.engine.isScreenSharing).toBe(true);
            expect(camera.track).not.toBeNull();
            const displayTrack = content.track;
            expect(displayTrack).not.toBeNull();
            content.replaceTrackCalls.length = 0;

            // Disable the camera mid-share (this is what session.setVideoEnabled(false) does).
            await h.engine.releaseVideoTrack();
            await flush();

            // Camera actually stopped to the capable peer (sender track cleared).
            expect(camera.track).toBeNull();
            // Local camera track is gone from localStream (no longer captured/sent).
            expect(h.engine.localStream?.getVideoTracks() ?? []).toHaveLength(0);
            // Content share unaffected: still the same display track, never touched.
            expect(content.track).toBe(displayTrack);
            expect(content.replaceTrackCalls).toHaveLength(0);
            expect(h.engine.isScreenSharing).toBe(true);
        });

        it('setVideoEnabled(true) (reacquireVideoTrack) re-enables the camera during a share; content untouched', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const peer = await joinAsOwnerCapable(h);
            await h.engine.startScreenShare();
            await flush();
            const { camera, content } = videoSenders(peer);
            const displayTrack = content.track;

            // Camera off, then back on, all mid-share.
            await h.engine.releaseVideoTrack();
            await flush();
            expect(camera.track).toBeNull();
            content.replaceTrackCalls.length = 0;

            await h.engine.reacquireVideoTrack();
            await flush();

            // Camera re-attached to the capable peer's camera sender.
            expect(camera.track).not.toBeNull();
            expect(camera.track?.kind).toBe('video');
            expect(h.engine.localStream?.getVideoTracks() ?? []).toHaveLength(1);
            // Content share never touched throughout.
            expect(content.track).toBe(displayTrack);
            expect(content.replaceTrackCalls).toHaveLength(0);
            expect(h.engine.isScreenSharing).toBe(true);
        });

        it('flipCamera operates on the camera track during a share; content sender untouched', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const peer = await joinAsOwnerCapable(h);
            h.engine.hasMultipleCameras = true;
            await h.engine.startScreenShare();
            await flush();
            const { camera, content } = videoSenders(peer);
            const cameraTrackBefore = camera.track;
            const displayTrack = content.track;
            content.replaceTrackCalls.length = 0;
            expect(h.engine.facingMode).toBe('user');

            await h.engine.flipCamera();
            await flush();

            // Facing mode flipped and a NEW camera track was swapped onto the camera sender.
            expect(h.engine.facingMode).toBe('environment');
            expect(camera.track).not.toBeNull();
            expect(camera.track).not.toBe(cameraTrackBefore);
            // Content share unaffected.
            expect(content.track).toBe(displayTrack);
            expect(content.replaceTrackCalls).toHaveLength(0);
            expect(h.engine.isScreenSharing).toBe(true);
        });

        it('local-video stall recovery refreshes the camera during a share; content untouched', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const peer = await joinAsOwnerCapable(h);
            await h.engine.startScreenShare();
            await flush();
            const { camera, content } = videoSenders(peer);
            const displayTrack = content.track;
            content.replaceTrackCalls.length = 0;

            // Simulate a stalled camera track (ended), then force a recovery pass.
            const stalledCamera = h.engine.localStream?.getVideoTracks()[0];
            expect(stalledCamera).toBeDefined();
            (stalledCamera as unknown as { readyState: string }).readyState = 'ended';
            // refreshLocalVideoTrack is private; drive it via the documented sleep-resume path.
            await (h.engine as unknown as {
                refreshLocalVideoTrack(reason: string, force?: boolean): Promise<boolean>;
            }).refreshLocalVideoTrack('sleep-resume', true);
            await flush();

            // Camera recovered to a fresh live track on the camera sender.
            expect(camera.track).not.toBeNull();
            expect(camera.track).not.toBe(stalledCamera);
            expect(camera.track?.readyState).toBe('live');
            // Content share unaffected throughout.
            expect(content.track).toBe(displayTrack);
            expect(content.replaceTrackCalls).toHaveLength(0);
            expect(h.engine.isScreenSharing).toBe(true);
        });
    });

    // --- Legacy (flag OFF) suppression-during-share is unchanged ---
    //
    // In the legacy single-video model the camera track IS the display track, so
    // camera controls MUST stay suppressed while sharing (touching the single
    // video sender would clobber the share / restore camera mid-share).
    describe('legacy single-video screen share suppresses camera controls (flag off)', () => {
        async function joinLegacy(h: Harness): Promise<FakePeerConnection> {
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer')).toHaveLength(1);
            });
            const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            const offerId = h.sent.find(m => m.type === 'offer')?.payload?.offerId;
            h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: 'zeta', sdp: 'remote-answer', offerId } });
            await flush();
            return peer;
        }

        it('releaseVideoTrack/reacquireVideoTrack/flipCamera are no-ops while a legacy share is active', async () => {
            const h = makeEngine({}); // flag OFF
            const peer = await joinLegacy(h);
            h.engine.hasMultipleCameras = true;
            // Legacy share: the single video sender now carries the display track.
            await h.engine.startScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(true);
            const videoSender = peer.senders.find(s => s.track?.kind === 'video');
            const displayTrack = videoSender?.track ?? null;
            expect(displayTrack).not.toBeNull();
            const facingModeBefore = h.engine.facingMode;
            if (videoSender) videoSender.replaceTrackCalls.length = 0;

            // All camera controls must be suppressed (the display IS the video track).
            await h.engine.releaseVideoTrack();
            await h.engine.reacquireVideoTrack();
            await h.engine.flipCamera();
            await flush();

            // Single video sender still carries the SAME display track; nothing swapped.
            expect(videoSender?.track).toBe(displayTrack);
            expect(videoSender?.replaceTrackCalls ?? []).toHaveLength(0);
            expect(h.engine.facingMode).toBe(facingModeBefore);
            expect(h.engine.isScreenSharing).toBe(true);
        });
    });

    // --- Mixed mesh: camera ops mid-share must not clobber a legacy peer's content ---
    //
    // Codex P1: with the flag ON locally and a screen share active, a capable peer
    // receives content on its separate content m-line while a non-capable (legacy)
    // peer receives content via its SINGLE video sender (camera preempted on that
    // connection — design: Mixed Mesh Rooms). A later camera op during the share
    // (releaseVideoTrack/reacquireVideoTrack/flipCamera/stall-recovery) routes
    // through `replaceVideoTrackOnAllPeers`. Capable peers go to their camera role
    // sender; the legacy peer must be SKIPPED (its single sender keeps the display
    // track). The bug: the legacy peer fell through to the generic single-sender
    // replacement and got its content sender overwritten with the camera track (or
    // null), breaking the share for the mixed-version peer.
    describe('mixed mesh: camera ops mid-share preserve the legacy peer content track', () => {
        /** Bound video senders for the capable owner peer, [camera, content]. */
        function capableVideoSenders(peer: FakePeerConnection): { camera: FakeSender; content: FakeSender } {
            const sorted = peer.transceivers
                .filter(t => t.kind === 'video')
                .sort((a, b) => Number(a.mid) - Number(b.mid));
            return { camera: sorted[0].sender, content: sorted[1].sender };
        }

        /** Single video sender for the legacy peer. */
        function legacyVideoSender(peer: FakePeerConnection): FakeSender {
            const sender = peer.senders.find(s => s.track?.kind === 'video');
            if (!sender) throw new Error('legacy peer has no video sender');
            return sender;
        }

        /**
         * Mixed mesh as the offer owner: local 'alpha' offers to a capable peer
         * 'zeta' and a legacy peer 'bravo' (alpha < both). Both peers answer.
         */
        async function joinMixedMesh(h: Harness): Promise<{ capable: FakePeerConnection; legacy: FakePeerConnection }> {
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                    { cid: 'bravo' }, // legacy: no capabilities/mediaPolicy advertised
                ],
            }, 'alpha');
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'zeta')).toHaveLength(1);
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'bravo')).toHaveLength(1);
            });
            for (const cid of ['zeta', 'bravo']) {
                const offerId = h.sent.find(m => m.type === 'offer' && m.to === cid)?.payload?.offerId;
                h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: cid, sdp: 'remote-answer', offerId } });
            }
            await flush();
            const capable = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            const legacy = h.engine.getPeerConnectionsMap().get('bravo') as unknown as FakePeerConnection;
            return { capable, legacy };
        }

        it('setVideoEnabled(false) + flipCamera mid-share leave the legacy peer carrying the display track', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const { capable, legacy } = await joinMixedMesh(h);
            h.engine.hasMultipleCameras = true;

            await h.engine.startScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(true);

            const { camera: capableCamera, content: capableContent } = capableVideoSenders(capable);
            const legacySender = legacyVideoSender(legacy);
            const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
            expect(displayTrackId).toBeDefined();
            // Sanity: capable peer gets content on its separate sender; the legacy
            // peer's single sender carries the display track.
            expect(capableContent.track?.id).toBe(displayTrackId);
            expect(legacySender.track?.id).toBe(displayTrackId);
            expect(legacySender.track?.label).toBe('display');
            expect(capableCamera.track).not.toBeNull();
            const capableContentTrack = capableContent.track;
            capableContent.replaceTrackCalls.length = 0;
            legacySender.replaceTrackCalls.length = 0;

            // 1) Disable the camera mid-share.
            await h.engine.releaseVideoTrack();
            await flush();
            // Capable peer's camera sender cleared; its content sender untouched.
            expect(capableCamera.track).toBeNull();
            expect(capableContent.track).toBe(capableContentTrack);
            expect(capableContent.replaceTrackCalls).toHaveLength(0);
            // Legacy peer STILL carries the display track (not clobbered).
            expect(legacySender.track?.id).toBe(displayTrackId);
            expect(legacySender.track?.label).toBe('display');
            expect(legacySender.replaceTrackCalls).toHaveLength(0);

            // 2) Re-enable the camera, then flip it — still mid-share.
            await h.engine.reacquireVideoTrack();
            await flush();
            await h.engine.flipCamera();
            await flush();
            // Capable peer's camera reflects the flip; content sender untouched.
            expect(h.engine.facingMode).toBe('environment');
            expect(capableCamera.track).not.toBeNull();
            expect(capableContent.track).toBe(capableContentTrack);
            expect(capableContent.replaceTrackCalls).toHaveLength(0);
            // Legacy peer's single sender NEVER touched throughout the camera ops.
            expect(legacySender.track?.id).toBe(displayTrackId);
            expect(legacySender.track?.label).toBe('display');
            expect(legacySender.replaceTrackCalls).toHaveLength(0);
            expect(h.engine.isScreenSharing).toBe(true);
        });

        it('after stopScreenShare the legacy peer camera is restored and a later toggle affects it', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const { legacy } = await joinMixedMesh(h);
            const legacySender = legacyVideoSender(legacy);
            const cameraTrackId = legacySender.track?.id;
            expect(cameraTrackId).toBeDefined();
            expect(legacySender.track?.label).toBe('cam');

            await h.engine.startScreenShare();
            await flush();
            const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
            expect(legacySender.track?.id).toBe(displayTrackId);

            // Stop the share → restoreLegacyPeerCameraTrack puts the camera back.
            await h.engine.stopScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(false);
            expect(legacySender.track?.id).toBe(cameraTrackId);
            expect(legacySender.track?.label).toBe('cam');

            // Camera ops now affect the legacy peer again (no share suppressing them).
            legacySender.replaceTrackCalls.length = 0;
            await h.engine.releaseVideoTrack();
            await flush();
            expect(legacySender.track).toBeNull();
            expect(legacySender.replaceTrackCalls).toContain(null);
        });
    });

    // --- FIX 1: capability-transition slot handling ---
    //
    // A peer can be created LEGACY before its capabilities arrive (an early offer
    // or a peer_joined with no caps). The later cap-bearing room_state only
    // updates the stored room caps; the existing peer connection stays immutably
    // legacy → a late-announced CAPABLE peer never negotiates/binds the content
    // transceiver. FIX: when a peer's computed independent capability flips, the
    // peer is recreated so it re-runs role binding with the correct camera/content
    // m-line layout (deterministic offer owner re-offers). Only on a real flip,
    // and inert when the flag is off (capability is always false → never flips).
    describe('FIX 1: capability-transition slot handling', () => {
        it('a legacy peer whose caps later make it capable is recreated and negotiates a content transceiver', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            // 1) zeta appears with NO capabilities → built LEGACY (one video m-line).
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await flush();
            const legacyPeer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            expect(legacyPeer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);
            expect(legacyPeer.closed).toBe(false);

            // 2) room_state now carries zeta's capabilities → capability flips
            // legacy→capable → the peer is recreated with camera+content m-lines.
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                ],
            }, 'alpha');
            await flush();

            // Old (legacy) peer connection was closed; a fresh one replaced it.
            expect(legacyPeer.closed).toBe(true);
            const capablePeer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            expect(capablePeer).not.toBe(legacyPeer);
            // The offer owner pre-created camera + content video transceivers.
            expect(capablePeer.transceivers.filter(t => t.kind === 'video')).toHaveLength(2);
            // The deterministic offer owner re-offered after the recreate.
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'zeta')).not.toHaveLength(0);
            });
        });

        it('an active share re-attaches to the content sender of a peer recreated on a capability flip', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            // zeta first appears legacy, then a share starts, THEN caps arrive.
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await flush();
            await h.engine.startScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(true);
            const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
            expect(displayTrackId).toBeDefined();

            // Caps arrive mid-share → recreate as capable. The in-progress share
            // must re-attach via the pending-content mechanism: the owner pre-
            // creates the content transceiver with pendingContentAttach set (live
            // localContentTrack), and attachPendingLocalTracks fills it.
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                ],
            }, 'alpha');
            await flush();

            const capablePeer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            const videoTransceivers = capablePeer.transceivers
                .filter(t => t.kind === 'video')
                .sort((a, b) => Number(a.mid) - Number(b.mid));
            expect(videoTransceivers).toHaveLength(2);
            const contentSender = videoTransceivers[1].sender;
            // The active share's display track is on the recreated peer's content sender.
            expect(contentSender.track?.id).toBe(displayTrackId);
            expect(contentSender.track?.label).toBe('display');
            expect(h.engine.isScreenSharing).toBe(true);
        });

        it('a room_state update that does NOT change capability does not recreate the peer (no churn)', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const peer = await joinAsOwnerCapable(h);
            expect(peer.closed).toBe(false);
            const offersBefore = h.sent.filter(m => m.type === 'offer').length;

            // Re-send the same capabilities (still capable) → no flip → no recreate.
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                ],
            }, 'alpha');
            await flush();

            // Same peer connection (not closed/replaced) and no extra offer churn.
            expect(peer.closed).toBe(false);
            expect(h.engine.getPeerConnectionsMap().get('zeta')).toBe(peer as unknown as RTCPeerConnection);
            expect(h.sent.filter(m => m.type === 'offer').length).toBe(offersBefore);
        });

        it('flag OFF: caps arriving never recreate the peer (capability is always false → never flips)', async () => {
            const h = makeEngine({}); // flag OFF
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await flush();
            const peer = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);

            // Same caps that flip the peer to capable when the flag is ON. With the
            // flag OFF isPeerIndependentCapable is always false → no flip → no recreate.
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                ],
            }, 'alpha');
            await flush();

            expect(peer.closed).toBe(false);
            expect(h.engine.getPeerConnectionsMap().get('zeta')).toBe(peer as unknown as RTCPeerConnection);
            // Still the legacy single-video layout (never rebuilt as camera+content).
            expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);
        });
    });

    // --- FIX 2: legacy-peer content sender encoding policy in a mixed mesh ---
    //
    // In a mixed mesh (flag on, a non-capable peer) the legacy peer's single video
    // sender carries the content (display) track during a share, but in
    // independent mode isLegacyScreenSharing is false, so without the fix it keeps
    // the CAMERA sender encoding. FIX: apply the conservative content profile (the
    // same maxBitrate/maxFramerate used on capable peers' content transceiver) to
    // that sender during the share, and restore camera params when it goes back to
    // carrying the camera.
    describe('FIX 2: legacy peer content sender encoding policy', () => {
        function legacyVideoSender(peer: FakePeerConnection): FakeSender {
            const sender = peer.senders.find(s => s.track?.kind === 'video');
            if (!sender) throw new Error('legacy peer has no video sender');
            return sender;
        }

        async function joinMixedMesh(h: Harness): Promise<{ capable: FakePeerConnection; legacy: FakePeerConnection }> {
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                    { cid: 'bravo' }, // legacy
                ],
            }, 'alpha');
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'zeta')).toHaveLength(1);
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'bravo')).toHaveLength(1);
            });
            for (const cid of ['zeta', 'bravo']) {
                const offerId = h.sent.find(m => m.type === 'offer' && m.to === cid)?.payload?.offerId;
                h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: cid, sdp: 'remote-answer', offerId } });
            }
            await flush();
            const capable = h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection;
            const legacy = h.engine.getPeerConnectionsMap().get('bravo') as unknown as FakePeerConnection;
            return { capable, legacy };
        }

        it('the legacy peer content sender gets the content encoding profile during a share, and reverts to camera params after stop', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const { capable, legacy } = await joinMixedMesh(h);
            const legacySender = legacyVideoSender(legacy);
            // Pre-share: the legacy sender carries the camera with no content caps.
            expect(legacySender.track?.label).toBe('cam');
            const beforeEncoding = legacySender.getParameters().encodings[0];
            expect(beforeEncoding.maxBitrate).toBeUndefined();
            expect(beforeEncoding.maxFramerate).toBeUndefined();

            await h.engine.startScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(true);

            // The legacy single sender now carries the content (display) track with
            // the SCREEN-CONTENT encoding profile (NOT the camera profile).
            // CONTENT_* are module-private in MediaEngine.ts; mirror their values
            // here (kept in sync via the capable-peer cross-check below).
            const CONTENT_MAX_BITRATE = 1_500_000;
            const CONTENT_MAX_FRAMERATE = 5;
            const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
            expect(legacySender.track?.id).toBe(displayTrackId);
            const duringEncoding = legacySender.getParameters().encodings[0];
            expect(duringEncoding.maxBitrate).toBe(CONTENT_MAX_BITRATE);
            expect(duringEncoding.maxFramerate).toBe(CONTENT_MAX_FRAMERATE);

            // The capable peer's content transceiver uses the same profile — assert
            // the legacy sender matches it (same conservative content params).
            const capableContentSender = capable.transceivers
                .filter(t => t.kind === 'video')
                .sort((a, b) => Number(a.mid) - Number(b.mid))[1].sender;
            const capableEncoding = capableContentSender.getParameters().encodings[0];
            expect(duringEncoding.maxBitrate).toBe(capableEncoding.maxBitrate);
            expect(duringEncoding.maxFramerate).toBe(capableEncoding.maxFramerate);

            // Stop the share → the legacy sender carries the camera again and the
            // content encoding overrides are dropped (camera params restored).
            await h.engine.stopScreenShare();
            await flush();
            expect(h.engine.isScreenSharing).toBe(false);
            expect(legacySender.track?.label).toBe('cam');
            const afterEncoding = legacySender.getParameters().encodings[0];
            expect(afterEncoding.maxBitrate).toBeUndefined();
            expect(afterEncoding.maxFramerate).toBeUndefined();
        });
    });

    // GAP 1: per-peer attach-failure isolation. The independent share rolls back
    // only when attachedCount===0 && pendingCount===0. With two capable peers,
    // one peer's replaceTrack failure must NOT roll back the share, must NOT tear
    // down that peer, and the healthy peer must still carry the content track. The
    // failed peer is marked for recovery (pending re-attach + forced renegotiation).
    describe('per-peer attach-failure isolation (independent content)', () => {
        /** Bound [camera, content] senders for a capable peer (sorted by mid). */
        function capableSenders(peer: FakePeerConnection): { camera: FakeSender; content: FakeSender } {
            const sorted = peer.transceivers
                .filter(t => t.kind === 'video')
                .sort((a, b) => Number(a.mid) - Number(b.mid));
            return { camera: sorted[0].sender, content: sorted[1].sender };
        }

        /**
         * Owner ('alpha') joined with TWO capable peers ('yankee', 'zeta', both
         * > alpha so alpha offers to both). Both answer → camera+content bound.
         */
        async function joinTwoCapable(h: Harness): Promise<{ a: FakePeerConnection; b: FakePeerConnection }> {
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [
                    { cid: 'alpha' },
                    { cid: 'yankee', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                    { cid: 'zeta', capabilities: { independentContentVideo: true }, mediaPolicy: { videoMediaEnabled: true } },
                ],
            }, 'alpha');
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'yankee')).toHaveLength(1);
                expect(h.sent.filter(m => m.type === 'offer' && m.to === 'zeta')).toHaveLength(1);
            });
            for (const cid of ['yankee', 'zeta']) {
                const offerId = h.sent.find(m => m.type === 'offer' && m.to === cid)?.payload?.offerId;
                h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: cid, sdp: 'remote-answer', offerId } });
            }
            await flush();
            return {
                a: h.engine.getPeerConnectionsMap().get('yankee') as unknown as FakePeerConnection,
                b: h.engine.getPeerConnectionsMap().get('zeta') as unknown as FakePeerConnection,
            };
        }

        it('one capable peer content-attach failure does not roll back the share or tear down the peer; healthy peer carries the track', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            const { a: peerA, b: peerB } = await joinTwoCapable(h);
            const aSenders = capableSenders(peerA);
            const bSenders = capableSenders(peerB);
            const aCameraTrackBefore = aSenders.camera.track;
            const offersToABefore = peerA.createOfferCalls;
            // Force peer A's content replaceTrack to FAIL; peer B succeeds.
            aSenders.content.failNextReplace = true;
            h.sent.length = 0;

            await h.engine.startScreenShare();
            await flush();

            // Share stays active (NOT rolled back): content_state active:true and
            // no active:false rollback emitted.
            expect(h.engine.isScreenSharing).toBe(true);
            const states = h.sent.filter(m => m.type === 'content_state');
            expect(states.some(m => m.payload?.active === true)).toBe(true);
            expect(states.some(m => m.payload?.active === false)).toBe(false);

            // Healthy peer B's content sender carries the display track.
            const displayTrackId = h.engine.getLocalContentStream()?.getVideoTracks()[0]?.id;
            expect(displayTrackId).toBeDefined();
            expect(bSenders.content.track?.id).toBe(displayTrackId);

            // Peer A is NOT torn down (connection still open) and its camera is
            // untouched — only its content attach failed.
            expect(peerA.closed).toBe(false);
            expect(h.engine.getPeerConnectionsMap().has('yankee')).toBe(true);
            expect(aSenders.camera.track).toBe(aCameraTrackBefore);

            // Peer A is marked for recovery: a forced structural renegotiation
            // offer was sent to it (alpha is the offer owner).
            expect(peerA.createOfferCalls).toBeGreaterThan(offersToABefore);

            // Recovery durability: when peer A's content m-line re-binds (the
            // forced renegotiation completes) the share re-attaches there instead
            // of leaving the content sender empty.
            aSenders.content.replaceTrackCalls.length = 0;
            h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: 'yankee', sdp: 're-answer', offerId: h.sent.filter(m => m.type === 'offer' && m.to === 'yankee').at(-1)?.payload?.offerId } });
            await flush();
            expect(aSenders.content.track?.id).toBe(displayTrackId);
        });
    });

    // GAP 2: per-role (camera vs content) inbound stall diagnostics per peer.
    // `sampleInboundRoleLiveness()` splits inbound-rtp `bytesReceived` by the
    // bound transceiver role; `getRoleLiveness(cid)` exposes per-role booleans so
    // a consumer derives "content stalled" = content.active && !contentReceiving.
    describe('per-role inbound stall diagnostics', () => {
        function capablePeer(h: Harness, cid = 'zeta'): FakePeerConnection {
            return h.engine.getPeerConnectionsMap().get(cid) as unknown as FakePeerConnection;
        }

        it('content active but content bytes not flowing → contentReceiving false (content stall derivable) while cameraReceiving true', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            await joinAsOwnerCapable(h);
            const peer = capablePeer(h);

            // Baseline sample: camera + content both at some bytes.
            peer.setInboundVideoBytes({ camera: 1000, content: 2000 });
            await h.engine.sampleInboundRoleLiveness();
            // First sample establishes the baseline → conservative false/false.
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: false, content: false });

            // Camera advances, content STALLS (no byte increase).
            peer.setInboundVideoBytes({ camera: 1500, content: 2000 });
            await h.engine.sampleInboundRoleLiveness();
            const liveness = h.engine.getRoleLiveness('zeta');
            expect(liveness.camera).toBe(true);
            expect(liveness.content).toBe(false);
            // "content stalled" derivation: content.active && !contentReceiving.
            const contentActive = true;
            expect(contentActive && !liveness.content).toBe(true);
        });

        it('samples total flow and per-role liveness with one getStats call per peer', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            await joinAsOwnerCapable(h);
            const peer = capablePeer(h);

            peer.setInboundVideoBytes({ camera: 1000, content: 2000 });
            await h.engine.sampleInboundLiveness();
            expect(peer.getStatsCalls).toBe(1);

            peer.setInboundVideoBytes({ camera: 1500, content: 2500 });
            const sample = await h.engine.sampleInboundLiveness();

            expect(peer.getStatsCalls).toBe(2);
            expect(sample.flowingCids).toEqual(['zeta']);
            expect(sample.roleLiveness.get('zeta')).toEqual({ camera: true, content: true });
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: true, content: true });
        });

        it('content bytes flowing → contentReceiving true', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            await joinAsOwnerCapable(h);
            const peer = capablePeer(h);
            peer.setInboundVideoBytes({ camera: 1000, content: 2000 });
            await h.engine.sampleInboundRoleLiveness();
            peer.setInboundVideoBytes({ camera: 1500, content: 5000 });
            await h.engine.sampleInboundRoleLiveness();
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: true, content: true });
        });

        it('falls back to content mid when inbound stats omit trackIdentifier', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            await joinAsOwnerCapable(h);
            const peer = capablePeer(h);
            peer.setInboundVideoBytes({ camera: 1000, content: 2000 }, { omitTrackIdentifier: true });
            await h.engine.sampleInboundRoleLiveness();
            peer.setInboundVideoBytes({ camera: 1500, content: 5000 }, { omitTrackIdentifier: true });
            await h.engine.sampleInboundRoleLiveness();
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: true, content: true });
        });

        it('flag-off camera-only peer: single inbound video routes to cameraReceiving, contentReceiving stays false', async () => {
            const h = makeEngine({}); // flag off → legacy single-video peer
            h.engine.updateSignalingConnected(true);
            await h.engine.startLocalMedia();
            h.engine.updateRoomState({
                hostCid: 'alpha',
                participants: [{ cid: 'alpha' }, { cid: 'zeta' }],
            }, 'alpha');
            await vi.waitFor(() => {
                expect(h.sent.filter(m => m.type === 'offer')).toHaveLength(1);
            });
            const offerId = h.sent.find(m => m.type === 'offer')?.payload?.offerId;
            h.engine.processSignalingMessage({ v: 1, type: 'answer', payload: { from: 'zeta', sdp: 'remote-answer', offerId } });
            await flush();
            const peer = capablePeer(h);
            // Legacy peer has exactly one video transceiver (no content role).
            expect(peer.transceivers.filter(t => t.kind === 'video')).toHaveLength(1);

            peer.setInboundVideoBytes({ legacy: 1000 });
            await h.engine.sampleInboundRoleLiveness();
            peer.setInboundVideoBytes({ legacy: 2000 });
            await h.engine.sampleInboundRoleLiveness();
            const liveness = h.engine.getRoleLiveness('zeta');
            expect(liveness.camera).toBe(true);
            expect(liveness.content).toBe(false);
        });

        it('forgets liveness for a peer that has left', async () => {
            const h = makeEngine({ enableIndependentContentVideo: true });
            await joinAsOwnerCapable(h);
            const peer = capablePeer(h);
            peer.setInboundVideoBytes({ camera: 1000, content: 2000 });
            await h.engine.sampleInboundRoleLiveness();
            peer.setInboundVideoBytes({ camera: 1500, content: 5000 });
            await h.engine.sampleInboundRoleLiveness();
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: true, content: true });

            // Peer leaves the room → tracking is cleaned up.
            h.engine.updateRoomState({ hostCid: 'alpha', participants: [{ cid: 'alpha' }] }, 'alpha');
            await flush();
            await h.engine.sampleInboundRoleLiveness();
            expect(h.engine.getRoleLiveness('zeta')).toEqual({ camera: false, content: false });
        });
    });
});
