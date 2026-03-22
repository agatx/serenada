import type {
    SerenadaConfig,
    DiagnosticsReport,
    DiagnosticCheckResult,
    CheckOutcome,
    ConnectivityReport,
    IceProbeReport,
} from './types.js';
import { buildApiUrl, resolveServerBaseUrl, resolveServerUrls } from './serverUrls.js';

interface DiagnosticTokenResponse {
    token?: string;
}

interface RoomIdResponse {
    roomId?: string;
}

interface TurnCredentialsResponse {
    username?: string;
    password?: string;
    uris?: string[];
}

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

    async runConnectivityChecks(): Promise<ConnectivityReport> {
        // Fetch the diagnostic token once and reuse it for the TURN credentials check.
        let tokenForTurn: string | undefined;
        const [roomApi, webSocket, sse, diagnosticToken] = await Promise.all([
            this.runTimedCheck(async () => {
                await this.createRoomId();
            }),
            this.runTimedCheck(async () => {
                await this.testWebSocket();
            }),
            this.runTimedCheck(async () => {
                await this.testSse();
            }),
            this.runTimedCheck(async () => {
                tokenForTurn = await this.fetchDiagnosticToken();
            }),
        ]);

        const turnCredentials = await this.runTimedCheck(async () => {
            const token = tokenForTurn ?? await this.fetchDiagnosticToken();
            await this.fetchTurnCredentials(token);
        });

        return { roomApi, webSocket, sse, diagnosticToken, turnCredentials };
    }

    async runIceProbe(turnsOnly: boolean, onCandidateLog?: (candidate: string) => void): Promise<IceProbeReport> {
        try {
            const token = await this.fetchDiagnosticToken();
            const credentials = await this.fetchTurnCredentials(token);
            const urls = turnsOnly
                ? credentials.uris.filter((uri) => uri.toLowerCase().startsWith('turns:'))
                : credentials.uris;

            return await this.gatherIceCandidates(urls, credentials.username, credentials.password, onCandidateLog);
        } catch (err) {
            return { stunPassed: false, turnPassed: false, logs: [toErrorMessage(err)] };
        }
    }

    async validateServerHost(host: string = this.config.serverHost): Promise<void> {
        const response = await this.fetchJson<RoomIdResponse>(buildApiUrl(host, '/api/room-id'), {
            method: 'GET',
            timeoutMs: 5000,
        });
        if (typeof response.roomId !== 'string' || response.roomId.trim().length === 0) {
            throw new Error('Room ID missing');
        }
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
            const res = await this.fetchResponse(buildApiUrl(this.config.serverHost, '/api/turn-credentials?token=probe'), {
                timeoutMs: 5000,
            });
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

    private async runTimedCheck(block: () => Promise<void>): Promise<CheckOutcome> {
        const start = Date.now();
        try {
            await block();
            return { status: 'passed', latencyMs: Date.now() - start };
        } catch (err) {
            return { status: 'failed', error: toErrorMessage(err) };
        }
    }

    private async createRoomId(): Promise<string> {
        const response = await this.fetchJson<RoomIdResponse>(buildApiUrl(this.config.serverHost, '/api/room-id'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '',
            timeoutMs: 5000,
        });
        if (typeof response.roomId !== 'string' || response.roomId.trim().length === 0) {
            throw new Error('Room ID missing');
        }
        return response.roomId;
    }

    private async fetchDiagnosticToken(): Promise<string> {
        const response = await this.fetchJson<DiagnosticTokenResponse>(buildApiUrl(this.config.serverHost, '/api/diagnostic-token'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '',
            timeoutMs: 5000,
        });
        const token = response.token?.trim();
        if (!token) {
            throw new Error('Diagnostic token missing');
        }
        return token;
    }

    private async fetchTurnCredentials(token: string): Promise<Required<TurnCredentialsResponse>> {
        const response = await this.fetchJson<TurnCredentialsResponse>(
            buildApiUrl(this.config.serverHost, `/api/turn-credentials?token=${encodeURIComponent(token)}`),
            { timeoutMs: 5000 },
        );
        if (
            typeof response.username !== 'string' ||
            response.username.trim().length === 0 ||
            typeof response.password !== 'string' ||
            response.password.trim().length === 0 ||
            !Array.isArray(response.uris) ||
            response.uris.length === 0
        ) {
            throw new Error('Invalid TURN credentials');
        }
        return {
            username: response.username,
            password: response.password,
            uris: response.uris,
        };
    }

    private async testWebSocket(): Promise<void> {
        if (typeof WebSocket === 'undefined') {
            throw new Error('WebSocket not available');
        }

        const { wsUrl } = resolveServerUrls(this.config.serverHost);
        await new Promise<void>((resolve, reject) => {
            let settled = false;
            const socket = new WebSocket(wsUrl);
            const timeout = globalThis.setTimeout(() => {
                finish(() => reject(new Error('WebSocket timeout')));
            }, 5000);

            const finish = (callback: () => void) => {
                if (settled) return;
                settled = true;
                globalThis.clearTimeout(timeout);
                socket.onopen = null;
                socket.onerror = null;
                callback();
                socket.close(1000, 'diagnostics');
            };

            socket.onopen = () => {
                finish(resolve);
            };
            socket.onerror = () => {
                finish(() => reject(new Error('WebSocket failed')));
            };
        });
    }

    private async testSse(): Promise<void> {
        if (typeof EventSource === 'undefined') {
            throw new Error('EventSource not available');
        }

        const baseUrl = resolveServerBaseUrl(this.config.serverHost);
        const sid = `diag-${Math.random().toString(36).slice(2, 10)}`;
        const sseUrl = `${baseUrl}/sse?sid=${encodeURIComponent(sid)}`;

        await new Promise<void>((resolve, reject) => {
            let settled = false;
            const eventSource = new EventSource(sseUrl);
            const timeout = globalThis.setTimeout(() => {
                finish(() => reject(new Error('SSE timeout')));
            }, 5000);

            const finish = (callback: () => void) => {
                if (settled) return;
                settled = true;
                globalThis.clearTimeout(timeout);
                eventSource.close();
                callback();
            };

            eventSource.onopen = async () => {
                try {
                    const response = await this.fetchResponse(sseUrl, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ v: 1, type: 'ping', payload: { ts: Date.now() } }),
                        timeoutMs: 5000,
                    });
                    if (!response.ok) {
                        throw new Error(`SSE ping failed: ${response.status}`);
                    }
                    finish(resolve);
                } catch (err) {
                    finish(() => reject(err));
                }
            };
            eventSource.onerror = () => {
                finish(() => reject(new Error('SSE connection failed')));
            };
        });
    }

    private async gatherIceCandidates(
        urls: string[],
        username: string,
        credential: string,
        onCandidateLog?: (candidate: string) => void,
    ): Promise<IceProbeReport> {
        if (typeof RTCPeerConnection === 'undefined') {
            return { stunPassed: false, turnPassed: false, logs: ['WebRTC not available'] };
        }
        if (urls.length === 0) {
            return { stunPassed: false, turnPassed: false, logs: ['No ICE servers'] };
        }

        const logs: string[] = [];
        const log = (message: string) => {
            logs.push(message);
            onCandidateLog?.(message);
        };

        return await new Promise<IceProbeReport>((resolve) => {
            let settled = false;
            let stunPassed = false;
            let turnPassed = false;
            const iceServersSummary = urls.join(', ');
            const connection = new RTCPeerConnection({
                iceServers: urls.map((url) => (
                    url.toLowerCase().startsWith('stun:')
                        ? { urls: [url] }
                        : { urls: [url], username, credential }
                )),
            });

            const finish = () => {
                if (settled) return;
                settled = true;
                globalThis.clearTimeout(timeout);
                connection.onicecandidate = null;
                connection.onicecandidateerror = null;
                connection.onicegatheringstatechange = null;
                connection.close();
                resolve({ stunPassed, turnPassed, logs, iceServersSummary });
            };

            const timeout = globalThis.setTimeout(() => {
                log('ICE gathering timed out');
                finish();
            }, 10000);

            connection.onicecandidate = (event) => {
                const candidate = event.candidate?.candidate;
                if (!candidate) {
                    finish();
                    return;
                }

                log(candidate);
                if (candidate.includes(' typ srflx ')) {
                    stunPassed = true;
                }
                if (candidate.includes(' typ relay ')) {
                    turnPassed = true;
                }
            };
            connection.onicecandidateerror = (event) => {
                log(`ICE candidate error: ${event.errorText || event.errorCode}`);
            };
            connection.onicegatheringstatechange = () => {
                if (connection.iceGatheringState === 'complete') {
                    finish();
                }
            };

            connection.createDataChannel('diagnostics');
            void connection.createOffer()
                .then((offer) => connection.setLocalDescription(offer))
                .catch((err) => {
                    log(`ICE probe failed: ${toErrorMessage(err)}`);
                    finish();
                });
        });
    }

    private async fetchJson<T>(url: string, options: RequestInit & { timeoutMs: number }): Promise<T> {
        const response = await this.fetchResponse(url, options);
        if (!response.ok) {
            throw new Error(`Request failed: ${response.status}`);
        }
        return await response.json() as T;
    }

    private async fetchResponse(url: string, options: RequestInit & { timeoutMs: number }): Promise<Response> {
        const controller = new AbortController();
        const timeout = globalThis.setTimeout(() => controller.abort(), options.timeoutMs);
        try {
            return await fetch(url, {
                ...options,
                signal: controller.signal,
            });
        } finally {
            globalThis.clearTimeout(timeout);
        }
    }
}

function toErrorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}
