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

export type ConnectionStatus = 'connected' | 'recovering' | 'retrying';

interface PeerState {
    pc: RTCPeerConnection;
    remoteStream: MediaStream | null;
    iceBuffer: RTCIceCandidateInit[];
    isMakingOffer: boolean;
    offerTimeout: number | null;
    iceRestartTimer: number | null;
    lastIceRestartAt: number;
    pendingIceRestart: boolean;
    nonHostFallbackTimer: number | null;
    nonHostFallbackAttempts: number;
}

interface WebRTCContextValue {
    localStream: MediaStream | null;
    remoteStreams: Map<string, MediaStream>;
    startLocalMedia: () => Promise<MediaStream | null>;
    stopLocalMedia: () => void;
    startScreenShare: () => Promise<void>;
    stopScreenShare: () => Promise<void>;
    isScreenSharing: boolean;
    canScreenShare: boolean;
    flipCamera: () => Promise<void>;
    facingMode: 'user' | 'environment';
    hasMultipleCameras: boolean;
    peerConnections: Map<string, RTCPeerConnection>;
    iceConnectionState: RTCIceConnectionState;
    connectionState: RTCPeerConnectionState;
    signalingState: RTCSignalingState;
    connectionStatus: ConnectionStatus;
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
    const [remoteStreams, setRemoteStreams] = useState<Map<string, MediaStream>>(new Map());
    const peersRef = useRef<Map<string, PeerState>>(new Map());
    const screenShareTrackRef = useRef<MediaStreamTrack | null>(null);
    const requestingMediaRef = useRef(false);
    const unmountedRef = useRef(false);
    const localStreamRef = useRef<MediaStream | null>(null);
    const isScreenSharingRef = useRef(false);
    const isConnectedRef = useRef(isConnected);
    const connectionStatusRef = useRef<ConnectionStatus>('connected');
    const retryingTimerRef = useRef<number | null>(null);

    // RTC Config State — init with default STUN immediately (non-blocking ICE bootstrap)
    const [rtcConfig, setRtcConfig] = useState<RTCConfiguration>(DEFAULT_RTC_CONFIG);
    const rtcConfigRef = useRef<RTCConfiguration>(DEFAULT_RTC_CONFIG);
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
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('connected');
    const [peerConnectionsVersion, setPeerConnectionsVersion] = useState(0);

    // Derived: expose peer connections map (rebuilds when version bumps)
    const peerConnections = React.useMemo(() => {
        void peerConnectionsVersion; // trigger recompute
        const map = new Map<string, RTCPeerConnection>();
        for (const [cid, ps] of peersRef.current) map.set(cid, ps.pc);
        return map;
    }, [peerConnectionsVersion]);

    // Helpers to reduce duplication
    const clearPeerTimers = (peer: PeerState) => {
        if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        if (peer.iceRestartTimer) { window.clearTimeout(peer.iceRestartTimer); peer.iceRestartTimer = null; }
        if (peer.nonHostFallbackTimer) { window.clearTimeout(peer.nonHostFallbackTimer); peer.nonHostFallbackTimer = null; }
    };

    const bumpPeerConnectionsVersion = () => setPeerConnectionsVersion(v => v + 1);

    // Keep refs in sync
    useEffect(() => { isConnectedRef.current = isConnected; }, [isConnected]);
    useEffect(() => { facingModeRef.current = facingMode; }, [facingMode]);
    useEffect(() => { isScreenSharingRef.current = isScreenSharing; }, [isScreenSharing]);
    useEffect(() => { localStreamRef.current = localStream; }, [localStream]);
    useEffect(() => { roomStateRef.current = roomState; }, [roomState]);
    useEffect(() => { clientIdRef.current = clientId; }, [clientId]);
    useEffect(() => { rtcConfigRef.current = rtcConfig; }, [rtcConfig]);
    useEffect(() => { setCanScreenShare(!!navigator.mediaDevices?.getDisplayMedia); }, []);

    // --- Aggregate connection state from all peers (with change guards) ---
    const iceConnectionStateRef = useRef<RTCIceConnectionState>('new');
    const connectionStateRef = useRef<RTCPeerConnectionState>('new');
    const signalingStateRef = useRef<RTCSignalingState>('stable');

    const updateAggregateState = useCallback(() => {
        const peers = peersRef.current;
        let worstIce: RTCIceConnectionState = peers.size === 0 ? 'new' : 'completed';
        let worstConn: RTCPeerConnectionState = peers.size === 0 ? 'new' : 'connected';
        let worstSig: RTCSignalingState = peers.size === 0 ? 'stable' : 'stable';

        if (peers.size > 0) {
            const iceOrder: RTCIceConnectionState[] = ['failed', 'disconnected', 'checking', 'new', 'connected', 'completed', 'closed'];
            const connOrder: RTCPeerConnectionState[] = ['failed', 'disconnected', 'connecting', 'new', 'connected', 'closed'];
            const sigOrder: RTCSignalingState[] = ['closed', 'have-local-offer', 'have-remote-offer', 'have-local-pranswer', 'have-remote-pranswer', 'stable'];

            for (const [, peer] of peers) {
                const ice = peer.pc.iceConnectionState;
                const conn = peer.pc.connectionState;
                const sig = peer.pc.signalingState;
                if (iceOrder.indexOf(ice) < iceOrder.indexOf(worstIce)) worstIce = ice;
                if (connOrder.indexOf(conn) < connOrder.indexOf(worstConn)) worstConn = conn;
                if (sigOrder.indexOf(sig) < sigOrder.indexOf(worstSig)) worstSig = sig;
            }
        }

        if (iceConnectionStateRef.current !== worstIce) { iceConnectionStateRef.current = worstIce; setIceConnectionState(worstIce); }
        if (connectionStateRef.current !== worstConn) { connectionStateRef.current = worstConn; setConnectionState(worstConn); }
        if (signalingStateRef.current !== worstSig) { signalingStateRef.current = worstSig; setSignalingState(worstSig); }
    }, []);

    // --- Connection status machine ---
    const setConnectionStatusValue = (nextStatus: ConnectionStatus) => {
        if (connectionStatusRef.current === nextStatus) return;
        connectionStatusRef.current = nextStatus;
        setConnectionStatus(nextStatus);
    };

    const resetConnectionStatusMachine = () => {
        if (retryingTimerRef.current) {
            window.clearTimeout(retryingTimerRef.current);
            retryingTimerRef.current = null;
        }
        setConnectionStatusValue('connected');
    };

    const isConnectionStatusMachineActive = (state = roomStateRef.current) => {
        return !!state && (state.participants?.length ?? 0) > 1;
    };

    const scheduleRetryingTransition = () => {
        if (retryingTimerRef.current) return;
        retryingTimerRef.current = window.setTimeout(() => {
            retryingTimerRef.current = null;
            if (connectionStatusRef.current === 'recovering' && isConnectionStatusMachineActive()) {
                setConnectionStatusValue('retrying');
            }
        }, 10_000);
    };

    const setConnectionRecovering = (state = roomStateRef.current) => {
        if (!isConnectionStatusMachineActive(state)) {
            resetConnectionStatusMachine();
            return;
        }
        if (connectionStatusRef.current === 'connected') {
            setConnectionStatusValue('recovering');
        }
        if (connectionStatusRef.current !== 'retrying') {
            scheduleRetryingTransition();
        }
    };

    const updateConnectionStatus = useCallback((
        currentIceState: RTCIceConnectionState,
        currentConnectionState: RTCPeerConnectionState,
        state = roomStateRef.current
    ) => {
        if (!isConnectionStatusMachineActive(state)) {
            resetConnectionStatusMachine();
            return;
        }
        const isDegraded =
            !isConnectedRef.current ||
            currentIceState === 'disconnected' ||
            currentIceState === 'failed' ||
            currentConnectionState === 'disconnected' ||
            currentConnectionState === 'failed';
        if (isDegraded) {
            setConnectionRecovering(state);
            return;
        }
        resetConnectionStatusMachine();
    }, []);

    useEffect(() => {
        updateConnectionStatus(iceConnectionState, connectionState, roomState);
    }, [isConnected, iceConnectionState, connectionState, roomState, updateConnectionStatus]);

    // --- ICE restart (per-peer) ---
    const scheduleIceRestart = (remoteCid: string, reason: string, delayMs: number) => {
        const peer = peersRef.current.get(remoteCid);
        if (!peer) return;
        if (!isConnectedRef.current) {
            peer.pendingIceRestart = true;
            return;
        }
        if (peer.iceRestartTimer) return;
        const now = Date.now();
        if (now - peer.lastIceRestartAt < ICE_RESTART_COOLDOWN_MS) return;

        peer.iceRestartTimer = window.setTimeout(() => {
            peer.iceRestartTimer = null;
            void triggerIceRestart(remoteCid, reason);
        }, delayMs);
    };

    const triggerIceRestart = async (remoteCid: string, reason: string) => {
        const peer = peersRef.current.get(remoteCid);
        if (!peer) return;
        if (!isConnectedRef.current) {
            peer.pendingIceRestart = true;
            return;
        }
        // Determine if we're the offerer for this peer
        if (!shouldIOffer(remoteCid)) return;
        if (peer.isMakingOffer) {
            peer.pendingIceRestart = true;
            return;
        }
        peer.lastIceRestartAt = Date.now();
        peer.pendingIceRestart = false;
        console.warn(`[WebRTC] ICE restart triggered for ${remoteCid} (${reason})`);
        await createOfferTo(remoteCid, { iceRestart: true });
    };

    // --- Determine offer direction: existing participants offer to newcomers ---
    const shouldIOffer = (remoteCid: string): boolean => {
        const state = roomStateRef.current;
        if (!state) return false;
        const myId = clientIdRef.current;
        if (!myId) return false;
        const myParticipant = state.participants?.find(p => p.cid === myId);
        const theirParticipant = state.participants?.find(p => p.cid === remoteCid);
        if (!myParticipant || !theirParticipant) return false;
        const myJoinedAt = myParticipant.joinedAt ?? 0;
        const theirJoinedAt = theirParticipant.joinedAt ?? 0;
        return myJoinedAt < theirJoinedAt || (myJoinedAt === theirJoinedAt && myId < remoteCid);
    };

    // --- Helpers ---
    const applySpeechTrackHints = (stream: MediaStream) => {
        const audioTrack = stream.getAudioTracks()[0];
        if (!audioTrack) return;
        if ('contentHint' in audioTrack) {
            try { audioTrack.contentHint = 'speech'; } catch { /* ignore */ }
        }
    };

    const applyAudioSenderParameters = async (pc: RTCPeerConnection) => {
        const sender = pc.getSenders().find(s => s.track?.kind === 'audio');
        if (!sender?.getParameters || !sender?.setParameters) return;
        try {
            const params = sender.getParameters();
            if (!params.encodings || params.encodings.length === 0) params.encodings = [{}];
            if (params.encodings[0]) params.encodings[0].maxBitrate = 32000;
            await sender.setParameters(params);
        } catch (err) {
            console.warn('[WebRTC] Failed to apply audio sender parameters', err);
        }
    };

    // --- Peer lifecycle ---
    const getOrCreatePeer = (remoteCid: string): PeerState => {
        const existing = peersRef.current.get(remoteCid);
        if (existing) return existing;

        const pc = new RTCPeerConnection(rtcConfigRef.current);
        const peerState: PeerState = {
            pc,
            remoteStream: null,
            iceBuffer: [],
            isMakingOffer: false,
            offerTimeout: null,
            iceRestartTimer: null,
            lastIceRestartAt: 0,
            pendingIceRestart: false,
            nonHostFallbackTimer: null,
            nonHostFallbackAttempts: 0,
        };

        // Add local tracks
        const stream = localStreamRef.current;
        if (stream) {
            stream.getTracks().forEach(track => pc.addTrack(track, stream));
            void applyAudioSenderParameters(pc);
        }

        pc.ontrack = (event) => {
            console.log(`[WebRTC][${remoteCid}] Remote track received`, event.streams);
            let remoteStream: MediaStream;
            if (event.streams?.[0]) {
                remoteStream = event.streams[0];
            } else {
                // Safari fallback
                remoteStream = peerState.remoteStream || new MediaStream();
                if (!remoteStream.getTracks().some(t => t.id === event.track.id)) {
                    remoteStream.addTrack(event.track);
                }
            }
            peerState.remoteStream = remoteStream;
            setRemoteStreams(prev => {
                if (prev.get(remoteCid) === remoteStream) return prev;
                return new Map(prev).set(remoteCid, remoteStream);
            });
        };

        pc.oniceconnectionstatechange = () => {
            console.log(`[WebRTC][${remoteCid}] ICE: ${pc.iceConnectionState}`);
            updateAggregateState();
            if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                if (peerState.iceRestartTimer) { window.clearTimeout(peerState.iceRestartTimer); peerState.iceRestartTimer = null; }
                peerState.pendingIceRestart = false;
                return;
            }
            if (pc.iceConnectionState === 'disconnected') {
                scheduleIceRestart(remoteCid, 'ice-disconnected', 2000);
            } else if (pc.iceConnectionState === 'failed') {
                scheduleIceRestart(remoteCid, 'ice-failed', 0);
            }
        };

        pc.onconnectionstatechange = () => {
            console.log(`[WebRTC][${remoteCid}] Connection: ${pc.connectionState}`);
            updateAggregateState();
            if (pc.connectionState === 'connected') {
                if (peerState.iceRestartTimer) { window.clearTimeout(peerState.iceRestartTimer); peerState.iceRestartTimer = null; }
                peerState.pendingIceRestart = false;
                return;
            }
            if (pc.connectionState === 'disconnected') {
                scheduleIceRestart(remoteCid, 'conn-disconnected', 2000);
            } else if (pc.connectionState === 'failed') {
                scheduleIceRestart(remoteCid, 'conn-failed', 0);
            }
        };

        pc.onsignalingstatechange = () => {
            console.log(`[WebRTC][${remoteCid}] Signaling: ${pc.signalingState}`);
            updateAggregateState();
            if (pc.signalingState === 'stable') {
                if (peerState.offerTimeout) { window.clearTimeout(peerState.offerTimeout); peerState.offerTimeout = null; }
            }
            if (pc.signalingState === 'stable' && peerState.pendingIceRestart) {
                if (peerState.offerTimeout) { window.clearTimeout(peerState.offerTimeout); peerState.offerTimeout = null; }
                if (!isConnectedRef.current || !shouldIOffer(remoteCid)) return;
                peerState.pendingIceRestart = false;
                peerState.lastIceRestartAt = Date.now();
                void createOfferTo(remoteCid, { iceRestart: true });
            }
        };

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                sendMessage('ice', { candidate: event.candidate }, remoteCid);
            }
        };

        pc.onnegotiationneeded = async () => {
            if (!shouldIOffer(remoteCid)) return;
            await createOfferTo(remoteCid);
        };

        peersRef.current.set(remoteCid, peerState);
        bumpPeerConnectionsVersion();

        return peerState;
    };

    const cleanupPeer = (remoteCid: string) => {
        const peer = peersRef.current.get(remoteCid);
        if (!peer) return;
        clearPeerTimers(peer);
        peer.pc.close();
        peersRef.current.delete(remoteCid);
        setRemoteStreams(prev => {
            const next = new Map(prev);
            next.delete(remoteCid);
            return next;
        });
        bumpPeerConnectionsVersion();
        updateAggregateState();
    };

    const cleanupAllPeers = () => {
        for (const [, peer] of peersRef.current) {
            clearPeerTimers(peer);
            peer.pc.close();
        }
        peersRef.current.clear();
        setRemoteStreams(new Map());
        bumpPeerConnectionsVersion();
        if (retryingTimerRef.current) {
            window.clearTimeout(retryingTimerRef.current);
            retryingTimerRef.current = null;
        }
        setIceConnectionState('closed');
        setConnectionState('closed');
        setSignalingState('closed');
        setConnectionStatusValue('connected');
    };

    // --- Offer / Answer / ICE ---
    const createOfferTo = async (remoteCid: string, options?: { iceRestart?: boolean }) => {
        const peer = peersRef.current.get(remoteCid);
        if (!peer) return;
        if (peer.isMakingOffer) {
            if (options?.iceRestart) peer.pendingIceRestart = true;
            return;
        }
        try {
            const { pc } = peer;
            if (pc.signalingState !== 'stable') {
                if (options?.iceRestart) peer.pendingIceRestart = true;
                return;
            }
            peer.isMakingOffer = true;
            console.log(`[WebRTC][${remoteCid}] Creating offer...`);
            const offer = await pc.createOffer(options);
            await pc.setLocalDescription(offer as RTCSessionDescriptionInit);
            console.log(`[WebRTC][${remoteCid}] Sending offer`);
            sendMessage('offer', { sdp: offer.sdp }, remoteCid);

            if (peer.offerTimeout) window.clearTimeout(peer.offerTimeout);
            peer.offerTimeout = window.setTimeout(() => {
                peer.offerTimeout = null;
                const currentPeer = peersRef.current.get(remoteCid);
                if (!currentPeer) return;
                console.warn(`[WebRTC][${remoteCid}] Offer timeout; signalingState=${currentPeer.pc.signalingState}`);
                currentPeer.pendingIceRestart = true;
                if (currentPeer.pc.signalingState === 'have-local-offer') {
                    currentPeer.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit)
                        .catch(err => console.warn(`[WebRTC][${remoteCid}] Rollback failed`, err))
                        .finally(() => scheduleIceRestart(remoteCid, 'offer-timeout', 0));
                } else {
                    scheduleIceRestart(remoteCid, 'offer-timeout-unexpected-state', 0);
                }
            }, OFFER_TIMEOUT_MS);
        } catch (err) {
            console.error(`[WebRTC][${remoteCid}] Error creating offer:`, err);
        } finally {
            peer.isMakingOffer = false;
            if (peer.pendingIceRestart) {
                peer.pendingIceRestart = false;
                scheduleIceRestart(remoteCid, 'pending-retry', 500);
            }
        }
    };

    const scheduleNonHostFallback = (remoteCid: string) => {
        if (shouldIOffer(remoteCid)) return;
        const peer = peersRef.current.get(remoteCid);
        if (!peer) return;
        if (peer.nonHostFallbackTimer) return;
        if (peer.nonHostFallbackAttempts >= NON_HOST_FALLBACK_MAX_ATTEMPTS) return;

        peer.nonHostFallbackTimer = window.setTimeout(async () => {
            peer.nonHostFallbackTimer = null;
            const currentPeer = peersRef.current.get(remoteCid);
            if (!currentPeer) return;
            if (shouldIOffer(remoteCid)) return;
            if (currentPeer.pc.remoteDescription) return;
            if (currentPeer.pc.signalingState !== 'stable') return;
            if (!isConnectedRef.current) return;

            currentPeer.nonHostFallbackAttempts++;
            console.warn(`[WebRTC][${remoteCid}] Non-host fallback offer (attempt ${currentPeer.nonHostFallbackAttempts})`);
            try {
                const offer = await currentPeer.pc.createOffer();
                await currentPeer.pc.setLocalDescription(offer as RTCSessionDescriptionInit);
                sendMessage('offer', { sdp: offer.sdp }, remoteCid);

                if (currentPeer.offerTimeout) window.clearTimeout(currentPeer.offerTimeout);
                currentPeer.offerTimeout = window.setTimeout(async () => {
                    currentPeer.offerTimeout = null;
                    const p = peersRef.current.get(remoteCid);
                    if (!p) return;
                    if (p.pc.signalingState === 'have-local-offer') {
                        try {
                            await p.pc.setLocalDescription({ type: 'rollback' } as RTCSessionDescriptionInit);
                        } catch (err) {
                            console.warn(`[WebRTC][${remoteCid}] Non-host rollback failed`, err);
                        }
                    }
                    scheduleNonHostFallback(remoteCid);
                }, OFFER_TIMEOUT_MS);
            } catch (err) {
                console.error(`[WebRTC][${remoteCid}] Non-host fallback offer failed`, err);
                scheduleNonHostFallback(remoteCid);
            }
        }, NON_HOST_FALLBACK_DELAY_MS);
    };

    const handleOfferFrom = async (fromCid: string, sdp: string) => {
        try {
            console.log(`[WebRTC][${fromCid}] Handling offer...`);
            const peer = getOrCreatePeer(fromCid);
            // Cancel non-host fallback
            if (peer.nonHostFallbackTimer) {
                window.clearTimeout(peer.nonHostFallbackTimer);
                peer.nonHostFallbackTimer = null;
            }
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
            if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }

            // Process buffered ICE
            while (peer.iceBuffer.length > 0) {
                const c = peer.iceBuffer.shift();
                if (c) await peer.pc.addIceCandidate(c);
            }

            const answer = await peer.pc.createAnswer();
            await peer.pc.setLocalDescription(answer);
            console.log(`[WebRTC][${fromCid}] Sending answer`);
            sendMessage('answer', { sdp: answer.sdp }, fromCid);
        } catch (err) {
            console.error(`[WebRTC][${fromCid}] Error handling offer:`, err);
        }
    };

    const handleAnswerFrom = async (fromCid: string, sdp: string) => {
        try {
            console.log(`[WebRTC][${fromCid}] Handling answer...`);
            const peer = peersRef.current.get(fromCid);
            if (!peer) {
                console.warn(`[WebRTC][${fromCid}] No peer for answer`);
                return;
            }
            // Cancel non-host fallback
            if (peer.nonHostFallbackTimer) {
                window.clearTimeout(peer.nonHostFallbackTimer);
                peer.nonHostFallbackTimer = null;
            }
            await peer.pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
            if (peer.offerTimeout) { window.clearTimeout(peer.offerTimeout); peer.offerTimeout = null; }
        } catch (err) {
            console.error(`[WebRTC][${fromCid}] Error handling answer:`, err);
        }
    };

    const handleIceFrom = async (fromCid: string, candidate: RTCIceCandidateInit) => {
        try {
            const peer = getOrCreatePeer(fromCid);
            if (peer.pc.remoteDescription) {
                await peer.pc.addIceCandidate(candidate);
            } else {
                if (peer.iceBuffer.length >= ICE_CANDIDATE_BUFFER_MAX) {
                    peer.iceBuffer.shift();
                }
                peer.iceBuffer.push(candidate);
            }
        } catch (err) {
            console.error(`[WebRTC][${fromCid}] Error handling ICE:`, err);
        }
    };

    // --- Signaling message routing ---
    const processSignalingMessage = useCallback(async (msg: any) => {
        const { type, payload } = msg;
        if (!payload) return;
        const fromCid = payload.from;
        try {
            switch (type) {
                case 'offer':
                    if (fromCid && payload.sdp) {
                        await handleOfferFrom(fromCid, payload.sdp);
                    }
                    break;
                case 'answer':
                    if (fromCid && payload.sdp) {
                        await handleAnswerFrom(fromCid, payload.sdp);
                    }
                    break;
                case 'ice':
                    if (fromCid && payload.candidate) {
                        await handleIceFrom(fromCid, payload.candidate);
                    }
                    break;
            }
        } catch (err) {
            console.error(`[WebRTC] Error processing message ${type}:`, err);
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []); // Handlers use refs (peersRef, roomStateRef, rtcConfigRef), not state directly

    useEffect(() => {
        const unsubscribe = subscribeToMessages((msg: any) => {
            processSignalingMessage(msg);
        });
        return unsubscribe;
    }, [subscribeToMessages, processSignalingMessage]);

    // --- Room state effect: manage peers based on participant changes ---
    useEffect(() => {
        const myId = clientId;
        if (!roomState || !myId) {
            if (peersRef.current.size > 0) {
                console.log('[WebRTC] Room state cleared, cleaning up all peers');
                cleanupAllPeers();
            }
            return;
        }

        const remotePeers = roomState.participants?.filter(p => p.cid !== myId) ?? [];
        const remoteCids = new Set(remotePeers.map(p => p.cid));

        // Cleanup peers for departed participants
        for (const [cid] of peersRef.current) {
            if (!remoteCids.has(cid)) {
                console.log(`[WebRTC] Participant ${cid} left, cleaning up peer`);
                cleanupPeer(cid);
            }
        }

        // Create peers for new participants
        for (const peer of remotePeers) {
            if (!peersRef.current.has(peer.cid)) {
                getOrCreatePeer(peer.cid);
                if (shouldIOffer(peer.cid)) {
                    // I was here first — send offer
                    const peerState = peersRef.current.get(peer.cid);
                    if (peerState && peerState.pc.signalingState === 'stable' && !peerState.pc.remoteDescription) {
                        void createOfferTo(peer.cid);
                    }
                } else {
                    // They were here first — wait for their offer, schedule fallback
                    scheduleNonHostFallback(peer.cid);
                }
            }
        }
    }, [roomState, clientId, rtcConfig]);

    // --- Pending ICE restart on reconnect ---
    useEffect(() => {
        if (!isConnected) return;
        for (const [cid, peer] of peersRef.current) {
            if (peer.pendingIceRestart && shouldIOffer(cid) && peer.pc.signalingState === 'stable') {
                peer.pendingIceRestart = false;
                peer.lastIceRestartAt = Date.now();
                void createOfferTo(cid, { iceRestart: true });
            }
        }
    }, [isConnected]);

    // --- Camera detection ---
    const detectCameras = useCallback(async () => {
        if (!navigator.mediaDevices?.enumerateDevices) return;
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            setHasMultipleCameras(devices.filter(d => d.kind === 'videoinput').length > 1);
        } catch { /* ignore */ }
    }, []);

    useEffect(() => {
        detectCameras();
        navigator.mediaDevices?.addEventListener?.('devicechange', detectCameras);
        return () => { navigator.mediaDevices?.removeEventListener?.('devicechange', detectCameras); };
    }, [detectCameras]);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            unmountedRef.current = true;
            if (retryingTimerRef.current) {
                window.clearTimeout(retryingTimerRef.current);
                retryingTimerRef.current = null;
            }
            stopLocalMedia();
        };
    }, []);

    // --- TURN fetching ---
    useEffect(() => {
        if (!turnToken) return;
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
                            uris = uris.filter((u: string) => u.startsWith('turns:'));
                        }
                        if (uris.length > 0) {
                            servers.push({ urls: uris, username: data.username, credential: data.password });
                        }
                    }

                    const config: RTCConfiguration = {
                        iceServers: servers.length > 0 ? servers : DEFAULT_RTC_CONFIG.iceServers
                    };
                    if (turnsOnly) config.iceTransportPolicy = 'relay';
                    setRtcConfig(config);
                }
            } catch (err) {
                if (!controller.signal.aborted) console.error('[WebRTC] Error fetching ICE servers:', err);
            }
        };
        fetchIceServers();
        return () => controller.abort();
    }, [turnToken]);

    // --- Network change handlers ---
    useEffect(() => {
        const handleOnline = () => {
            for (const [cid] of peersRef.current) {
                scheduleIceRestart(cid, 'network-online', 0);
            }
        };
        const handleNetworkChange = () => {
            for (const [cid] of peersRef.current) {
                scheduleIceRestart(cid, 'network-change', 0);
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

    // --- Local media ---
    const mediaRequestIdRef = useRef<number>(0);

    const startLocalMedia = useCallback(async (): Promise<MediaStream | null> => {
        const requestId = mediaRequestIdRef.current + 1;
        mediaRequestIdRef.current = requestId;

        if (localStream) return localStream;

        requestingMediaRef.current = true;
        try {
            if (!navigator.mediaDevices?.getUserMedia) {
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
            } catch {
                stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
            }

            if (unmountedRef.current || mediaRequestIdRef.current !== requestId) {
                stream.getTracks().forEach(t => t.stop());
                return null;
            }

            applySpeechTrackHints(stream);
            setLocalStream(stream);
            await detectCameras();
            requestingMediaRef.current = false;

            // Add tracks to all existing peers
            for (const [, peer] of peersRef.current) {
                stream.getTracks().forEach(track => peer.pc.addTrack(track, stream));
                void applyAudioSenderParameters(peer.pc);
            }
            return stream;
        } catch (err) {
            console.error("Error accessing media", err);
            requestingMediaRef.current = false;
            return null;
        }
    }, [localStream, facingMode, showToast, t]);

    const stopLocalMedia = useCallback(() => {
        mediaRequestIdRef.current += 1;
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

    // Helper: replace video track across all peer connections
    const replaceVideoTrackOnAllPeers = async (newTrack: MediaStreamTrack | null) => {
        for (const [, peer] of peersRef.current) {
            const sender = peer.pc.getSenders().find(s => s.track?.kind === 'video');
            if (sender) {
                try { await sender.replaceTrack(newTrack); } catch (err) {
                    console.warn('[WebRTC] Failed to replace track on peer', err);
                }
            }
        }
    };

    const stopScreenShare = useCallback(async () => {
        if (!isScreenSharingRef.current) return;
        const currentStream = localStreamRef.current;
        if (!currentStream) { setIsScreenSharing(false); return; }

        const screenShareTrack = screenShareTrackRef.current;
        if (screenShareTrack) {
            screenShareTrack.onended = null;
            screenShareTrackRef.current = null;
        }

        const previousVideoTrack = currentStream.getVideoTracks()[0];
        const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;

        try {
            const cameraStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: facingModeRef.current }, audio: false
            });
            const cameraTrack = cameraStream.getVideoTracks()[0];
            if (!cameraTrack) throw new Error('No camera track returned');
            cameraTrack.enabled = wasVideoEnabled;

            await replaceVideoTrackOnAllPeers(cameraTrack);

            setLocalStream(new MediaStream([cameraTrack, ...currentStream.getAudioTracks()]));
            if (previousVideoTrack) previousVideoTrack.stop();
            setIsScreenSharing(false);
        } catch (err) {
            console.error('[WebRTC] Failed to stop screen share and restore camera', err);
            await replaceVideoTrackOnAllPeers(null);
            if (previousVideoTrack) previousVideoTrack.stop();
            setLocalStream(new MediaStream([...currentStream.getAudioTracks()]));
            setIsScreenSharing(false);
            showToast('error', t('toast_camera_error'));
        }
    }, [showToast, t]);

    const startScreenShare = useCallback(async () => {
        if (isScreenSharingRef.current || !canScreenShare) return;
        const currentStream = localStreamRef.current;
        if (!currentStream) return;

        try {
            const displayStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
            const displayTrack = displayStream.getVideoTracks()[0];
            if (!displayTrack) {
                displayStream.getTracks().forEach(track => track.stop());
                throw new Error('No display track returned');
            }

            const previousVideoTrack = currentStream.getVideoTracks()[0];
            const wasVideoEnabled = previousVideoTrack ? previousVideoTrack.enabled : true;
            displayTrack.enabled = wasVideoEnabled;
            if ('contentHint' in displayTrack) {
                try { displayTrack.contentHint = 'detail'; } catch { /* ignore */ }
            }

            await replaceVideoTrackOnAllPeers(displayTrack);

            if (screenShareTrackRef.current) screenShareTrackRef.current.onended = null;
            screenShareTrackRef.current = displayTrack;
            displayTrack.onended = () => { void stopScreenShare(); };

            setLocalStream(new MediaStream([displayTrack, ...currentStream.getAudioTracks()]));
            if (previousVideoTrack) previousVideoTrack.stop();
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
            const oldVideoTrack = localStream.getVideoTracks()[0];
            if (oldVideoTrack) oldVideoTrack.stop();

            const newStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: newMode }, audio: false
            });
            const newVideoTrack = newStream.getVideoTracks()[0];

            await replaceVideoTrackOnAllPeers(newVideoTrack);

            setLocalStream(new MediaStream([newVideoTrack, ...localStream.getAudioTracks()]));
        } catch (err) {
            console.error('[WebRTC] Failed to flip camera', err);
            showToast('error', t('toast_flip_camera_error'));
        }
    };

    return (
        <WebRTCContext.Provider value={{
            localStream,
            remoteStreams,
            startLocalMedia,
            stopLocalMedia,
            startScreenShare,
            stopScreenShare,
            isScreenSharing,
            canScreenShare,
            flipCamera,
            facingMode,
            hasMultipleCameras,
            peerConnections,
            iceConnectionState,
            connectionState,
            signalingState,
            connectionStatus
        }}>
            {children}
        </WebRTCContext.Provider>
    );
};
