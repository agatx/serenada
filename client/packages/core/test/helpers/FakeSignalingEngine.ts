import type { RoomState, SignalingMessage } from '../../src/signaling/types.js';
import type { TransportKind } from '../../src/signaling/transports/types.js';
import type { RoomStatuses } from '../../src/signaling/roomStatuses.js';

/**
 * Fake SignalingEngine for testing SerenadaSession.
 *
 * Mirrors the public property surface of the real SignalingEngine so that
 * SerenadaSession's `rebuildState()` reads correct values.  All outbound
 * calls (connect, joinRoom, leaveRoom, etc.) are tracked in arrays for
 * assertion.
 */
export class FakeSignalingEngine {
    // --- Public state (read by SerenadaSession.rebuildState) ---
    isConnected = false;
    activeTransport: TransportKind | null = null;
    clientId: string | null = null;
    roomState: RoomState | null = null;
    turnToken: string | null = null;
    turnTokenTTLMs: number | null = null;
    error: string | null = null;
    roomStatuses: RoomStatuses = {};

    // --- Call tracking ---
    connectCalls = 0;
    destroyCalls = 0;
    endRoomCalls = 0;
    joinRoomCalls: { roomId: string; options?: { createMaxParticipants?: number } }[] = [];
    leaveRoomCalls: { options?: { preserveReconnectState?: boolean } }[] = [];
    sendMessageCalls: { type: string; payload?: Record<string, unknown>; to?: string }[] = [];

    // --- Listeners ---
    private messageListeners: ((msg: SignalingMessage) => void)[] = [];
    private stateListeners: (() => void)[] = [];

    connect(): void {
        this.connectCalls++;
    }

    destroy(): void {
        this.destroyCalls++;
    }

    sendMessage(type: string, payload?: Record<string, unknown>, to?: string): void {
        this.sendMessageCalls.push({ type, payload, to });
    }

    joinRoom(roomId: string, options?: { createMaxParticipants?: number }): void {
        this.joinRoomCalls.push({ roomId, options });
    }

    leaveRoom(options?: { preserveReconnectState?: boolean }): void {
        this.leaveRoomCalls.push({ options });
    }

    endRoom(): void {
        this.endRoomCalls++;
    }

    watchRooms(_rids: string[]): void { /* no-op */ }

    clearError(): void {
        this.error = null;
        this.notifyStateChange();
    }

    subscribeToMessages(cb: (msg: SignalingMessage) => void): () => void {
        this.messageListeners.push(cb);
        return () => {
            this.messageListeners = this.messageListeners.filter(l => l !== cb);
        };
    }

    onStateChange(cb: () => void): () => void {
        this.stateListeners.push(cb);
        return () => {
            this.stateListeners = this.stateListeners.filter(l => l !== cb);
        };
    }

    get currentRoom(): string | null {
        return null;
    }

    // --- Test helpers ---

    /** Apply a partial state update and notify all state listeners (triggers rebuildState). */
    emit(partial: Partial<Pick<FakeSignalingEngine, 'isConnected' | 'activeTransport' | 'clientId' | 'roomState' | 'turnToken' | 'turnTokenTTLMs' | 'error' | 'roomStatuses'>>): void {
        Object.assign(this, partial);
        this.notifyStateChange();
    }

    /** Emit a signaling message to all message listeners. */
    emitMessage(msg: SignalingMessage): void {
        [...this.messageListeners].forEach(cb => cb(msg));
    }

    private notifyStateChange(): void {
        [...this.stateListeners].forEach(cb => cb());
    }
}
