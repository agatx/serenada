import type { SignalingTransport, TransportKind, TransportHandlers } from '../../src/signaling/transports/types';
import type { SignalingMessage } from '../../src/signaling/types';

/**
 * A fake transport for unit-testing SignalingEngine in isolation.
 *
 * Call the `simulate*` helpers from the test side to drive the engine's
 * `onOpen / onClose / onMessage` callbacks without any network I/O.
 */
export class FakeTransport implements SignalingTransport {
    kind: TransportKind;
    connectCalls = 0;
    closeCalls = 0;
    sentMessages: SignalingMessage[] = [];
    private _isOpen = false;
    private handlers: TransportHandlers;

    constructor(kind: TransportKind, handlers: TransportHandlers) {
        this.kind = kind;
        this.handlers = handlers;
    }

    connect(): void {
        this.connectCalls += 1;
    }

    close(): void {
        this.closeCalls += 1;
        this._isOpen = false;
    }

    forceClose(reason: string): void {
        this._isOpen = false;
        this.handlers.onClose(reason);
    }

    send(msg: SignalingMessage): void {
        this.sentMessages.push(msg);
    }

    isOpen(): boolean {
        return this._isOpen;
    }

    // ---- Test-side drivers ----

    simulateOpen(): void {
        this._isOpen = true;
        this.handlers.onOpen();
    }

    simulateMessage(msg: SignalingMessage): void {
        this.handlers.onMessage(msg);
    }

    simulateClose(reason = 'transport-closed'): void {
        this._isOpen = false;
        this.handlers.onClose(reason);
    }
}
