# Snapshot Capture

`SerenadaSession.captureSnapshot(source)` grabs the current video frame from a
chosen stream at the source track's full intrinsic resolution. It works against
either the local stream or any specific remote participant's stream. The call
returns asynchronously; on success it resolves to a snapshot result, on failure
it rejects with a typed error.

The optional snapshot button in `SerenadaCallFlow` overlays a circular shutter
control on the current large preview, anchored to its short edge — bottom in
portrait, right in landscape. The control is gated by an opt-in flag; without
it, the button never renders.

## Web

```ts
import { SerenadaCore, SnapshotError } from '@agatx/serenada-core';

const session = new SerenadaCore({ serverHost: 'serenada.app' }).join(url);

try {
  const result = await session.captureSnapshot({ kind: 'local' });
  // result.blob is image/jpeg at result.width × result.height
  const url = URL.createObjectURL(result.blob);
  imgEl.src = url;
} catch (err) {
  if (err instanceof SnapshotError && err.code === 'streamNotActive') {
    // Camera off, or remote participant left
  }
}
```

Render the shutter button by enabling the flag on `<SerenadaCallFlow>`:

```tsx
<SerenadaCallFlow
  url={url}
  config={{ snapshotEnabled: true }}
  onSnapshotCaptured={(result) => downloadBlob(result.blob, 'photo.jpg')}
  onSnapshotError={(err) => toast(`Snapshot failed: ${err.code}`)}
/>
```

## iOS

```swift
import SerenadaCore

do {
    let result = try await session.captureSnapshot(source: .local)
    UIImage(data: result.jpegData).map(saveToPhotos)
} catch SnapshotError.streamNotActive {
    // Camera off
} catch {
    print("snapshot failed: \(error)")
}
```

```swift
SerenadaCallFlow(
    session: session,
    config: SerenadaCallFlowConfig(snapshotEnabled: true)
)
.onSnapshotCaptured { result in
    UIImage(data: result.jpegData).map(viewerStore.append)
}
.onSnapshotError { err in
    print("Snapshot error: \(err)")
}
```

## Android

```kotlin
import app.serenada.core.SnapshotError
import app.serenada.core.SnapshotSource

lifecycleScope.launch {
    runCatching { session.captureSnapshot(SnapshotSource.Local) }
        .onSuccess { result -> writeJpeg(result.jpeg) }
        .onFailure { err ->
            when (err) {
                is SnapshotError.StreamNotActive -> showCameraOffMessage()
                else -> Log.w("Snapshot", "failed", err)
            }
        }
}
```

```kotlin
SerenadaCallFlow(
    session = session,
    config = SerenadaCallFlowConfig(snapshotEnabled = true),
    onSnapshotCaptured = { result -> previewSink(result.jpeg) },
    onSnapshotError = { err -> Log.w("Serenada", "snapshot: $err") },
)
```

## Source selection

| Source                          | Backed by                          | When inactive                |
| ------------------------------- | ---------------------------------- | ---------------------------- |
| `local` (default)               | local camera/screen track          | `streamNotActive`            |
| `remote(cid)` / `Remote(cid)`   | a specific peer's video track      | `streamNotActive`            |

The error variants:

- `streamNotActive` — chosen stream's track is missing or the participant has video off
- `noVideoTrack` — stream exists but has no video
- `captureTimeout` — no frame arrived within the resilience window
- `captureFailed` — encoder failure (zero dimensions, decode error, etc.)
- `unsupportedSource` — reserved

## UI placement

The optional shutter button sits on the short edge of whichever preview is
currently shown large. The "current large preview" is the local stream when
the user has swapped local-as-large; otherwise it's the first remote
participant's stream. In multi-party scenes the button hides until a tile is
explicitly pinned (the short-edge concept needs a single dominant preview).

The button automatically disables itself when:

- the call hasn't reached `inCall` or `waiting`
- the chosen primary participant has video off
- a previous snapshot is still in flight (web)
