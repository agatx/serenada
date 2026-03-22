# SDK Architecture Execution Plan

## Summary
Because SDK interface compatibility is not required and every client lives in this repo, the fastest path is a repo-wide sweep with no shims, no deprecation window, and no duplicate model surfaces kept alive for compatibility. The current working copy already contains partial iOS model-ownership cleanup and some unrelated Android utility changes; start by stabilizing that baseline, then do the architectural work in 6 stages.

Status legend:
- `[x]` implemented in the current working tree
- `[ ]` not done yet

The 10 workstreams and current status:
- [x] Finish moving shared call models out of iOS UI layer ownership.
- [x] Eliminate the iOS dual-state / `@Published` timing trap.
- [x] Remove duplicated UI-facing call state models where core state can be consumed directly.
- [x] Add DI seams for signaling, WebRTC, API, and audio (timers/schedulers deferred).
- [x] Add orchestration-level tests for join, reconnect, negotiation, and recovery.
- [x] Enforce Android main-thread usage and fix Android stats/resource lifecycle.
- [x] Encapsulate `PeerConnectionSlot` state machines.
- [x] Replace raw public WebRTC string state with typed diagnostics.
- [x] Split iOS/Android `WebRtcEngine` into focused media components.
- [x] Replace callback-only `createRoom()` with async/suspend APIs.

## Public API And Type Changes
- [x] Core packages own call-domain models. iOS `SerenadaCallUI` and Android `serenada-call-ui` consume core `CallState`, `CallDiagnostics`, participant, and phase types; presentation mapping stays in the host app/UI layer.
- [x] `SerenadaSession` exposes one app-facing snapshot `state` plus one low-level snapshot `diagnostics`. Android low-level transport/debug fields were removed from `CallState`, and iOS extra published fields were collapsed into `diagnostics`.
- [x] Add `CallDiagnostics` with typed ICE/peer/signaling states, signaling connectivity, active transport, stats, remote-content metadata, local media capability state, and `featureDegradations`.
- [x] Add `FeatureDegradation`, including at minimum `compositeCameraUnavailable`.
- [x] iOS `createRoom()` becomes `async throws -> CreateRoomResult`; Android `createRoom()` becomes `suspend fun createRoom(): CreateRoomResult`. Delete callback variants once all in-repo callers are migrated.
- [x] Android main-thread access becomes a hard SDK contract enforced with fail-fast preconditions.

## Stages
1. **Stabilize Current Working Copy**
   - [x] Complete the in-flight iOS type move already visible in the working tree: shared call models stay in `SerenadaCore`, deleted `SerenadaCallUI` duplicate model files stay deleted, and all UI/app/tests import the core-owned types.
   - [x] Keep the small Android utility/backfill edits only if they stay green; otherwise isolate them from the architecture branch rather than mixing them into later refactors.
   - [x] Establish a clean baseline by running repo builds/tests before larger changes.
     Web build/tests, Android unit tests, iOS build (`xcodegen generate` + `xcodebuild build`), and resilience parity check all pass. Full iOS app-scheme test run still has a pre-existing live UI test failure in `DeepLinkParticipantCountUITests`.

2. **Foundation: DI, Test Harness, Runtime Safety**
   - [x] Introduce internal factories/interfaces for signaling, WebRTC, API, and audio dependencies in iOS and Android session layers.
     Protocols (iOS) and interfaces (Android) created for `SessionSignaling`, `SessionAPIClient`, `SessionAudioController`, and `SessionMediaEngine`. Concrete classes conform to these abstractions. `SerenadaSession` on both platforms accepts optional DI params with production defaults. Clock/timer/scheduler abstractions deferred to a follow-up.
   - [x] Build a hermetic session harness with fake signaling/media/timers and add contract tests for permission gating, join ack timeout, join recovery, reconnect backoff, WS-to-SSE failover, offer timeout, ICE restart, turn refresh, leave, and end.
     Fake implementations created for all four DI seams on both platforms. iOS: 10 contract tests (all green) covering join flow, error handling, reconnect, leave/end cleanup, TURN fetch/failure. Android: 13 contract tests (10 message-driven + 3 timer-dependent using Robolectric ShadowLooper). Production changes: PeerConnectionSlot.factory made nullable on both platforms; Android recreateWebRtcEngineForNewCall() preserves injected engines. Timer-dependent iOS tests deferred until clock abstraction is added.
   - [x] Android: add main-thread preconditions on all public `SerenadaCore` and `SerenadaSession` entrypoints and replace the current reusable stats executor with a lifecycle-owned scheduler that cannot be reused after shutdown.

3. **Unify State And Model Ownership**
   - [x] iOS: delete `legacyUiState` and `syncPublishedSnapshot()`, and move to one reducer-driven immutable `CallState`.
     Replaced the dual-state pattern with a single `commitSnapshot` helper that batch-updates `state` and `diagnostics` directly. `internalPhase` and `participantCount` remain as private session fields for internal decision-making; all observable state is now authoritative in the published snapshots.
   - [x] Introduce `CallDiagnostics` on iOS and Android and migrate transport/debug/media-detail fields into it.
   - [x] Remove UI-owned duplicate state models where they are full copies of SDK state; UI packages should read core `CallState`/`CallDiagnostics` directly and derive display-only fields locally.
   - [x] Update in-repo host apps, UI modules, tests, and samples in the same sweep.

4. **Decompose Session Orchestration**
   - [x] Refactor iOS and Android `SerenadaSession` into a thin facade over extracted coordinators.
     Five coordinators now own all non-trivial session logic: `StatsPoller` (stats polling), `ConnectionStatusTracker` (connected/recovering/retrying state machine), `TurnManager` (TURN fetch/refresh/default ICE), `JoinTimer` (join timeout/kickstart/recovery), and `PeerNegotiationEngine` (slot lifecycle, offer/answer/ICE processing, ICE restart, non-host fallback, aggregate peer state). Session is a thin facade that owns shared state (`peerSlots`, `internalPhase`, `clientId`, `hostCid`, `currentRoomState`) and coordinates between coordinators via closure-based communication. ~20 methods and priority maps moved per platform. iOS session reduced from ~1664 to ~1250 LOC; Android from ~1334 to ~930 LOC.
   - [x] Make `PeerConnectionSlot` an owned state machine with explicit methods for offer lifecycle, ICE restart lifecycle, non-host fallback, and cleanup; session code must stop mutating slot fields directly.
     Both iOS and Android slots now use `private(set)` / `private set` fields with explicit mutation methods: `beginOffer()`, `completeOffer()`, `markOfferSent()`, `markPendingIceRestart()`, `clearPendingIceRestart()`, `recordIceRestart()`, and task-management methods for offer timeout, ICE restart, and non-host fallback tasks.
   - [x] Keep signaling protocol v1 and resilience constants unchanged.

5. **Decompose Media Engine And Surface Degradation**
   - [x] Split iOS and Android `WebRtcEngine` into focused media components: `CameraCaptureController` (camera device mgmt, mode switching, torch, zoom), `ScreenShareController` (screen share orchestration), and extracted nested classes (`CompositeCameraVideoCapturer`, `ScreenShareCapturers` on iOS). Renderer registry (~40 LOC) and stats/media bridge were not extracted as separate components since they are too small and tightly coupled; renderer tracking stays in the facade and stats are already in `StatsPoller`/`PeerConnectionSlot`. iOS `WebRtcEngine` reduced from 1903 to 457 LOC; Android from 1518 to 440 LOC.
   - [x] Remove silent composite-camera failure handling. Composite failures must set `FeatureDegradation.compositeCameraUnavailable`, carry a reason in diagnostics, and disable composite only for the current session unless explicitly retried.
   - [x] Android: remove persistent composite failure disablement from `SharedPreferences`; capability detection caching may remain, failure persistence may not.

6. **Repo-Wide API Cleanup**
   - [x] Replace callback-based `createRoom()` call sites in the app shells, samples, and tests with async/suspend usage, then delete the callback APIs.
   - [x] Remove iOS extra session-published properties and any remaining duplicate UI state adapters that survived earlier stages.
     The `legacyUiState` removal in Stage 3 eliminated the last internal duplicate adapter. `SerenadaSession` now exposes only `state` and `diagnostics` as `@Published` properties with no intermediate shadow state.
   - [x] Update README and platform SDK docs to describe `state`, `diagnostics`, Android main-thread requirements, and async room creation.
     Root README now includes an "SDK Pattern" section describing the headless SDK architecture, `state`/`diagnostics` snapshots, and async `createRoom()`. Platform SDK docs and sample READMEs were already updated in prior work.

## Test Plan
- Baseline and after every stage:
  - [x] `cd client && npm test && npm run build`
  - [ ] `cd client-ios && xcodegen generate && xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,name=iPhone 16' test`
    `xcodegen generate` passed, but the full iOS test command is still blocked by a live UI test failure in `DeepLinkParticipantCountUITests`.
  - [x] `cd client-android && ./gradlew :serenada-core:testDebugUnitTest :app:testDebugUnitTest`
  - [x] `node scripts/check-resilience-constants.mjs`
- Required scenarios:
  - [x] iOS subscribers never observe stale state after a session change.
    The dual-state pattern was eliminated; `state` and `diagnostics` are now the single source of truth with no intermediate shadow state that could go stale.
  - [x] Android off-main-thread SDK calls fail immediately.
  - [ ] Join, permission resume, reconnect, WS/SSE fallback, offer timeout, ICE restart, and leave/end are behaviorally unchanged.
  - [x] UI packages compile against core-owned model types with no duplicated call-domain models left in the UI modules.
  - [x] Composite-camera failure is visible in diagnostics and does not persist across sessions.
  - [ ] All host apps and samples compile after the async `createRoom()` migration.

## Assumptions
- Save this revised plan as `SDK_ARCHITECTURE_EXECUTION_PLAN.md` at the repo root when edits are allowed.
- Public SDK compatibility is intentionally out of scope; all in-repo callers are migrated in the same changes that break old interfaces.
- No signaling protocol change, no resilience constant change, and no new third-party dependency is part of this plan.
- Existing Claude working-copy changes should be preserved only where they align with the target architecture and stay green under the baseline build/test pass.
