import type {
    SerenadaConfig,
    DiagnosticsReport,
    DiagnosticCheckResult,
    CheckOutcome,
    ConnectivityReport,
    IceProbeReport,
} from './types.js';
import { buildApiUrl, resolveServerBaseUrl, resolveServerUrls } from './serverUrls.js';
import type { ResolvedSerenadaConfig } from './configValidation.js';
import { resolveSerenadaConfig } from './configValidation.js';
import { formatError } from './formatError.js';
import { normalizeIceServers } from './iceServers.js';

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

/**
 * Pre-flight diagnostics utility. Checks device capabilities (camera, mic, speaker)
 * and server connectivity (signaling, TURN) before joining a call.
 */
export class SerenadaDiagnostics {
    private readonly resolvedConfig: ResolvedSerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.resolvedConfig = resolveSerenadaConfig(config);
    }

    /** Run all diagnostic checks and return a full report. */
    async runAll(): Promise<DiagnosticsReport> {
        const devicesPromise = this.enumerateDevices();
        const networkPromise = this.checkNetwork();
        const turnPromise = this.checkTurn();
        const signalingPromise = this.resolvedConfig.serverHost
            ? this.checkSignaling()
            : Promise.resolve({ status: 'skipped', reason: 'requires serverHost' } as DiagnosticCheckResult & { transport?: string });

        const [devices, network, signaling, turn] = await Promise.all([
            devicesPromise,
            networkPromise,
            signalingPromise,
            turnPromise,
        ]);
        const camera = this.checkMediaCapability(devices, 'videoinput', 'No camera found');
        const microphone = this.checkMediaCapability(devices, 'audioinput', 'No microphone found');
        const speaker = this.checkDeviceAvailability(devices, 'audiooutput', 'No speaker found');
        return { camera, microphone, speaker, network, signaling, turn, devices };
    }

    /** Test server connectivity: room API, WebSocket, SSE, and TURN credentials. */
    async runConnectivityChecks(): Promise<ConnectivityReport> {
        const serverHost = this.resolvedConfig.serverHost;
        if (!serverHost) throw new Error('requires serverHost');
        // Fetch the diagnostic token once and reuse it for the TURN credentials check.
        let tokenForTurn: string | undefined;
        const [roomApi, webSocket, sse, diagnosticToken] = await Promise.all([
            this.runTimedCheck(async () => {
                await this.createRoomId(serverHost);
            }),
            this.runTimedCheck(async () => {
                await this.testWebSocket(serverHost);
            }),
            this.runTimedCheck(async () => {
                await this.testSse(serverHost);
            }),
            this.runTimedCheck(async () => {
                tokenForTurn = await this.fetchDiagnosticToken(serverHost);
            }),
        ]);

        const turnCredentials = await this.runTimedCheck(async () => {
            const token = tokenForTurn ?? await this.fetchDiagnosticToken(serverHost);
            await this.fetchTurnCredentials(serverHost, token);
        });

        return { roomApi, webSocket, sse, diagnosticToken, turnCredentials };
    }

    /** Probe ICE connectivity using the active server or provider ICE source. */
    async runTurnProbe(turnsOnly: boolean, onCandidateLog?: (candidate: string) => void): Promise<IceProbeReport> {
        try {
            const iceServers = await this.resolveIceServers();
            return await this.gatherIceCandidates(iceServers, turnsOnly, onCandidateLog);
        } catch (err) {
            return { stunPassed: false, turnPassed: false, logs: [formatError(err)] };
        }
    }

    /** Probe ICE connectivity (STUN/TURN) by gathering candidates with a real peer connection. */
    async runIceProbe(turnsOnly: boolean, onCandidateLog?: (candidate: string) => void): Promise<IceProbeReport> {
        return this.runTurnProbe(turnsOnly, onCandidateLog);
    }

    /** Validate that a server host is reachable by requesting a room ID. */
    async validateServerHost(host?: string): Promise<void> {
        if (!host) {
            const resolved = this.resolvedConfig.serverHost;
            if (!resolved) throw new Error('requires serverHost');
            host = resolved;
        }
        const response = await this.fetchJson<RoomIdResponse>(buildApiUrl(host, '/api/room-id'), {
            method: 'GET',
            timeoutMs: 5000,
        });
        if (typeof response.roomId !== 'string' || response.roomId.trim().length === 0) {
            throw new Error('Room ID missing');
        }
    }

    /** Check if a camera is available and authorized. */
    async checkCamera(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkMediaCapability(devices, 'videoinput', 'No camera found');
    }

    /** Check if a microphone is available and authorized. */
    async checkMicrophone(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkMediaCapability(devices, 'audioinput', 'No microphone found');
    }

    /** Check if a speaker/audio output device is available. */
    async checkSpeaker(): Promise<DiagnosticCheckResult> {
        const devices = await this.enumerateDevices();
        return this.checkDeviceAvailability(devices, 'audiooutput', 'No speaker found');
    }

    /** Check if the browser reports network connectivity. */
    async checkNetwork(): Promise<DiagnosticCheckResult> {
        try {
            if (!navigator.onLine) return { status: 'unavailable', reason: 'Browser reports offline' };
            return { status: 'available' };
        } catch (err) {
            return { status: 'skipped', reason: String(err) };
        }
    }

    /** Check if the signaling server is reachable. */
    async checkSignaling(): Promise<DiagnosticCheckResult & { transport?: string }> {
        const serverHost = this.resolvedConfig.serverHost;
        if (!serverHost) {
            return { status: 'skipped', reason: 'requires serverHost' };
        }
        try {
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 5000);
            const res = await fetch(buildApiUrl(serverHost, '/api/room-id'), {
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

    /** Check if the TURN relay endpoint is reachable. */
    async checkTurn(): Promise<DiagnosticCheckResult & { latencyMs?: number }> {
        const serverHost = this.resolvedConfig.serverHost;
        if (!serverHost) {
            try {
                await this.resolveIceServers();
                return { status: 'available' };
            } catch (err) {
                return { status: 'unavailable', reason: String(err) };
            }
        }
        try {
            const start = Date.now();
            const res = await this.fetchResponse(buildApiUrl(serverHost, '/api/turn-credentials?token=probe'), {
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
            return { status: 'failed', error: formatError(err) };
        }
    }

    private async createRoomId(serverHost: string): Promise<string> {
        const response = await this.fetchJson<RoomIdResponse>(buildApiUrl(serverHost, '/api/room-id'), {
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

    private async fetchDiagnosticToken(serverHost: string): Promise<string> {
        const response = await this.fetchJson<DiagnosticTokenResponse>(buildApiUrl(serverHost, '/api/diagnostic-token'), {
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

    private async fetchTurnCredentials(serverHost: string, token: string): Promise<Required<TurnCredentialsResponse>> {
        const response = await this.fetchJson<TurnCredentialsResponse>(
            buildApiUrl(serverHost, `/api/turn-credentials?token=${encodeURIComponent(token)}`),
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

    private async testWebSocket(serverHost: string): Promise<void> {
        if (typeof WebSocket === 'undefined') {
            throw new Error('WebSocket not available');
        }

        const { wsUrl } = resolveServerUrls(serverHost);
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

    private async testSse(serverHost: string): Promise<void> {
        if (typeof EventSource === 'undefined') {
            throw new Error('EventSource not available');
        }

        const baseUrl = resolveServerBaseUrl(serverHost);
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
        iceServers: RTCIceServer[],
        turnsOnly: boolean,
        onCandidateLog?: (candidate: string) => void,
    ): Promise<IceProbeReport> {
        if (typeof RTCPeerConnection === 'undefined') {
            return { stunPassed: false, turnPassed: false, logs: ['WebRTC not available'] };
        }
        const normalizedIceServers = normalizeIceServers(iceServers, turnsOnly);
        if (normalizedIceServers.length === 0) {
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
            const iceServersSummary = normalizedIceServers
                .flatMap((iceServer) => Array.isArray(iceServer.urls) ? iceServer.urls : [iceServer.urls])
                .join(', ');
            const connection = new RTCPeerConnection({
                iceServers: normalizedIceServers,
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
                    log(`ICE probe failed: ${formatError(err)}`);
                    finish();
                });
        });
    }

    private async resolveIceServers(): Promise<RTCIceServer[]> {
        if (this.resolvedConfig.serverHost) {
            const token = await this.fetchDiagnosticToken(this.resolvedConfig.serverHost);
            const credentials = await this.fetchTurnCredentials(this.resolvedConfig.serverHost, token);
            return [{
                urls: credentials.uris,
                username: credentials.username,
                credential: credentials.password,
            }];
        }
        return await (this.resolvedConfig.signalingProvider as NonNullable<ResolvedSerenadaConfig['signalingProvider']>).getIceServers();
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
