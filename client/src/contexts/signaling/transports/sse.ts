import type { SignalingMessage } from '../types';
import type { SignalingTransport, TransportHandlers, TransportKind } from './types';
import { CONNECT_TIMEOUT_MS } from '../../../constants/webrtcResilience';

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

export class SseTransport implements SignalingTransport {
    kind: TransportKind = 'sse';
    private es: EventSource | null = null;
    private handlers: TransportHandlers;
    private open = false;
    private sid: string;
    private sseUrl = `${getHttpBaseUrl()}/sse`;
    private connectTimeout: number | null = null;

    constructor(handlers: TransportHandlers, options?: { sid?: string }) {
        this.handlers = handlers;
        this.sid = options?.sid || createSid();
    }

    getSessionId(): string {
        return this.sid;
    }

    private clearConnectTimeout() {
        if (this.connectTimeout) {
            window.clearTimeout(this.connectTimeout);
            this.connectTimeout = null;
        }
    }

    private detachEventSourceHandlers(es: EventSource) {
        es.onopen = null;
        es.onerror = null;
        es.onmessage = null;
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

        this.connectTimeout = window.setTimeout(() => {
            if (this.es && this.es.readyState !== EventSource.OPEN) {
                console.warn(`[SSE] Connection timeout after ${CONNECT_TIMEOUT_MS}ms`);
                this.es.close();
                this.es = null;
                this.open = false;
                this.handlers.onClose('timeout');
            }
        }, CONNECT_TIMEOUT_MS);

        this.es.onopen = () => {
            this.clearConnectTimeout();
            this.open = true;
            this.handlers.onOpen();
        };

        this.es.onerror = (err) => {
            if (!this.es) return;
            if (this.es.readyState === EventSource.CLOSED) {
                this.clearConnectTimeout();
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
        this.clearConnectTimeout();
        const es = this.es;
        this.es = null;
        if (es) {
            this.detachEventSourceHandlers(es);
            es.close();
        }
        this.open = false;
    }

    forceClose(reason: string) {
        this.clearConnectTimeout();
        const es = this.es;
        this.es = null;
        if (es) {
            this.detachEventSourceHandlers(es);
            es.close();
        }
        this.open = false;
        this.handlers.onClose(reason);
    }

    isOpen() {
        return this.open;
    }

    send(msg: SignalingMessage) {
        const url = new URL(this.sseUrl);
        url.searchParams.set('sid', this.sid);
        fetch(url.toString(), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(msg)
        })
            .then(res => {
                if (res.status === 410) {
                    this.open = false;
                    if (this.es) {
                        this.es.close();
                        this.es = null;
                    }
                    this.handlers.onClose('gone');
                }
            })
            .catch(err => {
                console.error('[SSE] Failed to send message', err);
            });
    }
}
