import type { SerenadaConfig, DiagnosticsReport, DiagnosticCheckResult } from './types.js';
import { buildApiUrl } from './serverUrls.js';

export class SerenadaDiagnostics {
    private config: SerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.config = config;
    }

    async runAll(): Promise<DiagnosticsReport> {
        const [camera, microphone, speaker, network, signaling, turn, devices] = await Promise.all([
            this.checkCamera(),
            this.checkMicrophone(),
            this.checkSpeaker(),
            this.checkNetwork(),
            this.checkSignaling(),
            this.checkTurn(),
            this.enumerateDevices(),
        ]);
        return { camera, microphone, speaker, network, signaling, turn, devices };
    }

    async checkCamera(): Promise<DiagnosticCheckResult> {
        try {
            if (!navigator.permissions) {
                return { status: 'skipped', reason: 'Permissions API not available' };
            }
            const result = await navigator.permissions.query({ name: 'camera' as PermissionName });
            if (result.state === 'denied') return { status: 'notAuthorized' };
            if (result.state === 'prompt') return { status: 'notAuthorized' };

            const devices = await navigator.mediaDevices.enumerateDevices();
            const cameras = devices.filter(d => d.kind === 'videoinput');
            if (cameras.length === 0) return { status: 'unavailable', reason: 'No camera found' };
            return { status: 'available' };
        } catch (err) {
            return { status: 'skipped', reason: String(err) };
        }
    }

    async checkMicrophone(): Promise<DiagnosticCheckResult> {
        try {
            if (!navigator.permissions) {
                return { status: 'skipped', reason: 'Permissions API not available' };
            }
            const result = await navigator.permissions.query({ name: 'microphone' as PermissionName });
            if (result.state === 'denied') return { status: 'notAuthorized' };
            if (result.state === 'prompt') return { status: 'notAuthorized' };

            const devices = await navigator.mediaDevices.enumerateDevices();
            const mics = devices.filter(d => d.kind === 'audioinput');
            if (mics.length === 0) return { status: 'unavailable', reason: 'No microphone found' };
            return { status: 'available' };
        } catch (err) {
            return { status: 'skipped', reason: String(err) };
        }
    }

    async checkSpeaker(): Promise<DiagnosticCheckResult> {
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            const speakers = devices.filter(d => d.kind === 'audiooutput');
            if (speakers.length === 0) return { status: 'unavailable', reason: 'No speaker found' };
            return { status: 'available' };
        } catch (err) {
            return { status: 'skipped', reason: String(err) };
        }
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
            // Probe server reachability without creating a room
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

    private async enumerateDevices(): Promise<MediaDeviceInfo[]> {
        try {
            if (!navigator.mediaDevices?.enumerateDevices) return [];
            return await navigator.mediaDevices.enumerateDevices();
        } catch {
            return [];
        }
    }
}
