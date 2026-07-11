import type { AnySignalingProvider } from './SignalingProvider.js';
import type { SerenadaConfig } from './types.js';

/**
 * Signaling-provider versions the SDK understands: `1` (single-session
 * `SignalingProvider`) and `2` (app-global `MultiSessionSignalingProvider`).
 */
export const SUPPORTED_SIGNALING_PROVIDER_VERSIONS: readonly number[] = [1, 2];

export interface ResolvedSerenadaConfig {
    serverHost: string | null;
    signalingProvider: AnySignalingProvider | null;
}

export function resolveSerenadaConfig(config: SerenadaConfig): ResolvedSerenadaConfig {
    const trimmedHost = typeof config.serverHost === 'string' ? config.serverHost.trim() : '';
    const serverHost = trimmedHost.length > 0 ? trimmedHost : null;
    const signalingProvider = config.signalingProvider ?? null;

    if (serverHost && signalingProvider) {
        throw new Error('Provide exactly one of serverHost or signalingProvider');
    }
    if (!serverHost && !signalingProvider) {
        throw new Error('Provide exactly one of serverHost or signalingProvider');
    }
    if (signalingProvider && !SUPPORTED_SIGNALING_PROVIDER_VERSIONS.includes(signalingProvider.version)) {
        throw new Error(
            `Unsupported signalingProvider version: ${signalingProvider.version} `
            + `(supported: ${SUPPORTED_SIGNALING_PROVIDER_VERSIONS.join(', ')})`,
        );
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
