import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { PermissionsAndroid, Platform } from 'react-native';
import {
  mediaDevices,
  RTCPeerConnection,
  RTCSessionDescription,
  RTCIceCandidate,
} from 'react-native-webrtc';
import { config } from '../config';
import { useSignaling } from './SignalingContext';
import { useToast } from './ToastContext';

type MediaStream = any;

type IceServer = {
  urls: string | string[];
  username?: string;
  credential?: string;
};

type RTCConfiguration = {
  iceServers: IceServer[];
};

type IceCandidateInit = {
  candidate: string;
  sdpMid?: string;
  sdpMLineIndex?: number;
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
  const { sendMessage, roomState, clientId, isConnected, subscribeToMessages } = useSignaling();
  const { showToast } = useToast();

  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null);
  const [rtcConfig, setRtcConfig] = useState<RTCConfiguration | null>(null);

  const pcRef = useRef<RTCPeerConnection | null>(null);
  const requestingMediaRef = useRef(false);
  const cancelMediaRef = useRef(false);
  const unmountedRef = useRef(false);
  const localStreamRef = useRef<MediaStream | null>(null);
  const iceBufferRef = useRef<IceCandidateInit[]>([]);

  useEffect(() => {
    return () => {
      unmountedRef.current = true;
      stopLocalMedia();
    };
  }, [stopLocalMedia]);

  useEffect(() => {
    const fetchIceServers = async () => {
      try {
        const res = await fetch(`${config.apiBaseUrl}/api/turn-credentials`);
        if (res.ok) {
          const data = await res.json();
          const servers: IceServer[] = [];
          if (data.uris) {
            servers.push({
              urls: data.uris,
              username: data.username,
              credential: data.password,
            });
          }

          setRtcConfig({
            iceServers: servers.length > 0 ? servers : [{ urls: 'stun:stun.l.google.com:19302' }],
          });
        } else {
          setRtcConfig({
            iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
          });
        }
      } catch (err) {
        console.error('[WebRTC] Error fetching ICE servers:', err);
        setRtcConfig({
          iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
        });
      }
    };

    fetchIceServers();
  }, []);

  useEffect(() => {
    if (!isConnected) {
      cleanupPC();
    }
  }, [isConnected]);

  useEffect(() => {
    const handleMessage = async (msg: any) => {
      const { type, payload } = msg;
      console.log(`[WebRTC] Received message: ${type}`, payload);

      try {
        switch (type) {
          case 'offer':
            if (payload && payload.sdp) {
              await handleOffer(payload.sdp);
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
  }, [subscribeToMessages]);

  useEffect(() => {
    if (roomState && roomState.participants && roomState.participants.length === 2 && roomState.hostCid === clientId) {
      const pc = getOrCreatePC();
      if (pc.signalingState === 'stable') {
        createOffer();
      }
    } else if (roomState && roomState.participants && roomState.participants.length < 2) {
      if (pcRef.current || remoteStream) {
        console.log('[WebRTC] Participant left, cleaning up connection');
        cleanupPC();
      }
    }
  }, [roomState, clientId, remoteStream]);

  const getOrCreatePC = () => {
    if (!rtcConfig) {
      console.warn('getOrCreatePC called before ICE config loaded');
      throw new Error('Cannot create PC before ICE config is loaded');
    }
    if (pcRef.current) return pcRef.current;

    const pc = new RTCPeerConnection(rtcConfig);
    pcRef.current = pc;

    if (localStream) {
      localStream.getTracks().forEach(track => {
        pc.addTrack(track, localStream);
      });
    }

    pc.ontrack = event => {
      if (event.streams && event.streams[0]) {
        setRemoteStream(event.streams[0]);
      }
    };

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (pc as any).onaddstream = (event: any) => {
      if (event.stream) {
        setRemoteStream(event.stream);
      }
    };

    pc.onicecandidate = event => {
      if (event.candidate) {
        sendMessage('ice', { candidate: event.candidate });
      }
    };

    return pc;
  };

  const cleanupPC = () => {
    if (pcRef.current) {
      pcRef.current.close();
      pcRef.current = null;
    }
    setRemoteStream(null);
  };

  useEffect(() => {
    localStreamRef.current = localStream;
  }, [localStream]);

  const ensurePermissions = async () => {
    if (Platform.OS !== 'android') return true;
    const results = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.CAMERA,
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    ]);

    return (
      results[PermissionsAndroid.PERMISSIONS.CAMERA] === PermissionsAndroid.RESULTS.GRANTED &&
      results[PermissionsAndroid.PERMISSIONS.RECORD_AUDIO] === PermissionsAndroid.RESULTS.GRANTED
    );
  };

  const startLocalMedia = async () => {
    if (localStream || requestingMediaRef.current) {
      return;
    }
    cancelMediaRef.current = false;
    requestingMediaRef.current = true;

    const permissionsOk = await ensurePermissions();
    if (!permissionsOk) {
      showToast('error', 'Camera/Mic permissions are required.');
      requestingMediaRef.current = false;
      return;
    }

    try {
      const stream = await mediaDevices.getUserMedia({ video: true, audio: true });

      if (cancelMediaRef.current || unmountedRef.current) {
        stream.getTracks().forEach(track => track.stop());
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
    } catch (err) {
      console.error('Error accessing media', err);
      showToast('error', 'Could not access camera/microphone.');
      requestingMediaRef.current = false;
    }
  };

  const stopLocalMedia = useCallback(() => {
    cancelMediaRef.current = true;
    const stream = localStreamRef.current;
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
      setLocalStream(null);
    }
    requestingMediaRef.current = false;
  }, []);

  const createOffer = async () => {
    try {
      const pc = getOrCreatePC();
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      sendMessage('offer', { sdp: offer.sdp });
    } catch (err) {
      console.error('[WebRTC] Error creating offer:', err);
    }
  };

  const handleOffer = async (sdp: string) => {
    try {
      const pc = getOrCreatePC();
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));

      while (iceBufferRef.current.length > 0) {
        const candidate = iceBufferRef.current.shift();
        if (candidate) {
          await pc.addIceCandidate(new RTCIceCandidate(candidate));
        }
      }

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      sendMessage('answer', { sdp: answer.sdp });
    } catch (err) {
      console.error('[WebRTC] Error handling offer:', err);
    }
  };

  const handleAnswer = async (sdp: string) => {
    try {
      const pc = getOrCreatePC();
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
    } catch (err) {
      console.error('[WebRTC] Error handling answer:', err);
    }
  };

  const handleIce = async (candidate: IceCandidateInit) => {
    try {
      const pc = getOrCreatePC();
      if (pc.remoteDescription) {
        await pc.addIceCandidate(new RTCIceCandidate(candidate));
      } else {
        iceBufferRef.current.push(candidate);
      }
    } catch (err) {
      console.error('[WebRTC] Error handling ICE:', err);
    }
  };

  return (
    <WebRTCContext.Provider
      value={{
        localStream,
        remoteStream,
        startLocalMedia,
        stopLocalMedia,
        peerConnection: pcRef.current,
      }}
    >
      {children}
    </WebRTCContext.Provider>
  );
};
