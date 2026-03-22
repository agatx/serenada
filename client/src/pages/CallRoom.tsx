import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, CheckSquare, Copy, Square } from 'lucide-react';
import { SerenadaCallFlow } from '@serenada/react-ui';
import type { SerenadaString } from '@serenada/react-ui';
import { SerenadaCore, ConsoleSerenadaLogger, SNAPSHOT_PREPARE_TIMEOUT_MS } from '@serenada/core';
import type { CallState, SerenadaSessionHandle } from '@serenada/core';
import { useToast } from '../contexts/ToastContext';
import { saveCall } from '../utils/callHistory';
import { getOrCreatePushKeyPair } from '../utils/pushCrypto';
import { markRoomJoined, saveRoom } from '../utils/savedRooms';
import { getConfiguredServerHost } from '../utils/serverHost';

function urlBase64ToUint8Array(base64String: string): Uint8Array {
    const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
    const base64 = (base64String + padding)
        .replace(/-/g, '+')
        .replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; i += 1) {
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
    return data.filter((item: { id?: number; publicKey?: JsonWebKey }) => typeof item?.id === 'number' && item?.publicKey);
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
        // Ignore autoplay restrictions.
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

async function buildEncryptedSnapshot(stream: MediaStream, roomId: string): Promise<string | null> {
    if (!('crypto' in window) || !window.crypto.subtle) return null;

    const recipients = await fetchRecipients(roomId);
    if (recipients.length === 0) return null;

    const snapshot = await captureSnapshotBytes(stream);
    if (!snapshot || snapshot.bytes.length > 200 * 1024) return null;

    const snapshotKey = await crypto.subtle.generateKey(
        { name: 'AES-GCM', length: 256 },
        true,
        ['encrypt', 'decrypt'],
    );
    const snapshotIv = crypto.getRandomValues(new Uint8Array(12));
    const snapshotBuffer = snapshot.bytes.buffer.slice(
        snapshot.bytes.byteOffset,
        snapshot.bytes.byteOffset + snapshot.bytes.byteLength,
    ) as ArrayBuffer;
    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: snapshotIv },
        snapshotKey,
        snapshotBuffer,
    );
    const snapshotKeyRaw = new Uint8Array(await crypto.subtle.exportKey('raw', snapshotKey));

    const ephemeral = await crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits'],
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
                [],
            );
            const sharedBits = await crypto.subtle.deriveBits(
                { name: 'ECDH', public: recipientKey },
                ephemeral.privateKey,
                256,
            );
            const hkdfKey = await crypto.subtle.importKey('raw', sharedBits, 'HKDF', false, ['deriveKey']);
            const wrapKey = await crypto.subtle.deriveKey(
                { name: 'HKDF', hash: 'SHA-256', salt, info },
                hkdfKey,
                { name: 'AES-GCM', length: 256 },
                false,
                ['encrypt', 'decrypt'],
            );
            const wrapIv = crypto.getRandomValues(new Uint8Array(12));
            const wrappedKey = await crypto.subtle.encrypt(
                { name: 'AES-GCM', iv: wrapIv },
                wrapKey,
                snapshotKeyRaw,
            );
            recipientsPayload.push({
                id: recipient.id,
                wrappedKey: base64FromBytes(new Uint8Array(wrappedKey)),
                wrappedKeyIv: base64FromBytes(wrapIv),
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
            recipients: recipientsPayload,
        }),
    });

    if (!res.ok) return null;
    const data = await res.json();
    return typeof data.id === 'string' ? data.id : null;
}

function buildSerenadaCallStrings(
    t: (key: string, opts?: Record<string, string>) => string,
): Partial<Record<SerenadaString, string>> {
    return {
        joiningCall: t('connecting'),
        waitingForOther: t('waiting_message'),
        shareLink: t('copy_link_share'),
        copied: t('toast_link_copied'),
        startScreenShare: t('screen_share_start'),
        stopScreenShare: t('screen_share_stop'),
        reconnecting: t('reconnecting'),
        cancel: t('cancel'),
    };
}

const CallRoom: React.FC = () => {
    const { t } = useTranslation();
    const { roomId } = useParams<{ roomId: string }>();
    const navigate = useNavigate();
    const { showToast } = useToast();

    const urlParams = new URLSearchParams(window.location.search);
    const sharedName = urlParams.get('name');

    const [shouldJoin, setShouldJoin] = useState(false);
    const [session, setSession] = useState<SerenadaSessionHandle | null>(null);
    const [previewStream, setPreviewStream] = useState<MediaStream | null>(null);
    const [isSubscribed, setIsSubscribed] = useState(false);
    const [pushSupported, setPushSupported] = useState(false);
    const [vapidKey, setVapidKey] = useState<string | null>(null);
    const [isInviting, setIsInviting] = useState(false);

    const previewVideoRef = useRef<HTMLVideoElement | null>(null);
    const callStartTimeRef = useRef<number | null>(null);
    const pushNotifySentRef = useRef(false);

    const core = useMemo(() => new SerenadaCore({ serverHost: getConfiguredServerHost(), logger: new ConsoleSerenadaLogger() }), []);
    const strings = useMemo(() => buildSerenadaCallStrings(t), [t]);

    const stopPreview = useCallback(() => {
        setPreviewStream((current) => {
            current?.getTracks().forEach((track) => track.stop());
            return null;
        });
    }, []);

    useEffect(() => {
        if (!previewVideoRef.current || !previewStream) return;
        if (previewVideoRef.current.srcObject !== previewStream) {
            previewVideoRef.current.srcObject = previewStream;
        }
    }, [previewStream]);

    useEffect(() => {
        if (!roomId || shouldJoin) {
            stopPreview();
            return;
        }

        let cancelled = false;
        let activeStream: MediaStream | null = null;

        void (async () => {
            try {
                const stream = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: 'user' },
                    audio: true,
                });
                if (cancelled) {
                    stream.getTracks().forEach((track) => track.stop());
                    return;
                }
                activeStream = stream;
                setPreviewStream(stream);
            } catch (err) {
                console.warn('[CallRoom] Failed to start preview stream', err);
            }
        })();

        return () => {
            cancelled = true;
            activeStream?.getTracks().forEach((track) => track.stop());
        };
    }, [roomId, shouldJoin, stopPreview]);

    useEffect(() => {
        if (!roomId || !shouldJoin) return;

        const callUrl = `${window.location.origin}/call/${roomId}`;
        const nextSession = core.join(callUrl);
        callStartTimeRef.current = Date.now();
        setSession(nextSession);

        return () => {
            nextSession.destroy();
            setSession(null);
        };
    }, [core, roomId, shouldJoin]);

    useEffect(() => {
        if (!roomId) return;
        if ('serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window) {
            setPushSupported(true);

            void fetch('/api/push/vapid-public-key')
                .then((res) => res.json())
                .then((data: { publicKey?: string }) => {
                    if (typeof data.publicKey === 'string') {
                        setVapidKey(data.publicKey);
                    }
                })
                .catch((err) => console.error('[Push] Failed to load VAPID key', err));

            void navigator.serviceWorker.ready.then((reg) => {
                void reg.pushManager.getSubscription().then((sub) => {
                    if (!sub) return;
                    setIsSubscribed(true);
                    void getOrCreatePushKeyPair()
                        .then(({ publicJwk }) => fetch(`/api/push/subscribe?roomId=${roomId}`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ ...sub.toJSON(), locale: navigator.language, encPublicKey: publicJwk }),
                        }))
                        .catch(() => {});
                });
            });
        }
    }, [roomId]);

    useEffect(() => {
        if (!session || !roomId) return;
        pushNotifySentRef.current = false;

        const unsubscribe = session.subscribe((state: CallState) => {
            if ((state.phase === 'waiting' || state.phase === 'inCall') && !pushNotifySentRef.current) {
                pushNotifySentRef.current = true;
                const localStream = session.localStream;

                void (async () => {
                    try {
                        const [snapshotId, pushEndpoint] = await Promise.all([
                            localStream
                                ? Promise.race([
                                    buildEncryptedSnapshot(localStream, roomId).catch(() => null),
                                    new Promise<null>((resolve) => setTimeout(() => resolve(null), SNAPSHOT_PREPARE_TIMEOUT_MS)),
                                ])
                                : Promise.resolve(null),
                            (async (): Promise<string | undefined> => {
                                try {
                                    if ('serviceWorker' in navigator && 'PushManager' in window) {
                                        const reg = await navigator.serviceWorker.ready;
                                        const sub = await reg.pushManager.getSubscription();
                                        return sub?.endpoint;
                                    }
                                } catch {
                                    // Ignore push lookup failures.
                                }
                                return undefined;
                            })(),
                        ]);

                        await fetch(`/api/push/notify?roomId=${encodeURIComponent(roomId)}`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                cid: state.localParticipant?.cid,
                                snapshotId: snapshotId || undefined,
                                pushEndpoint: pushEndpoint || undefined,
                            }),
                        });
                    } catch (err) {
                        console.warn('[Push] Post-join push notify failed', err);
                    }
                })();
            }
        });

        return unsubscribe;
    }, [roomId, session]);

    useEffect(() => {
        return () => {
            stopPreview();
            if (callStartTimeRef.current && roomId) {
                const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
                saveCall({
                    roomId,
                    startTime: callStartTimeRef.current,
                    duration: duration > 0 ? duration : 0,
                });
                markRoomJoined(roomId, Date.now());
                callStartTimeRef.current = null;
            }
        };
    }, [roomId, stopPreview]);

    const saveInvitedRoom = useCallback((): boolean => {
        if (!sharedName || !roomId) return false;
        const result = saveRoom({
            roomId,
            name: sharedName,
            createdAt: Date.now(),
        });
        if (result === 'ok') {
            showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
            return true;
        }
        showToast('error', t('toast_saved_rooms_save_error') || 'Failed to save room.');
        return false;
    }, [roomId, sharedName, showToast, t]);

    const handleJoin = useCallback((saveBeforeJoin = false) => {
        if (!roomId) return;
        if (saveBeforeJoin && !saveInvitedRoom()) return;
        stopPreview();
        setShouldJoin(true);
    }, [roomId, saveInvitedRoom, stopPreview]);

    const handleSaveOnly = useCallback(() => {
        if (!saveInvitedRoom()) return;
        navigate('/');
    }, [navigate, saveInvitedRoom]);

    const handleCopyLink = useCallback(() => {
        void navigator.clipboard.writeText(window.location.href).then(() => {
            showToast('success', t('toast_link_copied'));
        });
    }, [showToast, t]);

    const handleDismiss = useCallback(() => {
        if (callStartTimeRef.current && roomId) {
            const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
            saveCall({
                roomId,
                startTime: callStartTimeRef.current,
                duration: duration > 0 ? duration : 0,
            });
            markRoomJoined(roomId, Date.now());
            callStartTimeRef.current = null;
        }
        navigate('/');
    }, [navigate, roomId]);

    const handleInvite = useCallback(async (event: React.MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();
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
                body: JSON.stringify(endpoint ? { endpoint } : {}),
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
    }, [isInviting, roomId, showToast, t]);

    const handlePushToggle = useCallback(async (event: React.MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        if (!roomId || !vapidKey) return;

        try {
            const reg = await navigator.serviceWorker.ready;
            if (isSubscribed) {
                const sub = await reg.pushManager.getSubscription();
                if (sub) {
                    await sub.unsubscribe();
                    await fetch(`/api/push/subscribe?roomId=${roomId}`, {
                        method: 'DELETE',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ endpoint: sub.endpoint }),
                    });
                    setIsSubscribed(false);
                    showToast('success', 'Unsubscribed');
                }
                return;
            }

            const permission = await Notification.requestPermission();
            if (permission !== 'granted') {
                showToast('error', 'Notifications blocked');
                return;
            }

            const { publicJwk } = await getOrCreatePushKeyPair();
            const sub = await reg.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: urlBase64ToUint8Array(vapidKey) as BufferSource,
            });

            await fetch(`/api/push/subscribe?roomId=${roomId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ...sub.toJSON(), locale: navigator.language, encPublicKey: publicJwk }),
            });
            setIsSubscribed(true);
            showToast('success', 'You will be notified!');
        } catch (err) {
            console.error('[Push] Failed to update subscription', err);
            showToast('error', 'Failed to update subscription');
        }
    }, [isSubscribed, roomId, showToast, vapidKey]);

    if (!roomId) {
        navigate('/');
        return null;
    }

    if (!shouldJoin || !session) {
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

                    <div className="video-preview-container">
                        <video
                            ref={previewVideoRef}
                            autoPlay
                            playsInline
                            muted
                            className="video-preview mirrored"
                        />
                        {!previewStream && <div className="video-placeholder">{t('camera_off')}</div>}
                    </div>

                    {sharedName ? (
                        <>
                            <div className="prejoin-invite-actions">
                                <button className="btn-primary" onClick={() => handleJoin(true)}>
                                    {t('saved_rooms_save_and_join') || 'Save & Join'}
                                </button>
                                <button className="btn-secondary" onClick={handleSaveOnly}>
                                    {t('saved_rooms_save_only') || 'Save Only'}
                                </button>
                            </div>
                            <div className="button-group prejoin-invite-home">
                                <button className="btn-secondary" onClick={() => navigate('/')}>
                                    {t('home')}
                                </button>
                            </div>
                        </>
                    ) : (
                        <div className="button-group">
                            <button className="btn-primary" onClick={() => handleJoin(false)}>
                                {t('join_call')}
                            </button>
                            <button className="btn-secondary" onClick={handleCopyLink}>
                                <Copy size={16} /> {t('copy_link')}
                            </button>
                            <button className="btn-secondary" onClick={() => navigate('/')}>
                                {t('home')}
                            </button>
                        </div>
                    )}
                </div>
            </div>
        );
    }

    const waitingActions = (
        <>
            <button
                type="button"
                className={`btn-small ${isInviting ? 'active' : ''}`}
                onClick={handleInvite}
                disabled={isInviting}
            >
                <BellRing size={16} />
                {t('invite_to_call')}
            </button>

            {pushSupported && (
                <button
                    type="button"
                    className={`btn-small ${isSubscribed ? 'active' : ''}`}
                    onClick={handlePushToggle}
                >
                    {isSubscribed ? <CheckSquare size={16} /> : <Square size={16} />}
                    {isSubscribed ? t('notify_me_on') : t('notify_me')}
                </button>
            )}
        </>
    );

    return (
        <SerenadaCallFlow
            session={session}
            config={{
                screenSharingEnabled: true,
                inviteControlsEnabled: true,
                debugOverlayEnabled: true,
            }}
            strings={strings}
            waitingActions={waitingActions}
            onDismiss={handleDismiss}
        />
    );
};

export default CallRoom;
