import { DEFAULT_CAMERA_MODES, type ConfigurableCameraMode } from './types.js';

/**
 * Resolve the configured {@link SerenadaConfig.cameraModes} list into the set
 * of modes actually available on this platform, in the configured order.
 * Web never supports `composite`, so it is silently dropped. `screenShare`
 * is likewise rejected (screen sharing is controlled separately). Duplicates
 * are dropped, preserving the first occurrence. Returning an empty array is
 * valid and signals that video is disabled entirely.
 */
export function resolveCameraModes(configured: readonly ConfigurableCameraMode[] | undefined): ConfigurableCameraMode[] {
    const source = configured ?? DEFAULT_CAMERA_MODES;
    const seen = new Set<ConfigurableCameraMode>();
    const result: ConfigurableCameraMode[] = [];
    for (const mode of source) {
        if (mode === 'composite') continue;
        if (mode !== 'selfie' && mode !== 'world') continue;
        if (seen.has(mode)) continue;
        seen.add(mode);
        result.push(mode);
    }
    return result;
}

/** Next mode in the cycle, or `null` if cycling is not possible. */
export function nextCameraMode(
    modes: readonly ConfigurableCameraMode[],
    current: ConfigurableCameraMode,
): ConfigurableCameraMode | null {
    if (modes.length <= 1) return null;
    const index = modes.indexOf(current);
    if (index === -1) return modes[0];
    return modes[(index + 1) % modes.length];
}
