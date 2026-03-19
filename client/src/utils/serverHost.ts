export const getConfiguredServerHost = (): string => {
    const wsUrl = import.meta.env.VITE_WS_URL;
    if (wsUrl) {
        try {
            return new URL(wsUrl).host;
        } catch {
            // Ignore invalid override and fall back to the current origin.
        }
    }

    return window.location.host;
};

export const resolveServerUrls = (serverHost: string): { wsUrl: string; httpBaseUrl: string } => {
    const isLocal = serverHost.startsWith('localhost') || serverHost.startsWith('127.');
    const protocol = isLocal ? 'http' : 'https';
    const wsProtocol = isLocal ? 'ws' : 'wss';

    return {
        wsUrl: `${wsProtocol}://${serverHost}/ws`,
        httpBaseUrl: `${protocol}://${serverHost}`,
    };
};
