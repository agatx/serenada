import type { CallError, CallMediaRole, SerenadaConfig, CallState, ConnectionEvent, CreateRoomResult, SerenadaSessionHandle } from './types.js';
import { ProviderUnavailableError } from './types.js';
import { SerenadaSession } from './SerenadaSession.js';
import { createRoomId } from './api/roomApi.js';
import { buildRoomUrl } from './serverUrls.js';
import { canonicalizeRoomId } from './roomIdentity.js';
import type { ResolvedSerenadaConfig } from './configValidation.js';
import { requireServerHost, resolveSerenadaConfig } from './configValidation.js';
import { SerenadaServerProvider } from './SerenadaServerProvider.js';
import type {
    AnySignalingProvider,
    PeerMessage,
    ProviderCapabilities,
    SignalingProvider,
    SignalingProviderEventMap,
    SignalingProviderEventName,
} from './SignalingProvider.js';
import { isMultiSessionSignalingProvider } from './SignalingProvider.js';
import { SnapshotError } from './media/captureSnapshot.js';
import {
    clearRecoveryRecord,
    loadRecoveryRecord,
    type RecoveryRecord,
} from './recoveryStorage.js';

/**
 * Message carried by {@link ProviderUnavailableError} and the resulting error
 * `CallState` / registry failure when a second concurrent session would bind a
 * single-session v1 `SignalingProvider`. Kept as a constant so the direct-join
 * and registry-join surfaces (and tests) share the exact wording.
 */
export const PROVIDER_SINGLE_SESSION_MESSAGE =
    'Signaling provider is single-session (v1); a session is already using it. '
    + 'Provide a version-2 MultiSessionSignalingProvider for multi-call.';

/**
 * Process-wide map of v1 `SignalingProvider` objects currently bound to a live
 * session, keyed by provider IDENTITY, valued by the OWNING liveness channel.
 * The single-session contract is a property of the provider object, not of a
 * `SerenadaCore` instance: two cores configured with the SAME v1 provider must
 * not both bind it (that cross-wires two sessions onto one channel). A per-core
 * guard cannot see the other core's bind, so the guard lives here. The value is
 * the owning channel so a release is ownership-scoped: a retired channel only
 * clears the entry while it is still the owner, never a bind a NEWER channel
 * (after sequential reuse) has since taken. Cleared when the bound session tears
 * down (see {@link createV1LivenessChannel}) or a failed session construction
 * rolls back.
 */
const boundV1Providers = new WeakMap<AnySignalingProvider, SignalingProvider>();

/**
 * Wrap a single-session v1 `SignalingProvider` so its teardown releases the
 * core's liveness bind, and FENCE the wrapper to its owning session. The session
 * drives the SAME underlying provider through this thin delegate; `disconnect()`
 * is intercepted because the session calls `signaling.disconnect()` on EVERY
 * terminal path (leave/end/destroy AND the remote-end / error resets).
 *
 * The channel is one-shot: the FIRST teardown (a terminal reset OR `destroy()`,
 * whichever runs first) `retire`s it — releasing the bind (via `onRetire`) and
 * detaching every event subscription this channel forwarded to the underlying
 * provider. After a channel retires, a newer session may bind the same provider,
 * so every subsequent forwarded call from THIS (now-dead) channel that could
 * touch the shared provider is suppressed:
 *  - `disconnect()` no longer forwards, so a late `destroy()` after a terminal
 *    reset cannot tear down the NEW owner's transport.
 *  - listener registration and outbound ops (`connect`/`joinRoom`/`leaveRoom`/
 *    `endRoom`/`sendToPeer`/`broadcast`) and the gate setters are no-ops, so the
 *    dead session cannot mutate or emit on a provider it no longer owns.
 * Detaching subscriptions on retire also stops the underlying provider's events
 * from leaking into the dead session between a terminal reset and `destroy()`.
 */
function createV1LivenessChannel(
    underlying: SignalingProvider,
    onRetire: () => void,
): SignalingProvider {
    let retired = false;
    // Unbind thunks for every subscription this channel forwarded to the
    // underlying provider, replayed on retire so events can't leak into the
    // now-dead session. `off` is idempotent, so append-only is safe.
    const detachers: Array<() => void> = [];
    const retire = (): void => {
        if (retired) return;
        retired = true;
        for (const detach of detachers) detach();
        detachers.length = 0;
        onRetire();
    };
    const channel: SignalingProvider = {
        get version(): number { return underlying.version; },
        get capabilities(): ProviderCapabilities | undefined { return underlying.capabilities; },
        connect: () => { if (!retired) underlying.connect(); },
        disconnect: () => {
            // Only forward while THIS channel still owns the provider. After
            // retire (a terminal reset already released the bind and a newer
            // session may now own the provider), a late disconnect() — e.g.
            // destroy() after a terminal error — must NOT close the new owner's
            // transport.
            const ownedProvider = !retired;
            retire();
            if (ownedProvider) underlying.disconnect();
        },
        joinRoom: (roomId, options) => { if (!retired) underlying.joinRoom(roomId, options); },
        leaveRoom: () => { if (!retired) underlying.leaveRoom(); },
        endRoom: () => { if (!retired) underlying.endRoom(); },
        sendToPeer: (peerId, type, payload) => { if (!retired) underlying.sendToPeer(peerId, type, payload); },
        broadcast: (type, payload) => { if (!retired) underlying.broadcast(type, payload); },
        getIceServers: () => underlying.getIceServers(),
        on<K extends SignalingProviderEventName>(event: K, cb: (data: SignalingProviderEventMap[K]) => void): void {
            if (retired) return;
            underlying.on(event, cb);
            detachers.push(() => underlying.off(event, cb));
        },
        off<K extends SignalingProviderEventName>(event: K, cb: (data: SignalingProviderEventMap[K]) => void): void {
            underlying.off(event, cb);
        },
    };
    // Preserve the optional hooks' PRESENCE: the session probes them with
    // `signaling.setTurnRefreshGate?.(...)`, so a delegate that always defined
    // them would call into a provider that never implemented them. Fenced too,
    // so a dead channel never clobbers gates the new owner installed.
    if (underlying.setTurnRefreshGate) {
        channel.setTurnRefreshGate = (gate) => { if (!retired) underlying.setTurnRefreshGate!(gate); };
    }
    if (underlying.setDurableRecoveryGate) {
        channel.setDurableRecoveryGate = (gate) => { if (!retired) underlying.setDurableRecoveryGate!(gate); };
    }
    if (underlying.persistDurableRecoveryNow) {
        channel.persistDurableRecoveryNow = () => { if (!retired) underlying.persistDurableRecoveryNow!(); };
    }
    return channel;
}

/**
 * Main entry point for the Serenada SDK.
 * Create an instance with a {@link SerenadaConfig}, then use {@link join} or
 * {@link createRoom} to start a call.
 */
export class SerenadaCore {
    private readonly config: SerenadaConfig;
    private readonly resolvedConfig: ResolvedSerenadaConfig;
    /**
     * The v1-provider liveness channel currently bound to a live session, or
     * `null`. Identity-based: a second concurrent join against a single-session
     * v1 provider fails while this is set; it clears when the bound session tears
     * down (see {@link createV1LivenessChannel}). Server mode and v2 providers
     * vend a fresh per-session object each join and never set this.
     */
    private v1BoundChannel: SignalingProvider | null = null;

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
        try {
            return this.joinInternal(room, {
                initialMediaRole: 'foreground',
                acquireForegroundLease: true,
                displayName,
                peerId,
            });
        } catch (err) {
            // A single-session v1 provider already backing a live session surfaces
            // on the direct join as an error CallState (parity with the deferred
            // foreground-lease-unavailable failure) rather than throwing out of the
            // non-throwing public join(). The registry path lets this throw so
            // `createOrReuseCall` records a `{ kind: 'failed' }` join result.
            if (err instanceof ProviderUnavailableError) {
                const roomId = typeof urlOrOptions === 'string'
                    ? this.parseRoomIdFromUrl(urlOrOptions)
                    : urlOrOptions.roomId;
                return this.createErrorStandInSession(
                    { code: 'providerUnavailable', message: err.message },
                    roomId,
                );
            }
            throw err;
        }
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
            // Registry path only (public join() returns the stand-in handle above).
            // Never launder the unsupported stand-in (a SerenadaSessionHandle) as a
            // SerenadaSession — it implements none of the registry-only methods
            // (preflight/activate/release/abortForeground) or getters the registry
            // calls. Throw so the registry's createOrReuseCall catch surfaces it as
            // a normal joinFailed instead of publishing a broken managed call.
            throw new Error('WebRTC is not supported in this browser');
        }
        const roomId = 'url' in room ? this.parseRoomIdFromUrl(room.url) : room.roomId;
        const roomUrl = 'url' in room
            ? room.url
            : (this.resolvedConfig.serverHost ? buildRoomUrl(this.resolvedConfig.serverHost, room.roomId) : null);
        // Throws `ProviderUnavailableError` for a second concurrent bind of a v1
        // provider; may bind the v1 liveness channel (see `v1BoundChannel`).
        const signalingProvider = this.createSignalingProvider(roomId);
        try {
            return new SerenadaSession(this.config, roomId, roomUrl, signalingProvider, {
                displayName: options.displayName,
                peerId: options.peerId,
                initialMediaRole: options.initialMediaRole,
                acquireForegroundLease: options.acquireForegroundLease,
            });
        } catch (err) {
            // Session construction failed after the v1 liveness bind: roll it back
            // so the provider is reusable (the channel's `disconnect()` unbind
            // never runs when the session never existed). Release both the
            // per-core reference and the process-wide provider-identity guard,
            // scoped by ownership so an unrelated later bind is never removed.
            if (this.v1BoundChannel === signalingProvider) {
                const provider = this.resolvedConfig.signalingProvider!;
                if (boundV1Providers.get(provider) === signalingProvider) {
                    boundV1Providers.delete(provider);
                }
                this.v1BoundChannel = null;
            }
            throw err;
        }
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
        return this.buildStandInHandle(errorState, 'WebRTC is not supported');
    }

    /**
     * Stand-in error handle for a join that failed before a real session could be
     * constructed (e.g. a single-session v1 provider already backing another live
     * session). Mirrors {@link createUnsupportedSession} but carries the caller's
     * terminal error and room id so a host renders the failure like any other
     * error `CallState`.
     */
    private createErrorStandInSession(error: CallError, roomId: string | null): SerenadaSessionHandle {
        const errorState: CallState = {
            phase: 'error',
            roomId,
            roomUrl: null,
            localParticipant: null,
            remoteParticipants: [],
            connectionStatus: 'disconnected',
            signalingState: { kind: 'failed', reason: error.code },
            activeTransport: null,
            requiredPermissions: null,
            error,
        };
        return this.buildStandInHandle(errorState, error.message);
    }

    /** Build the no-op session handle shared by the terminal stand-ins. */
    private buildStandInHandle(errorState: CallState, snapshotMessage: string): SerenadaSessionHandle {
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
                throw new SnapshotError('streamNotActive', snapshotMessage);
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

    /**
     * Resolve the per-session signaling channel for a join (the F2 seam). Server
     * mode builds a fresh `SerenadaServerProvider` per session (already
     * channel-per-session). A v2 `MultiSessionSignalingProvider` vends one channel
     * per session via `openSession(roomId)`. A single-session v1 provider is
     * returned directly, guarded by the liveness bind so a second concurrent
     * session throws {@link ProviderUnavailableError} instead of cross-wiring.
     */
    private createSignalingProvider(roomId: string): SignalingProvider {
        if (this.resolvedConfig.serverHost) {
            return new SerenadaServerProvider({
                serverHost: this.resolvedConfig.serverHost,
                transports: this.config.transports,
                logger: this.config.logger,
                videoMediaEnabled: this.config.videoMediaEnabled,
                enableIndependentContentVideo: this.config.enableIndependentContentVideo,
            });
        }
        const provider = this.resolvedConfig.signalingProvider!;
        if (isMultiSessionSignalingProvider(provider)) {
            // v2: one channel per session, permanently bound to this canonical room.
            return provider.openSession(roomId);
        }
        // v1: single-session. Refuse a second concurrent bind of the SAME
        // provider object, across ANY core (identity-keyed, process-wide).
        if (boundV1Providers.has(provider)) {
            throw new ProviderUnavailableError(PROVIDER_SINGLE_SESSION_MESSAGE);
        }
        const channel = createV1LivenessChannel(provider, () => {
            // Ownership-scoped release: only clear the bind while THIS channel is
            // still the owner. If a newer channel rebound the same provider after
            // this one retired (sequential reuse), its bind must survive.
            if (boundV1Providers.get(provider) === channel) {
                boundV1Providers.delete(provider);
            }
            if (this.v1BoundChannel === channel) {
                this.v1BoundChannel = null;
            }
        });
        boundV1Providers.set(provider, channel);
        this.v1BoundChannel = channel;
        return channel;
    }

    private parseRoomIdFromUrl(url: string): string {
        return canonicalizeRoomId(url);
    }
}
