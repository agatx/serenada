export const createRoomId = async (serverHost: string): Promise<string> => {
    const protocol = serverHost.startsWith('localhost') || serverHost.startsWith('127.') ? 'http' : 'https';
    const apiUrl = `${protocol}://${serverHost}/api/room-id`;

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
