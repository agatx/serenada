import { describe, expect, it } from 'vitest';
import {
    captureFrameFromStream,
    resolveSnapshotStream,
    SnapshotError,
} from '../../src/media/captureSnapshot.js';

class FakeMediaStreamTrack {
    constructor(
        public kind: 'audio' | 'video',
        public readyState: 'live' | 'ended' = 'live',
    ) {}
    stop(): void {
        this.readyState = 'ended';
    }
}

class FakeMediaStream {
    private tracks: FakeMediaStreamTrack[];
    constructor(tracks: FakeMediaStreamTrack[]) {
        this.tracks = tracks;
    }
    getVideoTracks(): FakeMediaStreamTrack[] {
        return this.tracks.filter((t) => t.kind === 'video');
    }
    getTracks(): FakeMediaStreamTrack[] {
        return [...this.tracks];
    }
}

interface FakeVideoElement extends EventTarget {
    videoWidth: number;
    videoHeight: number;
    srcObject: unknown;
    muted: boolean;
    playsInline: boolean;
    autoplay: boolean;
    readyState: number;
    pauseCalls: number;
    play(): Promise<void>;
    pause(): void;
}

interface FakeCanvasElement {
    width: number;
    height: number;
    drawnSources: unknown[];
    encodedAs: { type: string; quality: number } | null;
    getContext(kind: '2d'): { drawImage(src: unknown, x: number, y: number, w: number, h: number): void };
    toBlob(cb: (blob: Blob | null) => void, type: string, quality: number): void;
}

interface FakeDocument {
    createdVideos: FakeVideoElement[];
    createdCanvases: FakeCanvasElement[];
    createElement(tag: 'video' | 'canvas'): FakeVideoElement | FakeCanvasElement;
}

interface FakeDocumentOptions {
    /**
     * If set, the fake video resolves frame-ready synchronously with these dims.
     * If undefined, the video stays at zero dimensions and never fires events
     * (drives the timeout case).
     */
    initialDimensions?: { width: number; height: number };
    /** Fail canvas.toBlob to drive the captureFailed path. */
    blobReturnsNull?: boolean;
    /** Throw on srcObject set to drive the captureFailed bind path. */
    failOnBind?: boolean;
}

function createFakeDocument(options: FakeDocumentOptions = {}): FakeDocument {
    const createdVideos: FakeVideoElement[] = [];
    const createdCanvases: FakeCanvasElement[] = [];
    return {
        createdVideos,
        createdCanvases,
        createElement(tag) {
            if (tag === 'video') {
                const listeners = new Map<string, Set<EventListener>>();
                let srcObject: unknown = null;
                let videoWidth = 0;
                let videoHeight = 0;
                const dispatch = (type: string) => {
                    const set = listeners.get(type);
                    if (!set) return;
                    for (const l of set) {
                        l({ type } as unknown as Event);
                    }
                };
                const setDimensionsAndFire = () => {
                    if (options.initialDimensions) {
                        videoWidth = options.initialDimensions.width;
                        videoHeight = options.initialDimensions.height;
                        dispatch('loadedmetadata');
                    }
                };
                const video: FakeVideoElement = {
                    addEventListener(type: string, listener: EventListener) {
                        let set = listeners.get(type);
                        if (!set) {
                            set = new Set();
                            listeners.set(type, set);
                        }
                        set.add(listener);
                    },
                    removeEventListener(type: string, listener: EventListener) {
                        listeners.get(type)?.delete(listener);
                    },
                    dispatchEvent(_event: Event) {
                        return true;
                    },
                    get videoWidth() {
                        return videoWidth;
                    },
                    get videoHeight() {
                        return videoHeight;
                    },
                    get srcObject() {
                        return srcObject;
                    },
                    set srcObject(value: unknown) {
                        if (options.failOnBind && value !== null) {
                            throw new Error('bind failed');
                        }
                        srcObject = value;
                        if (value !== null) {
                            // Fire metadata after assignment, async to mimic browser
                            queueMicrotask(setDimensionsAndFire);
                        }
                    },
                    muted: false,
                    playsInline: false,
                    autoplay: false,
                    readyState: 0,
                    pauseCalls: 0,
                    async play() {
                        return undefined;
                    },
                    pause() {
                        this.pauseCalls += 1;
                    },
                };
                createdVideos.push(video);
                return video;
            }
            // canvas
            const drawnSources: unknown[] = [];
            const canvas: FakeCanvasElement = {
                width: 0,
                height: 0,
                drawnSources,
                encodedAs: null,
                getContext(kind) {
                    if (kind !== '2d') throw new Error('unsupported context');
                    return {
                        drawImage: (src: unknown) => {
                            drawnSources.push(src);
                        },
                    };
                },
                toBlob(cb, type, quality) {
                    canvas.encodedAs = { type, quality };
                    if (options.blobReturnsNull) {
                        cb(null);
                        return;
                    }
                    cb(new Blob(['fake-image-bytes'], { type }));
                },
            };
            createdCanvases.push(canvas);
            return canvas;
        },
    };
}

function videoStream(): MediaStream {
    return new FakeMediaStream([
        new FakeMediaStreamTrack('audio'),
        new FakeMediaStreamTrack('video'),
    ]) as unknown as MediaStream;
}

function audioOnlyStream(): MediaStream {
    return new FakeMediaStream([new FakeMediaStreamTrack('audio')]) as unknown as MediaStream;
}

describe('captureFrameFromStream', () => {
    it('captures a JPEG blob at the source video resolution', async () => {
        const doc = createFakeDocument({ initialDimensions: { width: 1280, height: 720 } });
        const stream = videoStream();
        const result = await captureFrameFromStream(stream, { doc: doc as unknown as Document });

        expect(result.width).toBe(1280);
        expect(result.height).toBe(720);
        expect(result.blob.type).toBe('image/jpeg');

        const canvas = doc.createdCanvases[0]!;
        expect(canvas.width).toBe(1280);
        expect(canvas.height).toBe(720);
        expect(canvas.drawnSources).toHaveLength(1);
        expect(canvas.encodedAs).toEqual({ type: 'image/jpeg', quality: 0.95 });
    });

    it('throws noVideoTrack when stream has no video', async () => {
        const doc = createFakeDocument({ initialDimensions: { width: 320, height: 240 } });
        await expect(
            captureFrameFromStream(audioOnlyStream(), { doc: doc as unknown as Document }),
        ).rejects.toMatchObject({ name: 'SnapshotError', code: 'noVideoTrack' });
    });

    it('throws streamNotActive when the only video track has ended', async () => {
        const doc = createFakeDocument({ initialDimensions: { width: 320, height: 240 } });
        const ended = new FakeMediaStreamTrack('video');
        ended.stop();
        const stream = new FakeMediaStream([ended]) as unknown as MediaStream;
        await expect(
            captureFrameFromStream(stream, { doc: doc as unknown as Document }),
        ).rejects.toMatchObject({ name: 'SnapshotError', code: 'streamNotActive' });
    });

    it('rejects with captureTimeout when no frame arrives in time', async () => {
        const doc = createFakeDocument(); // no dimensions, no events fire
        await expect(
            captureFrameFromStream(videoStream(), { doc: doc as unknown as Document, timeoutMs: 30 }),
        ).rejects.toMatchObject({ name: 'SnapshotError', code: 'captureTimeout' });
        expect(doc.createdVideos[0]?.pauseCalls).toBeGreaterThanOrEqual(1);
    });

    it('rejects with captureFailed when canvas encode returns null', async () => {
        const doc = createFakeDocument({
            initialDimensions: { width: 640, height: 480 },
            blobReturnsNull: true,
        });
        await expect(
            captureFrameFromStream(videoStream(), { doc: doc as unknown as Document }),
        ).rejects.toMatchObject({ name: 'SnapshotError', code: 'captureFailed' });
    });

    it('rejects with captureFailed when binding the stream throws', async () => {
        const doc = createFakeDocument({ failOnBind: true });
        await expect(
            captureFrameFromStream(videoStream(), { doc: doc as unknown as Document }),
        ).rejects.toMatchObject({ name: 'SnapshotError', code: 'captureFailed' });
    });

    it('honors custom mime and quality options', async () => {
        const doc = createFakeDocument({ initialDimensions: { width: 100, height: 100 } });
        await captureFrameFromStream(videoStream(), {
            doc: doc as unknown as Document,
            type: 'image/png',
            quality: 0.5,
        });
        expect(doc.createdCanvases[0]?.encodedAs).toEqual({ type: 'image/png', quality: 0.5 });
    });
});

describe('resolveSnapshotStream', () => {
    it('returns the local stream for kind=local', () => {
        const local = videoStream();
        const remoteMap = new Map<string, MediaStream>();
        expect(resolveSnapshotStream({ kind: 'local' }, local, remoteMap)).toBe(local);
    });

    it('throws when local stream is missing', () => {
        const remoteMap = new Map<string, MediaStream>();
        expect(() => resolveSnapshotStream({ kind: 'local' }, null, remoteMap)).toThrow(SnapshotError);
    });

    it('returns the matching remote stream', () => {
        const remote = videoStream();
        const remoteMap = new Map<string, MediaStream>([['peer-a', remote]]);
        expect(resolveSnapshotStream({ kind: 'remote', cid: 'peer-a' }, null, remoteMap)).toBe(remote);
    });

    it('throws when no remote stream exists for the cid', () => {
        const remoteMap = new Map<string, MediaStream>();
        expect(() => resolveSnapshotStream({ kind: 'remote', cid: 'missing' }, null, remoteMap))
            .toThrow(SnapshotError);
    });
});

describe('SnapshotError', () => {
    it('preserves the code on the error instance', () => {
        const err = new SnapshotError('streamNotActive', 'no stream');
        expect(err.name).toBe('SnapshotError');
        expect(err.code).toBe('streamNotActive');
        expect(err.message).toBe('no stream');
        expect(err).toBeInstanceOf(Error);
    });
});
