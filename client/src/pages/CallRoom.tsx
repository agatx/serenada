import React, { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useSignaling } from '../contexts/SignalingContext';
import { useWebRTC } from '../contexts/WebRTCContext';
import { useToast } from '../contexts/ToastContext';
import { Mic, MicOff, Video, VideoOff, PhoneOff, Copy, AlertCircle, RotateCcw, Maximize2, Minimize2, CheckSquare, Square } from 'lucide-react';
import QRCode from 'react-qr-code';
import { saveCall } from '../utils/callHistory';
import { useTranslation } from 'react-i18next';
import { playJoinChime } from '../utils/audio';
import { getOrCreatePushKeyPair } from '../utils/pushCrypto';

function urlBase64ToUint8Array(base64String: string) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding)
        .replace(/\-/g, '+')
        .replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; ++i) {
        outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
}

function base64FromBytes(bytes: Uint8Array): string {
    let binary = '';
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
        binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
    }
    return window.btoa(binary);
}

async function fetchRecipients(roomId: string): Promise<{ id: number; publicKey: JsonWebKey }[]> {
    const res = await fetch(`/api/push/recipients?roomId=${encodeURIComponent(roomId)}`);
    if (!res.ok) return [];
    const data = await res.json();
    if (!Array.isArray(data)) return [];
    return data.filter((item: any) => typeof item?.id === 'number' && item?.publicKey);
}

async function buildEncryptedSnapshot(stream: MediaStream, roomId: string): Promise<string | null> {
    if (!('crypto' in window) || !window.crypto.subtle) return null;

    const recipients = await fetchRecipients(roomId);
    if (recipients.length === 0) return null;

    const snapshot = await captureSnapshotBytes(stream);
    if (!snapshot) return null;
    if (snapshot.bytes.length > 200 * 1024) return null;

    const snapshotKey = await crypto.subtle.generateKey(
        { name: 'AES-GCM', length: 256 },
        true,
        ['encrypt', 'decrypt']
    );
    const snapshotIv = crypto.getRandomValues(new Uint8Array(12));
    const snapshotBuffer = snapshot.bytes.buffer.slice(
        snapshot.bytes.byteOffset,
        snapshot.bytes.byteOffset + snapshot.bytes.byteLength
    ) as ArrayBuffer;
    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: snapshotIv },
        snapshotKey,
        snapshotBuffer
    );
    const snapshotKeyRaw = new Uint8Array(await crypto.subtle.exportKey('raw', snapshotKey));

    const ephemeral = await crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits']
    );
    const ephemeralPubRaw = new Uint8Array(await crypto.subtle.exportKey('raw', ephemeral.publicKey));
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const info = new TextEncoder().encode('serenada-push-snapshot');

    const recipientsPayload: { id: number; wrappedKey: string; wrappedKeyIv: string }[] = [];

    for (const recipient of recipients) {
        try {
            const recipientKey = await crypto.subtle.importKey(
                'jwk',
                recipient.publicKey,
                { name: 'ECDH', namedCurve: 'P-256' },
                false,
                []
            );
            const sharedBits = await crypto.subtle.deriveBits(
                { name: 'ECDH', public: recipientKey },
                ephemeral.privateKey,
                256
            );
            const hkdfKey = await crypto.subtle.importKey('raw', sharedBits, 'HKDF', false, ['deriveKey']);
            const wrapKey = await crypto.subtle.deriveKey(
                { name: 'HKDF', hash: 'SHA-256', salt, info },
                hkdfKey,
                { name: 'AES-GCM', length: 256 },
                false,
                ['encrypt', 'decrypt']
            );
            const wrapIv = crypto.getRandomValues(new Uint8Array(12));
            const wrappedKey = await crypto.subtle.encrypt(
                { name: 'AES-GCM', iv: wrapIv },
                wrapKey,
                snapshotKeyRaw
            );
            recipientsPayload.push({
                id: recipient.id,
                wrappedKey: base64FromBytes(new Uint8Array(wrappedKey)),
                wrappedKeyIv: base64FromBytes(wrapIv)
            });
        } catch (err) {
            console.warn('[Push] Failed to encrypt snapshot for recipient', err);
        }
    }

    if (recipientsPayload.length === 0) return null;

    const res = await fetch('/api/push/snapshot', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            ciphertext: base64FromBytes(new Uint8Array(ciphertext)),
            snapshotIv: base64FromBytes(snapshotIv),
            snapshotSalt: base64FromBytes(salt),
            snapshotEphemeralPubKey: base64FromBytes(ephemeralPubRaw),
            snapshotMime: snapshot.mime,
            recipients: recipientsPayload
        })
    });

    if (!res.ok) return null;
    const data = await res.json();
    return data.id || null;
}

async function captureSnapshotBytes(stream: MediaStream): Promise<{ bytes: Uint8Array; mime: string } | null> {
    const track = stream.getVideoTracks()[0];
    if (!track) return null;

    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.srcObject = new MediaStream([track]);

    try {
        await video.play();
    } catch {
        // Ignore autoplay restrictions; we'll still try to grab a frame.
    }

    if (video.videoWidth === 0 || video.videoHeight === 0) {
        await new Promise<void>((resolve) => {
            const onLoaded = () => {
                video.removeEventListener('loadedmetadata', onLoaded);
                resolve();
            };
            video.addEventListener('loadedmetadata', onLoaded);
        });
    }

    const maxWidth = 320;
    const width = video.videoWidth || 320;
    const height = video.videoHeight || 240;
    const scale = width > maxWidth ? maxWidth / width : 1;
    const targetWidth = Math.round(width * scale);
    const targetHeight = Math.round(height * scale);

    const canvas = document.createElement('canvas');
    canvas.width = targetWidth;
    canvas.height = targetHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    ctx.drawImage(video, 0, 0, targetWidth, targetHeight);

    video.pause();
    video.srcObject = null;

    const blob = await new Promise<Blob | null>((resolve) => {
        canvas.toBlob((result) => resolve(result), 'image/jpeg', 0.7);
    });
    if (!blob) return null;

    const buffer = await blob.arrayBuffer();
    return { bytes: new Uint8Array(buffer), mime: 'image/jpeg' };
}

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
        activeTransport,
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
        remoteStream,
        iceConnectionState,
        connectionState,
        signalingState
    } = useWebRTC();
    const { showToast } = useToast();

    const [hasJoined, setHasJoined] = useState(false);
    const [isMuted, setIsMuted] = useState(false);
    const [isCameraOff, setIsCameraOff] = useState(false);
    const [areControlsVisible, setAreControlsVisible] = useState(true);
    const [isLocalLarge, setIsLocalLarge] = useState(false);
    const [remoteVideoFit, setRemoteVideoFit] = useState<'cover' | 'contain'>('cover');
    const [showReconnecting, setShowReconnecting] = useState(false);
    const [showWaiting, setShowWaiting] = useState(true);

    const lastFacingModeRef = useRef(facingMode);

    // Push Notifications State
    const [isSubscribed, setIsSubscribed] = useState(false);
    const [pushSupported, setPushSupported] = useState(false);
    const [vapidKey, setVapidKey] = useState<string | null>(null);

    useEffect(() => {
        if ('serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window) {
            setPushSupported(true);
            fetch('/api/push/vapid-public-key')
                .then(res => res.json())
                .then(data => setVapidKey(data.publicKey))
                .catch(console.error);

            navigator.serviceWorker.ready.then(reg => {
                reg.pushManager.getSubscription().then(sub => {
                    if (sub) {
                        setIsSubscribed(true);
                        getOrCreatePushKeyPair()
                            .then(({ publicJwk }) => fetch('/api/push/subscribe?roomId=' + roomId, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ ...sub.toJSON(), locale: navigator.language, encPublicKey: publicJwk })
                            }))
                            .catch(() => { });
                    }
                });
            });
        }
    }, []);

    const handlePushToggle = async (e: React.MouseEvent | React.PointerEvent) => {
        e.stopPropagation();
        handleControlsInteraction(); // Keep controls visible

        if (!vapidKey) return;
        try {
            const reg = await navigator.serviceWorker.ready;
            if (isSubscribed) {
                const sub = await reg.pushManager.getSubscription();
                if (sub) {
                    await sub.unsubscribe();
                    await fetch('/api/push/subscribe?roomId=' + roomId, {
                        method: 'DELETE',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ endpoint: sub.endpoint })
                    });
                    setIsSubscribed(false);
                    showToast('success', 'Unsubscribed');
                }
            } else {
                const permission = await Notification.requestPermission();
                if (permission !== 'granted') {
                    showToast('error', 'Notifications blocked');
                    return;
                }
                const { publicJwk } = await getOrCreatePushKeyPair();
                const sub = await reg.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: urlBase64ToUint8Array(vapidKey)
                });
                await fetch('/api/push/subscribe?roomId=' + roomId, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ...sub.toJSON(), locale: navigator.language, encPublicKey: publicJwk })
                });
                setIsSubscribed(true);
                showToast('success', 'You will be notified!');
            }
        } catch (err) {
            console.error(err);
            showToast('error', 'Failed to update subscription');
        }
    };

    // Track participant count to play chime on join
    const prevParticipantsCountRef = useRef(0);

    useEffect(() => {
        if (!hasJoined || !roomState) {
            prevParticipantsCountRef.current = 0;
            return;
        }

        const currentCount = roomState.participants.length;
        // If count increased and it's not the first time we joined (count > 1)
        if (currentCount > prevParticipantsCountRef.current && prevParticipantsCountRef.current > 0 && currentCount > 1) {
            console.log('[CallRoom] Playing join chime');
            playJoinChime();
        }
        prevParticipantsCountRef.current = currentCount;
    }, [roomState?.participants.length, hasJoined]);

    // Auto-swap videos based on camera facing mode
    useEffect(() => {
        if (facingMode !== lastFacingModeRef.current) {
            setIsLocalLarge(facingMode === 'environment');
            lastFacingModeRef.current = facingMode;
        }
    }, [facingMode]);

    useEffect(() => {
        if (!hasJoined) {
            setShowReconnecting(false);
            return;
        }
        const reconnecting =
            !isConnected ||
            iceConnectionState === 'disconnected' ||
            iceConnectionState === 'failed' ||
            connectionState === 'disconnected' ||
            connectionState === 'failed';

        if (!reconnecting) {
            setShowReconnecting(false);
            return;
        }

        const timer = window.setTimeout(() => {
            setShowReconnecting(true);
        }, 800);

        return () => {
            window.clearTimeout(timer);
        };
    }, [hasJoined, isConnected, iceConnectionState, connectionState]);

    const localVideoRef = useRef<HTMLVideoElement>(null);
    const remoteVideoRef = useRef<HTMLVideoElement>(null);
    const idleTimeoutRef = useRef<number | null>(null);
    const waitingTimerRef = useRef<number | null>(null);
    const [showDebug, setShowDebug] = useState(false);
    const debugTapRef = useRef<number>(0);
    const debugTapTimeoutRef = useRef<number | null>(null);

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

    useEffect(() => {
        const clearWaitingTimer = () => {
            if (waitingTimerRef.current) {
                window.clearTimeout(waitingTimerRef.current);
                waitingTimerRef.current = null;
            }
        };

        clearWaitingTimer();

        if (!hasJoined) {
            setShowWaiting(true);
            return clearWaitingTimer;
        }

        if (remoteStream) {
            setShowWaiting(false);
            return clearWaitingTimer;
        }

        if (showReconnecting) {
            setShowWaiting(false);
            waitingTimerRef.current = window.setTimeout(() => {
                setShowWaiting(true);
            }, 8000);
            return clearWaitingTimer;
        }

        setShowWaiting(true);
        return clearWaitingTimer;
    }, [hasJoined, remoteStream, showReconnecting]);

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
            const stream = await startLocalMedia();
            let snapshotId: string | null = null;
            if (stream) {
                const snapshotPromise = buildEncryptedSnapshot(stream, roomId).catch((err) => {
                    console.warn('[Push] Failed to build encrypted snapshot', err);
                    return null;
                });
                snapshotId = await Promise.race([
                    snapshotPromise,
                    new Promise<null>((resolve) => setTimeout(() => resolve(null), 1200))
                ]);
            }
            // Tiny delay to ensure state propagates
            setTimeout(() => {
                joinRoom(roomId, snapshotId ? { snapshotId } : undefined);
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

    const handleDebugToggle = () => {
        setShowDebug(prev => !prev);
    };

    const handleDebugCornerTap = (event: React.PointerEvent | React.MouseEvent) => {
        event.preventDefault();
        event.stopPropagation();
        const now = Date.now();
        if (debugTapTimeoutRef.current) {
            window.clearTimeout(debugTapTimeoutRef.current);
            debugTapTimeoutRef.current = null;
        }
        if (now - debugTapRef.current < 450) {
            debugTapRef.current = 0;
            handleDebugToggle();
            return;
        }
        debugTapRef.current = now;
        debugTapTimeoutRef.current = window.setTimeout(() => {
            debugTapRef.current = 0;
            debugTapTimeoutRef.current = null;
        }, 500);
    };

    const handleDebugCornerPointerUp = (event: React.PointerEvent) => {
        event.preventDefault();
        event.stopPropagation();
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
            <div
                className="debug-toggle-zone"
                onPointerDown={handleDebugCornerTap}
                onPointerUp={handleDebugCornerPointerUp}
                onPointerCancel={handleDebugCornerPointerUp}
            />
            {showDebug && (
                <div className="debug-panel">
                    <div>Signaling: {isConnected ? 'connected' : 'disconnected'}</div>
                    <div>Transport: {activeTransport ?? 'n/a'}</div>
                    <div>ICE: {iceConnectionState}</div>
                    <div>PC: {connectionState}</div>
                    <div>SDP: {signalingState}</div>
                    <div>Room: {roomState ? `${roomState.participants.length} participants` : 'none'}</div>
                    <div>Reconnecting: {showReconnecting ? 'yes' : 'no'}</div>
                </div>
            )}
            {showReconnecting && (
                <div className="reconnect-overlay" aria-live="polite">
                    <div className="reconnect-badge">{t('connecting')}</div>
                </div>
            )}
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
                {showWaiting && (
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

                                {pushSupported && (
                                    <button
                                        className={`btn-small ${isSubscribed ? 'active' : ''}`}
                                        onClick={handlePushToggle}
                                        onPointerUp={event => {
                                            event.stopPropagation();
                                            handleControlsInteraction();
                                        }}
                                        style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
                                    >
                                        {isSubscribed ? <CheckSquare size={16} /> : <Square size={16} />}
                                        {isSubscribed ? t('notify_me_on') : t('notify_me')}
                                    </button>
                                )}

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
        </div >
    );
};

export default CallRoom;
