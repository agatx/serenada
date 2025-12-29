import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Animated,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  useWindowDimensions,
} from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';
import { RTCView } from 'react-native-webrtc';

import { useSignaling } from '../contexts/SignalingContext';
import { useWebRTC } from '../contexts/WebRTCContext';
import { useToast } from '../contexts/ToastContext';
import { config } from '../config';

type CallRoomScreenProps = {
  roomId: string;
  onLeave: () => void;
};

const CallRoomScreen: React.FC<CallRoomScreenProps> = ({ roomId, onLeave }) => {

  const {
    joinRoom,
    leaveRoom,
    roomState,
    clientId,
    isConnected,
    error: signalingError,
    clearError,
  } = useSignaling();
  const { startLocalMedia, stopLocalMedia, localStream, remoteStream } = useWebRTC();
  const { showToast } = useToast();

  const [hasJoined, setHasJoined] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [isCameraOff, setIsCameraOff] = useState(false);
  const [areControlsVisible, setAreControlsVisible] = useState(true);

  const idleTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const controlsAnim = useRef(new Animated.Value(1)).current;
  const pipScale = useRef(new Animated.Value(1)).current;

  const shareUrl = useMemo(() => `${config.shareBaseUrl}/call/${roomId}`, [roomId]);

  useEffect(() => {
    Animated.timing(controlsAnim, {
      toValue: areControlsVisible ? 1 : 0,
      duration: 220,
      useNativeDriver: true,
    }).start();

    Animated.timing(pipScale, {
      toValue: areControlsVisible ? 1 : 0.5,
      duration: 220,
      useNativeDriver: true,
    }).start();
  }, [areControlsVisible, controlsAnim, pipScale]);

  const scheduleIdleHide = () => {
    if (idleTimeoutRef.current) {
      clearTimeout(idleTimeoutRef.current);
    }
    idleTimeoutRef.current = setTimeout(() => {
      setAreControlsVisible(false);
    }, 10000);
  };

  const clearIdleHide = () => {
    if (idleTimeoutRef.current) {
      clearTimeout(idleTimeoutRef.current);
      idleTimeoutRef.current = null;
    }
  };

  const handleScreenTap = () => {
    setAreControlsVisible(prev => {
      const next = !prev;
      if (next) {
        scheduleIdleHide();
      } else {
        clearIdleHide();
      }
      return next;
    });
  };

  const handleControlsInteraction = () => {
    setAreControlsVisible(true);
    scheduleIdleHide();
  };

  const handleAnyTouch = () => {
    if (areControlsVisible) {
      scheduleIdleHide();
    }
    return false;
  };

  const mediaStartedRef = useRef(false);

  useEffect(() => {
    if (!hasJoined && isConnected && !mediaStartedRef.current) {
      mediaStartedRef.current = true;
      startLocalMedia().catch(err => {
        console.error('Initial media start failed', err);
        mediaStartedRef.current = false;
      });
    }
  }, [hasJoined, isConnected, startLocalMedia]);

  useEffect(() => {
    return () => {
      stopLocalMedia();
      mediaStartedRef.current = false;
    };
  }, [stopLocalMedia]);

  const handleJoin = async () => {
    if (!roomId) return;
    try {
      clearError();
      await startLocalMedia();
      setTimeout(() => {
        joinRoom(roomId);
        setHasJoined(true);
        scheduleIdleHide();
      }, 50);
    } catch (err) {
      console.error('Failed to join room', err);
      showToast('error', 'Could not access camera/microphone.');
    }
  };

  useEffect(() => {
    if (signalingError && hasJoined && !roomState) {
      setHasJoined(false);
      stopLocalMedia();
    }
  }, [signalingError, hasJoined, roomState, stopLocalMedia]);

  const handleLeave = () => {
    leaveRoom();
    stopLocalMedia();
    onLeave();
  };

  const toggleMute = () => {
    if (localStream) {
      localStream.getAudioTracks().forEach(track => {
        track.enabled = !track.enabled;
      });
      setIsMuted(prev => !prev);
    }
  };

  const toggleVideo = () => {
    if (localStream) {
      localStream.getVideoTracks().forEach(track => {
        track.enabled = !track.enabled;
      });
      setIsCameraOff(prev => !prev);
    }
  };

  const copyLink = () => {
    Clipboard.setString(shareUrl);
    showToast('success', 'Link copied to clipboard!');
  };

  const { width, height } = useWindowDimensions();
  const pipWidth = Math.min(width * 0.3, 160);
  const pipHeight = Math.min(height * 0.3, 240);

  const otherParticipant = roomState?.participants?.find(p => p.cid !== clientId);

  if (!hasJoined) {
    return (
      <SafeAreaView style={styles.safeArea}>
        <View style={styles.prejoinCard}>
          <Text style={styles.prejoinTitle}>Ready to join?</Text>
          <Text style={styles.prejoinSubtitle}>Room ID: {roomId}</Text>
          {signalingError && <Text style={styles.errorText}>{signalingError}</Text>}
          <View style={styles.previewContainer}>
            {localStream ? (
              <RTCView
                streamURL={localStream.toURL()}
                style={styles.previewVideo}
                mirror
                objectFit="cover"
              />
            ) : (
              <View style={styles.previewPlaceholder}>
                <Text style={styles.previewPlaceholderText}>Camera Off</Text>
              </View>
            )}
          </View>
          <TouchableOpacity style={styles.primaryButton} onPress={handleJoin} disabled={!isConnected}>
            <Text style={styles.primaryButtonText}>{isConnected ? 'Join Call' : 'Connecting...'}</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <Pressable
      style={styles.callContainer}
      onPress={handleScreenTap}
      onStartShouldSetResponderCapture={handleAnyTouch}
    >
      {remoteStream ? (
        <RTCView
          streamURL={remoteStream.toURL()}
          style={styles.remoteVideo}
          objectFit="cover"
        />
      ) : (
        <View style={styles.remoteVideo} />
      )}
      {!remoteStream && (
        <View style={styles.waitingMessage}>
          <Text style={styles.waitingText}>
            {otherParticipant ? 'Connecting...' : 'Waiting for someone to join...'}
          </Text>
          <TouchableOpacity style={styles.secondaryButton} onPress={() => {
            handleControlsInteraction();
            copyLink();
          }}>
            <Text style={styles.secondaryButtonText}>Copy Link to Share</Text>
          </TouchableOpacity>
        </View>
      )}

      {localStream && (
        <Animated.View
          style={[
            styles.localContainer,
            {
              width: pipWidth,
              height: pipHeight,
              transform: [{ scale: pipScale }],
            },
          ]}
        >
          <RTCView
            streamURL={localStream.toURL()}
            style={styles.localVideo}
            mirror
            objectFit="cover"
          />
        </Animated.View>
      )}

      <Animated.View
        pointerEvents={areControlsVisible ? 'auto' : 'none'}
        style={[
          styles.controlsBar,
          {
            opacity: controlsAnim,
            transform: [
              {
                translateY: controlsAnim.interpolate({
                  inputRange: [0, 1],
                  outputRange: [20, 0],
                }),
              },
            ],
          },
        ]}
      >
        <TouchableOpacity style={[styles.controlButton, isMuted && styles.controlButtonActive]} onPress={() => {
          handleControlsInteraction();
          toggleMute();
        }}>
          <Text style={styles.controlButtonText}>{isMuted ? 'Mic Off' : 'Mic'}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.controlButton, isCameraOff && styles.controlButtonActive]} onPress={() => {
          handleControlsInteraction();
          toggleVideo();
        }}>
          <Text style={styles.controlButtonText}>{isCameraOff ? 'Cam Off' : 'Cam'}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.controlButton, styles.leaveButton]} onPress={() => {
          handleControlsInteraction();
          handleLeave();
        }}>
          <Text style={styles.controlButtonText}>Leave</Text>
        </TouchableOpacity>
      </Animated.View>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0b0f14',
    justifyContent: 'center',
    padding: 20,
  },
  prejoinCard: {
    backgroundColor: '#151a21',
    borderRadius: 16,
    padding: 20,
  },
  prejoinTitle: {
    fontSize: 22,
    fontWeight: '600',
    color: '#f8fafc',
  },
  prejoinSubtitle: {
    color: '#93a4b7',
    marginTop: 6,
  },
  errorText: {
    color: '#f87171',
    marginTop: 8,
  },
  previewContainer: {
    backgroundColor: '#0b0f14',
    height: 220,
    borderRadius: 12,
    overflow: 'hidden',
    marginTop: 12,
  },
  previewVideo: {
    width: '100%',
    height: '100%',
  },
  previewPlaceholder: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  previewPlaceholderText: {
    color: '#93a4b7',
  },
  primaryButton: {
    backgroundColor: '#2563eb',
    paddingVertical: 12,
    borderRadius: 999,
    alignItems: 'center',
    marginTop: 8,
  },
  primaryButtonText: {
    color: '#fff',
    fontWeight: '600',
  },
  callContainer: {
    flex: 1,
    backgroundColor: '#000',
  },
  remoteVideo: {
    flex: 1,
    backgroundColor: '#000',
  },
  localContainer: {
    position: 'absolute',
    bottom: 96,
    right: 20,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: '#1f2937',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.1)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.35,
    shadowRadius: 10,
    elevation: 8,
  },
  localVideo: {
    width: '100%',
    height: '100%',
  },
  waitingMessage: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  waitingText: {
    color: '#cbd5f5',
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 16,
  },
  secondaryButton: {
    borderWidth: 1,
    borderColor: '#cbd5f5',
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 999,
    marginTop: 16,
  },
  secondaryButtonText: {
    color: '#cbd5f5',
  },
  controlsBar: {
    position: 'absolute',
    bottom: 20,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'center',
  },
  controlButton: {
    backgroundColor: 'rgba(22, 27, 34, 0.9)',
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 999,
    marginHorizontal: 6,
  },
  controlButtonActive: {
    backgroundColor: '#fff',
  },
  leaveButton: {
    backgroundColor: '#b91c1c',
  },
  controlButtonText: {
    color: '#fff',
    fontWeight: '600',
  },
});

export default CallRoomScreen;
