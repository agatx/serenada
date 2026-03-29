import type { SignalingProvider } from './SignalingProvider.js';
import type { SerenadaConfig } from './types.js';

export const SUPPORTED_SIGNALING_PROVIDER_VERSION = 1;

export interface ResolvedSerenadaConfig {
    serverHost: string | null;
    signalingProvider: SignalingProvider | null;
}

export function resolveSerenadaConfig(config: SerenadaConfig): ResolvedSerenadaConfig {
    const serverHost = typeof config.serverHost === 'string' && config.serverHost.trim().length > 0
        ? config.serverHost
        : null;
    const signalingProvider = config.signalingProvider ?? null;

    if (serverHost && signalingProvider) {
        throw new Error('Provide exactly one of serverHost or signalingProvider');
    }
    if (!serverHost && !signalingProvider) {
        throw new Error('Provide exactly one of serverHost or signalingProvider');
    }
    if (signalingProvider && signalingProvider.version !== SUPPORTED_SIGNALING_PROVIDER_VERSION) {
        throw new Error(`Unsupported signalingProvider version: ${signalingProvider.version}`);
    }

    return { serverHost, signalingProvider };
}

export function requireServerHost(config: SerenadaConfig): string {
    const { serverHost } = resolveSerenadaConfig(config);
    if (!serverHost) {
        throw new Error('requires serverHost');
    }
    return serverHost;
}
