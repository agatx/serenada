# SDK Pluggable Signaling Execution Plan

Source plan: [`docs/sdk-pluggable-signaling-plan.md`](sdk-pluggable-signaling-plan.md)

Status legend:
- `[ ]` not started
- `[x]` completed

## Guardrails
- [ ] Keep built-in `serverHost` behavior unchanged while adding custom-provider support.
- [ ] Keep the scope SDK-only: no server changes beyond directly related documentation updates.
- [ ] Avoid new dependencies and keep changes minimal and targeted.
- [ ] Preserve the headless SDK + optional UI boundary on web, Android, and iOS.
- [ ] Land the lexicographic offer-ownership change on web, Android, and iOS together to avoid mixed-client behavior.

## Critical Path
- [ ] Complete Phase 1 before Phase 2 so config validation references the finalized `SignalingProvider` contract rather than a moving target.
- [ ] Complete Phases 1 and 2 before starting platform rewires in Phases 3, 4, and 5.
- [ ] Run Phases 3, 4, and 5 in parallel where possible, but treat the lexicographic offer-ownership cutover as one coordinated cross-platform change.
- [ ] Complete cross-platform parity cleanup before landing docs and final verification.

## Phase 0 - Baseline And Change Control
- [ ] Re-read [`docs/sdk-pluggable-signaling-plan.md`](sdk-pluggable-signaling-plan.md) and treat it as the source of truth for interface shape, reconnection ownership, and non-goals.
- [ ] Validate the worktree and confirm dependencies are installed for the platforms that will be touched.
- [ ] Record current built-in signaling behavior for join, reconnect, leave, end-room, TURN refresh, and fallback-offer flows on all three client platforms.
- [ ] Identify existing tests that cover signaling, TURN, diagnostics, and room-watcher behavior so they can be migrated instead of duplicated.
- [ ] Check for overlapping in-flight changes in the targeted files before editing them.
- [ ] Capture the Phase 0 baseline findings in this document or a linked working note before implementation starts so regressions can be checked against a concrete baseline.

## Phase 1 - Shared Contract And API Decisions
- [ ] Define the cross-platform `SignalingProvider` abstraction and align the interface/protocol shape across TypeScript, Kotlin, and Swift.
- [ ] Define the shared event model for `connected`, `disconnected`, `joined`, `roomStateUpdated`, `peerJoined`, `peerLeft`, `message`, `roomEnded`, `error`, and `iceServersChanged`.
- [ ] Define the shared payload models for `ConnectionInfo`, `JoinOptions`, `JoinedEvent`, `RoomStateEvent`, `Participant`, `PeerEvent`, `PeerMessage`, `RoomEndedEvent`, and `ErrorEvent`.
- [ ] Encode the interface version contract (`version == 1`) and define the construction-time failure path for unsupported provider versions.
- [ ] Encode `ProviderCapabilities.handlesReconnection` with default-false semantics on all platforms.
- [ ] Define the `getIceServers()` contract consistently on all platforms: one initial fetch after join, three retries with exponential backoff, throw/reject on failure, and treat `[]` as a valid STUN-only result.
- [ ] Define `iceServersChanged` as the only credential-refresh path after the initial `getIceServers()` call.
- [ ] Define `roomStateUpdated` as optional so incremental-only adapters remain valid.
- [ ] Standardize on `peerId` as the provider-facing identifier and keep built-in `cid` terminology internal to the built-in provider.
- [ ] Lock the public replacement for web `subscribeToMessages()` as `onPeerMessage()`.
- [ ] Lock the offer-ownership rule to lexicographic peer ID comparison and treat `joinedAt` as informational only.

## Phase 2 - Config Validation And API Gating
- [ ] Update the config contract on all platforms so exactly one of `serverHost` or `signalingProvider` must be provided.
- [ ] Add fail-fast validation when both `serverHost` and `signalingProvider` are set.
- [ ] Add fail-fast validation when neither `serverHost` nor `signalingProvider` is set.
- [ ] Define a single gating rule for server-bound APIs so `createRoom()`, `createRoomId()`, `RoomWatcher`, and server connectivity probes throw `requires serverHost` in provider mode.
- [ ] Define diagnostics behavior by mode at config-construction time rather than at join time.
- [ ] Audit in-repo call sites, samples, and tests for the new config contract before implementation lands.

## Cross-Cutting Workstream - Offer Ownership Cutover
- [ ] Implement the lexicographic peer-ID offer-ownership rule on web, Android, and iOS as one coordinated change set.
- [ ] Remove any remaining runtime dependency on `joinedAt` for offer/answer ownership while keeping `joinedAt` available for informational UI only.
- [ ] Verify the fallback-offer timer still recovers negotiation when the expected offer does not arrive after the ownership-rule change.
- [ ] Do not merge a partial offer-ownership rollout on only one or two platforms.

## Phase 3 - Web SDK Implementation
- [ ] Add `client/packages/core/src/SignalingProvider.ts` with the TypeScript interface, event/payload types, and `ProviderCapabilities`.
- [ ] Add the reusable `SignalingProviderEmitter` base class for third-party web adapters.
- [ ] Export `SignalingProvider`, the provider event/payload types, `ProviderCapabilities`, and `SignalingProviderEmitter` from `client/packages/core/src/index.ts`.
- [ ] Add `client/packages/core/src/SerenadaServerProvider.ts` to wrap `client/packages/core/src/signaling/SignalingEngine.ts` and the existing TURN sourcing flow.
- [ ] Ensure `client/packages/core/src/SerenadaServerProvider.ts` declares `capabilities.handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [ ] Update `client/packages/core/src/types.ts` to make `serverHost` optional, add `signalingProvider`, remove `subscribeToMessages`, and expose `onPeerMessage`.
- [ ] Rewire `client/packages/core/src/SerenadaSession.ts` to depend on `SignalingProvider` rather than directly on `SignalingEngine`.
- [ ] Move web session signaling flow to provider events for connect/disconnect, join, room-state refresh, peer presence, peer messages, room end, errors, and ICE-server refresh.
- [ ] Add session-owned `getIceServers()` retry logic in `client/packages/core/src/SerenadaSession.ts`.
- [ ] Branch web reconnection behavior in `client/packages/core/src/SerenadaSession.ts` based on `capabilities.handlesReconnection`.
- [ ] Add the public `onPeerMessage()` API in `client/packages/core/src/SerenadaSession.ts` and remove `subscribeToMessages()` plumbing.
- [ ] Move initial ICE-server sourcing out of `client/packages/core/src/media/MediaEngine.ts` and into the session/provider boundary so the media layer consumes provider-supplied ICE configs.
- [ ] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on web.
- [ ] Update `client/packages/core/src/media/MediaEngine.ts` to use lexicographic peer ID comparison for offer ownership.
- [ ] Update `client/packages/core/src/SerenadaCore.ts` to validate config, instantiate the built-in provider when `serverHost` is present, use the injected provider otherwise, and gate server-only APIs.
- [ ] Update `client/packages/core/src/SerenadaDiagnostics.ts` to split device checks, TURN checks, and Serenada-server checks; add `runTurnProbe()`; and mark server probes as skipped in provider mode.
- [ ] Update `client/packages/core/src/RoomWatcher.ts` to reject provider mode with a clear `requires serverHost` error.
- [ ] Update `client/packages/react-ui/src/SerenadaCallFlow.tsx` to use `onPeerMessage()` for `content_state` handling.
- [ ] Update or replace web tests that assert raw Serenada signaling envelopes or direct `SignalingEngine` coupling.

## Phase 4 - Android SDK Implementation
- [ ] Add `client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt` with the provider interface, event models, capabilities, and threading contract documentation.
- [ ] Add `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaServerProvider.kt` to wrap `client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingClient.kt` and the built-in TURN flow.
- [ ] Ensure `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaServerProvider.kt` declares `handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [ ] Decide whether `client-android/serenada-core/src/main/java/app/serenada/core/call/SessionSignaling.kt` remains as an internal bridge or is deleted once `SerenadaSession.kt` fully depends on `SignalingProvider`.
- [ ] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaConfig.kt` for optional `serverHost` plus `signalingProvider`.
- [ ] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaCore.kt` to validate config, build the correct provider, and gate server-only APIs.
- [ ] Rewire `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaSession.kt` from `SessionSignaling` to `SignalingProvider`.
- [ ] Add main-looper trampolining around provider callbacks in `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaSession.kt` so third-party adapters can safely call back from background threads.
- [ ] Move initial ICE-server acquisition and retry logic into the session/provider boundary.
- [ ] Restrict `client-android/serenada-core/src/main/java/app/serenada/core/call/TurnManager.kt` to the built-in provider path so TURN sourcing no longer leaks through the generic session contract.
- [ ] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on Android.
- [ ] Branch Android reconnection behavior based on `capabilities.handlesReconnection`.
- [ ] Update `client-android/serenada-core/src/main/java/app/serenada/core/call/PeerNegotiationEngine.kt` to use lexicographic peer ID comparison for offer ownership.
- [ ] Update `client-android/serenada-core/src/main/java/app/serenada/core/SerenadaDiagnostics.kt` to support provider-mode TURN probing and to skip Serenada-server checks when `serverHost` is absent.
- [ ] Update `client-android/serenada-core/src/main/java/app/serenada/core/RoomWatcher.kt` and room-creation APIs to throw clear `requires serverHost` errors in provider mode.
- [ ] Update Android tests and fake signaling implementations to target the new provider contract rather than the old concrete signaling-client contract.

## Phase 5 - iOS SDK Implementation
- [ ] Add `client-ios/SerenadaCore/Sources/SignalingProvider.swift` with the provider protocol, event models, capabilities, and delegate contract.
- [ ] Add `client-ios/SerenadaCore/Sources/SerenadaServerProvider.swift` to wrap `client-ios/SerenadaCore/Sources/Signaling/SignalingClient.swift` and the built-in TURN flow.
- [ ] Ensure `client-ios/SerenadaCore/Sources/SerenadaServerProvider.swift` declares `handlesReconnection = true` so existing built-in reconnect behavior is preserved.
- [ ] Decide whether `client-ios/SerenadaCore/Sources/Signaling/SessionSignaling.swift` remains as an internal bridge or is deleted once `SerenadaSession.swift` fully depends on `SignalingProvider`.
- [ ] Update `client-ios/SerenadaCore/Sources/SerenadaConfig.swift` for optional `serverHost` plus `signalingProvider`.
- [ ] Update `client-ios/SerenadaCore/Sources/SerenadaCore.swift` to validate config, build the correct provider, and gate server-only APIs.
- [ ] Rewire `client-ios/SerenadaCore/Sources/SerenadaSession.swift` from `SessionSignaling` to `SignalingProvider`.
- [ ] Add `MainActor` trampolining around provider delegate callbacks in `client-ios/SerenadaCore/Sources/SerenadaSession.swift` so third-party adapters can safely invoke delegates off-actor.
- [ ] Move initial ICE-server acquisition and retry logic into the session/provider boundary.
- [ ] Restrict `client-ios/SerenadaCore/Sources/Call/TurnManager.swift` to the built-in provider path so TURN sourcing no longer leaks through the generic session contract.
- [ ] Apply initial and refreshed ICE-server configs from the session to existing and future peer connections on iOS.
- [ ] Branch iOS reconnection behavior based on `capabilities.handlesReconnection`.
- [ ] Update `client-ios/SerenadaCore/Sources/Call/PeerNegotiationEngine.swift` to use lexicographic peer ID comparison for offer ownership.
- [ ] Update `client-ios/SerenadaCore/Sources/SerenadaDiagnostics.swift` to support provider-mode TURN probing and to skip Serenada-server checks when `serverHost` is absent.
- [ ] Update `client-ios/SerenadaCore/Sources/RoomWatcher.swift` and room-creation APIs to throw clear `requires serverHost` errors in provider mode.
- [ ] Update iOS tests and fake signaling implementations to target the new provider contract rather than the old concrete signaling-client contract.

## Phase 6 - Cross-Platform Parity And Public Surface Cleanup
- [ ] Verify the built-in `SerenadaServerProvider` preserves existing WS/SSE transport behavior, ping/pong, reconnect tokens, room lifecycle, and TURN refresh semantics on all platforms.
- [ ] Verify the built-in provider advertises reconnection ownership correctly on all platforms so existing reconnect flows do not fall back to session-managed rejoin logic.
- [ ] Verify custom-provider mode never depends on Serenada protocol envelopes, room APIs, watcher APIs, or server-owned membership state beyond the abstract provider contract.
- [ ] Verify `hostPeerId` remains optional end-to-end and that UI layers tolerate it being absent.
- [ ] Verify `roomStateUpdated` is opportunistic and does not become a hard requirement for third-party adapters.
- [ ] Verify `iceServersChanged` updates both existing and future peer connections on all platforms.
- [ ] Remove or migrate any remaining APIs, docs, or tests that expose raw Serenada signaling envelopes directly to SDK consumers.
- [ ] Audit exports, diagnostics, and logs so `peerId` is the public/provider-facing identifier while built-in `cid` mapping stays internal.
- [ ] Update `samples/web/`, `samples/android/`, and `samples/ios/` to demonstrate both built-in signaling and custom-provider usage.
- [ ] Add at least one minimal custom-provider smoke-test sample or harness that uses incremental presence plus peer message delivery without Serenada signaling transport.

## Phase 7 - Documentation Updates
- [ ] Update `docs/serenada_protocol_v1.md` section 5.1 to describe lexicographic peer ID offer ownership and keep `joinedAt` informational only.
- [ ] Update `README.md` to explain built-in versus custom signaling modes and the config validation rules.
- [ ] Update `docs/sdk/sdk-api-reference.md` to document `SignalingProvider`, provider-mode constraints, `onPeerMessage()`, and `runTurnProbe()`.
- [ ] Update `docs/sdk/sdk-customization.md` with third-party adapter guidance, reconnection ownership semantics, and ICE-server sourcing expectations.
- [ ] Update `docs/sdk/sdk-integration-web.md` with provider-mode setup and the `SerenadaCallFlow` message-hook change.
- [ ] Update `docs/sdk/sdk-integration-android.md` with provider-mode setup, main-thread callback guarantees, and server-bound API restrictions.
- [ ] Update `docs/sdk/sdk-integration-ios.md` with provider-mode setup, `MainActor` callback guarantees, and server-bound API restrictions.
- [ ] Update this execution plan and the source plan status as implementation work lands or scope changes.

## Phase 8 - Verification And Merge Gates
### Built-In Regression
- [ ] Run `node scripts/check-resilience-constants.mjs` to confirm signaling refactors did not drift shared resilience timing.
- [ ] Run web tests and build from `client/` for `@serenada/core` and `@serenada/react-ui`.
- [ ] Run Android unit tests from `client-android/` for the core module and any touched host-app integration code.
- [ ] Run iOS package and app tests from `client-ios/` after regenerating the Xcode project if needed.
- [ ] Run built-in signaling regression scenarios on all three platforms: join, reconnect, leave, end room, ICE restart, TURN refresh, and fallback-offer timeout.
- [ ] Verify the fallback-offer timer still recovers call setup after the lexicographic offer-ownership cutover on all three platforms.

### Custom-Provider Smoke
- [ ] Run custom-provider smoke scenarios on all three platforms with a mock or in-memory adapter that emits incremental presence only.
- [ ] Run custom-provider smoke scenarios on all three platforms with `roomStateUpdated` snapshots enabled.
- [ ] Verify `runAll()`, `runTurnProbe()`, and `runConnectivityChecks()` produce the expected results in both server mode and provider mode.
- [ ] Verify `createRoom()`, `createRoomId()`, and `RoomWatcher` succeed in server mode and fail clearly in provider mode.
- [ ] Verify version-mismatch rejection, `getIceServers()` retry exhaustion, empty ICE-server lists, and reconnection-ownership branches with dedicated tests.
- [ ] Verify web `onPeerMessage()` works in both built-in and custom-provider modes and confirm `subscribeToMessages()` is absent from the published API.
- [ ] Do not merge until the lexicographic offer-ownership change has landed on web, Android, and iOS together.
