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
}

export interface SignalingProviderParticipant {
    peerId: string;
    joinedAt?: number;
    displayName?: string;
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
