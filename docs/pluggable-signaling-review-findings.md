# Pluggable Signaling — Review Findings

Findings from the `/simplify` code review of the `pluggable-signaling` branch.
These were follow-up improvements after the main pluggable-signaling rollout.

Status as of 2026-03-28:
- Fixed: 1, 3, 4, 5, 6, 7
- Deferred: 2

---

## 1. ICE Retry Delays Not in Resilience Constants

Status: Fixed

The retry schedule `[0, 1000, 2000, 4000]` ms used when fetching initial ICE servers
is hardcoded in three files but **not tracked** by `check-resilience-constants.mjs`.

| Platform | File | Line |
|----------|------|------|
| Web | `client/packages/core/src/SerenadaSession.ts` | 562 |
| Android | `client-android/serenada-core/.../SerenadaSession.kt` | 994 |
| iOS | `client-ios/SerenadaCore/Sources/SerenadaSession.swift` | 871 |

**Why the checker misses them:** It only reads from the dedicated resilience constant
files (`constants.ts`, `WebRtcResilienceConstants.kt`, `WebRtcResilienceConstants.swift`)
via regex. These inline arrays in `SerenadaSession` are not in those files and are
arrays rather than scalar `const`/`val`/`let` values.

**Recommended fix:** Extract the delays to named constants in each platform's resilience
constants file (e.g. `ICE_FETCH_RETRY_DELAYS_MS`) and update the checker to verify
array parity.

---

## 2. Provider-to-SignalingMessage Adapter Boilerplate (Android/iOS)

Status: Deferred

Both Android and iOS `SerenadaSession` classes contain four adapter functions that convert
typed provider events back into `SignalingMessage` objects to feed `SignalingMessageRouter`:

| Function | Android lines | iOS lines |
|----------|--------------|-----------|
| `joinedMessageFromEvent` | 815–837 | 692–716 |
| `roomStateMessageFromEvent` | 839–861 | 718–741 |
| `signalingMessageFromPeerMessage` | 863–879 | 743–754 |
| `errorMessageFromEvent` | 881–893 | 756–765 |

This creates a round-trip: **provider event → SignalingMessage (JSON) → router parses
JSON back into typed payload**. On Android, `signalingMessageFromPeerMessage` was
previously doing a full JSON serialize/parse cycle (fixed in this review), but the
fundamental overhead of converting typed events to untyped messages and back remains.

The web platform avoids this entirely — it handles provider events directly without
going through a router.

**Recommended fix:** Update `SignalingMessageRouter` on Android/iOS to accept provider
events directly (e.g. `processJoinedEvent(event:)`, `processPeerMessage(message:)`),
eliminating the adapter layer.

---

## 3. iOS `gatherIceCandidates` Only Uses First Server's Credentials

Status: Fixed

In `client-ios/SerenadaCore/Sources/SerenadaDiagnostics.swift` (lines 188–210), the
`runTurnProbe` method collects URLs from **all** filtered ICE servers but passes only
the **first** server's `username`/`credential` to `gatherIceCandidates`:

```swift
let urls = filteredServers.flatMap(\.urls)        // all URLs
return await gatherIceCandidates(
    urls: urls,                                    // all URLs
    username: firstServer.username ?? "",           // first server only
    credential: firstServer.credential ?? "",       // first server only
    onCandidateLog: onCandidateLog
)
```

The underlying `gatherIceCandidates` takes flat `(urls:username:credential:)` parameters,
so it cannot represent per-server credentials.

The web version (`SerenadaDiagnostics.ts`, lines 371–465) passes the full
`RTCIceServer[]` array to `RTCPeerConnection`, preserving per-server credentials.

**Impact:** If a custom provider returns multiple ICE servers with different credentials,
the iOS probe will fail for any server whose credentials differ from the first.

**Recommended fix:** Change the iOS `gatherIceCandidates` signature to accept
`[IceServerConfig]` (the full array) and build `RTCIceServer` entries per-server, matching
the web approach.

---

## 4. ICE Server Normalization Duplicated on Web

Status: Fixed

Two near-identical implementations exist:

**`MediaEngine.ts`** (lines 722–738) — `normalizeIceServers()`:
```typescript
private normalizeIceServers(iceServers: RTCIceServer[]): RTCIceServer[] {
    const normalized: RTCIceServer[] = [];
    for (const iceServer of iceServers) {
        const urls = Array.isArray(iceServer.urls) ? iceServer.urls : [iceServer.urls];
        const filteredUrls = this.turnsOnly
            ? urls.filter(url => typeof url === 'string' && url.toLowerCase().startsWith('turns:'))
            : urls.filter(url => typeof url === 'string' && url.length > 0);
        if (filteredUrls.length === 0) continue;
        normalized.push({ ...iceServer, urls: filteredUrls });
    }
    return normalized;
}
```

**`SerenadaDiagnostics.ts`** (lines 379–395) — inline in `gatherIceCandidates`:
```typescript
for (const iceServer of iceServers) {
    const urls = Array.isArray(iceServer.urls) ? iceServer.urls : [iceServer.urls];
    const filteredUrls = urls.filter((url): url is string => {
        if (typeof url !== 'string' || url.length === 0) return false;
        return !turnsOnly || url.toLowerCase().startsWith('turns:');
    });
    if (filteredUrls.length === 0) continue;
    normalizedIceServers.push({ ...iceServer, urls: filteredUrls });
}
```

The logic is identical — only the predicate style differs.

**Recommended fix:** Extract to a shared utility
(e.g. `normalizeIceServers(servers, turnsOnly)` in a `webrtcUtils.ts` file) and import
from both `MediaEngine` and `SerenadaDiagnostics`.

---

## 5. `dedupeParticipants` Duplicated Within iOS Platform

Status: Fixed

Two structurally identical implementations exist on iOS:

**`SerenadaSession.swift`** (lines 779–796) — operates on `Participant` (uses `.cid`):
```swift
private func dedupeParticipants(participants: [Participant], localPeerId: String?) -> [Participant]
```

**`SerenadaServerProvider.swift`** (lines 371–388) — operates on `SignalingProviderParticipant` (uses `.peerId`):
```swift
func dedupeParticipants(participants: [SignalingProviderParticipant], localPeerId: String?) -> [SignalingProviderParticipant]
```

The algorithm is identical: deduplicate by ID preserving insertion order, inject the local
participant if missing.

**Recommended fix:** Extract a generic or protocol-based implementation. Both
`Participant` and `SignalingProviderParticipant` could conform to a `PeerIdentifiable`
protocol with an `identifier: String` property, enabling a single generic
`dedupeParticipants<T: PeerIdentifiable>` function.

---

## 6. `content_state` Forwarding Inconsistency Across Platforms

Status: Fixed

The media-message forwarding filter differs between web and Android/iOS:

| Platform | Filter | `content_state` forwarded? |
|----------|--------|---------------------------|
| Web (`SerenadaSession.ts:518`) | `offer`, `answer`, `ice` | No |
| Android (`SerenadaSession.kt:425`) | `content_state`, `offer`, `answer`, `ice` | Yes |
| iOS (`SerenadaSession.swift:597`) | `content_state`, `offer`, `answer`, `ice` | Yes |

On web, `content_state` messages from a custom signaling provider are silently dropped
and never reach `MediaEngine`. On Android/iOS they are routed through
`SignalingMessageRouter` which handles them.

This is not a regression from the previous architecture (web's `MediaEngine` never
processed inbound `content_state` — it only *sent* them), but it means a custom provider
that relays `content_state` messages will see different behavior across platforms.

**Recommended fix:** Add `content_state` to the web filter so all three platforms
handle it consistently.

---

## 7. `activeTransport` Type Weakened on Web

Status: Fixed

In `client/packages/core/src/types.ts` (line 65), `CallState.activeTransport` was
changed from `TransportKind | null` (`'ws' | 'sse' | null`) to `string | null`:

```typescript
activeTransport: string | null;  // was: TransportKind | null
```

This accommodates custom providers that may report transport names outside the built-in
`'ws' | 'sse'` union, but it weakens type safety for existing consumers who relied on
the discriminated union.

Note: `RoomWatcherState.activeTransport` (line 158) still uses `TransportKind | null`.

**Recommended fix:** Either define a new union type
`type ActiveTransport = TransportKind | (string & {})` that preserves autocomplete for
known values while allowing custom strings, or document the change as intentional in the
CHANGELOG.
