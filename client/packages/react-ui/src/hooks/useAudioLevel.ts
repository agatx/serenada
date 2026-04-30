import { useEffect, useState } from 'react';
import { AudioLevelMonitor } from '@serenada/core';

let sharedAudioContext: AudioContext | null = null;

function getSharedAudioContext(): AudioContext | undefined {
    if (sharedAudioContext && sharedAudioContext.state !== 'closed') {
        return sharedAudioContext;
    }
    const Ctx = typeof globalThis !== 'undefined'
        ? ((globalThis as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext })
            .AudioContext
            ?? (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
        : undefined;
    if (!Ctx) return undefined;
    try {
        sharedAudioContext = new Ctx();
        return sharedAudioContext;
    } catch {
        return undefined;
    }
}

export function useAudioLevel(stream: MediaStream | null | undefined, enabled = true): number {
    const [level, setLevel] = useState(0);

    useEffect(() => {
        if (!enabled || !stream) return undefined;
        const monitor = new AudioLevelMonitor(stream, { audioContext: getSharedAudioContext() });
        const unsubscribe = monitor.subscribe(setLevel);
        return () => {
            unsubscribe();
            monitor.dispose();
        };
    }, [stream, enabled]);

    return enabled && stream ? level : 0;
}
