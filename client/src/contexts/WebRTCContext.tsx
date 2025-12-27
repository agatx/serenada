import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useSignaling } from './SignalingContext';

// RTC Config
const rtcConfig: RTCConfiguration = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
    ],
};

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
    const { sendMessage, lastMessage, roomState, clientId, isConnected } = useSignaling();

    const [localStream, setLocalStream] = useState<MediaStream | null>(null);
    const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
    const pcRef = useRef<RTCPeerConnection | null>(null);

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
        if (!lastMessage) return;

        const handleMessage = async () => {
            const { type, payload } = lastMessage;

            switch (type) {
                case 'offer':
                    // payload has { from, sdp }
                    if (payload && payload.sdp) {
                        await handleOffer(payload.sdp);
                    }
                    break;
                case 'answer':
                    // payload has { from, sdp }
                    if (payload && payload.sdp) {
                        await handleAnswer(payload.sdp);
                    }
                    break;
                case 'ice':
                    // payload has { from, candidate }
                    if (payload && payload.candidate) {
                        await handleIce(payload.candidate);
                    }
                    break;
            }
        };
        handleMessage();
    }, [lastMessage]);

    // Logic to initiate offer if we are HOST and have 2 participants
    useEffect(() => {
        if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid === clientId) {
            // Check if we already have a connection or need to start
            // Ideally we check connectionState, but for MVP, if we are fresh, start.
            // Problem: StrictMode might trigger twice. RTCPeerConnection usually has states.
            // If we haven't negotiated, start.
            // We can check pc.signalingState
            const pc = getOrCreatePC();
            if (pc.signalingState === 'stable') {
                // We initiate offer.
                // But wait, "stable" is also the state after negotiation.
                // We need to know if we SHOULD start.
                // For MVP, if we just joined (or other just joined) and we are host.
                // Maybe rely on "negotiationneeded" event?
                // But managing perfect negotiation is hard.
                // Let's stick to: if we add tracks, negotiationneeded fires.
                // Or manually trigger createOffer.
                createOffer();
            }
        }
    }, [roomState, clientId]);


    const getOrCreatePC = () => {
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
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
            setLocalStream(stream);

            if (pcRef.current) {
                stream.getTracks().forEach(track => {
                    pcRef.current?.addTrack(track, stream);
                });
            }
            return;
        } catch (err) {
            console.error("Error accessing media", err);
        }
    };

    const stopLocalMedia = () => {
        if (localStream) {
            localStream.getTracks().forEach(t => t.stop());
            setLocalStream(null);
        }
    };

    const createOffer = async () => {
        const pc = getOrCreatePC();
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        sendMessage('offer', { sdp: offer.sdp });
    };

    const handleOffer = async (sdp: string) => {
        const pc = getOrCreatePC();
        // Check for glare/collision? Protocol says Host is Offerer. 
        // If we receive an offer, we must be the answerer (or logic failed).
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));

        // Process buffered ICE
        while (iceBufferRef.current.length > 0) {
            const c = iceBufferRef.current.shift();
            if (c) pc.addIceCandidate(c);
        }

        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        sendMessage('answer', { sdp: answer.sdp });
    };

    const handleAnswer = async (sdp: string) => {
        const pc = getOrCreatePC();
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
    };

    const handleIce = async (candidate: RTCIceCandidateInit) => {
        const pc = getOrCreatePC();
        if (pc.remoteDescription) {
            await pc.addIceCandidate(candidate);
        } else {
            iceBufferRef.current.push(candidate);
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
