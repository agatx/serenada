import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, CheckSquare, Copy, Square } from 'lucide-react';
import { SerenadaCallFlow, useSerenadaCallRegistry } from '@agatx/serenada-react-ui';
import type { SerenadaString } from '@agatx/serenada-react-ui';
import { ConsoleSerenadaLogger, SNAPSHOT_PREPARE_TIMEOUT_MS, SnapshotError } from '@agatx/serenada-core';
import type { CallState, SerenadaConfig, SnapshotResult } from '@agatx/serenada-core';
import { useToast } from '../contexts/ToastContext';
import { saveCall } from '../utils/callHistory';
import { getOrCreatePushKeyPair } from '../utils/pushCrypto';
import { markRoomJoined, saveRoom } from '../utils/savedRooms';
import { getConfiguredServerHost } from '../utils/serverHost';
import { parseTurnsOnly } from '../utils/turnsOnly';
import { getDisplayName, setDisplayName } from '../utils/displayName';
import { selectActiveCallTerminalError, selectCallView } from '../utils/callView';

const BUNDLED_APP_INDEPENDENT_CONTENT_VIDEO_ENABLED = true;

// One logger instance for the registry config so the memoized config stays
// referentially stable across renders (the registry reconstructs on config-key
// changes only).
const serenadaLogger = new ConsoleSerenadaLogger();

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
    const location = useLocation();
    const navigate = useNavigate();
    const { showToast } = useToast();

    const urlParams = new URLSearchParams(location.search);
    const sharedName = urlParams.get('name');
    const turnsOnly = useMemo(() => parseTurnsOnly(location.search), [location.search]);

    const [shouldJoin, setShouldJoin] = useState(false);
    // A message shown on the prejoin card when a call could not be (or stay) in
    // the foreground, so the user knows why they landed back on prejoin and can
    // retry. Two distinct sources funnel here:
    //   - P5-6: a failed JOIN (`joinAndSwitch` result `failed`); the failed
    //     managed call is dismissed from the registry.
    //   - P5-8: an ALREADY-ACTIVE call whose session reaches terminal `error`;
    //     the registry releases the lease and removes the call, so without this
    //     the prejoin would render with no error (silent idle, regressing the
    //     pre-Phase-5 single-call UX that showed the session's error state).
    const [joinError, setJoinError] = useState<string | null>(null);
    const [previewStream, setPreviewStream] = useState<MediaStream | null>(null);
    const [isSubscribed, setIsSubscribed] = useState(false);
    const [pushSupported, setPushSupported] = useState(false);
    const [vapidKey, setVapidKey] = useState<string | null>(null);
    const [isInviting, setIsInviting] = useState(false);
    const [displayNameInput, setDisplayNameInput] = useState(getDisplayName);

    const previewVideoRef = useRef<HTMLVideoElement | null>(null);
    const callStartTimeRef = useRef<number | null>(null);
    const pushNotifySentRef = useRef(false);
    const displayNameRef = useRef(displayNameInput);
    displayNameRef.current = displayNameInput;

    const strings = useMemo(() => buildSerenadaCallStrings(t), [t]);

    // Multi-call registry: a single call is a registry with one foreground call.
    // The active call (`registry.activeCall?.session`) is rendered by the
    // existing single-session SerenadaCallFlow, so the single-call UX is
    // identical. The registry owns the foreground media lease and teardown.
    const registryConfig = useMemo<SerenadaConfig>(() => ({
        serverHost: getConfiguredServerHost(),
        logger: serenadaLogger,
        turnsOnly,
        // Bundled web app opt-in: the headless SDK default remains false for
        // external integrators, but this app intentionally ships the
        // independent screen-share media path.
        enableIndependentContentVideo: BUNDLED_APP_INDEPENDENT_CONTENT_VIDEO_ENABLED,
    }), [turnsOnly]);

    const {
        registry,
        calls: registryCalls,
        activeCall,
        activeCallId,
        heldCalls,
        registryOperationInProgress,
        joinAndSwitch,
        switchTo,
        leave: leaveCall,
    } = useSerenadaCallRegistry({ config: registryConfig });

    // Active-call-only rendering (contract §5/§7/§11; design "Remote Playback" /
    // "React UI"): the audible `SerenadaCallFlow` mounts the FOREGROUND call's
    // session and nothing else. A held / no-lease session is NEVER mounted as the
    // active flow — only the foreground call owns audible media elements. When no
    // call is foregrounded we render a placeholder (joining / on-hold / idle)
    // chosen by `selectCallView`, not a held session.
    const activeSession = activeCall?.session ?? null;
    const callView = useMemo(
        () => selectCallView({
            hasActiveSession: activeSession !== null,
            calls: registryCalls,
            registryOperationInProgress,
        }),
        [activeSession, registryCalls, registryOperationInProgress],
    );
    // Whether any held call would survive the active call's termination. When
    // true, the held surface wins and the active call's terminal error stays
    // transient (round-2 behavior); only a lone active error surfaces a message.
    const hasHeldCalls = heldCalls.length > 0;

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

        const callUrl = `${window.location.origin}${location.pathname}${location.search}${location.hash}`;
        callStartTimeRef.current = Date.now();

        let cancelled = false;
        let joinedCallId: string | null = null;
        // Join held then foreground it: for a single call this is a registry with
        // one foreground call, so `activeCall?.session` renders exactly as before.
        // joinAndSwitch holds any prior active call first.
        void joinAndSwitch({
            url: callUrl,
            displayName: displayNameRef.current.trim() || undefined,
        }).then((result) => {
            if ('callId' in result && result.callId) {
                joinedCallId = result.callId;
                // The effect was torn down while the join was in flight: leave
                // the now-orphaned call (cleanup ran before the callId existed).
                if (cancelled) {
                    void leaveCall(joinedCallId);
                    return;
                }
            }
            // P5-6: a failed join must not linger. The registry still publishes a
            // failed managed call (its `activationError`/`joinFailed` set), and a
            // `joinFailed` timeout can leave the session reading `joining`. Left in
            // place it masks routing — `selectCallView` would otherwise be wedged on
            // "Joining…" and HIDE a surviving held call. Dismiss the failed call so
            // the held surface (or idle) shows; surface the error to the user.
            if (result.kind === 'failed') {
                console.warn('[CallRoom] joinAndSwitch failed', result.error);
                if (joinedCallId) registry.dismiss(joinedCallId);
                setJoinError(result.error.message);
                // Drop back to prejoin so the failed call can't wedge the view and
                // so the user can retry (re-arms the join effect on the next join).
                setShouldJoin(false);
            }
        });

        return () => {
            cancelled = true;
            if (joinedCallId) void leaveCall(joinedCallId);
        };
    }, [location.hash, location.pathname, location.search, roomId, shouldJoin, joinAndSwitch, leaveCall, registry]);

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
        if (!activeSession || !roomId) return;
        pushNotifySentRef.current = false;

        const unsubscribe = activeSession.subscribe((state: CallState) => {
            if ((state.phase === 'waiting' || state.phase === 'inCall') && !pushNotifySentRef.current) {
                const localStream = activeSession.localStream;
                if (!localStream) return; // Media still loading — wait for next state update
                pushNotifySentRef.current = true;

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
    }, [roomId, activeSession]);

    // P5-8: surface a lone active call's terminal error. When an ALREADY-ACTIVE
    // call's session reaches the terminal `error` phase, the registry releases
    // the lease and removes the call (default immediate retention), so the view
    // drops to `idle` and the prejoin card would render with no error — silently
    // losing the failure. Observe the active session's error AS it terminates and
    // capture it into the prejoin error surface, matching the pre-Phase-5
    // single-call UX. Distinct from the P5-6 failed-JOIN dismissal (that surfaces
    // a `joinAndSwitch` result failure; this fires only for an already-active
    // call's terminal error). When held calls survive, the held surface wins and
    // no error is surfaced (round-2 behavior unchanged) — `hasHeldCalls` is a
    // dependency so the subscription re-binds when held calls appear/disappear.
    useEffect(() => {
        if (!activeSession) return;
        const unsubscribe = activeSession.subscribe((state: CallState) => {
            const message = selectActiveCallTerminalError({
                phase: state.phase,
                error: state.error,
                hasOtherLiveCalls: hasHeldCalls,
            });
            if (message === null) return;
            setJoinError(message);
            // Re-arm the prejoin so the user can retry the join (mirrors P5-6).
            setShouldJoin(false);
        });
        return unsubscribe;
    }, [activeSession, hasHeldCalls]);

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
        setJoinError(null);
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

    const handleEndCall = useCallback(() => {
        // Leave through the registry so it releases the foreground lease and the
        // process owning mode (not just `session.leave()`).
        if (activeCallId) void leaveCall(activeCallId);
        handleDismiss();
    }, [activeCallId, handleDismiss, leaveCall]);

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

    const handleSnapshotCaptured = useCallback((result: SnapshotResult) => {
        const ts = new Date(result.timestampMs);
        const pad = (n: number) => n.toString().padStart(2, '0');
        const filename = `serenada-${ts.getFullYear()}${pad(ts.getMonth() + 1)}${pad(ts.getDate())}` +
            `-${pad(ts.getHours())}${pad(ts.getMinutes())}${pad(ts.getSeconds())}.jpg`;
        const url = URL.createObjectURL(result.blob);
        try {
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = filename;
            anchor.rel = 'noopener';
            anchor.style.display = 'none';
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
        } finally {
            // Revoke after one tick to give the browser time to start the
            // download. We cannot confirm the user actually saved it
            // (`download` is best-effort and not honored on every mobile
            // browser), so the toast intentionally says "ready" rather
            // than asserting that it was saved.
            window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        }
        showToast('success', t('snapshot_ready'));
    }, [showToast, t]);

    const handleSnapshotError = useCallback((error: SnapshotError) => {
        const reason = error.code === 'streamNotActive'
            ? t('snapshot_reason_no_video')
            : error.code === 'captureTimeout'
                ? t('snapshot_reason_timeout')
                : error.code;
        showToast('error', `${t('snapshot_failed')}: ${reason}`);
    }, [showToast, t]);

    if (!roomId) {
        navigate('/');
        return null;
    }

    // Prejoin card: before the user joins, or when there is no live call to show
    // (join not started / failed / torn down — `callView === 'idle'`). A held
    // call does NOT land here — it renders the on-hold switcher below.
    if (!shouldJoin || callView === 'idle') {
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

                    {joinError && (
                        <div className="error-message" role="alert">
                            {joinError}
                        </div>
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

                    <input
                        type="text"
                        className="display-name-input"
                        placeholder={t('display_name_placeholder')}
                        value={displayNameInput}
                        onChange={(e) => {
                            setDisplayNameInput(e.target.value);
                            setDisplayName(e.target.value);
                        }}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') handleJoin(!!sharedName);
                        }}
                        maxLength={40}
                    />

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

    // Joining placeholder: a join is settling and no call is foregrounded yet.
    // Shown instead of mounting the still-held in-flight session as the active
    // flow (active-call-only rendering).
    if (callView === 'joining') {
        return (
            <div className="page-container center-content">
                <div className="card prejoin-card">
                    <h2>{t('joining_call')}</h2>
                    <div className="video-preview-container">
                        <div className="video-placeholder">{t('connecting')}</div>
                    </div>
                </div>
            </div>
        );
    }

    // On-hold switcher: the active call ended with no auto-promote (Core
    // Invariant 5) but live held calls remain. The held sessions are NOT mounted
    // as the active flow — the user picks one to resume via `switchTo`, which
    // foregrounds it (and only then does `SerenadaCallFlow` mount it).
    if (callView === 'held') {
        return (
            <div className="page-container center-content">
                <div className="card prejoin-card">
                    <h2>{t('calls_on_hold_title')}</h2>
                    <p className="prejoin-invite-label">{t('calls_on_hold_desc')}</p>
                    <div className="button-group">
                        {heldCalls.map((call) => (
                            <button
                                key={call.id}
                                type="button"
                                className="btn-primary"
                                onClick={() => { void switchTo(call.id); }}
                            >
                                {t('resume_call', { name: call.displayName?.trim() || call.roomId })}
                            </button>
                        ))}
                        <button className="btn-secondary" onClick={handleDismiss}>
                            {t('home')}
                        </button>
                    </div>
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

    // Active-call-only: mount the audible flow with the FOREGROUND session alone.
    // `callView === 'active'` implies `activeSession` is non-null; the guard
    // narrows the type and is defense in depth (never mount a null/held session).
    if (!activeSession) return null;
    return (
        <SerenadaCallFlow
            session={activeSession}
            config={{
                screenSharingEnabled: true,
                inviteControlsEnabled: true,
                debugOverlayEnabled: true,
                snapshotEnabled: true,
            }}
            strings={strings}
            waitingActions={waitingActions}
            onDismiss={handleDismiss}
            onEndCall={handleEndCall}
            onSnapshotCaptured={handleSnapshotCaptured}
            onSnapshotError={handleSnapshotError}
        />
    );
};

export default CallRoom;
