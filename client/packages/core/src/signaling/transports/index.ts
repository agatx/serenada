import type { SignalingTransport, TransportHandlers, TransportKind } from './types.js';
import { WebSocketTransport } from './ws.js';
import { SseTransport } from './sse.js';

export type { SignalingTransport, TransportHandlers, TransportKind } from './types.js';
export { WebSocketTransport } from './ws.js';
export { SseTransport } from './sse.js';

export interface CreateTransportOptions {
    wsUrl: string;
    httpBaseUrl: string;
    sseSid?: string;
}

export const createSignalingTransport = (
    kind: TransportKind,
    handlers: TransportHandlers,
    options: CreateTransportOptions,
): SignalingTransport => {
    if (kind === 'sse') {
        return new SseTransport(handlers, options.httpBaseUrl, { sid: options.sseSid });
    }
    return new WebSocketTransport(handlers, options.wsUrl);
};
