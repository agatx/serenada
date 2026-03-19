import ReactDOM from 'react-dom/client';
import './i18n';
import App from './App';
import './index.css';
import { ToastProvider } from './contexts/ToastContext';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <ToastProvider>
    <App />
  </ToastProvider>
);
// Register Service Worker for PWA support
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(regError => {
      console.log('SW registration failed: ', regError);
    });
  });
}
