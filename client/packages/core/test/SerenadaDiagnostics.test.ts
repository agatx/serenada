import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SerenadaDiagnostics } from '../src/SerenadaDiagnostics.js';
import { FakeSignalingProvider } from './helpers/FakeSignalingProvider.js';

if (typeof globalThis.navigator === 'undefined') {
    (globalThis as Record<string, unknown>).navigator = {};
}

class FakeRtcPeerConnection {
    iceGatheringState: RTCIceGatheringState = 'new';
    onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
    onicecandidateerror: ((event: Event) => void) | null = null;
    onicegatheringstatechange: (() => void) | null = null;

    constructor(_config: RTCConfiguration) {}

    createDataChannel(_label: string): void {}

    async createOffer(): Promise<RTCSessionDescriptionInit> {
        queueMicrotask(() => {
            this.iceGatheringState = 'gathering';
            this.onicecandidate?.({
                candidate: { candidate: 'candidate:1 1 udp 1 127.0.0.1 3478 typ relay raddr 0.0.0.0 rport 0' } as RTCIceCandidate,
            } as RTCPeerConnectionIceEvent);
            this.iceGatheringState = 'complete';
            this.onicecandidate?.({ candidate: null } as RTCPeerConnectionIceEvent);
            this.onicegatheringstatechange?.();
        });
        return { type: 'offer', sdp: 'v=0' };
    }

    async setLocalDescription(_description: RTCLocalSessionDescriptionInit): Promise<void> {}

    close(): void {}
}

describe('SerenadaDiagnostics', () => {
    const originalRtcPeerConnection = (globalThis as Record<string, unknown>).RTCPeerConnection;
    const originalMediaDevices = navigator.mediaDevices;
    const originalOnLine = navigator.onLine;

    beforeEach(() => {
        Object.defineProperty(navigator, 'mediaDevices', {
            value: { enumerateDevices: vi.fn().mockResolvedValue([]) },
            configurable: true,
        });
        Object.defineProperty(navigator, 'onLine', {
            value: true,
            configurable: true,
        });
    });

    afterEach(() => {
        Object.defineProperty(navigator, 'mediaDevices', {
            value: originalMediaDevices,
            configurable: true,
        });
        Object.defineProperty(navigator, 'onLine', {
            value: originalOnLine,
            configurable: true,
        });
        (globalThis as Record<string, unknown>).RTCPeerConnection = originalRtcPeerConnection;
    });

    it('runAll skips signaling in provider mode and still checks TURN', async () => {
        const provider = new FakeSignalingProvider();
        provider.getIceServersResults = [[{
            urls: ['turns:relay.example.com'],
            username: 'user',
            credential: 'pass',
        }]];
        const diagnostics = new SerenadaDiagnostics({ signalingProvider: provider });

        const report = await diagnostics.runAll();

        expect(report.signaling).toEqual({ status: 'skipped', reason: 'requires serverHost' });
        expect(report.turn.status).toBe('available');
        expect(provider.getIceServersCalls).toBe(1);
    });

    it('runConnectivityChecks rejects provider mode without serverHost', async () => {
        const diagnostics = new SerenadaDiagnostics({ signalingProvider: new FakeSignalingProvider() });
        await expect(diagnostics.runConnectivityChecks()).rejects.toThrow('requires serverHost');
    });

    it('runTurnProbe uses provider ICE servers', async () => {
        const provider = new FakeSignalingProvider();
        provider.getIceServersResults = [[{
            urls: ['turns:relay.example.com'],
            username: 'user',
            credential: 'pass',
        }]];
        const diagnostics = new SerenadaDiagnostics({ signalingProvider: provider });
        (globalThis as Record<string, unknown>).RTCPeerConnection = FakeRtcPeerConnection;

        const report = await diagnostics.runTurnProbe(false);

        expect(report.turnPassed).toBe(true);
        expect(report.logs).toContain('candidate:1 1 udp 1 127.0.0.1 3478 typ relay raddr 0.0.0.0 rport 0');
        expect(provider.getIceServersCalls).toBe(1);
    });
});
