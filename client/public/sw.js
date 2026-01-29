// Minimal service worker to satisfy PWA installation requirements
const CACHE_NAME = 'serenada-v1';
const PUSH_DB_NAME = 'serenada-push';
const PUSH_STORE_NAME = 'keys';
const PUSH_KEY_ID = 'v1';

function base64ToBytes(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

function bytesToBase64(bytes) {
    let binary = '';
    for (let i = 0; i < bytes.length; i += 1) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}

async function blobToDataUrl(blob) {
    const buffer = await blob.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    const mime = blob.type || 'application/octet-stream';
    return `data:${mime};base64,${bytesToBase64(bytes)}`;
}

function openPushDb() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(PUSH_DB_NAME, 1);
        request.onerror = () => reject(request.error);
        request.onupgradeneeded = () => {
            const db = request.result;
            if (!db.objectStoreNames.contains(PUSH_STORE_NAME)) {
                db.createObjectStore(PUSH_STORE_NAME);
            }
        };
        request.onsuccess = () => resolve(request.result);
    });
}

async function getPrivateKey() {
    const db = await openPushDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(PUSH_STORE_NAME, 'readonly');
        const store = tx.objectStore(PUSH_STORE_NAME);
        const req = store.get(PUSH_KEY_ID);
        req.onerror = () => reject(req.error);
        req.onsuccess = () => {
            const value = req.result;
            resolve(value ? value.privateKey : null);
        };
    });
}

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
    event.waitUntil((async () => {
        let data = {};
        if (event.data) {
            try {
                data = event.data.json();
            } catch {
                data = {};
            }
        }

        let imageUrl = null;
        let iconUrl = null;
        if (
            data.snapshotId &&
            data.snapshotKey &&
            data.snapshotKeyIv &&
            data.snapshotIv &&
            data.snapshotSalt &&
            data.snapshotEphemeralPubKey
        ) {
            try {
                const privateKey = await getPrivateKey();
                if (privateKey) {
                    const snapshotIv = base64ToBytes(data.snapshotIv);
                    const snapshotSalt = base64ToBytes(data.snapshotSalt);
                    const ephemeralPub = base64ToBytes(data.snapshotEphemeralPubKey);
                    const wrappedKey = base64ToBytes(data.snapshotKey);
                    const wrappedIv = base64ToBytes(data.snapshotKeyIv);

                    const ephemeralKey = await crypto.subtle.importKey(
                        'raw',
                        ephemeralPub,
                        { name: 'ECDH', namedCurve: 'P-256' },
                        false,
                        []
                    );
                    const sharedBits = await crypto.subtle.deriveBits(
                        { name: 'ECDH', public: ephemeralKey },
                        privateKey,
                        256
                    );
                    const hkdfKey = await crypto.subtle.importKey('raw', sharedBits, 'HKDF', false, ['deriveKey']);
                    const wrapKey = await crypto.subtle.deriveKey(
                        {
                            name: 'HKDF',
                            hash: 'SHA-256',
                            salt: snapshotSalt,
                            info: new TextEncoder().encode('serenada-push-snapshot')
                        },
                        hkdfKey,
                        { name: 'AES-GCM', length: 256 },
                        false,
                        ['decrypt']
                    );
                    const rawSnapshotKey = await crypto.subtle.decrypt(
                        { name: 'AES-GCM', iv: wrappedIv },
                        wrapKey,
                        wrappedKey
                    );
                    const snapshotKey = await crypto.subtle.importKey(
                        'raw',
                        rawSnapshotKey,
                        { name: 'AES-GCM' },
                        false,
                        ['decrypt']
                    );

                    const res = await fetch(`/api/push/snapshot/${data.snapshotId}`);
                    if (res.ok) {
                        const encrypted = await res.arrayBuffer();
                        const decrypted = await crypto.subtle.decrypt(
                            { name: 'AES-GCM', iv: snapshotIv },
                            snapshotKey,
                            encrypted
                        );
                        const mime = data.snapshotMime || 'image/jpeg';
                        const bytes = new Uint8Array(decrypted);
                        const blob = new Blob([bytes], { type: mime });
                        imageUrl = await blobToDataUrl(blob);

                        if (typeof OffscreenCanvas !== 'undefined' && typeof createImageBitmap === 'function') {
                            const bitmap = await createImageBitmap(blob);
                            const size = Math.min(bitmap.width, bitmap.height);
                            if (size > 0) {
                                const canvas = new OffscreenCanvas(size, size);
                                const ctx = canvas.getContext('2d');
                                if (ctx) {
                                    const sx = Math.floor((bitmap.width - size) / 2);
                                    const sy = Math.floor((bitmap.height - size) / 2);
                                    ctx.drawImage(bitmap, sx, sy, size, size, 0, 0, size, size);
                                    const iconBlob = await canvas.convertToBlob({ type: 'image/png' });
                                    iconUrl = await blobToDataUrl(iconBlob);
                                }
                            }
                            bitmap.close();
                        }
                    }
                }
            } catch (err) {
                console.warn('[SW] Failed to decrypt snapshot', err);
                imageUrl = null;
            }
        }

        const title = data.title || 'Serenada';
        const options = {
            body: data.body || 'Someone joined the call',
            icon: iconUrl || imageUrl || '/serenada.png',
            badge: '/serenada.png',
            data: { url: data.url }
        };
        if (imageUrl) {
            options.image = imageUrl;
        }

        await self.registration.showNotification(title, options);
    })());
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
