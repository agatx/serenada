export type RoomStatuses = Record<string, number>;

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function mergeRoomStatusesPayload(previous: RoomStatuses, payload: unknown): RoomStatuses {
    if (!isRecord(payload)) {
        return previous;
    }

    const next: RoomStatuses = { ...previous };
    for (const [rid, value] of Object.entries(payload)) {
        if (typeof rid === 'string' && typeof value === 'number' && Number.isFinite(value)) {
            next[rid] = value;
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

    return {
        ...previous,
        [rid]: count
    };
}
