import type { ParticipantConnectionStatus } from './signaling/types.js';

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
    // Wire-reported signaling transport status. Absent = active.
    connectionStatus?: ParticipantConnectionStatus;
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
    on<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void;
    off<K extends SignalingProviderEventName>(
        event: K,
        cb: (data: SignalingProviderEventMap[K]) => void,
    ): void;
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
