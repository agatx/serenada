import type { TransportKind } from './transports';

const DEFAULT_TRANSPORTS: TransportKind[] = ['ws', 'sse'];

const normalizeTransport = (value: string): TransportKind | null => {
    const normalized = value.trim().toLowerCase();
    if (normalized === 'ws' || normalized === 'wss') return 'ws';
    if (normalized === 'sse') return 'sse';
    return null;
};

export const parseTransportOrder = (raw?: string | null): TransportKind[] => {
    if (!raw) return DEFAULT_TRANSPORTS;

    const parsed = raw
        .split(',')
        .map(normalizeTransport)
        .filter((kind): kind is TransportKind => !!kind);

    if (parsed.length === 0) return DEFAULT_TRANSPORTS;

    const unique: TransportKind[] = [];
    for (const kind of parsed) {
        if (!unique.includes(kind)) {
            unique.push(kind);
        }
    }

    return unique;
};

export const getConfiguredTransportOrder = (): TransportKind[] => {
    const raw = import.meta.env.TRANSPORTS || import.meta.env.VITE_TRANSPORTS;
    return parseTransportOrder(raw);
};
