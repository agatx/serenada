import React from 'react';
import { initialsFor, type AvatarResolver } from '../hooks/useAvatarResolver.js';

export const RemoteAvatar: React.FC<{
    peerId: string | undefined;
    displayName: string | undefined;
    resolveAvatar: AvatarResolver;
    compact?: boolean;
}> = ({ peerId, displayName, resolveAvatar, compact = false }) => {
    const resolved = resolveAvatar(peerId);
    const initials = initialsFor(displayName);
    const className = `serenada-avatar-circle${compact ? ' compact' : ''}`;

    if (resolved) {
        return (
            <div className={className} aria-hidden="true">
                <img src={resolved.url} alt="" />
            </div>
        );
    }

    return (
        <div className={`${className} initials`} aria-hidden="true">
            {initials || '•'}
        </div>
    );
};
