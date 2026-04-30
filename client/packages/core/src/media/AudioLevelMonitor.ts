import type { SerenadaLogger } from '../types.js';

/** Update frequency for audio level subscribers. 10 Hz pairs well with 100 ms CSS transitions. */
const DEFAULT_UPDATE_INTERVAL_MS = 100;
/** Default FFT size — small for low CPU; we only need a coarse RMS. */
const DEFAULT_FFT_SIZE = 1024;
/** Default smoothing applied by the analyser node (0..1). */
const DEFAULT_SMOOTHING = 0.5;
/** dBFS at which the indicator reads zero. Quieter than this is treated as silence. */
const NOISE_FLOOR_DB = -60;
/** dBFS at which the indicator reads full. Normal speech peaks around -20 to -15 dBFS. */
const SPEECH_PEAK_DB = -15;
/** Attack/release smoothing across ticks (0..1). Higher = stickier; 0 = no smoothing. */
const ATTACK_SMOOTHING = 0.4;
const RELEASE_SMOOTHING = 0.7;

export interface AudioLevelMonitorOptions {
    /** AnalyserNode smoothingTimeConstant (0..1). Defaults to 0.5. */
    smoothing?: number;
    /** AnalyserNode FFT size — power of two. Defaults to 1024. */
    fftSize?: number;
    /** Subscriber notification interval in ms. Defaults to 100. */
    updateIntervalMs?: number;
    /** Reuse an existing AudioContext (the monitor will not close it on dispose). */
    audioContext?: AudioContext;
    /** Optional logger for diagnostics. */
    logger?: SerenadaLogger;
}

type LevelCallback = (level: number) => void;

/**
 * Computes a normalized speech audio level (0..1) from a MediaStream's audio
 * track using the Web Audio API. Use it to drive UI activity indicators.
 *
 * The monitor is lazy: it only runs the analysis loop while at least one
 * subscriber is attached. Call {@link dispose} when the stream is gone or
 * the consumer goes away.
 */
export class AudioLevelMonitor {
    private context: AudioContext | null = null;
    private analyser: AnalyserNode | null = null;
    private source: MediaStreamAudioSourceNode | null = null;
    private buffer: Uint8Array<ArrayBuffer> | null = null;
    private timer: number | null = null;
    private subscribers = new Set<LevelCallback>();
    private currentLevel = 0;
    private disposed = false;
    private readonly ownsContext: boolean;
    private readonly updateIntervalMs: number;
    private readonly logger?: SerenadaLogger;

    constructor(stream: MediaStream, options: AudioLevelMonitorOptions = {}) {
        this.updateIntervalMs = options.updateIntervalMs ?? DEFAULT_UPDATE_INTERVAL_MS;
        this.logger = options.logger;
        this.ownsContext = !options.audioContext;

        const Ctx = typeof globalThis !== 'undefined'
            ? ((globalThis as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext })
                .AudioContext
                ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
            : undefined;

        if (!Ctx && !options.audioContext) {
            this.logger?.log('debug', 'AudioLevelMonitor', 'AudioContext unavailable; monitor will report zero level');
            return;
        }

        if (stream.getAudioTracks().length === 0) {
            this.logger?.log('debug', 'AudioLevelMonitor', 'Stream has no audio tracks');
            return;
        }

        try {
            this.context = options.audioContext ?? new (Ctx as typeof AudioContext)();
            this.analyser = this.context.createAnalyser();
            this.analyser.fftSize = options.fftSize ?? DEFAULT_FFT_SIZE;
            this.analyser.smoothingTimeConstant = options.smoothing ?? DEFAULT_SMOOTHING;
            this.source = this.context.createMediaStreamSource(stream);
            this.source.connect(this.analyser);
            this.buffer = new Uint8Array(new ArrayBuffer(this.analyser.fftSize));
            // Browsers may create the context in a suspended state until a user gesture;
            // resume it so analysis starts producing samples immediately.
            if (this.context.state === 'suspended') {
                void this.context.resume().catch((err) => {
                    this.logger?.log('debug', 'AudioLevelMonitor', `resume() failed: ${err}`);
                });
            }
        } catch (err) {
            this.logger?.log('warning', 'AudioLevelMonitor', `Failed to attach to stream: ${err}`);
            this.cleanup();
        }
    }

    /** Returns the most recent level computed by the monitor (0..1). */
    get level(): number {
        return this.currentLevel;
    }

    /**
     * Subscribe for level updates. The callback fires immediately with the
     * current level, then at {@link AudioLevelMonitorOptions.updateIntervalMs}.
     * Returns an unsubscribe function.
     */
    subscribe(callback: LevelCallback): () => void {
        if (this.disposed) {
            callback(0);
            return () => { /* noop */ };
        }
        this.subscribers.add(callback);
        callback(this.currentLevel);
        if (this.analyser && this.subscribers.size === 1) {
            this.startLoop();
        }
        return () => {
            this.subscribers.delete(callback);
            if (this.subscribers.size === 0) {
                this.stopLoop();
            }
        };
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.cleanup();
    }

    private startLoop(): void {
        if (this.timer !== null) return;
        const setIntervalFn = typeof globalThis !== 'undefined' && typeof globalThis.setInterval === 'function'
            ? globalThis.setInterval.bind(globalThis)
            : null;
        if (!setIntervalFn) return;
        this.timer = setIntervalFn(() => this.tick(), this.updateIntervalMs) as unknown as number;
    }

    private stopLoop(): void {
        if (this.timer === null) return;
        const clearIntervalFn = typeof globalThis !== 'undefined' && typeof globalThis.clearInterval === 'function'
            ? globalThis.clearInterval.bind(globalThis)
            : null;
        clearIntervalFn?.(this.timer as unknown as ReturnType<typeof setInterval>);
        this.timer = null;
    }

    private tick(): void {
        if (this.disposed || !this.analyser || !this.buffer) return;
        if (this.context?.state === 'suspended') {
            void this.context.resume().catch(() => {});
        }
        this.analyser.getByteTimeDomainData(this.buffer);
        let sumSquares = 0;
        for (let i = 0; i < this.buffer.length; i++) {
            const sample = (this.buffer[i] - 128) / 128;
            sumSquares += sample * sample;
        }
        const rms = Math.sqrt(sumSquares / this.buffer.length);
        // Map RMS to dBFS, then to a perceptual 0..1 between the noise floor and speech peak.
        const dbfs = rms > 0 ? 20 * Math.log10(rms) : NOISE_FLOOR_DB;
        const target = Math.max(0, Math.min(1, (dbfs - NOISE_FLOOR_DB) / (SPEECH_PEAK_DB - NOISE_FLOOR_DB)));
        // Asymmetric smoothing: snap up quickly (attack), decay slowly (release).
        const smoothing = target > this.currentLevel ? ATTACK_SMOOTHING : RELEASE_SMOOTHING;
        const level = this.currentLevel * smoothing + target * (1 - smoothing);
        this.currentLevel = level;
        this.subscribers.forEach((cb) => {
            try { cb(level); } catch (err) {
                this.logger?.log('warning', 'AudioLevelMonitor', `Subscriber threw: ${err}`);
            }
        });
    }

    private cleanup(): void {
        this.stopLoop();
        if (this.source) {
            try { this.source.disconnect(); } catch { /* ignore */ }
            this.source = null;
        }
        if (this.analyser) {
            try { this.analyser.disconnect(); } catch { /* ignore */ }
            this.analyser = null;
        }
        if (this.context && this.ownsContext) {
            try { void this.context.close(); } catch { /* ignore */ }
        }
        this.context = null;
        this.buffer = null;
        this.subscribers.clear();
    }
}
