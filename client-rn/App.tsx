import React, { useState } from 'react';
import HomeScreen from './src/screens/HomeScreen';
import CallRoomScreen from './src/screens/CallRoomScreen';
import { ToastProvider } from './src/contexts/ToastContext';
import { SignalingProvider } from './src/contexts/SignalingContext';
import { WebRTCProvider } from './src/contexts/WebRTCContext';

const App: React.FC = () => {
  const [activeRoomId, setActiveRoomId] = useState<string | null>(null);

  return (
    <ToastProvider>
      <SignalingProvider>
        <WebRTCProvider>
          {activeRoomId ? (
            <CallRoomScreen roomId={activeRoomId} onLeave={() => setActiveRoomId(null)} />
          ) : (
            <HomeScreen onStartCall={setActiveRoomId} />
          )}
        </WebRTCProvider>
      </SignalingProvider>
    </ToastProvider>
  );
};

export default App;
