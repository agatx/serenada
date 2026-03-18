import React, { useCallback, useEffect, useRef, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useToast } from '../contexts/ToastContext';
import { useTranslation } from 'react-i18next';
import { SerenadaCallFlow } from '@serenada/react-ui';
import type { SerenadaString } from '@serenada/react-ui';
import { SerenadaCore } from '@serenada/core';
import type { SerenadaSession, CallState } from '@serenada/core';
import { saveCall } from '../utils/callHistory';
import { saveRoom, markRoomJoined } from '../utils/savedRooms';
import { getOrCreatePushKeyPair } from '../utils/pushCrypto';
import { SNAPSHOT_PREPARE_TIMEOUT_MS } from '../constants/webrtcResilience';

// ---------------------------------------------------------------------------
// Push notification helpers (host-app concerns)
// ---------------------------------------------------------------------------

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
        // Ignore autoplay restrictions
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

// ---------------------------------------------------------------------------
// Build locale strings for SerenadaCallFlow from i18next
// ---------------------------------------------------------------------------

function buildSerenadaCallStrings(t: (key: string, opts?: Record<string, string>) => string): Partial<Record<SerenadaString, string>> {
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

// ---------------------------------------------------------------------------
// CallRoom — host app shell that delegates call presentation to SerenadaCallFlow
// ---------------------------------------------------------------------------

const CallRoom: React.FC = () => {
    const { t } = useTranslation();
    const { roomId } = useParams<{ roomId: string }>();
    const navigate = useNavigate();
    const { showToast } = useToast();

    // Parse URL parameters for room name sharing
    const urlParams = new URLSearchParams(window.location.search);
    const sharedName = urlParams.get('name');

    // State
    const [showSavePrompt, setShowSavePrompt] = useState(!!sharedName);
    const sessionRef = useRef<SerenadaSession | null>(null);
    const callStartTimeRef = useRef<number | null>(null);
    const pushNotifySentRef = useRef(false);

    // Create SerenadaCore once
    const core = useMemo(() => new SerenadaCore({ serverHost: window.location.host }), []);

    // Create session when ready
    const [session, setSession] = useState<SerenadaSession | null>(null);

    useEffect(() => {
        if (!roomId || showSavePrompt) return;

        const callUrl = `${window.location.origin}/call/${roomId}`;
        const sess = core.join(callUrl);
        sessionRef.current = sess;
        callStartTimeRef.current = Date.now();
        // eslint-disable-next-line react-hooks/set-state-in-effect -- initializing resource
        setSession(sess);

        return () => {
            sess.destroy();
            sessionRef.current = null;
            setSession(null);
        };
    }, [roomId, showSavePrompt, core]);

    // Host-app effect: push subscription on mount
    useEffect(() => {
        if (!roomId) return;
        if ('serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window) {
            navigator.serviceWorker.ready.then(reg => {
                reg.pushManager.getSubscription().then(sub => {
                    if (sub) {
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
    }, [roomId]);

    // Host-app effect: push notification after joining (snapshot + notify)
    useEffect(() => {
        if (!session || !roomId) return;
        pushNotifySentRef.current = false;

        const unsub = session.subscribe((state: CallState) => {
            if ((state.phase === 'waiting' || state.phase === 'inCall') && !pushNotifySentRef.current) {
                pushNotifySentRef.current = true;
                const localStream = session.localStream;

                void (async () => {
                    try {
                        const [snapshotId, pushEndpoint] = await Promise.all([
                            localStream
                                ? Promise.race([
                                    buildEncryptedSnapshot(localStream, roomId).catch(() => null),
                                    new Promise<null>((resolve) => setTimeout(() => resolve(null), SNAPSHOT_PREPARE_TIMEOUT_MS))
                                ])
                                : Promise.resolve(null),
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
                                cid: state.localParticipant?.cid,
                                snapshotId: snapshotId || undefined,
                                pushEndpoint: pushEndpoint || undefined
                            })
                        });
                    } catch (err) {
                        console.warn('[Push] Post-join push notify failed', err);
                    }
                })();
            }
        });

        return unsub;
    }, [session, roomId]);

    // Host-app effect: save call history on unmount
    useEffect(() => {
        return () => {
            if (callStartTimeRef.current && roomId) {
                const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
                saveCall({
                    roomId,
                    startTime: callStartTimeRef.current,
                    duration: duration > 0 ? duration : 0
                });
                markRoomJoined(roomId, Date.now());
                callStartTimeRef.current = null;
            }
        };
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Locale strings for SerenadaCallFlow
    const strings = useMemo(() => buildSerenadaCallStrings(t), [t]);

    // Dismiss handler
    const handleDismiss = useCallback(() => {
        if (callStartTimeRef.current && roomId) {
            const duration = Math.floor((Date.now() - callStartTimeRef.current) / 1000);
            saveCall({
                roomId,
                startTime: callStartTimeRef.current,
                duration: duration > 0 ? duration : 0
            });
            markRoomJoined(roomId, Date.now());
            callStartTimeRef.current = null;
        }
        navigate('/');
    }, [roomId, navigate]);

    // Redirect if no room ID
    if (!roomId) {
        navigate('/');
        return null;
    }

    // Pre-save screen for invited rooms with shared name
    if (showSavePrompt && sharedName) {
        return (
            <div className="page-container center-content">
                <div className="card prejoin-card">
                    <div className="prejoin-invite-title">
                        <span className="prejoin-invite-label">
                            {t('saved_rooms_invited_prefix') || 'Invited to'}
                        </span>
                        <h2 className="prejoin-invite-room">{sharedName}</h2>
                    </div>
                    <div className="prejoin-invite-actions">
                        <button
                            className="btn-primary"
                            onClick={() => {
                                const result = saveRoom({
                                    roomId,
                                    name: sharedName,
                                    createdAt: Date.now()
                                });
                                if (result === 'ok') {
                                    showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
                                }
                                setShowSavePrompt(false);
                            }}
                        >
                            {t('saved_rooms_save_and_join') || 'Save & Join'}
                        </button>
                        <button
                            className="btn-secondary"
                            onClick={() => {
                                const result = saveRoom({
                                    roomId,
                                    name: sharedName,
                                    createdAt: Date.now()
                                });
                                if (result === 'ok') {
                                    showToast('success', t('saved_rooms_save_success') || 'Room saved successfully');
                                }
                                navigate('/');
                            }}
                        >
                            {t('saved_rooms_save_only') || 'Save Only'}
                        </button>
                    </div>
                    <div className="button-group prejoin-invite-home">
                        <button className="btn-secondary" onClick={() => navigate('/')}>
                            {t('home')}
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // Main call presentation via SerenadaCallFlow
    return (
        <div style={{ width: '100vw', height: '100vh', position: 'relative' }}>
            <SerenadaCallFlow
                session={session ?? undefined}
                config={{
                    screenSharingEnabled: true,
                    inviteControlsEnabled: true,
                    debugOverlayEnabled: true,
                }}
                strings={strings}
                onDismiss={handleDismiss}
            />
        </div>
    );
};

export default CallRoom;
