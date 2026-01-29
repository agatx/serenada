const DB_NAME = 'serenada-push';
const STORE_NAME = 'keys';
const KEY_ID = 'v1';

function openDb(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, 1);
        request.onerror = () => reject(request.error);
        request.onupgradeneeded = () => {
            const db = request.result;
            if (!db.objectStoreNames.contains(STORE_NAME)) {
                db.createObjectStore(STORE_NAME);
            }
        };
        request.onsuccess = () => resolve(request.result);
    });
}

async function readKeys(): Promise<{ privateKey: CryptoKey; publicJwk: JsonWebKey } | null> {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.get(KEY_ID);
        req.onerror = () => reject(req.error);
        req.onsuccess = () => {
            const value = req.result as { privateKey: CryptoKey; publicJwk: JsonWebKey } | undefined;
            resolve(value ?? null);
        };
    });
}

async function writeKeys(privateKey: CryptoKey, publicJwk: JsonWebKey): Promise<void> {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.put({ privateKey, publicJwk }, KEY_ID);
        req.onerror = () => reject(req.error);
        req.onsuccess = () => resolve();
    });
}

export async function getOrCreatePushKeyPair(): Promise<{ privateKey: CryptoKey; publicJwk: JsonWebKey }> {
    const existing = await readKeys();
    if (existing) {
        return existing;
    }

    const keyPair = await crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits']
    );

    const publicJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
    await writeKeys(keyPair.privateKey, publicJwk);
    return { privateKey: keyPair.privateKey, publicJwk };
}
