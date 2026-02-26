## Cross-Client WebRTC Join/Rejoin/Reconnect Reliability Convergence Plan

### Summary
Unify web, iOS, and Android around one resilience profile that keeps call setup fast and recoverable under transport drops, TURN latency, and negotiation stalls.
Primary policy (selected): **hybrid recovery**: host-offerer remains primary, with guarded non-host fallback for rare stalled-negotiation cases.

### Current Comparison (Grounded Findings)
| Area | Web | iOS | Android | Reliability/Performance Impact |
|---|---|---|---|---|
| Join timeout | No explicit hard join timeout in signaling layer | 12s hard timeout + 1.2s kickstart + 4s join recovery | 25s hard timeout only | Web can hang indefinitely in edge join states; Android can hang too long; iOS is most self-healing |
| ICE bootstrap gating | `rtcConfig` is blocked on TURN token/fetch path | Applies default STUN immediately, then upgrades with TURN | Waits for TURN fetch callback before PC is ready | Web/Android can stall negotiation if TURN is slow/missing; iOS connects faster/more robustly |
| TURN fetch timeout | No explicit timeout | 2s timeout with fallback handling | No explicit timeout wrapper in call flow | Android/Web can have longer blocked setup on slow network |
| Offer recovery | Host-offer only + ICE restart logic | Host-offer + non-host fallback offer logic | Host-offer only | iOS can recover from host-offer stalls that can deadlock Web/Android |
| SSE reconnect session continuity | New `sid` each new transport instance | Reuses `sid` across reconnects (same host) | Reuses `sid` across reconnects (same host) | Web loses SSE continuity optimization; more ghost/churn risk during reconnect |
| Host override TURN fetch | N/A (origin/ws derived) | TURN fetch currently uses `serverHost` path | Uses current call host | iOS one-off host calls can fetch TURN from wrong host, reducing NAT traversal reliability |
| Transport fallback after success | Never falls back to SSE once WS connected (`transportConnectedOnceRef`) | Never falls back once WS connected (`transportConnectedOnce`) | Never falls back once WS connected (`transportConnectedOnce`) | All platforms can get stuck retrying WS forever if WS path degrades mid-call |
| SDP send reliability | Fire-and-forget; silent drop if transport hiccups | Fire-and-forget | Fire-and-forget | Lost offer/answer silently breaks negotiation on all platforms; recovery relies on slow 8s offer timeout path |
| Ping/pong validation | Sends pings, does not track pong responses | Sends pings, does not track pong responses | Sends pings, does not track pong responses | Half-open connections persist silently for 30-90s until OS TCP timeout |
| Network change handling | No listener; relies solely on ICE state callbacks | NWPathMonitor triggers ICE restart | ConnectivityManager callback triggers ICE restart | Web misses proactive recovery on Wi-Fi↔cellular transitions |
| TURN credential lifetime | 5-min token from `joined`, 15-min from `/api/turn-credentials` | Same | Same | Calls >15 min lose TURN relay; no in-call refresh path; falls back to STUN-only (fails behind symmetric NAT) |
| Error surfacing | Logs errors, no consistent user-facing state | CallPhase-based error screens | Error state in ViewModel | Users on different platforms see different failure experiences |

Evidence references:

- Web signaling/join/reconnect: [SignalingContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/SignalingContext.tsx:181), [SignalingContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/SignalingContext.tsx:257)
- Web ICE gating: [WebRTCContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/WebRTCContext.tsx:137), [WebRTCContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/WebRTCContext.tsx:252)
- Transport fallback bug (all platforms): Web [SignalingContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/SignalingContext.tsx:271), iOS [SignalingClient.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Signaling/SignalingClient.swift:200), Android [SignalingClient.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/SignalingClient.kt:202) — `shouldFallback()` returns false once `transportConnectedOnce[kind]` is set, identical logic on all three platforms
- Web offer timeout incomplete: [WebRTCContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/WebRTCContext.tsx:834) (rollback only if `signalingState === 'have-local-offer'`, no ICE restart otherwise)
- Web listener mutation: [SignalingContext.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/SignalingContext.tsx:148) (listeners iterated with `forEach` while callbacks can mutate the array)
- iOS join resilience + fallback: [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:34), [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:1149), [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:1653), [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:1048)
- iOS TURN host mismatch: [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:1247)
- iOS remote track duplication: [WebRtcEngine.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/WebRtcEngine.swift:1092) (`didAdd stream`), [WebRtcEngine.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/WebRtcEngine.swift:1503) (`didAdd rtpReceiver`), [WebRtcEngine.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/WebRtcEngine.swift:1538) (callback) — three paths deliver remote video track with no deduplication
- Android join + timeout + offer path: [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:626), [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:1186), [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:1116)
- Android strict room-state parse: [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:1299)
- Android thread safety: [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:107) (`joinAttemptSerial` non-atomic increment), [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:118) (`pendingMessages` unsynchronized `ArrayDeque`), [SignalingClient.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/SignalingClient.kt:38) (`connected`/`connecting` flags lack `@Volatile`)
- Snapshot preparation timeouts: Android [CallManager.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/CallManager.kt:899) (`prepareSnapshotId` has no timeout), iOS [CallManager.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Call/CallManager.swift:1525) (`prepareSnapshotId` callback-based, no explicit timeout), Web [CallRoom.tsx](/Users/alexeygavrilov/Developer/src/connected/client/src/pages/CallRoom.tsx:582) (already has 1200ms `Promise.race` timeout — reference implementation)
- SSE sid behavior: [SseSignalingTransport.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Signaling/SseSignalingTransport.swift:6), [SignalingClient.swift](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Signaling/SignalingClient.swift:49), [SseSignalingTransport.kt](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/SseSignalingTransport.kt:29), [sse.ts](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/signaling/transports/sse.ts:31)
- Server ghost eviction race: [signaling.go](/Users/alexeygavrilov/Developer/src/connected/server/signaling.go:227) (double ghost search with unlock/relock gap at line 274)
- Server TURN token TTL mismatch: [signaling.go](/Users/alexeygavrilov/Developer/src/connected/server/signaling.go:333) (5-min token) vs [turn_auth.go](/Users/alexeygavrilov/Developer/src/connected/server/turn_auth.go:145) (15-min credentials)
- Server unauthenticated reconnectCID: [signaling.go](/Users/alexeygavrilov/Developer/src/connected/server/signaling.go:229) (any client can claim any CID)
- Server SSE stale timeout: [sse.go](/Users/alexeygavrilov/Developer/src/connected/server/sse.go:18) (60s evicts active listeners)
- Server WriteTimeout disabled: [main.go](/Users/alexeygavrilov/Developer/src/connected/server/main.go:115) (`WriteTimeout: 0`)

---

## Implementation Plan

### Phase 0: Server Hardening (unblocks client reliability)

1. **Fix ghost eviction race condition** (`signaling.go:227-298`):
   - Consolidate the two ghost-client searches (lines 229 and 253) into a single pass.
   - Hold room lock across the full eviction: remove ghost from `room.Participants`, clear its `cid`/`rid`, then release lock before calling `removeClientFromRoom` for hub-level cleanup, and re-acquire only if needed.
   - Alternatively, use a mark-and-sweep pattern: mark ghost for removal under room lock, release, then sweep outside the lock.
   - Add a test: two concurrent reconnects with same `reconnectCID` must never result in duplicate CIDs.

2. **Add TURN credential refresh support**:
   - Option A (signaling message): add a `turn-refresh` request/response message type. Client sends `{"type":"turn-refresh","rid":"..."}` when in a room. Server validates room membership and responds with fresh TURN token + credentials. No new HTTP endpoint needed.
   - Option B (HTTP endpoint): add `/api/turn-credentials/refresh` that accepts the original (possibly expired) TURN token + room ID. Server validates room membership via hub lookup.
   - Decision: Option A (signaling message) — keeps credential exchange on the existing transport, no CORS/auth changes.
   - Extend join-issued TURN token TTL from 5 minutes to 30 minutes (`signaling.go:333`). Clients proactively request refresh at 80% of TTL (i.e., at 24 minutes).
   - Add constant: `TURN_REFRESH_TRIGGER_RATIO = 0.8`.

3. **Extend SSE stale timeout** (`sse.go:18`):
   - Increase from 60s to 5 minutes for clients currently in a room.
   - Keep 60s for clients not in a room (idle connections).
   - Rationale: Active call participants who only receive (no POST) should not be evicted.

4. **Authenticate `reconnectCID`** (`signaling.go:229-234`):
   - On initial join, server returns a `reconnectToken` (HMAC of `sid + cid + rid + secret`) in the `joined` payload.
   - On reconnect join, client must provide `reconnectCid` + `reconnectToken`. Server validates the HMAC before allowing ghost eviction.
   - Wire-protocol addition: `reconnectToken` field in `joined` response and `join` request payloads.

5. **Enable HTTP WriteTimeout** (`main.go:115`):
   - Set `WriteTimeout: 30s` to prevent slow-client goroutine exhaustion.
   - Exempt SSE handler (SSE connections are long-lived by design) using per-handler `http.TimeoutHandler` or by resetting the deadline in the SSE write loop.

### Phase 1: Critical Client Reliability Parity (highest impact)
1. **Standardize resilience constants across all clients:**
   - `CONNECT_TIMEOUT_MS = 2000`
   - `RECONNECT_BACKOFF_BASE_MS = 500`, cap `5000`
   - `JOIN_PUSH_ENDPOINT_WAIT_MS = 250`
   - `JOIN_CONNECT_KICKSTART_MS = 1200`
   - `JOIN_RECOVERY_MS = 4000`
   - `JOIN_HARD_TIMEOUT_MS = 15000`
   - `TURN_FETCH_TIMEOUT_MS = 2000`
   - `TURN_REFRESH_TRIGGER_RATIO = 0.8`
   - `OFFER_TIMEOUT_MS = 8000`
   - `ICE_RESTART_COOLDOWN_MS = 10000`
   - `NON_HOST_FALLBACK_DELAY_MS = 4000`, max attempts `2`
   - `WS_FALLBACK_CONSECUTIVE_FAILURES = 3`
   - `PONG_MISS_THRESHOLD = 2` (trigger reconnect after 2 missed pongs)
   - `ICE_CANDIDATE_BUFFER_MAX = 50`
   - `SNAPSHOT_PREPARE_TIMEOUT_MS = 2000`
   - Canonical reference: maintain a `RESILIENCE_CONSTANTS.md` table listing each constant, its value, and its rationale. CI lint step (per-platform) validates that each client's definition matches the reference values.

2. **Make ICE bootstrap non-blocking on all clients:**
   - Web: initialize default STUN config immediately; never block signaling processing on TURN fetch.
   - Android: apply default STUN immediately after join flow starts; TURN fetch upgrades config later.
   - iOS: keep current default-first behavior.
   - Rule: missing/failed/late TURN token must still allow call setup via default STUN path.

3. **Fix iOS host-override TURN fetch:**
   - Use `currentSignalingHost()` for TURN fetch in active call context.
   - Preserve existing host override semantics for `/call/{roomId}` and saved-room one-off hosts.

4. **Align join/rejoin lifecycle parity:**
   - Web: add explicit join attempt lifecycle (attempt id, hard timeout, kickstart, recovery resend logic).
   - Android: add join kickstart + join recovery timers mirroring iOS behavior.
   - iOS: keep current timers, adjust hard timeout to shared value.

5. **Standardize negotiation recovery (hybrid):**
   - Keep host-as-offerer primary.
   - Add host watchdog retries when 2 participants and no remote description.
   - Add guarded non-host fallback offer (stable signaling, no remote description, connected, bounded attempts).
   - Cancel fallback timers on any inbound offer/answer or remote-description set.

6. **Preserve SSE session continuity on web reconnect:**
   - Keep a stable SSE `sid` per host session and reuse it across reconnect attempts.
   - Reset SID only on host change or explicit full reset.

7. **Fix transport fallback after WS degradation (all clients):**
   - All three platforms share identical `shouldFallback()` + `transportConnectedOnce` logic that blocks SSE fallback once WS has connected even once.
   - Web: [SignalingContext.tsx:271](/Users/alexeygavrilov/Developer/src/connected/client/src/contexts/SignalingContext.tsx:271), iOS: [SignalingClient.swift:200](/Users/alexeygavrilov/Developer/src/connected/client-ios/Sources/Core/Signaling/SignalingClient.swift:200), Android: [SignalingClient.kt:202](/Users/alexeygavrilov/Developer/src/connected/client-android/app/src/main/java/app/serenada/android/call/SignalingClient.kt:202).
   - Fix: track consecutive WS reconnect failures per session. After `WS_FALLBACK_CONSECUTIVE_FAILURES` (3) consecutive failures, allow SSE fallback even if WS previously connected.
   - Reset the failure counter on any successful WS connection.
   - Rationale: current `transportConnectedOnce` prevents fallback forever once WS connects, which traps clients if WS path degrades mid-call (e.g., corporate proxy starts blocking).

8. **Add proactive TURN credential refresh on all clients:**
   - Schedule a refresh timer at `TURN_REFRESH_TRIGGER_RATIO * turnTokenTTL` after receiving credentials.
   - Send `turn-refresh` signaling message; on response, update ICE server config and trigger ICE restart if currently using TURN relay candidates.
   - On refresh failure: log warning, retry once after 60s, then continue with existing credentials (graceful degradation).

9. **Add ping/pong liveness validation on all clients:**
   - Track timestamp of last pong received from server.
   - If `PONG_MISS_THRESHOLD` (2) consecutive ping intervals pass with no pong, treat connection as dead and trigger reconnect.
   - Prevents half-open connections from persisting silently for 30-90s.

10. **Add network change listener on web:**
    - Listen to `navigator.onLine` / `offline`/`online` events and `navigator.connection?.addEventListener('change', ...)`.
    - On network recovery (`online` event or connection type change), immediately trigger ICE restart if `iceConnectionState` is `disconnected` or `failed`.
    - Aligns web with iOS (NWPathMonitor) and Android (ConnectivityManager) behavior.

### Phase 2: Robustness and Diagnostics Parity
1. **Normalize room-state recovery behavior:**
   - Android: add host fallback parsing safety similar to iOS when payload is partially malformed.
   - Web: validate minimal room-state fields before promoting state.

2. **Standardize reconnect observability:**
   - Android: set `isReconnecting` consistently on close/open to match iOS semantics.
   - Web/iOS/Android: emit uniform lifecycle log events for join/rejoin/reconnect/offer-fallback/turn-refresh outcomes.
   - Log event schema: `{ event, phase, attempt, transport, durationMs, outcome, error? }`.

3. **Add bounded rejoin resend policy:**
   - On signaling reconnect with active room, always send join with `reconnectCid` + `reconnectToken`.
   - Retry join resend on recovery timer up to 2 times before surfacing error.

4. **Add offer/answer send reliability:**
   - For `offer` and `answer` messages only (not ICE candidates): if the expected reciprocal message (answer for offer, offer for answer) does not arrive within `OFFER_TIMEOUT_MS`, resend the original message once.
   - Cancel the resend timer immediately on receiving the expected response.
   - Cap at 1 resend per negotiation round to avoid loops.
   - Rationale: leverages the existing offer timeout infrastructure; no protocol changes needed.

5. **Fix offer timeout recovery to always schedule ICE restart:**
   - Web (`WebRTCContext.tsx:834`): currently only rolls back if `signalingState === 'have-local-offer'`. If state transitioned unexpectedly (late remote offer arrived), the handler returns without scheduling retry or ICE restart.
   - Fix: on offer timeout, always schedule ICE restart regardless of current signaling state. The negotiation is clearly stalled.

6. **Android thread safety audit:**
   - `CallManager.kt:107`: replace `joinAttemptSerial++` with `AtomicInteger.incrementAndGet()`.
   - `CallManager.kt:118`: synchronize access to `pendingMessages` (`ArrayDeque`) or replace with `ConcurrentLinkedDeque`.
   - `SignalingClient.kt:38-46`: add `@Volatile` to `connected` and `connecting` flags.
   - General: audit all mutable state accessed from both the signaling callback thread and the main/coroutine threads.

7. **Standardize snapshot preparation timeout (all clients):**
   - Android (`CallManager.kt:899`): `prepareSnapshotId` has no timeout. If the snapshot API hangs, the entire join flow stalls.
   - iOS (`CallManager.swift:1525`): `prepareSnapshotId` is callback-based with no explicit timeout; a hung snapshot API delays the join signal.
   - Web (`CallRoom.tsx:582`): already has a 1200ms `Promise.race` timeout — use as the reference pattern.
   - Fix: add `SNAPSHOT_PREPARE_TIMEOUT_MS` (2s) timeout on iOS and Android; proceed without snapshot on timeout. Web already conforms (its 1200ms is within the 2s budget).

8. **iOS remote video track deduplication:**
   - `WebRtcEngine.swift` delivers remote video tracks from three separate code paths (`didAdd stream` :1092, `didAdd rtpReceiver` :1503, callback :1538) with no deduplication.
   - Add a `remoteVideoTrackDelivered` guard flag; reset on peer connection teardown.

9. **Web listener array mutation safety:**
   - `SignalingContext.tsx:148`: copy the array before iteration (`[...listenersRef.current].forEach(...)`) to prevent mutation during callback dispatch.

10. **Cap ICE candidate buffer on web and Android:**
    - Web (`WebRTCContext.tsx:222`): `iceBufferRef` grows without limit.
    - Android (`WebRtcEngine.kt:200`): `pendingIceCandidates` grows without limit.
    - Cap at `ICE_CANDIDATE_BUFFER_MAX` (50); drop oldest on overflow and log a warning.
    - Flush buffer + log warning if >30s elapses without remote description being set.

### Phase 3: Error Surfacing and Diagnostics

1. **Define unified error state machine across all platforms:**

   ```
   ┌──────────┐  timeout/  ┌──────────┐  max retries  ┌────────┐
   │recovering├───failure──►│ retrying  ├───exhausted───►│ failed │
   └────┬─────┘            └─────┬─────┘               └────┬───┘
        │ success                │ success                   │ user tap
        ▼                        ▼                           ▼
   ┌──────────┐            ┌──────────┐               ┌──────────┐
   │ connected│            │ connected│               │  leave   │
   └──────────┘            └──────────┘               └──────────┘
   ```

   States and user-visible behavior:
   | State | Trigger | User sees | Auto-action |
   |---|---|---|---|
   | `recovering` | ICE disconnected, signaling transport dropped | Subtle "reconnecting…" indicator (e.g., banner or spinner overlay) | Kickstart + backoff reconnect |
   | `retrying` | Recovery timer expired, attempting next strategy | Same indicator, optional "taking longer than usual" after 10s | Escalate: ICE restart → offer resend → rejoin → transport fallback |
   | `failed` | All retry budgets exhausted (`JOIN_HARD_TIMEOUT_MS`, reconnect attempts, offer retries) | Full-screen "Call failed" with "Try again" / "Leave" buttons | Stop all timers, clear reconnect identity |

   Platform mapping:
   - iOS: map to `CallPhase.reconnecting` → `.error` (existing phases).
   - Android: map to `CallUiState.Reconnecting` → `.Error` (extend existing sealed class).
   - Web: add `connectionStatus` state to `WebRTCContext` (`connected` | `recovering` | `retrying` | `failed`), render as overlay in `CallRoom.tsx`.

2. **Standardize media constraint fallback (web):**
   - `WebRTCContext.tsx:564`: currently hardcodes `sampleRate: 48000` as exact constraint.
   - Change to `ideal` constraint; add a catch-and-retry with relaxed constraints (`{ audio: true, video: true }`) on `getUserMedia` failure.
   - Prevents call failure on devices that don't support 48kHz.

3. **Debounce device enumeration on web:**
   - `WebRTCContext.tsx:120`: `detectCameras()` fires on every `roomState` change.
   - Call once on mount + listen to `navigator.mediaDevices.addEventListener('devicechange', ...)` instead.

### Phase 4: Verification and Rollout
1. **Add targeted tests per client:**
   - Join timeout + kickstart + recovery transitions.
   - TURN missing/slow path still negotiates via default ICE.
   - Signaling reconnect auto-rejoin with `reconnectCid` + `reconnectToken`.
   - Offer timeout rollback + ICE restart path (including non-`have-local-offer` states).
   - Offer/answer resend on timeout (single retry, cancellation on response).
   - Non-host fallback guard rails and retry bounds.
   - SSE sid reuse behavior (web-specific).
   - WS→SSE fallback after consecutive WS failures.
   - TURN credential refresh at 80% TTL.
   - Ping/pong liveness detection and reconnect trigger.
   - Network change → ICE restart (web-specific).
   - ICE candidate buffer cap and flush behavior.
   - Android thread safety under concurrent signaling callbacks.
   - Error state machine transitions: recovering → retrying → failed → leave.

2. **Add cross-client chaos scenarios:**
   - `BLOCK_WEBSOCKET=hang` and `BLOCK_WEBSOCKET=block` server modes.
   - Mid-call network flap (offline/online).
   - Reconnect storm with ghost eviction (`reconnectCid`) behavior.
   - TURN endpoint delay simulation > 2s.
   - TURN credential expiry mid-call (server returns expired-token error).
   - WS degrades mid-call (accept then RST after 5s) → verify SSE fallback on all platforms.
   - Concurrent reconnects with same `reconnectCID` (verify no duplicate CIDs server-side).
   - Half-open connection simulation (drop pong responses for >24s).

3. **Acceptance gates:**

   **Client-side:**
   - No indefinite join state > 15s.
   - Join success under TURN-delay scenarios remains functional via default ICE.
   - Reconnect recovers signaling + media path without manual user action.
   - No regression in host override/deep-link behavior for iOS/Android.
   - WS degradation mid-call leads to SSE fallback within 3 reconnect cycles (all platforms).
   - Offer/answer loss during transport hiccup recovers within `OFFER_TIMEOUT_MS` + one resend.
   - TURN credentials refresh successfully before expiry on calls >24 minutes.
   - Error state machine reaches `failed` (not stuck in `recovering`) when all retries exhausted.
   - All three platforms show consistent user-facing state for each error phase.

   **Server-side:**
   
   - Ghost eviction completes atomically: no window where two clients share a CID.
   - `reconnectToken` validation rejects forged reconnect attempts.
   - SSE clients in active rooms survive 5 minutes of receive-only inactivity.
   - `turn-refresh` response latency < 500ms at p99.
   - HTTP WriteTimeout prevents goroutine count growth under slow-client load.

---

## Important API / Interface Changes
1. **Web signaling transport factory:**
   - Extend `createSignalingTransport(kind, handlers, options?)` with optional SSE session id reuse input.
2. **Web SSE transport:**
   - Constructor/options support optional injected `sid` and expose current sid for reuse bookkeeping.
3. **Internal call lifecycle state models:**
   - Add explicit join-attempt metadata/timers in web and Android signaling/call managers.
   - Add `connectionStatus` state (`connected` | `recovering` | `retrying` | `failed`) to web `WebRTCContext` and equivalent enums on iOS/Android.
4. **Transport fallback (all clients):**
   - Add consecutive-failure counter to signaling client on each platform; extend `shouldFallback()` to allow SSE after `WS_FALLBACK_CONSECUTIVE_FAILURES` even when `transportConnectedOnce` is true.
   - Web: `SignalingContext.tsx`, iOS: `SignalingClient.swift`, Android: `SignalingClient.kt`.
5. **Protocol/server API:**
   - **New signaling message:** `turn-refresh` (request from client) → `turn-refreshed` (response with new token + credentials). Only valid when client is in a room.
   - **Extended `joined` payload:** adds `reconnectToken` field (HMAC-based proof for future reconnect).
   - **Extended `join` payload:** accepts optional `reconnectToken` alongside `reconnectCid`.
   - All other wire-protocol messages (`join`, `offer/answer/ice`, `leave`, `room_state`, `end_room`, `error`) remain unchanged.

---

## Resilience Constants Reference

All clients must define these constants with matching values. CI validates per-platform definitions against this table.

| Constant | Value | Unit | Rationale |
|---|---|---|---|
| `CONNECT_TIMEOUT_MS` | 2000 | ms | Max wait for transport handshake |
| `RECONNECT_BACKOFF_BASE_MS` | 500 | ms | Initial reconnect delay |
| `RECONNECT_BACKOFF_CAP_MS` | 5000 | ms | Max reconnect delay |
| `JOIN_PUSH_ENDPOINT_WAIT_MS` | 250 | ms | Grace period for push endpoint registration before join |
| `JOIN_CONNECT_KICKSTART_MS` | 1200 | ms | Re-send join if no response after this |
| `JOIN_RECOVERY_MS` | 4000 | ms | Second-tier join recovery timer |
| `JOIN_HARD_TIMEOUT_MS` | 15000 | ms | Absolute join attempt deadline |
| `TURN_FETCH_TIMEOUT_MS` | 2000 | ms | Max wait for TURN credential fetch |
| `TURN_REFRESH_TRIGGER_RATIO` | 0.8 | ratio | Refresh TURN at 80% of TTL |
| `OFFER_TIMEOUT_MS` | 8000 | ms | Max wait for answer after sending offer |
| `ICE_RESTART_COOLDOWN_MS` | 10000 | ms | Min interval between ICE restarts |
| `NON_HOST_FALLBACK_DELAY_MS` | 4000 | ms | Wait before non-host sends fallback offer |
| `NON_HOST_FALLBACK_MAX_ATTEMPTS` | 2 | count | Max non-host fallback offers per negotiation |
| `WS_FALLBACK_CONSECUTIVE_FAILURES` | 3 | count | WS failures before allowing SSE fallback |
| `PONG_MISS_THRESHOLD` | 2 | count | Missed pongs before treating connection as dead |
| `ICE_CANDIDATE_BUFFER_MAX` | 50 | count | Max buffered ICE candidates before flush/warn |
| `SNAPSHOT_PREPARE_TIMEOUT_MS` | 2000 | ms | Max wait for snapshot preparation before join |

---

## Error State Machine

```
                  ┌─────────────────────────────────────────────┐
                  │              CONNECTED                       │
                  │  (normal call operation)                     │
                  └──────┬──────────────────────────┬───────────┘
                         │ ICE disconnected /       │ explicit leave /
                         │ signaling dropped /      │ end room
                         │ network change           │
                         ▼                          ▼
                  ┌──────────────┐           ┌───────────┐
                  │  RECOVERING  │           │   IDLE    │
                  │              │           └───────────┘
                  │ "Reconnect-  │
                  │  ing…" banner│
                  └──────┬───────┘
                         │ recovery timer expired /
                         │ kickstart failed
                         ▼
                  ┌──────────────┐
                  │   RETRYING   │
                  │              │◄──── escalation sequence:
                  │ "Taking      │  1. ICE restart
                  │  longer…"    │  2. offer/answer resend
                  │  (after 10s) │  3. rejoin with reconnectCid
                  └──────┬───────┘  4. transport fallback
                         │
              ┌──────────┼──────────┐
              │ success  │          │ all retries exhausted
              ▼          │          ▼
       ┌──────────┐      │   ┌───────────┐
       │CONNECTED │      │   │  FAILED   │
       └──────────┘      │   │           │
                         │   │ "Call      │
                         │   │  failed"  │
                         │   │ [Retry]   │
                         │   │ [Leave]   │
                         │   └─────┬─────┘
                         │         │ user taps Retry
                         │         ▼
                         │   ┌───────────┐
                         └──►│RECOVERING │ (fresh attempt)
                             └───────────┘
```

Transitions:

| From | To | Trigger | Client action |
|---|---|---|---|
| `connected` | `recovering` | ICE `disconnected`, signaling transport error, network change event | Start reconnect backoff, show subtle indicator |
| `recovering` | `connected` | ICE `connected`, signaling re-established | Hide indicator, resume normal operation |
| `recovering` | `retrying` | `JOIN_RECOVERY_MS` elapsed without recovery | Escalate: ICE restart → offer resend → rejoin → transport fallback |
| `retrying` | `connected` | Any escalation step succeeds | Hide indicator, resume |
| `retrying` | `failed` | `JOIN_HARD_TIMEOUT_MS` elapsed, all escalation steps exhausted | Stop all timers, show full-screen error, clear reconnect identity |
| `failed` | `recovering` | User taps "Retry" | Fresh join attempt with new attempt ID |
| `failed` | `idle` | User taps "Leave" | Navigate to home screen |

---

## Test Cases and Scenarios
1. Join with valid TURN token and healthy network.
2. Join with missing TURN token in `joined` payload.
3. Join with TURN fetch delayed beyond `TURN_FETCH_TIMEOUT_MS`.
4. WS blocked/hanging, fallback to SSE, then successful join.
5. Mid-call signaling disconnect and auto-rejoin with `reconnectCid` + `reconnectToken`.
6. ICE `DISCONNECTED` then recovery via host ICE restart.
7. Offer timeout rollback and retry path (including non-`have-local-offer` state).
8. Host-offer stall resolved by guarded non-host fallback.
9. Deep-link call on one-off host (iOS/Android) with successful TURN fetch.
10. Explicit leave/end-room clears reconnect identity and prevents unintended rejoin.
11. WS connects, works, then permanently fails mid-call → client falls back to SSE after 3 failures (all platforms).
12. Offer sent, transport hiccups, answer never arrives → offer resent once after `OFFER_TIMEOUT_MS`.
13. TURN credentials approach expiry → client sends `turn-refresh`, receives new credentials, updates ICE config.
14. Call lasting >30 minutes maintains TURN relay throughout via credential refresh.
15. Two consecutive pings with no pong → client triggers reconnect.
16. Web: device goes offline then online → ICE restart triggered proactively.
17. Forged `reconnectCid` without valid `reconnectToken` → server rejects join, returns error.
18. Concurrent reconnects with same CID → server evicts ghost atomically, no duplicate CIDs.
19. SSE-only client in active room idle for 3 minutes → not evicted by server stale reaper.
20. All retries exhausted → user sees "Call failed" screen with Retry/Leave options on all platforms.

---

## Assumptions and Defaults
1. ~~Keep protocol v1 and server behavior unchanged; all fixes are client-side.~~ Server changes are included in Phase 0 to address critical reliability gaps (TURN refresh, ghost eviction, reconnect auth, SSE timeout).
2. Keep host-as-offerer as the primary negotiation rule.
3. Use hybrid fallback as selected: host watchdog + guarded non-host fallback.
4. No new dependencies; implement with existing platform primitives.
5. Keep existing camera mode semantics untouched (`selfie -> world -> composite` and iOS composite skip behavior).
6. Wire-protocol additions are minimal and backward-compatible: `turn-refresh`/`turn-refreshed` messages, `reconnectToken` field in `joined`/`join` payloads. Older clients that don't send `turn-refresh` or `reconnectToken` continue to work (server treats missing token as legacy client).
7. Error state machine defines user-visible behavior; per-platform visual design (colors, animations, layout) is left to each client's design system.
