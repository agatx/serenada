import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useSignaling } from './SignalingContext';
import { useToast } from './ToastContext';

// RTC Config
// RTC Config moved to state


interface WebRTCContextValue {
    localStream: MediaStream | null;
    remoteStream: MediaStream | null;
    startLocalMedia: () => Promise<void>;
    stopLocalMedia: () => void;
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
    const { sendMessage, roomState, clientId, isConnected, subscribeToMessages } = useSignaling();
    const { showToast } = useToast();

    const [localStream, setLocalStream] = useState<MediaStream | null>(null);
    const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);
    const requestingMediaRef = useRef(false);
    const cancelMediaRef = useRef(false);
    const unmountedRef = useRef(false);

    // RTC Config State
    const [rtcConfig, setRtcConfig] = useState<RTCConfiguration | null>(null);

    // Ensure media is stopped when the provider unmounts
    useEffect(() => {
        return () => {
            unmountedRef.current = true;
            stopLocalMedia();
        };
    }, []);

    // Fetch ICE Servers on mount
    useEffect(() => {
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

                const res = await fetch(apiUrl);
                if (res.ok) {
                    const data = await res.json();
                    console.log('[WebRTC] Loaded ICE Servers:', data);

                    const servers: RTCIceServer[] = [];
                    if (data.uris) {
                        servers.push({
                            urls: data.uris,
                            username: data.username,
                            credential: data.password
                        });
                    }

                    setRtcConfig({
                        iceServers: servers.length > 0 ? servers : [{ urls: 'stun:stun.l.google.com:19302' }]
                    });
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
    }, []);

    // Buffer ICE candidates if remote description not set
    const iceBufferRef = useRef<RTCIceCandidateInit[]>([]);

    // Initialize or Cleanup PC based on connection
    useEffect(() => {
        if (!isConnected) {
            cleanupPC();
        }
    }, [isConnected]);

    // Handle incoming signaling messages
    useEffect(() => {
        const handleMessage = async (msg: any) => {
            const { type, payload } = msg; // Use msg from callback
            console.log(`[WebRTC] Received message: ${type}`, payload);

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
                console.error(`[WebRTC] Error handling message ${type}:`, err);
            }
        };

        const unsubscribe = subscribeToMessages(handleMessage);
        return () => {
            unsubscribe();
        };
    }, [subscribeToMessages]); // Dependency on subscribeToMessages (stable)

    // Logic to initiate offer if we are HOST and have 2 participants
    useEffect(() => {
        if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid === clientId) {
            // ... (existing logic)
            const pc = getOrCreatePC();
            if (pc.signalingState === 'stable') {
                createOffer();
            }
        } else if (roomState && roomState.participants && roomState.participants.length < 2) {
            // Check if we need to cleanup. If we have a PC or remote stream, clean it.
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Participant left, cleaning up connection');
                cleanupPC();
            }
        }
    }, [roomState, clientId, remoteStream]);


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
        }

        pc.ontrack = (event) => {
            console.log('Remote track received', event.streams);
            if (event.streams && event.streams[0]) {
                setRemoteStream(event.streams[0]);
            }
        };

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                sendMessage('ice', { candidate: event.candidate });
            }
        };

        pc.onnegotiationneeded = () => {
            // Handled manually for now to align with protocol roles? 
            // Or we can simple trigger offer if we are host. Use variable?
        };

        return pc;
    };

    const cleanupPC = () => {
        if (pcRef.current) {
            pcRef.current.close();
            pcRef.current = null;
        }
        setRemoteStream(null);
        // We do NOT stop local stream here to allow reuse? 
        // Actually usually we stop it on leave.
    };

    const startLocalMedia = async () => {
        // If we already have (or are fetching) an active stream, reuse it to avoid creating parallel streams
        if (localStream || requestingMediaRef.current) {
            return;
        }
        cancelMediaRef.current = false;
        requestingMediaRef.current = true;
        try {
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                showToast('error', "Camera/Mic access blocked! Please ensure you are using a secure context (HTTPS or localhost).");
                requestingMediaRef.current = false;
                return;
            }
            const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });

            // If we navigated away or requested stop while the prompt was open, immediately clean up
            if (cancelMediaRef.current || unmountedRef.current) {
                stream.getTracks().forEach(t => t.stop());
                requestingMediaRef.current = false;
                return;
            }

            setLocalStream(stream);
            requestingMediaRef.current = false;

            if (pcRef.current) {
                stream.getTracks().forEach(track => {
                    pcRef.current?.addTrack(track, stream);
                });
            }
            return;
        } catch (err) {
            console.error("Error accessing media", err);
            requestingMediaRef.current = false;
        }
    };

    const stopLocalMedia = () => {
        cancelMediaRef.current = true;
        if (localStream) {
            localStream.getTracks().forEach(t => t.stop());
            setLocalStream(null);
        }
        requestingMediaRef.current = false;
    };

    const createOffer = async () => {
        try {
            console.log('[WebRTC] Creating offer...');
            const pc = getOrCreatePC();
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            console.log('[WebRTC] Sending offer');
            sendMessage('offer', { sdp: offer.sdp });
        } catch (err) {
            console.error('[WebRTC] Error creating offer:', err);
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
            peerConnection: pcRef.current
        }}>
            {children}
        </WebRTCContext.Provider>
    );
};
