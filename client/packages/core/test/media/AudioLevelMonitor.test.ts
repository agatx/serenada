import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AudioLevelMonitor } from '../../src/media/AudioLevelMonitor.js';

interface FakeAnalyser {
    fftSize: number;
    smoothingTimeConstant: number;
    frequencyBinCount: number;
    getByteTimeDomainData: (buffer: Uint8Array) => void;
    disconnect: () => void;
}

interface FakeSource {
    connect: (target: unknown) => void;
    disconnect: () => void;
}

class FakeAudioContext {
    closed = false;
    public lastSource: FakeSource | null = null;
    public lastAnalyser: FakeAnalyser | null = null;
    private waveform: Int8Array | null = null;

    setWaveform(samples: number[] | null): void {
        this.waveform = samples ? Int8Array.from(samples) : null;
    }

    createAnalyser(): FakeAnalyser {
        const analyser: FakeAnalyser = {
            fftSize: 2048,
            smoothingTimeConstant: 0.8,
            get frequencyBinCount() { return this.fftSize / 2; },
            getByteTimeDomainData: (buffer: Uint8Array) => {
                if (!this.waveform) {
                    buffer.fill(128); // silence == 128 in u8 PCM
                    return;
                }
                for (let i = 0; i < buffer.length; i++) {
                    const sample = this.waveform[i % this.waveform.length] ?? 0;
                    buffer[i] = Math.max(0, Math.min(255, 128 + sample));
                }
            },
            disconnect: () => { /* noop */ },
        };
        this.lastAnalyser = analyser;
        return analyser;
    }

    createMediaStreamSource(_stream: MediaStream): FakeSource {
        const source: FakeSource = {
            connect: () => { /* noop */ },
            disconnect: () => { /* noop */ },
        };
        this.lastSource = source;
        return source;
    }

    async close(): Promise<void> {
        this.closed = true;
    }
}

function makeFakeStream(audioTracks: number): MediaStream {
    const tracks = Array.from({ length: audioTracks }, () => ({}));
    return {
        getAudioTracks: () => tracks,
        getVideoTracks: () => [],
        getTracks: () => tracks,
    } as unknown as MediaStream;
}

describe('AudioLevelMonitor', () => {
    const originalAudioContext = (globalThis as Record<string, unknown>).AudioContext;

    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
        if (originalAudioContext === undefined) {
            delete (globalThis as Record<string, unknown>).AudioContext;
        } else {
            (globalThis as Record<string, unknown>).AudioContext = originalAudioContext;
        }
    });

    it('reports zero level and noop unsubscribe when AudioContext is unavailable', () => {
        delete (globalThis as Record<string, unknown>).AudioContext;
        delete (globalThis as Record<string, unknown>).webkitAudioContext;

        const monitor = new AudioLevelMonitor(makeFakeStream(1));
        const samples: number[] = [];
        const unsubscribe = monitor.subscribe((l) => samples.push(l));
        unsubscribe();
        monitor.dispose();

        expect(samples).toEqual([0]);
        expect(monitor.level).toBe(0);
    });

    it('reports zero level when stream has no audio tracks', () => {
        (globalThis as Record<string, unknown>).AudioContext = FakeAudioContext;
        const monitor = new AudioLevelMonitor(makeFakeStream(0));
        const samples: number[] = [];
        monitor.subscribe((l) => samples.push(l));

        expect(samples).toEqual([0]);
        monitor.dispose();
    });

    it('produces a non-zero level for a non-silent waveform', () => {
        const ctx = new FakeAudioContext();
        // Mid-level speech-like waveform (RMS ~ 50/128 ≈ 0.39, ~ -8 dBFS).
        ctx.setWaveform([50, -50]);
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });

        const samples: number[] = [];
        monitor.subscribe((l) => samples.push(l));
        // Initial sample is current level (0 before first tick)
        expect(samples[0]).toBe(0);

        // Advance through one tick (default 100 ms interval)
        vi.advanceTimersByTime(100);
        expect(samples.length).toBeGreaterThanOrEqual(2);
        const after = samples[samples.length - 1];
        expect(after).toBeGreaterThan(0);
        expect(after).toBeLessThanOrEqual(1);

        monitor.dispose();
    });

    it('converges toward 1 for very loud signals across multiple ticks', () => {
        const ctx = new FakeAudioContext();
        ctx.setWaveform([127, -127]); // RMS ≈ 0.99 (~0 dBFS)
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });

        let last = 0;
        monitor.subscribe((l) => { last = l; });
        // EMA with attack 0.4 reaches ~0.99 after ~5 ticks
        vi.advanceTimersByTime(600);
        expect(last).toBeGreaterThan(0.95);
        expect(last).toBeLessThanOrEqual(1);

        monitor.dispose();
    });

    it('reports zero for silence (waveform pinned to 128)', () => {
        const ctx = new FakeAudioContext();
        ctx.setWaveform(null);
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });

        let last = -1;
        monitor.subscribe((l) => { last = l; });
        vi.advanceTimersByTime(200);
        expect(last).toBe(0);

        monitor.dispose();
    });

    it('stops the loop when all subscribers unsubscribe', () => {
        const ctx = new FakeAudioContext();
        ctx.setWaveform([50, -50]);
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });

        const samples: number[] = [];
        const unsubscribe = monitor.subscribe((l) => samples.push(l));
        vi.advanceTimersByTime(100);
        const samplesBeforeUnsub = samples.length;
        unsubscribe();
        vi.advanceTimersByTime(500);

        expect(samples.length).toBe(samplesBeforeUnsub);
        monitor.dispose();
    });

    it('does not close an externally-supplied AudioContext on dispose', () => {
        const ctx = new FakeAudioContext();
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });
        monitor.dispose();
        expect(ctx.closed).toBe(false);
    });

    it('subscribing after dispose returns a noop and zero', () => {
        const ctx = new FakeAudioContext();
        const monitor = new AudioLevelMonitor(makeFakeStream(1), { audioContext: ctx as unknown as AudioContext });
        monitor.dispose();

        const samples: number[] = [];
        const unsubscribe = monitor.subscribe((l) => samples.push(l));
        unsubscribe();

        expect(samples).toEqual([0]);
    });
});
