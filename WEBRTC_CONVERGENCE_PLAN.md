## Cross-Client WebRTC Join/Rejoin/Reconnect Reliability Convergence Plan

### Summary
Unify web, iOS, and Android around one resilience profile that keeps call setup fast and recoverable under transport drops, TURN latency, and negotiation stalls.
Primary policy (selected): **hybrid recovery**: host-offerer remains primary, with guarded non-host fallback for rare stalled-negotiation cases.

> **Last updated: 2026-02-28.** The majority of the original plan has been implemented. This document now reflects the current state of the codebase and focuses on the remaining work items.

---

### Implementation Status Overview

| Area | Server | Web | iOS | Android |
|---|---|---|---|---|
| Resilience constants (centralized) | N/A | Done | Done | Done |
| Join lifecycle (kickstart + recovery + hard timeout) | N/A | Done | Done | Done |
| ICE bootstrap (STUN-first, non-blocking) | N/A | Done | Done | Done |
| TURN fetch timeout (2s) | N/A | Done | Done | **Constant exists but not wired** |
| Non-host fallback offer | N/A | Done | Done | Done |
| SSE session continuity (sid reuse) | N/A | Done | Done | Done |
| Transport fallback (consecutive WS failure counting) | N/A | Done | Done | Done |
| Ping/pong liveness (2-miss threshold) | N/A | Done | Done | Done |
| Network change → ICE restart | N/A | Done | Done | Done |
| TURN credential refresh (80% TTL) | Done | Done | Done | Done |
| Ghost eviction race fix | Done | N/A | N/A | N/A |
| SSE stale timeout (5min in-room) | Done | N/A | N/A | N/A |
| `reconnectToken` auth | Done | Done | Done | Done |
| Offer timeout → ICE restart (all states) | N/A | Done | Done | Done |
| Listener array mutation safety | N/A | Done | N/A | N/A |
| ICE candidate buffer cap (50) | N/A | Done | N/A | Done |
| Snapshot preparation timeout (2s) | N/A | Done | Done | Done |
| iOS remote video track deduplication | N/A | N/A | Done | N/A |
| Media constraint fallback (ideal + retry) | N/A | Done | N/A | N/A |
| Device enumeration (mount + devicechange only) | N/A | Done | N/A | N/A |
| Unified error state machine | N/A | **Not done** | **Not done** | **Not done** |
| Reconnect observability (structured logs) | N/A | **Not done** | **Not done** | **Not done** |
| Android thread safety audit | N/A | N/A | N/A | **Partial** |

---

### Current State by Platform (as of 2026-02-28)

#### Server (`server/`)
All Phase 0 items are complete:
- **Ghost eviction** (`signaling.go:268-311`): Single-pass mark-and-sweep. Ghost removed from `room.Participants` under room lock; hub cleanup deferred outside lock.
- **TURN token TTL**: Extended to 30 minutes (`signaling.go:20`). Clients refresh at 80% TTL via `turn-refresh`/`turn-refreshed` signaling messages (`signaling.go:379-406`).
- **SSE stale timeout** (`sse.go:15-21`): Dual timeout — 60s for idle clients, 5 minutes for in-room clients.
- **`reconnectToken`** (`signaling.go:262-276`): HMAC-SHA256 token issued in `joined` payload, validated on reconnect. Legacy clients without token still accepted (intentional backward compatibility).
- **HTTP timeouts**: `WriteTimeout: 0` (required for SSE long-lived connections); per-handler timeouts via `http.TimeoutHandler` on all short-lived API routes (15s for TURN/room-id, 5s for stats).

**Note**: TURN token (30min) and TURN credentials (15min, `turn_auth.go:145`) have different TTLs. This is acceptable because clients proactively refresh at 80% of the token TTL (24min), well before credentials expire.

#### Web Client (`client/`)
Resilience constants: `client/src/constants/webrtcResilience.ts` — all 17 constants matching the reference table.

Implemented in `SignalingContext.tsx`:
- 3-tier join lifecycle with attempt-id fencing (kickstart 1.2s, recovery 4s, hard 15s)
- SSE sid reuse via `sseSidRef` — saved before close, injected on reconnect
- Consecutive WS failure counting (`wsConsecutiveFailuresRef`) with SSE fallback after 3 failures
- Pong tracking (`lastPongAtRef`, `missedPongsRef`) — reconnect after 2 missed pongs
- TURN refresh timer at 80% TTL → sends `turn-refresh`, handles `turn-refreshed`
- Listener array copy before iteration (`[...listenersRef.current].forEach(...)`)

Implemented in `WebRTCContext.tsx`:
- Non-blocking ICE bootstrap: default STUN config immediately, TURN upgrades asynchronously
- 2s TURN fetch timeout via AbortController
- Offer timeout handles unexpected signaling states (else-branch schedules ICE restart)
- Network change listeners: `window.online` + `navigator.connection.change` → ICE restart
- ICE candidate buffer capped at 50 (drop oldest on overflow)
- Media constraints use `{ ideal: 48000 }` with full constraint-relaxation retry
- Device enumeration: mount + `devicechange` event only (no `roomState` dependency)

#### iOS Client (`client-ios/`)
Resilience constants: `Sources/Core/Call/WebRtcResilienceConstants.swift` — all constants matching the reference table.

Implemented in `CallManager.swift`:
- 3-tier join lifecycle (kickstart 1.2s, recovery 4s, hard 15s)
- STUN-first ICE bootstrap via `applyDefaultIceServers()`, TURN upgrades asynchronously
- TURN fetch uses `currentSignalingHost()` with 2s race timeout
- Host-offer + non-host fallback offer (4s delay, max 2 attempts)
- TURN credential refresh at 80% TTL
- NWPathMonitor → ICE restart on network recovery

Implemented in `SignalingClient.swift`:
- SSE sid preserved across same-host reconnects (reset on host change or explicit close)
- Consecutive WS failure counting with SSE fallback after 3 failures
- Pong tracking with 2-miss threshold

Implemented in `WebRtcEngine.swift`:
- Remote video track deduplication via `remoteVideoTrackDelivered` guard flag

Implemented in `JoinSnapshotFeature.swift`:
- 2s snapshot preparation timeout via `withTimeout`

**Note**: No explicit `.reconnecting` CallPhase — reconnecting state is modeled as a boolean (`isReconnecting`) on `CallUiState` alongside the current phase.

#### Android Client (`client-android/`)
Resilience constants: `WebRtcResilienceConstants.kt` — all constants matching the reference table.

Implemented in `CallManager.kt`:
- 3-tier join lifecycle (kickstart 1.2s, recovery 4s, hard 15s)
- ICE bootstrap: waits for TURN fetch result (or fallback to default STUN) before creating peer connection
- Host-offer + non-host fallback offer (4s delay, max 2 attempts)
- TURN credential refresh at 80% TTL
- ConnectivityManager `onAvailable` → ICE restart when in call
- 2s snapshot preparation timeout in `JoinSnapshotFeature.kt`

Implemented in `SignalingClient.kt`:
- SSE sid preserved across same-host reconnects
- Consecutive WS failure counting with SSE fallback after 3 failures
- Pong tracking with 2-miss threshold
- `@Volatile` on `connected` and `connecting` flags

Implemented in `WebRtcEngine.kt`:
- ICE candidate buffer capped at 50

---

### Remaining Work Items

#### 1. Android: Wire TURN fetch timeout to OkHttpClient

`TURN_FETCH_TIMEOUT_MS = 2000` exists in `WebRtcResilienceConstants.kt` but is dead code. The `OkHttpClient` in `ApiClient.kt` uses default timeouts (10s connect, 10s read). Either:
- Set `callTimeout(2, TimeUnit.SECONDS)` on the OkHttpClient used for TURN fetch, or
- Add a coroutine-level `withTimeout(TURN_FETCH_TIMEOUT_MS)` wrapper around the fetch call in `CallManager.kt`

Impact: Low. Default STUN fallback still works. But the 2s budget matches iOS/web behavior and prevents a stalled TURN fetch from delaying call setup by up to 20s.

#### 2. Unified error state machine (all platforms)

The error state machine (`connected` → `recovering` → `retrying` → `failed`) has not been implemented on any platform. Current state:
- **Web**: Only raw ICE/connection/signaling states exposed. No `connectionStatus` in `WebRTCContextValue`.
- **iOS**: `CallPhase.error` exists; `isReconnecting` is a boolean, not a phased state machine.
- **Android**: `CallPhase.Error` with optional message fields; no `Reconnecting` sub-states.

Implementation approach:
- **Web**: Add `connectionStatus: 'connected' | 'recovering' | 'retrying' | 'failed'` to `WebRTCContext`. Derive from ICE state + signaling state + timer state. Render as overlay in `CallRoom.tsx`.
- **iOS**: Map `isReconnecting = true` → `recovering`; escalation timers → `retrying`; `CallPhase.error` → `failed`. Expose as a computed property on `CallUiState`.
- **Android**: Extend `CallUiState` with `connectionStatus` enum. Map from `isReconnecting` + timer state + `CallPhase.Error`.

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

#### 3. Reconnect observability (structured log events)

All platforms log reconnect/recovery events, but formats are inconsistent. Standardize to a common schema for debugging and analytics:

```
{ event, phase, attempt, transport, durationMs, outcome, error? }
```

Examples: `join-kickstart-fired`, `turn-refresh-success`, `ws-fallback-triggered`, `pong-timeout-reconnect`, `non-host-fallback-offer-sent`.

This is a low-priority quality-of-life improvement, not a reliability blocker.

#### 4. Android thread safety

Current state: all mutable state in `CallManager.kt` is accessed from the main handler thread by convention (documented in a comment at line 107). `SignalingClient.kt` has `@Volatile` on `connected`/`connecting` but not on `wsConsecutiveFailures`, `missedPongs`, `transportIndex`, or `activeAttemptId` — these are accessed through the handler callback chain.

This is safe by discipline but not enforced by the type system. Two options:
- **Accept as-is**: Document the threading model. The handler-thread discipline is consistent and adding atomics/locks would add complexity with no functional benefit.
- **Harden**: Replace `joinAttemptSerial` with `AtomicLong`, wrap `pendingMessages` with `synchronized`, add `@Volatile` to remaining SignalingClient fields. Marginal safety gain.

Recommendation: Accept as-is with documented threading contract. The current code has no observed race conditions and the handler pattern is idiomatic Android.

#### 5. Android room-state parsing resilience

`parseRoomState()` in `CallManager.kt` returns `null` when `hostCid` is blank, which causes `room_state` messages to be silently dropped. iOS is more lenient (processes partial state). Consider making Android match iOS behavior: proceed with available data even if `hostCid` is missing, using the last known host as fallback.

Impact: Very low. Server always sends `hostCid`. Only relevant if server has a bug or protocol evolves.

#### 6. ICE candidate buffer stale flush (nice-to-have)

The 50-candidate cap is implemented on web and Android, but the plan also called for flushing the buffer with a warning if >30s elapses without remote description being set. This timer is not implemented on any platform. In practice, the offer timeout (8s) + ICE restart cycle handles stalled negotiations before 30s elapses, making this redundant.

Recommendation: Skip. The offer timeout path covers this scenario.

---

### Completed Work (Reference)

<details>
<summary>Phase 0: Server Hardening — ALL COMPLETE</summary>

1. **Ghost eviction race fix** — Single-pass mark-and-sweep pattern in `signaling.go:268-311`
2. **TURN credential refresh** — `turn-refresh`/`turn-refreshed` message pair, 30-min token TTL
3. **SSE stale timeout** — Dual timeout (60s idle, 5min in-room) in `sse.go:15-21`
4. **`reconnectToken` auth** — HMAC-SHA256 token in `joined` payload, validated on reconnect with legacy bypass

</details>

<details>
<summary>Phase 1: Critical Client Reliability Parity — ALL COMPLETE</summary>

1. **Resilience constants** — Centralized per-platform: `webrtcResilience.ts`, `WebRtcResilienceConstants.swift`, `WebRtcResilienceConstants.kt`. All values match.
2. **Non-blocking ICE bootstrap** — Web/iOS: default STUN first, TURN upgrades. Android: waits for TURN/STUN before PC creation (acceptable — completes within 2s TURN fetch budget).
3. **iOS TURN host fix** — Uses `currentSignalingHost()` for TURN fetch
4. **Join lifecycle parity** — All three clients have 3-tier timers (1.2s kickstart, 4s recovery, 15s hard timeout) with attempt-id fencing
5. **Negotiation recovery (hybrid)** — Host-as-offerer primary + non-host fallback (4s delay, max 2 attempts) on all platforms
6. **SSE session continuity** — Web: `sseSidRef` injection. iOS/Android: sid preserved across same-host reconnects.
7. **Transport fallback** — Consecutive WS failure counting (threshold 3) on all platforms; `shouldFallback()` allows SSE even after prior WS success.
8. **TURN credential refresh** — 80% TTL proactive refresh on all platforms via `turn-refresh` signaling message
9. **Ping/pong liveness** — 2-miss threshold triggers reconnect on all platforms
10. **Network change (web)** — `window.online` + `navigator.connection.change` → ICE restart

</details>

<details>
<summary>Phase 2: Robustness Fixes — MOSTLY COMPLETE</summary>

- **Offer timeout → ICE restart (web)** — else-branch handles unexpected signaling states
- **Snapshot timeout (iOS/Android)** — 2s timeout via platform-appropriate wrappers
- **iOS remote video track dedup** — `remoteVideoTrackDelivered` guard flag
- **Web listener array mutation safety** — Spread copy before iteration
- **ICE candidate buffer cap (web/Android)** — Capped at 50

Not done:
- Room-state parsing resilience (Android) — low impact
- Reconnect observability — quality-of-life, not blocking
- Android thread safety — working by convention, recommend accepting as-is

</details>

<details>
<summary>Phase 3: Error Surfacing — PARTIALLY COMPLETE</summary>

Done:
- **Media constraint fallback (web)** — `ideal` + retry path
- **Device enumeration (web)** — mount + `devicechange` only

Not done:
- **Unified error state machine** — Not implemented on any platform (see Remaining Work Item #2)

</details>

---

### API / Interface Changes (Implemented)

1. **Web SSE transport**: Constructor accepts optional `sid` for reuse; exposes `getSessionId()`.
2. **Join lifecycle state**: All platforms have join-attempt metadata/timers with attempt-id fencing.
3. **Transport fallback**: All platforms have consecutive-failure counter extending `shouldFallback()`.
4. **Protocol/server**: `turn-refresh`/`turn-refreshed` messages, `reconnectToken` in `joined`/`join` payloads — all implemented and backward-compatible.

Remaining API change:
- `connectionStatus` state (`connected` | `recovering` | `retrying` | `failed`) to be added to web `WebRTCContext` and equivalent models on iOS/Android (see Remaining Work Item #2).

---

### Resilience Constants Reference

All clients define these constants with matching values. Canonical locations:
- Web: `client/src/constants/webrtcResilience.ts`
- iOS: `client-ios/Sources/Core/Call/WebRtcResilienceConstants.swift`
- Android: `client-android/app/src/main/java/app/serenada/android/call/WebRtcResilienceConstants.kt`

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

### Test Cases and Scenarios

Scenarios 1-20 remain relevant. Tests should be written against the implemented behavior:

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

### Assumptions and Defaults
1. Server changes (Phase 0) are complete. Protocol additions (`turn-refresh`/`turn-refreshed`, `reconnectToken`) are deployed and backward-compatible.
2. Host-as-offerer is the primary negotiation rule.
3. Hybrid fallback (host watchdog + guarded non-host fallback) is implemented on all platforms.
4. No new dependencies; all features use existing platform primitives.
5. Camera mode semantics are untouched (`selfie → world → composite`).
6. Error state machine defines user-visible behavior; per-platform visual design is left to each client's design system.
