# Push Notifications (Encrypted Snapshots)

## Goals
- Deliver a join notification that includes a camera snapshot.
- Keep the server blind to snapshot contents (no plaintext stored or processed).
- Preserve multi-subscriber delivery (one snapshot can be shared across multiple recipients).
- Keep snapshots short-lived (10 minute TTL).

## Architecture (Server-blind)
### Key material (per subscriber)
- Each device that subscribes to notifications generates an ECDH key pair (P-256) in the browser.
- The private key is stored in IndexedDB (non-exported). The public key is sent to the server with the subscription.
- The server stores the public key alongside the subscription record (`enc_pubkey`).

### Snapshot encryption (per join)
1. Joiner captures a camera frame at Join time and compresses it (JPEG, ~320px width).
2. Joiner generates a random AES-256-GCM content key and encrypts the snapshot with a random IV.
3. Joiner generates an ephemeral ECDH key pair for this snapshot.
4. For each recipient public key:
   - Derive an ECDH shared secret (joiner ephemeral private key + recipient public key).
   - Use HKDF(SHA-256) with a random salt and fixed info string to derive a per-recipient AES key.
   - Encrypt the content key with that per-recipient AES key (AES-256-GCM), producing a wrapped key.
5. Joiner uploads:
   - The encrypted snapshot bytes.
   - Snapshot IV + HKDF salt + ephemeral public key.
   - A list of recipient IDs with their wrapped content keys + IVs.

### Server behavior
- Stores only encrypted snapshot bytes plus metadata (key wrapping data, IVs, mime).
- Never sees plaintext.
- TTL cleanup removes snapshot data after 10 minutes.
- On join, server sends push notifications that include:
  - `snapshotId`
  - `snapshotIv`
  - `snapshotSalt`
  - `snapshotEphemeralPubKey`
  - `snapshotKey` (wrapped content key)
  - `snapshotKeyIv`
  - `snapshotMime`

### Service worker (recipient)
- Retrieves its private key from IndexedDB.
- Uses `snapshotEphemeralPubKey` + HKDF salt to derive the wrap key.
- Decrypts the wrapped content key.
- Fetches the encrypted snapshot blob (`/api/push/snapshot/{id}`) and decrypts it.
- Displays the decrypted image in the notification:
  - `image` for Android Chrome.
  - `icon` fallback for macOS (Notification Center ignores `image`).

## Protocol details
### Subscription request
`POST /api/push/subscribe?roomId=...`

```json
{
  "endpoint": "...",
  "keys": { "auth": "...", "p256dh": "..." },
  "locale": "en-US",
  "encPublicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
}
```

### Recipient list
`GET /api/push/recipients?roomId=...`

```json
[
  { "id": 123, "publicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." } }
]
```

### Snapshot upload
`POST /api/push/snapshot`

```json
{
  "ciphertext": "<base64>",
  "snapshotIv": "<base64>",
  "snapshotSalt": "<base64>",
  "snapshotEphemeralPubKey": "<base64>",
  "snapshotMime": "image/jpeg",
  "recipients": [
    {
      "id": 123,
      "wrappedKey": "<base64>",
      "wrappedKeyIv": "<base64>"
    }
  ]
}
```

### Push payload fields
```json
{
  "title": "Serenada",
  "body": "Someone joined your call!",
  "url": "/call/ROOM_ID",
  "snapshotId": "SNAP-...",
  "snapshotIv": "<base64>",
  "snapshotSalt": "<base64>",
  "snapshotEphemeralPubKey": "<base64>",
  "snapshotKey": "<base64>",
  "snapshotKeyIv": "<base64>",
  "snapshotMime": "image/jpeg"
}
```

## Data retention
- Snapshots are encrypted and stored under `DATA_DIR/snapshots`.
- TTL cleanup removes files older than 10 minutes.
- No deletion-on-first-fetch to allow multiple subscribers.

## Limitations
- macOS Chrome does not render `image` in notifications; we use `icon` as a fallback.
- If the device lacks the private key or decryption fails, notifications fall back to text-only.
- Push payload size must remain under service limits; wrapped key data stays small per recipient.
