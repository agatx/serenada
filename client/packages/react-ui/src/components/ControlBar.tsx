import React from 'react';
import {
    Mic, MicOff,
    Video, VideoOff,
    PhoneOff,
    ScreenShare, ScreenShareOff,
    RotateCcw,
} from 'lucide-react';
import type { SerenadaCallFlowConfig, SerenadaString } from '../types.js';
import { resolveString } from '../types.js';

export interface ControlBarProps {
    audioEnabled: boolean;
    videoEnabled: boolean;
    isScreenSharing: boolean;
    onToggleAudio: () => void;
    onToggleVideo: () => void;
    onFlipCamera?: () => void;
    onToggleScreenShare?: () => void;
    onEndCall: () => void;
    config?: SerenadaCallFlowConfig;
    strings?: Partial<Record<SerenadaString, string>>;
}

const BTN_SIZE = 48;
const ICON_SIZE = 22;

const baseButtonStyle: React.CSSProperties = {
    width: BTN_SIZE,
    height: BTN_SIZE,
    borderRadius: '50%',
    border: 'none',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: 'pointer',
    transition: 'background-color 0.15s',
};

const barStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    padding: '12px 16px',
    background: 'rgba(0,0,0,0.55)',
    borderRadius: 28,
    backdropFilter: 'blur(12px)',
    WebkitBackdropFilter: 'blur(12px)',
};

export const ControlBar: React.FC<ControlBarProps> = ({
    audioEnabled,
    videoEnabled,
    isScreenSharing,
    onToggleAudio,
    onToggleVideo,
    onFlipCamera,
    onToggleScreenShare,
    onEndCall,
    config,
    strings,
}) => {
    const s = strings;
    const screenSharingEnabled = config?.screenSharingEnabled !== false;

    return (
        <div style={barStyle}>
            {/* Audio toggle */}
            <button
                type="button"
                onClick={onToggleAudio}
                title={audioEnabled ? resolveString('muteAudio', s) : resolveString('unmuteAudio', s)}
                aria-label={audioEnabled ? resolveString('muteAudio', s) : resolveString('unmuteAudio', s)}
                style={{
                    ...baseButtonStyle,
                    backgroundColor: audioEnabled ? 'rgba(255,255,255,0.2)' : '#ef4444',
                    color: '#fff',
                }}
            >
                {audioEnabled ? <Mic size={ICON_SIZE} /> : <MicOff size={ICON_SIZE} />}
            </button>

            {/* Video toggle */}
            <button
                type="button"
                onClick={onToggleVideo}
                title={videoEnabled ? resolveString('disableVideo', s) : resolveString('enableVideo', s)}
                aria-label={videoEnabled ? resolveString('disableVideo', s) : resolveString('enableVideo', s)}
                style={{
                    ...baseButtonStyle,
                    backgroundColor: videoEnabled ? 'rgba(255,255,255,0.2)' : '#ef4444',
                    color: '#fff',
                }}
            >
                {videoEnabled ? <Video size={ICON_SIZE} /> : <VideoOff size={ICON_SIZE} />}
            </button>

            {/* Flip camera */}
            {onFlipCamera && (
                <button
                    type="button"
                    onClick={onFlipCamera}
                    title={resolveString('flipCamera', s)}
                    aria-label={resolveString('flipCamera', s)}
                    style={{
                        ...baseButtonStyle,
                        backgroundColor: 'rgba(255,255,255,0.2)',
                        color: '#fff',
                    }}
                >
                    <RotateCcw size={ICON_SIZE} />
                </button>
            )}

            {/* Screen share toggle */}
            {screenSharingEnabled && onToggleScreenShare && (
                <button
                    type="button"
                    onClick={onToggleScreenShare}
                    title={isScreenSharing ? resolveString('stopScreenShare', s) : resolveString('startScreenShare', s)}
                    aria-label={isScreenSharing ? resolveString('stopScreenShare', s) : resolveString('startScreenShare', s)}
                    style={{
                        ...baseButtonStyle,
                        backgroundColor: isScreenSharing ? '#3b82f6' : 'rgba(255,255,255,0.2)',
                        color: '#fff',
                    }}
                >
                    {isScreenSharing ? <ScreenShareOff size={ICON_SIZE} /> : <ScreenShare size={ICON_SIZE} />}
                </button>
            )}

            {/* End call */}
            <button
                type="button"
                onClick={onEndCall}
                title={resolveString('endCall', s)}
                aria-label={resolveString('endCall', s)}
                style={{
                    ...baseButtonStyle,
                    backgroundColor: '#ef4444',
                    color: '#fff',
                    width: BTN_SIZE + 16,
                    borderRadius: 24,
                }}
            >
                <PhoneOff size={ICON_SIZE} />
            </button>
        </div>
    );
};
