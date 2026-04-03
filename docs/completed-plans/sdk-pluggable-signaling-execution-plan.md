# SDK Pluggable Signaling Execution Plan

Source plan: [`docs/sdk-pluggable-signaling-plan.md`](sdk-pluggable-signaling-plan.md)

Status legend:
- `[ ]` not started
- `[x]` completed

## Guardrails
- [x] Keep built-in `serverHost` behavior unchanged while adding custom-provider support.
- [x] Keep the scope SDK-only: no server changes beyond directly related documentation updates.
- [x] Avoid new dependencies and keep changes minimal and targeted.
- [x] Preserve the headless SDK + optional UI boundary on web, Android, and iOS.
- [x] Land the lexicographic offer-ownership change on web, Android, and iOS together to avoid mixed-client behavior.

## Critical Path
- [x] Complete Phase 1 before Phase 2 so config validation references the finalized `SignalingProvider` contract rather than a moving target.
- [x] Complete Phases 1 and 2 before starting platform rewires in Phases 3, 4, and 5.
- [x] Run Phases 3, 4, and 5 in parallel where possible, but treat the lexicographic offer-ownership cutover as one coordinated cross-platform change.
- [x] Complete cross-platform parity cleanup before landing docs and final verification.

## Phase 0 - Baseline And Change Control
- [x] Re-read [`docs/sdk-pluggable-signaling-plan.md`](sdk-pluggable-signaling-plan.md) and treat it as the source of truth for interface shape, reconnection ownership, and non-goals.
- [x] Validate the worktree and confirm dependencies are installed for the platforms that will be touched.
- [x] Record current built-in signaling behavior for join, reconnect, leave, end-room, TURN refresh, and fallback-offer flows on all three client platforms.
- [x] Identify existing tests that cover signaling, TURN, diagnostics, and room-watcher behavior so they can be migrated instead of duplicated.
- [x] Check for overlapping in-flight changes in the targeted files before editing them.
- [x] Capture the Phase 0 baseline findings in this document or a linked working note before implementation starts so regressions can be checked against a concrete baseline.

### Phase 0 Findings (2026-03-28)
- Source-of-truth review: [`docs/sdk-pluggable-signaling-plan.md`](sdk-pluggable-signaling-plan.md) is the baseline for provider shape, `handlesReconnection`, `getIceServers()`, optional `roomStateUpdated`, `onPeerMessage()`, server-only API gating, and lexicographic offer ownership.
- Worktree validation: `tools/worktree-validate.sh .` passed end-to-end for web, server, Android, and iOS. Warnings were pre-existing `.env` placeholder secrets, existing web ESLint errors, and local Go `1.23` vs the repo warning for `1.24+`.
- Overlap check: `git status --short -- client/packages/core/src client/packages/react-ui/src client-android/serenada-core/src/main/java/app/serenada/core client-ios/SerenadaCore/Sources docs` returned clean, so there are no local in-flight edits in the targeted files.
- Web baseline:
  - `SerenadaSession` directly owns `SignalingEngine`, wires raw `subscribeToMessages()` into `MediaEngine.processSignalingMessage()`, mirrors `roomState`/`turnToken` on signaling state changes, and auto-calls `connect()` then `joinRoom()` unless a fake signaling engine is injected.
  - Built-in reconnect ownership lives in `SignalingEngine`: WS/SSE fallback, ping/pong liveness, exponential reconnect backoff, persisted reconnect CID/token, and automatic re-join of the current room after transport recovery.
  - Initial and refreshed TURN flow is `joined.turnToken` or `turn-refreshed.turnToken` -> `SerenadaSession` forwards `turnToken` -> `MediaEngine.updateTurnToken()` -> `/api/turn-credentials` fetch inside `MediaEngine`; `SignalingEngine` schedules `turn-refresh` using TTL.
  - Leave/end behavior is built into the session: `leave()` sends `leaveRoom()`, cleans peers, stops stats, and destroys; `end()` sends `endRoom()` and then calls `leave()`.
  - Offer ownership is still `(joinedAt, cid)` ordered in `MediaEngine.shouldIOffer()`. The non-offerer fallback timer remains active and resends fallback offers when the expected offer never arrives.
- Android baseline:
  - `SerenadaSession` depends on `SessionSignaling`, `SignalingMessageRouter`, and `TurnManager`; join state is driven by `"joined"` and `"room_state"` messages from the router.
  - Built-in reconnect ownership lives across `SignalingClient` and the session: WS/SSE fallback, ping/pong tracking, close notifications, session backoff-based reconnect scheduling, reconnect CID/token reuse, and rejoin after signaling reopens.
  - Initial and refreshed TURN flow is `"joined"`/`"turn-refreshed"` -> `TurnManager.fetchTurnCredentials()` -> `SessionAPIClient.fetchTurnCredentials()` with timeout fallback to default STUN; TTL-driven `turn-refresh` remains built-in.
  - Leave/end behavior is session-owned: `leave()` sends `"leave"` and cleans up; `end()` sends `"end_room"` and then tears down the call.
  - Offer ownership in `PeerNegotiationEngine.shouldIOffer()` is still `(joinedAt, cid)` ordered. Non-host fallback and offer-timeout recovery remain active.
- iOS baseline:
  - `SerenadaSession` depends on `SessionSignaling`, `SignalingMessageRouter`, `TurnManager`, and `PeerNegotiationEngine`; join state is driven by `"joined"` and `"room_state"` parsing in the session/router.
  - Built-in reconnect ownership lives across `SignalingClient` and the session: WS/SSE fallback, ping/pong, transport-close notifications, session reconnect backoff, reconnect CID/token reuse, and in-call ICE restart after signaling recovery.
  - Initial and refreshed TURN flow is `"joined"`/`"turn-refreshed"` -> `TurnManager.ensureIceSetupIfNeeded()` / `fetchTurnCredentials()` -> `SessionAPIClient.fetchTurnCredentials()` with default STUN fallback; TTL-driven `turn-refresh` remains built-in.
  - Leave/end behavior is session-owned: `leave()` sends `"leave"` when connected or in-room, then cleans up; `end()` sends `"end_room"` and then cleans up.
  - Offer ownership in `PeerNegotiationEngine.shouldIOffer()` is still `(joinedAt, cid)` ordered. Non-host fallback and offer-timeout recovery remain active.
- Existing tests to migrate rather than duplicate:
  - Web signaling transport and reconnect behavior: `client/packages/core/test/signaling/SignalingEngine.test.ts`.
  - Web session orchestration, TURN token wiring, leave/end, and reconnect state: `client/packages/core/test/SerenadaSession.test.ts`.
  - Web room watcher and room API: `client/packages/core/test/signaling/RoomWatcher.test.ts`, `client/packages/core/test/api/roomApi.test.ts`.
  - Web signaling payload parsing including TURN refresh payloads: `client/packages/core/test/signaling/payloads.test.ts`.
  - Android session contract coverage for join, reconnect, leave/end, TURN fetch success/failure, and join timeout: `client-android/serenada-core/src/test/java/app/serenada/core/SerenadaSessionContractTest.kt`.
  - Android negotiation coverage for current joined-at offer ownership, fallback offers, ICE restarts, peer removal, and reconnect-triggered ICE restart: `client-android/serenada-core/src/test/java/app/serenada/core/SessionNegotiationTest.kt`.
  - Android occupancy coverage only: `client-android/serenada-core/src/test/java/app/serenada/core/RoomOccupanciesTest.kt`, `client-android/app/src/test/java/app/serenada/android/call/RoomStatusesTest.kt`.
  - iOS session orchestration coverage for join, room-state updates, reconnect, leave/end, TURN fetch success/failure, join timeout, and reconnect backoff: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SessionOrchestrationTests.swift`.
  - iOS negotiation coverage for current joined-at offer ownership, fallback answers/offers, ICE restarts, reconnect-triggered ICE restart, and peer-slot sync: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SessionNegotiationTests.swift`.
  - iOS session smoke/contract coverage: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SerenadaSessionTests.swift`, `client-ios/SerenadaCore/Tests/SerenadaCoreTests/JoinRecoveryTests.swift`.
  - Current explicit diagnostics test coverage is effectively absent across web, Android, and iOS, so diagnostics work in later phases will need new tests rather than pure migration.

## Phase 1 - Shared Contract And API Decisions
- [x] Define the cross-platform `SignalingProvider` abstraction and align the interface/protocol shape across TypeScript, Kotlin, and Swift.
- [x] Define the shared event model for `connected`, `disconnected`, `joined`, `roomStateUpdated`, `peerJoined`, `peerLeft`, `message`, `roomEnded`, `error`, and `iceServersChanged`.
- [x] Define the shared payload models for `ConnectionInfo`, `JoinOptions`, `JoinedEvent`, `RoomStateEvent`, `Participant`, `PeerEvent`, `PeerMessage`, `RoomEndedEvent`, and `ErrorEvent`.
- [x] Encode the interface version contract (`version == 1`) and define the construction-time failure path for unsupported provider versions.
- [x] Encode `ProviderCapabilities.handlesReconnection` with default-false semantics on all platforms.
- [x] Define the `getIceServers()` contract consistently on all platforms: one initial fetch after join, three retries with exponential backoff, throw/reject on failure, and treat `[]` as a valid STUN-only result.
- [x] Define `iceServersChanged` as the only credential-refresh path after the initial `getIceServers()` call.
- [x] Define `roomStateUpdated` as optional so incremental-only adapters remain valid.
- [x] Standardize on `peerId` as the provider-facing identifier and keep built-in `cid` terminology internal to the built-in provider.
- [x] Lock the public replacement for web `subscribeToMessages()` as `onPeerMessage()`.
- [x] Lock the offer-ownership rule to lexicographic peer ID comparison and treat `joinedAt` as informational only.

### Phase 1 Decisions (2026-03-28)
- Contract shape is locked from the source plan for all three SDKs: lifecycle (`connect`/`disconnect`), room actions (`joinRoom`/`leaveRoom`/`endRoom`), peer messaging (`sendToPeer`/`broadcast`), initial ICE acquisition (`getIceServers()`), and provider-owned capability flags.
- Shared events are locked as `connected`, `disconnected`, `joined`, `roomStateUpdated`, `peerJoined`, `peerLeft`, `message`, `roomEnded`, `error`, and `iceServersChanged`; Android uses a listener, iOS uses a delegate, and web uses `on`/`off`.
- Shared payload vocabulary is locked as `ConnectionInfo`, `JoinOptions`, `JoinedEvent`, `RoomStateEvent`, `Participant`, `PeerEvent`, `PeerMessage`, `RoomEndedEvent`, and `ErrorEvent`.
- Versioning is locked to `version == 1`, and unsupported provider versions must fail during SDK/session construction rather than later during signaling flow.
- `ProviderCapabilities.handlesReconnection` is locked with default-false semantics; the built-in `SerenadaServerProvider` will be the explicit opt-in override later.
- `getIceServers()` is locked as a single initial fetch after join, with session-owned retries (`1s`, `2s`, `4s`), rejection/throw on failure, and `[]` treated as a valid STUN-only outcome rather than an error sentinel.
- `iceServersChanged` is locked as the only post-join ICE credential refresh path; the session must not poll `getIceServers()` after the initial fetch.
- `roomStateUpdated` is locked as optional so incremental-only providers remain valid.
- Public/provider-facing naming is locked to `peerId`; built-in `cid` terminology stays internal to the built-in server adapter.
- The web public API replacement is locked as `onPeerMessage()`, with raw `subscribeToMessages()` removed from the public session surface.
- Offer ownership is locked to lexicographic peer ID comparison only; `joinedAt` remains informational and must not participate in runtime offer/answer ownership.

## Phase 2 - Config Validation And API Gating
- [x] Update the config contract on all platforms so exactly one of `serverHost` or `signalingProvider` must be provided.
- [x] Add fail-fast validation when both `serverHost` and `signalingProvider` are set.
- [x] Add fail-fast validation when neither `serverHost` nor `signalingProvider` is set.
- [x] Define a single gating rule for server-bound APIs so `createRoom()`, `createRoomId()`, `RoomWatcher`, and server connectivity probes throw `requires serverHost` in provider mode.
- [x] Define diagnostics behavior by mode at config-construction time rather than at join time.
- [x] Audit in-repo call sites, samples, and tests for the new config contract before implementation lands.

### Phase 2 Decisions And Audit (2026-03-28)
- Config mode is locked as a construction-time invariant on all three platforms: exactly one of `serverHost` or `signalingProvider` must be set.
- Validation behavior is locked as fail-fast: both set -> throw during core/config construction; neither set -> throw during core/config construction. This is not deferred to `join()`.
- The server-only gating rule is locked to a single error contract: `createRoom()`, `createRoomId()`, `RoomWatcher`, and server connectivity probes must throw `requires serverHost` when invoked in provider mode.
- Diagnostics mode is locked at construction time:
  - `runAll()` stays full-fidelity in built-in/server mode.
  - Provider mode returns device + TURN checks, keeps the existing report shape, and marks the server/signaling section as `skipped` with `requires serverHost`.
  - `runTurnProbe()` is the cross-mode TURN check.
  - `runConnectivityChecks()` is server-only and must throw `requires serverHost` in provider mode.
- Current config surface that must change in implementation:
  - Web: `client/packages/core/src/types.ts` still requires `serverHost` and still exposes `subscribeToMessages()`.
  - Android: `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaConfig.kt` still requires `serverHost`.
  - iOS: `client-ios/SerenadaCore/Sources/SerenadaConfig.swift` still requires `serverHost`.
- Current in-repo built-in-only call sites audited for the upcoming config change:
  - Web app/UI/samples: `client/src/pages/CallRoom.tsx`, `client/packages/react-ui/src/SerenadaCallFlow.tsx`, `client/packages/react-ui/src/hooks/useSerenadaSession.ts`, `samples/web/src/App.tsx`.
  - Web room creation and watcher usage: `client/src/pages/Home.tsx`, `client/src/components/SavedRooms.tsx`, `client/src/hooks/useRoomStatusWatcher.ts`.
  - Android app/sample usage: `client-android/app/src/main/java/app/serenada/android/call/CallManager.kt`, `client-android/app/src/main/java/app/serenada/android/ui/DiagnosticsScreen.kt`, `client-android/serenada-call-ui/src/main/java/app/serenada/callui/SerenadaCallFlow.kt`, `samples/android/app/src/main/java/app/serenada/sample/MainActivity.kt`.
  - iOS app/sample usage: `client-ios/Sources/Core/Call/CallManager.swift`, `client-ios/Sources/UI/Screens/DiagnosticsScreen.swift`, `client-ios/SerenadaCallUI/Sources/SerenadaCallFlow.swift`, `samples/ios/SampleApp/SampleApp.swift`.
  - Platform docs and samples that currently assume built-in/server mode only: `docs/sdk/sdk-integration-web.md`, `docs/sdk/sdk-integration-android.md`, `docs/sdk/sdk-integration-ios.md`, `samples/web/README.md`, `samples/android/README.md`, `samples/ios/README.md`.
- Current test surface audited for the upcoming config/gating change:
  - Web: `client/packages/core/test/SerenadaCore.test.ts`, `client/packages/core/test/signaling/RoomWatcher.test.ts`, `client/packages/core/test/SerenadaSession.test.ts`.
  - Android: `client-android/serenada-core/src/test/java/app/serenada/core/SerenadaSessionContractTest.kt`, `client-android/serenada-core/src/test/java/app/serenada/core/SessionNegotiationTest.kt`, `client-android/serenada-core/src/test/java/app/serenada/core/fakes/TestSessionFactory.kt`.
  - iOS: `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SerenadaSessionTests.swift`, `client-ios/SerenadaCore/Tests/SerenadaCoreTests/SessionOrchestrationTests.swift`, `client-ios/SerenadaCore/Tests/SerenadaCoreTests/Helpers/SessionTestHarness.swift`.
- There are no existing in-repo production call sites using `signalingProvider`; provider-mode coverage will therefore be additive in later phases rather than a migration of live call sites.

## Cross-Cutting Workstream - Offer Ownership Cutover
- [x] Treat the offer-ownership implementation tasks in Phases 3, 4, and 5 as one coordinated rollout rather than three independent changes.
- [x] Confirm after the platform rewires land that `joinedAt` remains informational only and is no longer part of runtime offer/answer ownership.
- [x] Verify the fallback-offer timer still recovers negotiation when the expected offer does not arrive after the coordinated ownership-rule change.
- [x] Do not merge a partial offer-ownership rollout on only one or two platforms.

## Phase 3 - Web SDK Implementation
- [x] Add `client/packages/core/src/SignalingProvider.ts` with the TypeScript interface, event/payload types, and `ProviderCapabilities`.
- [x] Add the reusable `SignalingProviderEmitter` base class for third-party web adapters.
- [x] Export `SignalingProvider`, the provider event/payload types, `ProviderCapabilities`, and `SignalingProviderEmitter` from `client/packages/core/src/index.ts`.
- [x] Add `client/packages/core/src/SerenadaServerProvider.ts` to wrap `client/packages/core/src/signaling/SignalingEngine.ts` and the existing TURN sourcing flow.
- [x] Ensure `client/packages/core/src/SerenadaServerProvider.ts` declares `capabilities.handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [x] Update `client/packages/core/src/types.ts` to make `serverHost` optional, add `signalingProvider`, remove `subscribeToMessages`, and expose `onPeerMessage`.
- [x] Rewire `client/packages/core/src/SerenadaSession.ts` to depend on `SignalingProvider` rather than directly on `SignalingEngine`.
- [x] Move web session signaling flow to provider events for connect/disconnect, join, room-state refresh, peer presence, peer messages, room end, errors, and ICE-server refresh.
- [x] Add session-owned `getIceServers()` retry logic in `client/packages/core/src/SerenadaSession.ts`.
- [x] Branch web reconnection behavior in `client/packages/core/src/SerenadaSession.ts` based on `capabilities.handlesReconnection`.
- [x] Add the public `onPeerMessage()` API in `client/packages/core/src/SerenadaSession.ts` and remove `subscribeToMessages()` plumbing.
- [x] Move initial ICE-server sourcing out of `client/packages/core/src/media/MediaEngine.ts` and into the session/provider boundary so the media layer consumes provider-supplied ICE configs.
- [x] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on web.
- [x] Update `client/packages/core/src/media/MediaEngine.ts` to use lexicographic peer ID comparison for offer ownership.
- [x] Update `client/packages/core/src/SerenadaCore.ts` to validate config, instantiate the built-in provider when `serverHost` is present, use the injected provider otherwise, and gate server-only APIs.
- [x] Update `client/packages/core/src/SerenadaDiagnostics.ts` to split device checks, TURN checks, and Serenada-server checks; add `runTurnProbe()`; and mark server probes as skipped in provider mode.
- [x] Update `client/packages/core/src/RoomWatcher.ts` to reject provider mode with a clear `requires serverHost` error.
- [x] Update `client/packages/react-ui/src/SerenadaCallFlow.tsx` to use `onPeerMessage()` for `content_state` handling.
- [x] Update or replace web tests that assert raw Serenada signaling envelopes or direct `SignalingEngine` coupling.

### Phase 3 Validation (2026-03-28)
- `cd client && npm test` passed with `12` test files and `238` tests green after migrating the session/core/diagnostics/room-watcher harnesses to the provider contract.
- `cd client && npm run build` passed, including `tsc -b` type-checking for `@serenada/core`, `@serenada/react-ui`, and the app shell build.
- Validation surfaced and resolved two TypeScript ICE-server narrowing issues plus one diagnostics probe fixture issue before the final green run.

## Phase 4 - Android SDK Implementation
- [x] Add `client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt` with the provider interface, event models, capabilities, and threading contract documentation.
- [x] Add `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaServerProvider.kt` to wrap `client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingClient.kt` and the built-in TURN flow.
- [x] Ensure `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaServerProvider.kt` declares `handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [x] Decide whether `client-android/serenada-core/src/main/java/app/serenada/core/call/SessionSignaling.kt` remains as an internal bridge or is deleted once `SerenadaSession.kt` fully depends on `SignalingProvider`.
- [x] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaConfig.kt` for optional `serverHost` plus `signalingProvider`.
- [x] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaCore.kt` to validate config, build the correct provider, and gate server-only APIs.
- [x] Rewire `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaSession.kt` from `SessionSignaling` to `SignalingProvider`.
- [x] Add main-looper trampolining around provider callbacks in `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaSession.kt` so third-party adapters can safely call back from background threads.
- [x] Move initial ICE-server acquisition and retry logic into the session/provider boundary.
- [x] Restrict `client-android/serenada-core/src/main/java/app/serenada/core/call/TurnManager.kt` to the built-in provider path so TURN sourcing no longer leaks through the generic session contract.
- [x] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on Android.
- [x] Branch Android reconnection behavior based on `capabilities.handlesReconnection`.
- [x] Update `client-android/serenada-core/src/main/java/app/serenada/core/call/PeerNegotiationEngine.kt` to use lexicographic peer ID comparison for offer ownership.
- [x] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaDiagnostics.kt` to support provider-mode TURN probing and to skip Serenada-server checks when `serverHost` is absent.
- [x] Update `client-android/serenada-core/src/main/java/app/serenada/core/RoomWatcher.kt` and room-creation APIs to throw clear `requires serverHost` errors in provider mode.
- [x] Update Android tests and fake signaling implementations to target the new provider contract rather than the old concrete signaling-client contract.

### Phase 4 Validation (2026-03-28)
- `cd client-android && ./gradlew :serenada-core:testDebugUnitTest` passed after migrating the Android session/core harnesses to the provider contract and adding provider-mode diagnostics/core/room-watcher coverage.
- `cd client-android && ./gradlew :serenada-core:assembleDebug` passed, confirming the core Android SDK module still packages cleanly after the signaling/provider rewrite.
- Implementation decision: `SessionSignaling` remains as an internal built-in bridge used by `SerenadaServerProvider`; `SerenadaSession` no longer depends on it directly.

## Phase 5 - iOS SDK Implementation
- [x] Add `client-ios/SerenadaCore/Sources/SignalingProvider.swift` with the provider protocol, event models, capabilities, and delegate contract.
- [x] Add `client-ios/SerenadaCore/Sources/SerenadaServerProvider.swift` to wrap `client-ios/SerenadaCore/Sources/Signaling/SignalingClient.swift` and the built-in TURN flow.
- [x] Ensure `client-ios/SerenadaCore/Sources/SerenadaServerProvider.swift` declares `handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [x] Decide whether `client-ios/SerenadaCore/Sources/Signaling/SessionSignaling.swift` remains as an internal bridge or is deleted once `SerenadaSession.swift` fully depends on `SignalingProvider`.
- [x] Update `client-ios/SerenadaCore/Sources/SerenadaConfig.swift` for optional `serverHost` plus `signalingProvider`.
- [x] Update `client-ios/SerenadaCore/Sources/SerenadaCore.swift` to validate config, build the correct provider, and gate server-only APIs.
- [x] Rewire `client-ios/SerenadaCore/Sources/SerenadaSession.swift` from `SessionSignaling` to `SignalingProvider`.
- [x] Add `MainActor` trampolining around provider delegate callbacks in `client-ios/SerenadaCore/Sources/SerenadaSession.swift` so third-party adapters can safely invoke delegates off-actor.
- [x] Move initial ICE-server acquisition and retry logic into the session/provider boundary.
- [x] Restrict `client-ios/SerenadaCore/Sources/Call/TurnManager.swift` to the built-in provider path so TURN sourcing no longer leaks through the generic session contract.
- [x] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on iOS.
- [x] Branch iOS reconnection behavior based on `capabilities.handlesReconnection`.
- [x] Update `client-ios/SerenadaCore/Sources/Call/PeerNegotiationEngine.swift` to use lexicographic peer ID comparison for offer ownership.
- [x] Update `client-ios/SerenadaCore/Sources/SerenadaDiagnostics.swift` to support provider-mode TURN probing and to skip Serenada-server checks when `serverHost` is absent.
- [x] Update `client-ios/SerenadaCore/Sources/RoomWatcher.swift` and room-creation APIs to throw clear `requires serverHost` errors in provider mode.
- [x] Update iOS tests and fake signaling implementations to target the new provider contract rather than the old concrete signaling-client contract.

### Phase 5 Validation (2026-03-28)
- `cd client-ios && xcodegen generate` passed after adding the provider-backed iOS test/support files.
- `cd client-ios && xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,OS=18.4,name=iPhone 16' test` passed end-to-end with `202` unit tests green and the deep-link UI suites intentionally skipped unless explicit live-room override env vars are provided.
- Implementation decision: `SessionSignaling` remains as an internal built-in bridge used by `SerenadaServerProvider`; `SerenadaSession` no longer depends on it directly.

## Phase 6 - Cross-Platform Parity And Public Surface Cleanup
- [x] Verify the built-in `SerenadaServerProvider` preserves existing WS/SSE transport behavior, ping/pong, reconnect tokens, room lifecycle, and TURN refresh semantics on all platforms.
- [x] Verify the built-in provider advertises reconnection ownership correctly on all platforms so existing reconnect flows do not fall back to session-managed rejoin logic.
- [x] Verify custom-provider mode never depends on Serenada protocol envelopes, room APIs, watcher APIs, or server-owned membership state beyond the abstract provider contract.
- [x] Verify `hostPeerId` remains optional end-to-end and that UI layers tolerate it being absent.
- [x] Verify `roomStateUpdated` is opportunistic and does not become a hard requirement for third-party adapters.
- [x] Verify `iceServersChanged` updates both existing and future peer connections on all platforms.
- [x] Remove or migrate any remaining APIs, docs, or tests that expose raw Serenada signaling envelopes directly to SDK consumers.
- [x] Audit exports, diagnostics, and logs so `peerId` is the public/provider-facing identifier while built-in `cid` mapping stays internal.
- [x] Update `samples/web/`, `samples/android/`, and `samples/ios/` to demonstrate both built-in signaling and custom-provider usage.
- [x] Add at least one minimal custom-provider smoke-test sample or harness that uses incremental presence plus peer message delivery without Serenada signaling transport.

Validation:
- `cd client && npm test` passed with `13` files and `243` tests green.
- `cd client && npm run build` passed.
- `cd samples/web && npm run build` passed after wiring the provider-mode demo to the current `CallState` surface.
- `cd client-android && ./gradlew :serenada-core:testDebugUnitTest` passed.
- `cd client-android && ./gradlew :serenada-core:assembleDebug` passed.
- `cd samples/android && ./gradlew assembleDebug` passed.
- `cd client-ios && xcodegen generate` passed.
- `cd client-ios && xcodebuild -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,OS=18.4,name=iPhone 16' test` passed with `206` unit tests green and `2` live-room UI tests skipped by design.
- `cd samples/ios && xcodegen generate` passed.
- `cd samples/ios && xcodebuild -project SerenadaiOSSample.xcodeproj -scheme SerenadaiOSSample -destination 'platform=iOS Simulator,OS=18.4,name=iPhone 16' build` passed.

## Phase 7 - Documentation Updates
- [x] Update `docs/serenada_protocol_v1.md` section 5.1 to describe lexicographic peer ID offer ownership and keep `joinedAt` informational only.
- [x] Update `README.md` to explain built-in versus custom signaling modes and the config validation rules.
- [x] Update `docs/sdk/sdk-api-reference.md` to document `SignalingProvider`, provider-mode constraints, `onPeerMessage()`, and `runTurnProbe()`.
- [x] Update `docs/sdk/sdk-customization.md` with third-party adapter guidance, reconnection ownership semantics, and ICE-server sourcing expectations.
- [x] Update `docs/sdk/sdk-integration-web.md` with provider-mode setup and the `SerenadaCallFlow` message-hook change.
- [x] Update `docs/sdk/sdk-integration-android.md` with provider-mode setup, main-thread callback guarantees, and server-bound API restrictions.
- [x] Update `docs/sdk/sdk-integration-ios.md` with provider-mode setup, `MainActor` callback guarantees, and server-bound API restrictions.
- [x] Update this execution plan and the source plan status as implementation work lands or scope changes.

Validation:
- `git diff --check` passed after the documentation edits.
- A targeted `rg` audit confirmed the docs now cover `SignalingProvider`, `handlesReconnection`, `runTurnProbe()`, `onPeerMessage()`, `requires serverHost`, and the lexicographic offer-ownership rule.

## Phase 8 - Verification And Merge Gates
### Built-In Regression
- [x] Run `node scripts/check-resilience-constants.mjs` to confirm signaling refactors did not drift shared resilience timing.
- [x] Run web tests and build from `client/` for `@serenada/core` and `@serenada/react-ui`.
- [x] Run Android unit tests from `client-android/` for the core module and any touched host-app integration code.
- [x] Run iOS package and app tests from `client-ios/` after regenerating the Xcode project if needed.
- [x] Run built-in signaling regression scenarios on all three platforms: join, reconnect, leave, end room, ICE restart, TURN refresh, and fallback-offer timeout.
- [x] Verify the fallback-offer timer still recovers call setup after the lexicographic offer-ownership cutover on all three platforms.

### Custom-Provider Smoke
- [x] Run custom-provider smoke scenarios on all three platforms with a mock or in-memory adapter that emits incremental presence only.
- [x] Run custom-provider smoke scenarios on all three platforms with `roomStateUpdated` snapshots enabled.
- [x] Verify `runAll()`, `runTurnProbe()`, and `runConnectivityChecks()` produce the expected results in both server mode and provider mode.
- [x] Verify `createRoom()`, `createRoomId()`, and `RoomWatcher` succeed in server mode and fail clearly in provider mode.
- [x] Verify version-mismatch rejection, `getIceServers()` retry exhaustion, empty ICE-server lists, and reconnection-ownership branches with dedicated tests.
- [x] Verify web `onPeerMessage()` works in both built-in and custom-provider modes and confirm `subscribeToMessages()` is absent from the published API.
- [x] Do not merge until the lexicographic offer-ownership change has landed on web, Android, and iOS together.

Validation:
- `node scripts/check-resilience-constants.mjs` passed: `OK: 17 resilience constants match across platforms. (3 platform-specific skipped)`.
- `cd client && npm test` passed with `13` files and `246` tests green.
- `cd client && npm run build` passed.
- `cd client-android && ./gradlew :serenada-core:testDebugUnitTest` passed.
- `cd client-ios && xcodegen generate` passed.
- `cd client-ios && xcodebuild -quiet -project SerenadaiOS.xcodeproj -scheme SerenadaiOS -destination 'platform=iOS Simulator,OS=18.4,name=iPhone 16' -resultBundlePath /tmp/phase8-ios-full-final.xcresult test` passed; `xcresulttool` summary reported `116` passed tests, `0` failed tests, and `2` skipped UI tests.
- Dedicated regression coverage now includes provider-version rejection on Android and iOS, web ICE retry exhaustion and empty ICE-list fallback handling, and fallback-offer timeout recovery after lexicographic offer ownership on web and iOS.
- `git diff --check` passed after the final test and plan updates.
