import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useSignaling } from './SignalingContext';
import { useToast } from './ToastContext';
import { useTranslation } from 'react-i18next';

// RTC Config
// RTC Config moved to state


interface WebRTCContextValue {
    localStream: MediaStream | null;
    remoteStream: MediaStream | null;
    startLocalMedia: () => Promise<void>;
    stopLocalMedia: () => void;
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
    const requestingMediaRef = useRef(false);
    const unmountedRef = useRef(false);
    const localStreamRef = useRef<MediaStream | null>(null);
    const remoteStreamRef = useRef<MediaStream | null>(null);
    const isMakingOfferRef = useRef(false);
    const pendingIceRestartRef = useRef(false);
    const lastIceRestartAtRef = useRef(0);
    const iceRestartTimerRef = useRef<number | null>(null);
    const offerTimeoutRef = useRef<number | null>(null);
    const isConnectedRef = useRef(isConnected);

    // RTC Config State
    const [rtcConfig, setRtcConfig] = useState<RTCConfiguration | null>(null);
    const rtcConfigRef = useRef<RTCConfiguration | null>(null);
    const signalingBufferRef = useRef<any[]>([]);
    const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
    const [hasMultipleCameras, setHasMultipleCameras] = useState(false);
    const roomStateRef = useRef(roomState);
    const clientIdRef = useRef(clientId);
    const [iceConnectionState, setIceConnectionState] = useState<RTCIceConnectionState>('new');
    const [connectionState, setConnectionState] = useState<RTCPeerConnectionState>('new');
    const [signalingState, setSignalingState] = useState<RTCSignalingState>('stable');

    useEffect(() => {
        isConnectedRef.current = isConnected;
    }, [isConnected]);

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

    // Fetch ICE Servers on mount
    useEffect(() => {
        if (!turnToken) {
            return;
        }
        const fetchIceServers = async () => {
            try {
                // In production, this call goes to the same Go server via Nginx proxy or direct
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

                const res = await fetch(apiUrl);
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
                        iceServers: servers.length > 0 ? servers : [{ urls: 'stun:stun.l.google.com:19302' }]
                    };

                    if (turnsOnly) {
                        config.iceTransportPolicy = 'relay';
                    }

                    setRtcConfig(config);
                } else {
                    console.warn('[WebRTC] Failed to fetch ICE servers, using default Google STUN');
                    setRtcConfig({
                        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                    });
                }
            } catch (err) {
                console.error('[WebRTC] Error fetching ICE servers:', err);
                setRtcConfig({
                    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                });
            }
        };

        fetchIceServers();
    }, [turnToken]);

    // Sync rtcConfig to ref and flush buffered messages
    useEffect(() => {
        rtcConfigRef.current = rtcConfig;
        if (rtcConfig && signalingBufferRef.current.length > 0) {
            console.log(`[WebRTC] Flushing ${signalingBufferRef.current.length} buffered signaling messages`);
            const msgs = [...signalingBufferRef.current];
            signalingBufferRef.current = [];
            msgs.forEach(msg => {
                // We use setTimeout to ensure we don't block the effect and allow state updates to settle if needed
                setTimeout(() => processSignalingMessage(msg), 0);
            });
        }
    }, [rtcConfig]); // eslint-disable-line react-hooks/exhaustive-deps

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
            const { type } = msg;
            // Only buffer WebRTC negotiation messages
            if (['offer', 'answer', 'ice'].includes(type)) {
                if (!rtcConfigRef.current) {
                    console.log(`[WebRTC] Buffering signaling message: ${type}`);
                    signalingBufferRef.current.push(msg);
                    return;
                }
            }
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
        if (now - lastIceRestartAtRef.current < 10000) {
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
        // Wait for ICE config to be loaded before attempting to create peer connection
        if (!rtcConfig) {
            return;
        }
        if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid === clientId) {
            // ... (existing logic)
            const pc = getOrCreatePC();
            // Only initiate offer if we haven't established a connection yet (no remote description)
            // This prevents infinite negotiation loops when room_state updates occur
            if (pc.signalingState === 'stable' && !pc.remoteDescription) {
                createOffer();
            }
        } else if (roomState && roomState.participants && roomState.participants.length < 2) {
            // Check if we need to cleanup. If we have a PC or remote stream, clean it.
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Participant left, cleaning up connection');
                cleanupPC();
            }
        } else if (!roomState) {
            // We left the room completely
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Room state cleared, cleaning up connection');
                cleanupPC();
            }
        }
    }, [roomState, clientId, remoteStream, rtcConfig]);


    const getOrCreatePC = () => {
        if (!rtcConfig) {
            console.warn("getOrCreatePC called before ICE config loaded");
            throw new Error("Cannot create PC before ICE config is loaded");
        }
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
            scheduleIceRestart('network-online', 0);
        };
        window.addEventListener('online', handleOnline);
        return () => {
            window.removeEventListener('online', handleOnline);
        };
    }, []);

    const mediaRequestIdRef = useRef<number>(0);

    const startLocalMedia = useCallback(async () => {
        // Increment request ID for the new attempt
        const requestId = mediaRequestIdRef.current + 1;
        mediaRequestIdRef.current = requestId;

        // If we already have a stream, checks below will decide what to do.
        // But if localStream exists, we usually return.
        if (localStream) {
            return;
        }

        requestingMediaRef.current = true;
        try {
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                showToast('error', t('toast_media_blocked'));
                requestingMediaRef.current = false;
                return;
            }
            const audioConstraints: MediaTrackConstraints = {
                echoCancellation: { ideal: true },
                noiseSuppression: { ideal: true },
                autoGainControl: { ideal: true },
                channelCount: { ideal: 1 },
                sampleRate: { ideal: 48000 }
            };
            const stream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: facingMode },
                audio: audioConstraints
            });

            // Check validity:
            // 1. Component unmounted
            // 2. Request was obsolete (new request started or stop called)
            if (unmountedRef.current || mediaRequestIdRef.current !== requestId) {
                console.log(`[WebRTC] Media request ${requestId} stale or cancelled. Stopping tracks.`);
                stream.getTracks().forEach(t => t.stop());
                return;
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
            return;
        } catch (err) {
            console.error("Error accessing media", err);
            requestingMediaRef.current = false;
        }
    }, [localStream, facingMode, showToast, t]);

    // Use useCallback to make this stable, but access stream via ref to avoid stale closure
    const stopLocalMedia = useCallback(() => {
        // Invalidate any pending requests
        mediaRequestIdRef.current += 1; // Incrementing invalidates previous ID

        const stream = localStreamRef.current;
        if (stream) {
            stream.getTracks().forEach(t => t.stop());
            setLocalStream(null);
        }
        requestingMediaRef.current = false;
    }, []);

    const flipCamera = async () => {
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

            // Force/Prefer VP8 for compatibility with older Android devices
            const sdpWithVP8 = forceVP8(offer.sdp);
            const offerWithVP8 = { type: offer.type, sdp: sdpWithVP8 };

            await pc.setLocalDescription(offerWithVP8 as RTCSessionDescriptionInit);
            console.log('[WebRTC] Sending offer (VP8 preferred)');
            sendMessage('offer', { sdp: offerWithVP8.sdp });
            clearOfferTimeout();
            offerTimeoutRef.current = window.setTimeout(() => {
                const currentPc = pcRef.current;
                if (!currentPc) return;
                if (currentPc.signalingState !== 'have-local-offer') {
                    return;
                }
                console.warn('[WebRTC] Offer timeout; rolling back and retrying');
                pendingIceRestartRef.current = true;
                currentPc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit)
                    .catch(err => {
                        console.warn('[WebRTC] Rollback failed', err);
                    })
                    .finally(() => {
                        scheduleIceRestart('offer-timeout', 0);
                    });
            }, 8000);
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
                console.log('[WebRTC] Buffering ICE candidate');
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

// Helper to prioritize VP8 in SDP
function forceVP8(sdp: string | undefined): string | undefined {
    if (!sdp) return sdp;
    try {
        const sdpLines = sdp.split('\r\n');
        const mLineIndex = sdpLines.findIndex(line => line.startsWith('m=video'));
        if (mLineIndex === -1) return sdp;

        const mLine = sdpLines[mLineIndex];
        const elements = mLine.split(' ');
        const ptList = elements.slice(3); // Payload types

        // Find VP8 payload types
        const vp8Pts: string[] = [];
        sdpLines.forEach(line => {
            if (line.startsWith('a=rtpmap:')) {
                const parts = line.substring(9).split(' ');
                const pt = parts[0];
                const name = parts[1].split('/')[0];
                if (name.toUpperCase() === 'VP8') {
                    vp8Pts.push(pt);
                }
            }
        });

        if (vp8Pts.length === 0) return sdp;

        // Reorder: VP8 first
        const newPtList = [
            ...vp8Pts,
            ...ptList.filter(pt => !vp8Pts.includes(pt))
        ];

        sdpLines[mLineIndex] = `${elements.slice(0, 3).join(' ')} ${newPtList.join(' ')}`;
        return sdpLines.join('\r\n');
    } catch (e) {
        console.warn("Retaining original SDP due to parsing error", e);
        return sdp;
    }
}
