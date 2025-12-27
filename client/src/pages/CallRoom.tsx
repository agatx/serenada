import React, { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useSignaling } from '../contexts/SignalingContext';
import { useWebRTC } from '../contexts/WebRTCContext';
import { useToast } from '../contexts/ToastContext';
import { Mic, MicOff, Video, VideoOff, PhoneOff, Copy, AlertCircle } from 'lucide-react';

const CallRoom: React.FC = () => {
    const { roomId } = useParams<{ roomId: string }>();
    const navigate = useNavigate();
    const {
        joinRoom,
        leaveRoom,

        roomState,
        clientId,
        isConnected,
        error: signalingError,
        clearError
    } = useSignaling();
    const {
        startLocalMedia,
        stopLocalMedia,
        localStream,
        remoteStream
    } = useWebRTC();
    const { showToast } = useToast();

    const [hasJoined, setHasJoined] = useState(false);
    const [isMuted, setIsMuted] = useState(false);
    const [isCameraOff, setIsCameraOff] = useState(false);

    const localVideoRef = useRef<HTMLVideoElement>(null);
    const remoteVideoRef = useRef<HTMLVideoElement>(null);

    // Handle stream attachment
    useEffect(() => {
        if (localVideoRef.current && localStream) {
            localVideoRef.current.srcObject = localStream;
        }
    }, [localStream, hasJoined]);

    useEffect(() => {
        if (remoteVideoRef.current && remoteStream) {
            remoteVideoRef.current.srcObject = remoteStream;
        }
    }, [remoteStream]);

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

        // Cleanup on unmount - always stop media
        return () => {
            stopLocalMedia();
            mediaStartedRef.current = false;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [hasJoined, isConnected]);

    const handleJoin = async () => {
        if (!roomId) return;
        try {
            clearError();
            await startLocalMedia();
            // Tiny delay to ensure state propagates
            setTimeout(() => {
                joinRoom(roomId);
                setHasJoined(true);
            }, 50);
        } catch (err) {
            console.error("Failed to join room", err);
            showToast('error', "Could not access camera/microphone.");
        }
    };

    // If we receive a signaling error while trying to join, or if we are joined but room state becomes null
    useEffect(() => {
        if (signalingError && hasJoined && !roomState) {
            setHasJoined(false);
            stopLocalMedia();
        }
    }, [signalingError, hasJoined, roomState, stopLocalMedia]);

    const handleLeave = () => {
        leaveRoom();
        stopLocalMedia();
        navigate('/');
    };



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
        showToast('success', "Link copied to clipboard!");
    };

    // Render Pre-Join
    if (!hasJoined) {
        return (
            <div className="page-container center-content">
                <div className="card">
                    <h2>Ready to join?</h2>
                    <p>Room ID: {roomId}</p>
                    {signalingError && (
                        <div className="error-message">
                            <AlertCircle size={20} />
                            {signalingError}
                        </div>
                    )}
                    <div className="video-preview-container">
                        <video
                            ref={localVideoRef}
                            autoPlay
                            playsInline
                            muted
                            className="video-preview"
                        />
                        {!localStream && <div className="video-placeholder">Camera Off</div>}
                    </div>
                    <div className="button-group">
                        <button className="btn-primary" onClick={handleJoin} disabled={!isConnected}>
                            {isConnected ? 'Join Call' : 'Connecting...'}
                        </button>
                        <button className="btn-secondary" onClick={copyLink}>
                            <Copy size={16} /> Copy Link
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // Render In-Call
    const otherParticipant = roomState?.participants?.find(p => p.cid !== clientId);


    return (
        <div className="call-container">
            {/* Remote Video (Full Screen) */}
            <div className="video-remote-container">
                <video
                    ref={remoteVideoRef}
                    autoPlay
                    playsInline
                    className="video-remote"
                />
                {!remoteStream && (
                    <div className="waiting-message">
                        {otherParticipant ? 'Connecting...' : 'Waiting for someone to join...'}
                        <button className="btn-small" onClick={copyLink}>Copy Link to Share</button>
                    </div>
                )}
            </div>

            {/* Local Video (PIP) */}
            <div className="video-local-container">
                <video
                    ref={localVideoRef}
                    autoPlay
                    playsInline
                    muted
                    className="video-local"
                />
            </div>

            {/* Controls */}
            <div className="controls-bar">
                <button onClick={toggleMute} className={`btn-control ${isMuted ? 'active' : ''}`}>
                    {isMuted ? <MicOff /> : <Mic />}
                </button>
                <button onClick={toggleVideo} className={`btn-control ${isCameraOff ? 'active' : ''}`}>
                    {isCameraOff ? <VideoOff /> : <Video />}
                </button>
                <button onClick={handleLeave} className="btn-control btn-leave">
                    <PhoneOff />
                </button>
            </div>
        </div>
    );
};

export default CallRoom;
