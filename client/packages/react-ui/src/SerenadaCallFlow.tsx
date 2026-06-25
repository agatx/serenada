import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'react-qr-code';
import {
    Camera,
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
    SnapshotError,
    clampStageTileAspectRatio,
    computeLayout,
    computeStageLayout,
    STAGE_TILE_GAP_PX,
    type CallScene,
    type CallStats,
    type ContentSource,
    type LayoutResult,
    type MediaCapability,
    type SerenadaSessionHandle,
    type SnapshotSource,
} from '@agatx/serenada-core';
import { AudioActivityIndicator } from './components/AudioActivityIndicator.js';
import { DebugPanel } from './components/DebugPanel.js';
import { RemoteAvatar } from './components/RemoteAvatar.js';
import { useAvatarResolver, type AvatarResolver } from './hooks/useAvatarResolver.js';
import { StatusOverlay } from './components/StatusOverlay.js';
import { useAudioLevel } from './hooks/useAudioLevel.js';
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
import {
    resolveContentScene,
    resolveContentSource,
    shouldRenderContentStage,
    deriveStageTiles,
    pickStageSpotlightTileId,
    parseStageTileId,
    stageTileId,
    stageTileKeyEquals,
    type ContentScene,
    type StageTile,
    type StageTileKey,
} from './utils/contentRendering.js';

interface RemoteStageTile {
    cid: string;
    stream: MediaStream;
    aspectRatio: number;
}

const MOBILE_BROWSER_RE = /Mobi|Android|iPhone|iPad|iPod/i;
const PLAYBACK_RETRY_EVENTS = ['pointerdown', 'touchend', 'keydown'] as const;

function isAutoplayBlocked(error: unknown): boolean {
    return typeof error === 'object'
        && error !== null
        && 'name' in error
        && (error as { name?: unknown }).name === 'NotAllowedError';
}

function useMediaElementPlayback<T extends HTMLMediaElement>(
    mediaRef: React.RefObject<T | null>,
    stream: MediaStream | null,
    label: string,
): void {
    useEffect(() => {
        const media = mediaRef.current;
        if (!media || !stream) return undefined;

        if (media.srcObject !== stream) {
            media.srcObject = stream;
        }

        let disposed = false;
        let removeRetryListeners: (() => void) | null = null;

        const clearRetryListeners = () => {
            removeRetryListeners?.();
            removeRetryListeners = null;
        };

        const play = () => {
            if (disposed || !media.isConnected) return;
            clearRetryListeners();

            void media.play().catch((err) => {
                if (disposed) return;
                if (isAutoplayBlocked(err) && typeof window !== 'undefined') {
                    const retry = () => play();
                    PLAYBACK_RETRY_EVENTS.forEach((eventName) => {
                        window.addEventListener(eventName, retry, { capture: true, once: true });
                    });
                    removeRetryListeners = () => {
                        PLAYBACK_RETRY_EVENTS.forEach((eventName) => {
                            window.removeEventListener(eventName, retry, { capture: true });
                        });
                    };
                    return;
                }

                console.warn(`[SerenadaCallFlow] Failed to play ${label}`, err);
            });
        };

        play();

        return () => {
            disposed = true;
            clearRetryListeners();
            if (media.srcObject === stream) {
                media.srcObject = null;
            }
        };
    }, [label, mediaRef, stream]);
}

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

const ParticipantBadge: React.FC<{
    muted?: boolean;
    displayName?: string;
    stream?: MediaStream | null;
}> = ({ muted, displayName, stream }) => {
    const level = useAudioLevel(stream ?? null, !muted);
    if (!muted && !displayName && !stream) return null;
    return (
        <div className="participant-badge">
            {muted ? <MicOff size={14} /> : stream ? <AudioActivityIndicator level={level} /> : null}
            {displayName && <span className="participant-badge-name">{displayName}</span>}
        </div>
    );
};

const RemoteAudioSink: React.FC<{
    cid: string;
    stream: MediaStream;
}> = ({ cid, stream }) => {
    const audioRef = useRef<HTMLAudioElement>(null);
    useMediaElementPlayback(audioRef, stream, `remote audio (${cid})`);

    return <audio ref={audioRef} autoPlay data-serenada-remote-audio={cid} />;
};

const StreamVideo: React.FC<{
    stream: MediaStream;
    muted?: boolean;
    className?: string;
    style?: React.CSSProperties;
}> = ({ stream, muted = true, className, style }) => {
    const videoRef = useRef<HTMLVideoElement>(null);
    useMediaElementPlayback(videoRef, stream, 'remote video');

    return (
        <video
            ref={videoRef}
            autoPlay
            playsInline
            muted={muted}
            className={className}
            style={style}
        />
    );
};

const VideoTile: React.FC<{
    stream: MediaStream;
    label?: string;
    muted?: boolean;
    mirrored?: boolean;
    pinned?: boolean;
    tileStyle?: React.CSSProperties;
    videoFit?: RemoteVideoFit;
    videoEnabled?: boolean;
    cameraOffLabel?: string;
    compact?: boolean;
    peerId?: string;
    displayName?: string;
    resolveAvatar?: AvatarResolver;
    onAspectRatioChange?: (ratio: number) => void;
    onClick?: () => void;
}> = ({
    stream,
    label,
    muted = true,
    mirrored = false,
    pinned = false,
    tileStyle,
    videoFit = 'cover',
    videoEnabled,
    cameraOffLabel,
    compact = false,
    peerId,
    displayName,
    resolveAvatar,
    onAspectRatioChange,
    onClick,
}) => {
    const videoRef = useRef<HTMLVideoElement>(null);
    useMediaElementPlayback(videoRef, stream, 'remote video');

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
            {videoEnabled === false && (
                <div className={`video-camera-off-overlay${compact ? ' compact' : ''}`}>
                    {resolveAvatar && (
                        <RemoteAvatar
                            peerId={peerId}
                            displayName={displayName}
                            resolveAvatar={resolveAvatar}
                            compact={compact}
                        />
                    )}
                    <span className="video-camera-off-label">{cameraOffLabel}</span>
                </div>
            )}
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
    className: hostClassName,
    url,
    session: externalSession,
    serverHost,
    config,
    theme,
    strings,
    waitingActions,
    onDismiss,
    onEndCall,
    onStatsUpdate,
    onSnapshotCaptured,
    onSnapshotError,
}) => {
    useEffect(() => { ensureCallFlowStyles(); }, []);

    const internalSessionRef = useRef<SerenadaSessionHandle | null>(null);
    const [internalSession, setInternalSession] = useState<SerenadaSessionHandle | null>(null);
    const usesInternalSession = !externalSession;
    const videoEnabledConfig = config?.videoEnabled !== false;
    const videoMediaEnabledConfig = config?.videoMediaEnabled !== false;

    useEffect(() => {
        if (externalSession || !url) return;

        let host: string;
        try {
            host = serverHost ?? new URL(url).host;
        } catch {
            return;
        }

        const core = new SerenadaCore({
            serverHost: host,
            videoMediaEnabled: videoMediaEnabledConfig,
            cameraModes: videoEnabledConfig ? undefined : [],
        });
        const sess = core.join(url);
        internalSessionRef.current = sess;
        // eslint-disable-next-line react-hooks/set-state-in-effect -- internal SDK session is initialized from the URL-first effect
        setInternalSession(sess);

        return () => {
            sess.destroy();
            internalSessionRef.current = null;
            setInternalSession(null);
        };
        // videoEnabledConfig/videoMediaEnabledConfig are intentionally omitted:
        // they are read once at session creation. Toggling either mid-call would
        // destroy and rejoin the session, dropping the call. Host apps that need
        // to change them at runtime should remount this component.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [externalSession, serverHost, url]);

    const session: SerenadaSessionHandle | null = externalSession ?? internalSession;
    const state = useCallState(session ?? null);
    const rawEffectiveState = session ? state : IDLE_STATE;
    // Hide presumed-lost remotes from the call grid — the SDK keeps their
    // peer connections open in case they reattach, but for the active grid
    // they should be invisible. Host apps wanting different presentation
    // (e.g., dimmed tile + "connection lost" badge) can read presumedLost
    // off the SDK's state directly.
    const effectiveState = useMemo(() => {
        const visibleRemotes = rawEffectiveState.remoteParticipants.filter((p) => !p.presumedLost);
        if (visibleRemotes.length === rawEffectiveState.remoteParticipants.length) {
            return rawEffectiveState;
        }
        return { ...rawEffectiveState, remoteParticipants: visibleRemotes };
    }, [rawEffectiveState]);
    const localParticipant = effectiveState.localParticipant;
    const localStream = session?.localStream ?? null;
    const remoteStreams = session?.remoteStreams ?? EMPTY_STREAMS;
    const remoteStreamEntries = useMemo(() => Array.from(remoteStreams.entries()), [remoteStreams]);
    const remoteStream = remoteStreamEntries.length === 1 ? remoteStreamEntries[0][1] : null;
    const participantCount = (localParticipant ? 1 : 0) + effectiveState.remoteParticipants.length;
    const isMultiParty = remoteStreamEntries.length > 1;
    // Legacy (flag-off) screen share signal: the single video sender carries the
    // screen and the SDK reports cameraMode='screenShare'. In independent mode
    // cameraMode is never 'screenShare', so this stays false there and the local
    // PIP keeps presenting the camera (cover + mirror) — byte-identical flag-off.
    const isScreenSharing = localParticipant?.cameraMode === 'screenShare';
    // Independent-aware "am I presenting content": legacy cameraMode OR the
    // precise content.active flag. Drives the share control + start/stop only.
    const isSharingContent = isScreenSharing || localParticipant?.content?.active === true;
    const isCameraOff = localParticipant?.videoEnabled === false;
    const isMuted = localParticipant?.audioEnabled === false;
    const canScreenShare = session?.canScreenShare === true && !isMobileBrowser();
    const availableCameraModes = localParticipant?.availableCameraModes ?? [];
    const videoCaptureSupported = videoEnabledConfig && availableCameraModes.length > 0;
    const canFlipCamera = videoEnabledConfig
        && session?.hasMultipleCameras === true
        && availableCameraModes.length > 1
        && localParticipant?.videoEnabled === true;
    const showScreenShareControl = config?.screenSharingEnabled !== false && canScreenShare;
    const autoHideControls = config?.autoHideControls !== false;
    const inviteControlsEnabled = config?.inviteControlsEnabled !== false;
    const resolveAvatar = useAvatarResolver(config?.avatarProvider);
    const shareUrl = effectiveState.roomUrl ?? (typeof window !== 'undefined' ? window.location.href : '');
    const shouldMirrorLocalVideo = localParticipant?.cameraMode === 'selfie' && !isScreenSharing;

    const [permissionDenied, setPermissionDenied] = useState(false);
    const [copied, setCopied] = useState(false);
    const [isLocalLarge, setIsLocalLarge] = useState(false);
    // When the local camera is off there's nothing meaningful to enlarge — force
    // remote-as-primary so the user doesn't see a giant "Camera off" placeholder.
    // The user's swap preference (`isLocalLarge`) is preserved and reapplied
    // automatically when video comes back on.
    const effectiveLocalLarge = isLocalLarge && !isCameraOff;
    const [areControlsVisible, setAreControlsVisible] = useState(true);
    const [showConnectionStatusBadge, setShowConnectionStatusBadge] = useState(false);
    const [showWaiting, setShowWaiting] = useState(true);
    const [showDebug, setShowDebug] = useState(false);
    const [debugStats, setDebugStats] = useState<CallStats | null>(null);
    const [remoteVideoFit, setRemoteVideoFit] = useState<RemoteVideoFit>(() => getPersistedRemoteVideoFit());
    // Screen-share content defaults to FIT (the whole shared screen, legible); the
    // camera fit default (cover/fill) crops a shared screen badly. Kept independent
    // of `remoteVideoFit` so the content spotlight's fit toggle doesn't also move
    // the camera tiles (and vice versa).
    const [contentFitCover, setContentFitCover] = useState(false);
    const [pinnedParticipantId, setPinnedParticipantId] = useState<string | null>(null);
    // Stream-keyed pin for the content stage: pin ANY tile (camera OR content)
    // of ANY participant. Distinct from `pinnedParticipantId` (the legacy
    // multi-party no-content focus pin, which is participant-keyed and stays
    // byte-identical). `null` = default spotlight (most-recent share).
    const [pinnedTile, setPinnedTile] = useState<StageTileKey | null>(null);
    const [remoteContentState, setRemoteContentState] = useState<{ cid: string; contentType: ContentSource['type'] } | null>(null);
    // Local receive order of remote content activations, most-recent LAST.
    // Drives the default primary among multiple simultaneous remote sharers
    // (design "Multiple Sharers"); there is no server-stamped ordering.
    const [remoteContentOrder, setRemoteContentOrder] = useState<string[]>([]);
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
    const [stageViewportNode, setStageViewportNode] = useState<HTMLDivElement | null>(null);
    const stageViewportRef = useCallback((node: HTMLDivElement | null) => {
        setStageViewportNode(node);
    }, []);
    const debugTapRef = useRef(0);
    const debugTapTimeoutRef = useRef<number | null>(null);

    const permissionRequester = config?.requestPermissions;
    const requestPermissions = useCallback((permissions: MediaCapability[]) => {
        return permissionRequester?.(permissions) ?? SerenadaPermissions.request(permissions);
    }, [permissionRequester]);

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
                const granted = await requestPermissions(permissions);
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
    }, [internalSession, requestPermissions, usesInternalSession]);

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
        (effectiveState.phase === 'waiting' || effectiveState.phase === 'inCall') &&
        effectiveState.connectionStatus !== 'connected' &&
        showConnectionStatusBadge
    ), [effectiveState.connectionStatus, effectiveState.phase, showConnectionStatusBadge]);

    useEffect(() => {
        if (effectiveState.phase !== 'waiting' && effectiveState.phase !== 'inCall') {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- reconnect badge resets immediately when the call is no longer active
            setShowConnectionStatusBadge(false);
            return;
        }

        if (effectiveState.connectionStatus === 'connected') {
            setShowConnectionStatusBadge(false);
            return;
        }

        const timer = window.setTimeout(() => {
            setShowConnectionStatusBadge(true);
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
        return session.onPeerMessage((message) => {
            if (message.type !== 'content_state') return;
            const payload = message.payload && typeof message.payload === 'object' && !Array.isArray(message.payload)
                ? message.payload as Record<string, unknown>
                : null;
            if (!payload) return;
            const from = message.from;
            const active = payload.active === true;
            const contentType = payload.contentType;

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

        isControlsAutoHideEnabledRef.current = autoHideControls;
        wereControlsLastHiddenByAutoHideRef.current = false;
        setAreControlsVisible(true);
        scheduleIdleHide();

        return () => {
            clearIdleHide();
        };
    }, [autoHideControls, clearIdleHide, effectiveState.phase, scheduleIdleHide]);

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
        if (!autoHideControls) {
            setAreControlsVisible(true);
            clearIdleHide();
            return;
        }
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
    }, [autoHideControls, clearIdleHide, scheduleIdleHide]);

    const handleGrantPermissions = useCallback(() => {
        if (!session) return;
        void (async () => {
            const permissions = effectiveState.requiredPermissions ?? ['camera', 'microphone'];
            const granted = await requestPermissions(permissions);
            if (granted) {
                setPermissionDenied(false);
                await session.resumeJoin();
            } else {
                setPermissionDenied(true);
            }
        })();
    }, [effectiveState.requiredPermissions, requestPermissions, session]);

    const handleCancel = useCallback(() => {
        session?.cancelJoin();
        onDismiss?.();
    }, [onDismiss, session]);

    const handleLeave = useCallback(() => {
        if (onEndCall) {
            onEndCall();
            return;
        }
        session?.leave();
        onDismiss?.();
    }, [onDismiss, onEndCall, session]);

    const handleToggleAudio = useCallback(() => {
        session?.toggleAudio();
        handleControlsInteraction();
    }, [handleControlsInteraction, session]);

    const handleToggleVideo = useCallback(() => {
        if (!session) return;
        void (async () => {
            if (isCameraOff) {
                const granted = await requestPermissions(['camera']);
                if (!granted) {
                    setPermissionDenied(true);
                    handleControlsInteraction();
                    return;
                }
                setPermissionDenied(false);
            }
            session.toggleVideo();
            handleControlsInteraction();
        })();
    }, [handleControlsInteraction, isCameraOff, requestPermissions, session]);

    const handleFlipCamera = useCallback(() => {
        handleControlsInteraction();
        void session?.flipCamera();
    }, [handleControlsInteraction, session]);

    const handleToggleScreenShare = useCallback(() => {
        if (!session) return;
        handleControlsInteraction();
        if (isSharingContent) {
            void session.stopScreenShare();
        } else {
            void session.startScreenShare();
        }
    }, [handleControlsInteraction, isSharingContent, session]);

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

    const toggleContentFit = useCallback((event: React.MouseEvent | React.PointerEvent) => {
        event.stopPropagation();
        handleControlsInteraction();
        setContentFitCover((prev) => !prev);
    }, [handleControlsInteraction]);

    const snapshotEnabled = config?.snapshotEnabled === true;
    const [isSnapshotInFlight, setIsSnapshotInFlight] = useState(false);

    // Snapshot source mirrors whichever stream is currently shown large.
    // In 1:1 that's localStream when the user has swapped to local-large,
    // otherwise the only remote stream. In multi-party there is no single
    // "large preview" until a tile is pinned — pinned tiles render as the
    // dominant stage tile and the shutter captures from that participant.
    // When the source's video is off we return null so the button is hidden,
    // matching Android/iOS (rather than rendering a disabled shutter).
    const primarySnapshotSource: SnapshotSource | null = useMemo(() => {
        if (!snapshotEnabled) return null;
        // Stream-keyed content stage pins via `pinnedTile` (not pinnedParticipantId).
        // The spotlight there is a specific stream: a pinned CAMERA tile is
        // snapshot-able; a pinned screen-share (content) is not, so hide the
        // shutter. `pinnedTile` is only ever set inside the stream-keyed stage
        // and is cleared when its tile (or the stage) goes away, so this branch
        // never leaks into the legacy/1:1 paths below.
        if (pinnedTile) {
            if (pinnedTile.kind === 'content') return null;
            if (pinnedTile.cid === localParticipant?.cid) {
                return isCameraOff ? null : { kind: 'local' };
            }
            const pinnedStageRemote = effectiveState.remoteParticipants.find(
                (p) => p.cid === pinnedTile.cid,
            );
            if (!pinnedStageRemote || pinnedStageRemote.videoEnabled === false) return null;
            return { kind: 'remote', cid: pinnedStageRemote.cid };
        }
        if (isMultiParty) {
            if (!pinnedParticipantId) return null;
            if (pinnedParticipantId === localParticipant?.cid) {
                return isCameraOff ? null : { kind: 'local' };
            }
            const pinnedRemote = effectiveState.remoteParticipants.find(
                (p) => p.cid === pinnedParticipantId,
            );
            if (!pinnedRemote || pinnedRemote.videoEnabled === false) return null;
            return { kind: 'remote', cid: pinnedRemote.cid };
        }
        if (effectiveLocalLarge) return { kind: 'local' };
        const cid = remoteStreamEntries[0]?.[0];
        if (!cid) return null;
        const remoteParticipant = effectiveState.remoteParticipants.find((p) => p.cid === cid);
        if (remoteParticipant?.videoEnabled === false) return null;
        return { kind: 'remote', cid };
    }, [
        snapshotEnabled,
        pinnedTile,
        isMultiParty,
        pinnedParticipantId,
        localParticipant?.cid,
        isCameraOff,
        effectiveLocalLarge,
        remoteStreamEntries,
        effectiveState.remoteParticipants,
    ]);

    // Match native: anchor button to the device/window short edge so the web
    // and iOS/Android UIs agree regardless of stream aspect ratio. The
    // primary preview fills the call container, so window orientation
    // tracks the rendered short edge.
    const [isWindowLandscape, setIsWindowLandscape] = useState(() =>
        typeof window !== 'undefined' && window.innerWidth > window.innerHeight,
    );
    useEffect(() => {
        if (typeof window === 'undefined') return;
        const handler = () => setIsWindowLandscape(window.innerWidth > window.innerHeight);
        window.addEventListener('resize', handler);
        return () => window.removeEventListener('resize', handler);
    }, []);

    const handleSnapshot = useCallback((event?: React.PointerEvent | React.MouseEvent) => {
        event?.stopPropagation();
        handleControlsInteraction();
        if (!session || !primarySnapshotSource) return;
        setIsSnapshotInFlight(true);
        void (async () => {
            try {
                const result = await session.captureSnapshot(primarySnapshotSource);
                onSnapshotCaptured?.(result);
            } catch (err) {
                const error = err instanceof SnapshotError
                    ? err
                    : new SnapshotError('captureFailed', (err as Error)?.message ?? 'Snapshot failed');
                onSnapshotError?.(error);
            } finally {
                setIsSnapshotInFlight(false);
            }
        })();
    }, [handleControlsInteraction, onSnapshotCaptured, onSnapshotError, primarySnapshotSource, session]);

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

    const remoteParticipantMap = useMemo(() => (
        new Map(effectiveState.remoteParticipants.map((p) => [p.cid, p]))
    ), [effectiveState.remoteParticipants]);

    const remoteStageLayout = useMemo(() => (
        computeStageLayout(remoteStageTiles, stageViewportSize.width, stageViewportSize.height, STAGE_TILE_GAP_PX)
    ), [remoteStageTiles, stageViewportSize.height, stageViewportSize.width]);

    // Resolve content (screen share) separately from camera. Consumes the SDK's
    // independent content streams + per-participant content.active when present,
    // and falls back to the legacy single-video-as-content path (cameraMode /
    // received content_state) when no independent content stream exists — so the
    // flag-off default UX is byte-identical to today.
    const contentScene = useMemo((): ContentScene => (
        resolveContentScene({
            local: localParticipant
                ? { cid: localParticipant.cid, cameraMode: localParticipant.cameraMode, content: localParticipant.content }
                : null,
            localStream,
            remotes: effectiveState.remoteParticipants.map((p) => ({
                cid: p.cid,
                content: p.content,
                supportsIndependentContentVideo: session?.getRemoteIndependentContentVideo(p.cid) === true,
            })),
            remoteStreams,
            independentContentEnabled: session?.independentContentVideoEnabled === true,
            legacyRemoteContent: remoteContentState
                ? { cid: remoteContentState.cid, contentType: remoteContentState.contentType }
                : null,
            accessors: {
                getLocalContentStream: () => session?.getLocalContentStream() ?? null,
                getRemoteContentStream: (cid) => session?.getRemoteContentStream(cid),
            },
            // Audio-only receivers (videoMediaEnabled=false) never negotiated
            // content receive and must suppress all content UI.
            localVideoMediaEnabled: videoMediaEnabledConfig,
            remoteContentOrder,
        })
    ), [
        effectiveState.remoteParticipants,
        localParticipant,
        localStream,
        remoteContentOrder,
        remoteContentState,
        remoteStreams,
        session,
        videoMediaEnabledConfig,
    ]);

    // The content-role input to the layout. Multi-party surfaces content for the
    // primary owner whether independent or legacy (unchanged). 1:1 surfaces
    // content ONLY when it is an INDEPENDENT stream — in legacy 1:1 the single
    // video is physically swapped to the screen and shown in the normal one-tile
    // layout, so this stays null there and that path is byte-identical to today.
    const contentSource = useMemo((): ContentSource | null => (
        resolveContentSource(contentScene.primary, isMultiParty)
    ), [contentScene.primary, isMultiParty]);

    // STREAM-KEYED STAGE: active whenever ANY participant is presenting an
    // INDEPENDENT content stream (local or remote). In that case the whole stage
    // switches to a single filmstrip+spotlight where EVERY stream is its own tile
    // (a camera tile per participant whose camera is on, a content tile per
    // sharer — including the local user's own screen). This engages for 1:1 + a
    // share exactly the same as group, and surfaces a sharer's camera AND screen
    // as two equal peers. Legacy / flag-off content (no independent stream) is
    // NOT stream-keyed and keeps the existing participant-keyed content tile path
    // below (byte-identical). Audio-only receivers resolve no content here.
    const hasIndependentContent = useMemo(
        () => contentScene.all.some((c) => c.mode === 'independent'),
        [contentScene.all],
    );
    const streamKeyedStageActive = hasIndependentContent;

    // Whether the content-stage (filmstrip + spotlight) layout should render.
    // Stream-keyed when independent content is active (1:1 or group). Otherwise
    // the legacy gate: always for multi-party (pin or legacy content, as today),
    // never for legacy 1:1 (single swapped video stays in the normal layout).
    const useContentStageLayout = streamKeyedStageActive || isMultiParty || contentSource !== null;

    useEffect(() => {
        // Measure the stage viewport whenever the content-stage layout renders —
        // multi-party (pin/content) and now also 1:1 independent content, both of
        // which mount `stageViewportRef`. Legacy 1:1 never renders the stage, so
        // this stays inert there (byte-identical).
        if (!useContentStageLayout || !stageViewportNode) return;

        const node = stageViewportNode;
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
    }, [stageViewportNode, useContentStageLayout]);

    const primaryContent = contentScene.primary;

    // Keep the most-recently-active-last order in sync with which remotes are
    // currently presenting content. New active owners append (most recent last);
    // owners that stop are dropped. Order-only changes don't re-trigger this
    // (membership-keyed), so there is no feedback loop with contentScene.
    const activeRemoteContentCids = useMemo(
        () => contentScene.remotes.map((r) => r.ownerId),
        [contentScene.remotes],
    );
    useEffect(() => {
        const activeSet = new Set(activeRemoteContentCids);
        // eslint-disable-next-line react-hooks/set-state-in-effect -- receive-order tracking reconciles to the active content owner set (membership-keyed, converges)
        setRemoteContentOrder((prev) => {
            const kept = prev.filter((cid) => activeSet.has(cid));
            const keptSet = new Set(kept);
            const added = activeRemoteContentCids.filter((cid) => !keptSet.has(cid));
            const next = [...kept, ...added];
            const unchanged = next.length === prev.length && next.every((cid, i) => cid === prev[i]);
            return unchanged ? prev : next;
        });
    }, [activeRemoteContentCids]);

    // The stream-keyed tile list for the content stage (one tile per camera +
    // one per active share, keyed by {cid, kind}). Empty unless an independent
    // content stream is present, so the legacy path below is never affected.
    const stageTiles = useMemo<StageTile[]>(() => {
        if (!streamKeyedStageActive || !localParticipant?.cid) return [];
        const cameras = [
            ...remoteStreamEntries.map(([cid]) => ({
                cid,
                isLocal: false,
            })),
            { cid: localParticipant.cid, isLocal: true },
        ];
        return deriveStageTiles({ cameras, content: contentScene.all });
    }, [
        streamKeyedStageActive,
        localParticipant,
        remoteStreamEntries,
        contentScene.all,
    ]);

    // The spotlight (primary) tile id: a pinned tile if present, else the
    // most-recent active share (reusing `contentScene.primary`).
    const stageSpotlightId = useMemo(
        () => pickStageSpotlightTileId(stageTiles, pinnedTile, contentScene.primary),
        [stageTiles, pinnedTile, contentScene.primary],
    );

    // Drop a stale pin when its tile disappears (e.g. the pinned sharer stopped,
    // or the pinned camera turned off) so the spotlight reverts to the default.
    useEffect(() => {
        if (!pinnedTile) return;
        const pinnedId = stageTileId(pinnedTile);
        if (!stageTiles.some((t) => t.id === pinnedId)) {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- stale stream-key pin must clear when its tile is gone
            setPinnedTile(null);
        }
    }, [pinnedTile, stageTiles]);

    const computedLayout = useMemo((): LayoutResult | null => {
        if (!useContentStageLayout || !localParticipant?.cid) return null;

        // ---- Stream-keyed stage: every stream is its own tile -----------------
        // Build a composite-id `focus` scene so `computeLayout` runs the existing
        // `computePrimaryWithFilmstrip` geometry (single spotlight + filmstrip)
        // over the stream-keyed tiles. Tile ids encode {cid, kind}; the engine
        // treats them as opaque strings. The conformance-locked `content`/`focus`
        // modes are untouched — this reuses the geometry, not those code paths.
        if (streamKeyedStageActive) {
            if (stageTiles.length === 0 || !stageSpotlightId) return null;

            const participants: CallScene['participants'] = stageTiles.map((tile) => ({
                id: tile.id,
                // Spotlight is `local` so `computeLayout` never demotes it to a
                // PIP; filmstrip tiles are `remote`. Role only affects PIP/order
                // here, and focus mode emits no PIP, so this is purely structural.
                role: tile.id === stageSpotlightId ? ('local' as const) : ('remote' as const),
                videoEnabled: true,
                videoAspectRatio: tile.kind === 'camera' ? remoteStageAspectRatios[tile.cid] ?? null : null,
            }));

            const scene: CallScene = {
                viewportWidth: stageViewportSize.width,
                viewportHeight: stageViewportSize.height,
                safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
                participants,
                localParticipantId: stageSpotlightId,
                activeSpeakerId: null,
                // Pin the spotlight tile → focus mode. With a single tile the
                // engine would derive `solo`; force focus by always pinning.
                pinnedParticipantId: stageSpotlightId,
                contentSource: null,
                userPrefs: { swappedLocalAndRemote: false, dominantFit: remoteVideoFit },
            };

            // Single-tile edge (lone sharer, cameras off, alone): focus mode is
            // bypassed by `solo` in `computeLayout`. Emit the full-area spotlight
            // directly — identical to `computePrimaryWithFilmstrip` with no strip.
            if (stageTiles.length === 1) {
                return {
                    mode: 'focus',
                    tiles: [{
                        id: stageSpotlightId,
                        type: 'participant',
                        frame: { x: 0, y: 0, width: stageViewportSize.width, height: stageViewportSize.height },
                        fit: remoteVideoFit,
                        cornerRadius: 0,
                        zOrder: 0,
                    }],
                    localPip: null,
                };
            }

            return computeLayout(scene);
        }

        // ---- Legacy participant-keyed stage (unchanged) -----------------------
        // Stage layout fires when there is a reason for it (a pin or a legacy
        // content source). Multi-party allows either reason (as today); 1:1
        // allows only an independent content source — which routes through the
        // stream-keyed branch above, so 1:1 legacy never reaches here.
        if (!pinnedParticipantId && !contentSource) return null;

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
        localParticipant,
        pinnedParticipantId,
        remoteStageAspectRatios,
        remoteStreamEntries,
        remoteVideoFit,
        stageViewportSize.height,
        stageViewportSize.width,
        streamKeyedStageActive,
        stageTiles,
        stageSpotlightId,
        useContentStageLayout,
    ]);

    const rootClassName = [
        'serenada-callflow',
        hostClassName,
        // The `.multi-party-call` class carries ALL the `.video-stage*` sizing
        // (viewport height:100%, flex, padding, tile chrome). The content stage
        // (filmstrip + spotlight) reuses those styles, so apply the class whenever
        // that stage renders — including a 1:1 with an active share — not only when
        // multi-party. Without this a 1:1 share collapsed the stage viewport to
        // height:0 and the absolute tiles overflowed it. `computedLayout != null`
        // mirrors `shouldRenderContentStage`'s hasContentStageLayout input.
        (effectiveState.phase === 'inCall' || effectiveState.phase === 'waiting')
            && (isMultiParty || computedLayout != null) ? 'multi-party-call' : '',
        !areControlsVisible ? 'controls-hidden' : '',
    ].filter(Boolean).join(' ');

    const rootStyle = useMemo<React.CSSProperties>(() => ({
        background: theme?.backgroundColor ?? '#000',
        '--serenada-accent': theme?.accentColor ?? '#3b82f6',
    } as React.CSSProperties), [theme?.accentColor, theme?.backgroundColor]);

    if (effectiveState.phase === 'idle' || effectiveState.phase === 'joining') {
        return (
            <div data-serenada-callflow="" className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <div style={spinnerStyle} />
                    <p style={messageTextStyle}>{resolveString('joiningCall', strings)}</p>
                </div>
            </div>
        );
    }

    if (effectiveState.phase === 'awaitingPermissions') {
        return (
            <div data-serenada-callflow="" className={rootClassName} style={rootStyle}>
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
            <div data-serenada-callflow="" className={rootClassName} style={rootStyle}>
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
            <div data-serenada-callflow="" className={rootClassName} style={rootStyle}>
                <div style={centerContentStyle}>
                    <p style={messageTextStyle}>{resolveString('callEnded', strings)}</p>
                </div>
            </div>
        );
    }

    const overlayContent = (
        <>
            <StatusOverlay
                connectionStatus={showReconnecting ? effectiveState.connectionStatus : 'connected'}
                strings={strings}
            />
            {permissionDenied && effectiveState.phase === 'inCall' && (
                <div className="permission-denied-banner">
                    {resolveString('permissionDeniedSettings', strings)}
                </div>
            )}
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

    // True when btn-zoom (fit/cover) renders in the same top-right corner as
    // the snapshot button on the large view: 1:1 with remote in the primary
    // tile, or any pinned multi-party stage. In those cases the snapshot
    // cascades — down in portrait, left in landscape — so it doesn't overlap.
    const cornerHasCompanion = !!primarySnapshotSource && (
        isMultiParty
            || (primarySnapshotSource.kind === 'remote' && remoteStream != null)
    );

    const snapshotButton = snapshotEnabled && primarySnapshotSource ? (
        <button
            type="button"
            className={`btn-snapshot${
                cornerHasCompanion
                    ? isWindowLandscape ? ' cascade-landscape' : ' cascade-portrait'
                    : ''
            }`}
            // `onClick` so keyboard activation (Enter / Space) works for
            // assistive tech; `onPointerUp` only stops the pointer event
            // from bubbling into the screen's tap-to-toggle-controls handler.
            onClick={handleSnapshot}
            onPointerUp={(event) => event.stopPropagation()}
            disabled={isSnapshotInFlight}
            title={resolveString('takeSnapshot', strings)}
            aria-label={resolveString('takeSnapshot', strings)}
            data-testid="call.takeSnapshot"
        >
            <Camera size={20} />
        </button>
    ) : null;

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
            {videoCaptureSupported && (
                <button type="button" onClick={handleToggleVideo} className={`btn-control ${isCameraOff ? 'active' : ''}`}>
                    {isCameraOff ? <VideoOff size={22} /> : <Video size={22} />}
                </button>
            )}
            {canFlipCamera && (
                <button type="button" onClick={handleFlipCamera} className="btn-control" disabled={isScreenSharing}>
                    <RotateCcw size={22} />
                </button>
            )}
            {showScreenShareControl && (
                <button
                    type="button"
                    onClick={handleToggleScreenShare}
                    className={`btn-control ${isSharingContent ? 'active-screen-share' : ''}`}
                    title={isSharingContent ? resolveString('stopScreenShare', strings) : resolveString('startScreenShare', strings)}
                    aria-label={isSharingContent ? resolveString('stopScreenShare', strings) : resolveString('startScreenShare', strings)}
                    data-testid="call.toggleScreenShare"
                >
                    {isSharingContent ? <ScreenShareOff size={22} /> : <ScreenShare size={22} />}
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

    const remoteAudioSinks = remoteStreamEntries.map(([cid, stream]) => (
        <RemoteAudioSink key={cid} cid={cid} stream={stream} />
    ));

    // The content-stage render branch fires for multi-party as today, and now
    // ALSO for a 1:1 call that has an independent content layout. In 1:1 it only
    // enters when `computedLayout` is present, which is gated on an independent
    // `contentSource` — so legacy 1:1 (no independent content) never enters here
    // and falls through to the unchanged single/PIP layout below (byte-identical).
    //
    // Phase gate (pure, tested in shouldRenderContentStage): `inCall` is
    // unchanged; `waiting` additionally enters ONLY when the content-stage layout
    // is present — i.e. the local user started an independent screen share before
    // any remote joined — surfacing the local content preview + "sharing, waiting
    // for participants" badge instead of the normal waiting layout. Not sharing →
    // no content-stage layout → normal waiting UI unchanged. Flag-off/legacy 1:1
    // never resolves a content-stage layout, so `waiting` stays byte-identical.
    const renderContentStage = shouldRenderContentStage({
        phase: effectiveState.phase,
        isMultiParty,
        hasContentStageLayout: computedLayout != null,
    });

    if (renderContentStage) {
        return (
            <div data-serenada-callflow="" className={rootClassName} style={rootStyle} onPointerUp={handleScreenTap}>
                {callProbe}
                {overlayContent}
                {remoteAudioSinks}
                <div className="call-container">
                    <div className="video-stage">
                        <div className="video-stage-viewport" ref={stageViewportRef}>
                            {computedLayout && streamKeyedStageActive ? (
                                <div style={{ position: 'relative', width: '100%', height: '100%' }}>
                                    {computedLayout.tiles.map((tile) => {
                                        // Stream-keyed stage: each tile id encodes {cid, kind}. Map it
                                        // back to a participant + which of their streams to show.
                                        const key = parseStageTileId(tile.id);
                                        if (!key) return null;
                                        const isContentTile = key.kind === 'content';
                                        const isLocalTile = key.cid === localParticipant?.cid;
                                        const ownerContent = isContentTile
                                            ? contentScene.all.find((c) => c.ownerId === key.cid) ?? null
                                            : null;

                                        // Camera tiles source the camera-only stream (audio plays
                                        // separately via RemoteAudioSink, so it is never double-played
                                        // here). Content tiles source the dedicated content stream.
                                        const stream = isContentTile
                                            ? ownerContent?.stream ?? null
                                            : isLocalTile
                                                ? localStream
                                                : session?.getRemoteCameraStream(key.cid) ?? null;

                                        const tileStyle: React.CSSProperties = {
                                            position: 'absolute',
                                            left: `${tile.frame.x}px`,
                                            top: `${tile.frame.y}px`,
                                            width: `${tile.frame.width}px`,
                                            height: `${tile.frame.height}px`,
                                            borderRadius: `${tile.cornerRadius}px`,
                                            zIndex: tile.zOrder,
                                        };

                                        const togglePin = () => setPinnedTile((prev) => (
                                            stageTileKeyEquals(prev, key) ? null : key
                                        ));
                                        const isPinned = stageTileKeyEquals(pinnedTile, key);
                                        const handlePinKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
                                            if (event.key !== 'Enter' && event.key !== ' ') return;
                                            event.preventDefault();
                                            event.stopPropagation();
                                            togglePin();
                                        };

                                        // Content active but its media has not arrived yet
                                        // (receiver-side hold): a connecting tile, not a stale frame.
                                        if (isContentTile && !stream) {
                                            return (
                                                <div
                                                    key={tile.id}
                                                    className="video-stage-tile"
                                                    style={tileStyle}
                                                    data-testid="call.contentLoading"
                                                    role="button"
                                                    tabIndex={0}
                                                    onPointerUp={(event) => { event.stopPropagation(); togglePin(); }}
                                                    onKeyDown={handlePinKeyDown}
                                                >
                                                    <div className="video-stage-placeholder">
                                                        <div style={spinnerStyle} />
                                                        {ownerContent?.isLocal && ownerContent.waitingForParticipants && (
                                                            <span className="video-camera-off-label">
                                                                {resolveString('contentWaitingForParticipants', strings)}
                                                            </span>
                                                        )}
                                                    </div>
                                                </div>
                                            );
                                        }

                                        // Camera tile with no stream at all (rare: peer mid-connect /
                                        // no media yet) — drop it; a video-OFF peer still has an audio
                                        // stream, so it renders below via VideoTile's camera-off overlay
                                        // (avatar + name), which already handles the video-off case.
                                        if (!stream) return null;

                                        const isPrimaryTile = tile.zOrder === 0;
                                        const tileRemote = isLocalTile ? undefined : remoteParticipantMap.get(key.cid);
                                        // Audio mute/level indicators: content tiles carry no audio.
                                        const tileAudioMuted = isContentTile ? false : isLocalTile ? isMuted : tileRemote?.audioEnabled === false;
                                        const tileDisplayName = isLocalTile ? localParticipant?.displayName : tileRemote?.displayName;
                                        // A camera tile is "video off" only when that camera is off;
                                        // a content tile always has video.
                                        const tileVideoEnabled = isContentTile
                                            ? true
                                            : isLocalTile
                                                ? localParticipant?.cameraEnabled !== false
                                                : tileRemote?.cameraEnabled !== false;
                                        // Audio-level indicator stream: the audio-bearing combined
                                        // stream for the camera tile, none for the content tile.
                                        const tileAudioStream = isContentTile
                                            ? null
                                            : isLocalTile
                                                ? localStream
                                                : remoteStreams.get(key.cid) ?? null;
                                        const shouldMirror = isLocalTile && !isContentTile && shouldMirrorLocalVideo;
                                        return (
                                            <div
                                                key={tile.id}
                                                className="video-stage-tile"
                                                style={tileStyle}
                                                data-testid={isContentTile ? 'call.contentTile' : undefined}
                                            >
                                                <VideoTile
                                                    stream={stream}
                                                    mirrored={shouldMirror}
                                                    tileStyle={{ width: '100%', height: '100%', borderRadius: 'inherit' }}
                                                    videoFit={isContentTile ? (contentFitCover ? 'cover' : 'contain') : (tile.fit === 'contain' ? 'contain' : 'cover')}
                                                    videoEnabled={tileVideoEnabled}
                                                    cameraOffLabel={tileDisplayName ?? resolveString('cameraOff', strings)}
                                                    peerId={isContentTile || isLocalTile ? undefined : tileRemote?.peerId}
                                                    displayName={isContentTile || isLocalTile ? undefined : tileRemote?.displayName}
                                                    resolveAvatar={isContentTile || isLocalTile ? undefined : resolveAvatar}
                                                    onAspectRatioChange={
                                                        isContentTile || isLocalTile ? undefined : (ratio) => {
                                                            setRemoteStageAspectRatios((prev) => (
                                                                prev[key.cid] === ratio ? prev : { ...prev, [key.cid]: ratio }
                                                            ));
                                                        }
                                                    }
                                                    onClick={togglePin}
                                                    pinned={isPinned}
                                                />
                                                {isContentTile && ownerContent?.isLocal && ownerContent.waitingForParticipants && (
                                                    <div className="content-waiting-badge" data-testid="call.contentWaiting">
                                                        {resolveString('contentWaitingForParticipants', strings)}
                                                    </div>
                                                )}
                                                {isPrimaryTile && (
                                                    <button
                                                        type="button"
                                                        className="btn-zoom"
                                                        onPointerUp={isContentTile ? toggleContentFit : toggleRemoteFit}
                                                        title={(isContentTile ? contentFitCover : remoteVideoFit === 'cover') ? 'Fit video' : 'Fill video'}
                                                    >
                                                        {(isContentTile ? contentFitCover : remoteVideoFit === 'cover') ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
                                                    </button>
                                                )}
                                                <ParticipantBadge muted={tileAudioMuted} displayName={tileVideoEnabled === false ? undefined : tileDisplayName} stream={tileAudioStream} />
                                            </div>
                                        );
                                    })}
                                </div>
                            ) : computedLayout ? (
                                <div style={{ position: 'relative', width: '100%', height: '100%' }}>
                                    {computedLayout.tiles.map((tile) => {
                                        const contentOwnerCid = contentSource?.ownerParticipantId;
                                        const isContentTile = tile.type === 'contentSource';
                                        const isLocalTile = tile.id === localParticipant?.cid;
                                        const isLocalPlaceholder = isLocalTile && contentOwnerCid === localParticipant?.cid && !isContentTile;
                                        // Content tile sources the CONTENT stream (independent screen
                                        // share), or the legacy single video presented as content when
                                        // there is no separate content stream — byte-identical flag-off.
                                        // Filmstrip participant tiles source camera as before.
                                        const stream = isContentTile
                                            ? primaryContent?.stream ?? null
                                            : isLocalTile
                                                ? localStream
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

                                        // Content is active but its media has not arrived yet
                                        // (independent receiver-side hold / per-peer pending, or
                                        // legacy track not yet present): show a connecting tile
                                        // rather than nothing or a stale camera frame.
                                        if (isContentTile && !stream) {
                                            return (
                                                <div
                                                    key={tile.id}
                                                    className="video-stage-tile"
                                                    style={tileStyle}
                                                    data-testid="call.contentLoading"
                                                >
                                                    <div className="video-stage-placeholder">
                                                        <div style={spinnerStyle} />
                                                        {primaryContent?.isLocal && primaryContent.waitingForParticipants && (
                                                            <span className="video-camera-off-label">
                                                                {resolveString('contentWaitingForParticipants', strings)}
                                                            </span>
                                                        )}
                                                    </div>
                                                </div>
                                            );
                                        }

                                        if (!stream) return null;

                                        const isPrimaryTile = tile.zOrder === 0;
                                        const tileRemote = isContentTile || isLocalTile ? undefined : remoteParticipantMap.get(tile.id);
                                        const tileAudioMuted = isContentTile ? false : isLocalTile ? isMuted : tileRemote?.audioEnabled === false;
                                        const tileDisplayName = isContentTile ? undefined : isLocalTile ? localParticipant?.displayName : tileRemote?.displayName;
                                        const tileVideoEnabled = isContentTile ? true : isLocalTile ? localParticipant?.videoEnabled !== false : tileRemote?.videoEnabled;
                                        const tileAudioStream = isContentTile ? null : isLocalTile ? localStream : remoteStreams.get(tile.id) ?? null;
                                        return (
                                            <div
                                                key={tile.id}
                                                className="video-stage-tile"
                                                style={tileStyle}
                                                data-testid={isContentTile ? 'call.contentTile' : undefined}
                                            >
                                                <VideoTile
                                                    stream={stream}
                                                    tileStyle={{ width: '100%', height: '100%', borderRadius: 'inherit' }}
                                                    videoFit={isContentTile ? (contentFitCover ? 'cover' : 'contain') : (tile.fit === 'contain' ? 'contain' : 'cover')}
                                                    videoEnabled={tileVideoEnabled}
                                                    cameraOffLabel={tileDisplayName ?? resolveString('cameraOff', strings)}
                                                    peerId={isContentTile || isLocalTile ? undefined : tileRemote?.peerId}
                                                    displayName={isContentTile || isLocalTile ? undefined : tileRemote?.displayName}
                                                    resolveAvatar={isContentTile || isLocalTile ? undefined : resolveAvatar}
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
                                                {isContentTile && primaryContent?.isLocal && primaryContent.waitingForParticipants && (
                                                    <div className="content-waiting-badge" data-testid="call.contentWaiting">
                                                        {resolveString('contentWaitingForParticipants', strings)}
                                                    </div>
                                                )}
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
                                                <ParticipantBadge muted={tileAudioMuted} displayName={tileVideoEnabled === false ? undefined : tileDisplayName} stream={tileAudioStream} />
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
                                                const gridRemote = remoteParticipantMap.get(tile.cid);
                                                return (
                                                    <div key={tile.cid} style={{ position: 'relative', width: `${tile.width}px`, height: `${tile.height}px` }}>
                                                        <VideoTile
                                                            stream={stageTile.stream}
                                                            tileStyle={{ width: '100%', height: '100%' }}
                                                            videoEnabled={gridRemote?.videoEnabled}
                                                            cameraOffLabel={gridRemote?.displayName ?? resolveString('cameraOff', strings)}
                                                            peerId={gridRemote?.peerId}
                                                            displayName={gridRemote?.displayName}
                                                            resolveAvatar={resolveAvatar}
                                                            onAspectRatioChange={(ratio) => {
                                                                setRemoteStageAspectRatios((prev) => (
                                                                    prev[tile.cid] === ratio ? prev : { ...prev, [tile.cid]: ratio }
                                                                ));
                                                            }}
                                                            onClick={() => setPinnedParticipantId(tile.cid)}
                                                        />
                                                        <ParticipantBadge muted={gridRemote?.audioEnabled === false} displayName={gridRemote?.videoEnabled === false ? undefined : gridRemote?.displayName} stream={stageTile.stream} />
                                                    </div>
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
                            <ParticipantBadge muted={isMuted} displayName={localParticipant?.displayName} stream={localStream} />
                        </div>
                    )}
                    {snapshotButton}
                </div>
                {controlsBar}
            </div>
        );
    }

    const remoteParticipant0 = effectiveState.remoteParticipants[0];

    return (
        <div data-serenada-callflow="" className={rootClassName} style={rootStyle} onPointerUp={handleScreenTap}>
            {callProbe}
            {overlayContent}
            {remoteAudioSinks}
            <div className={`call-container ${effectiveLocalLarge ? 'local-large' : ''}`}>
                <div
                    className={`video-remote-container ${effectiveLocalLarge ? 'pip' : 'primary'}`}
                    onPointerUp={effectiveLocalLarge ? (event) => {
                        event.stopPropagation();
                        handleControlsInteraction();
                        setIsLocalLarge(false);
                    } : undefined}
                >
                    {remoteStream && (
                        <StreamVideo
                            stream={remoteStream}
                            muted
                            className="video-remote"
                            style={{ objectFit: remoteVideoFit }}
                        />
                    )}

                    {remoteParticipant0?.videoEnabled === false && !showWaiting && (
                        <div className={`video-camera-off-overlay${effectiveLocalLarge ? ' compact' : ''}`}>
                            <RemoteAvatar
                                peerId={remoteParticipant0.peerId}
                                displayName={remoteParticipant0.displayName}
                                resolveAvatar={resolveAvatar}
                                compact={effectiveLocalLarge}
                            />
                            <span className="video-camera-off-label">
                                {remoteParticipant0.displayName ?? resolveString('cameraOff', strings)}
                            </span>
                        </div>
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

                    <ParticipantBadge
                        muted={remoteParticipant0?.audioEnabled === false}
                        displayName={remoteParticipant0?.videoEnabled === false ? undefined : remoteParticipant0?.displayName}
                        stream={remoteStream}
                    />

                    {waitingOverlay}
                </div>

                <div
                    className={`video-local-container ${effectiveLocalLarge ? 'primary' : 'pip'}`}
                    onPointerUp={!effectiveLocalLarge ? (event) => {
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
                    <ParticipantBadge muted={isMuted} displayName={localParticipant?.displayName} stream={localStream} />
                </div>
                {snapshotButton}
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
