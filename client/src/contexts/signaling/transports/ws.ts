import type { SignalingMessage } from '../types';
import type { SignalingTransport, TransportHandlers, TransportKind } from './types';

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

    connect() {
        const wsUrl = getWsUrl();
        this.ws = new WebSocket(wsUrl);

        // 2-second timeout for connection to open (handles hanging connections)
        this.connectTimeout = window.setTimeout(() => {
            if (this.ws && this.ws.readyState !== WebSocket.OPEN) {
                console.warn('[WS] Connection timeout after 2s');
                this.ws.close();
                this.open = false;
                this.handlers.onClose('timeout');
            }
        }, 2000);

        this.ws.onopen = () => {
            if (this.connectTimeout) {
                window.clearTimeout(this.connectTimeout);
                this.connectTimeout = null;
            }
            this.open = true;
            this.handlers.onOpen();
        };

        this.ws.onclose = (evt) => {
            if (this.connectTimeout) {
                window.clearTimeout(this.connectTimeout);
                this.connectTimeout = null;
            }
            this.open = false;
            this.handlers.onClose('close', evt);
        };

        this.ws.onerror = (err) => {
            if (this.connectTimeout) {
                window.clearTimeout(this.connectTimeout);
                this.connectTimeout = null;
            }
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
        if (this.connectTimeout) {
            window.clearTimeout(this.connectTimeout);
            this.connectTimeout = null;
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.open = false;
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
