const getHostname = (serverHost: string): string | null => {
    try {
        const normalized = serverHost.includes('://') ? serverHost : `http://${serverHost}`;
        return new URL(normalized).hostname;
    } catch {
        return null;
    }
};

const isLoopbackHost = (serverHost: string): boolean => {
    const hostname = getHostname(serverHost)?.replace(/^\[|\]$/g, '');
    if (!hostname) return false;
    return hostname === 'localhost' || hostname === '::1' || hostname.startsWith('127.');
};

const getCurrentPageProtocol = (serverHost: string): 'http:' | 'https:' | null => {
    if (typeof window === 'undefined') return null;
    if (window.location.host !== serverHost) return null;
    if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
        return window.location.protocol;
    }
    return null;
};

export const resolveServerBaseUrl = (serverHost: string): string => {
    const trimmed = serverHost.trim();
    if (!trimmed) {
        throw new Error('serverHost is required');
    }

    try {
        const parsed = new URL(trimmed);
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
            return parsed.origin;
        }
        if (parsed.protocol === 'ws:' || parsed.protocol === 'wss:') {
            parsed.protocol = parsed.protocol === 'ws:' ? 'http:' : 'https:';
            parsed.pathname = '';
            parsed.search = '';
            parsed.hash = '';
            return parsed.origin;
        }
    } catch {
        // Fall back to interpreting serverHost as a bare host[:port].
    }

    const normalizedHost = trimmed.replace(/\/+$/, '');
    const protocol = getCurrentPageProtocol(normalizedHost) ?? (isLoopbackHost(normalizedHost) ? 'http:' : 'https:');
    return `${protocol}//${normalizedHost}`;
};

export const resolveServerUrls = (serverHost: string): { wsUrl: string; httpBaseUrl: string } => {
    const httpBaseUrl = resolveServerBaseUrl(serverHost);
    const baseUrl = new URL(httpBaseUrl);
    const wsProtocol = baseUrl.protocol === 'http:' ? 'ws:' : 'wss:';

    return {
        httpBaseUrl,
        wsUrl: `${wsProtocol}//${baseUrl.host}/ws`,
    };
};

export const buildApiUrl = (serverHost: string, path: string): string => {
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${resolveServerBaseUrl(serverHost)}${normalizedPath}`;
};

export const buildRoomUrl = (serverHost: string, roomId: string): string =>
    `${resolveServerBaseUrl(serverHost)}/call/${roomId}`;
