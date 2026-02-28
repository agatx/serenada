import type { SignalingTransport, TransportHandlers, TransportKind } from './types';
import { WebSocketTransport } from './ws';
import { SseTransport } from './sse';

export type { SignalingTransport, TransportHandlers, TransportKind } from './types';
export { WebSocketTransport } from './ws';
export { SseTransport } from './sse';

export const createSignalingTransport = (kind: TransportKind, handlers: TransportHandlers, options?: { sseSid?: string }): SignalingTransport => {
    if (kind === 'sse') {
        return new SseTransport(handlers, { sid: options?.sseSid });
    }
    return new WebSocketTransport(handlers);
};
