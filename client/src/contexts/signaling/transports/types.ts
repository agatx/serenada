import type { SignalingMessage } from '../types';

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
    forceClose?: (reason: string) => void;
    send: (msg: SignalingMessage) => void;
    isOpen: () => boolean;
    getSessionId?: () => string;
}
