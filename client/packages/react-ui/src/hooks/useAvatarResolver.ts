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
    const mountedRef = useRef(true);
    const [, forceUpdate] = useReducer((x: number) => x + 1, 0);

    useEffect(() => () => {
        mountedRef.current = false;
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
        // Wrap in Promise.resolve so a sync throw inside a non-async provider
        // becomes a rejection and never escapes the render path.
        Promise.resolve().then(() => provider(peerId)).then(
            (source) => {
                if (!mountedRef.current) return;
                cacheRef.current.set(peerId, materializeAvatar(source, objectUrlsRef.current));
                forceUpdate();
            },
            (error) => {
                if (!mountedRef.current) return;
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
    const initials: string[] = [];
    for (const part of displayName.trim().split(/\s+/)) {
        for (const ch of part) {
            if (/[\p{L}\p{N}]/u.test(ch)) {
                initials.push(ch.toUpperCase());
                break;
            }
        }
    }
    if (initials.length === 0) return '';
    if (initials.length === 1) return initials[0];
    return initials[0] + initials[initials.length - 1];
}
