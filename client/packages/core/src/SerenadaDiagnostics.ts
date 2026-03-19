import type { SerenadaConfig, DiagnosticsReport, DiagnosticCheckResult } from './types.js';
import { buildApiUrl } from './serverUrls.js';

export class SerenadaDiagnostics {
    private config: SerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.config = config;
    }

    async runAll(): Promise<DiagnosticsReport> {
        const [devices, network, signaling, turn] = await Promise.all([
            this.enumerateDevices(),
            this.checkNetwork(),
            this.checkSignaling(),
            this.checkTurn(),
        ]);
        const camera = this.checkMediaCapability(devices, 'videoinput', 'No camera found');
        const microphone = this.checkMediaCapability(devices, 'audioinput', 'No microphone found');
        const speaker = this.checkDeviceAvailability(devices, 'audiooutput', 'No speaker found');
        return { camera, microphone, speaker, network, signaling, turn, devices };
    }

    async checkCamera(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkMediaCapability(devices, 'videoinput', 'No camera found');
    }

    async checkMicrophone(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkMediaCapability(devices, 'audioinput', 'No microphone found');
    }

    async checkSpeaker(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkDeviceAvailability(devices, 'audiooutput', 'No speaker found');
    }

    async checkNetwork(): Promise<DiagnosticCheckResult> {
        try {
            if (!navigator.onLine) return { status: 'unavailable', reason: 'Browser reports offline' };
            return { status: 'available' };
        } catch (err) {
            return { status: 'skipped', reason: String(err) };
        }
    }

    async checkSignaling(): Promise<DiagnosticCheckResult & { transport?: string }> {
        try {
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 5000);
            const res = await fetch(buildApiUrl(this.config.serverHost, '/api/room-id'), {
                method: 'GET',
                signal: controller.signal,
            });
            clearTimeout(timeout);
            if (res.ok || res.status === 405) {
                return { status: 'available', transport: 'ws' };
            }
            return { status: 'unavailable', reason: `Server returned ${res.status}` };
        } catch (err) {
            return { status: 'unavailable', reason: String(err) };
        }
    }

    async checkTurn(): Promise<DiagnosticCheckResult & { latencyMs?: number }> {
        try {
            const start = Date.now();
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 5000);
            const res = await fetch(buildApiUrl(this.config.serverHost, '/api/turn-credentials?token=probe'), {
                signal: controller.signal,
            });
            clearTimeout(timeout);
            const latencyMs = Date.now() - start;
            if (res.ok) {
                return { status: 'available', latencyMs };
            }
            // 401/403 is expected without a valid token but means the endpoint is reachable
            if (res.status === 401 || res.status === 403) {
                return { status: 'available', latencyMs };
            }
            return { status: 'unavailable', reason: `TURN endpoint returned ${res.status}` };
        } catch (err) {
            return { status: 'unavailable', reason: String(err) };
        }
    }

    private checkMediaCapability(
        devices: MediaDeviceInfo[],
        deviceKind: MediaDeviceKind,
        notFoundMsg: string,
    ): DiagnosticCheckResult {
        const matching = devices.filter(d => d.kind === deviceKind);
        // If labels are empty, permissions haven't been granted yet
        if (matching.length > 0 && matching.every(d => !d.label)) {
            return { status: 'notAuthorized' };
        }
        if (matching.length === 0) return { status: 'unavailable', reason: notFoundMsg };
        return { status: 'available' };
    }

    private checkDeviceAvailability(
        devices: MediaDeviceInfo[],
        deviceKind: MediaDeviceKind,
        notFoundMsg: string,
    ): DiagnosticCheckResult {
        const matching = devices.filter(d => d.kind === deviceKind);
        if (matching.length === 0) return { status: 'unavailable', reason: notFoundMsg };
        return { status: 'available' };
    }

    private async enumerateDevices(): Promise<MediaDeviceInfo[]> {
        try {
            if (!navigator.mediaDevices?.enumerateDevices) return [];
            return await navigator.mediaDevices.enumerateDevices();
        } catch {
            return [];
        }
    }
}
