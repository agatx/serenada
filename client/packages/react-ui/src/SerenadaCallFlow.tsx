import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SerenadaCore } from '@serenada/core';
import type { SerenadaSession } from '@serenada/core';
import { useCallState } from './hooks/useCallState.js';
import { ControlBar } from './components/ControlBar.js';
import { StatusOverlay } from './components/StatusOverlay.js';
import { DebugPanel } from './components/DebugPanel.js';
import { ParticipantGrid } from './components/ParticipantGrid.js';
import type { ParticipantInfo } from './components/ParticipantGrid.js';
import { SerenadaPermissions } from './SerenadaPermissions.js';
import type { CallFlowProps, SerenadaString } from './types.js';
import { resolveString } from './types.js';
import { IDLE_STATE } from './hooks/constants.js';

// ---------------------------------------------------------------------------
// SerenadaCallFlow
// ---------------------------------------------------------------------------

export const SerenadaCallFlow: React.FC<CallFlowProps> = ({
    url,
    session: externalSession,
    serverHost,
    config,
    theme,
    strings,
    onDismiss,
    onStatsUpdate,
}) => {
    // --- Inject keyframes on mount -------------------------------------------
    useEffect(() => { ensureKeyframes(); }, []);

    // --- Session management ---------------------------------------------------
    const internalSessionRef = useRef<SerenadaSession | null>(null);
    const [internalSession, setInternalSession] = useState<SerenadaSession | null>(null);

    // URL-first mode: create a session from the URL
    useEffect(() => {
        if (externalSession || !url) return;

        let host: string;
        try {
            host = serverHost ?? new URL(url).host;
        } catch {
            return;
        }
        const core = new SerenadaCore({ serverHost: host });
        const sess = core.join(url);
        internalSessionRef.current = sess;
        setInternalSession(sess);

        return () => {
            sess.destroy();
            internalSessionRef.current = null;
            setInternalSession(null);
        };
    }, [url, externalSession, serverHost]);

    const session = externalSession ?? internalSession;
    const state = useCallState(session ?? null);
    const effectiveState = session ? state : IDLE_STATE;

    // --- Stats forwarding (legacy bridge) ------------------------------------
    useEffect(() => {
        if (!onStatsUpdate || !session) return;
        const interval = window.setInterval(() => {
            onStatsUpdate(session.callStats);
        }, 1000);
        return () => window.clearInterval(interval);
    }, [onStatsUpdate, session]);

    // --- Permission handling (URL-first mode) --------------------------------
    const [permissionDenied, setPermissionDenied] = useState(false);

    useEffect(() => {
        if (!session) return;
        // eslint-disable-next-line react-hooks/immutability -- session is an SDK object with mutable callback properties
        session.onPermissionsRequired = (perms) => {
            void (async () => {
                const granted = await SerenadaPermissions.request(perms);
                if (granted) {
                    session.resumeJoin();
                } else {
                    setPermissionDenied(true);
                }
            })();
        };
        return () => {
            session.onPermissionsRequired = null;
        };
    }, [session]);

    // --- Callbacks ------------------------------------------------------------
    const handleEndCall = useCallback(() => {
        if (session) {
            session.leave();
        }
        onDismiss?.();
    }, [session, onDismiss]);

    const handleToggleAudio = useCallback(() => {
        session?.toggleAudio();
    }, [session]);

    const handleToggleVideo = useCallback(() => {
        session?.toggleVideo();
    }, [session]);

    const handleFlipCamera = useCallback(() => {
        void session?.flipCamera();
    }, [session]);

    const handleToggleScreenShare = useCallback(() => {
        if (!session) return;
        const lp = effectiveState.localParticipant;
        if (lp?.cameraMode === 'screenShare') {
            void session.stopScreenShare();
        } else {
            void session.startScreenShare();
        }
    }, [session, effectiveState.localParticipant]);

    const handleGrantPermissions = useCallback(() => {
        if (!session) return;
        void (async () => {
            const perms = effectiveState.requiredPermissions ?? ['camera', 'microphone'];
            const granted = await SerenadaPermissions.request(perms);
            if (granted) {
                setPermissionDenied(false);
                session.resumeJoin();
            } else {
                setPermissionDenied(true);
            }
        })();
    }, [session, effectiveState.requiredPermissions]);

    const handleCancel = useCallback(() => {
        session?.cancelJoin();
        onDismiss?.();
    }, [session, onDismiss]);

    // --- Derived values -------------------------------------------------------
    const s = strings;
    const bgColor = theme?.backgroundColor ?? '#0f172a';
    const isScreenSharing = effectiveState.localParticipant?.cameraMode === 'screenShare';
    const audioEnabled = effectiveState.localParticipant?.audioEnabled ?? true;
    const videoEnabled = effectiveState.localParticipant?.videoEnabled ?? true;

    const participants: ParticipantInfo[] = useMemo(() => {
        const result: ParticipantInfo[] = [];
        if (effectiveState.localParticipant) {
            result.push({
                cid: effectiveState.localParticipant.cid,
                label: resolveString('you', s),
                isLocal: true,
                audioEnabled: effectiveState.localParticipant.audioEnabled,
                videoEnabled: effectiveState.localParticipant.videoEnabled,
            });
        }
        for (const rp of effectiveState.remoteParticipants) {
            result.push({
                cid: rp.cid,
                isLocal: false,
                audioEnabled: rp.audioEnabled,
                videoEnabled: rp.videoEnabled,
            });
        }
        return result;
    }, [effectiveState.localParticipant, effectiveState.remoteParticipants, s]);

    // --- Render phases --------------------------------------------------------

    // Idle / Joining — spinner
    if (effectiveState.phase === 'idle' || effectiveState.phase === 'joining') {
        return (
            <div style={{ ...containerStyle, backgroundColor: bgColor }}>
                <div style={centerContentStyle}>
                    <div style={spinnerStyle} />
                    <p style={messageTextStyle}>{resolveString('joiningCall', s)}</p>
                </div>
            </div>
        );
    }

    // Awaiting permissions
    if (effectiveState.phase === 'awaitingPermissions') {
        return (
            <div style={{ ...containerStyle, backgroundColor: bgColor }}>
                <div style={centerContentStyle}>
                    <h2 style={headingStyle}>{resolveString('permissionRequired', s)}</h2>
                    <p style={messageTextStyle}>{resolveString('permissionPrompt', s)}</p>
                    {permissionDenied && (
                        <p style={{ ...messageTextStyle, color: '#ef4444' }}>
                            Permission denied. Please allow access in your browser settings.
                        </p>
                    )}
                    <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
                        <button
                            type="button"
                            onClick={handleGrantPermissions}
                            style={primaryButtonStyle}
                        >
                            {resolveString('grantPermissions', s)}
                        </button>
                        <button
                            type="button"
                            onClick={handleCancel}
                            style={secondaryButtonStyle}
                        >
                            {resolveString('cancel', s)}
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // Waiting for remote participant
    if (effectiveState.phase === 'waiting') {
        return (
            <div style={{ ...containerStyle, backgroundColor: bgColor }}>
                <StatusOverlay connectionStatus={effectiveState.connectionStatus} strings={s} />
                <div style={centerContentStyle}>
                    <p style={messageTextStyle}>{resolveString('waitingForOther', s)}</p>
                    {effectiveState.roomUrl && (
                        <WaitingLinkDisplay url={effectiveState.roomUrl} strings={s} />
                    )}
                </div>
                <div style={controlBarContainerStyle}>
                    <ControlBar
                        audioEnabled={audioEnabled}
                        videoEnabled={videoEnabled}
                        isScreenSharing={isScreenSharing}
                        onToggleAudio={handleToggleAudio}
                        onToggleVideo={handleToggleVideo}
                        onFlipCamera={handleFlipCamera}
                        onEndCall={handleEndCall}
                        config={config}
                        strings={s}
                    />
                </div>
            </div>
        );
    }

    // In call — full UI
    if (effectiveState.phase === 'inCall') {
        return (
            <div style={{ ...containerStyle, backgroundColor: bgColor }}>
                <StatusOverlay connectionStatus={effectiveState.connectionStatus} strings={s} />

                {config?.debugOverlayEnabled && (
                    <DebugPanel stats={session?.callStats ?? null} strings={s} />
                )}

                <div style={videoAreaStyle}>
                    <ParticipantGrid
                        localStream={session?.localStream ?? null}
                        remoteStreams={session?.remoteStreams ?? EMPTY_STREAMS}
                        participants={participants}
                        strings={s}
                    />
                </div>

                <div style={controlBarContainerStyle}>
                    <ControlBar
                        audioEnabled={audioEnabled}
                        videoEnabled={videoEnabled}
                        isScreenSharing={isScreenSharing}
                        onToggleAudio={handleToggleAudio}
                        onToggleVideo={handleToggleVideo}
                        onFlipCamera={handleFlipCamera}
                        onToggleScreenShare={handleToggleScreenShare}
                        onEndCall={handleEndCall}
                        config={config}
                        strings={s}
                    />
                </div>
            </div>
        );
    }

    // Error
    if (effectiveState.phase === 'error') {
        return (
            <div style={{ ...containerStyle, backgroundColor: bgColor }}>
                <div style={centerContentStyle}>
                    <h2 style={{ ...headingStyle, color: '#ef4444' }}>{resolveString('errorOccurred', s)}</h2>
                    {effectiveState.error && (
                        <p style={messageTextStyle}>{effectiveState.error.message}</p>
                    )}
                    <button type="button" onClick={handleEndCall} style={primaryButtonStyle}>
                        {resolveString('endCall', s)}
                    </button>
                </div>
            </div>
        );
    }

    // Ending
    return (
        <div style={{ ...containerStyle, backgroundColor: bgColor }}>
            <div style={centerContentStyle}>
                <p style={messageTextStyle}>{resolveString('callEnded', s)}</p>
            </div>
        </div>
    );
};

// ---------------------------------------------------------------------------
// WaitingLinkDisplay (sub-component)
// ---------------------------------------------------------------------------

const WaitingLinkDisplay: React.FC<{ url: string; strings?: Partial<Record<SerenadaString, string>> }> = ({ url, strings }) => {
    const [copied, setCopied] = useState(false);

    const handleCopy = useCallback(() => {
        void navigator.clipboard.writeText(url).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    }, [url]);

    return (
        <div style={{ marginTop: 16, textAlign: 'center' }}>
            <p style={{ color: '#94a3b8', fontSize: 13, margin: '0 0 8px' }}>
                {resolveString('shareLink', strings)}
            </p>
            <div style={linkBoxStyle}>
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1, fontSize: 13 }}>
                    {url}
                </span>
                <button type="button" onClick={handleCopy} style={copyButtonStyle}>
                    {copied ? resolveString('copied', strings) : 'Copy'}
                </button>
            </div>
        </div>
    );
};

// ---------------------------------------------------------------------------
// Shared empty map (stable reference)
// ---------------------------------------------------------------------------
const EMPTY_STREAMS = new Map<string, MediaStream>();

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const containerStyle: React.CSSProperties = {
    position: 'relative',
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    color: '#e2e8f0',
};

const centerContentStyle: React.CSSProperties = {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
};

const videoAreaStyle: React.CSSProperties = {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
};

const controlBarContainerStyle: React.CSSProperties = {
    position: 'absolute',
    bottom: 24,
    left: '50%',
    transform: 'translateX(-50%)',
    zIndex: 40,
};

const headingStyle: React.CSSProperties = {
    fontSize: 20,
    fontWeight: 600,
    margin: '0 0 8px',
    color: '#e2e8f0',
};

const messageTextStyle: React.CSSProperties = {
    fontSize: 15,
    color: '#94a3b8',
    margin: '4px 0',
    textAlign: 'center',
};

const primaryButtonStyle: React.CSSProperties = {
    padding: '10px 24px',
    borderRadius: 8,
    border: 'none',
    background: '#3b82f6',
    color: '#fff',
    fontSize: 14,
    fontWeight: 600,
    cursor: 'pointer',
};

const secondaryButtonStyle: React.CSSProperties = {
    padding: '10px 24px',
    borderRadius: 8,
    border: '1px solid rgba(255,255,255,0.2)',
    background: 'transparent',
    color: '#e2e8f0',
    fontSize: 14,
    fontWeight: 500,
    cursor: 'pointer',
};

const linkBoxStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 12px',
    background: 'rgba(255,255,255,0.08)',
    borderRadius: 8,
    maxWidth: 420,
    color: '#e2e8f0',
};

const copyButtonStyle: React.CSSProperties = {
    flexShrink: 0,
    padding: '4px 12px',
    borderRadius: 6,
    border: 'none',
    background: 'rgba(255,255,255,0.15)',
    color: '#e2e8f0',
    fontSize: 12,
    cursor: 'pointer',
    fontWeight: 500,
};

const KEYFRAMES_ID = 'serenada-callflow-keyframes';
function ensureKeyframes(): void {
    if (typeof document === 'undefined') return;
    if (document.getElementById(KEYFRAMES_ID)) return;
    const style = document.createElement('style');
    style.id = KEYFRAMES_ID;
    style.textContent = `@keyframes serenada-spin { to { transform: rotate(360deg); } }`;
    document.head.appendChild(style);
}

const spinnerStyle: React.CSSProperties = {
    width: 36,
    height: 36,
    border: '3px solid rgba(255,255,255,0.15)',
    borderTopColor: '#3b82f6',
    borderRadius: '50%',
    animation: 'serenada-spin 0.8s linear infinite',
    marginBottom: 16,
};
