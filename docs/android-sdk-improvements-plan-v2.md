# Serenada Android SDK — Code-Level Implementation Plan

## Overview

Executable, code-level plan for landing the SDK improvements in [`serenada-sdk-proposal.md`](../zello-android-client/docs/plans/serenada-sdk-proposal.md). Designed for hand-off to a team of coding agents.

**Iteration 10 — final convergence.** Targeted fixes on top of iteration 9. Plan shape unchanged.

- **Reuse the existing `JoinReconnectOutcome` enum** ([SignalingProvider.kt:50](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)) for `CallEvent.Recovered`, instead of declaring a new `ReconnectOutcome`. The existing enum already has the same `FRESH | REATTACHED | RECOVERED` shape; introducing a parallel type is needless drift. API-Lock therefore *does not* introduce a new outcome enum — it simply references `JoinReconnectOutcome`.
- **Recovery API surface additions move to API-Lock.** The defaulted constructor parameters on `JoinedEvent` (`reconnectToken: String? = null`, `reconnectTokenTTLMs: Long? = null`) and `JoinOptions` (`reconnectToken: String? = null`) are part of the locked public surface, so they land in API-Lock. L0 keeps the *parser* and *plumbing* edits (`SerenadaServerProvider.processJoinedPayload`, `SignalingMessageRouter.processJoinedEvent`, TTL persistence). This restores "all public API additions land in API-Lock" without splitting hairs.
- **`ConnectionDegraded` and `ThermalThrottle` get explicit producer tests.** L1-Integrate adds `ConnectionDegradedEventTest`; L2-Integrate's `ThermalIntegrationTest` asserts a `CallEvent.ThermalThrottle` is emitted on each transition.
- **`StatsPoller` construction example uses the injected `mainDispatcher`** for `snapshotSlots`, so dispatcher-boundary tests exercise the same dispatcher the production code uses.

**Iteration 9 carryover.** Two follow-ups on top of iteration 8.

- **Drop unproduced `CallEvent` cases.** `CodecSwitched` and `BitrateAdapted` were declared in API-Lock but no later track produces or tests them. Per "empty knobs are API noise" (A8), removed from the locked sealed interface for 0.6.0; re-introduce in the release that adds a producer.
- **L1-Integrate checklist expanded** to enumerate dispatcher wiring, failure isolation, null-merge skip, thermal collector lifecycle, and main-thread publish boundaries.

**Iteration 8 carryover.** Five blocker fixes on top of iteration 7. The plan shape is unchanged.

- **`StatsPoller` threading model is now explicit.** Slot-list snapshot runs on the **session/main thread** (the only safe reader of `peerSlots`); the off-main worker pulls each tick's snapshot via `snapshotSlots: suspend () -> List<PeerConnectionSlotProtocol>` which internally hops to the **injected `mainDispatcher`** (`Dispatchers.Main.immediate` in production, the test dispatcher in tests) and copies the map. Stats *collection* and `mergeRealtimeStats` stay on the worker dispatcher (`Dispatchers.Default`). The three `publish*` callbacks hop back to the same injected `mainDispatcher` before touching `MutableStateFlow.value`. Resolves review block #1.
- **`StatsPoller` per-slot collection is now isolation-failure tolerant.** Each `slot.collectAwait()` is wrapped in `runCatching` so one stuck or throwing slot cannot kill the loop, and `mergeRealtimeStats(...)` returning `null` (no samples) skips the slow tick instead of NPE'ing into `publishRealtime`/`QualityScorer.score`. Resolves review block #2.
- **`JoinedEvent` is now listed as a binary-breaking change** alongside `RecoveryRecord`, `JoinOptions`, and `SerenadaConfig`. The two new constructor parameters are defaulted (`null`), so source compat holds; the binary signature does not. Resolves review block #3.
- **Reduced-motion testing uses an explicit component parameter**, not the non-existent `LocalAccessibilityManager.isReduceMotionEnabled`. L3 components accept `reduceMotion: Boolean = LocalReduceMotion.current` (a `ProvidableCompositionLocal<Boolean>` defined in `serenada-call-ui` defaulting to `false`). Production reads `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` once at the host and provides the value; tests pass `reduceMotion = true` directly to the component. Resolves review block #4.
- **`ThermalSensor` uses a `ThermalStatusSource` seam**, not a "fake PowerManager". The interface is API-level-aware; production binds it to `PowerManager.OnThermalStatusChangedListener` (API ≥ 29) or to a constant `NONE` source (API < 29). Tests provide a `FakeThermalStatusSource` directly. Resolves review block #5.

**Iteration 7 carryover.** Final pass converges six P0/P1 handoff inconsistencies the prior iteration missed. What moved is **ownership** (so each track compiles in isolation), **lifecycle detail** (so agents don't have to invent it), and a small number of **factual corrections** against the actual WebRTC AAR.

- **API-Lock now owns the additive `RecoveryRecord` fields** (`roomUrl: String = ""`, `displayName: String? = null`) **and the public config model types** (`VideoQualityConfig`, `AudioQualityConfig`, `AudioProcessingConfig`, `BluetoothPolicy`, `ThermalConfig`). L0/L2 keep the *behavior* edits but no longer carry the constructor/field declarations the earlier base-commit code already references. Resolves review P0-1, P0-3.
- **`JoinedEvent` is widened in L0 to carry `reconnectToken: String?` and `reconnectTokenTTLMs: Long?`** ([SignalingProvider.kt:52](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)). `SerenadaServerProvider.processJoinedPayload` already parses both — L0 just forwards them. `SignalingMessageRouter.processJoinedEvent` ([SignalingMessageRouter.kt:99](client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingMessageRouter.kt)) plumbs both through `onJoined`. Resolves review P0-2.
- **`QualityScorer` is a pure reducer over an explicit `QualityScoreState`** — `score(state, sample) -> (state', CallQuality)`. EWMA and hysteresis live in the state record. The stateful holder is `StatsPoller`. Resolves review P1-4.
- **`StatsPoller` lifecycle is specified**: owning `CoroutineScope`, `Dispatchers.Default` worker, `start()`/`stop()`, per-slot 1 s timeout via `withTimeoutOrNull`, guarded continuation resume. Cadence text corrected: **2 s = 10 fast publishes + 1 slow publish** (every 10th 200 ms tick). Resolves review P1-5.
- **`PeerAudioLevels` and `AudioLevelSmoother` are owned by L1-2a**, not L1-Integrate, so the policy file compiles standalone. Resolves review P1-6.
- **L0+ ownership widened**: damp body lives in `WebRtcEngine.dampLocalAudio()` near `toggleAudio` ([WebRtcEngine.kt:281](client-android/serenada-core/src/main/java/app/serenada/core/call/WebRtcEngine.kt)); `SessionMediaEngine` interface gets the method; `FakeMediaEngine` ([FakeMediaEngine.kt](client-android/serenada-core/src/test/java/app/serenada/core/fakes/FakeMediaEngine.kt)) records calls for tests. Resolves review P1-7.
- **L2-Integrate also owns `SessionAudioController.kt`** to widen the route surface ([SessionAudioController.kt](client-android/serenada-core/src/main/java/app/serenada/core/call/SessionAudioController.kt)). Resolves review P1-8.
- **VP9 `scalabilityMode` is dropped** from `SimulcastTransceiverBuilder`. The bundled `libwebrtc-7827` AAR's Java `RtpParameters.Encoding` still doesn't expose that field; we ship 3-layer simulcast on the existing encoding params and leave SVC for a later Java API exposure. Resolves review P1-9.
- **Test deps add `kotlinx-coroutines-test`** in API-Lock (`testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")`). Resolves review P2-10.
- **`AudioQualityAutoPolicy` is named explicitly** as the holder of FEC AUTO thresholds + dwell, alongside `SdpAudioPolicy`. Resolves review P2-11.
- **`assertNoEngineRefs` is wired into `:serenada-call-ui:check`** so V1 actually runs the guard. Resolves review P2-12.

Carryover from earlier iterations stays as decided: P1-2 (`AudioRouteDevice` exposed), P1-3 (`dampLocalAudio` seam), P1-5 (`onIceRestarted` callback), P1-6 (`Recovered` only on server-confirmed outcome), P2-7 (no-op stubs), P2-8 (`cd client-android &&` prefix), P2-9 (semantic-state tests), and the iteration-6 stats seam (`WebRtcStatsSnapshot`).

**Scope.** Android-only. Items 11–14 land in `serenada-call-ui`; items 1–10 in `serenada-core`. Items 15–19 (Zello-side glue) are out of scope.

**Repository anchors.** Paths are relative to `/Users/alexeygavrilov/Developer/src/connected/`.

## Goals

1. Ship every Core SDK item (1–10) and every Call-UI SDK item (11–14) per the proposal.
2. Each track lands with deterministic automated tests. Real BT radio (L2-5) and on-device thermal sensors (L1-3) are documented manual escapes; their selector logic is fully unit-tested.
3. Preserve **source** compatibility for existing consumers. ABI changes are acknowledged and signalled by a 0.5.x → 0.6.0 minor bump.
4. L0 + L1-Integrate are the unlock layer for L2 / L3.

## Non-Goals

- Pre-call UI, envelope push-wake, PTT arbitration, account/contact glue (proposal items 15–18).
- iOS/Web parity for the new types.
- Multi-party (>2 participants).
- Wire-protocol changes. Server already emits `reconnectToken`/`reconnectTokenTTLMs` and per-candidate `ice` messages.

## Requirements

### Functional

- **R1.** All public API additions are non-breaking at the **source** level: every new constructor parameter has a default value, every new method is additive, no existing methods change signature. Binary compatibility is **not** promised — see N4.
- **R2.** Every new config knob defaults to **today's behavior**. Default-flip PRs are separate, one-line, post-Zello-rollout.
- **R3.** New event/state surfaces are cold-safe; replay buffers are bounded.
- **R4.** Recovery survives process death. `RecoveryRecord` v2 carries `roomUrl: String = ""` (defaulted for source compat; runtime-validated) and `displayName: String? = null`; `reconnectTokenTTLMs` is plumbed end-to-end.
- **R5.** Quality score is deterministic given a fixed `RealtimeCallStats` sequence.

### Non-Functional

- **N1.** **No new runtime dependencies.** Recovery JSON keeps using `org.json.JSONObject`; no `kotlinx-serialization`.
- **N2.** No reflection in production code or tests. SDP rewriting is a tested helper with golden inputs/outputs.
- **N3.** Hot flows on `SerenadaSession` follow the existing pattern (`MutableStateFlow.asStateFlow()` / `MutableSharedFlow.asSharedFlow()`); they are bound to the session lifetime.
- **N4.** **ABI is allowed to break** in this release because the SDK is pre-1.0 (0.5.x → 0.6.0). Four public data classes change primary-constructor shape: `RecoveryRecord`, `JoinOptions`, `SerenadaConfig`, and `JoinedEvent` (the last is part of the public `SignalingProvider` surface — gains `reconnectToken` and `reconnectTokenTTLMs`, both `null`-defaulted). **Every new parameter has a default value**, so source-form callers continue compiling. The 0.6.0 release notes call this out for any external SDK consumer. `CallDiagnostics` is **not** modified — design grounds, not ABI grounds.

### Verification (run on every track)

- **V1.** `cd client-android && ./gradlew :serenada-core:test :serenada-call-ui:test`
- **V2.** `cd client-android && ./gradlew :serenada-core:lint :serenada-call-ui:lint` — no new warnings beyond baseline.
- **V3.** `node scripts/check-resilience-constants.mjs && node scripts/check-version-parity.mjs`
- **V4.** `tools/worktree-validate.sh` (`SKIP_IOS=1` acceptable on Linux CI).

## API Model

API additions land as the first commit on the feature branch (T API-Lock). It is a base commit, not a standalone PR. The consolidated PR ships at T Finalize.

```kotlin
// SerenadaSession — new top-level surfaces, side-by-side with existing state/diagnostics.
val events: SharedFlow<CallEvent>                                   // hot, replay=0, buffer=32, DROP_OLDEST
val qualityScore: StateFlow<CallQuality>                            // populated by L1-Integrate
val localAudioLevel: StateFlow<Float>                               // 0.0..1.0, populated by L1-Integrate
val remoteAudioLevels: StateFlow<Map<String, Float>>                // peerCid -> level
val thermalState: StateFlow<ThermalState>                           // populated by L1-Integrate
val audioRoute: StateFlow<AudioRouteDevice?>                        // currently active route (null if unknown)
val availableAudioRoutes: StateFlow<List<AudioRouteDevice>>         // resolves review P1-2

fun setPreferredAudioRoute(routeId: Int)                            // selects by stable AudioRouteDevice.id
fun setPreferredAudioRoute(route: AudioRoute)                       // convenience for "any device of this type"

// SerenadaCore — recovery API. Synchronous; uses existing `var delegate`.
fun SerenadaCore.getRecoverableSession(): RecoveryRecord?
fun SerenadaCore.resume(record: RecoveryRecord, displayName: String? = null): SerenadaSession

// SessionRecoveryToken — cross-process envelope. org.json-backed.
class SessionRecoveryToken {
    fun encode(): String
    fun toRecord(): RecoveryRecord                                  // returns the v2 RecoveryRecord
    companion object {
        fun decode(s: String): SessionRecoveryToken?                // null on unknown version
        fun fromRecord(r: RecoveryRecord): SessionRecoveryToken
    }
}

// AudioRouteDevice — exposed publicly for the picker (resolves review P1-2).
data class AudioRouteDevice(
    val id: Int,                  // stable id from AudioDeviceInfo.getId()
    val type: AudioRoute,         // SPEAKER, EARPIECE, BLUETOOTH_SCO, BLUETOOTH_LE, WIRED_HEADSET, ...
    val label: String,            // human-readable name from AudioDeviceInfo.productName
)
```

The two `setPreferredAudioRoute` overloads cover both "user picked a specific device from the list" (id-based) and "switch to speaker / earpiece" (type-based). Picking by id is exact; picking by type chooses the highest-priority device of that type, falling back to current route if none.

### Why telemetry stays off `CallDiagnostics`

Even with ABI relaxed (N4), telemetry as separate flows is the better **design**: each flow has its own change-detection granularity, callers compose with `combine`/`distinctUntilChanged` per-field, and we avoid emitting a fat snapshot every time any one value changes. `CallDiagnostics` and `SerenadaDiagnostics.kt` (the pre-flight utility) are both untouched.

## Execution DAG

```
T API-Lock (base commit; includes WebRTC test-harness probe)
  │
  ├─► L0 Recovery schema + resume()
  │     └─► L0+ Reconnect audio damp
  │
  ├─► L1 (independent policies in parallel)
  │     ├─ L1-1a QualityScorer.kt
  │     ├─ L1-2a AudioLevelSampler.kt
  │     ├─ L1-3a ThermalSensor.kt
  │     └─► L1-Integrate (single agent: wires all three into StatsPoller + SerenadaSession + PeerNegotiationEngine)
  │
  ├─► L2 (independent policies in parallel)
  │     ├─ L2-1a SimulcastTransceiverBuilder
  │     ├─ L2-2a SdpAudioPolicy + AudioQualityAutoPolicy
  │     ├─ L2-3a SdpRedPolicy (RED)
  │     ├─ L2-4a AudioDeviceModuleBuilder (seam)
  │     ├─ L2-5a CallAudioRoutePolicy
  │     ├─ L2-6  Trickle ICE verification (tests only)
  │     ├─ L2-7a ThermalGovernorPolicy (pure)
  │     └─► L2-Integrate (single agent)
  │
  └─► L3 (parallel, after L1-Integrate + L2-Integrate)
        ├─ L3-1 QualityChip
        ├─ L3-2 ReconnectingChip
        ├─ L3-3 AudioDevicePicker
        └─ L3-4 AudioLevelDot

T Finalize: docs, version bump, parity, single PR.
```

The "policy `*a` + integrator" pattern: each L1/L2 track first lands a self-contained policy class in its own file. A single follow-up integrator agent owns the only edits to the shared big files (`StatsPoller.kt`, `SerenadaSession.kt`, `WebRtcEngine.kt`, `CallAudioSessionController.kt`, `SerenadaConfig.kt`, plus `PeerConnectionSlotProtocol.kt`/`PeerConnectionSlot.kt`/`FakePeerConnectionSlot.kt` and `PeerNegotiationEngine.kt` for L1-Integrate, and `SessionAudioController.kt` for L2-Integrate).

### File ownership table

| Track | Writes |
|---|---|
| API-Lock | `CallEvent.kt` *new* (uses existing `JoinReconnectOutcome`), `SessionRecoveryToken.kt` *new*, `AudioRoute.kt` + `AudioRouteDevice.kt` *new*, `RecoveryStorage.kt` (additive `roomUrl` + `displayName` fields with defaults — schema/migration body still in L0), `VideoQualityConfig.kt` *new* (data class only), `AudioQualityConfig.kt` *new* (data class only), `AudioProcessingConfig.kt` *new* (data class only), `BluetoothPolicy.kt` *new* (data class only), `ThermalConfig.kt` *new* (data class only), `SerenadaConfig.kt` (sub-field defaults plumbed; bodies don't read them yet), `SignalingProvider.kt` (`JoinedEvent` gains `reconnectToken: String? = null` + `reconnectTokenTTLMs: Long? = null`; `JoinOptions.reconnectToken: String? = null` — both signature-only, parser bodies still in L0), `SerenadaSession.kt` (new flows + `setPreferredAudioRoute` no-op stubs), `SerenadaCore.kt` (`resume` no-op stub), `SessionMediaEngine.kt` (`dampLocalAudio` no-op stub), `serenada-call-ui/build.gradle.kts` (Compose test deps + `assertNoEngineRefs` wired into `check`), `serenada-core/build.gradle.kts` (`kotlinx-coroutines-test` test dep), `WebRtcHarnessProbeTest.kt` *new* |
| L0 | `RecoveryStorage.kt` (v2 schema + migration body), `SerenadaServerProvider.processJoinedPayload` forwards `reconnectToken` + `reconnectTokenTTLMs` (signatures landed in API-Lock), `SignalingMessageRouter.processJoinedEvent` ([SignalingMessageRouter.kt:99](client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingMessageRouter.kt)) plumbs them through `onJoined`, `SerenadaServerProvider.kt` (`JoinOptions.reconnectToken` seeded on first connect), `SerenadaCore.kt` (`resume` body), `SerenadaSession.kt` (TTL persistence, internal join-with-token path, `Recovered` emission on server-confirmed outcome) |
| L0+ | `SerenadaSession.kt` (state observer), `SessionMediaEngine.kt` (`dampLocalAudio` body on the interface — delegates to engine), `WebRtcEngine.kt` (concrete `dampLocalAudio` near `toggleAudio` at [WebRtcEngine.kt:281](client-android/serenada-core/src/main/java/app/serenada/core/call/WebRtcEngine.kt)), `FakeMediaEngine.kt` (records `dampLocalAudio` calls for tests at [FakeMediaEngine.kt](client-android/serenada-core/src/test/java/app/serenada/core/fakes/FakeMediaEngine.kt)) |
| L1-1a | `QualityScorer.kt` *new* (pure reducer + `QualityScoreState` data class) |
| L1-2a | `AudioLevelSampler.kt` *new* (pure extractor over raw `RTCStatsReport`), `PeerAudioLevels.kt` *new* (data class), `AudioLevelSmoother.kt` *new* (per-slot smoother) |
| L1-3a | `ThermalSensor.kt` *new* |
| L1-Integrate | `StatsPoller.kt`, `SerenadaSession.kt`, `PeerConnectionSlotProtocol.kt` (callback shape → `WebRtcStatsSnapshot`), `PeerConnectionSlot.kt` (call `AudioLevelSampler` in-place; emit snapshot), `FakePeerConnectionSlot.kt` (test double matches new shape), `PeerNegotiationEngine.kt` (add ICE-restart callback — resolves review P1-5) |
| L2-1a | `SimulcastTransceiverBuilder.kt` *new* (consumes API-Lock `VideoQualityConfig`; no `scalabilityMode` — see review P1-9) |
| L2-2a | `SdpAudioPolicy.kt` *new*, `AudioQualityAutoPolicy.kt` *new* (FEC AUTO thresholds + 30 s dwell — resolves review P2-11) |
| L2-3a | `SdpRedPolicy.kt` *new* |
| L2-4a | `AudioDeviceModuleBuilder.kt` *new* (seam over `JavaAudioDeviceModule.builder()`) |
| L2-5a | `CallAudioRoutePolicy.kt` *new* (consumes the public `AudioRouteDevice` from API-Lock) |
| L2-6 | `TrickleIceVerificationTest.kt` only |
| L2-7a | `ThermalGovernorPolicy.kt` *new* (consumes API-Lock `VideoQualityConfig` + `ThermalConfig`) |
| L2-Integrate | `WebRtcEngine.kt`, `CallAudioSessionController.kt`, `SessionAudioController.kt` (widen interface with route flows — resolves review P1-8), `SerenadaConfig.kt` (sub-field bodies are now read), `SerenadaSession.kt` (`setPreferredAudioRoute` bodies, `availableAudioRoutes`/`audioRoute` emitter wiring) |
| L3-1..4 | one Compose file each under `serenada-call-ui/.../components/` |

## StatsPoller concurrency design

`PeerConnectionSlot` is the only owner of the raw `RTCStatsReport` — it parses the report into a `RealtimeCallStats` summary and currently discards the rest. The cleanest seam is to widen what the slot returns: parse audio levels in the same pass and hand the scheduler a single snapshot. The scheduler stays slot-agnostic.

```kotlin
// PeerConnectionSlotProtocol.kt — widened callback shape (L1-Integrate change)
internal data class WebRtcStatsSnapshot(
    val summary: String,                                  // existing diagnostic string
    val realtime: RealtimeCallStats?,                     // existing parsed summary
    val audioLevels: PeerAudioLevels,                     // NEW — parsed in-slot from the same report
)

internal data class PeerAudioLevels(
    val remoteByTrackId: Map<String, Float>,              // smoothed inbound levels per remote audio track
    val localOutbound: Float,                             // smoothed outbound level for this peer's local sender
)

interface PeerConnectionSlotProtocol {
    /* …existing surface… */
    fun collectWebRtcStats(onComplete: (WebRtcStatsSnapshot) -> Unit)   // signature change
}
```

`AudioLevelSampler` is a pure helper called inside `PeerConnectionSlot.onStatsDelivered` against the raw `RTCStatsReport`; smoothing state is held per-slot. `StatsPoller` then sees only snapshots and owns its own lifecycle:

```kotlin
internal class StatsPoller(
    // Snapshot must be taken on the session/main thread because peerSlots is
    // session-owned and not thread-safe. The lambda hops to Main.immediate
    // internally and returns a defensive copy; the worker stays off-main.
    private val snapshotSlots: suspend () -> List<PeerConnectionSlotProtocol>,
    private val resolvePeerCid: (PeerConnectionSlotProtocol) -> PeerCid,
    // Publish callbacks hop to Main.immediate before touching MutableStateFlow.value.
    private val publishAudio: suspend (Map<PeerCid, Float>, Float /*localOutbound*/) -> Unit,
    private val publishRealtime: suspend (RealtimeCallStats) -> Unit,
    private val publishQuality: suspend (CallQuality) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,   // injectable for tests
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /* …existing deps… */
) {
    private var scope: CoroutineScope? = null
    private val mutex = Mutex()                       // single global in-flight guard
    private var qualityState = QualityScoreState.initial()

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + workerDispatcher).also { it.launch { runLoop() } }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    private suspend fun PeerConnectionSlotProtocol.collectAwait(): WebRtcStatsSnapshot? =
        withTimeoutOrNull(SLOT_STATS_TIMEOUT_MS) {     // 1 s per-slot ceiling
            suspendCancellableCoroutine { cont ->
                try { collectWebRtcStats { snap -> if (cont.isActive) cont.resume(snap) } }
                catch (t: Throwable) { if (cont.isActive) cont.resumeWithException(t) }
            }
        }

    private suspend fun runLoop() {
        var slowTickCounter = 0
        while (coroutineContext.isActive) {
            delay(FAST_LANE_INTERVAL_MS)               // 200 ms
            if (!mutex.tryLock()) continue             // skip if previous tick still draining
            try {
                val isSlowTick = (++slowTickCounter % SLOW_LANE_RATIO == 0)  // every 10th tick (2 s)
                val slots = snapshotSlots()             // hops to Main.immediate, copies, returns
                val snapshots = coroutineScope {
                    slots.map { slot ->
                        async {
                            // runCatching isolates a single failing slot from the rest of the tick.
                            slot to runCatching { slot.collectAwait() }.getOrNull()
                        }
                    }.awaitAll()
                }.mapNotNull { (s, snap) -> snap?.let { s to it } }

                val audioByPeer = mutableMapOf<PeerCid, Float>()
                var localOutbound = 0f
                for ((slot, snap) in snapshots) {
                    audioByPeer[resolvePeerCid(slot)] = snap.audioLevels.remoteByTrackId.values.maxOrNull() ?: 0f
                    localOutbound = maxOf(localOutbound, snap.audioLevels.localOutbound)
                }
                withContext(mainDispatcher) { publishAudio(audioByPeer, localOutbound) }

                if (isSlowTick) {
                    val merged = mergeRealtimeStats(snapshots.mapNotNull { it.second.realtime })
                        ?: continue                     // no samples this tick — skip slow publish
                    val (next, quality) = QualityScorer.score(qualityState, merged)
                    qualityState = next
                    withContext(mainDispatcher) {
                        publishRealtime(merged)
                        publishQuality(quality)
                    }
                }
            } finally { mutex.unlock() }
        }
    }
}
```

Threading model:

- **Slot-list snapshot** runs on `Dispatchers.Main.immediate` (the only safe reader of `peerSlots`); the lambda copies the map and returns the list to the worker.
- **Stats collection + merge** run on `Dispatchers.Default` (or `StandardTestDispatcher` in tests).
- **Publish** hops back to `Main.immediate` before mutating `MutableStateFlow.value`.

Failure isolation: each `slot.collectAwait()` is `runCatching`-wrapped, so one stuck/throwing slot cannot tear down the loop or sibling slots. `mergeRealtimeStats(...)` returning `null` (zero samples) skips the slow publish via `continue`, avoiding the NPE path the prior pseudocode allowed. Per-slot `withTimeoutOrNull(1_000)` keeps a stuck slot from blocking the whole tick. `QualityScorer.score` is a pure reducer over `QualityScoreState`; `StatsPoller` is the stateful holder.

**Cadence.** Fast lane fires every 200 ms (5 Hz). Slow lane rides every 10th fast tick — i.e. **2 s = 10 fast publishes + 1 slow publish**. Tested in `StatsPollerSchedulerTest.kt` (virtual time via `kotlinx-coroutines-test` + a `FakePeerConnectionSlot` returning canned `WebRtcStatsSnapshot`s) and in `PeerConnectionSlotAudioLevelTest.kt` (raw `RTCStatsReport` → `PeerAudioLevels`).

## Rollout policy

SDK ships every new knob with **today's behavior** as default. Zello opts in:

| Knob | SDK default | Zello opts in |
|---|---|---|
| `VideoQualityConfig.simulcastEnabled` | `false` | `true` |
| `AudioQualityConfig.fec` | `OFF` | `AUTO` |
| `AudioQualityConfig.dtx` | `OFF` | `AUTO` |
| `AudioQualityConfig.redundancy` (RED) | `false` | stays `false` until cross-platform reciprocal |
| `AudioProcessingConfig.*` | matches today's hard-coded path | (no change) |
| `BluetoothPolicy.preferLeAudio` | `false` | `true` |
| `ThermalConfig.policyEnabled` | `false` | `true` |
| `events`, telemetry flows | always on (purely additive) | — |

DTX default is `OFF` because today's WebRTC SDP doesn't set `usedtx=1`. AUTO would change media behavior, so `OFF` is the true preserve-current setting. Zello flips to `AUTO` after one production cycle.

---

## Track Details

### T API-Lock

**Scope.**

1. New types: `CallEvent` (sealed interface), `CallQuality`, `ThermalState`, `AudioRoute`, `AudioRouteDevice`, `SessionRecoveryToken`. `MediaKind` and `Direction` are deferred to the release that introduces a producer (see iteration-9 note).
2. **Public config model types as data classes (no behavior, defaults preserve today's behavior)** — `VideoQualityConfig`, `AudioQualityConfig`, `AudioProcessingConfig`, `BluetoothPolicy`, `ThermalConfig`. `SerenadaConfig` gains the corresponding sub-fields with defaults, but L2 still owns the bodies that *read* them. This keeps API-Lock the single locking commit for public surface.
3. **`RecoveryRecord` gains `roomUrl: String = ""` and `displayName: String? = null`** with defaults. L0 fills the storage migration body; the constructor change must land in API-Lock so `SessionRecoveryToken.toRecord()` and the `SerenadaCore.resume` stub compile.
3a. **`JoinedEvent` gains `reconnectToken: String? = null` and `reconnectTokenTTLMs: Long? = null`** ([SignalingProvider.kt:52](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)). Defaults preserve source compat. L0 fills the parser body that populates them; API-Lock owns the constructor signature so all downstream callers compile.
3b. **`JoinOptions.reconnectToken: String? = null`** is added in API-Lock for the same reason — it is part of the public `SignalingProvider` surface. L0 fills the seed-on-first-connect logic.
3c. **`CallEvent.Recovered.outcome` reuses `JoinReconnectOutcome`** ([SignalingProvider.kt:50](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)) — no new outcome enum is introduced.
4. New flows + methods on `SerenadaSession`: `events`, `qualityScore`, `localAudioLevel`, `remoteAudioLevels`, `thermalState`, `audioRoute`, `availableAudioRoutes`, `setPreferredAudioRoute(routeId)`, `setPreferredAudioRoute(route)`.
   - **Stubs are safe no-ops** (resolves review P2-7): `setPreferredAudioRoute` logs at DEBUG and returns; `events` is a real `MutableSharedFlow` that simply has no producers yet; `qualityScore` defaults to `CallQuality.UNKNOWN`; flows that are list-typed default to `emptyList()`.
5. `SerenadaCore.resume(record, displayName)` — synchronous stub that calls `assertMainThread()` and **delegates to `join(record.roomUrl, displayName ?: record.displayName)`** as a placeholder. L0 replaces the body with the real reconnect path. Stubs that throw are avoided so the base branch can run end-to-end tests during stacked work.
6. New seam on `SessionMediaEngine`: `fun dampLocalAudio(durationMs: Long, restoreIfEnabled: () -> Boolean)`. API-Lock body is a no-op; L0+ fills it in (and adds the concrete implementation in `WebRtcEngine`).
7. **Test deps wired** (resolves review P2-10, P2-12):
   ```kotlin
   // serenada-core/build.gradle.kts
   dependencies {
       /* …existing… */
       testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
   }

   // serenada-call-ui/build.gradle.kts
   dependencies {
       /* …existing… */
       testImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
       debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
       testImplementation("org.robolectric:robolectric:4.12.2")
       testImplementation("junit:junit:4.13.2")
   }
   android.testOptions.unitTests.isIncludeAndroidResources = true
   tasks.named("check") { dependsOn("assertNoEngineRefs") }   // wire CI guard into V1
   ```
8. **WebRTC test-harness probe.** `WebRtcHarnessProbeTest.kt` runs Robolectric, calls `PeerConnectionFactory.initialize` + creates a no-op `PeerConnection`. The probe's outcome is recorded in `client-android/serenada-core/TESTING.md`. L2 agents do not branch on viability.

**Tests.**

- `ApiSurfaceCompileTest.kt` — plain compile-time usage, no reflection:

  ```kotlin
  @Suppress("UNUSED_VARIABLE", "unused")
  class ApiSurfaceCompileTest {
      fun usesEvents(s: SerenadaSession) { val f: SharedFlow<CallEvent> = s.events }
      fun usesQuality(s: SerenadaSession) { val f: StateFlow<CallQuality> = s.qualityScore }
      fun usesRoutes(s: SerenadaSession) {
          val f: StateFlow<List<AudioRouteDevice>> = s.availableAudioRoutes
          s.setPreferredAudioRoute(0); s.setPreferredAudioRoute(AudioRoute.SPEAKER)
      }
      // …one fun per new public surface
  }
  ```
 
- `EventsBufferOverflowTest.kt` — uses an **active slow subscriber**. Subscriber consumes one event then `delay(10_000)`s; producer emits 100 events; on resume, subscriber sees the most-recent 32 (DROP_OLDEST semantics). With `replay = 0`, the test never asserts that a late subscriber sees prior events.
- One trivial Compose test under `:serenada-call-ui:test` to confirm test deps resolve.

**`CallEvent` shape (locked here, emitted later).**

```kotlin
sealed interface CallEvent {
    val timestampMs: Long
    data class IceRestarted(val reason: String, override val timestampMs: Long) : CallEvent
    data class AudioRouteChanged(val from: AudioRouteDevice?, val to: AudioRouteDevice?, override val timestampMs: Long) : CallEvent
    data class ConnectionDegraded(val from: CallQuality, val to: CallQuality, override val timestampMs: Long) : CallEvent
    data class FecEngaged(val lossPercent: Double, override val timestampMs: Long) : CallEvent
    data class ThermalThrottle(val from: ThermalState, val to: ThermalState, override val timestampMs: Long) : CallEvent
    data class Recovered(val tokenVersion: Int, val outcome: JoinReconnectOutcome, override val timestampMs: Long) : CallEvent
    // CodecSwitched and BitrateAdapted intentionally omitted in 0.6.0 — no producer track in this plan.
    // They will be introduced (along with their MediaKind / Direction enums) in the release that
    // adds the producer (codec-switching track / per-encoding bitrate adaptor).
}
// outcome reuses the existing app.serenada.core.JoinReconnectOutcome enum; no new enum is declared here.
```

**Checklist.**

- [ ] New types and flows compiled; stubs are safe no-ops, not throwing.
- [ ] `setPreferredAudioRoute` (id and type overloads) and `availableAudioRoutes` declared.
- [ ] `SessionMediaEngine.dampLocalAudio` seam declared.
- [ ] Compose test scopes added; trivial test runs; `kotlinx-coroutines-test` resolves on `:serenada-core:test`.
- [ ] `WebRtcHarnessProbeTest` runs and `TESTING.md` documents the chosen harness.
- [ ] `assertNoEngineRefs` runs as part of `:serenada-call-ui:check`.
- [ ] No new runtime deps; no reflection in tests.
- [ ] `CallDiagnostics` source untouched.

---

### T L0 — Recovery schema + `resume()`

**Schema (constructor in API-Lock; storage body here).** API-Lock landed:

```kotlin
data class RecoveryRecord(
    val roomId: String,
    val cid: String,
    val reconnectToken: String,
    val lastEpoch: Long?,
    val sessionStartTs: Long,
    val expiresAtMs: Long,
    val roomUrl: String = "",                // defaulted for source compat (review P0-1)
    val displayName: String? = null,
)

class RecoveryRecordIncompleteException(msg: String) : IllegalStateException(msg)
class RecoveryExpiredException : IllegalStateException("Recovery record expired")
```

A blank `roomUrl` cannot drive a reconnect, so `resume()` validates it and rejects with `RecoveryRecordIncompleteException`. The default satisfies R1 without weakening runtime behavior.

**Migration semantics.** `RecoveryStorage.load()` reads the JSON `version` field. If `version != 2` (or missing — i.e., v1 records written by 0.5.x), `load()` calls `clear()` and returns `null`. There is no in-memory v1 record with a blank `roomUrl`. `RecoveryRecordSchemaTest.kt` asserts: (a) v2 records round-trip; (b) v1 records are cleared and `load()` returns `null` on next call.

**TTL + token plumbing (resolves review P0-2).** Three coordinated edits (the constructor changes themselves landed in API-Lock — L0 only fills bodies):

1. `JoinedEvent` ([SignalingProvider.kt:52](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)) already carries `reconnectToken: String?` and `reconnectTokenTTLMs: Long?` (added in API-Lock as defaulted fields). L0 owns no signature change here.
2. `SerenadaServerProvider.processJoinedPayload` ([SerenadaServerProvider.kt:226](client-android/serenada-core/src/main/java/app/serenada/core/SerenadaServerProvider.kt)) parses both values from the wire payload (it already does — they were just discarded) and forwards them on the `JoinedEvent`.
3. `SignalingMessageRouter.processJoinedEvent` ([SignalingMessageRouter.kt:99](client-android/serenada-core/src/main/java/app/serenada/core/call/SignalingMessageRouter.kt)) replaces the current `onJoined(..., null, null, null)` with the real values from the event, and `onJoined`'s callback signature gains `reconnectToken: String?, reconnectTokenTTLMs: Long?`. `SerenadaSession` writes `expiresAtMs = now + ttl` from the server-issued value rather than a hard-coded constant, and persists `reconnectToken` into the v2 record.

**Reconnect-outcome plumbing (resolves review P1-6).** The server's `joined` payload already carries a `reconnect` field with values `fresh | reattached | recovered` (see `server/signaling.go:60–80`). `SignalingMessageRouter` now plumbs that field through `onJoined` to `SerenadaSession`. `SerenadaSession.persistRecoveryRecord` already runs on every `joined`; in addition, when this session was started via `core.resume(...)` AND the server outcome is `reattached` or `recovered`, `SerenadaSession` emits exactly one `CallEvent.Recovered(tokenVersion, outcome)`. `fresh` outcomes do not emit `Recovered`. Sessions started via plain `core.join(...)` never emit `Recovered`.

**Files.** `RecoveryStorage.kt`, `SignalingMessageRouter.kt`, `SignalingPayloads.kt` (parse the existing `reconnect` field if not already), `SerenadaSession.kt`, `SerenadaServerProvider.kt` (parser body for token + TTL; seeds `JoinOptions.reconnectToken` on first connect — both signatures landed in API-Lock), `SerenadaCore.kt`, `SessionRecoveryToken.kt` (org.json envelope, `version = 2`).

`SerenadaCore.resume`:

```kotlin
fun resume(record: RecoveryRecord, displayName: String? = null): SerenadaSession {
    assertMainThread()
    if (record.roomUrl.isBlank()) throw RecoveryRecordIncompleteException("roomUrl missing")
    if (System.currentTimeMillis() > record.expiresAtMs) throw RecoveryExpiredException()
    return joinInternal(
        url = record.roomUrl,
        displayName = displayName ?: record.displayName,
        reconnectPeerId = record.cid,
        reconnectToken = record.reconnectToken,
        markAsResume = true,                       // gates the Recovered event emission
    )
}
```

**Tests.**

- `RecoveryRecordSchemaTest.kt` — v2 round-trip; v1 cleared, second `load()` returns `null`.
- `RecoveryRecordIncompleteTest.kt` — blank-`roomUrl` record: `getRecoverableSession()` returns it (it's still in storage), but `core.resume(record)` throws `RecoveryRecordIncompleteException`.
- `RecoveryTtlPlumbingTest.kt` — `FakeSignalingProvider` emits `joined` with `reconnectTokenTTLMs = 30_000`; assert `expiresAtMs ≈ now + 30_000`.
- `RecoveryResumeTest.kt` — persist v2 record; `core.resume(record)` produces a session whose first join envelope contains both `reconnectPeerId` and `reconnectToken`.
- `RecoveredEventTest.kt` — three cases: `fresh` outcome → no `Recovered` event; `reattached` outcome → one `Recovered` event with `outcome = REATTACHED`; `recovered` outcome → one `Recovered` event with `outcome = RECOVERED`. Plain `join()` (no resume) → no `Recovered` event regardless of outcome.
- `RecoveryExpiredTest.kt` — past `expiresAtMs` → `getRecoverableSession()` returns `null`; `resume(stale)` throws `RecoveryExpiredException`.
- `SessionRecoveryTokenTest.kt` — `JSONObject` round-trip; reject unknown `version`.

**Checklist.**

- [ ] v2 schema + migration (v1 → cleared); `RecoveryRecordIncompleteException` defined (constructor change shipped in API-Lock).
- [ ] `SerenadaServerProvider.processJoinedPayload` forwards `reconnectToken` + `reconnectTokenTTLMs` on `JoinedEvent` (signature already added in API-Lock); `SignalingMessageRouter.onJoined` plumbs both.
- [ ] `JoinOptions.reconnectToken` plumbed; `SerenadaServerProvider` seeds it on first connect.
- [ ] `core.resume(record, displayName)` synchronous; uses existing `delegate`.
- [ ] `SessionRecoveryToken.toRecord()` returns v2 `RecoveryRecord`.
- [ ] `Recovered` event emitted only on server-confirmed `reattached`/`recovered` outcomes after `resume()`.
- [ ] No regression in `SerenadaSessionContractTest`.

---

### T L0+ — Reconnect audio damp

**Scope.** On `ConnectionStatus` transition into `Connected` from either `Recovering` or `Retrying`, suppress local audio for 200 ms to avoid the WebRTC squelch on reattach. Damp only when the user has not muted themselves; restore the prior effective state.

**Seam (resolves review P1-3, P1-7).** `SessionMediaEngine` is an interface ([SessionMediaEngine.kt](client-android/serenada-core/src/main/java/app/serenada/core/call/SessionMediaEngine.kt)). API-Lock declares the method; L0+ adds the body in two places:

```kotlin
// WebRtcEngine.kt — concrete implementation near toggleAudio() at line 281
override fun dampLocalAudio(durationMs: Long, restoreIfEnabled: () -> Boolean) {
    // Routes to the existing audio-track enable toggle, schedules a re-enable
    // that re-reads restoreIfEnabled() at the deadline. Does NOT mutate
    // CallState or broadcast media-state events.
    setLocalAudioEnabledInternal(false)              // private peer of toggleAudio that skips state broadcast
    mainHandler.postDelayed({
        if (restoreIfEnabled()) setLocalAudioEnabledInternal(true)
    }, durationMs)
}

// FakeMediaEngine.kt — records calls so ReconnectAudioDampTest can assert
override fun dampLocalAudio(durationMs: Long, restoreIfEnabled: () -> Boolean) {
    dampCalls += DampCall(durationMs, restoreIfEnabled)
}
```

`SerenadaSession` then observes its own state and triggers the seam:

```kotlin
private fun observeReconnectForDamp() = launch {
    state.map { it.connectionStatus }.distinctUntilChanged()
         .scan(null as ConnectionStatus? to null as ConnectionStatus?) { acc, cur -> acc.second to cur }
         .collect { (prev, cur) ->
             val transitioning = (prev == ConnectionStatus.Recovering || prev == ConnectionStatus.Retrying)
                              && cur == ConnectionStatus.Connected
             if (!transitioning) return@collect
             if (!state.value.localAudioEnabled) return@collect          // honor user mute
             mediaEngine.dampLocalAudio(200) { state.value.localAudioEnabled }
         }
}
```

The damp does not emit any `state` change, does not broadcast media-state, and reads `localAudioEnabled` again at the deadline so a mute issued during the 200 ms window is honored.

**Tests.** `ReconnectAudioDampTest.kt`:

- Damps when `Recovering → Connected` and `localAudioEnabled = true`.
- Damps when `Retrying → Connected` and `localAudioEnabled = true`.
- Does not damp when user is muted (`localAudioEnabled = false`).
- If the user mutes during the 200 ms damp window, the post-delay restore checks the new mute state and does not re-enable.
- Damp does not emit any `state.localAudioEnabled = false` snapshot through `SerenadaSession.state`.

**Checklist.**

- [ ] `WebRtcEngine.dampLocalAudio` body implemented; `FakeMediaEngine` records calls.
- [ ] `SerenadaSession.observeReconnectForDamp` wired.
- [ ] Mute-state respected; no spurious `state` emissions.
- [ ] All five test cases green.

---

### T L1-1a — `QualityScorer.kt`

Pure reducer over an explicit state record (resolves review P1-4):

```kotlin
internal data class QualityScoreState(
    val ewmaR: Double,             // smoothed R-factor (audio)
    val ewmaVideo: Double,
    val sustainedAtCurrent: Int,   // ticks the candidate has held — drives hysteresis
    val current: CallQuality,
) {
    companion object { fun initial() = QualityScoreState(0.0, 0.0, 0, CallQuality.UNKNOWN) }
}

internal object QualityScorer {
    fun score(state: QualityScoreState, sample: RealtimeCallStats): Pair<QualityScoreState, CallQuality>
}
```

G.107 simplified for audio (R-factor from rtt, jitter, loss); video from freeze rate, decode fps, decoded height; combine = `min`. EWMA over 5 s lives inside the state. Hysteresis: downgrade after 1 sample, upgrade after 3 (`sustainedAtCurrent` counter). `StatsPoller` owns the state cell — there is no global mutable state in the scorer.

**Tests.** `QualityScorerTest.kt` — 30-row golden table; oscillation around boundaries doesn't flap (driving the same sequence twice yields the same trace).

### T L1-2a — `AudioLevelSampler.kt`

Pure extractor over a single raw `RTCStatsReport`. Owns the data classes it returns (resolves review P1-6):

```kotlin
// PeerAudioLevels.kt — owned by L1-2a so the policy file compiles standalone
internal data class PeerAudioLevels(
    val remoteByTrackId: Map<String, Float>,
    val localOutbound: Float,
)

// AudioLevelSmoother.kt — per-slot IIR state, owned by L1-2a
internal class AudioLevelSmoother { /* IIR coefficients per track-id */ }

// AudioLevelSampler.kt
internal object AudioLevelSampler {
    fun sample(report: RTCStatsReport, smoother: AudioLevelSmoother): PeerAudioLevels
}
```

Called from inside `PeerConnectionSlot.onStatsDelivered` (L1-Integrate wires that call site; the `*a` track only adds the three files). Smoothing state is per-slot and lives in the smoother instance.

**Tests.** `AudioLevelSamplerTest.kt` — synthetic `RTCStatsReport`s exercise inbound and outbound paths; silence ≤ 0.05; smoothing bounded; track-id keying preserved across calls.

### T L1-3a — `ThermalSensor.kt`

Two files (resolves review block #5):

```kotlin
// ThermalStatusSource.kt — internal seam, the only thing ThermalSensor reads
internal interface ThermalStatusSource {
    /** Hot flow of platform thermal status integers (PowerManager.THERMAL_STATUS_*). */
    fun statuses(): Flow<Int>
    companion object {
        fun fromPlatform(context: Context): ThermalStatusSource =
            if (Build.VERSION.SDK_INT >= 29) PowerManagerThermalStatusSource(context.getSystemService(PowerManager::class.java))
            else ConstantThermalStatusSource(PowerManager.THERMAL_STATUS_NONE)
    }
}

// ThermalSensor.kt — pure mapping over the seam
internal class ThermalSensor(private val source: ThermalStatusSource) {
    fun states(): Flow<ThermalState> =
        source.statuses().map { mapPlatformStatusToThermalState(it) }.distinctUntilChanged()
}
```

Production binds the seam to `PowerManager.OnThermalStatusChangedListener` (API ≥ 29) or to a constant `THERMAL_STATUS_NONE` source (API < 29). The pre-API-29 branch is selected at construction time, not at every emit.

**Tests.** `ThermalSensorTest.kt` — `FakeThermalStatusSource` exposes a `MutableSharedFlow<Int>`; the test pushes status codes and asserts mapped `ThermalState` emissions, including duplicate-suppression. No Robolectric, no fake `PowerManager`.

### T L1-Integrate

**Sole owner of `StatsPoller.kt`, `SerenadaSession.kt`, `PeerConnectionSlotProtocol.kt`, `PeerConnectionSlot.kt`, `FakePeerConnectionSlot.kt`, and `PeerNegotiationEngine.kt` edits in L1.**

1. Widen `PeerConnectionSlotProtocol.collectWebRtcStats` to deliver `WebRtcStatsSnapshot` (definition above). `summary` and `realtime` are populated by the existing parsing path; the new `audioLevels` field is populated by `AudioLevelSampler.sample(rawReport, smoother)` against the same raw `RTCStatsReport` the slot already has in hand at `PeerConnectionSlot.kt:408`. `WebRtcStatsSnapshot` is owned by L1-Integrate (it composes types from L1-2a).
2. `PeerConnectionSlot` holds an `AudioLevelSmoother` per slot lifetime; clears it on close.
3. `FakePeerConnectionSlot` returns canned `WebRtcStatsSnapshot`s to match.
4. Implement the slot-iterating scheduler above with explicit `start()`/`stop()`, owning supervised scope, injectable worker dispatcher (`Dispatchers.Default` → `StandardTestDispatcher` in tests), separately-injectable main dispatcher (`Dispatchers.Main.immediate` → the same `StandardTestDispatcher` in tests), `runCatching` per slot, `withTimeoutOrNull(1_000)` per slot, and null-merged-slow-tick skipping. `SerenadaSession` constructs the poller with `mainDispatcher = sessionMainDispatcher` (the same dispatcher used elsewhere in the session — `Dispatchers.Main.immediate` in production, the test dispatcher in tests) and passes `snapshotSlots = { withContext(sessionMainDispatcher) { peerSlots.values.toList() } }`. The injected dispatcher is used for both the snapshot hop and the publish hop, so dispatcher-boundary tests exercise the same shape as production. `StatsPoller` holds the `QualityScoreState` cell. `ThermalSensor` is a separate session-scoped collector built over the L1-3a `ThermalStatusSource` seam.
5. `PeerNegotiationEngine` exposes a new optional callback (resolves review P1-5):

```kotlin
// PeerNegotiationEngine
var onIceRestarted: ((reason: String) -> Unit)? = null
// fired from the existing scheduleIceRestart()/triggerIceRestart() paths
```

`SerenadaSession` registers a callback that forwards into the `events` SharedFlow as `CallEvent.IceRestarted`. Quality downgrades emit `ConnectionDegraded`; thermal transitions emit `ThermalThrottle`.

**Tests.**

- `StatsPollerSchedulerTest.kt` — virtual time (`StandardTestDispatcher` from `kotlinx-coroutines-test`). Required cases:
  - 2 s window = exactly 10 fast publishes + 1 slow publish.
  - Concurrent ticks do not stack (mutex test): a long collect that exceeds 200 ms causes the next tick to be skipped, not queued.
  - **Thrown slot collect**: one slot's callback throws; `runCatching` swallows it; sibling slots still publish on the same tick; loop continues to the next tick.
  - **Timed-out slot**: one slot never invokes its callback; `withTimeoutOrNull(1_000)` returns `null` for that slot; the tick still publishes whatever sibling slots produced.
  - **Null `realtime` per snapshot**: snapshots have non-null `audioLevels` but null `realtime`; slow tick's `mergeRealtimeStats` returns `null`; slow tick `continue`s without `publishRealtime`/`publishQuality`.
  - **All-null slow tick**: zero slots returned snapshots this tick; slow tick `continue`s without panic.
  - **Dispatcher boundaries**: `publishAudio` / `publishRealtime` / `publishQuality` invocations land on the `mainDispatcher`, not the worker dispatcher (test asserts via dispatcher tagging in the publish lambdas).
  - `start()`/`stop()` lifecycle round-trips: `start()` is idempotent; `stop()` cancels the scope; calling `start()` again creates a fresh scope and resumes ticking.
  - Uses `FakePeerConnectionSlot` returning canned `WebRtcStatsSnapshot`s.
- `PeerConnectionSlotAudioLevelTest.kt` — drive `PeerConnectionSlot.onStatsDelivered` with synthetic `RTCStatsReport`s; assert the emitted `WebRtcStatsSnapshot.audioLevels` matches expected smoothed values for inbound and outbound tracks.
- `SessionTelemetryWiringTest.kt` — end-to-end through `TestSessionFactory`; `localAudioLevel` and `remoteAudioLevels` flows update from snapshots.
- `IceRestartedEventTest.kt` — driving `FakePeerNegotiationEngine.onIceRestarted("manual")` produces exactly one `CallEvent.IceRestarted` on `session.events`.
- `ConnectionDegradedEventTest.kt` — feed canned `RealtimeCallStats` sequences through the slow-lane pipeline so the `QualityScorer` reports a downgrade (`GOOD → POOR`) and an upgrade (`POOR → GOOD`); assert a single `CallEvent.ConnectionDegraded(from=GOOD, to=POOR)` on the downgrade and **no** event on the upgrade (the contract is degradation-only, not symmetric — upgrades are surfaced via `qualityScore` but not as events).

**Checklist.**

- [ ] `WebRtcStatsSnapshot` defined; `PeerConnectionSlotProtocol` callback signature updated; `PeerConnectionSlot` and `FakePeerConnectionSlot` adapted.
- [ ] `AudioLevelSampler` invoked inside `PeerConnectionSlot` on the raw report; per-slot smoother lifecycle correct (cleared on slot close).
- [ ] `StatsPoller` has `start()`/`stop()`, owning supervised scope, **separate injectable worker (`Dispatchers.Default`) and main (`Dispatchers.Main.immediate`) dispatchers**, per-slot 1 s `withTimeoutOrNull`; consumes snapshots only.
- [ ] **Slot-list snapshot hops to `Main.immediate`** via `snapshotSlots: suspend () -> List<...>` constructed by `SerenadaSession`; worker never touches `peerSlots` directly.
- [ ] **Per-slot failure isolation**: each `slot.collectAwait()` wrapped in `runCatching`; a single thrown or timed-out slot does not cancel sibling collects or the loop.
- [ ] **Null-merge slow tick**: when `mergeRealtimeStats(...)` returns `null`, the slow tick `continue`s without invoking `publishRealtime` or `QualityScorer.score`.
- [ ] **Main-thread publish boundary**: `publishAudio`, `publishRealtime`, `publishQuality` invoked under `withContext(mainDispatcher)`; `MutableStateFlow.value` writes happen on main only.
- [ ] **Thermal collector lifecycle**: `ThermalSensor.states()` collected under the same session scope as `StatsPoller`; cancelled on session close; emits via the `thermalState: StateFlow<ThermalState>` flow on main.
- [ ] All three telemetry flows (`localAudioLevel`, `remoteAudioLevels`, `qualityScore`) populated end-to-end through `TestSessionFactory`.
- [ ] `PeerNegotiationEngine.onIceRestarted` callback added; events wired through to `session.events` as `CallEvent.IceRestarted`, on main.

---

### T L2 — pure policy tracks (parallel)

All `*a` tracks land **only new files** with **no edits to shared big files**.

- **L2-1a Simulcast.** `SimulcastTransceiverBuilder.kt` returns `RtpTransceiver.RtpTransceiverInit` for a given `VideoQualityConfig` (from API-Lock) — three encoding params with `maxBitrateBps` / `scaleResolutionDownBy` / `active`. **No `scalabilityMode`** (resolves review P1-9): the bundled `libwebrtc-7827` AAR's Java `RtpParameters.Encoding` does not expose that field. VP9 SVC is deferred. Tested with `SimulcastTransceiverBuilderTest.kt`.
- **L2-2a Opus FEC + DTX.** `SdpAudioPolicy.kt` — pure SDP munger, fmtp-only, idempotent (consumes API-Lock `AudioQualityConfig`, default `dtx = OFF`). `AudioQualityAutoPolicy.kt` — pure stats→action policy with the FEC AUTO thresholds (5 s loss > 0.5% engages; 30 s sustained < 0.2% disengages); L2-Integrate calls it from the slow-lane stats hook to drive renegotiation (resolves review P2-11). Tests: `SdpAudioPolicyTest.kt` (golden SDPs in `src/test/resources/sdp/`), `AudioQualityAutoPolicyTest.kt` (hysteresis + dwell), `OpusInteropHarnessTest.kt` (API-Lock harness round-trip).
- **L2-3a Opus RED.** `SdpRedPolicy.kt` — PT allocator (96–127, avoid existing PTs), m-line PT-list rewrite, `red/48000/2` rtpmap, `<red-pt> <opus-pt>/<opus-pt>` fmtp. Tests: `SdpRedPolicyTest.kt` (golden + PT-collision matrix) + `RedRoundTripHarnessTest.kt`. Default off; stays off this plan's lifetime.
- **L2-4a AEC/NS/AGC.** `AudioDeviceModuleBuilder.kt` — interface seam wrapping `JavaAudioDeviceModule.builder()` (consumes API-Lock `AudioProcessingConfig`). Tests: `AudioDeviceModuleBuilderTest.kt`.
- **L2-5a LE Audio routing.** `CallAudioRoutePolicy.kt` consumes the public `AudioRouteDevice` and `BluetoothPolicy` declared in API-Lock:
  ```kotlin
  fun select(devices: List<AudioRouteDevice>, policy: BluetoothPolicy, current: AudioRouteDevice?): AudioRouteDevice?
  ```
  Pure, trivially unit-testable. Adaptation from `AudioDeviceInfo` to `AudioRouteDevice` happens in `CallAudioSessionController` (L2-Integrate). Tests: `CallAudioRoutePolicyTest.kt` (BLE-only, SCO-only, both, none, `preferLeAudio` on/off).
- **L2-6 Trickle ICE — tests-only verification.** No new code, no `IceConfig` knob. `TrickleIceVerificationTest.kt` asserts `PeerNegotiationEngine.kt:155` sends `ice` synchronously per `onIceCandidate`; receiver-side buffer drains correctly.
- **L2-7a Thermal policy.** `ThermalGovernorPolicy.kt` — pure `(ThermalState, VideoQualityConfig) -> Policy(maxFps, disableHighestLayer, audioOnly)`. Tests: `ThermalGovernorPolicyTest.kt`.

### T L2-Integrate

**Sole owner** of edits to `WebRtcEngine.kt`, `CallAudioSessionController.kt`, `SessionAudioController.kt` (interface widening — resolves review P1-8), `SerenadaConfig.kt` (sub-field bodies), and `SerenadaSession.kt` in L2. The `SessionAudioController` interface ([SessionAudioController.kt](client-android/serenada-core/src/main/java/app/serenada/core/call/SessionAudioController.kt)) currently only exposes `activate/deactivate/shouldPauseVideoForProximity`; L2-Integrate adds `audioRoute: StateFlow<AudioRouteDevice?>`, `availableAudioRoutes: StateFlow<List<AudioRouteDevice>>`, `setPreferredAudioRoute(routeId: Int)`, `setPreferredAudioRoute(route: AudioRoute)` to the interface and implements them in `CallAudioSessionController`. Wires:

- `SimulcastTransceiverBuilder` into `WebRtcEngine` video sender (`addTransceiver`).
- `SdpAudioPolicy` (and optionally `SdpRedPolicy`) between `createOffer/Answer` and `setLocalDescription`. `AudioQualityAutoPolicy` is invoked from the slow-lane stats hook to flip FEC and trigger renegotiation.
- `AudioDeviceModuleBuilder` seam into `configureAudioDeviceModule` (lines 135–193).
- `CallAudioRoutePolicy` into `CallAudioSessionController.applyCallAudioRouting`. Adapts `AudioDeviceInfo` → `AudioRouteDevice` (id, type, label). Updates `audioRoute: StateFlow<AudioRouteDevice?>` and `availableAudioRoutes: StateFlow<List<AudioRouteDevice>>` flows on `SerenadaSession`. Implements both `setPreferredAudioRoute(routeId)` and `setPreferredAudioRoute(route)` bodies — the id-based form looks up the device in the cached available list; the type-based form picks the first matching device. Emits `CallEvent.AudioRouteChanged(from, to)`.
- `ThermalGovernorPolicy` consumer subscribes to `SerenadaSession.thermalState` and applies via `RtpSender.setParameters` mutations.

**Integration tests.**

- `WebRtcEngineSimulcastIntegrationTest.kt` — 3 `a=rid` lines on offer; offer/answer round-trips through the API-Lock harness.
- `OpusFecIntegrationTest.kt` — AUTO threshold + 30 s dwell drives renegotiation; `FecEngaged` emitted; harness round-trips.
- `WebRtcEngineAudioProcessingTest.kt` — each `AecMode` triggers the right ADM-builder calls.
- `LeAudioRoutingTest.kt` — `audioRoute` and `availableAudioRoutes` update; events emitted; both `setPreferredAudioRoute` overloads round-trip; selection by stale id falls back to current route.
- `ThermalIntegrationTest.kt` — SEVERE disables video track; MODERATE disables top simulcast layer; **each transition emits exactly one `CallEvent.ThermalThrottle(from, to)` with the correct `from`/`to` states**, and the no-op transition (e.g., `LIGHT → LIGHT`) emits nothing.

**Checklist.**

- [ ] All policies wired through one integrator agent.
- [ ] `SessionAudioController` interface widened; route flows + setter overloads land here.
- [ ] All new `SerenadaConfig` sub-field bodies wired in one PR.
- [ ] Both `setPreferredAudioRoute` overloads implemented.
- [ ] Integration tests green.

---

### T L3 — Compose components (parallel; depends on L2-Integrate for `setPreferredAudioRoute`)

Four files under `serenada-call-ui/.../components/`. Consume **only public flows / methods on `SerenadaSession`**.

- `QualityChip.kt` ← `session.qualityScore`
- `ReconnectingChip.kt` ← `session.state.connectionStatus`
- `AudioDevicePicker.kt` — binds to `session.audioRoute: AudioRouteDevice?` and `session.availableAudioRoutes: List<AudioRouteDevice>`; on selection calls `session.setPreferredAudioRoute(device.id)`. The picker shows `device.label` per row, so two paired Bluetooth devices are distinguishable (resolves review P1-2). Persistence is the host's responsibility.
- `AudioLevelDot.kt` ← `session.localAudioLevel` / `session.remoteAudioLevels[peerCid]`

**Reduced-motion seam.** L3 introduces a small composition local in `serenada-call-ui` so the components themselves stay declarative:

```kotlin
// ReduceMotion.kt — owned by L3
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
```

Each L3 component exposes `reduceMotion: Boolean = LocalReduceMotion.current` as its first non-data parameter; when `true`, the component renders the same final visual state but skips `animate*AsState`/`Crossfade` wrappers. The host app provides the value once at the call screen root by reading `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` (the platform-correct signal); L3 itself does not touch system settings.

**Tests.** Each: `createComposeRule()` + `onNodeWithContentDescription(...)` per state. **Reduced-motion variants are tested via the explicit parameter** (resolves review P2-9, iteration-8 review block #4): the test composes the component with `reduceMotion = true` (or wraps it in `CompositionLocalProvider(LocalReduceMotion provides true)`) and asserts the *observable* state — content description, semantics — without measuring animation calls. No reliance on `LocalAccessibilityManager.isReduceMotionEnabled` (which is not a Compose UI 1.6 API).

**CI guard.** API-Lock wires `:serenada-call-ui:assertNoEngineRefs` into `:serenada-call-ui:check`. The task fails the build if any `serenada-call-ui` source imports any of the **specific engine internals**:
- `app.serenada.core.call.WebRtcEngine`
- `app.serenada.core.call.PeerConnectionSlot` (and `.PeerConnectionSlotProtocol`)
- `app.serenada.core.call.PeerNegotiationEngine`
- `app.serenada.core.call.StatsPoller`
- `app.serenada.core.call.CallAudioSessionController`
- `android.media.AudioManager`

Public model types in `app.serenada.core.call.*` (e.g., `CallPhase`, `ConnectionStatus`, `LocalCameraMode`) remain allowed.

**Checklist.**

- [ ] Four components added.
- [ ] `assertNoEngineRefs` task in place with the narrow allowlist above and attached to `check`.
- [ ] Compose semantic-state tests green (no animation-call counts).

---

### T Finalize

1. CHANGELOGs in `client-android/serenada-core/` and `client-android/serenada-call-ui/`. **Call out the ABI break** (`RecoveryRecord`, `JoinOptions`, `SerenadaConfig`, `JoinedEvent`) explicitly under "Breaking changes (binary)".
2. Bump SDK version 0.5.x → **0.6.0** across all 8 sources tracked by `check-version-parity.mjs`. The minor bump is the public signal of the ABI break.
3. README example: `events`, `resume`, configs, `setPreferredAudioRoute`, `AudioRouteDevice`.
4. Open the consolidated PR. **API-Lock is not merged separately** — Finalize is the merge unit.

**Checklist.**

- [ ] CHANGELOGs note breaking ABI changes.
- [ ] Version bumped to 0.6.0; parity script green.
- [ ] README examples up to date.
- [ ] `tools/worktree-validate.sh` green on a clean clone.

---

## What the loss harness is and isn't

A `LossyNetwork` wrapper around `FakeSignalingTransport` exists for **control-plane** tests (recovery handoff, candidate ordering). It does not validate media features.

Media features are covered by:

- **Pure stats-policy tests** — `QualityScorerTest`, `AudioQualityAutoPolicyTest`, `ThermalGovernorPolicyTest`, `AudioLevelSamplerTest`, `CallAudioRoutePolicyTest`, `SimulcastTransceiverBuilderTest`. Deterministic, no harness.
- **WebRTC harness chosen at API-Lock** (recorded in `TESTING.md`). Used by `OpusInteropHarnessTest`, `RedRoundTripHarnessTest`, `WebRtcEngineSimulcastIntegrationTest`. Runs in `:serenada-core:test`, no device.
- **No `tc netem`** harness.

## Alternatives Considered

### A1. Modify `CallDiagnostics` in place

**Rejected on design grounds (not ABI grounds).** Even with N4 allowing ABI breaks, separate flows compose better and yield narrower change-detection.

### A2. Sidecar types instead of extending `RecoveryRecord` / `JoinOptions` / `SerenadaConfig`

**Considered, rejected.** Cost: doubled type surface, two `JoinOptions` flowing through `SignalingProvider`, idiom-breaking config split. Benefit: avoids a 0.x SDK ABI bump that consumers in this monorepo (Zello, sample app) won't notice because they're rebuilt in lockstep. The honest call is the version bump; sidecar types would carry compat baggage we don't need.

### A3. Single big-bang track

**Rejected.** The `*a` policy + integrator pattern is the smallest model that lets independent agents work in parallel without merge conflicts.

### A4. Generic SDP rewriter library

**Rejected.** Per-feature pure functions with golden tests are simpler.

### A5. Bundle Opus RED with FEC/DTX

**Rejected.** RED needs PT allocation + m-line surgery; FEC/DTX is an fmtp-only edit.

### A6. Inflate `SerenadaDiagnostics` with runtime flows

**Rejected.** That class is the pre-flight utility, not the runtime telemetry surface.

### A7. Re-engineer trickle ICE

**Rejected.** Sender-side trickle is already today's behavior.

### A8. Add an `IceConfig` knob with only `ENABLED`

**Rejected.** Empty knobs are API noise.

### A9. Audio dampening inside `ReconnectingChip`

**Rejected.** Audio behavior is core SDK policy. Dampening is in L0+ via the `SessionMediaEngine.dampLocalAudio` seam.

### A10. Persist `SessionRecoveryToken` inside the SDK via DataStore

**Considered.** The SDK already persists via `RecoveryStorage` (SharedPreferences). The envelope is a cross-process serializer (e.g., FCM data payloads).

### A11. Add `kotlinx-serialization-json`

**Rejected.** `org.json.JSONObject` is already used everywhere in the SDK.

### A12. Reflection-based API surface test

**Rejected.** Plain compile-time usage is enforced by `kotlinc`.

### A13. Default-on simulcast/FEC/LE Audio in the SDK from day one

**Rejected.** Default-flip PRs follow Zello rollout, one knob at a time.

### A14. Code-generate Kotlin types from a shared cross-platform schema

**Rejected.** No second consumer; premature.

### A15. Add Paparazzi/Roborazzi for visual regression

**Deferred.** Compose semantics tests cover logic regressions.

### A16. Default DTX = `AUTO`

**Rejected.** AUTO would change media behavior. `OFF` is the true preserve-current setting; Zello flips to AUTO post-rollout.

### A17. Use platform `AudioDeviceInfo` directly in `CallAudioRoutePolicy`

**Rejected.** Hard to instantiate in unit tests. Policy consumes `AudioRouteDevice`; adaptation lives in `CallAudioSessionController`.

### A18. Expose audio routes as `StateFlow<List<AudioRoute>>`

**Rejected (review P1-2).** Bare `AudioRoute` cannot distinguish two Bluetooth devices or carry labels. The picker needs `AudioRouteDevice` with `id` and `label`.

### A19. Fire `CallEvent.Recovered` at `resume()` start

**Rejected (review P1-6).** Optimistic emission lies if the server later issues a `fresh` outcome (token expired). Emit only after the server-confirmed `reattached`/`recovered`.

### A20. `NotImplementedError` stubs at API-Lock

**Rejected (review P2-7).** Stubs are safe no-ops so the base branch can run end-to-end tests during stacked work.

### A21. Keep `PeerConnectionSlotProtocol.collectWebRtcStats` callback shape unchanged and parse audio levels in `StatsPoller`

**Rejected (review P0, iteration 6).** The slot is the only owner of the raw `RTCStatsReport` — by the time `StatsPoller` would see anything, the report has already been parsed and discarded inside the slot ([PeerConnectionSlot.kt:408](client-android/serenada-core/src/main/java/app/serenada/core/call/PeerConnectionSlot.kt)). Either the slot keeps holding the report (which we'd have to expose, doubling the work and inviting two parsing passes) or the slot widens its callback once. Widening the callback once is the cleaner change; smoothing state lives where the parsing already lives.

### A22. Keep config model types in L2 (their natural home with the policies that read them)

**Rejected (review P0-3, iteration 7).** It would mean L2 tracks introduce new public types, contradicting "all public API additions land in API-Lock". L2 already depends on the model types (e.g., L2-7a depends on `VideoQualityConfig` from L2-1a even though both run in parallel). Moving the bare data classes to API-Lock is the single small change that unbreaks the parallel DAG and keeps API-Lock a real lock.

### A23. Implement VP9 SVC via `scalabilityMode`

**Rejected (review P1-9, iteration 7).** `libwebrtc-7827` ([build.gradle.kts:56](client-android/serenada-core/build.gradle.kts)) doesn't expose `scalabilityMode` on Java `RtpParameters.Encoding`. SVC is deferred; this plan ships 3-layer simulcast on the existing encoding params.

### A24. `QualityScorer` as a stateful object holding EWMA internally

**Rejected (review P1-4, iteration 7).** Hidden global state in a singleton is unfriendly to multi-session tests. The pure-reducer form (`(state, sample) -> (state', quality)`) is testable in isolation; `StatsPoller` is the natural state holder.

## Open Questions → Decisions

1. **Token signing** — trust server validation; envelope is unsigned.
2. **AUTO FEC hysteresis** — engage at 5 s loss > 0.5%, disengage after 30 s sustained < 0.2%.
3. **Default-on simulcast on low-end devices** — SDK default off; Zello opts in; thermal governor downshifts.
4. **Quality-score override hook** — none; SDK ships single reference impl.
5. **Compose UI screenshot framework** — none; semantic-state assertions only.
6. **LE Audio smoke testing** — documented in smoke-test README; selector unit-tested.
7. **iOS/Web parity timeline** — not in this plan.
8. **WebRTC test harness viability** — decided in API-Lock by `WebRtcHarnessProbeTest`; result recorded in `TESTING.md`.
9. **Pre-existing on-disk recovery records (v1)** — cleared at load; `load()` returns `null` on the next call.
10. **ABI compatibility** — explicitly broken across 0.5.x → 0.6.0 across `RecoveryRecord`, `JoinOptions`, `SerenadaConfig`, **and `JoinedEvent`**; source compat preserved via default-valued constructor parameters; CHANGELOG calls it out.
11. **DTX default** — `OFF` to preserve today's behavior; Zello opts into `AUTO`.
12. **Route policy input type** — SDK-owned `AudioRouteDevice`, not platform `AudioDeviceInfo`.
13. **Audio route picker granularity** — `StateFlow<List<AudioRouteDevice>>` with id-based selection (resolves review P1-2).
14. **`Recovered` event source** — emitted only on server-confirmed `reattached`/`recovered` outcomes after `resume()` (resolves review P1-6).
15. **Reconnect damp seam** — `SessionMediaEngine.dampLocalAudio(durationMs, restoreIfEnabled)`; declared at API-Lock, body in L0+ (resolves review P1-3).
16. **Stats seam for audio levels** — widen `PeerConnectionSlotProtocol.collectWebRtcStats` to deliver `WebRtcStatsSnapshot` (summary + realtime + audioLevels). `AudioLevelSampler` runs inside `PeerConnectionSlot` on the raw report; `StatsPoller` consumes snapshots only (resolves iteration-6 P0).
17. **Public-config home** — bare data classes (`VideoQualityConfig`, `AudioQualityConfig`, `AudioProcessingConfig`, `BluetoothPolicy`, `ThermalConfig`) live in API-Lock; bodies that read them stay in L2 (resolves iteration-7 P0-3).
18. **Recovery token + TTL on `JoinedEvent`** — `JoinedEvent` carries both fields; `SerenadaServerProvider` forwards them; `SignalingMessageRouter` plumbs them via `onJoined` (resolves iteration-7 P0-2).
19. **`QualityScorer` shape** — pure reducer over `QualityScoreState`; `StatsPoller` holds the cell (resolves iteration-7 P1-4).
20. **`StatsPoller` lifecycle** — owning `CoroutineScope`, injectable `Dispatchers.Default`, `start()`/`stop()`, per-slot `withTimeoutOrNull(1_000)`; cadence is 200 ms fast / 2 s slow = 10 fast + 1 slow per 2 s (resolves iteration-7 P1-5).
21. **`PeerAudioLevels` / `AudioLevelSmoother` ownership** — L1-2a, so the policy file compiles standalone (resolves iteration-7 P1-6).
22. **Damp body** — concrete in `WebRtcEngine`; `FakeMediaEngine` records calls (resolves iteration-7 P1-7).
23. **Audio-route seam** — `SessionAudioController` interface widened in L2-Integrate (resolves iteration-7 P1-8).
24. **VP9 SVC** — deferred; current AAR doesn't expose `scalabilityMode` (resolves iteration-7 P1-9).
25. **Test deps** — `kotlinx-coroutines-test` added; `assertNoEngineRefs` wired into `:serenada-call-ui:check` (resolves iteration-7 P2-10, P2-12).
26. **`AudioQualityAutoPolicy`** — explicit owner of FEC AUTO thresholds + dwell, called from L2-Integrate's slow-lane stats hook (resolves iteration-7 P2-11).
27. **`StatsPoller` threading** — slot-list snapshot on `Main.immediate`, collection/merge on `Default`, publish hops back to `Main.immediate` (resolves iteration-8 review block #1).
28. **`StatsPoller` failure isolation** — per-slot `runCatching`; null-merged slow tick `continue`s (resolves iteration-8 review block #2).
29. **Reduced-motion seam** — `LocalReduceMotion` composition local + per-component `reduceMotion: Boolean` parameter; host reads `Settings.Global.TRANSITION_ANIMATION_SCALE`; tests pass `true` directly (resolves iteration-8 review block #4).
30. **`ThermalSensor` test seam** — internal `ThermalStatusSource` interface; tests use `FakeThermalStatusSource`; production binds to `PowerManager.OnThermalStatusChangedListener` or constant source by API level (resolves iteration-8 review block #5).
31. **Unproduced events** — `CallEvent.CodecSwitched` and `CallEvent.BitrateAdapted` (and their `MediaKind` / `Direction` enums) are deferred until a producer track exists. Empty event types are API noise (resolves iteration-9 review on declared-but-unwired events).
32. **Recovery API surface ownership** — `JoinedEvent` token/TTL fields and `JoinOptions.reconnectToken` are signature-added in API-Lock; L0 owns only the parser/seed bodies. Restores the "all public API additions land in API-Lock" rule (resolves iteration-10 P1).
33. **`CallEvent.Recovered.outcome`** reuses the existing `JoinReconnectOutcome` enum ([SignalingProvider.kt:50](client-android/serenada-core/src/main/java/app/serenada/core/SignalingProvider.kt)); no parallel `ReconnectOutcome` is introduced (resolves iteration-10 P1).
34. **`ConnectionDegraded` is degradation-only.** No event is emitted on quality upgrade — upgrades surface via `qualityScore` only. Codified in `ConnectionDegradedEventTest` (resolves iteration-10 P1).

There are no remaining pre-track blockers — every prior open question is now a decision baked above.
