export type RoomStatus = {
    count: number;
    maxParticipants?: number;
};

export type RoomStatuses = Record<string, RoomStatus>;

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalizeMaxParticipants(value: unknown, fallback?: number): number | undefined {
    if (typeof value === 'number' && Number.isFinite(value) && value >= 2) {
        return value;
    }
    if (typeof fallback === 'number' && Number.isFinite(fallback) && fallback >= 2) {
        return fallback;
    }
    return undefined;
}

function parseRoomStatus(value: unknown, fallbackMaxParticipants?: number): RoomStatus | null {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return {
            count: value,
            maxParticipants: normalizeMaxParticipants(fallbackMaxParticipants, 2)
        };
    }
    if (!isRecord(value)) {
        return null;
    }

    const count = value.count;
    if (typeof count !== 'number' || !Number.isFinite(count)) {
        return null;
    }

    return {
        count,
        maxParticipants: normalizeMaxParticipants(value.maxParticipants, fallbackMaxParticipants)
    };
}

export function mergeRoomStatusesPayload(previous: RoomStatuses, payload: unknown): RoomStatuses {
    if (!isRecord(payload)) {
        return previous;
    }

    const next: RoomStatuses = { ...previous };
    for (const [rid, value] of Object.entries(payload)) {
        const status = parseRoomStatus(value, previous[rid]?.maxParticipants);
        if (typeof rid === 'string' && status) {
            next[rid] = status;
        }
    }
    return next;
}

export function mergeRoomStatusUpdatePayload(previous: RoomStatuses, payload: unknown): RoomStatuses {
    if (!isRecord(payload)) {
        return previous;
    }

    const rid = payload.rid;
    const count = payload.count;
    if (typeof rid !== 'string' || typeof count !== 'number' || !Number.isFinite(count)) {
        return previous;
    }

    const maxParticipants = normalizeMaxParticipants(payload.maxParticipants, previous[rid]?.maxParticipants);

    return {
        ...previous,
        [rid]: {
            count,
            maxParticipants
        }
    };
}

export function getRoomStatusState(status?: RoomStatus | null): 'hidden' | 'waiting' | 'full' {
    const count = status?.count ?? 0;
    if (count <= 0) {
        return 'hidden';
    }

    const maxParticipants = normalizeMaxParticipants(status?.maxParticipants, 2) ?? 2;
    return count >= maxParticipants ? 'full' : 'waiting';
}
