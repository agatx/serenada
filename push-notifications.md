# Push Notifications (Encrypted Snapshots)

## Goals
- Deliver a join notification that includes a camera snapshot.
- Deliver a room invite notification on explicit invite action.
- Keep the server blind to snapshot contents (no plaintext stored or processed).
- Preserve multi-subscriber delivery (one snapshot can be shared across multiple recipients).
- Keep snapshots short-lived (10 minute TTL).

## Client support
- Snapshot sender on join: web client and native Android client.
- Push subscription + notification rendering:
  - Web: service worker (`webpush` transport).
  - Native Android: Firebase Cloud Messaging (`fcm` transport).

## Architecture (Server-blind)
### Key material (per subscriber)
- Each subscribing device generates an ECDH key pair (P-256).
  - Web stores the private key in IndexedDB as a CryptoKey.
  - Android stores the private key in Android Keystore.
- The public key is sent to the server with the subscription.
- The server stores the public key alongside the subscription record (`enc_pubkey`).

### Snapshot encryption (per join)
1. Joiner captures a camera frame at Join time and compresses it (JPEG, ~320px width, ~200KB target).
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
- Cleanup runs on snapshot upload and removes files older than 10 minutes.
- Enforces a max ciphertext size of 300KB per snapshot.
- On join, server sends push notifications (`kind: "join"`) that include:
  - `host` (preferred backend host for deep-link/snapshot fetch routing)
  - `snapshotId`
  - `snapshotIv`
  - `snapshotSalt`
  - `snapshotEphemeralPubKey`
  - `snapshotKey` (wrapped content key)
  - `snapshotKeyIv`
  - `snapshotMime`
- On invite (`POST /api/push/invite`), server sends push notifications with `kind: "invite"` and no snapshot fields.

### Service worker (recipient)
- Retrieves its private key from IndexedDB.
- Uses `snapshotEphemeralPubKey` + HKDF salt to derive the wrap key.
- Decrypts the wrapped content key.
- Fetches the encrypted snapshot blob (`/api/push/snapshot/{id}`) and decrypts it.
- Displays the decrypted image in the notification:
  - `image` for Android Chrome.
  - `icon` fallback for macOS (Notification Center ignores `image`).

### Native Android recipient
- Receives FCM data message in `FirebaseMessagingService`.
- Uses Android Keystore private key + `snapshotEphemeralPubKey` and HKDF salt to unwrap `snapshotKey`.
- Fetches encrypted snapshot via `/api/push/snapshot/{id}` and decrypts locally.
- Renders notification with `BigPictureStyle` and deep-links to `/call/{roomId}`.
- Invite notifications are shown only when the invited room is saved on device and the Settings toggle is enabled.

## Protocol details
### VAPID key
`GET /api/push/vapid-public-key`

```json
{ "publicKey": "..." }
```

### Subscription request
`POST /api/push/subscribe?roomId=...`

```json
{
  "transport": "webpush",
  "endpoint": "...",
  "keys": { "auth": "...", "p256dh": "..." },
  "locale": "en-US",
  "encPublicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
}
```

Android (`fcm`) subscription example:
```json
{
  "transport": "fcm",
  "endpoint": "<fcm_registration_token>",
  "locale": "en-US",
  "encPublicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
}
```

### Unsubscribe
`DELETE /api/push/subscribe?roomId=...`

```json
{ "endpoint": "..." }
```

### Recipient list
`GET /api/push/recipients?roomId=...`

```json
[
  { "id": 123, "publicKey": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." } }
]
```

### Invite trigger
`POST /api/push/invite?roomId=...`

```json
{ "endpoint": "optional-sender-endpoint-or-fcm-token" }
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

**Response**
```json
{ "id": "SNAP-...", "url": "/api/push/snapshot/SNAP-..." }
```

### Push payload fields
```json
{
  "kind": "join",
  "title": "Serenada",
  "body": "Someone joined your call!",
  "host": "serenada.app",
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

Invite payload example:
```json
{
  "kind": "invite",
  "title": "Serenada",
  "body": "You were invited to a room.",
  "host": "serenada.app",
  "url": "/call/ROOM_ID"
}
```

## Data retention
- Snapshots are encrypted and stored under `DATA_DIR/snapshots`.
- Cleanup runs on snapshot upload and removes files older than 10 minutes.
- No deletion-on-first-fetch to allow multiple subscribers.

## Server configuration (Android FCM)
- Configure one of:
  - `FCM_SERVICE_ACCOUNT_JSON` (full service account JSON string)
  - `FCM_SERVICE_ACCOUNT_FILE` (path to a service account JSON file)
- If unset, `fcm` subscriptions are accepted but native Android pushes are skipped server-side.
- `STUN_HOST` (or `DOMAIN`, if set) is used as the push `host` hint so Android opens/fetches against the originating backend.

## Limitations
- macOS Chrome does not render `image` in notifications; we use `icon` as a fallback.
- If the device lacks the private key or decryption fails, notifications fall back to text-only.
- Push payload size must remain under service limits; wrapped key data stays small per recipient.
- Android requires Firebase runtime config in `client-android` (`firebaseAppId`, `firebaseApiKey`, `firebaseProjectId`, `firebaseSenderId` Gradle properties).
