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
    const cancelMediaRef = useRef(false);
    const unmountedRef = useRef(false);
    const localStreamRef = useRef<MediaStream | null>(null);

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
        } else if (!roomState) {
            // We left the room completely
            if (pcRef.current || remoteStream) {
                console.log('[WebRTC] Room state cleared, cleaning up connection');
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

    // Keep ref in sync with state
    useEffect(() => {
        localStreamRef.current = localStream;
    }, [localStream]);

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

    // Use useCallback to make this stable, but access stream via ref to avoid stale closure
    const stopLocalMedia = useCallback(() => {
        cancelMediaRef.current = true;
        const stream = localStreamRef.current;
        if (stream) {
            stream.getTracks().forEach(t => t.stop());
            setLocalStream(null);
        }
        requestingMediaRef.current = false;
    }, []);

    const createOffer = async () => {
        try {
            console.log('[WebRTC] Creating offer...');
            const pc = getOrCreatePC();
            const offer = await pc.createOffer();

            // Force/Prefer VP8 for compatibility with older Android devices
            const sdpWithVP8 = forceVP8(offer.sdp);
            const offerWithVP8 = { type: offer.type, sdp: sdpWithVP8 };

            await pc.setLocalDescription(offerWithVP8 as RTCSessionDescriptionInit);
            console.log('[WebRTC] Sending offer (VP8 preferred)');
            sendMessage('offer', { sdp: offerWithVP8.sdp });
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
