import type { SerenadaConfig, CallState, CreateRoomResult, SerenadaSessionHandle } from './types.js';
import type { SignalingMessage } from './signaling/types.js';
import { SerenadaSession } from './SerenadaSession.js';
import { createRoomId } from './api/roomApi.js';
import { buildRoomUrl } from './serverUrls.js';

export class SerenadaCore {
    private config: SerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.config = config;
    }

    static isSupported(): boolean {
        return typeof RTCPeerConnection !== 'undefined';
    }

    join(url: string): SerenadaSessionHandle;
    join(options: { roomId: string }): SerenadaSessionHandle;
    join(urlOrOptions: string | { roomId: string }): SerenadaSessionHandle {
        if (!SerenadaCore.isSupported()) {
            return this.createUnsupportedSession();
        }
        if (typeof urlOrOptions === 'string') {
            const roomId = this.parseRoomIdFromUrl(urlOrOptions);
            return new SerenadaSession(this.config, roomId, urlOrOptions);
        }
        const roomUrl = buildRoomUrl(this.config.serverHost, urlOrOptions.roomId);
        return new SerenadaSession(this.config, urlOrOptions.roomId, roomUrl);
    }

    async createRoom(): Promise<CreateRoomResult> {
        if (!SerenadaCore.isSupported()) {
            throw new Error('WebRTC is not supported in this environment');
        }
        const roomId = await createRoomId(this.config.serverHost);
        const url = buildRoomUrl(this.config.serverHost, roomId);
        const session = new SerenadaSession(this.config, roomId, url);
        return { url, roomId, session };
    }

    private createUnsupportedSession(): SerenadaSessionHandle {
        const errorState: CallState = {
            phase: 'error',
            roomId: null,
            roomUrl: null,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'connected',
            activeTransport: null,
            requiredPermissions: null,
            error: { code: 'webrtcUnavailable', message: 'WebRTC is not supported in this browser' },
        };
        const noop = () => {};
        const noopAsync = async () => {};
        const emptyMap = new Map<string, MediaStream>();
        return {
            get state() { return errorState; },
            subscribe(_cb: (state: CallState) => void) { return noop; },
            subscribeToMessages(_cb: (message: SignalingMessage) => void) { return noop; },
            leave: noop,
            end: noop,
            toggleAudio: noop,
            toggleVideo: noop,
            flipCamera: noopAsync,
            setAudioEnabled: noop,
            setVideoEnabled: noop,
            setCameraMode: noop,
            startScreenShare: noopAsync,
            stopScreenShare: noopAsync,
            resumeJoin: noopAsync,
            cancelJoin: noop,
            destroy: noop,
            get localStream() { return null; },
            get remoteStreams() { return emptyMap; },
            get callStats() { return null; },
            get hasMultipleCameras() { return false; },
            get canScreenShare() { return false; },
            get isSignalingConnected() { return false; },
            get iceConnectionState(): RTCIceConnectionState { return 'closed'; },
            get peerConnectionState(): RTCPeerConnectionState { return 'closed'; },
            get rtcSignalingState(): RTCSignalingState { return 'closed'; },
            onPermissionsRequired: null,
        };
    }

    private parseRoomIdFromUrl(url: string): string {
        try {
            const parsed = new URL(url);
            const parts = parsed.pathname.split('/');
            const callIndex = parts.indexOf('call');
            if (callIndex !== -1 && parts[callIndex + 1]) {
                return parts[callIndex + 1];
            }
            // Fallback: last path segment
            return parts[parts.length - 1] || url;
        } catch {
            return url;
        }
    }
}
