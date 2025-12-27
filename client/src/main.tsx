import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';
import { SignalingProvider } from './contexts/SignalingContext';
import { WebRTCProvider } from './contexts/WebRTCContext';
import { ToastProvider } from './contexts/ToastContext';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ToastProvider>
      <SignalingProvider>
        <WebRTCProvider>
          <App />
        </WebRTCProvider>
      </SignalingProvider>
    </ToastProvider>
  </React.StrictMode>
);
