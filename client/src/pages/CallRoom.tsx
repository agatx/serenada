import React, { useCallback, useEffect, useRef, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useSignaling } from '../contexts/SignalingContext';
import { useWebRTC } from '../contexts/WebRTCContext';
import { useToast } from '../contexts/ToastContext';
import { Mic, MicOff, Video, VideoOff, PhoneOff, Copy, AlertCircle, RotateCcw, Maximize2, Minimize2, CheckSquare, Square, ScreenShare, ScreenShareOff, BellRing, Pin } from 'lucide-react';
import QRCode from 'react-qr-code';
import { saveCall } from '../utils/callHistory';
import { useTranslation } from 'react-i18next';
import { playJoinChime } from '../utils/audio';
import {
    computeStageLayout,
    computeLayout,
    clampStageTileAspectRatio,
    STAGE_TILE_GAP_PX,
    type CallScene,
    type ContentSource,
    type LayoutResult,
} from '../layout/computeLayout';
import { getOrCreatePushKeyPair } from '../utils/pushCrypto';
import { getPersistedRemoteVideoFit, persistRemoteVideoFit, type RemoteVideoFit } from '../utils/remoteVideoFit';
import { saveRoom, markRoomJoined, type SaveRoomResult } from '../utils/savedRooms';
import { buildDebugPanelSections, useRealtimeCallStats } from './callDiagnostics';
import { SNAPSHOT_PREPARE_TIMEOUT_MS } from '../constants/webrtcResilience';

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

// Multi-party remote tile (defined outside component to avoid remounts)
const VideoTile: React.FC<{
    stream: MediaStream;
    tileStyle?: React.CSSProperties;
    label?: string;
    onAspectRatioChange?: (ratio: number) => void;
    onClick?: () => void;
    pinned?: boolean;
    videoFit?: 'cover' | 'contain';
}> = ({ stream, tileStyle, label, onAspectRatioChange, onClick, pinned, videoFit }) => {
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

    return (
        <div
            className="video-stage-tile"
            style={tileStyle}
            onPointerUp={onClick ? (e) => { e.stopPropagation(); onClick(); } : undefined}
            onKeyDown={onClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } } : undefined}
            role={onClick ? 'button' : undefined}
            tabIndex={onClick ? 0 : undefined}
        >
            <video ref={videoRef} autoPlay playsInline className="video-stage-remote" style={videoFit ? { objectFit: videoFit } : undefined} />
            {label && <div className="video-grid-label">{label}</div>}
            {pinned && <div className="video-stage-pin-indicator" aria-hidden="true"><Pin size={16} /></div>}
        </div>
    );
};

const CallRoom: React.FC = () => {
    const { t } = useTranslation();
    const { roomId } = useParams<{ roomId: string }>();
    const navigate = useNavigate();

    // Parse URL parameters for room name sharing
    const location = window.location;
    const urlParams = new URLSearchParams(location.search);
    const sharedName = urlParams.get('name');
    // Web clients always create group-capable rooms unless explicitly restricted
    const isGroupCallRequested = urlParams.get('group') !== '0';

    const {
        joinRoom,
        leaveRoom,

        roomState,
        clientId,
        isConnected,
        activeTransport,
        subscribeToMessages,
        error: signalingError,
        clearError
    } = useSignaling();
    const {
        startLocalMedia,
        stopLocalMedia,
        startScreenShare,
        stopScreenShare,
        isScreenSharing,
        canScreenShare,
        flipCamera,
        facingMode,
        hasMultipleCameras,
        localStream,
        remoteStreams,
        peerConnections,
        iceConnectionState,
        connectionState,
        signalingState,
        connectionStatus
    } = useWebRTC();
    const { showToast } = useToast();

    // Derive single remote stream for 1:1 layout and multi-party flag (memoized)
    const remoteStreamEntries = useMemo(() => Array.from(remoteStreams.entries()), [remoteStreams]);
    const isMultiParty = remoteStreamEntries.length > 1;
    const remoteStream = remoteStreamEntries.length === 1 ? remoteStreamEntries[0][1] : null;
    // For diagnostics (memoized to avoid effect churn)
    const peerConnectionsArray = useMemo(() => Array.from(peerConnections.values()), [peerConnections]);

    const [hasJoined, setHasJoined] = useState(false);
    const [isMuted, setIsMuted] = useState(false);
    const [isCameraOff, setIsCameraOff] = useState(false);
    const [areControlsVisible, setAreControlsVisible] = useState(true);
    const [isLocalLarge, setIsLocalLarge] = useState(false);
    const [remoteVideoFit, setRemoteVideoFit] = useState<RemoteVideoFit>(() => getPersistedRemoteVideoFit());
    const [showRecoveringBadge, setShowRecoveringBadge] = useState(false);
    const [showWaiting, setShowWaiting] = useState(true);
    const [pinnedParticipantId, setPinnedParticipantId] = useState<string | null>(null);
    const [remoteContentState, setRemoteContentState] = useState<{ cid: string; contentType: ContentSource['type'] } | null>(null);

    const lastFacingModeRef = useRef(facingMode);

    // Push Notifications State
    const [isSubscribed, setIsSubscribed] = useState(false);
    const [pushSupported, setPushSupported] = useState(false);
    const [vapidKey, setVapidKey] = useState<string | null>(null);
    const [isInviting, setIsInviting] = useState(false);

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

    const handleInvite = async (e: React.MouseEvent | React.PointerEvent) => {
        e.stopPropagation();
        handleControlsInteraction();
        if (!roomId || isInviting) return;

        setIsInviting(true);
        try {
            let endpoint: string | undefined;
            if ('serviceWorker' in navigator && 'PushManager' in window) {
                const reg = await navigator.serviceWorker.ready;
                const sub = await reg.pushManager.getSubscription();
                endpoint = sub?.endpoint;
            }
            const res = await fetch(`/api/push/invite?roomId=${encodeURIComponent(roomId)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(endpoint ? { endpoint } : {})
            });
            if (!res.ok) {
                throw new Error(`Invite request failed: ${res.status}`);
            }
            showToast('success', t('toast_invite_sent'));
        } catch (err) {
            console.error('[Invite] Failed to send invite', err);
            showToast('error', t('toast_invite_failed'));
        } finally {
            setIsInviting(false);
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
            setShowRecoveringBadge(false);
            return;
        }

        if (connectionStatus !== 'recovering') {
            setShowRecoveringBadge(false);
            return;
        }

        const timer = window.setTimeout(() => {
            setShowRecoveringBadge(true);
        }, 800);

        return () => {
            window.clearTimeout(timer);
        };
    }, [hasJoined, connectionStatus]);

    const showReconnecting =
        hasJoined &&
        (connectionStatus === 'retrying' || (connectionStatus === 'recovering' && showRecoveringBadge));

    const localVideoRef = useRef<HTMLVideoElement>(null);
    const remoteVideoRef = useRef<HTMLVideoElement>(null);
    const stageViewportRef = useRef<HTMLDivElement | null>(null);
    const idleTimeoutRef = useRef<number | null>(null);
    const isControlsAutoHideEnabledRef = useRef(true);
    const wereControlsLastHiddenByAutoHideRef = useRef(false);
    const waitingTimerRef = useRef<number | null>(null);
    const [showDebug, setShowDebug] = useState(false);
    const debugTapRef = useRef<number>(0);
    const debugTapTimeoutRef = useRef<number | null>(null);
    const [stageViewportSize, setStageViewportSize] = useState(() => ({
        width: typeof window !== 'undefined' ? window.innerWidth : 0,
        height: typeof window !== 'undefined' ? window.innerHeight : 0
    }));
    const [remoteStageAspectRatios, setRemoteStageAspectRatios] = useState<Record<string, number>>({});
    const realtimeStats = useRealtimeCallStats(peerConnectionsArray, showDebug && hasJoined);

    const isMobileDevice = () => {
        if (typeof window === 'undefined') return false;
        return (
            window.matchMedia('(pointer: coarse)').matches ||
            /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent)
        );
    };
    const isMobileBrowser = () => {
        if (typeof window === 'undefined') return false;
        return /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
    };
    const shouldMirrorLocalVideo = facingMode === 'user' && !isScreenSharing;
    const showScreenShareControl = canScreenShare && !isMobileBrowser();

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

    const attachVideoStream = useCallback((video: HTMLVideoElement | null, stream: MediaStream | null) => {
        if (!video) return;
        if (video.srcObject !== stream) {
            video.srcObject = stream;
        }
    }, []);

    const setLocalVideoRef = useCallback((node: HTMLVideoElement | null) => {
        localVideoRef.current = node;
        attachVideoStream(node, localStream);
    }, [attachVideoStream, localStream]);

    const setRemoteVideoRef = useCallback((node: HTMLVideoElement | null) => {
        remoteVideoRef.current = node;
        attachVideoStream(node, remoteStream);
    }, [attachVideoStream, remoteStream]);

    const setStageViewportNode = useCallback((node: HTMLDivElement | null) => {
        stageViewportRef.current = node;
    }, []);

    const updateRemoteStageAspectRatio = useCallback((cid: string, ratio: number) => {
        setRemoteStageAspectRatios((prev) => {
            const nextRatio = clampStageTileAspectRatio(ratio);
            if (prev[cid] === nextRatio) {
                return prev;
            }
            return {
                ...prev,
                [cid]: nextRatio
            };
        });
    }, []);

    const remoteStageTiles = useMemo(() => (
        remoteStreamEntries.map(([cid, stream]) => ({
            cid,
            stream,
            aspectRatio: remoteStageAspectRatios[cid] ?? clampStageTileAspectRatio(getStreamAspectRatio(stream))
        }))
    ), [remoteStageAspectRatios, remoteStreamEntries]);

    const remoteStageTileMap = useMemo(() => (
        new Map(remoteStageTiles.map((tile) => [tile.cid, tile]))
    ), [remoteStageTiles]);

    const remoteStageLayout = useMemo(() => (
        computeStageLayout(remoteStageTiles, stageViewportSize.width, stageViewportSize.height, STAGE_TILE_GAP_PX)
    ), [remoteStageTiles, stageViewportSize.height, stageViewportSize.width]);

    // Content source: local screen share or remote content in multi-party triggers content layout
    const contentSource = useMemo((): ContentSource | null => {
        if (!isMultiParty) return null;
        if (isScreenSharing && clientId) {
            return { type: 'screenShare', ownerParticipantId: clientId, aspectRatio: null };
        }
        if (remoteContentState) {
            return { type: remoteContentState.contentType, ownerParticipantId: remoteContentState.cid, aspectRatio: null };
        }
        return null;
    }, [isScreenSharing, isMultiParty, clientId, remoteContentState]);

    // Compute focus/content layout when pinned or content source active
    const computedLayout = useMemo((): LayoutResult | null => {
        if ((!pinnedParticipantId && !contentSource) || !isMultiParty || !clientId) return null;

        const participants = [
            ...remoteStreamEntries.map(([cid]) => ({
                id: cid,
                role: 'remote' as const,
                videoEnabled: true,
                videoAspectRatio: remoteStageAspectRatios[cid] ?? null,
            })),
            {
                id: clientId,
                role: 'local' as const,
                videoEnabled: !isCameraOff,
                videoAspectRatio: null as number | null,
            },
        ];

        const scene: CallScene = {
            viewportWidth: stageViewportSize.width,
            viewportHeight: stageViewportSize.height,
            safeAreaInsets: { top: 0, bottom: 0, left: 0, right: 0 },
            participants,
            localParticipantId: clientId,
            activeSpeakerId: null,
            pinnedParticipantId: contentSource ? null : pinnedParticipantId,
            contentSource,
            userPrefs: { swappedLocalAndRemote: false, dominantFit: remoteVideoFit },
        };

        return computeLayout(scene);
    }, [pinnedParticipantId, contentSource, isMultiParty, clientId, remoteStreamEntries, remoteStageAspectRatios, isCameraOff, stageViewportSize, remoteVideoFit]);

    // Handle stream attachment
    useEffect(() => {
        attachVideoStream(localVideoRef.current, localStream);
    }, [attachVideoStream, localStream, hasJoined]);

    useEffect(() => {
        attachVideoStream(remoteVideoRef.current, remoteStream);
    }, [attachVideoStream, remoteStream]);

    useEffect(() => {
        const activeRemoteCids = new Set(remoteStreamEntries.map(([cid]) => cid));
        setRemoteStageAspectRatios((prev) => {
            const nextEntries = Object.entries(prev).filter(([cid]) => activeRemoteCids.has(cid));
            if (nextEntries.length === Object.keys(prev).length) {
                return prev;
            }
            return Object.fromEntries(nextEntries);
        });
        // Auto-unpin if pinned participant left (but not if local is pinned)
        if (pinnedParticipantId && pinnedParticipantId !== clientId && !activeRemoteCids.has(pinnedParticipantId)) {
            setPinnedParticipantId(null);
        }
        // Clear remote content state if sharing participant left
        if (remoteContentState && !activeRemoteCids.has(remoteContentState.cid)) {
            setRemoteContentState(null);
        }
    }, [remoteStreamEntries, pinnedParticipantId, remoteContentState]);

    // Listen for content_state messages from remote participants
    useEffect(() => {
        return subscribeToMessages((msg: any) => {
            if (msg.type === 'content_state' && msg.payload?.from) {
                if (msg.payload.active && msg.payload.contentType) {
                    setRemoteContentState({ cid: msg.payload.from, contentType: msg.payload.contentType });
                } else {
                    // Only clear if the inactive message is from the current content owner
                    setRemoteContentState((prev) =>
                        prev && prev.cid === msg.payload.from ? null : prev
                    );
                }
            }
        });
    }, [subscribeToMessages]);

    useEffect(() => {
        if (!isMultiParty || !stageViewportRef.current) {
            return;
        }

        const node = stageViewportRef.current;
        const updateViewportSize = () => {
            const rect = node.getBoundingClientRect();
            setStageViewportSize({
                width: Math.max(0, Math.floor(rect.width)),
                height: Math.max(0, Math.floor(rect.height))
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

        if (remoteStreams.size > 0) {
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
    }, [hasJoined, remoteStreams.size, showReconnecting]);

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

    const showSaveRoomError = (result: SaveRoomResult) => {
        if (result === 'quota_exceeded') {
            showToast('error', t('toast_saved_rooms_storage_full') || 'Storage is full. Remove old rooms and try again.');
            return;
        }
        showToast('error', t('toast_saved_rooms_save_error') || 'Failed to save room.');
    };

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
                markRoomJoined(rid, Date.now());
                callStartTimeRef.current = null;
            }
            // Preserve reconnect identity on unload so a refreshed tab can reclaim
            // its room slot before the server's disconnect grace period expires.
            lr({ preserveReconnectState: true });
            slm();
            mediaStartedRef.current = false;
        };
    }, []); // Run only on mount/unmount
    // eslint-disable-line react-hooks/exhaustive-deps

    const callStartTimeRef = useRef<number | null>(null);

    const saveInvitedRoom = async (): Promise<boolean> => {
        if (!sharedName || !roomId) return false;
        const result = saveRoom({
            roomId,
            name: sharedName,
            createdAt: Date.now()
        });
        if (result !== 'ok') {
            showSaveRoomError(result);
            return false;
        }
        showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
        return true;
    };

    const handleJoin = async (shouldSave = false) => {
        if (!roomId) return;

        if (shouldSave) {
            await saveInvitedRoom();
        }

        if (!isConnected) return; // Allow save to happen even if not connected, but stop here for joining

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
            // Join immediately — push notification will fire asynchronously after join
            setTimeout(() => {
                joinRoom(roomId, isGroupCallRequested ? { createMaxParticipants: 4 } : undefined);
                setHasJoined(true);
                callStartTimeRef.current = Date.now();
            }, 50);
        } catch (err) {
            console.error("Failed to join room", err);
            showToast('error', t('toast_camera_error'));
        }
    };

    const handleSaveOnly = async () => {
        await saveInvitedRoom();
    };

    // If we receive a signaling error while trying to join, or if we are joined but room state becomes null
    useEffect(() => {
        if (signalingError && hasJoined && !roomState) {
            setHasJoined(false);
            stopLocalMedia();
        }
    }, [signalingError, hasJoined, roomState, stopLocalMedia]);

    // Post-join: asynchronously capture snapshot and trigger push notification
    const pushNotifySentRef = useRef(false);
    useEffect(() => {
        if (!hasJoined) {
            pushNotifySentRef.current = false;
            return;
        }
        if (!roomId || !clientId || !localStream) return;
        const isCurrentParticipant = roomState?.participants?.some((participant) => participant.cid === clientId) ?? false;
        if (!isCurrentParticipant) return;
        if (pushNotifySentRef.current) return;
        pushNotifySentRef.current = true;

        (async () => {
            try {
                const [snapshotId, pushEndpoint] = await Promise.all([
                    Promise.race([
                        buildEncryptedSnapshot(localStream, roomId).catch((err) => {
                            console.warn('[Push] Failed to build encrypted snapshot', err);
                            return null;
                        }),
                        new Promise<null>((resolve) => setTimeout(() => resolve(null), SNAPSHOT_PREPARE_TIMEOUT_MS))
                    ]),
                    (async (): Promise<string | undefined> => {
                        try {
                            if ('serviceWorker' in navigator && 'PushManager' in window) {
                                const reg = await navigator.serviceWorker.ready;
                                const sub = await reg.pushManager.getSubscription();
                                return sub?.endpoint;
                            }
                        } catch { /* ignore */ }
                        return undefined;
                    })()
                ]);

                await fetch(`/api/push/notify?roomId=${encodeURIComponent(roomId)}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        cid: clientId,
                        snapshotId: snapshotId || undefined,
                        pushEndpoint: pushEndpoint || undefined
                    })
                });
            } catch (err) {
                console.warn('[Push] Post-join push notify failed', err);
            }
        })();
    }, [hasJoined, roomId, clientId, localStream, roomState]);

    const handleLeave = () => {
        if (callStartTimeRef.current && roomId) {
            const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
            saveCall({
                roomId,
                startTime: callStartTimeRef.current,
                duration: duration > 0 ? duration : 0
            });

            // Also update lastJoinedAt if it's a saved room
            markRoomJoined(roomId, Date.now());

            callStartTimeRef.current = null;
        }
        leaveRoom();
        stopLocalMedia();
        exitFullscreenIfActive();
        navigate('/');
    };


    const scheduleIdleHide = () => {
        if (!isControlsAutoHideEnabledRef.current) {
            return;
        }
        if (idleTimeoutRef.current) {
            window.clearTimeout(idleTimeoutRef.current);
        }
        idleTimeoutRef.current = window.setTimeout(() => {
            wereControlsLastHiddenByAutoHideRef.current = true;
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
    };

    const handleControlsInteraction = () => {
        setAreControlsVisible(true);
        if (wereControlsLastHiddenByAutoHideRef.current) {
            isControlsAutoHideEnabledRef.current = false;
            wereControlsLastHiddenByAutoHideRef.current = false;
            clearIdleHide();
            return;
        }
        scheduleIdleHide();
    };

    useEffect(() => {
        if (!hasJoined) return;
        isControlsAutoHideEnabledRef.current = true;
        wereControlsLastHiddenByAutoHideRef.current = false;
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
        setRemoteVideoFit(prev => {
            const next = prev === 'cover' ? 'contain' : 'cover';
            persistRemoteVideoFit(next);
            return next;
        });
    };

    // Render Pre-Join
    if (!hasJoined) {
        return (
            <div className="page-container center-content">
                <div className="card prejoin-card">
                    {sharedName ? (
                        <div className="prejoin-invite-title">
                            <span className="prejoin-invite-label">
                                {t('saved_rooms_invited_prefix') || 'Invited to'}
                            </span>
                            <h2 className="prejoin-invite-room">{sharedName}</h2>
                        </div>
                    ) : (
                        <h2>{t('ready_to_join')}</h2>
                    )}
                    <p style={{ display: 'none' }}>{t('room_id')} {roomId}</p>

                    {signalingError && (
                        <div className="error-message">
                            <AlertCircle size={20} />
                            {signalingError}
                        </div>
                    )}
                    <div className="video-preview-container">
                        <video
                            ref={setLocalVideoRef}
                            autoPlay
                            playsInline
                            muted
                            className={`video-preview ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                        />
                        {!localStream && <div className="video-placeholder">{t('camera_off')}</div>}
                    </div>

                    {sharedName ? (
                        <>
                            <div className="prejoin-invite-actions">
                                <button className="btn-primary" disabled={!isConnected} onClick={() => { void handleJoin(true); }}>
                                    {isConnected ? (t('saved_rooms_save_and_join') || 'Save & Join') : (t('connecting') || 'Connecting...')}
                                </button>
                                <button className="btn-secondary" onClick={() => { void handleSaveOnly(); }}>
                                    {t('saved_rooms_save_only') || 'Save Only'}
                                </button>
                            </div>
                            <div className="button-group prejoin-invite-home">
                                <button className="btn-secondary" onClick={handleLeave}>
                                    {t('home')}
                                </button>
                            </div>
                        </>
                    ) : (
                        <div className="button-group">
                            <button className="btn-primary" onClick={() => handleJoin()}>
                                {isConnected ? t('join_call') : t('connecting')}
                            </button>
                            <button className="btn-secondary" onClick={copyLink}>
                                <Copy size={16} /> {t('copy_link')}
                            </button>
                            <button className="btn-secondary" onClick={handleLeave}>
                                {t('home')}
                            </button>
                        </div>
                    )}
                </div>
            </div>
        );
    }

    // Render In-Call
    const otherParticipants = roomState?.participants?.filter(p => p.cid !== clientId) ?? [];
    const otherParticipant = otherParticipants.length > 0 ? otherParticipants[0] : undefined;
    const participantCount = roomState?.participants.length ?? 1;
    const shareUrl = typeof window !== 'undefined' ? window.location.href : '';
    const debugSections = buildDebugPanelSections({
        isConnected,
        activeTransport,
        iceConnectionState,
        connectionState,
        signalingState,
        roomParticipantCount: roomState ? roomState.participants.length : null,
        showReconnecting: connectionStatus !== 'connected',
        realtimeStats
    });
    const callProbe = (
        <div
            data-testid="call-participant-count"
            data-count={participantCount}
            data-phase={connectionStatus}
            aria-hidden="true"
            style={{
                position: 'absolute',
                width: 1,
                height: 1,
                overflow: 'hidden',
                opacity: 0,
                pointerEvents: 'none'
            }}
        >
            {participantCount}
        </div>
    );


    // Shared controls bar (used in both 1:1 and multi-party layouts)
    const controlsBar = (
        <div
            className="controls-bar"
            onPointerUp={event => {
                event.stopPropagation();
                handleControlsInteraction();
            }}
        >
            <button onClick={toggleMute} className={`btn-control ${isMuted ? 'active' : ''}`}>
                {isMuted ? <MicOff /> : <Mic />}
            </button>
            <button onClick={toggleVideo} className={`btn-control ${isCameraOff ? 'active' : ''}`}>
                {isCameraOff ? <VideoOff /> : <Video />}
            </button>
            {hasMultipleCameras && (
                <button onClick={flipCamera} className="btn-control" disabled={isScreenSharing}>
                    <RotateCcw />
                </button>
            )}
            {showScreenShareControl && (
                <button
                    onClick={() => {
                        if (isScreenSharing) {
                            void stopScreenShare();
                        } else {
                            void startScreenShare();
                        }
                    }}
                    className={`btn-control ${isScreenSharing ? 'active-screen-share' : ''}`}
                    title={isScreenSharing ? t('screen_share_stop') : t('screen_share_start')}
                    aria-label={isScreenSharing ? t('screen_share_stop') : t('screen_share_start')}
                >
                    {isScreenSharing ? <ScreenShareOff /> : <ScreenShare />}
                </button>
            )}
            <button onClick={handleLeave} className="btn-control btn-leave">
                <PhoneOff />
            </button>
        </div>
    );

    // Shared debug/reconnecting overlay
    const overlayContent = (
        <>
            <div
                className="debug-toggle-zone"
                onPointerDown={handleDebugCornerTap}
                onPointerUp={handleDebugCornerPointerUp}
                onPointerCancel={handleDebugCornerPointerUp}
            />
            {showDebug && (
                <div className="debug-panel">
                    <div className="debug-panel-grid">
                        {debugSections.map(section => (
                            <section className="debug-section" key={section.title}>
                                <div className="debug-section-title">{section.title}</div>
                                {section.metrics.map(metric => (
                                    <div className="debug-metric-row" key={`${section.title}:${metric.label}`}>
                                        <div className="debug-metric-label">
                                            <span className={`debug-dot debug-dot-${metric.status}`} />
                                            {metric.label && <span>{metric.label}</span>}
                                        </div>
                                        <span className="debug-metric-value">{metric.value}</span>
                                    </div>
                                ))}
                            </section>
                        ))}
                    </div>
                </div>
            )}
            {showReconnecting && (
                <div className="reconnect-overlay" aria-live="polite">
                    <div className={`reconnect-badge ${connectionStatus === 'retrying' ? 'reconnect-badge-retrying' : ''}`}>
                        <span>{t('reconnecting', { defaultValue: 'Reconnecting...' })}</span>
                        {connectionStatus === 'retrying' && (
                            <span className="reconnect-badge-subtext">
                                {t('reconnecting_taking_longer', { defaultValue: 'Taking longer than usual...' })}
                            </span>
                        )}
                    </div>
                </div>
            )}
        </>
    );

    // Multi-party stage layout (3+ participants)
    if (isMultiParty) {
        return (
            <div
                className={`call-container multi-party-call ${areControlsVisible ? '' : 'controls-hidden'}`}
                onPointerUp={handleScreenTap}
            >
                {callProbe}
                {overlayContent}
                <div className="video-stage">
                    <div className="video-stage-viewport" ref={setStageViewportNode}>
                        {computedLayout ? (
                            // Focus/content mode: absolute positioning from computeLayout
                            <div style={{ position: 'relative', width: '100%', height: '100%' }}>
                                {computedLayout.tiles.map((tile) => {
                                    const isContentTile = tile.type === 'contentSource';
                                    const isLocal = tile.id === clientId;
                                    const contentOwnerCid = contentSource?.ownerParticipantId;
                                    const isLocalContent = isContentTile && contentOwnerCid === clientId;
                                    const isRemoteContent = isContentTile && contentOwnerCid !== clientId;
                                    // Local participant tile when LOCAL user owns content: camera replaced, show placeholder
                                    const isLocalPlaceholder = isLocal && contentOwnerCid === clientId && !isContentTile;
                                    // Resolve the stream for this tile
                                    const stream = isLocalContent || isLocal
                                        ? localStream
                                        : isRemoteContent
                                            ? remoteStageTileMap.get(contentOwnerCid!)?.stream
                                            : remoteStageTileMap.get(tile.id)?.stream;
                                    if (!stream && !isLocalPlaceholder) return null;

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

                                    const isPrimaryTile = tile.zOrder === 0;
                                    return (
                                        <div key={tile.id} className="video-stage-tile" style={tileStyle}>
                                            <VideoTile
                                                stream={stream!}
                                                tileStyle={{ width: '100%', height: '100%', borderRadius: 'inherit' }}
                                                videoFit={tile.fit}
                                                onAspectRatioChange={
                                                    isLocal || isContentTile ? undefined : (ratio) => updateRemoteStageAspectRatio(tile.id, ratio)
                                                }
                                                onClick={() => {
                                                    if (!isContentTile) {
                                                        setPinnedParticipantId(
                                                            tile.id === pinnedParticipantId ? null : tile.id
                                                        );
                                                    }
                                                }}
                                                pinned={tile.id === pinnedParticipantId}
                                            />
                                            {isPrimaryTile && (
                                                <button
                                                    className="btn-zoom"
                                                    onPointerUp={toggleRemoteVideoFit}
                                                    title={remoteVideoFit === 'cover' ? t('zoom_fit') : t('zoom_fill')}
                                                >
                                                    {remoteVideoFit === 'cover' ? <Minimize2 /> : <Maximize2 />}
                                                </button>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        ) : (
                            // Grid mode: existing row-based rendering
                            <div className="video-stage-rows">
                                {remoteStageLayout.map((row, rowIndex) => (
                                    <div className="video-stage-row" key={`row-${rowIndex}`}>
                                        {row.items.map((tile) => {
                                            const stageTile = remoteStageTileMap.get(tile.cid);
                                            if (!stageTile) {
                                                return null;
                                            }
                                            return (
                                                <VideoTile
                                                    key={tile.cid}
                                                    stream={stageTile.stream}
                                                    tileStyle={{
                                                        width: `${tile.width}px`,
                                                        height: `${tile.height}px`
                                                    }}
                                                    onAspectRatioChange={(ratio) => updateRemoteStageAspectRatio(tile.cid, ratio)}
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
                {/* Hide local PIP when in focus mode (local is in the filmstrip) */}
                {!computedLayout && (
                    <div
                        className="video-local-container pip video-local-container-stage"
                        onPointerUp={(event) => {
                            event.stopPropagation();
                            handleControlsInteraction();
                        }}
                    >
                        <video
                            ref={setLocalVideoRef}
                            autoPlay
                            playsInline
                            muted
                            className={`video-local ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                            style={{ objectFit: isScreenSharing ? 'contain' : 'cover' }}
                        />
                    </div>
                )}
                {controlsBar}
            </div>
        );
    }

    // --- 1:1 layout (existing) ---
    return (
        <div
            className={`call-container ${areControlsVisible ? '' : 'controls-hidden'} ${isLocalLarge ? 'local-large' : ''}`}
            onPointerUp={handleScreenTap}
        >
            {callProbe}
            {overlayContent}
            {/* Primary Video (Full Screen) */}
            <div
                className={`video-remote-container ${isLocalLarge ? 'pip' : 'primary'}`}
                onPointerUp={isLocalLarge ? (e) => {
                    e.stopPropagation();
                    setIsLocalLarge(false);
                } : undefined}
            >
                <video
                    ref={setRemoteVideoRef}
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
                                <button
                                    className={`btn-small ${isInviting ? 'active' : ''}`}
                                    onClick={handleInvite}
                                    onPointerUp={event => {
                                        event.stopPropagation();
                                        handleControlsInteraction();
                                    }}
                                    disabled={isInviting}
                                    style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
                                >
                                    <BellRing size={16} />
                                    {t('invite_to_call')}
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
                    ref={setLocalVideoRef}
                    autoPlay
                    playsInline
                    muted
                    className={`video-local ${shouldMirrorLocalVideo ? 'mirrored' : ''}`}
                    style={{ objectFit: isScreenSharing ? 'contain' : 'cover' }}
                />
            </div>

            {controlsBar}
        </div >
    );
};

export default CallRoom;
