import { useEffect, useState } from 'react';
import { AudioLevelMonitor } from '@serenada/core';

/**
 * Returns a smoothed audio level (0..1) for the audio track of the given
 * stream. Returns 0 when `enabled` is false, when the stream has no audio
 * track, or when the Web Audio API is unavailable.
 *
 * Use to drive a voice-activity indicator next to a participant tile.
 */
export function useAudioLevel(stream: MediaStream | null | undefined, enabled = true): number {
    const [level, setLevel] = useState(0);

    useEffect(() => {
        if (!enabled || !stream) return undefined;
        const monitor = new AudioLevelMonitor(stream);
        const unsubscribe = monitor.subscribe(setLevel);
        return () => {
            unsubscribe();
            monitor.dispose();
        };
    }, [stream, enabled]);

    return enabled && stream ? level : 0;
}
