export const createRoomId = async (wsUrl?: string): Promise<string> => {
    let apiUrl = '/api/room-id';
    if (wsUrl) {
        const url = new URL(wsUrl);
        url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
        url.pathname = '/api/room-id';
        url.search = '';
        url.hash = '';
        apiUrl = url.toString();
    }

    const res = await fetch(apiUrl, { method: 'POST' });
    if (!res.ok) {
        throw new Error(`Room ID request failed: ${res.status}`);
    }

    const data = await res.json();
    if (!data?.roomId) {
        throw new Error('Room ID missing from response');
    }
    return data.roomId;
};
