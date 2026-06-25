import type { SerenadaConfig, CallState, ConnectionEvent, CreateRoomResult, SerenadaSessionHandle } from './types.js';
import { SerenadaSession } from './SerenadaSession.js';
import { createRoomId } from './api/roomApi.js';
import { buildRoomUrl } from './serverUrls.js';
import type { ResolvedSerenadaConfig } from './configValidation.js';
import { requireServerHost, resolveSerenadaConfig } from './configValidation.js';
import { SerenadaServerProvider } from './SerenadaServerProvider.js';
import type { PeerMessage, SignalingProvider } from './SignalingProvider.js';
import { SnapshotError } from './media/captureSnapshot.js';
import {
    clearRecoveryRecord,
    loadRecoveryRecord,
    type RecoveryRecord,
} from './recoveryStorage.js';

/**
 * Main entry point for the Serenada SDK.
 * Create an instance with a {@link SerenadaConfig}, then use {@link join} or
 * {@link createRoom} to start a call.
 */
export class SerenadaCore {
    private readonly config: SerenadaConfig;
    private readonly resolvedConfig: ResolvedSerenadaConfig;

    constructor(config: SerenadaConfig) {
        this.config = config;
        this.resolvedConfig = resolveSerenadaConfig(config);
    }

    /** Check if the current browser supports WebRTC calling. */
    static isSupported(): boolean {
        return typeof RTCPeerConnection !== 'undefined';
    }

    /**
     * Returns a recoverable session if the previous tab/page session ended
     * abruptly (reload, OS-level crash) while a call was active and the
     * persisted reconnect token is still within its TTL. Host apps should
     * call this on launch and surface a "Rejoin call?" prompt — calling
     * {@link join} with the returned `roomId` reattaches under the same CID.
     *
     * Returns `null` when there is nothing to recover.
     */
    static getRecoverableSession(): RecoveryRecord | null {
        return loadRecoveryRecord();
    }

    /**
     * Drops any persisted recovery record. Host apps call this when the
     * user explicitly declines to rejoin, so subsequent launches do not
     * keep prompting for the same dead session.
     */
    static discardRecoverableSession(): void {
        clearRecoveryRecord();
    }

    /** Join an existing call by URL. Returns a session handle. */
    join(url: string, options?: { displayName?: string; peerId?: string }): SerenadaSessionHandle;
    /** Join an existing call by room ID. Returns a session handle. */
    join(options: { roomId: string; displayName?: string; peerId?: string }): SerenadaSessionHandle;
    join(
        urlOrOptions: string | { roomId: string; displayName?: string; peerId?: string },
        extraOptions?: { displayName?: string; peerId?: string },
    ): SerenadaSessionHandle {
        if (!SerenadaCore.isSupported()) {
            return this.createUnsupportedSession();
        }
        const signalingProvider = this.createSignalingProvider();
        if (typeof urlOrOptions === 'string') {
            const roomId = this.parseRoomIdFromUrl(urlOrOptions);
            return new SerenadaSession(this.config, roomId, urlOrOptions, signalingProvider, {
                displayName: extraOptions?.displayName,
                peerId: extraOptions?.peerId,
            });
        }
        const roomUrl = this.resolvedConfig.serverHost
            ? buildRoomUrl(this.resolvedConfig.serverHost, urlOrOptions.roomId)
            : null;
        return new SerenadaSession(this.config, urlOrOptions.roomId, roomUrl, signalingProvider, {
            displayName: urlOrOptions.displayName,
            peerId: urlOrOptions.peerId,
        });
    }

    /** Create a new room. Returns the room URL and ID. Call {@link join} to start the call. */
    async createRoom(): Promise<CreateRoomResult> {
        const serverHost = requireServerHost(this.config);
        const roomId = await createRoomId(serverHost);
        const url = buildRoomUrl(serverHost, roomId);
        return { url, roomId };
    }

    private createUnsupportedSession(): SerenadaSessionHandle {
        const errorState: CallState = {
            phase: 'error',
            roomId: null,
            roomUrl: null,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'connected',
            signalingState: { kind: 'failed', reason: 'webrtcUnavailable' },
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
            onPeerMessage(_cb: (message: PeerMessage) => void) { return noop; },
            onConnectionEvent(_cb: (event: ConnectionEvent) => void) { return noop; },
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
            captureSnapshot: async () => {
                throw new SnapshotError('streamNotActive', 'WebRTC is not supported');
            },
            resumeJoin: noopAsync,
            cancelJoin: noop,
            destroy: noop,
            get localStream() { return null; },
            get remoteStreams() { return emptyMap; },
            getRemoteCameraStream: () => undefined,
            getRemoteContentStream: () => undefined,
            getRemoteStream: () => undefined,
            getLocalContentStream: () => null,
            get independentContentVideoEnabled() { return false; },
            getRemoteIndependentContentVideo: () => false,
            get callStats() { return null; },
            get callQualitySummary() { return null; },
            get hasMultipleCameras() { return false; },
            get canScreenShare() { return false; },
            get isSignalingConnected() { return false; },
            get iceConnectionState(): RTCIceConnectionState { return 'closed'; },
            get peerConnectionState(): RTCPeerConnectionState { return 'closed'; },
            get rtcSignalingState(): RTCSignalingState { return 'closed'; },
            onPermissionsRequired: null,
        };
    }

    private createSignalingProvider(): SignalingProvider {
        if (this.resolvedConfig.serverHost) {
            return new SerenadaServerProvider({
                serverHost: this.resolvedConfig.serverHost,
                transports: this.config.transports,
                logger: this.config.logger,
                videoMediaEnabled: this.config.videoMediaEnabled,
                enableIndependentContentVideo: this.config.enableIndependentContentVideo,
            });
        }
        return this.resolvedConfig.signalingProvider as SignalingProvider;
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
