import type { SignalingMessage } from './types';

export type TransportKind = 'ws' | 'sse';

export type TransportHandlers = {
    onOpen: () => void;
    onClose: (reason: string, err?: unknown) => void;
    onMessage: (msg: SignalingMessage) => void;
};

export interface SignalingTransport {
    kind: TransportKind;
    connect: () => void;
    close: () => void;
    send: (msg: SignalingMessage) => void;
    isOpen: () => boolean;
}

const getWsUrl = () => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return import.meta.env.VITE_WS_URL || `${protocol}//${window.location.host}/ws`;
};

const getHttpBaseUrl = () => {
    const wsUrl = import.meta.env.VITE_WS_URL;
    if (wsUrl) {
        const url = new URL(wsUrl);
        url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
        url.pathname = '';
        url.search = '';
        url.hash = '';
        return url.toString().replace(/\/$/, '');
    }
    return window.location.origin;
};

const createSid = () => {
    if (window.crypto && window.crypto.getRandomValues) {
        const bytes = new Uint8Array(8);
        window.crypto.getRandomValues(bytes);
        return `S-${Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')}`;
    }
    return `S-${Math.random().toString(16).slice(2, 10)}${Math.random().toString(16).slice(2, 10)}`;
};

export class WebSocketTransport implements SignalingTransport {
    kind: TransportKind = 'ws';
    private ws: WebSocket | null = null;
    private handlers: TransportHandlers;
    private open = false;

    constructor(handlers: TransportHandlers) {
        this.handlers = handlers;
    }

    connect() {
        const wsUrl = getWsUrl();
        this.ws = new WebSocket(wsUrl);

        this.ws.onopen = () => {
            this.open = true;
            this.handlers.onOpen();
        };

        this.ws.onclose = (evt) => {
            this.open = false;
            this.handlers.onClose('close', evt);
        };

        this.ws.onerror = (err) => {
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

export class SseTransport implements SignalingTransport {
    kind: TransportKind = 'sse';
    private es: EventSource | null = null;
    private handlers: TransportHandlers;
    private open = false;
    private sid = createSid();
    private sseUrl = `${getHttpBaseUrl()}/sse`;

    constructor(handlers: TransportHandlers) {
        this.handlers = handlers;
    }

    connect() {
        if (typeof EventSource === 'undefined') {
            this.open = false;
            this.handlers.onClose('unsupported');
            return;
        }
        const url = new URL(this.sseUrl);
        url.searchParams.set('sid', this.sid);
        this.es = new EventSource(url.toString());

        this.es.onopen = () => {
            this.open = true;
            this.handlers.onOpen();
        };

        this.es.onerror = (err) => {
            if (!this.es) return;
            if (this.es.readyState === EventSource.CLOSED) {
                this.open = false;
                this.handlers.onClose('close', err);
            }
        };

        this.es.onmessage = (event) => {
            try {
                const msg: SignalingMessage = JSON.parse(event.data);
                this.handlers.onMessage(msg);
            } catch (e) {
                console.error('Failed to parse message', e);
            }
        };
    }

    close() {
        if (this.es) {
            this.es.close();
            this.es = null;
        }
        this.open = false;
    }

    isOpen() {
        return this.open;
    }

    send(msg: SignalingMessage) {
        fetch(this.sseUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-SSE-SID': this.sid
            },
            body: JSON.stringify(msg)
        }).catch(err => {
            console.error('[SSE] Failed to send message', err);
        });
    }
}

export const createSignalingTransport = (kind: TransportKind, handlers: TransportHandlers): SignalingTransport => {
    if (kind === 'sse') {
        return new SseTransport(handlers);
    }
    return new WebSocketTransport(handlers);
};
