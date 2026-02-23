export interface SavedRoom {
    roomId: string;
    name: string;
    createdAt: number;
    host?: string;
    lastJoinedAt?: number;
}

export type SaveRoomResult = 'ok' | 'quota_exceeded' | 'error' | 'invalid_input';

const STORAGE_KEY = 'serenada_saved_rooms';
const MAX_SAVED_ROOMS = 50;
const MAX_ROOM_NAME_LENGTH = 120;
const ROOM_ID_REGEX = /^[A-Za-z0-9_-]{27}$/;

const isValidRoomId = (roomId: string) => ROOM_ID_REGEX.test(roomId);

const normalizeName = (name: string): string | null => {
    const trimmed = name.trim();
    if (!trimmed) return null;
    return trimmed.slice(0, MAX_ROOM_NAME_LENGTH);
};

const normalizeHost = (hostInput: string | null | undefined): string | null => {
    if (!hostInput) return null;
    const raw = hostInput.trim();
    if (!raw) return null;

    let withScheme = raw;
    if (!raw.startsWith('http://') && !raw.startsWith('https://')) {
        withScheme = `https://${raw}`;
    }

    try {
        const url = new URL(withScheme);
        const host = url.hostname.trim().toLowerCase();
        if (!host) return null;
        if (url.username || url.password || url.search || url.hash) return null;
        if (url.pathname && url.pathname !== '/') return null;

        const portStr = url.port;
        if (portStr) {
            const port = parseInt(portStr, 10);
            if (isNaN(port) || port <= 0 || port > 65535) return null;
            return `${host}:${port}`;
        }
        return host;
    } catch {
        return null;
    }
};

const isQuotaExceededError = (error: unknown): boolean => {
    if (!(error instanceof DOMException)) return false;
    return (
        error.name === 'QuotaExceededError' ||
        error.name === 'NS_ERROR_DOM_QUOTA_REACHED' ||
        error.code === 22 ||
        error.code === 1014
    );
};

export const getSavedRooms = (): SavedRoom[] => {
    try {
        const json = localStorage.getItem(STORAGE_KEY);
        if (!json) return [];
        const parsed = JSON.parse(json);
        if (!Array.isArray(parsed)) return [];

        const rooms: SavedRoom[] = [];
        const seenIds = new Set<string>();

        for (const item of parsed) {
            if (!item || typeof item !== 'object') continue;
            const roomId = item.roomId;
            if (typeof roomId !== 'string' || !isValidRoomId(roomId)) continue;
            if (seenIds.has(roomId)) continue;

            const name = normalizeName(item.name || '');
            if (!name) continue;

            const createdAt = typeof item.createdAt === 'number' ? Math.max(1, item.createdAt) : Date.now();
            const host = normalizeHost(item.host);
            const lastJoinedAt = typeof item.lastJoinedAt === 'number' && item.lastJoinedAt > 0 ? item.lastJoinedAt : undefined;

            seenIds.add(roomId);
            rooms.push({ roomId, name, createdAt, host: host || undefined, lastJoinedAt });

            if (rooms.length >= MAX_SAVED_ROOMS) break;
        }

        if (rooms.length !== parsed.length) {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(rooms));
        }

        return rooms;
    } catch (error) {
        console.error('Failed to parse saved rooms', error);
        return [];
    }
};

export const saveRoom = (room: SavedRoom): SaveRoomResult => {
    if (!isValidRoomId(room.roomId)) return 'invalid_input';
    const cleanName = normalizeName(room.name);
    if (!cleanName) return 'invalid_input';
    const cleanHost = normalizeHost(room.host);

    try {
        let rooms = getSavedRooms();
        const existing = rooms.find(r => r.roomId === room.roomId);
        
        rooms = rooms.filter(r => r.roomId !== room.roomId);
        
        const newRoom: SavedRoom = {
            roomId: room.roomId,
            name: cleanName,
            createdAt: Math.max(1, room.createdAt),
            host: cleanHost || undefined,
            lastJoinedAt: (room.lastJoinedAt || existing?.lastJoinedAt) || undefined
        };
        
        rooms.unshift(newRoom);
        rooms = rooms.slice(0, MAX_SAVED_ROOMS);
        
        localStorage.setItem(STORAGE_KEY, JSON.stringify(rooms));
        return 'ok';
    } catch (error) {
        if (isQuotaExceededError(error)) {
            console.warn('Saved rooms storage quota exceeded', error);
            return 'quota_exceeded';
        }
        console.error('Failed to save room', error);
        return 'error';
    }
};

export const removeRoom = (roomId: string) => {
    if (!roomId || !roomId.trim()) return;
    try {
        const rooms = getSavedRooms();
        const filtered = rooms.filter(r => r.roomId !== roomId);
        if (rooms.length === filtered.length) return;
        
        localStorage.setItem(STORAGE_KEY, JSON.stringify(filtered));
    } catch (error) {
        console.error('Failed to remove saved room', error);
    }
};

export const markRoomJoined = (roomId: string, joinedAt: number = Date.now()): boolean => {
    if (!roomId || !roomId.trim()) return false;
    try {
        const rooms = getSavedRooms();
        const index = rooms.findIndex(r => r.roomId === roomId);
        if (index === -1) return false;
        
        const cleanJoinedAt = Math.max(1, joinedAt);
        if (rooms[index].lastJoinedAt === cleanJoinedAt) return false;
        
        rooms[index] = { ...rooms[index], lastJoinedAt: cleanJoinedAt };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(rooms));
        return true;
    } catch (error) {
        console.error('Failed to mark saved room joined', error);
        return false;
    }
};
