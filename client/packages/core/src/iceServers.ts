export function normalizeIceServers(iceServers: RTCIceServer[], turnsOnly: boolean): RTCIceServer[] {
    const normalized: RTCIceServer[] = [];
    for (const iceServer of iceServers) {
        const urls = Array.isArray(iceServer.urls) ? iceServer.urls : [iceServer.urls];
        const filteredUrls = urls.filter((url): url is string => {
            if (typeof url !== 'string' || url.length === 0) {
                return false;
            }
            return !turnsOnly || url.toLowerCase().startsWith('turns:');
        });
        if (filteredUrls.length === 0) {
            continue;
        }
        normalized.push({
            ...iceServer,
            urls: filteredUrls,
        });
    }
    return normalized;
}
