export type RemoteVideoFit = 'cover' | 'contain';

const STORAGE_KEY = 'serenada_remote_video_fit';

function isRemoteVideoFit(value: unknown): value is RemoteVideoFit {
    return value === 'cover' || value === 'contain';
}

export function getPersistedRemoteVideoFit(defaultValue: RemoteVideoFit = 'cover'): RemoteVideoFit {
    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        return isRemoteVideoFit(stored) ? stored : defaultValue;
    } catch (error) {
        console.error('[SerenadaCallFlow] Failed to read remote video fit preference', error);
        return defaultValue;
    }
}

export function persistRemoteVideoFit(value: RemoteVideoFit): void {
    try {
        localStorage.setItem(STORAGE_KEY, value);
    } catch (error) {
        console.error('[SerenadaCallFlow] Failed to persist remote video fit preference', error);
    }
}
