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

self.addEventListener('push', (event) => {
    let data = {};
    if (event.data) {
        data = event.data.json();
    }

    const title = data.title || 'Serenada';
    const options = {
        body: data.body || 'Someone joined the call',
        icon: '/serenada.png',
        badge: '/serenada.png',
        data: { url: data.url }
    };

    event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
            const url = event.notification.data.url;
            // Check if tab is already open
            for (let client of windowClients) {
                if (client.url.includes(url) && 'focus' in client) {
                    return client.focus();
                }
            }
            if (clients.openWindow) {
                // Construct absolute URL if needed, but openWindow handles relative to origin
                return clients.openWindow(url);
            }
        })
    );
});
