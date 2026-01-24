import React, { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useSignaling } from '../contexts/SignalingContext';
import { useWebRTC } from '../contexts/WebRTCContext';
import { useToast } from '../contexts/ToastContext';
import { Mic, MicOff, Video, VideoOff, PhoneOff, Copy, AlertCircle, RotateCcw, Maximize2, Minimize2 } from 'lucide-react';
import QRCode from 'react-qr-code';
import { saveCall } from '../utils/callHistory';
import { useTranslation } from 'react-i18next';

const CallRoom: React.FC = () => {
    const { t } = useTranslation();
    const { roomId } = useParams<{ roomId: string }>();
    const navigate = useNavigate();
    const {
        joinRoom,
        leaveRoom,

        roomState,
        clientId,
        isConnected,
        error: signalingError,
        clearError
    } = useSignaling();
    const {
        startLocalMedia,
        stopLocalMedia,
        flipCamera,
        facingMode,
        hasMultipleCameras,
        localStream,
        remoteStream
    } = useWebRTC();
    const { showToast } = useToast();

    const [hasJoined, setHasJoined] = useState(false);
    const [isMuted, setIsMuted] = useState(false);
    const [isCameraOff, setIsCameraOff] = useState(false);
    const [areControlsVisible, setAreControlsVisible] = useState(true);
    const [isLocalLarge, setIsLocalLarge] = useState(false);
    const [remoteVideoFit, setRemoteVideoFit] = useState<'cover' | 'contain'>('cover');
    const lastFacingModeRef = useRef(facingMode);

    // Auto-swap videos based on camera facing mode
    useEffect(() => {
        if (facingMode !== lastFacingModeRef.current) {
            setIsLocalLarge(facingMode === 'environment');
            lastFacingModeRef.current = facingMode;
        }
    }, [facingMode]);

    const localVideoRef = useRef<HTMLVideoElement>(null);
    const remoteVideoRef = useRef<HTMLVideoElement>(null);
    const idleTimeoutRef = useRef<number | null>(null);

    const isMobileDevice = () => {
        if (typeof window === 'undefined') return false;
        return (
            window.matchMedia('(pointer: coarse)').matches ||
            /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent)
        );
    };
    const shouldMirrorLocalVideo = facingMode === 'user';

    const exitFullscreenIfActive = () => {
        const doc = document as Document & {
            webkitExitFullscreen?: () => Promise<void>;
            msExitFullscreen?: () => Promise<void>;
        };
        const exitFullscreen = document.exitFullscreen || doc.webkitExitFullscreen || doc.msExitFullscreen;
        if (exitFullscreen && document.fullscreenElement) {
            exitFullscreen.call(document).catch(() => { });
        }
    };

    // Handle stream attachment
    useEffect(() => {
        if (localVideoRef.current && localStream) {
            localVideoRef.current.srcObject = localStream;
        }
    }, [localStream, hasJoined]);

    useEffect(() => {
        if (remoteVideoRef.current && remoteStream) {
            remoteVideoRef.current.srcObject = remoteStream;
        }
    }, [remoteStream]);

    // Handle room state changes
    useEffect(() => {
        if (!roomId) {
            navigate('/');
            return;
        }
    }, [roomId, navigate]);

    // Auto-start local media for preview when not joined
    const mediaStartedRef = useRef(false);

    useEffect(() => {
        if (!hasJoined && isConnected && !mediaStartedRef.current) {
            mediaStartedRef.current = true;
            startLocalMedia().catch(err => {
                console.error("Initial media start failed", err);
                mediaStartedRef.current = false;
            });
        }
    }, [hasJoined, isConnected, startLocalMedia]);

    // Unified cleanup on unmount - using refs to avoid re-running when context functions change
    const cleanupRefs = useRef({ leaveRoom, stopLocalMedia, roomId });
    useEffect(() => {
        cleanupRefs.current = { leaveRoom, stopLocalMedia, roomId };
    }, [leaveRoom, stopLocalMedia, roomId]);

    useEffect(() => {
        return () => {
            const { leaveRoom: lr, stopLocalMedia: slm, roomId: rid } = cleanupRefs.current;
            if (callStartTimeRef.current && rid) {
                const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
                saveCall({
                    roomId: rid,
                    startTime: callStartTimeRef.current,
                    duration: duration > 0 ? duration : 0
                });
                callStartTimeRef.current = null;
            }
            lr();
            slm();
            mediaStartedRef.current = false;
        };
    }, []); // Run only on mount/unmount
    // eslint-disable-line react-hooks/exhaustive-deps

    const callStartTimeRef = useRef<number | null>(null);

    const handleJoin = async () => {
        if (!roomId) return;
        try {
            clearError();
            if (isMobileDevice()) {
                const rootElement = document.documentElement as HTMLElement & {
                    webkitRequestFullscreen?: () => Promise<void>;
                    msRequestFullscreen?: () => Promise<void>;
                };
                const requestFullscreen =
                    rootElement.requestFullscreen ||
                    rootElement.webkitRequestFullscreen ||
                    rootElement.msRequestFullscreen;
                if (requestFullscreen) {
                    requestFullscreen.call(rootElement).catch(() => { });
                }
            }
            await startLocalMedia();
            // Tiny delay to ensure state propagates
            setTimeout(() => {
                joinRoom(roomId);
                setHasJoined(true);
                callStartTimeRef.current = Date.now();
            }, 50);
        } catch (err) {
            console.error("Failed to join room", err);
            showToast('error', t('toast_camera_error'));
        }
    };

    // If we receive a signaling error while trying to join, or if we are joined but room state becomes null
    useEffect(() => {
        if (signalingError && hasJoined && !roomState) {
            setHasJoined(false);
            stopLocalMedia();
        }
    }, [signalingError, hasJoined, roomState, stopLocalMedia]);

    const handleLeave = () => {
        if (callStartTimeRef.current && roomId) {
            const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
            saveCall({
                roomId,
                startTime: callStartTimeRef.current,
                duration: duration > 0 ? duration : 0
            });
            callStartTimeRef.current = null;
        }
        leaveRoom();
        stopLocalMedia();
        exitFullscreenIfActive();
        navigate('/');
    };


    const scheduleIdleHide = () => {
        if (idleTimeoutRef.current) {
            window.clearTimeout(idleTimeoutRef.current);
        }
        idleTimeoutRef.current = window.setTimeout(() => {
            setAreControlsVisible(false);
        }, 10000);
    };

    const clearIdleHide = () => {
        if (idleTimeoutRef.current) {
            window.clearTimeout(idleTimeoutRef.current);
        }
    };

    const handleScreenTap = () => {
        setAreControlsVisible(prev => {
            const next = !prev;
            if (next) {
                scheduleIdleHide();
            } else {
                clearIdleHide();
            }
            return next;
        });
    };

    const handleControlsInteraction = () => {
        setAreControlsVisible(true);
        scheduleIdleHide();
    };

    useEffect(() => {
        if (!hasJoined) return;
        scheduleIdleHide();
        const handleBeforeUnload = () => {
            exitFullscreenIfActive();
        };
        window.addEventListener('beforeunload', handleBeforeUnload);
        return () => {
            clearIdleHide();
            window.removeEventListener('beforeunload', handleBeforeUnload);
            exitFullscreenIfActive();
        };
    }, [hasJoined]);



    const toggleMute = () => {
        if (localStream) {
            localStream.getAudioTracks().forEach(t => t.enabled = !t.enabled);
            setIsMuted(!isMuted);
        }
    }

    const toggleVideo = () => {
        if (localStream) {
            localStream.getVideoTracks().forEach(t => t.enabled = !t.enabled);
            setIsCameraOff(!isCameraOff);
        }
    }

    const copyLink = () => {
        navigator.clipboard.writeText(window.location.href);
        showToast('success', t('toast_link_copied'));
    };

    const toggleRemoteVideoFit = (e: React.PointerEvent | React.MouseEvent) => {
        e.stopPropagation();
        setRemoteVideoFit(prev => prev === 'cover' ? 'contain' : 'cover');
    };

    // Render Pre-Join
    if (!hasJoined) {
        return (
            <div className="page-container center-content">
                <div className="card prejoin-card">
                    <h2>{t('ready_to_join')}</h2>
                    <p>{t('room_id')} {roomId}</p>
                    {signalingError && (
                        <div className="error-message">
                            <AlertCircle size={20} />
                            {signalingError}
                        </div>
                    )}
                    <div className="video-preview-container">
                        <video
                            ref={localVideoRef}
                            autoPlay
                            playsInline
                            muted
                            className={`video-preview ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                        />
                        {!localStream && <div className="video-placeholder">{t('camera_off')}</div>}
                    </div>
                    <div className="button-group">
                        <button className="btn-primary" onClick={handleJoin} disabled={!isConnected}>
                            {isConnected ? t('join_call') : t('connecting')}
                        </button>
                        <button className="btn-secondary" onClick={copyLink}>
                            <Copy size={16} /> {t('copy_link')}
                        </button>
                        <button className="btn-secondary" onClick={handleLeave}>
                            {t('home')}
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // Render In-Call
    const otherParticipant = roomState?.participants?.find(p => p.cid !== clientId);
    const shareUrl = typeof window !== 'undefined' ? window.location.href : '';


    return (
        <div
            className={`call-container ${areControlsVisible ? '' : 'controls-hidden'} ${isLocalLarge ? 'local-large' : ''}`}
            onPointerUp={handleScreenTap}
        >
            {/* Primary Video (Full Screen) */}
            <div
                className={`video-remote-container ${isLocalLarge ? 'pip' : 'primary'}`}
                onPointerUp={isLocalLarge ? (e) => {
                    e.stopPropagation();
                    setIsLocalLarge(false);
                } : undefined}
            >
                <video
                    ref={remoteVideoRef}
                    autoPlay
                    playsInline
                    className="video-remote"
                    style={{ objectFit: remoteVideoFit }}
                />

                {remoteStream && (
                    <button
                        className="btn-zoom"
                        onPointerUp={toggleRemoteVideoFit}
                        title={remoteVideoFit === 'cover' ? t('zoom_fit') : t('zoom_fill')}
                    >
                        {remoteVideoFit === 'cover' ? <Minimize2 /> : <Maximize2 />}
                    </button>
                )}
                {!remoteStream && (
                    <div className="waiting-message">
                        {otherParticipant ? t('waiting_message_person') : t('waiting_message')}
                        {!isLocalLarge && (
                            <>
                                <div className="qr-code-container" aria-hidden={!shareUrl}>
                                    {shareUrl && <QRCode value={shareUrl} size={184} />}
                                </div>
                                <button
                                    className="btn-small"
                                    onClick={copyLink}
                                    onPointerUp={event => {
                                        event.stopPropagation();
                                        handleControlsInteraction();
                                    }}
                                >
                                    {t('copy_link_share')}
                                </button>
                                <button
                                    className="btn-small"
                                    onClick={handleLeave}
                                    onPointerUp={event => {
                                        event.stopPropagation();
                                        handleControlsInteraction();
                                    }}
                                >
                                    {t('home')}
                                </button>
                            </>
                        )}
                    </div>
                )}
            </div>

            {/* PIP Video (Thumbnail) */}
            <div
                className={`video-local-container ${isLocalLarge ? 'primary' : 'pip'}`}
                onPointerUp={!isLocalLarge ? (e) => {
                    e.stopPropagation();
                    setIsLocalLarge(true);
                } : undefined}
            >
                <video
                    ref={localVideoRef}
                    autoPlay
                    playsInline
                    muted
                    className={`video-local ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                />
            </div>

            {/* Controls */}
            <div
                className="controls-bar"
                onPointerUp={event => {
                    event.stopPropagation();
                    handleControlsInteraction();
                }}
            >
                {hasMultipleCameras && (
                    <button onClick={flipCamera} className="btn-control">
                        <RotateCcw />
                    </button>
                )}
                <button onClick={toggleMute} className={`btn-control ${isMuted ? 'active' : ''}`}>
                    {isMuted ? <MicOff /> : <Mic />}
                </button>
                <button onClick={toggleVideo} className={`btn-control ${isCameraOff ? 'active' : ''}`}>
                    {isCameraOff ? <VideoOff /> : <Video />}
                </button>
                <button onClick={handleLeave} className="btn-control btn-leave">
                    <PhoneOff />
                </button>
            </div>
        </div>
    );
};

export default CallRoom;
