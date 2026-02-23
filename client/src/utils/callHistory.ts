export interface RecentCall {
    roomId: string;
    startTime: number;
    duration: number; // in seconds
}

const STORAGE_KEY = 'serenada_call_history';
const MAX_RECENT_CALLS = 3;
const ROOM_ID_REGEX = /^[A-Za-z0-9_-]{27}$/;
const UUID_REGEX = /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/;

const isValidRoomId = (roomId: string) => ROOM_ID_REGEX.test(roomId);
const isLegacyRoomId = (roomId: string) => UUID_REGEX.test(roomId);

export const saveCall = (call: RecentCall) => {
    try {
        if (!isValidRoomId(call.roomId)) {
            return;
        }
        const historyJson = localStorage.getItem(STORAGE_KEY);
        let history: RecentCall[] = historyJson ? JSON.parse(historyJson) : [];

        // Remove previous entry for this room if it exists
        history = history.filter(item => item.roomId !== call.roomId);

        // Add new call to the beginning
        history.unshift(call);

        // Limit to MAX_RECENT_CALLS
        history = history.slice(0, MAX_RECENT_CALLS);

        localStorage.setItem(STORAGE_KEY, JSON.stringify(history));
    } catch (error) {
        console.error('Failed to save call history:', error);
    }
};

export const getRecentCalls = (): RecentCall[] => {
    try {
        const historyJson = localStorage.getItem(STORAGE_KEY);
        const history: RecentCall[] = historyJson ? JSON.parse(historyJson) : [];
        const filtered = history.filter(item => isValidRoomId(item.roomId));
        const needsCleanup = filtered.length !== history.length || history.some(item => isLegacyRoomId(item.roomId));
        if (needsCleanup) {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(filtered));
        }
        return filtered;
    } catch (error) {
        console.error('Failed to get call history:', error);
        return [];
    }
};

export const removeRecentCall = (roomId: string) => {
    try {
        const historyJson = localStorage.getItem(STORAGE_KEY);
        if (!historyJson) return;
        const history: RecentCall[] = JSON.parse(historyJson);
        const filtered = history.filter(item => item.roomId !== roomId);
        localStorage.setItem(STORAGE_KEY, JSON.stringify(filtered));
    } catch (error) {
        console.error('Failed to remove recent call:', error);
    }
};
