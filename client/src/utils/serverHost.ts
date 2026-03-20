import { resolveServerBaseUrl, resolveServerUrls as resolveSdkServerUrls } from '@serenada/core';

export const getConfiguredServerHost = (): string => {
    const wsUrl = import.meta.env.VITE_WS_URL;
    if (wsUrl) {
        try {
            return resolveServerBaseUrl(wsUrl);
        } catch {
            // Ignore invalid override and fall back to the current origin.
        }
    }

    return window.location.origin;
};

export const resolveServerUrls = (serverHost: string): { wsUrl: string; httpBaseUrl: string } =>
    resolveSdkServerUrls(serverHost);
