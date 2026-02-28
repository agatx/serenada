# Unified Error State Machine Plan

## Goal

Replace the ad-hoc reconnecting indicators on all three platforms with a consistent, well-defined **connection status state machine** that retries indefinitely without requiring user interaction:

```
┌──────────┐  timeout/  ┌──────────┐
│recovering├───10s──────►│ retrying  │
└────┬─────┘            └─────┬─────┘
     │ success                │ success
     ▼                        ▼
┌──────────┐          ┌──────────┐
│ connected│          │ connected│
└──────────┘          └──────────┘
```

No `failed` state. The system retries with exponential backoff until the server confirms the room is gone or the user chooses to leave.

## State Definitions

| State | Trigger | User sees | Auto-action |
|---|---|---|---|
| `connected` | ICE `connected`/`completed`, signaling up | Nothing (normal call) | — |
| `recovering` | ICE `disconnected`, signaling transport dropped, network change | Subtle "Reconnecting…" badge (after 800ms debounce) | Kickstart + backoff reconnect |
| `retrying` | 10s in `recovering` without recovery | Badge with "Taking longer than usual…" sub-text + inline "Leave call" link | Continue escalation with exponential backoff: ICE restart → offer resend → rejoin → transport fallback → repeat |

## State Transitions

| From | To | Trigger |
|---|---|---|
| `connected` | `recovering` | ICE `disconnected`/`failed`, signaling transport error, network change event |
| `recovering` | `connected` | ICE `connected`/`completed`, signaling re-established |
| `recovering` | `retrying` | 10s elapsed without recovery |
| `retrying` | `connected` | Any reconnect attempt succeeds |
| `retrying` | `idle` | User taps "Leave call" in badge, OR server confirms room is gone (other peer left, room expired) |

## Current State per Platform

### Web (`CallRoom.tsx` + `WebRTCContext.tsx`)
- **`showReconnecting`** boolean derived from `!isConnected || iceState disconnected/failed || connState disconnected/failed`
- 800ms debounce before showing a "Connecting..." badge overlay
- No escalation from recovering → retrying
- No `connectionStatus` concept in context

### iOS (`CallManager.swift` + `CallScreen.swift`)
- **`CallPhase`** enum: `idle | creatingRoom | joining | waiting | inCall | ending | error`
- **`isReconnecting`** boolean in `CallUiState`
- `shouldShowCallStatusLabel()` shows "Reconnecting..." capsule when phase is `.inCall` and ICE/signaling is degraded
- Error phase shows error screen via existing routing, but only for terminal errors (room not found, join timeout, etc.)
- No intermediate `retrying` state — jumps from reconnecting to error only on hard timeout

### Android (`CallManager.kt` + `CallScreen.kt` + `ErrorScreen.kt`)
- **`CallPhase`** enum: `Idle | CreatingRoom | Joining | Waiting | InCall | Ending | Error`
- **`isReconnecting`** derived in `CallScreen.kt` from ICE/connection/signaling states
- 800ms debounce `LaunchedEffect` before showing "Connecting..." badge
- `ErrorScreen` exists but only has a "Back" button (dismiss), no Retry
- Same gap as iOS: no `retrying` state, no escalation messaging

---

## Implementation Plan

### Step 1: Define `ConnectionStatus` enum/type on each platform

**Web** — `client/src/contexts/WebRTCContext.tsx`
```typescript
type ConnectionStatus = 'connected' | 'recovering' | 'retrying';
```
- Add `connectionStatus` state and expose it from the context
- Add `retryingTimerRef` (10s → set `retrying`)
- Compute transitions in the existing `iceConnectionState`/`connectionState` effect:
  - Any degraded signal → `recovering` (start 10s timer)
  - Restored → `connected` (clear timer)
  - 10s timer fires → `retrying`

**iOS** — `client-ios/Sources/Core/Models/ConnectionStatus.swift` (new file)
```swift
enum ConnectionStatus: String, Equatable {
    case connected, recovering, retrying
}
```
- Add `connectionStatus: ConnectionStatus` to `CallUiState`
- Drive transitions in `CallManager` when `phase == .inCall` and ICE/signaling states change

**Android** — `client-android/.../call/ConnectionStatus.kt` (new file)
```kotlin
enum class ConnectionStatus { Connected, Recovering, Retrying }
```
- Add `connectionStatus: ConnectionStatus` to `CallUiState`
- Drive transitions in `CallManager` when `phase == InCall` and ICE/signaling states change

### Step 2: Drive state transitions in CallManager / WebRTCContext

**All platforms — same logic:**

```
on ICE disconnected/failed OR signaling dropped OR network change:
    if connectionStatus == connected:
        connectionStatus = recovering
        start retryingTimer (10s)

on retryingTimer fires:
    if connectionStatus == recovering:
        connectionStatus = retrying
        // existing escalation machinery continues with exponential backoff
        // cycle: ICE restart → offer resend → rejoin → transport fallback → repeat

on ICE connected/completed AND signaling connected:
    connectionStatus = connected
    cancel retryingTimer

on server confirms room gone (other peer left, room expired):
    leaveRoom / endRoom as normal

on user taps "Leave call" (from retrying badge):
    leaveRoom / endRoom as normal
```

**Web** (`WebRTCContext.tsx`):
- Add `connectionStatusRef` + `connectionStatus` state
- Add `retryingTimerRef`
- Hook into existing `oniceconnectionstatechange` and signaling `isConnected` changes
- Expose `connectionStatus` from context

**iOS** (`CallManager.swift`):
- Add `connectionStatusRetryingTimer` (DispatchWorkItem)
- In `handleIceConnectionStateChange` and signaling state callbacks, apply transition logic
- Only active when `phase == .inCall`

**Android** (`CallManager.kt`):
- Add `connectionStatusRetryingRunnable` posted to handler
- In `onIceConnectionChange` and signaling state callbacks, apply transition logic
- Only active when `phase == InCall`

### Step 3: Update UI rendering on each platform

**Web** (`CallRoom.tsx`):
- Remove `showReconnecting` state and its 800ms debounce effect
- Replace with reading `connectionStatus` from `useWebRTC()`
- Render based on status:
  - `connected` → nothing
  - `recovering` → "Reconnecting…" badge (keep 800ms debounce before showing)
  - `retrying` → same badge + "Taking longer than usual…" sub-text + "Leave call" link

**iOS** (`CallScreen.swift`):
- Keep `shouldShowCallStatusLabel` for `recovering` and `retrying` states
- For `recovering`: "Reconnecting…" capsule (existing)
- For `retrying`: "Reconnecting… Taking longer than usual" capsule + "Leave call" tap target

**Android** (`CallScreen.kt`):
- Replace `isReconnecting` derivation with reading `uiState.connectionStatus`
- For `recovering`: "Reconnecting…" badge (with 800ms debounce)
- For `retrying`: same badge + "Taking longer than usual…" sub-text + "Leave call" tap target

### Step 4: Guard state machine — only active during `inCall` phase

The `connectionStatus` state machine is only meaningful when the user is in an active call:
- On `joinRoom`: reset to `connected`
- On `leaveRoom` / `endRoom` / phase transition away from `inCall`: reset to `connected`, cancel all timers
- Do NOT drive transitions during `joining` or `waiting` phases (those have their own timeout logic via `joinAttemptId` / kickstart / recovery / hard timeout)

---

## Design Decisions (Finalized)

1. **Badge copy**: **"Reconnecting…"** on all platforms. The web client currently says "Connecting..." — update it to match iOS/Android.

2. **"Taking longer than usual" threshold**: **10 seconds**. Long enough to avoid false alarms on brief network hiccups.

3. **No failed state / no full-screen overlay**: The system retries indefinitely with exponential backoff. The user is never forced to interact — if the connection comes back after minutes, the call auto-resumes. The `retrying` badge includes an inline "Leave call" link for users who want to give up voluntarily.

4. **Retry strategy**: Exponential backoff cycling through escalation steps (ICE restart → offer resend → rejoin → transport fallback → repeat). Only stop when the server confirms the room is gone or the user taps "Leave call".

5. **Sound/haptic**: **Short haptic on iOS/Android** when entering the `retrying` state. No sound. No haptic on web.

---

## Files to Modify

| Platform | File | Change |
|---|---|---|
| Web | `client/src/contexts/WebRTCContext.tsx` | Add `ConnectionStatus` type, state, timer, transition logic |
| Web | `client/src/pages/CallRoom.tsx` | Remove `showReconnecting`, render based on `connectionStatus` from context |
| iOS | `client-ios/Sources/Core/Models/ConnectionStatus.swift` | New file: `ConnectionStatus` enum |
| iOS | `client-ios/Sources/Core/Models/CallUiState.swift` | Add `connectionStatus` property |
| iOS | `client-ios/Sources/Core/Call/CallManager.swift` | Add transition logic, timer |
| iOS | `client-ios/Sources/UI/Screens/CallScreen.swift` | Update status label for `retrying`, add inline "Leave call" |
| Android | `client-android/.../call/ConnectionStatus.kt` | New file: `ConnectionStatus` enum |
| Android | `client-android/.../call/CallUiState.kt` | Add `connectionStatus` property |
| Android | `client-android/.../call/CallManager.kt` | Add transition logic, timer |
| Android | `client-android/.../ui/CallScreen.kt` | Update badge for `retrying`, add inline "Leave call" |

## Verification

1. **Happy path**: Join a call → verify `connectionStatus` stays `connected`
2. **Recovering**: Toggle airplane mode briefly → badge appears after 800ms → badge disappears on reconnect
3. **Retrying**: Block network for >10s → badge shows "Taking longer than usual…" sub-text with "Leave call" link
4. **Long outage auto-resume**: Block network for 2+ minutes → restore network → call auto-resumes, badge disappears, no user interaction needed
5. **Leave from retrying**: Tap "Leave call" in badge → returns to home screen
6. **Server-side room gone**: Other peer leaves during outage → system detects room gone on next reconnect attempt → auto-navigates to home
7. **Cross-platform consistency**: All three platforms show same transitions at same thresholds
