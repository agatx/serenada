import {
    SignalingProviderEmitter,
    type JoinOptions,
    type NegotiationDirtyEvent,
    type PeerEvent,
    type PeerMessage,
    type ProviderCapabilities,
    type RelayFailedEvent,
    type RoomEndedEvent,
    type RoomStateEvent,
    type SignalingProviderParticipant,
} from '../../src/SignalingProvider.js';

export class FakeSignalingProvider extends SignalingProviderEmitter {
    readonly capabilities?: ProviderCapabilities;

    connectCalls = 0;
    disconnectCalls = 0;
    endRoomCalls = 0;
    joinRoomCalls: Array<{ roomId: string; options?: JoinOptions }> = [];
    leaveRoomCalls = 0;
    sendToPeerCalls: Array<{ peerId: string; type: string; payload: unknown }> = [];
    broadcastCalls: Array<{ type: string; payload: unknown }> = [];
    getIceServersCalls = 0;
    getIceServersResults: Array<RTCIceServer[] | Error> = [[]];

    constructor(capabilities: ProviderCapabilities = { handlesReconnection: true }) {
        super();
        this.capabilities = capabilities;
    }

    connect(): void {
        this.connectCalls += 1;
    }

    disconnect(): void {
        this.disconnectCalls += 1;
    }

    joinRoom(roomId: string, options?: JoinOptions): void {
        this.joinRoomCalls.push({ roomId, options });
    }

    leaveRoom(): void {
        this.leaveRoomCalls += 1;
    }

    endRoom(): void {
        this.endRoomCalls += 1;
    }

    sendToPeer(peerId: string, type: string, payload: unknown): void {
        this.sendToPeerCalls.push({ peerId, type, payload });
    }

    broadcast(type: string, payload: unknown): void {
        this.broadcastCalls.push({ type, payload });
    }

    async getIceServers(): Promise<RTCIceServer[]> {
        this.getIceServersCalls += 1;
        const nextResult = this.getIceServersResults.length > 1
            ? this.getIceServersResults.shift()
            : this.getIceServersResults[0];
        if (nextResult instanceof Error) {
            throw nextResult;
        }
        return nextResult ?? [];
    }

    emitConnected(transport = 'ws'): void {
        this.emit('connected', { transport });
    }

    emitDisconnected(reason?: string): void {
        this.emit('disconnected', reason);
    }

    turnRefreshGate: (() => Promise<boolean>) | null = null;
    setTurnRefreshGate(gate: (() => Promise<boolean>) | null): void {
        this.turnRefreshGate = gate;
    }

    emitJoined(event: {
        peerId: string;
        participants: SignalingProviderParticipant[];
        hostPeerId?: string;
        maxParticipants?: number;
    }): void {
        this.emit('joined', event);
    }

    emitRoomStateUpdated(event: RoomStateEvent): void {
        this.emit('roomStateUpdated', event);
    }

    emitPeerJoined(event: PeerEvent): void {
        this.emit('peerJoined', event);
    }

    emitPeerLeft(event: PeerEvent): void {
        this.emit('peerLeft', event);
    }

    emitMessage(message: PeerMessage): void {
        this.emit('message', message);
    }

    emitRoomEnded(event: RoomEndedEvent = { by: 'host', reason: 'room ended' }): void {
        this.emit('roomEnded', event);
    }

    emitError(code: string, message: string): void {
        this.emit('error', { code, message });
    }

    emitIceServersChanged(iceServers: RTCIceServer[]): void {
        this.emit('iceServersChanged', iceServers);
    }

    emitNegotiationDirty(event: NegotiationDirtyEvent): void {
        this.emit('negotiationDirty', event);
    }

    emitRelayFailed(event: RelayFailedEvent): void {
        this.emit('relayFailed', event);
    }
}
