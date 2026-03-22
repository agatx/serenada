import type { SerenadaLogger } from '../../types.js';
import type { SignalingMessage } from '../types.js';
import type { SignalingTransport, TransportHandlers, TransportKind } from './types.js';
import { CONNECT_TIMEOUT_MS } from '../../constants.js';
import { formatError } from '../../formatError.js';

export class WebSocketTransport implements SignalingTransport {
    kind: TransportKind = 'ws';
    private ws: WebSocket | null = null;
    private handlers: TransportHandlers;
    private open = false;
    private connectTimeout: number | null = null;
    private wsUrl: string;
    private logger?: SerenadaLogger;

    constructor(handlers: TransportHandlers, wsUrl: string, logger?: SerenadaLogger) {
        this.handlers = handlers;
        this.wsUrl = wsUrl;
        this.logger = logger;
    }

    private clearConnectTimeout() {
        if (this.connectTimeout) {
            window.clearTimeout(this.connectTimeout);
            this.connectTimeout = null;
        }
    }

    private detachSocketHandlers(ws: WebSocket) {
        ws.onopen = null;
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
    }

    connect() {
        this.ws = new WebSocket(this.wsUrl);

        this.connectTimeout = window.setTimeout(() => {
            if (this.ws && this.ws.readyState !== WebSocket.OPEN) {
                this.logger?.log('warning', 'Transport', `WS connection timeout after ${CONNECT_TIMEOUT_MS}ms`);
                this.ws.close();
                this.open = false;
                this.handlers.onClose('timeout');
            }
        }, CONNECT_TIMEOUT_MS);

        this.ws.onopen = () => {
            this.clearConnectTimeout();
            this.open = true;
            this.handlers.onOpen();
        };

        this.ws.onclose = (evt) => {
            this.clearConnectTimeout();
            this.open = false;
            this.handlers.onClose('close', evt);
        };

        this.ws.onerror = (err) => {
            this.clearConnectTimeout();
            this.open = false;
            this.handlers.onClose('error', err);
        };

        this.ws.onmessage = (event) => {
            try {
                const msg: SignalingMessage = JSON.parse(event.data);
                this.handlers.onMessage(msg);
            } catch (e) {
                this.logger?.log('error', 'Transport', `Failed to parse WS message: ${formatError(e)}`);
            }
        };
    }

    close() {
        this.clearConnectTimeout();
        const ws = this.ws;
        this.ws = null;
        if (ws) {
            this.detachSocketHandlers(ws);
            ws.close();
        }
        this.open = false;
    }

    forceClose(reason: string) {
        this.clearConnectTimeout();
        const ws = this.ws;
        this.ws = null;
        if (ws) {
            this.detachSocketHandlers(ws);
            ws.close();
        }
        this.open = false;
        this.handlers.onClose(reason);
    }

    isOpen() {
        return !!this.ws && this.open && this.ws.readyState === WebSocket.OPEN;
    }

    send(msg: SignalingMessage) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(msg));
        }
    }
}
