import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useSignaling } from './SignalingContext';
import { useToast } from './ToastContext';
import { useTranslation } from 'react-i18next';
import {
    OFFER_TIMEOUT_MS,
    ICE_RESTART_COOLDOWN_MS,
    NON_HOST_FALLBACK_DELAY_MS,
    NON_HOST_FALLBACK_MAX_ATTEMPTS,
    ICE_CANDIDATE_BUFFER_MAX,
    TURN_FETCH_TIMEOUT_MS,
} from '../constants/webrtcResilience';

// Default STUN config for non-blocking ICE bootstrap
const DEFAULT_RTC_CONFIG: RTCConfiguration = {
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
};

interface WebRTCContextValue {
    localStream: MediaStream | null;
    remoteStream: MediaStream | null;
    startLocalMedia: () => Promise<MediaStream | null>;
    stopLocalMedia: () => void;
    startScreenShare: () => Promise<void>;
    stopScreenShare: () => Promise<void>;
    isScreenSharing: boolean;
    canScreenShare: boolean;
    flipCamera: () => Promise<void>;
    facingMode: 'user' | 'environment';
    hasMultipleCameras: boolean;
    peerConnection: RTCPeerConnection | null;
    iceConnectionState: RTCIceConnectionState;
    connectionState: RTCPeerConnectionState;
    signalingState: RTCSignalingState;
}

const WebRTCContext = createContext<WebRTCContextValue | null>(null);

export const useWebRTC = () => {
    const context = useContext(WebRTCContext);
    if (!context) {
        throw new Error('useWebRTC must be used within a WebRTCProvider');
    }
    return context;
};

export const WebRTCProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const { sendMessage, roomState, clientId, isConnected, subscribeToMessages, turnToken } = useSignaling();
    const { showToast } = useToast();
    const { t } = useTranslation();

    const [localStream, setLocalStream] = useState<MediaStream | null>(null);
    const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);
    const screenShareTrackRef = useRef<MediaStreamTrack | null>(null);
    const requestingMediaRef = useRef(false);
    const unmountedRef = useRef(false);
    const localStreamRef = useRef<MediaStream | null>(null);
    const remoteStreamRef = useRef<MediaStream | null>(null);
    const isMakingOfferRef = useRef(false);
    const isScreenSharingRef = useRef(false);
    const pendingIceRestartRef = useRef(false);
    const lastIceRestartAtRef = useRef(0);
    const iceRestartTimerRef = useRef<number | null>(null);
    const offerTimeoutRef = useRef<number | null>(null);
    const isConnectedRef = useRef(isConnected);
    const nonHostFallbackTimerRef = useRef<number | null>(null);
    const nonHostFallbackAttemptsRef = useRef(0);

    // RTC Config State — init with default STUN immediately (non-blocking ICE bootstrap)
    const [rtcConfig, setRtcConfig] = useState<RTCConfiguration>(DEFAULT_RTC_CONFIG);
    const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
    const facingModeRef = useRef<'user' | 'environment'>('user');
    const [hasMultipleCameras, setHasMultipleCameras] = useState(false);
    const [isScreenSharing, setIsScreenSharing] = useState(false);
    const [canScreenShare, setCanScreenShare] = useState(false);
    const roomStateRef = useRef(roomState);
    const clientIdRef = useRef(clientId);
    const [iceConnectionState, setIceConnectionState] = useState<RTCIceConnectionState>('new');
    const [connectionState, setConnectionState] = useState<RTCPeerConnectionState>('new');
    const [signalingState, setSignalingState] = useState<RTCSignalingState>('stable');

    useEffect(() => {
        isConnectedRef.current = isConnected;
    }, [isConnected]);

    useEffect(() => {
        setCanScreenShare(!!navigator.mediaDevices?.getDisplayMedia);
    }, []);

    useEffect(() => {
        facingModeRef.current = facingMode;
    }, [facingMode]);

    useEffect(() => {
        isScreenSharingRef.current = isScreenSharing;
    }, [isScreenSharing]);

    useEffect(() => {
        if (!isConnected || !pendingIceRestartRef.current) {
            return;
        }
        if (!isHost()) {
            return;
        }
        const pc = pcRef.current;
        if (!pc) return;
        if (pc.signalingState === 'stable') {
            pendingIceRestartRef.current = false;
            lastIceRestartAtRef.current = Date.now();
            void createOffer({ iceRestart: true });
            return;
        }
    }, [isConnected]);

    const detectCameras = useCallback(async () => {
        if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            const cameras = devices.filter(device => device.kind === 'videoinput');
            setHasMultipleCameras(cameras.length > 1);
        } catch (err) {
            console.warn('[WebRTC] Failed to enumerate devices', err);
        }
    }, []);

    // Detect multiple cameras
    useEffect(() => {
        detectCameras();
        // Also listen for device changes
        navigator.mediaDevices?.addEventListener?.('devicechange', detectCameras);
        return () => {
            navigator.mediaDevices?.removeEventListener?.('devicechange', detectCameras);
        };
    }, [detectCameras]);

    // Ensure media is stopped when the provider unmounts
    useEffect(() => {
        return () => {
            unmountedRef.current = true;
            stopLocalMedia();
        };
    }, []);

    // Fetch ICE Servers when TURN token is available (non-blocking: default STUN already set)
    useEffect(() => {
        if (!turnToken) {
            return;
        }
        const controller = new AbortController();
        const fetchIceServers = async () => {
            try {
                let apiUrl = '/api/turn-credentials';
                const wsUrl = import.meta.env.VITE_WS_URL;
                if (wsUrl) {
                    const url = new URL(wsUrl);
                    url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
                    url.pathname = '/api/turn-credentials';
                    url.searchParams.set('token', turnToken);
                    apiUrl = url.toString();
                } else {
                    apiUrl = `/api/turn-credentials?token=${encodeURIComponent(turnToken)}`;
                }

                const timeoutId = setTimeout(() => controller.abort(), TURN_FETCH_TIMEOUT_MS);
                const res = await fetch(apiUrl, { signal: controller.signal });
                clearTimeout(timeoutId);

                if (res.ok) {
                    const data = await res.json();
                    console.log('[WebRTC] Loaded ICE Servers:', data);

                    const params = new URLSearchParams(window.location.search);
                    const turnsOnly = params.get('turnsonly') === '1';

                    const servers: RTCIceServer[] = [];
                    if (data.uris) {
                        let uris = data.uris;
                        if (turnsOnly) {
                            console.log('[WebRTC] Forced TURNS mode active. Filtering URIs.');
                            uris = uris.filter((u: string) => u.startsWith('turns:'));
                        }

                        if (uris.length > 0) {
                            servers.push({
                                urls: uris,
                                username: data.username,
                                credential: data.password
                            });
                        }
                    }

                    const config: RTCConfiguration = {
                        iceServers: servers.length > 0 ? servers : DEFAULT_RTC_CONFIG.iceServers
                    };

                    if (turnsOnly) {
                        config.iceTransportPolicy = 'relay';
                    }

                    setRtcConfig(config);
                } else {
                    console.warn('[WebRTC] Failed to fetch ICE servers, keeping default STUN');
                }
            } catch (err) {
                if (controller.signal.aborted) {
                    console.warn('[WebRTC] TURN fetch timed out, keeping default STUN');
                } else {
                    console.error('[WebRTC] Error fetching ICE servers:', err);
                }
            }
        };

        fetchIceServers();
        return () => controller.abort();
    }, [turnToken]);

    // Buffer ICE candidates if remote description not set
    const iceBufferRef = useRef<RTCIceCandidateInit[]>([]);

    const processSignalingMessage = useCallback(async (msg: any) => {
        const { type, payload } = msg;
        try {
            switch (type) {
                case 'offer':
                    if (payload && payload.sdp) {
                        await handleOffer(payload.sdp);
                    } else {
                        console.warn('[WebRTC] Offer received without SDP');
                    }
                    break;
                case 'answer':
                    if (payload && payload.sdp) {
                        await handleAnswer(payload.sdp);
                    }
                    break;
                case 'ice':
                    if (payload && payload.candidate) {
                        await handleIce(payload.candidate);
                    }
                    break;
            }
        } catch (err) {
            console.error(`[WebRTC] Error processing message ${type}:`, err);
        }
    }, [roomState, clientId, rtcConfig]); // Depends on state used in handlers

    // Handle incoming signaling messages
    useEffect(() => {
        const handleMessage = (msg: any) => {
            processSignalingMessage(msg);
        };

        const unsubscribe = subscribeToMessages(handleMessage);
        return () => {
            unsubscribe();
        };
    }, [subscribeToMessages, processSignalingMessage]);

    const applySpeechTrackHints = (stream: MediaStream) => {
        const audioTrack = stream.getAudioTracks()[0];
        if (!audioTrack) return;
        if ('contentHint' in audioTrack) {
            try {
                audioTrack.contentHint = 'speech';
            } catch (err) {
                console.warn('[WebRTC] Failed to set audio contentHint', err);
            }
        }
    };

    const applyAudioSenderParameters = async (pc: RTCPeerConnection) => {
        const sender = pc.getSenders().find(s => s.track?.kind === 'audio');
        if (!sender || !sender.getParameters || !sender.setParameters) return;
        try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) {
                params.encodings = [{}];
            }
            if (params.encodings[0]) {
                params.encodings[0].maxBitrate = 32000; // Speech-optimized bitrate (bps)
            }
            await sender.setParameters(params);
        } catch (err) {
            console.warn('[WebRTC] Failed to apply audio sender parameters', err);
        }
    };

    const isHost = () => {
        const state = roomStateRef.current;
        return !!state && !!state.hostCid && state.hostCid === clientIdRef.current;
    };

    const clearIceRestartTimer = () => {
        if (iceRestartTimerRef.current) {
            window.clearTimeout(iceRestartTimerRef.current);
            iceRestartTimerRef.current = null;
        }
    };

    const clearOfferTimeout = () => {
        if (offerTimeoutRef.current) {
            window.clearTimeout(offerTimeoutRef.current);
            offerTimeoutRef.current = null;
        }
    };

    const scheduleIceRestart = (reason: string, delayMs: number) => {
        if (!pcRef.current) return;
        if (!isHost()) return;
        if (!isConnectedRef.current) {
            pendingIceRestartRef.current = true;
            return;
        }
        if (iceRestartTimerRef.current) return;

        const now = Date.now();
        if (now - lastIceRestartAtRef.current < ICE_RESTART_COOLDOWN_MS) {
            return;
        }

        iceRestartTimerRef.current = window.setTimeout(() => {
            iceRestartTimerRef.current = null;
            void triggerIceRestart(reason);
        }, delayMs);
    };

    const triggerIceRestart = async (reason: string) => {
        if (!pcRef.current) return;
        if (!isHost()) return;
        if (!isConnectedRef.current) {
            pendingIceRestartRef.current = true;
            return;
        }

        if (isMakingOfferRef.current) {
            pendingIceRestartRef.current = true;
            return;
        }

        lastIceRestartAtRef.current = Date.now();
        pendingIceRestartRef.current = false;
        console.warn(`[WebRTC] ICE restart triggered (${reason})`);
        await createOffer({ iceRestart: true });
    };

    // Logic to initiate offer if we are HOST and have 2 participants
    useEffect(() => {
        if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid === clientId) {
            const pc = getOrCreatePC();
            // Only initiate offer if we haven't established a connection yet (no remote description)
            if (pc.signalingState === 'stable' && !pc.remoteDescription) {
                createOffer();
            }
        } else if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid !== clientId) {
            // Non-host: create PC and schedule fallback offer if host doesn't send one
            getOrCreatePC();
            scheduleNonHostFallback();
        } else if (roomState && roomState.participants && roomState.participants.length < 2) {
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Participant left, cleaning up connection');
                cleanupPC();
            }
        } else if (!roomState) {
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Room state cleared, cleaning up connection');
                cleanupPC();
            }
        }
    }, [roomState, clientId, remoteStream, rtcConfig]);


    const clearNonHostFallback = () => {
        if (nonHostFallbackTimerRef.current) {
            window.clearTimeout(nonHostFallbackTimerRef.current);
            nonHostFallbackTimerRef.current = null;
        }
    };

    const scheduleNonHostFallback = () => {
        if (isHost()) return;
        if (nonHostFallbackTimerRef.current) return;
        if (nonHostFallbackAttemptsRef.current >= NON_HOST_FALLBACK_MAX_ATTEMPTS) return;
        const pc = pcRef.current;
        if (!pc) return;

        nonHostFallbackTimerRef.current = window.setTimeout(async () => {
            nonHostFallbackTimerRef.current = null;
            const currentPc = pcRef.current;
            if (!currentPc) return;
            if (isHost()) return;
            if (currentPc.remoteDescription) return; // host sent offer, no need
            if (currentPc.signalingState !== 'stable') return;
            if (!isConnectedRef.current) return;

            nonHostFallbackAttemptsRef.current++;
            console.warn(`[WebRTC] Non-host fallback offer (attempt ${nonHostFallbackAttemptsRef.current})`);
            try {
                const offer = await currentPc.createOffer();
                await currentPc.setLocalDescription(offer as RTCSessionDescriptionInit);
                sendMessage('offer', { sdp: offer.sdp });

                // Schedule answer timeout — rollback and reschedule (no ICE restart)
                clearOfferTimeout();
                offerTimeoutRef.current = window.setTimeout(async () => {
                    offerTimeoutRef.current = null;
                    const pc = pcRef.current;
                    if (!pc) return;
                    if (pc.signalingState === 'have-local-offer') {
                        console.warn('[WebRTC] Non-host fallback offer timed out, rolling back');
                        try {
                            await pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit);
                        } catch (rollbackErr) {
                            console.warn('[WebRTC] Non-host fallback rollback failed', rollbackErr);
                        }
                    }
                    scheduleNonHostFallback();
                }, OFFER_TIMEOUT_MS);
            } catch (err) {
                console.error('[WebRTC] Non-host fallback offer failed', err);
                if (currentPc.localDescription?.type === 'offer') {
                    try {
                        await currentPc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit);
                    } catch (rollbackErr) {
                        console.error('[WebRTC] Non-host fallback rollback failed', rollbackErr);
                    }
                }
                scheduleNonHostFallback();
            }
        }, NON_HOST_FALLBACK_DELAY_MS);
    };

    const getOrCreatePC = () => {
        if (pcRef.current) return pcRef.current;

        const pc = new RTCPeerConnection(rtcConfig);
        pcRef.current = pc;
        setIceConnectionState(pc.iceConnectionState);
        setConnectionState(pc.connectionState);
        setSignalingState(pc.signalingState);

        // Add local tracks if available
        if (localStream) {
            localStream.getTracks().forEach(track => {
                pc.addTrack(track, localStream);
            });
            void applyAudioSenderParameters(pc);
        }

        pc.ontrack = (event) => {
            console.log('Remote track received', event.streams);
            if (event.streams && event.streams[0]) {
                const stream = event.streams[0];
                remoteStreamRef.current = stream;
                console.log(`[WebRTC] Stream active: ${stream.active}`);
                stream.getTracks().forEach(t => console.log(`[WebRTC] Track ${t.kind}: enabled=${t.enabled}, muted=${t.muted}, state=${t.readyState}`));
                setRemoteStream(stream);
                return;
            }

            // Safari may not populate event.streams; build a stream from tracks.
            let stream = remoteStreamRef.current;
            if (!stream) {
                stream = new MediaStream();
                remoteStreamRef.current = stream;
            }
            if (!stream.getTracks().some(t => t.id === event.track.id)) {
                stream.addTrack(event.track);
            }
            setRemoteStream(stream);
        };

        pc.oniceconnectionstatechange = () => {
            console.log(`[WebRTC] ICE Connection State: ${pc.iceConnectionState}`);
            setIceConnectionState(pc.iceConnectionState);

            if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                clearIceRestartTimer();
                pendingIceRestartRef.current = false;
                return;
            }

            if (pc.iceConnectionState === 'disconnected') {
                scheduleIceRestart('ice-disconnected', 2000);
            } else if (pc.iceConnectionState === 'failed') {
                scheduleIceRestart('ice-failed', 0);
            }
        };

        pc.onconnectionstatechange = () => {
            console.log(`[WebRTC] Connection State: ${pc.connectionState}`);
            setConnectionState(pc.connectionState);

            if (pc.connectionState === 'connected') {
                clearIceRestartTimer();
                pendingIceRestartRef.current = false;
                return;
            }

            if (pc.connectionState === 'disconnected') {
                scheduleIceRestart('conn-disconnected', 2000);
            } else if (pc.connectionState === 'failed') {
                scheduleIceRestart('conn-failed', 0);
            }
        };

        pc.onsignalingstatechange = () => {
            console.log(`[WebRTC] Signaling State: ${pc.signalingState}`);
            setSignalingState(pc.signalingState);
            if (pc.signalingState === 'stable') {
                clearOfferTimeout();
            }
            if (pc.signalingState === 'stable' && pendingIceRestartRef.current) {
                clearOfferTimeout();
                if (!isConnectedRef.current || !isHost()) {
                    return;
                }
                pendingIceRestartRef.current = false;
                lastIceRestartAtRef.current = Date.now();
                void createOffer({ iceRestart: true });
            }
        };

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                sendMessage('ice', { candidate: event.candidate });
            }
        };

        pc.onnegotiationneeded = async () => {
            const state = roomStateRef.current;
            if (!state || !state.participants || state.participants.length < 2) {
                return;
            }
            if (!state.hostCid || state.hostCid !== clientIdRef.current) {
                return;
            }
            await createOffer();
        };

        return pc;
    };

    const cleanupPC = () => {
        if (pcRef.current) {
            pcRef.current.close();
            pcRef.current = null;
        }
        clearIceRestartTimer();
        clearOfferTimeout();
        clearNonHostFallback();
        nonHostFallbackAttemptsRef.current = 0;
        pendingIceRestartRef.current = false;
        setIceConnectionState('closed');
        setConnectionState('closed');
        setSignalingState('closed');
        remoteStreamRef.current = null;
        setRemoteStream(null);
        // We do NOT stop local stream here to allow reuse? 
        // Actually usually we stop it on leave.
    };

    // Keep ref in sync with state
    useEffect(() => {
        localStreamRef.current = localStream;
    }, [localStream]);

    useEffect(() => {
        roomStateRef.current = roomState;
    }, [roomState]);

    useEffect(() => {
        clientIdRef.current = clientId;
    }, [clientId]);

    useEffect(() => {
        const handleOnline = () => {
            const pc = pcRef.current;
            if (pc && (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed')) {
                scheduleIceRestart('network-online', 0);
            }
        };
        const handleNetworkChange = () => {
            const pc = pcRef.current;
            if (pc && (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed')) {
                scheduleIceRestart('network-change', 0);
            }
        };
        window.addEventListener('online', handleOnline);
        const conn = (navigator as any).connection;
        conn?.addEventListener?.('change', handleNetworkChange);
        return () => {
            window.removeEventListener('online', handleOnline);
            conn?.removeEventListener?.('change', handleNetworkChange);
        };
    }, []);

    const mediaRequestIdRef = useRef<number>(0);

    const startLocalMedia = useCallback(async (): Promise<MediaStream | null> => {
        // Increment request ID for the new attempt
        const requestId = mediaRequestIdRef.current + 1;
        mediaRequestIdRef.current = requestId;

        // If we already have a stream, checks below will decide what to do.
        // But if localStream exists, we usually return.
        if (localStream) {
            return localStream;
        }

        requestingMediaRef.current = true;
        try {
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                showToast('error', t('toast_media_blocked'));
                requestingMediaRef.current = false;
                return null;
            }
            const audioConstraints: MediaTrackConstraints = {
                echoCancellation: { ideal: true },
                noiseSuppression: { ideal: true },
                autoGainControl: { ideal: true },
                channelCount: { ideal: 1 },
                sampleRate: { ideal: 48000 }
            };
            let stream: MediaStream;
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: facingMode },
                    audio: audioConstraints
                });
            } catch (constraintErr) {
                // Retry with relaxed constraints on failure
                console.warn('[WebRTC] getUserMedia failed with constraints, retrying with relaxed', constraintErr);
                stream = await navigator.mediaDevices.getUserMedia({
                    video: true,
                    audio: true
                });
            }

            // Check validity:
            // 1. Component unmounted
            // 2. Request was obsolete (new request started or stop called)
            if (unmountedRef.current || mediaRequestIdRef.current !== requestId) {
                console.log(`[WebRTC] Media request ${requestId} stale or cancelled. Stopping tracks.`);
                stream.getTracks().forEach(t => t.stop());
                return null;
            }

            applySpeechTrackHints(stream);
            setLocalStream(stream);
            await detectCameras();
            requestingMediaRef.current = false;

            if (pcRef.current) {
                stream.getTracks().forEach(track => {
                    pcRef.current?.addTrack(track, stream);
                });
                void applyAudioSenderParameters(pcRef.current);
            }
            return stream;
        } catch (err) {
            console.error("Error accessing media", err);
            requestingMediaRef.current = false;
            return null;
        }
    }, [localStream, facingMode, showToast, t]);

    // Use useCallback to make this stable, but access stream via ref to avoid stale closure
    const stopLocalMedia = useCallback(() => {
        // Invalidate any pending requests
        mediaRequestIdRef.current += 1; // Incrementing invalidates previous ID

        const screenShareTrack = screenShareTrackRef.current;
        if (screenShareTrack) {
            screenShareTrack.onended = null;
            screenShareTrackRef.current = null;
        }

        const stream = localStreamRef.current;
        if (stream) {
            stream.getTracks().forEach(t => t.stop());
            setLocalStream(null);
        }
        setIsScreenSharing(false);
        setFacingMode('user');
        requestingMediaRef.current = false;
    }, []);

    const stopScreenShare = useCallback(async () => {
        if (!isScreenSharingRef.current) {
            return;
        }

        const currentStream = localStreamRef.current;
        if (!currentStream) {
            setIsScreenSharing(false);
            return;
        }

        const screenShareTrack = screenShareTrackRef.current;
        if (screenShareTrack) {
            screenShareTrack.onended = null;
            screenShareTrackRef.current = null;
        }

        const previousVideoTrack = currentStream.getVideoTracks()[0];
        const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;

        try {
            const cameraStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: facingModeRef.current },
                audio: false
            });
            const cameraTrack = cameraStream.getVideoTracks()[0];
            if (!cameraTrack) {
                throw new Error('No camera track returned');
            }
            cameraTrack.enabled = wasVideoEnabled;

            const pc = pcRef.current;
            if (pc) {
                const videoSender = pc.getSenders().find(sender => sender.track?.kind === 'video');
                if (videoSender) {
                    await videoSender.replaceTrack(cameraTrack);
                } else {
                    pc.addTrack(cameraTrack, currentStream);
                }
            }

            setLocalStream(new MediaStream([
                cameraTrack,
                ...currentStream.getAudioTracks()
            ]));
            if (previousVideoTrack) {
                previousVideoTrack.stop();
            }
            setIsScreenSharing(false);
        } catch (err) {
            console.error('[WebRTC] Failed to stop screen share and restore camera', err);
            const pc = pcRef.current;
            if (pc) {
                const videoSender = pc.getSenders().find(sender => sender.track?.kind === 'video');
                if (videoSender) {
                    try {
                        await videoSender.replaceTrack(null);
                    } catch (replaceErr) {
                        console.warn('[WebRTC] Failed to clear video sender after screen share error', replaceErr);
                    }
                }
            }
            if (previousVideoTrack) {
                previousVideoTrack.stop();
            }
            setLocalStream(new MediaStream([
                ...currentStream.getAudioTracks()
            ]));
            setIsScreenSharing(false);
            showToast('error', t('toast_camera_error'));
        }
    }, [showToast, t]);

    const startScreenShare = useCallback(async () => {
        if (isScreenSharingRef.current || !canScreenShare) {
            return;
        }

        const currentStream = localStreamRef.current;
        if (!currentStream) {
            return;
        }

        try {
            const displayStream = await navigator.mediaDevices.getDisplayMedia({
                video: true,
                audio: false
            });
            const displayTrack = displayStream.getVideoTracks()[0];
            if (!displayTrack) {
                displayStream.getTracks().forEach(track => track.stop());
                throw new Error('No display track returned');
            }

            const previousVideoTrack = currentStream.getVideoTracks()[0];
            const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;
            displayTrack.enabled = wasVideoEnabled;
            if ('contentHint' in displayTrack) {
                try {
                    displayTrack.contentHint = 'detail';
                } catch (err) {
                    console.warn('[WebRTC] Failed to set display track contentHint', err);
                }
            }

            const pc = pcRef.current;
            if (pc) {
                const videoSender = pc.getSenders().find(sender => sender.track?.kind === 'video');
                if (videoSender) {
                    await videoSender.replaceTrack(displayTrack);
                } else {
                    pc.addTrack(displayTrack, currentStream);
                }
            }

            if (screenShareTrackRef.current) {
                screenShareTrackRef.current.onended = null;
            }
            screenShareTrackRef.current = displayTrack;
            displayTrack.onended = () => {
                void stopScreenShare();
            };

            setLocalStream(new MediaStream([
                displayTrack,
                ...currentStream.getAudioTracks()
            ]));
            if (previousVideoTrack) {
                previousVideoTrack.stop();
            }
            setIsScreenSharing(true);
        } catch (err) {
            console.error('[WebRTC] Failed to start screen share', err);
            showToast('error', t('toast_screen_share_error'));
        }
    }, [canScreenShare, showToast, stopScreenShare, t]);

    const flipCamera = async () => {
        if (isScreenSharingRef.current) return;
        if (!hasMultipleCameras) return;

        const newMode = facingMode === 'user' ? 'environment' : 'user';
        setFacingMode(newMode);

        if (!localStream) return;

        try {
            // Stop old video tracks
            const oldVideoTrack = localStream.getVideoTracks()[0];
            if (oldVideoTrack) oldVideoTrack.stop();

            // Get new stream with new facing mode
            const newStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: newMode },
                audio: false // Keep same audio if possible, but simpler to just get new video
            });

            const newVideoTrack = newStream.getVideoTracks()[0];

            // Replace track in peer connection
            if (pcRef.current) {
                const senders = pcRef.current.getSenders();
                const videoSender = senders.find(s => s.track?.kind === 'video');
                if (videoSender) {
                    await videoSender.replaceTrack(newVideoTrack);
                }
            }

            // Update local stream
            const combinedStream = new MediaStream([
                newVideoTrack,
                ...localStream.getAudioTracks()
            ]);
            setLocalStream(combinedStream);
        } catch (err) {
            console.error('[WebRTC] Failed to flip camera', err);
            showToast('error', t('toast_flip_camera_error'));
        }
    };

    const createOffer = async (options?: { iceRestart?: boolean }) => {
        if (isMakingOfferRef.current) {
            if (options?.iceRestart) {
                pendingIceRestartRef.current = true;
            }
            return;
        }
        try {
            console.log('[WebRTC] Creating offer...');
            const pc = getOrCreatePC();
            if (pc.signalingState !== 'stable') {
                console.log('[WebRTC] Skipping offer; signaling state is not stable');
                if (options?.iceRestart) {
                    pendingIceRestartRef.current = true;
                }
                return;
            }
            isMakingOfferRef.current = true;
            const offer = await pc.createOffer(options);
            await pc.setLocalDescription(offer as RTCSessionDescriptionInit);
            console.log('[WebRTC] Sending offer');
            sendMessage('offer', { sdp: offer.sdp });
            clearOfferTimeout();
            offerTimeoutRef.current = window.setTimeout(() => {
                const currentPc = pcRef.current;
                if (!currentPc) return;
                console.warn(`[WebRTC] Offer timeout; signalingState=${currentPc.signalingState}`);
                pendingIceRestartRef.current = true;
                if (currentPc.signalingState === 'have-local-offer') {
                    currentPc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit)
                        .catch(err => {
                            console.warn('[WebRTC] Rollback failed', err);
                        })
                        .finally(() => {
                            scheduleIceRestart('offer-timeout', 0);
                        });
                } else {
                    // Negotiation stalled in unexpected state — still schedule ICE restart
                    scheduleIceRestart('offer-timeout-unexpected-state', 0);
                }
            }, OFFER_TIMEOUT_MS);
        } catch (err) {
            console.error('[WebRTC] Error creating offer:', err);
        } finally {
            isMakingOfferRef.current = false;
            if (pendingIceRestartRef.current) {
                pendingIceRestartRef.current = false;
                scheduleIceRestart('pending-retry', 500);
            }
        }
    };


    const handleOffer = async (sdp: string) => {
        try {
            console.log('[WebRTC] Handling offer...');
            clearNonHostFallback(); // Cancel non-host fallback on inbound offer
            const pc = getOrCreatePC();
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
            console.log('[WebRTC] Remote description set (offer)');
            clearOfferTimeout();

            // Process buffered ICE
            while (iceBufferRef.current.length > 0) {
                const c = iceBufferRef.current.shift();
                if (c) {
                    console.log('[WebRTC] Adding buffered ICE candidate');
                    await pc.addIceCandidate(c);
                }
            }

            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            console.log('[WebRTC] Sending answer');
            sendMessage('answer', { sdp: answer.sdp });
        } catch (err) {
            console.error('[WebRTC] Error handling offer:', err);
        }
    };

    const handleAnswer = async (sdp: string) => {
        try {
            console.log('[WebRTC] Handling answer...');
            clearNonHostFallback(); // Cancel non-host fallback on inbound answer
            const pc = getOrCreatePC();
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            console.log('[WebRTC] Remote description set (answer)');
            clearOfferTimeout();
        } catch (err) {
            console.error('[WebRTC] Error handling answer:', err);
        }
    };

    const handleIce = async (candidate: RTCIceCandidateInit) => {
        try {
            const pc = getOrCreatePC();
            if (pc.remoteDescription) {
                await pc.addIceCandidate(candidate);
            } else {
                if (iceBufferRef.current.length >= ICE_CANDIDATE_BUFFER_MAX) {
                    console.warn(`[WebRTC] ICE buffer full (${ICE_CANDIDATE_BUFFER_MAX}), dropping oldest`);
                    iceBufferRef.current.shift();
                }
                iceBufferRef.current.push(candidate);
            }
        } catch (err) {
            console.error('[WebRTC] Error handling ICE:', err);
        }
    };

    return (
        <WebRTCContext.Provider value={{
            localStream,
            remoteStream,
            startLocalMedia,
            stopLocalMedia,
            startScreenShare,
            stopScreenShare,
            isScreenSharing,
            canScreenShare,
            flipCamera: flipCamera,
            facingMode: facingMode,
            hasMultipleCameras: hasMultipleCameras,
            peerConnection: pcRef.current,
            iceConnectionState,
            connectionState,
            signalingState
        }}>
            {children}
        </WebRTCContext.Provider>
    );
};
