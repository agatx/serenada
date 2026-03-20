import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'react-qr-code';
import {
    Copy,
    Maximize2,
    Mic,
    MicOff,
    Minimize2,
    PhoneOff,
    Pin,
    RotateCcw,
    ScreenShare,
    ScreenShareOff,
    Video,
    VideoOff,
} from 'lucide-react';
import {
    SerenadaCore,
    clampStageTileAspectRatio,
    computeLayout,
    computeStageLayout,
    STAGE_TILE_GAP_PX,
    type CallScene,
    type CallStats,
    type ContentSource,
    type LayoutResult,
    type SerenadaSessionHandle,
} from '@serenada/core';
import { DebugPanel } from './components/DebugPanel.js';
import { StatusOverlay } from './components/StatusOverlay.js';
import { useCallState } from './hooks/useCallState.js';
import { SerenadaPermissions } from './SerenadaPermissions.js';
import type { CallFlowProps } from './types.js';
import { resolveString } from './types.js';
import { IDLE_STATE, EMPTY_STREAMS } from './hooks/constants.js';
import { ensureCallFlowStyles } from './callFlowStyles.js';
import { playJoinChime } from './utils/audio.js';
import {
    getPersistedRemoteVideoFit,
    persistRemoteVideoFit,
    type RemoteVideoFit,
} from './utils/remoteVideoFit.js';

interface RemoteStageTile {
    cid: string;
    stream: MediaStream;
    aspectRatio: number;
}

const MOBILE_BROWSER_RE = /Mobi|Android|iPhone|iPad|iPod/i;

function getStreamAspectRatio(stream: MediaStream): number | null {
    const track = stream.getVideoTracks()[0];
    if (!track) return null;
    const settings = track.getSettings?.();
    if (!settings) return null;
    if (typeof settings.aspectRatio === 'number' && settings.aspectRatio > 0) {
        return settings.aspectRatio;
    }
    if (typeof settings.width === 'number' && typeof settings.height === 'number' && settings.height > 0) {
        return settings.width / settings.height;
    }
    return null;
}

function isMobileBrowser(): boolean {
    return typeof navigator !== 'undefined' && MOBILE_BROWSER_RE.test(navigator.userAgent);
}

const VideoTile: React.FC<{
    stream: MediaStream;
    label?: string;
    muted?: boolean;
    mirrored?: boolean;
    pinned?: boolean;
    tileStyle?: React.CSSProperties;
    videoFit?: RemoteVideoFit;
    onAspectRatioChange?: (ratio: number) => void;
    onClick?: () => void;
}> = ({
    stream,
    label,
    muted = false,
    mirrored = false,
    pinned = false,
    tileStyle,
    videoFit = 'cover',
    onAspectRatioChange,
    onClick,
}) => {
    const videoRef = useRef<HTMLVideoElement>(null);

    useEffect(() => {
        if (videoRef.current && videoRef.current.srcObject !== stream) {
            videoRef.current.srcObject = stream;
        }
    }, [stream]);

    useEffect(() => {
        if (!onAspectRatioChange || !videoRef.current) return;

        const video = videoRef.current;
        const updateAspectRatio = () => {
            if (video.videoWidth > 0 && video.videoHeight > 0) {
                onAspectRatioChange(clampStageTileAspectRatio(video.videoWidth / video.videoHeight));
            }
        };

        updateAspectRatio();
        video.addEventListener('loadedmetadata', updateAspectRatio);
        video.addEventListener('resize', updateAspectRatio);
        return () => {
            video.removeEventListener('loadedmetadata', updateAspectRatio);
            video.removeEventListener('resize', updateAspectRatio);
        };
    }, [onAspectRatioChange, stream]);

    const handlePointerUp = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
        if (!onClick) return;
        event.stopPropagation();
        onClick();
    }, [onClick]);

    const handleKeyDown = useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
        if (!onClick) return;
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onClick();
        }
    }, [onClick]);

    return (
        <div
            className="video-stage-tile"
            style={tileStyle}
            onPointerUp={onClick ? handlePointerUp : undefined}
            onKeyDown={onClick ? handleKeyDown : undefined}
            role={onClick ? 'button' : undefined}
            tabIndex={onClick ? 0 : undefined}
        >
            <video
                ref={videoRef}
                autoPlay
                playsInline
                muted={muted}
                className="video-stage-remote"
                style={{
                    objectFit: videoFit,
                    transform: mirrored ? 'scaleX(-1)' : undefined,
                }}
            />
            {label && <div className="video-grid-label">{label}</div>}
            {pinned && (
                <div className="video-stage-pin-indicator" aria-hidden="true">
                    <Pin size={16} />
                </div>
            )}
        </div>
    );
};

export const SerenadaCallFlow: React.FC<CallFlowProps> = ({
    url,
    session: externalSession,
    serverHost,
    config,
    theme,
    strings,
    waitingActions,
    onDismiss,
    onStatsUpdate,
}) => {
    useEffect(() => { ensureCallFlowStyles(); }, []);

    const internalSessionRef = useRef<SerenadaSessionHandle | null>(null);
    const [internalSession, setInternalSession] = useState<SerenadaSessionHandle | null>(null);
    const usesInternalSession = !externalSession;

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
        // eslint-disable-next-line react-hooks/set-state-in-effect -- internal SDK session is initialized from the URL-first effect
        setInternalSession(sess);

        return () => {
            sess.destroy();
            internalSessionRef.current = null;
            setInternalSession(null);
        };
    }, [externalSession, serverHost, url]);

    const session: SerenadaSessionHandle | null = externalSession ?? internalSession;
    const state = useCallState(session ?? null);
    const effectiveState = session ? state : IDLE_STATE;
    const localParticipant = effectiveState.localParticipant;
    const localStream = session?.localStream ?? null;
    const remoteStreams = session?.remoteStreams ?? EMPTY_STREAMS;
    const remoteStreamEntries = useMemo(() => Array.from(remoteStreams.entries()), [remoteStreams]);
    const remoteStream = remoteStreamEntries.length === 1 ? remoteStreamEntries[0][1] : null;
    const participantCount = (localParticipant ? 1 : 0) + effectiveState.remoteParticipants.length;
    const isMultiParty = remoteStreamEntries.length > 1;
    const isScreenSharing = localParticipant?.cameraMode === 'screenShare';
    const isCameraOff = localParticipant?.videoEnabled === false;
    const isMuted = localParticipant?.audioEnabled === false;
    const canScreenShare = session?.canScreenShare === true && !isMobileBrowser();
    const hasMultipleCameras = session?.hasMultipleCameras === true;
    const showScreenShareControl = config?.screenSharingEnabled !== false && canScreenShare;
    const inviteControlsEnabled = config?.inviteControlsEnabled !== false;
    const shareUrl = effectiveState.roomUrl ?? (typeof window !== 'undefined' ? window.location.href : '');
    const shouldMirrorLocalVideo = localParticipant?.cameraMode === 'selfie' && !isScreenSharing;

    const [permissionDenied, setPermissionDenied] = useState(false);
    const [copied, setCopied] = useState(false);
    const [isLocalLarge, setIsLocalLarge] = useState(false);
    const [areControlsVisible, setAreControlsVisible] = useState(true);
    const [showRecoveringBadge, setShowRecoveringBadge] = useState(false);
    const [showWaiting, setShowWaiting] = useState(true);
    const [showDebug, setShowDebug] = useState(false);
    const [debugStats, setDebugStats] = useState<CallStats | null>(null);
    const [remoteVideoFit, setRemoteVideoFit] = useState<RemoteVideoFit>(() => getPersistedRemoteVideoFit());
    const [pinnedParticipantId, setPinnedParticipantId] = useState<string | null>(null);
    const [remoteContentState, setRemoteContentState] = useState<{ cid: string; contentType: ContentSource['type'] } | null>(null);
    const [remoteStageAspectRatios, setRemoteStageAspectRatios] = useState<Record<string, number>>({});
    const [stageViewportSize, setStageViewportSize] = useState(() => ({
        width: typeof window !== 'undefined' ? window.innerWidth : 0,
        height: typeof window !== 'undefined' ? window.innerHeight : 0,
    }));

    const lastCameraModeRef = useRef(localParticipant?.cameraMode ?? 'selfie');
    const idleTimeoutRef = useRef<number | null>(null);
    const wereControlsLastHiddenByAutoHideRef = useRef(false);
    const isControlsAutoHideEnabledRef = useRef(true);
    const waitingTimerRef = useRef<number | null>(null);
    const prevParticipantCountRef = useRef(0);
    const stageViewportRef = useRef<HTMLDivElement | null>(null);
    const debugTapRef = useRef(0);
    const debugTapTimeoutRef = useRef<number | null>(null);

    useEffect(() => {
        if (!onStatsUpdate || !session) return;
        const interval = window.setInterval(() => {
            onStatsUpdate(session.callStats);
        }, 1000);
        return () => window.clearInterval(interval);
    }, [onStatsUpdate, session]);

    useEffect(() => {
        if (!showDebug || !session) {
            setDebugStats(null);
            return;
        }

        const refreshStats = () => {
            setDebugStats(session.callStats ? { ...session.callStats } : null);
        };

        refreshStats();
        const interval = window.setInterval(refreshStats, 1000);
        return () => window.clearInterval(interval);
    }, [session, showDebug]);

    useEffect(() => () => {
        if (debugTapTimeoutRef.current) {
            window.clearTimeout(debugTapTimeoutRef.current);
            debugTapTimeoutRef.current = null;
        }
    }, []);

    useEffect(() => {
        if (!usesInternalSession || !internalSession) return;
        internalSession.onPermissionsRequired = (permissions) => {
            void (async () => {
                const granted = await SerenadaPermissions.request(permissions);
                if (granted) {
                    setPermissionDenied(false);
                    await internalSession.resumeJoin();
                } else {
                    setPermissionDenied(true);
                }
            })();
        };
        return () => {
            internalSession.onPermissionsRequired = null;
        };
    }, [internalSession, usesInternalSession]);

    useEffect(() => {
        if (localParticipant?.cameraMode !== lastCameraModeRef.current) {
            if (localParticipant?.cameraMode === 'world') {
                // eslint-disable-next-line react-hooks/set-state-in-effect -- camera mode changes intentionally drive the initial pip/primary swap
                setIsLocalLarge(true);
            } else if (localParticipant?.cameraMode === 'selfie') {
                setIsLocalLarge(false);
            }
            lastCameraModeRef.current = localParticipant?.cameraMode ?? 'selfie';
        }
    }, [localParticipant?.cameraMode]);

    const showReconnecting = useMemo(() => (
        effectiveState.phase !== 'idle' &&
        effectiveState.phase !== 'ending' &&
        effectiveState.connectionStatus === 'retrying'
    ) || (
        effectiveState.phase !== 'idle' &&
        effectiveState.phase !== 'ending' &&
        effectiveState.connectionStatus === 'recovering' &&
        showRecoveringBadge
    ), [effectiveState.connectionStatus, effectiveState.phase, showRecoveringBadge]);

    useEffect(() => {
        if (effectiveState.phase !== 'waiting' && effectiveState.phase !== 'inCall') {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- reconnect badge resets immediately when the call is no longer active
            setShowRecoveringBadge(false);
            return;
        }

        if (effectiveState.connectionStatus !== 'recovering') {
            setShowRecoveringBadge(false);
            return;
        }

        const timer = window.setTimeout(() => {
            setShowRecoveringBadge(true);
        }, 800);
        return () => window.clearTimeout(timer);
    }, [effectiveState.connectionStatus, effectiveState.phase]);

    useEffect(() => {
        if (effectiveState.phase !== 'waiting' && effectiveState.phase !== 'inCall') {
            if (waitingTimerRef.current !== null) {
                window.clearTimeout(waitingTimerRef.current);
                waitingTimerRef.current = null;
            }
            // eslint-disable-next-line react-hooks/set-state-in-effect -- waiting overlay state resets immediately outside active call phases
            setShowWaiting(true);
            return;
        }

        if (remoteStreamEntries.length > 0) {
            if (waitingTimerRef.current !== null) {
                window.clearTimeout(waitingTimerRef.current);
                waitingTimerRef.current = null;
            }
            setShowWaiting(false);
            return;
        }

        if (showReconnecting) {
            setShowWaiting(false);
            waitingTimerRef.current = window.setTimeout(() => {
                setShowWaiting(true);
            }, 8000);
            return () => {
                if (waitingTimerRef.current !== null) {
                    window.clearTimeout(waitingTimerRef.current);
                    waitingTimerRef.current = null;
                }
            };
        }

        setShowWaiting(true);
        return undefined;
    }, [effectiveState.phase, remoteStreamEntries.length, showReconnecting]);

    useEffect(() => {
        if (participantCount > prevParticipantCountRef.current && prevParticipantCountRef.current > 0 && participantCount > 1) {
            playJoinChime();
        }
        prevParticipantCountRef.current = participantCount;
    }, [participantCount]);

    useEffect(() => {
        if (!session) return;
        return session.subscribeToMessages((message) => {
            if (message.type !== 'content_state') return;
            const from = typeof message.payload?.from === 'string' ? message.payload.from : null;
            if (!from) return;
            const active = message.payload?.active === true;
            const contentType = message.payload?.contentType;

            if (active && (contentType === 'screenShare' || contentType === 'worldCamera' || contentType === 'compositeCamera')) {
                setRemoteContentState({ cid: from, contentType });
                return;
            }

            setRemoteContentState((prev) => (prev && prev.cid === from ? null : prev));
        });
    }, [session]);

    useEffect(() => {
        const activeRemoteCids = new Set(remoteStreamEntries.map(([cid]) => cid));

        // eslint-disable-next-line react-hooks/set-state-in-effect -- stale aspect-ratio cache must be pruned when remote tiles disappear
        setRemoteStageAspectRatios((prev) => {
            const nextEntries = Object.entries(prev).filter(([cid]) => activeRemoteCids.has(cid));
            return nextEntries.length === Object.keys(prev).length ? prev : Object.fromEntries(nextEntries);
        });

        if (pinnedParticipantId && pinnedParticipantId !== localParticipant?.cid && !activeRemoteCids.has(pinnedParticipantId)) {
            setPinnedParticipantId(null);
        }

        if (remoteContentState && !activeRemoteCids.has(remoteContentState.cid)) {
            setRemoteContentState(null);
        }
    }, [localParticipant?.cid, pinnedParticipantId, remoteContentState, remoteStreamEntries]);

    useEffect(() => {
        if (!isMultiParty || !stageViewportRef.current) return;

        const node = stageViewportRef.current;
        const updateViewportSize = () => {
            const rect = node.getBoundingClientRect();
            setStageViewportSize({
                width: Math.max(0, Math.floor(rect.width)),
                height: Math.max(0, Math.floor(rect.height)),
            });
        };

        updateViewportSize();

        if (typeof ResizeObserver !== 'undefined') {
            const observer = new ResizeObserver(updateViewportSize);
            observer.observe(node);
            return () => observer.disconnect();
        }

        window.addEventListener('resize', updateViewportSize);
        return () => window.removeEventListener('resize', updateViewportSize);
    }, [isMultiParty]);

    const clearIdleHide = useCallback(() => {
        if (idleTimeoutRef.current !== null) {
            window.clearTimeout(idleTimeoutRef.current);
            idleTimeoutRef.current = null;
        }
    }, []);

    const scheduleIdleHide = useCallback(() => {
        if (!isControlsAutoHideEnabledRef.current) return;
        clearIdleHide();
        idleTimeoutRef.current = window.setTimeout(() => {
            wereControlsLastHiddenByAutoHideRef.current = true;
            setAreControlsVisible(false);
        }, 10000);
    }, [clearIdleHide]);

    useEffect(() => {
        const callActive = effectiveState.phase === 'waiting' || effectiveState.phase === 'inCall';
        if (!callActive) {
            clearIdleHide();
            // eslint-disable-next-line react-hooks/set-state-in-effect -- controls must become visible again as soon as the active call UI is gone
            setAreControlsVisible(true);
            return;
        }

        isControlsAutoHideEnabledRef.current = true;
        wereControlsLastHiddenByAutoHideRef.current = false;
        setAreControlsVisible(true);
        scheduleIdleHide();

        return () => {
            clearIdleHide();
        };
    }, [clearIdleHide, effectiveState.phase, scheduleIdleHide]);

    const handleControlsInteraction = useCallback(() => {
        setAreControlsVisible(true);
        if (wereControlsLastHiddenByAutoHideRef.current) {
            isControlsAutoHideEnabledRef.current = false;
            wereControlsLastHiddenByAutoHideRef.current = false;
            clearIdleHide();
            return;
        }
        scheduleIdleHide();
    }, [clearIdleHide, scheduleIdleHide]);

    const handleScreenTap = useCallback(() => {
        setAreControlsVisible((prev) => {
            const next = !prev;
            if (next) {
                if (wereControlsLastHiddenByAutoHideRef.current) {
                    isControlsAutoHideEnabledRef.current = false;
                    wereControlsLastHiddenByAutoHideRef.current = false;
                    clearIdleHide();
                } else {
                    scheduleIdleHide();
                }
            } else {
                wereControlsLastHiddenByAutoHideRef.current = false;
                clearIdleHide();
            }
            return next;
        });
    }, [clearIdleHide, scheduleIdleHide]);

    const handleGrantPermissions = useCallback(() => {
        if (!session) return;
        void (async () => {
            const permissions = effectiveState.requiredPermissions ?? ['camera', 'microphone'];
            const granted = await SerenadaPermissions.request(permissions);
            if (granted) {
                setPermissionDenied(false);
                await session.resumeJoin();
            } else {
                setPermissionDenied(true);
            }
        })();
    }, [effectiveState.requiredPermissions, session]);

    const handleCancel = useCallback(() => {
        session?.cancelJoin();
        onDismiss?.();
    }, [onDismiss, session]);

    const handleLeave = useCallback(() => {
        if (session) {
            session.leave();
        }
        onDismiss?.();
    }, [onDismiss, session]);

    const handleToggleAudio = useCallback(() => {
        session?.toggleAudio();
        handleControlsInteraction();
    }, [handleControlsInteraction, session]);

    const handleToggleVideo = useCallback(() => {
        session?.toggleVideo();
        handleControlsInteraction();
    }, [handleControlsInteraction, session]);

    const handleFlipCamera = useCallback(() => {
        handleControlsInteraction();
        void session?.flipCamera();
    }, [handleControlsInteraction, session]);

    const handleToggleScreenShare = useCallback(() => {
        if (!session) return;
        handleControlsInteraction();
        if (isScreenSharing) {
            void session.stopScreenShare();
        } else {
            void session.startScreenShare();
        }
    }, [handleControlsInteraction, isScreenSharing, session]);

    const handleCopy = useCallback((event?: React.MouseEvent | React.PointerEvent) => {
        event?.stopPropagation();
        handleControlsInteraction();
        if (!shareUrl) return;
        void navigator.clipboard.writeText(shareUrl).then(() => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 2000);
        });
    }, [handleControlsInteraction, shareUrl]);

    const toggleRemoteFit = useCallback((event: React.MouseEvent | React.PointerEvent) => {
        event.stopPropagation();
        handleControlsInteraction();
        setRemoteVideoFit((prev) => {
            const next = prev === 'cover' ? 'contain' : 'cover';
            persistRemoteVideoFit(next);
            return next;
        });
    }, [handleControlsInteraction]);

    const remoteStageTiles = useMemo<RemoteStageTile[]>(() => (
        remoteStreamEntries.map(([cid, stream]) => ({
            cid,
            stream,
            aspectRatio: remoteStageAspectRatios[cid] ?? clampStageTileAspectRatio(getStreamAspectRatio(stream)),
        }))
    ), [remoteStageAspectRatios, remoteStreamEntries]);

    const remoteStageTileMap = useMemo(() => (
        new Map(remoteStageTiles.map((tile) => [tile.cid, tile]))
    ), [remoteStageTiles]);

    const remoteStageLayout = useMemo(() => (
        computeStageLayout(remoteStageTiles, stageViewportSize.width, stageViewportSize.height, STAGE_TILE_GAP_PX)
    ), [remoteStageTiles, stageViewportSize.height, stageViewportSize.width]);

    const contentSource = useMemo((): ContentSource | null => {
        if (!isMultiParty) return null;
        if (isScreenSharing && localParticipant?.cid) {
            return { type: 'screenShare', ownerParticipantId: localParticipant.cid, aspectRatio: null };
        }
        if (remoteContentState) {
            return { type: remoteContentState.contentType, ownerParticipantId: remoteContentState.cid, aspectRatio: null };
        }
        return null;
    }, [isMultiParty, isScreenSharing, localParticipant, remoteContentState]);

    const computedLayout = useMemo((): LayoutResult | null => {
        if ((!pinnedParticipantId && !contentSource) || !isMultiParty || !localParticipant?.cid) return null;

        const participants: CallScene['participants'] = [
            ...remoteStreamEntries.map(([cid]) => ({
                id: cid,
                role: 'remote' as const,
                videoEnabled: true,
                videoAspectRatio: remoteStageAspectRatios[cid] ?? null,
            })),
            {
                id: localParticipant.cid,
                role: 'local' as const,
                videoEnabled: !isCameraOff,
                videoAspectRatio: null,
            },
        ];

        const scene: CallScene = {
            viewportWidth: stageViewportSize.width,
            viewportHeight: stageViewportSize.height,
            safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
            participants,
            localParticipantId: localParticipant.cid,
            activeSpeakerId: null,
            pinnedParticipantId: contentSource ? null : pinnedParticipantId,
            contentSource,
            userPrefs: { swappedLocalAndRemote: false, dominantFit: remoteVideoFit },
        };

        return computeLayout(scene);
    }, [
        contentSource,
        isCameraOff,
        isMultiParty,
        localParticipant,
        pinnedParticipantId,
        remoteStageAspectRatios,
        remoteStreamEntries,
        remoteVideoFit,
        stageViewportSize.height,
        stageViewportSize.width,
    ]);

    const rootClassName = [
        'serenada-callflow',
        effectiveState.phase === 'inCall' && isMultiParty ? 'multi-party-call' : '',
        !areControlsVisible ? 'controls-hidden' : '',
    ].filter(Boolean).join(' ');

    const rootStyle = useMemo<React.CSSProperties>(() => ({
        background: theme?.backgroundColor ?? '#000',
        '--serenada-accent': theme?.accentColor ?? '#3b82f6',
    } as React.CSSProperties), [theme?.accentColor, theme?.backgroundColor]);

    if (effectiveState.phase === 'idle' || effectiveState.phase === 'joining') {
        return (
            <div className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <div style={spinnerStyle} />
                    <p style={messageTextStyle}>{resolveString('joiningCall', strings)}</p>
                </div>
            </div>
        );
    }

    if (effectiveState.phase === 'awaitingPermissions') {
        return (
            <div className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <h2 style={headingStyle}>{resolveString('permissionRequired', strings)}</h2>
                    <p style={messageTextStyle}>{resolveString('permissionPrompt', strings)}</p>
                    {permissionDenied && (
                        <p style={{ ...messageTextStyle, color: '#ef4444' }}>
                            Permission denied. Please allow access in your browser settings.
                        </p>
                    )}
                    <div style={buttonRowStyle}>
                        <button type="button" onClick={handleGrantPermissions} style={primaryButtonStyle}>
                            {resolveString('grantPermissions', strings)}
                        </button>
                        <button type="button" onClick={handleCancel} style={secondaryButtonStyle}>
                            {resolveString('cancel', strings)}
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    if (effectiveState.phase === 'error') {
        return (
            <div className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <h2 style={{ ...headingStyle, color: '#ef4444' }}>{resolveString('errorOccurred', strings)}</h2>
                    {effectiveState.error && (
                        <p style={messageTextStyle}>{effectiveState.error.message}</p>
                    )}
                    <button type="button" onClick={handleLeave} style={primaryButtonStyle}>
                        {resolveString('endCall', strings)}
                    </button>
                </div>
            </div>
        );
    }

    if (effectiveState.phase === 'ending') {
        return (
            <div className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <p style={messageTextStyle}>{resolveString('callEnded', strings)}</p>
                </div>
            </div>
        );
    }

    const overlayContent = (
        <>
            <StatusOverlay connectionStatus={effectiveState.connectionStatus} strings={strings} />
            {config?.debugOverlayEnabled && (
                <div
                    className="debug-toggle-zone"
                    onPointerDown={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        const now = Date.now();
                        if (debugTapTimeoutRef.current) {
                            window.clearTimeout(debugTapTimeoutRef.current);
                            debugTapTimeoutRef.current = null;
                        }
                        if (now - debugTapRef.current < 450) {
                            debugTapRef.current = 0;
                            setShowDebug((prev) => !prev);
                            return;
                        }
                        debugTapRef.current = now;
                        debugTapTimeoutRef.current = window.setTimeout(() => {
                            debugTapRef.current = 0;
                            debugTapTimeoutRef.current = null;
                        }, 500);
                    }}
                    onPointerUp={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                    }}
                    onPointerCancel={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                    }}
                />
            )}
            {config?.debugOverlayEnabled && showDebug && (
                <DebugPanel
                    stats={debugStats}
                    connectionInfo={session ? {
                        isSignalingConnected: session.isSignalingConnected,
                        activeTransport: effectiveState.activeTransport,
                        iceConnectionState: session.iceConnectionState,
                        peerConnectionState: session.peerConnectionState,
                        rtcSignalingState: session.rtcSignalingState,
                        roomParticipantCount: participantCount,
                        showReconnecting,
                    } : undefined}
                    strings={strings}
                />
            )}
        </>
    );

    const controlsBar = (
        <div
            className="controls-bar"
            onPointerUp={(event) => {
                event.stopPropagation();
                handleControlsInteraction();
            }}
        >
            <button type="button" onClick={handleToggleAudio} className={`btn-control ${isMuted ? 'active' : ''}`}>
                {isMuted ? <MicOff size={22} /> : <Mic size={22} />}
            </button>
            <button type="button" onClick={handleToggleVideo} className={`btn-control ${isCameraOff ? 'active' : ''}`}>
                {isCameraOff ? <VideoOff size={22} /> : <Video size={22} />}
            </button>
            {hasMultipleCameras && (
                <button type="button" onClick={handleFlipCamera} className="btn-control" disabled={isScreenSharing}>
                    <RotateCcw size={22} />
                </button>
            )}
            {showScreenShareControl && (
                <button
                    type="button"
                    onClick={handleToggleScreenShare}
                    className={`btn-control ${isScreenSharing ? 'active-screen-share' : ''}`}
                    title={isScreenSharing ? resolveString('stopScreenShare', strings) : resolveString('startScreenShare', strings)}
                    aria-label={isScreenSharing ? resolveString('stopScreenShare', strings) : resolveString('startScreenShare', strings)}
                >
                    {isScreenSharing ? <ScreenShareOff size={22} /> : <ScreenShare size={22} />}
                </button>
            )}
            <button type="button" onClick={handleLeave} className="btn-control btn-leave">
                <PhoneOff size={22} />
            </button>
        </div>
    );

    const waitingOverlay = showWaiting && (
        <div className="waiting-message">
            <div>{resolveString('waitingForOther', strings)}</div>
            {inviteControlsEnabled && shareUrl && (
                <div className="waiting-actions">
                    <div className="qr-code-container" aria-hidden={!shareUrl}>
                        <QRCode value={shareUrl} size={184} />
                    </div>
                    <button type="button" className="btn-small" onClick={handleCopy} onPointerUp={(event) => event.stopPropagation()}>
                        <Copy size={16} />
                        {copied ? resolveString('copied', strings) : resolveString('shareLink', strings)}
                    </button>
                </div>
            )}
            {waitingActions}
        </div>
    );

    const callProbe = (
        <div
            data-testid="call-participant-count"
            data-count={participantCount}
            data-phase={effectiveState.connectionStatus}
            aria-hidden="true"
            style={callProbeStyle}
        >
            {participantCount}
        </div>
    );

    if (effectiveState.phase === 'inCall' && isMultiParty) {
        return (
            <div className={rootClassName} style={rootStyle} onPointerUp={handleScreenTap}>
                {callProbe}
                {overlayContent}
                <div className="call-container">
                    <div className="video-stage">
                        <div className="video-stage-viewport" ref={stageViewportRef}>
                            {computedLayout ? (
                                <div style={{ position: 'relative', width: '100%', height: '100%' }}>
                                    {computedLayout.tiles.map((tile) => {
                                        const contentOwnerCid = contentSource?.ownerParticipantId;
                                        const isContentTile = tile.type === 'contentSource';
                                        const isLocalTile = tile.id === localParticipant?.cid;
                                        const isLocalContent = isContentTile && contentOwnerCid === localParticipant?.cid;
                                        const isRemoteContent = isContentTile && contentOwnerCid !== localParticipant?.cid;
                                        const isLocalPlaceholder = isLocalTile && contentOwnerCid === localParticipant?.cid && !isContentTile;
                                        const stream = isLocalContent || isLocalTile
                                            ? localStream
                                            : isRemoteContent
                                                ? remoteStageTileMap.get(contentOwnerCid!)?.stream ?? null
                                                : remoteStageTileMap.get(tile.id)?.stream ?? null;

                                        const tileStyle: React.CSSProperties = {
                                            position: 'absolute',
                                            left: `${tile.frame.x}px`,
                                            top: `${tile.frame.y}px`,
                                            width: `${tile.frame.width}px`,
                                            height: `${tile.frame.height}px`,
                                            borderRadius: `${tile.cornerRadius}px`,
                                            zIndex: tile.zOrder,
                                        };

                                        if (isLocalPlaceholder) {
                                            return (
                                                <div key={tile.id} className="video-stage-tile" style={tileStyle}>
                                                    <div className="video-stage-placeholder">
                                                        <VideoOff size={24} />
                                                    </div>
                                                </div>
                                            );
                                        }

                                        if (!stream) return null;

                                        const isPrimaryTile = tile.zOrder === 0;
                                        return (
                                            <div key={tile.id} className="video-stage-tile" style={tileStyle}>
                                                <VideoTile
                                                    stream={stream}
                                                    tileStyle={{ width: '100%', height: '100%', borderRadius: 'inherit' }}
                                                    videoFit={tile.fit === 'contain' ? 'contain' : 'cover'}
                                                    onAspectRatioChange={
                                                        isLocalTile || isContentTile ? undefined : (ratio) => {
                                                            setRemoteStageAspectRatios((prev) => (
                                                                prev[tile.id] === ratio ? prev : { ...prev, [tile.id]: ratio }
                                                            ));
                                                        }
                                                    }
                                                    onClick={() => {
                                                        if (!isContentTile) {
                                                            setPinnedParticipantId((prev) => (prev === tile.id ? null : tile.id));
                                                        }
                                                    }}
                                                    pinned={tile.id === pinnedParticipantId}
                                                />
                                                {isPrimaryTile && (
                                                    <button
                                                        type="button"
                                                        className="btn-zoom"
                                                        onPointerUp={toggleRemoteFit}
                                                        title={remoteVideoFit === 'cover' ? 'Fit video' : 'Fill video'}
                                                    >
                                                        {remoteVideoFit === 'cover' ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
                                                    </button>
                                                )}
                                            </div>
                                        );
                                    })}
                                </div>
                            ) : (
                                <div className="video-stage-rows">
                                    {remoteStageLayout.map((row, rowIndex) => (
                                        <div className="video-stage-row" key={`row-${rowIndex}`}>
                                            {row.items.map((tile) => {
                                                const stageTile = remoteStageTileMap.get(tile.cid);
                                                if (!stageTile) return null;
                                                return (
                                                    <VideoTile
                                                        key={tile.cid}
                                                        stream={stageTile.stream}
                                                        tileStyle={{ width: `${tile.width}px`, height: `${tile.height}px` }}
                                                        onAspectRatioChange={(ratio) => {
                                                            setRemoteStageAspectRatios((prev) => (
                                                                prev[tile.cid] === ratio ? prev : { ...prev, [tile.cid]: ratio }
                                                            ));
                                                        }}
                                                        onClick={() => setPinnedParticipantId(tile.cid)}
                                                    />
                                                );
                                            })}
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                    {!computedLayout && (
                        <div
                            className="video-local-container pip video-local-container-stage"
                            onPointerUp={(event) => {
                                event.stopPropagation();
                                handleControlsInteraction();
                            }}
                        >
                            {localStream && (
                                <video
                                    autoPlay
                                    playsInline
                                    muted
                                    ref={(node) => {
                                        if (node && node.srcObject !== localStream) {
                                            node.srcObject = localStream;
                                        }
                                    }}
                                    className={`video-local ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                                    style={{ objectFit: isScreenSharing ? 'contain' : 'cover' }}
                                />
                            )}
                        </div>
                    )}
                </div>
                {controlsBar}
            </div>
        );
    }

    return (
        <div className={rootClassName} style={rootStyle} onPointerUp={handleScreenTap}>
            {callProbe}
            {overlayContent}
            <div className={`call-container ${isLocalLarge ? 'local-large' : ''}`}>
                <div
                    className={`video-remote-container ${isLocalLarge ? 'pip' : 'primary'}`}
                    onPointerUp={isLocalLarge ? (event) => {
                        event.stopPropagation();
                        handleControlsInteraction();
                        setIsLocalLarge(false);
                    } : undefined}
                >
                    {remoteStream && (
                        <video
                            autoPlay
                            playsInline
                            ref={(node) => {
                                if (node && node.srcObject !== remoteStream) {
                                    node.srcObject = remoteStream;
                                }
                            }}
                            className="video-remote"
                            style={{ objectFit: remoteVideoFit }}
                        />
                    )}

                    {remoteStream && (
                        <button
                            type="button"
                            className="btn-zoom"
                            onPointerUp={toggleRemoteFit}
                            title={remoteVideoFit === 'cover' ? 'Fit video' : 'Fill video'}
                        >
                            {remoteVideoFit === 'cover' ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
                        </button>
                    )}

                    {waitingOverlay}
                </div>

                <div
                    className={`video-local-container ${isLocalLarge ? 'primary' : 'pip'}`}
                    onPointerUp={!isLocalLarge ? (event) => {
                        event.stopPropagation();
                        handleControlsInteraction();
                        setIsLocalLarge(true);
                    } : undefined}
                >
                    {localStream && (
                        <video
                            autoPlay
                            playsInline
                            muted
                            ref={(node) => {
                                if (node && node.srcObject !== localStream) {
                                    node.srcObject = localStream;
                                }
                            }}
                            className={`video-local ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                            style={{ objectFit: isScreenSharing ? 'contain' : 'cover' }}
                        />
                    )}
                </div>
            </div>
            {controlsBar}
        </div>
    );
};

const centerContentStyle: React.CSSProperties = {
    display: 'flex',
    flex: 1,
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    textAlign: 'center',
};

const headingStyle: React.CSSProperties = {
    margin: '0 0 8px',
    color: '#e2e8f0',
    fontSize: 20,
    fontWeight: 600,
};

const messageTextStyle: React.CSSProperties = {
    margin: '4px 0',
    color: '#94a3b8',
    fontSize: 15,
    textAlign: 'center',
};

const buttonRowStyle: React.CSSProperties = {
    display: 'flex',
    gap: 12,
    marginTop: 16,
};

const primaryButtonStyle: React.CSSProperties = {
    padding: '10px 24px',
    border: 'none',
    borderRadius: 8,
    background: 'var(--serenada-accent)',
    color: '#fff',
    fontSize: 14,
    fontWeight: 600,
    cursor: 'pointer',
};

const secondaryButtonStyle: React.CSSProperties = {
    padding: '10px 24px',
    border: '1px solid rgba(255,255,255,0.2)',
    borderRadius: 8,
    background: 'transparent',
    color: '#e2e8f0',
    fontSize: 14,
    fontWeight: 500,
    cursor: 'pointer',
};

const spinnerStyle: React.CSSProperties = {
    width: 36,
    height: 36,
    marginBottom: 16,
    border: '3px solid rgba(255,255,255,0.15)',
    borderTopColor: 'var(--serenada-accent)',
    borderRadius: '50%',
    animation: 'serenada-spin 0.8s linear infinite',
};

const callProbeStyle: React.CSSProperties = {
    position: 'absolute',
    width: 1,
    height: 1,
    overflow: 'hidden',
    opacity: 0,
    pointerEvents: 'none',
};
