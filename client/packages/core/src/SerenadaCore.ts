import type { CallMediaRole, SerenadaConfig, CallState, ConnectionEvent, CreateRoomResult, SerenadaSessionHandle } from './types.js';
import { SerenadaSession } from './SerenadaSession.js';
import { createRoomId } from './api/roomApi.js';
import { buildRoomUrl } from './serverUrls.js';
import { canonicalizeRoomId } from './roomIdentity.js';
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
        // Public single-call join always foregrounds and routes through the
        // process-wide arbiter (mode `direct`): `acquireForegroundLease` makes the
        // session constructor take the lease, so a second concurrent direct join
        // (or a join while a registry owns the process) fails fast with
        // `ForegroundLeaseUnavailable`. Delegates to `joinInternal` so the
        // URL/roomId -> session construction lives in one place.
        const room = typeof urlOrOptions === 'string'
            ? { url: urlOrOptions }
            : { roomId: urlOrOptions.roomId };
        const displayName = typeof urlOrOptions === 'string' ? extraOptions?.displayName : urlOrOptions.displayName;
        const peerId = typeof urlOrOptions === 'string' ? extraOptions?.peerId : urlOrOptions.peerId;
        return this.joinInternal(room, {
            initialMediaRole: 'foreground',
            acquireForegroundLease: true,
            displayName,
            peerId,
        });
    }

    /**
     * @internal Registry-internal join with an explicit initial media role
     * (multi-call session, Phase 2). NOT a public `join()` parameter: the public
     * `join()` always foregrounds. The (Phase 3) `SerenadaCallRegistry` calls this
     * to create a `'held'` call (no capture, no coordinator, no lease — stable
     * senders are still created during negotiation) or a `'foreground'` call.
     *
     * For `'foreground'` the caller is responsible for the arbiter lease (the
     * registry acquires/releases it). For `'held'` no lease is taken. The session
     * self-acquires the `direct`-mode lease only when the caller passes
     * `acquireForegroundLease: true`; the public `join()` is the only path that does.
     */
    joinInternal(
        room: { url: string } | { roomId: string },
        options: {
            initialMediaRole: CallMediaRole;
            displayName?: string;
            peerId?: string;
            /**
             * When `true`, the session takes the process-wide foreground lease at
             * construction. The public `join()` sets this; the registry leaves it
             * unset and manages the lease itself.
             */
            acquireForegroundLease?: boolean;
        } = { initialMediaRole: 'foreground' },
    ): SerenadaSession {
        if (!SerenadaCore.isSupported()) {
            return this.createUnsupportedSession() as unknown as SerenadaSession;
        }
        const signalingProvider = this.createSignalingProvider();
        const roomId = 'url' in room ? this.parseRoomIdFromUrl(room.url) : room.roomId;
        const roomUrl = 'url' in room
            ? room.url
            : (this.resolvedConfig.serverHost ? buildRoomUrl(this.resolvedConfig.serverHost, room.roomId) : null);
        return new SerenadaSession(this.config, roomId, roomUrl, signalingProvider, {
            displayName: options.displayName,
            peerId: options.peerId,
            initialMediaRole: options.initialMediaRole,
            acquireForegroundLease: options.acquireForegroundLease,
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
        return canonicalizeRoomId(url);
    }
}
