import type { ParticipantCapabilities, ParticipantConnectionStatus, ParticipantContentState, ParticipantMediaPolicy } from './signaling/types.js';

export interface ProviderCapabilities {
    handlesReconnection?: boolean;
}

export interface ConnectionInfo {
    transport?: string;
}

export interface JoinOptions {
    reconnectPeerId?: string;
    maxParticipants?: number;
    displayName?: string;
    /**
     * Host-supplied stable identity. Distinct from `peerId`/cid (which is per-call
     * and server-issued) — lets host applications correlate a participant to
     * their own user identity (avatar lookup, telemetry).
     */
    appPeerId?: string;
}

export interface SignalingProviderParticipant {
    peerId: string;
    joinedAt?: number;
    displayName?: string;
    /** Host-supplied stable identity — see {@link JoinOptions.appPeerId}. */
    appPeerId?: string;
    audioEnabled?: boolean;
    videoEnabled?: boolean;
    /**
     * Multi-call "on hold" flag from the room snapshot (`joined`/`room_state`).
     * Lets a late joiner / reconnecting peer render a held participant that
     * cannot re-broadcast its live `participant_media_state`. Additive; absent
     * for older clients and never-held participants.
     */
    held?: boolean;
    // Wire-reported signaling transport status. Absent = active.
    connectionStatus?: ParticipantConnectionStatus;
    /** Capabilities the participant advertised at join (allowlisted server-side). */
    capabilities?: ParticipantCapabilities;
    /** Per-session media policy the participant advertised at join. */
    mediaPolicy?: ParticipantMediaPolicy;
    /**
     * Latest persisted content (screen share) presentation state from the room
     * snapshot in `joined`/`room_state`. Lets a joining/reconnecting peer
     * surface a share that started before it had a transport, reconciled via the
     * same keep-highest revision rule as live `content_state`.
     */
    contentState?: ParticipantContentState;
}

export interface JoinedEvent {
    peerId: string;
    participants: SignalingProviderParticipant[];
    hostPeerId?: string;
    maxParticipants?: number;
}

export interface RoomStateEvent {
    participants: SignalingProviderParticipant[];
    hostPeerId?: string;
    maxParticipants?: number;
}

export interface PeerEvent {
    peerId: string;
    joinedAt?: number;
    displayName?: string;
    /** Host-supplied stable identity — see {@link JoinOptions.appPeerId}. */
    appPeerId?: string;
}

export interface PeerMessage {
    from: string;
    type: string;
    payload: unknown;
    /**
     * Sender's signaling session id from the wire envelope, when the provider
     * surfaces it. Used to scope per-`(cid, sid)` ordering of `content_state`
     * revisions so a rejoin restarting at `revision:1` is accepted by identity.
     * Absent when the provider does not carry a session id.
     */
    sid?: string;
}

export interface RoomEndedEvent {
    by?: string;
    reason: string;
}

export interface SignalingErrorEvent {
    code: string;
    message: string;
}

/**
 * Server tells an active peer that a previously-suspended peer has reattached
 * AND there was pending negotiation traffic to it during the suspension. The
 * SDK should perform glare-safe fresh negotiation / ICE restart for the named
 * CID. The wire payload field `with` is mapped to the explicit `withCid` here
 * to avoid the JavaScript reserved-word association and to match the Android
 * / iOS event shapes.
 */
export interface NegotiationDirtyEvent {
    /** The CID that needs fresh renegotiation. */
    withCid: string;
}

/** Server tells the sender it could not deliver a relay because the target had no transport. */
export interface RelayFailedEvent {
    /** Server-assigned reason code, e.g. `"target_suspended"`. */
    reason: string;
    /** Target CIDs the relay could not reach. */
    targets: string[];
    /** Original signaling type that failed, e.g. `"offer" | "answer" | "ice"`. */
    of?: string;
}

export interface SignalingProviderEventMap {
    connected: ConnectionInfo | undefined;
    disconnected: string | undefined;
    joined: JoinedEvent;
    roomStateUpdated: RoomStateEvent;
    peerJoined: PeerEvent;
    peerLeft: PeerEvent;
    message: PeerMessage;
    roomEnded: RoomEndedEvent;
    error: SignalingErrorEvent;
    iceServersChanged: RTCIceServer[];
    negotiationDirty: NegotiationDirtyEvent;
    relayFailed: RelayFailedEvent;
}

export type SignalingProviderEventName = keyof SignalingProviderEventMap;

export interface SignalingProvider {
    readonly version: number;
    readonly capabilities?: ProviderCapabilities;
    connect(): void;
    disconnect(): void;
    joinRoom(roomId: string, options?: JoinOptions): void;
    leaveRoom(): void;
    endRoom(): void;
    sendToPeer(peerId: string, type: string, payload: unknown): void;
    broadcast(type: string, payload: unknown): void;
    getIceServers(): Promise<RTCIceServer[]>;
    /**
     * Optional hook: install a gate that returns `false` to skip a scheduled
     * TURN-credential refresh. Providers without periodic refresh (e.g.,
     * loopback/test) may omit this.
     */
    setTurnRefreshGate?(gate: (() => Promise<boolean>) | null): void;
    /**
     * Optional hook: install a gate that returns `false` to suppress writing the
     * durable cross-launch recovery record (multi-call session: only the
     * foreground call owns the record). Providers without durable recovery
     * (e.g. loopback/test) may omit this.
     */
    setDurableRecoveryGate?(gate: (() => boolean) | null): void;
    /**
     * Optional hook: force an immediate durable-recovery persist from current
     * reconnect credentials. Called on resume-to-foreground so the record
     * describes the newly-foregrounded call with no gap.
     */
    persistDurableRecoveryNow?(): void;
    on<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void;
    off<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void;
}

/**
 * App-global signaling service that vends one {@link SignalingProvider} channel
 * per call session (multi-call session, contract §F2). Where a v1
 * {@link SignalingProvider} is a single per-session channel (one listener slot,
 * room-less ops), a v2 provider owns the physical transport once and hands each
 * joining session its own channel — so two concurrent sessions never share a
 * listener slot, a CID, or a `disconnect()`.
 *
 * Configure it exactly like a v1 provider (`SerenadaConfig.signalingProvider`);
 * the SDK detects `version === 2` and calls {@link openSession} once per join.
 * A v1 provider stays supported for single-call use — a second concurrent
 * session against a v1 provider fails with a typed error (see
 * {@link ProviderUnavailableError}). Multi-call requires a v2 provider.
 *
 * Implementor obligations (the SDK cannot enforce another process's transport):
 * each channel is permanently bound to one canonical room and receives only that
 * room's events; `leave`/`end`/`disconnect`/TURN-refresh/reconnect are
 * channel-local; the service owns the physical transport and closing one channel
 * must not disconnect another; queued events are dropped after a channel closes.
 */
export interface MultiSessionSignalingProvider {
    readonly version: 2;
    /** Default capabilities applied to each vended channel. */
    readonly capabilities?: ProviderCapabilities;
    /**
     * Vend a channel permanently bound to one canonical room. Called once per
     * session join. The returned channel is a standard v1 {@link SignalingProvider}
     * that the session drives (connect/joinRoom/leaveRoom/...); its events must be
     * scoped to `roomId`.
     */
    openSession(roomId: string): SignalingProvider;
    /** ICE servers without a call (diagnostics). */
    getIceServers(): Promise<RTCIceServer[]>;
}

/**
 * Either a single-session v1 {@link SignalingProvider} or an app-global v2
 * {@link MultiSessionSignalingProvider}. Accepted by
 * {@link SerenadaConfig.signalingProvider}.
 */
export type AnySignalingProvider = SignalingProvider | MultiSessionSignalingProvider;

/**
 * Runtime discriminator for {@link AnySignalingProvider}. A v2 provider vends
 * per-session channels via {@link MultiSessionSignalingProvider.openSession}; a
 * v1 provider is itself a single-session channel.
 */
export function isMultiSessionSignalingProvider(
    provider: AnySignalingProvider,
): provider is MultiSessionSignalingProvider {
    return provider.version === 2 && typeof (provider as MultiSessionSignalingProvider).openSession === 'function';
}

export class SignalingProviderEmitter implements SignalingProvider {
    readonly version = 1;
    readonly capabilities?: ProviderCapabilities;
    private readonly listeners = new Map<SignalingProviderEventName, Set<(data: unknown) => void>>();

    connect(): void {
        throw new Error('Not implemented');
    }

    disconnect(): void {
        throw new Error('Not implemented');
    }

    joinRoom(_roomId: string, _options?: JoinOptions): void {
        throw new Error('Not implemented');
    }

    leaveRoom(): void {
        throw new Error('Not implemented');
    }

    endRoom(): void {
        throw new Error('Not implemented');
    }

    sendToPeer(_peerId: string, _type: string, _payload: unknown): void {
        throw new Error('Not implemented');
    }

    broadcast(_type: string, _payload: unknown): void {
        throw new Error('Not implemented');
    }

    async getIceServers(): Promise<RTCIceServer[]> {
        throw new Error('Not implemented');
    }

    on<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void {
        let listeners = this.listeners.get(event);
        if (!listeners) {
            listeners = new Set();
            this.listeners.set(event, listeners);
        }
        listeners.add(cb as (data: unknown) => void);
    }

    off<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void {
        this.listeners.get(event)?.delete(cb as (data: unknown) => void);
    }

    protected emit<K extends SignalingProviderEventName>(
        event: K,
        data: SignalingProviderEventMap[K],
    ): void {
        const listeners = this.listeners.get(event);
        if (!listeners) {
            return;
        }
        for (const listener of listeners) {
            listener(data);
        }
    }
}
