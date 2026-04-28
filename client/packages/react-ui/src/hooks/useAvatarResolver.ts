import { useCallback, useEffect, useReducer, useRef } from 'react';
import type { AvatarProvider, AvatarSource } from '../types.js';

type ResolvedAvatar = { url: string } | null;
type CacheEntry = ResolvedAvatar | 'pending';

export type AvatarResolver = (peerId: string | undefined) => ResolvedAvatar;

/**
 * Lazily resolves and caches avatars for the lifetime of the call UI.
 * Each `peerId` is sent through the provider at most once. The returned
 * resolver is synchronous: it returns `null` until the avatar is ready, then
 * triggers a re-render with the resolved URL on the next call.
 */
export function useAvatarResolver(provider: AvatarProvider | undefined): AvatarResolver {
    const cacheRef = useRef<Map<string, CacheEntry>>(new Map());
    const objectUrlsRef = useRef<string[]>([]);
    const [, forceUpdate] = useReducer((x: number) => x + 1, 0);

    useEffect(() => () => {
        for (const url of objectUrlsRef.current) {
            URL.revokeObjectURL(url);
        }
        objectUrlsRef.current = [];
        cacheRef.current.clear();
    }, []);

    return useCallback((peerId: string | undefined): ResolvedAvatar => {
        if (!provider || !peerId) {
            return null;
        }
        const cached = cacheRef.current.get(peerId);
        if (cached === 'pending') return null;
        if (cached !== undefined) return cached;

        cacheRef.current.set(peerId, 'pending');
        provider(peerId).then(
            (source) => {
                cacheRef.current.set(peerId, materializeAvatar(source, objectUrlsRef.current));
                forceUpdate();
            },
            (error) => {
                console.warn('[serenada] avatarProvider rejected for', peerId, error);
                cacheRef.current.set(peerId, null);
                forceUpdate();
            },
        );
        return null;
    }, [provider]);
}

function materializeAvatar(source: AvatarSource | null, objectUrls: string[]): ResolvedAvatar {
    if (!source) return null;
    switch (source.kind) {
        case 'url':
            return source.url ? { url: source.url } : null;
        case 'image':
            return source.image.src ? { url: source.image.src } : null;
        case 'bytes': {
            const blob = new Blob([source.bytes as BlobPart]);
            const url = URL.createObjectURL(blob);
            objectUrls.push(url);
            return { url };
        }
    }
}

export function initialsFor(displayName: string | undefined): string {
    if (!displayName) return '';
    const trimmed = displayName.trim();
    if (!trimmed) return '';
    const parts = trimmed.split(/\s+/);
    if (parts.length === 1) {
        return [...parts[0]][0]?.toUpperCase() ?? '';
    }
    const first = [...parts[0]][0] ?? '';
    const last = [...parts[parts.length - 1]][0] ?? '';
    return (first + last).toUpperCase();
}
