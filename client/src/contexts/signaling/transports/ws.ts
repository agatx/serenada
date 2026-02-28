import type { SignalingMessage } from '../types';
import type { SignalingTransport, TransportHandlers, TransportKind } from './types';
import { CONNECT_TIMEOUT_MS } from '../../../constants/webrtcResilience';

const getWsUrl = () => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return import.meta.env.VITE_WS_URL || `${protocol}//${window.location.host}/ws`;
};

export class WebSocketTransport implements SignalingTransport {
    kind: TransportKind = 'ws';
    private ws: WebSocket | null = null;
    private handlers: TransportHandlers;
    private open = false;
    private connectTimeout: number | null = null;

    constructor(handlers: TransportHandlers) {
        this.handlers = handlers;
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
        const wsUrl = getWsUrl();
        this.ws = new WebSocket(wsUrl);

        // Timeout for connection to open (handles hanging connections)
        this.connectTimeout = window.setTimeout(() => {
            if (this.ws && this.ws.readyState !== WebSocket.OPEN) {
                console.warn(`[WS] Connection timeout after ${CONNECT_TIMEOUT_MS}ms`);
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
                console.error('Failed to parse message', e);
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
