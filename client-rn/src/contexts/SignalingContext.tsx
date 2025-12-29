import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { config } from '../config';
import { useToast } from './ToastContext';

export type RoomState = {
  hostCid: string | null;
  participants: { cid: string; joinedAt?: number }[];
};

export type SignalingMessage = {
  v: number;
  type: string;
  rid?: string;
  sid?: string;
  cid?: string;
  to?: string;
  payload?: any;
};

interface SignalingContextValue {
  isConnected: boolean;
  clientId: string | null;
  roomState: RoomState | null;
  joinRoom: (roomId: string) => void;
  leaveRoom: () => void;
  endRoom: () => void;
  sendMessage: (type: string, payload?: any, to?: string) => void;
  lastMessage: SignalingMessage | null;
  subscribeToMessages: (cb: (msg: SignalingMessage) => void) => () => void;
  error: string | null;
  clearError: () => void;
}

const SignalingContext = createContext<SignalingContextValue | null>(null);

export const useSignaling = () => {
  const context = useContext(SignalingContext);
  if (!context) {
    throw new Error('useSignaling must be used within a SignalingProvider');
  }
  return context;
};

export const SignalingProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isConnected, setIsConnected] = useState(false);
  const [clientId, setClientId] = useState<string | null>(null);
  const [roomState, setRoomState] = useState<RoomState | null>(null);
  const [lastMessage, setLastMessage] = useState<SignalingMessage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { showToast } = useToast();

  const listenersRef = useRef<((msg: SignalingMessage) => void)[]>([]);
  const wsRef = useRef<WebSocket | null>(null);
  const currentRoomIdRef = useRef<string | null>(null);
  const pendingJoinRef = useRef<string | null>(null);

  useEffect(() => {
    const reconnectAttemptsRef = { current: 0 };
    let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
    let closedByUnmount = false;

    const clearReconnectTimeout = () => {
      if (reconnectTimeout !== null) {
        clearTimeout(reconnectTimeout);
        reconnectTimeout = null;
      }
    };

    const scheduleReconnect = () => {
      if (closedByUnmount) return;
      if (reconnectTimeout !== null) return;
      const attempt = reconnectAttemptsRef.current + 1;
      reconnectAttemptsRef.current = attempt;
      const backoff = Math.min(500 * Math.pow(2, attempt - 1), 5000);

      reconnectTimeout = setTimeout(() => {
        reconnectTimeout = null;
        connect();
      }, backoff);
    };

    const connect = () => {
      if (closedByUnmount) return;
      if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
        return;
      }

      const ws = new WebSocket(config.wsUrl);
      wsRef.current = ws;

      const handleDisconnect = (reason: string, err?: any) => {
        if (closedByUnmount) return;
        console.error(`[WS] Disconnected via ${reason}`, err);
        setIsConnected(false);
        setClientId(null);
        setRoomState(null);
        wsRef.current = null;
        scheduleReconnect();
      };

      ws.onopen = () => {
        console.log('WS Connected');
        reconnectAttemptsRef.current = 0;
        setIsConnected(true);
        if (pendingJoinRef.current) {
          joinRoom(pendingJoinRef.current);
          pendingJoinRef.current = null;
        } else if (currentRoomIdRef.current) {
          joinRoom(currentRoomIdRef.current);
        }
      };

      ws.onclose = event => {
        handleDisconnect('close', event);
      };

      ws.onerror = event => {
        handleDisconnect('error', event);
      };

      ws.onmessage = event => {
        try {
          const msg: SignalingMessage = JSON.parse(event.data);
          console.log('RX:', msg);

          switch (msg.type) {
            case 'joined':
              if (msg.cid) setClientId(msg.cid);
              if (msg.payload) {
                setRoomState(msg.payload as RoomState);
              }
              break;
            case 'room_state':
              if (msg.payload) {
                setRoomState(msg.payload as RoomState);
              }
              break;
            case 'room_ended':
              setRoomState(null);
              currentRoomIdRef.current = null;
              break;
            case 'error':
              if (msg.payload && msg.payload.message) {
                setError(msg.payload.message);
                showToast('error', msg.payload.message);
              }
              break;
          }

          setLastMessage(msg);
          listenersRef.current.forEach(listener => listener(msg));
        } catch (err) {
          console.error('Failed to parse message', err);
        }
      };
    };

    connect();

    return () => {
      closedByUnmount = true;
      clearReconnectTimeout();
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, []);

  const sendMessage = (type: string, payload?: any, to?: string) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      const realMsg = {
        v: 1,
        type,
        rid: currentRoomIdRef.current || undefined,
        cid: clientId || undefined,
        to,
        payload,
      };

      console.log('TX:', realMsg);
      wsRef.current.send(JSON.stringify(realMsg));
    } else {
      console.warn('WS not connected');
    }
  };

  const clearError = () => setError(null);

  const joinRoom = (roomId: string) => {
    console.log(`[Signaling] joinRoom call for ${roomId}`);
    setError(null);
    currentRoomIdRef.current = roomId;
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      sendMessage('join', { capabilities: { trickleIce: true } });
    } else {
      console.log('[Signaling] WS not ready, buffering join');
      pendingJoinRef.current = roomId;
    }
  };

  const leaveRoom = () => {
    sendMessage('leave');
    currentRoomIdRef.current = null;
    setRoomState(null);
  };

  const endRoom = () => {
    sendMessage('end_room');
  };

  const subscribeToMessages = (cb: (msg: SignalingMessage) => void) => {
    listenersRef.current.push(cb);
    return () => {
      listenersRef.current = listenersRef.current.filter(l => l !== cb);
    };
  };

  return (
    <SignalingContext.Provider
      value={{
        isConnected,
        clientId,
        roomState,
        joinRoom,
        leaveRoom,
        endRoom,
        sendMessage,
        lastMessage,
        subscribeToMessages,
        error,
        clearError,
      }}
    >
      {children}
    </SignalingContext.Provider>
  );
};
