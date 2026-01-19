import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { useSignaling } from './SignalingContext';
import { useToast } from './ToastContext';

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

    const [localStream, setLocalStream] = useState<MediaStream | null>(null);
    const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);
    const requestingMediaRef = useRef(false);
    const unmountedRef = useRef(false);
    const localStreamRef = useRef<MediaStream | null>(null);
    const remoteStreamRef = useRef<MediaStream | null>(null);
    const isMakingOfferRef = useRef(false);

    // RTC Config State
    const [rtcConfig, setRtcConfig] = useState<RTCConfiguration | null>(null);
    const rtcConfigRef = useRef<RTCConfiguration | null>(null);
    const signalingBufferRef = useRef<any[]>([]);
    const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
    const [hasMultipleCameras, setHasMultipleCameras] = useState(false);
    const roomStateRef = useRef(roomState);
    const clientIdRef = useRef(clientId);

    // Detect multiple cameras
    useEffect(() => {
        const detectCameras = async () => {
            if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
            try {
                const devices = await navigator.mediaDevices.enumerateDevices();
                const cameras = devices.filter(device => device.kind === 'videoinput');
                setHasMultipleCameras(cameras.length > 1);
            } catch (err) {
                console.warn('[WebRTC] Failed to enumerate devices', err);
            }
        };
        detectCameras();
        // Also listen for device changes
        navigator.mediaDevices?.addEventListener?.('devicechange', detectCameras);
        return () => {
            navigator.mediaDevices?.removeEventListener?.('devicechange', detectCameras);
        };
    }, []);

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
                    apiUrl = url.toString();
                }

                const res = await fetch(apiUrl, {
                    headers: {
                        'X-Turn-Token': turnToken
                    }
                });
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

    // Initialize or Cleanup PC based on connection
    useEffect(() => {
        if (!isConnected) {
            cleanupPC();
        }
    }, [isConnected]);

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
        };

        pc.onconnectionstatechange = () => {
            console.log(`[WebRTC] Connection State: ${pc.connectionState}`);
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
                showToast('error', "Camera/Mic access blocked! Please ensure you are using a secure context (HTTPS or localhost).");
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
    }, [localStream, facingMode, showToast]);

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
            showToast('error', 'Failed to flip camera');
        }
    };

    const createOffer = async () => {
        if (isMakingOfferRef.current) {
            return;
        }
        try {
            console.log('[WebRTC] Creating offer...');
            const pc = getOrCreatePC();
            if (pc.signalingState !== 'stable') {
                console.log('[WebRTC] Skipping offer; signaling state is not stable');
                return;
            }
            isMakingOfferRef.current = true;
            const offer = await pc.createOffer();

            // Force/Prefer VP8 for compatibility with older Android devices
            const sdpWithVP8 = forceVP8(offer.sdp);
            const offerWithVP8 = { type: offer.type, sdp: sdpWithVP8 };

            await pc.setLocalDescription(offerWithVP8 as RTCSessionDescriptionInit);
            console.log('[WebRTC] Sending offer (VP8 preferred)');
            sendMessage('offer', { sdp: offerWithVP8.sdp });
        } catch (err) {
            console.error('[WebRTC] Error creating offer:', err);
        } finally {
            isMakingOfferRef.current = false;
        }
    };


    const handleOffer = async (sdp: string) => {
        try {
            console.log('[WebRTC] Handling offer...');
            const pc = getOrCreatePC();
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
            console.log('[WebRTC] Remote description set (offer)');

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
            peerConnection: pcRef.current
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
