import React, { useEffect, useRef } from 'react';
import type { SerenadaString } from '../types.js';
import { resolveString } from '../types.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ParticipantInfo {
    cid: string;
    label?: string;
    isLocal?: boolean;
    audioEnabled?: boolean;
    videoEnabled?: boolean;
}

export interface ParticipantGridProps {
    localStream: MediaStream | null;
    remoteStreams: Map<string, MediaStream>;
    participants: ParticipantInfo[];
    /** Fill or fit the video tile. Defaults to 'cover'. */
    videoFit?: 'cover' | 'contain';
    strings?: Partial<Record<SerenadaString, string>>;
}

// ---------------------------------------------------------------------------
// Single video tile
// ---------------------------------------------------------------------------

const VideoTile: React.FC<{
    stream: MediaStream;
    muted: boolean;
    mirrored: boolean;
    label?: string;
    videoFit: 'cover' | 'contain';
}> = ({ stream, muted, mirrored, label, videoFit }) => {
    const ref = useRef<HTMLVideoElement>(null);

    useEffect(() => {
        if (ref.current && ref.current.srcObject !== stream) {
            ref.current.srcObject = stream;
        }
    }, [stream]);

    return (
        <div style={tileContainerStyle}>
            <video
                ref={ref}
                autoPlay
                playsInline
                muted={muted}
                style={{
                    ...videoStyle,
                    objectFit: videoFit,
                    transform: mirrored ? 'scaleX(-1)' : undefined,
                }}
            />
            {label && <div style={labelStyle}>{label}</div>}
        </div>
    );
};

// ---------------------------------------------------------------------------
// Grid component
// ---------------------------------------------------------------------------

export const ParticipantGrid: React.FC<ParticipantGridProps> = ({
    localStream,
    remoteStreams,
    participants,
    videoFit = 'cover',
    strings,
}) => {
    const totalTiles = participants.length;
    const columns = totalTiles <= 1 ? 1 : 2;

    return (
        <div style={{ ...gridStyle, gridTemplateColumns: `repeat(${columns}, 1fr)` }}>
            {participants.map(p => {
                const stream = p.isLocal ? localStream : remoteStreams.get(p.cid);
                if (!stream) {
                    return (
                        <div key={p.cid} style={{ ...tileContainerStyle, backgroundColor: '#1e293b' }}>
                            <div style={placeholderStyle}>
                                {p.label ?? (p.isLocal ? resolveString('you', strings) : resolveString('remote', strings))}
                            </div>
                        </div>
                    );
                }
                return (
                    <VideoTile
                        key={p.cid}
                        stream={stream}
                        muted={!!p.isLocal}
                        mirrored={!!p.isLocal}
                        label={p.label ?? (p.isLocal ? resolveString('you', strings) : undefined)}
                        videoFit={videoFit}
                    />
                );
            })}
        </div>
    );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const gridStyle: React.CSSProperties = {
    display: 'grid',
    gap: 4,
    width: '100%',
    height: '100%',
    overflow: 'hidden',
};

const tileContainerStyle: React.CSSProperties = {
    position: 'relative',
    width: '100%',
    height: '100%',
    overflow: 'hidden',
    borderRadius: 8,
    backgroundColor: '#0f172a',
};

const videoStyle: React.CSSProperties = {
    width: '100%',
    height: '100%',
    display: 'block',
};

const labelStyle: React.CSSProperties = {
    position: 'absolute',
    bottom: 8,
    left: 8,
    padding: '2px 8px',
    borderRadius: 4,
    background: 'rgba(0,0,0,0.55)',
    color: '#fff',
    fontSize: 12,
    fontWeight: 500,
};

const placeholderStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '100%',
    height: '100%',
    color: '#64748b',
    fontSize: 14,
    fontWeight: 500,
};
