import type { SnapshotErrorCode, SnapshotSource } from '../types.js';

export class SnapshotError extends Error {
    readonly code: SnapshotErrorCode;
    constructor(code: SnapshotErrorCode, message?: string) {
        super(message ?? `Snapshot failed: ${code}`);
        this.name = 'SnapshotError';
        this.code = code;
    }
}

export const SNAPSHOT_FRAME_TIMEOUT_MS = 2000;
export const DEFAULT_SNAPSHOT_MIME = 'image/jpeg';
export const DEFAULT_SNAPSHOT_QUALITY = 0.95;

export interface CaptureFrameOptions {
    /** Maximum time to wait for the first decoded frame. Defaults to {@link SNAPSHOT_FRAME_TIMEOUT_MS}. */
    timeoutMs?: number;
    /** Image MIME type. Defaults to `'image/jpeg'`. */
    type?: string;
    /** JPEG/WebP quality 0–1. Defaults to `0.95`. */
    quality?: number;
    /** Document used to create the offscreen video and canvas. Defaults to the global `document`. */
    doc?: Document;
}

export interface CapturedFrame {
    blob: Blob;
    width: number;
    height: number;
}

/**
 * Capture the current video frame from a MediaStream into a Blob at the
 * stream's full intrinsic resolution. Throws {@link SnapshotError} on failure.
 *
 * Implementation: bind the stream to an offscreen `<video>`, wait for the
 * first decoded frame, draw it onto an offscreen `<canvas>` at
 * `videoWidth × videoHeight`, then encode via `canvas.toBlob`.
 */
export async function captureFrameFromStream(
    stream: MediaStream,
    options: CaptureFrameOptions = {},
): Promise<CapturedFrame> {
    const doc = options.doc ?? (typeof document !== 'undefined' ? document : null);
    if (!doc) {
        throw new SnapshotError('captureFailed', 'No document available for canvas');
    }

    const videoTracks = stream.getVideoTracks();
    if (videoTracks.length === 0) {
        throw new SnapshotError('noVideoTrack', 'Stream has no video track');
    }
    if (!videoTracks.some((t) => t.readyState === 'live' && t.enabled !== false)) {
        // A track that is `live` but `enabled === false` produces black frames
        // (the SDK's video-toggle path flips `enabled`, not `readyState`), so
        // capturing it would silently return a blank image. Treat as inactive.
        throw new SnapshotError('streamNotActive', 'Stream has no enabled live video track');
    }

    const video = doc.createElement('video');
    video.muted = true;
    (video as HTMLVideoElement & { playsInline?: boolean }).playsInline = true;
    video.autoplay = true;
    try {
        video.srcObject = stream;
    } catch (err) {
        throw new SnapshotError('captureFailed', `Cannot bind stream: ${(err as Error)?.message ?? err}`);
    }

    try {
        await waitForFirstFrame(video, options.timeoutMs ?? SNAPSHOT_FRAME_TIMEOUT_MS);

        const width = video.videoWidth;
        const height = video.videoHeight;
        if (width <= 0 || height <= 0) {
            throw new SnapshotError('captureFailed', 'Video dimensions are zero');
        }

        const canvas = doc.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
            throw new SnapshotError('captureFailed', 'Cannot get 2D canvas context');
        }
        ctx.drawImage(video, 0, 0, width, height);

        const blob = await canvasToBlob(
            canvas,
            options.type ?? DEFAULT_SNAPSHOT_MIME,
            options.quality ?? DEFAULT_SNAPSHOT_QUALITY,
        );
        if (!blob) {
            throw new SnapshotError('captureFailed', 'Canvas encode returned null');
        }
        return { blob, width, height };
    } finally {
        try {
            video.pause();
        } catch {
            // ignore
        }
        try {
            video.srcObject = null;
        } catch {
            // ignore
        }
    }
}

type VideoElementWithRvfc = HTMLVideoElement & {
    requestVideoFrameCallback?: (cb: (now: number, metadata: unknown) => void) => number;
    cancelVideoFrameCallback?: (handle: number) => void;
};

function waitForFirstFrame(video: HTMLVideoElement, timeoutMs: number): Promise<void> {
    return new Promise((resolve, reject) => {
        let settled = false;
        let timer: ReturnType<typeof setTimeout> | null = null;
        let rvfcHandle: number | null = null;

        const videoEx = video as VideoElementWithRvfc;
        const supportsRvfc = typeof videoEx.requestVideoFrameCallback === 'function';

        const cleanup = () => {
            if (timer !== null) {
                clearTimeout(timer);
                timer = null;
            }
            if (rvfcHandle !== null && typeof videoEx.cancelVideoFrameCallback === 'function') {
                try {
                    videoEx.cancelVideoFrameCallback(rvfcHandle);
                } catch {
                    // ignore
                }
                rvfcHandle = null;
            }
            video.removeEventListener('loadedmetadata', onMetadata);
            video.removeEventListener('error', onError);
        };
        const settleSuccess = () => {
            if (settled) return;
            if (video.videoWidth <= 0 || video.videoHeight <= 0) return;
            settled = true;
            cleanup();
            resolve();
        };
        const onMetadata = () => {
            // Without rVFC we treat metadata as a useful signal (the canvas
            // path needs videoWidth/Height anyway), but we defer one task
            // tick so the decoder has a chance to commit pixels before
            // drawImage runs — `loadedmetadata` fires before any frame is
            // actually drawable. In rVFC-supporting browsers this branch is
            // skipped because the rVFC callback gates on real frame data.
            if (supportsRvfc || settled) return;
            setTimeout(() => {
                if (settled) return;
                settleSuccess();
            }, 0);
        };
        const onError = () => {
            if (settled) return;
            settled = true;
            cleanup();
            reject(new SnapshotError('captureFailed', 'Video element reported error'));
        };

        video.addEventListener('loadedmetadata', onMetadata);
        video.addEventListener('error', onError);

        if (supportsRvfc) {
            // Resolve only when a real frame is presented — this is the only
            // signal that drawImage will actually have pixel data.
            rvfcHandle = videoEx.requestVideoFrameCallback!(() => {
                rvfcHandle = null;
                settleSuccess();
            });
        }

        const playPromise = (video as HTMLVideoElement & { play(): unknown }).play?.();
        if (playPromise && typeof (playPromise as Promise<void>).catch === 'function') {
            (playPromise as Promise<void>).catch(() => {
                // autoplay may be rejected; the frame-ready callbacks still fire
            });
        }

        timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            cleanup();
            reject(new SnapshotError('captureTimeout', `No frame within ${timeoutMs}ms`));
        }, timeoutMs);
    });
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
    return new Promise((resolve) => {
        if (typeof canvas.toBlob === 'function') {
            canvas.toBlob((blob) => resolve(blob), type, quality);
            return;
        }
        try {
            const dataUrl = canvas.toDataURL(type, quality);
            resolve(dataUrlToBlob(dataUrl));
        } catch {
            resolve(null);
        }
    });
}

function dataUrlToBlob(dataUrl: string): Blob | null {
    try {
        const commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0) return null;
        const meta = dataUrl.slice(0, commaIndex);
        const body = dataUrl.slice(commaIndex + 1);
        const mime = meta.match(/data:([^;]+)/)?.[1] ?? 'application/octet-stream';
        if (meta.includes(';base64')) {
            const binary = atob(body);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            return new Blob([bytes], { type: mime });
        }
        return new Blob([decodeURIComponent(body)], { type: mime });
    } catch {
        return null;
    }
}

export function resolveSnapshotStream(
    source: SnapshotSource,
    localStream: MediaStream | null,
    remoteStreams: Map<string, MediaStream>,
): MediaStream {
    if (source.kind === 'local') {
        if (!localStream) {
            throw new SnapshotError('streamNotActive', 'Local stream is not active');
        }
        return localStream;
    }
    if (source.kind === 'remote') {
        const stream = remoteStreams.get(source.cid);
        if (!stream) {
            throw new SnapshotError('streamNotActive', `Remote stream for cid ${source.cid} is not active`);
        }
        return stream;
    }
    throw new SnapshotError('unsupportedSource', 'Unknown snapshot source kind');
}
