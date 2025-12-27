import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';
import { SignalingProvider } from './contexts/SignalingContext';
import { WebRTCProvider } from './contexts/WebRTCContext';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <SignalingProvider>
      <WebRTCProvider>
        <App />
      </WebRTCProvider>
    </SignalingProvider>
  </React.StrictMode>
);
