// Minimal service worker to satisfy PWA installation requirements
const CACHE_NAME = 'serenada-v1';

self.addEventListener('install', (event) => {
    // skipWaiting() to activate the new SW immediately
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    // Claim clients to start controlling them immediately
    event.waitUntil(clients.claim());
});

self.addEventListener('fetch', (event) => {
    // Basic pass-through fetch handler
    event.respondWith(fetch(event.request));
});
