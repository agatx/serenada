import React from 'react';
import type { ConnectionStatus } from '@serenada/core';
import type { SerenadaString } from '../types.js';
import { resolveString } from '../types.js';

export interface StatusOverlayProps {
    connectionStatus: ConnectionStatus;
    strings?: Partial<Record<SerenadaString, string>>;
}

const overlayStyle: React.CSSProperties = {
    position: 'absolute',
    top: 12,
    left: '50%',
    transform: 'translateX(-50%)',
    zIndex: 50,
};

const badgeStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 16px',
    borderRadius: 20,
    background: 'rgba(239,68,68,0.85)',
    color: '#fff',
    fontSize: 13,
    fontWeight: 600,
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)',
};

const dotStyle: React.CSSProperties = {
    width: 8,
    height: 8,
    borderRadius: '50%',
    backgroundColor: '#fff',
    animation: 'serenada-pulse 1.2s ease-in-out infinite',
};

export const StatusOverlay: React.FC<StatusOverlayProps> = ({ connectionStatus, strings }) => {
    if (connectionStatus === 'connected') return null;

    return (
        <div style={overlayStyle}>
            <div style={badgeStyle}>
                <span style={dotStyle} />
                {resolveString('reconnecting', strings)}
            </div>
            <style>{`
                @keyframes serenada-pulse {
                    0%, 100% { opacity: 1; }
                    50% { opacity: 0.3; }
                }
            `}</style>
        </div>
    );
};
