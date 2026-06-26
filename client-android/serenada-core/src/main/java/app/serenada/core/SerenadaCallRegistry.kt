package app.serenada.core

import android.os.Looper
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.CallPhase
import app.serenada.core.call.MediaActivationState
import app.serenada.core.call.WebRtcResilienceConstants
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Multi-call session registry (contract §7 / design "Proposed Model"). Lets a
 * host keep several Serenada calls joined and switch the single foreground media
 * owner between them. Exactly one call may own local capture, screen share, and
 * process-wide audio at a time (Core Invariant 1); all others sit HELD (signaled
 * and connected, but owning no capture or audible playout; Core Invariant 2).
 *
 * The registry is the ONLY caller that acquires/releases the process-wide
 * [ForegroundMediaArbiter] lease for its calls (sessions it creates pass
 * `acquireForegroundLease = false`). It claims [ForegroundArbiterMode.REGISTRY]
 * for the process while it holds any non-ended call, so a direct
 * [SerenadaCore.join] fails fast with [ForegroundLeaseUnavailable] (Core
 * Invariant 6) — a host integrates through direct single-call APIs OR the
 * registry, not both.
 *
 * Operation serialization (contract §7): ALL mutating ops run through one
 * [Mutex] on [Dispatchers.Main.immediate]. The mutex guards foreground-lease +
 * call-map mutations, NOT slow network I/O: a composite join holds the lock only
 * for the short create+register and switch sections, releasing it across the
 * (seconds-long) held room join.
 *
 * The underlying [SerenadaSession] of the active call is exposed (not hidden) so
 * hosts can render it and read per-call diagnostics: `registry.activeSession`.
 */
class SerenadaCallRegistry internal constructor(
    private val core: SerenadaCore?,
    // Session factory seam (test injection). Production passes null and the
    // factory is derived from [core]; tests inject fakes. (roomRef, role) -> session.
    private val sessionFactory: ((RoomRef, CallMediaRole) -> SerenadaSession)? = null,
) {
    /** Production constructor: create a registry over a [SerenadaCore]. */
    constructor(core: SerenadaCore) : this(core, sessionFactory = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ONE mutex for ALL ops (join/switch/hold/leave/end). Held only for the
    // short locked sections (create+register, switch body), never across the
    // held room join (contract §7 "Operation serialization").
    private val opMutex = Mutex()

    // Identity-keyed mode claim so the arbiter knows the registry owns the process
    // (REGISTRY mode) while it has any non-ended managed call.
    private val modeRef = Any()
    private var modeClaimed = false

    private val managedCalls = LinkedHashMap<CallId, ManagedCall>()

    private val _state = MutableStateFlow(CallRegistryState())

    /** Aggregate, observable registry state (contract §11). */
    val state: StateFlow<CallRegistryState> = _state.asStateFlow()

    /** The active (foreground) call's [SerenadaSession], or null when none is foreground. */
    val activeSession: SerenadaSession?
        get() {
            assertMainThread()
            return activeCallId?.let { managedCalls[it]?.session }
        }

    /** The [SerenadaSession] for [callId], or null if not managed. Do not hide the session. */
    fun session(callId: CallId): SerenadaSession? {
        assertMainThread()
        return managedCalls[callId]?.session
    }

    // The active call is the one holding the foreground lease — the registry's
    // OWN authoritative signal ([ManagedCall.foregroundToken]), NOT the session's
    // mutable `mediaRole` (which a session-internal teardown resets to FOREGROUND).
    private val activeCallId: CallId?
        get() = managedCalls.values.firstOrNull { !it.ended && it.foregroundToken != null }?.callId

    // --- Internal mutable managed-call holder ---

    private inner class ManagedCall(
        val callId: CallId,
        val canonicalRoomId: String,
        val session: SerenadaSession,
    ) {
        /** The arbiter lease this call currently holds (foreground), or null. */
        var foregroundToken: ForegroundOwnerToken? = null

        /** Per-call error (contract §11). */
        var activationError: CallRegistryError? = null

        /** True once leave/end teardown has run; the call is then removed from the map. */
        var ended: Boolean = false

        /** Collector that refreshes published state when the session's state changes. */
        var stateJob: Job? = null
    }

    // --- Public API (contract §7) ---

    /**
     * Join [room] WITHOUT taking the foreground lease: the new call is created
     * HELD (stable senders, no capture, no audio coordinator, no lease). Idempotent
     * by canonical room id (a second live join returns the existing call).
     */
    suspend fun joinHeld(room: RoomRef): JoinResult {
        assertMainThread()
        // Part A (locked): create + register the managed call (mode guard, dedup).
        val created = withOp {
            registerOrDedup(room)
        }
        when (created) {
            is RegisterOutcome.Existing -> return JoinResult.Joined(created.call.callId)
            is RegisterOutcome.Failed -> return JoinResult.Failed(null, created.error)
            is RegisterOutcome.Created -> {
                // Part B (OUTSIDE the lock): held room join, bounded + cancellable.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    withOp { markCallFailed(created.call, joinError); teardownCall(created.call) }
                    return JoinResult.Failed(created.call.callId, joinError)
                }
                publish()
                return JoinResult.Joined(created.call.callId)
            }
        }
    }

    /**
     * Join [room] held, then switch to it (the common new-call flow; contract §7).
     * Holds the prior active call before activating the new one (Core Invariant 4
     * preflight first); a failing room join leaves the prior active call untouched.
     */
    suspend fun joinAndSwitch(room: RoomRef): JoinAndSwitchResult {
        assertMainThread()
        // Part A (locked): create + register.
        val created = withOp {
            registerOrDedup(room)
        }
        val call = when (created) {
            is RegisterOutcome.Existing -> created.call
            is RegisterOutcome.Failed -> return JoinAndSwitchResult.Failed(null, created.error)
            is RegisterOutcome.Created -> {
                // Part B (OUTSIDE the lock): held room join, bounded + cancellable.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    // Old active call untouched; clean up the failed new call.
                    withOp { markCallFailed(created.call, joinError); teardownCall(created.call) }
                    return JoinAndSwitchResult.Failed(created.call.callId, joinError)
                }
                created.call
            }
        }
        // Part C (locked): the switch body. Re-reads activeCallId (the world may
        // have changed between A and B).
        return when (val result = withOp { switchBody(call) }) {
            is SwitchResult.Active -> JoinAndSwitchResult.Active(call.callId)
            is SwitchResult.NeedsPermission -> JoinAndSwitchResult.NeedsPermission(call.callId)
            is SwitchResult.Failed -> JoinAndSwitchResult.Failed(call.callId, result.error)
        }
    }

    /** Move the foreground lease to [callId] (contract §7 switch algorithm). */
    suspend fun switchToCall(callId: CallId): SwitchResult {
        assertMainThread()
        return withOp {
            val next = managedCalls[callId]
                ?: return@withOp SwitchResult.Failed(
                    CallRegistryError.CallNotFound("No managed call $callId to switch to"),
                )
            switchBody(next)
        }
    }

    /**
     * Hold [callId]: drain its foreground resources, release the lease, and set
     * `activeCallId = null`. No auto-promote (Core Invariant 5). Holding an
     * already-held call is a no-op.
     */
    suspend fun holdCall(callId: CallId) {
        assertMainThread()
        withOp {
            val call = managedCalls[callId] ?: return@withOp
            // Active-teardown keys off the LEASE TOKEN (the registry's authoritative
            // signal; FIX B), NOT the session's mutable `mediaRole` — a session-side
            // partial release/teardown can reset the role to FOREGROUND while the
            // registry still holds the token, which would skip the lease release and
            // wedge the process. A held call (no token) is a no-op.
            if (call.foregroundToken == null) return@withOp
            holdAndReleaseForeground(call)
        }
    }

    /**
     * Leave [callId] gracefully (the other participant stays). For the active
     * call, foreground resources are drained and the lease released first; held
     * calls remain connected and are NOT auto-promoted (Core Invariant 5).
     */
    suspend fun leaveCall(callId: CallId) {
        assertMainThread()
        withOp {
            val call = managedCalls[callId] ?: return@withOp
            // Active-teardown keys off the LEASE TOKEN, not the session role (FIX B):
            // a partial release that left the session role FOREGROUND must still
            // release the lease here. The call is going away, so leave/end release
            // UNCONDITIONALLY after the bounded drain (FIX E) — unlike hold, which
            // keeps the lease on a drain timeout.
            if (call.foregroundToken != null) {
                drainAndReleaseForegroundForTeardown(call)
            }
            call.session.leave()
            teardownCall(call)
        }
    }

    /** End [callId] for all participants, then tear it down (same lease handling as [leaveCall]). */
    suspend fun endCall(callId: CallId) {
        assertMainThread()
        withOp {
            val call = managedCalls[callId] ?: return@withOp
            // Same lease-token keying + unconditional release on the going-away path
            // as [leaveCall] (FIX B / FIX E).
            if (call.foregroundToken != null) {
                drainAndReleaseForegroundForTeardown(call)
            }
            call.session.end()
            teardownCall(call)
        }
    }

    /**
     * Remove an ended/failed call from the registry (contract §11 "Remove ended
     * calls after retention/dismiss"). Ended and failed calls linger so the host
     * can read the final phase + [ManagedCallState.qualitySummary] (or the failure
     * cause); the host dismisses them when done. A non-ended call is ignored
     * (use [leaveCall]/[endCall] first).
     */
    suspend fun dismissCall(callId: CallId) {
        assertMainThread()
        withOp {
            val call = managedCalls[callId] ?: return@withOp
            if (!call.ended) return@withOp
            managedCalls.remove(call.callId)
            publish()
        }
    }

    /**
     * Permanently dispose the registry: leave/close every managed call and clear
     * the arbiter mode claim. After this the process is free for a fresh registry
     * or direct join.
     */
    fun close() {
        assertMainThread()
        managedCalls.values.toList().forEach { call ->
            call.stateJob?.cancel()
            runCatching { call.session.close() }
        }
        managedCalls.clear()
        if (modeClaimed) {
            runCatching { ForegroundMediaArbiter.releaseMode(modeRef) }
            modeClaimed = false
        }
        scope.coroutineContext[Job]?.cancel()
        publish()
    }

    // --- Locked sections ---

    private sealed interface RegisterOutcome {
        data class Created(val call: ManagedCall) : RegisterOutcome
        data class Existing(val call: ManagedCall) : RegisterOutcome
        data class Failed(val error: CallRegistryError) : RegisterOutcome
    }

    /**
     * Locked create+register section (composite join part A; contract §7). Claims
     * REGISTRY mode (mode guard; fails on a direct conflict), dedups by canonical
     * room id, then constructs the HELD session and starts its state collector.
     */
    private fun registerOrDedup(room: RoomRef): RegisterOutcome {
        val canonical = canonicalRoomId(room)
        managedCalls.values.firstOrNull { !it.ended && it.canonicalRoomId == canonical }?.let {
            return RegisterOutcome.Existing(it)
        }
        // Mode guard (Core Invariant 6): claim REGISTRY for the process. A live
        // direct session makes this throw ForegroundLeaseUnavailable.
        try {
            if (!modeClaimed) {
                ForegroundMediaArbiter.claimMode(ForegroundArbiterMode.REGISTRY, modeRef)
                modeClaimed = true
            }
        } catch (e: ForegroundLeaseUnavailable) {
            val error = CallRegistryError.LeaseUnavailable(e.message ?: "Registry mode unavailable")
            _state.value = _state.value.copy(lastError = error)
            return RegisterOutcome.Failed(error)
        }

        val callId = UUID.randomUUID().toString()
        val session = createHeldSession(room)
        val call = ManagedCall(callId = callId, canonicalRoomId = canonical, session = session)
        managedCalls[callId] = call
        call.stateJob = scope.launch {
            // Refresh published state whenever the session's state changes (phase,
            // participant count, local cid, etc.).
            session.state.collect { publish() }
        }
        publish()
        return RegisterOutcome.Created(call)
    }

    /**
     * The §7 switch body (preflight → drain old → release lease → acquire next →
     * activate → rollback). Runs INSIDE the op lock. Re-reads the current
     * foreground call so it is correct after a composite join's unlocked part B.
     */
    private suspend fun switchBody(next: ManagedCall): SwitchResult {
        val current = activeCallId
        if (current == next.callId) return SwitchResult.Active
        next.activationError = null

        val gen = ForegroundMediaArbiter.nextOperationGeneration()

        // 0. PREFLIGHT before touching the old call (Core Invariant 4).
        when (next.session.preflightForeground()) {
            SerenadaSession.ForegroundPreflight.NEEDS_PERMISSION -> {
                next.session.setMediaActivationState(MediaActivationState.NEEDS_PERMISSION)
                next.activationError = CallRegistryError.NeedsPermission(
                    "Call ${next.callId} needs a mic/camera grant for its desired media",
                )
                publish()
                return SwitchResult.NeedsPermission
            }
            SerenadaSession.ForegroundPreflight.FAILED -> {
                val error = CallRegistryError.ActivationFailed("Preflight failed for ${next.callId}")
                next.activationError = error
                publish()
                return SwitchResult.Failed(error)
            }
            SerenadaSession.ForegroundPreflight.OK -> Unit
        }

        val old = current?.let { managedCalls[it] }

        // 1. Drain the OLD call with its token, bounded by the release timeout.
        if (old != null) {
            val oldToken = old.foregroundToken
            // Mark a release pending so the arbiter grants no new lease while the
            // old one may still be owned (contract §2 rule 2 / Invariant 1).
            ForegroundMediaArbiter.markReleasePending()
            val released = try {
                withTimeout(WebRtcResilienceConstants.FOREGROUND_RELEASE_TIMEOUT_MS) {
                    if (oldToken != null) old.session.releaseForeground(oldToken)
                }
                true
            } catch (e: TimeoutCancellationException) {
                false
            }
            if (!released) {
                // Timeout: the old call KEEPS its lease (release pending stays
                // set so nothing new is granted). Mark it failed and abort.
                old.session.setMediaActivationState(MediaActivationState.FAILED)
                old.activationError = CallRegistryError.ReleaseFailed(
                    "Releasing foreground of ${old.callId} timed out; it stays foreground",
                )
                publish()
                return SwitchResult.Failed(old.activationError!!)
            }
            // Release confirmed fully-held: now the registry frees the lease.
            if (oldToken != null) runCatching { ForegroundMediaArbiter.releaseLease(oldToken) }
            old.foregroundToken = null
        }

        // 2. Acquire a FRESH token for next and activate, bounded.
        val activated = activateUnder(next, gen, WebRtcResilienceConstants.FOREGROUND_ACTIVATE_TIMEOUT_MS)
        if (activated == null) {
            publish()
            return SwitchResult.Active
        }

        // Activation failed: abort the partially-activated target, free its lease,
        // then roll back to old under a FRESH generation (contract §7 step 3).
        next.foregroundToken?.let { token ->
            runCatching { next.session.abortForegroundActivation(token) }
            runCatching { ForegroundMediaArbiter.releaseLease(token) }
        }
        next.foregroundToken = null
        next.activationError = activated

        if (old != null) {
            val rollbackGen = ForegroundMediaArbiter.nextOperationGeneration()
            val rolledBackFailure = activateUnder(old, rollbackGen, WebRtcResilienceConstants.FOREGROUND_ACTIVATE_TIMEOUT_MS)
            if (rolledBackFailure == null) {
                // Old restored to foreground; surface the recoverable error on next.
                publish()
                return SwitchResult.Failed(activated)
            }
            // Rollback also failed: no foreground owner; surface both.
            old.foregroundToken?.let { token ->
                runCatching { old.session.abortForegroundActivation(token) }
                runCatching { ForegroundMediaArbiter.releaseLease(token) }
            }
            old.foregroundToken = null
            old.activationError = rolledBackFailure
            val both = CallRegistryError.ActivationAndRollbackFailed(
                "Activation of ${next.callId} failed (${activated.message}); rollback to ${old.callId} failed (${rolledBackFailure.message})",
            )
            _state.value = _state.value.copy(lastError = both)
            publish()
            return SwitchResult.Failed(both)
        }
        // No old call to roll back to.
        publish()
        return SwitchResult.Failed(activated)
    }

    /**
     * Acquire a fresh lease and activate [call] under [generation], bounded by
     * [timeoutMs]. Returns null on success, or the [CallRegistryError] on failure
     * (after which the caller aborts + releases the lease). On acquire failure the
     * lease was never taken, so the caller's abort path is a no-op.
     */
    private suspend fun activateUnder(
        call: ManagedCall,
        generation: Long,
        timeoutMs: Long,
    ): CallRegistryError? {
        return try {
            val token = ForegroundMediaArbiter.acquireForeground(
                ownerId = call.callId,
                mode = ForegroundArbiterMode.REGISTRY,
                modeOwnerRef = modeRef,
            )
            call.foregroundToken = token
            withTimeout(timeoutMs) {
                call.session.activateForeground(token, generation)
            }
            call.activationError = null
            null
        } catch (e: ForegroundLeaseUnavailable) {
            // Lease never granted: nothing to abort.
            call.foregroundToken = null
            CallRegistryError.LeaseUnavailable(e.message ?: "Foreground lease unavailable")
        } catch (e: TimeoutCancellationException) {
            CallRegistryError.ActivationFailed("Activating ${call.callId} timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallRegistryError.ActivationFailed(e.message ?: "Activation of ${call.callId} failed")
        }
    }

    /**
     * Hold [call]: drain its foreground resources, bounded by the release timeout,
     * and on success release the lease and set `activeCallId = null` (FIX E).
     *
     * Core Invariant 1 (the user keeps the call they were on): on a drain TIMEOUT
     * the lease is KEPT — the [ForegroundOwnerToken] and `activeCallId` stay put,
     * the call's [MediaActivationState] is marked FAILED, and a [ReleaseFailed]
     * error is surfaced. Releasing the lease on a half-drained call could leave two
     * owners (the half-drained session still holding capture/audio, plus whoever
     * acquires next), so hold must not. This is distinct from [leaveCall]/[endCall],
     * whose call is going away and so release UNCONDITIONALLY (see
     * [drainAndReleaseForegroundForTeardown]).
     */
    private suspend fun holdAndReleaseForeground(call: ManagedCall) {
        val token = call.foregroundToken ?: return
        // Mark a release pending so the arbiter grants no new lease while the old
        // one may still be owned (contract §2 rule 2 / Invariant 1).
        ForegroundMediaArbiter.markReleasePending()
        val released = try {
            withTimeout(WebRtcResilienceConstants.FOREGROUND_RELEASE_TIMEOUT_MS) {
                call.session.releaseForeground(token)
            }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (!released) {
            // Timeout: KEEP the lease + activeCallId (the user keeps this call).
            // releasePending stays set so nothing new is granted. Mark FAILED and
            // surface a release failure; do NOT release the lease or null the token.
            call.session.setMediaActivationState(MediaActivationState.FAILED)
            call.activationError = CallRegistryError.ReleaseFailed(
                "Holding ${call.callId} timed out draining its foreground; it stays foreground",
            )
            publish()
            return
        }
        // Drained fully-held: now free the lease (clears the pending flag) and drop
        // out of the active slot. No auto-promote (Core Invariant 5).
        runCatching { ForegroundMediaArbiter.releaseLease(token) }
        call.foregroundToken = null
        publish()
    }

    /**
     * Drain [call]'s foreground resources for leave/end and release its lease
     * UNCONDITIONALLY after the bounded drain (the call is going away regardless of
     * a drain timeout, so there is no live call to keep). Distinct from
     * [holdAndReleaseForeground], which keeps the lease on a timeout (FIX E).
     */
    private suspend fun drainAndReleaseForegroundForTeardown(call: ManagedCall) {
        val token = call.foregroundToken ?: return
        ForegroundMediaArbiter.markReleasePending()
        try {
            withTimeout(WebRtcResilienceConstants.FOREGROUND_RELEASE_TIMEOUT_MS) {
                call.session.releaseForeground(token)
            }
        } catch (e: TimeoutCancellationException) {
            // releaseForeground is idempotent/no-throw; a timeout still drops the
            // going-away call out of foreground. Free the lease so the process is
            // not wedged — unlike hold, no live call wants to keep this one.
        }
        runCatching { ForegroundMediaArbiter.releaseLease(token) }
        call.foregroundToken = null
        publish()
    }

    // --- Held join (outside the lock) ---

    /**
     * Await the held room join's outcome (composite join part B; contract §7).
     * Bounded by [WebRtcResilienceConstants.HELD_JOIN_TIMEOUT_MS] and cancellable.
     * Returns null on success (the session reached Waiting/InCall) or a
     * [CallRegistryError] on failure/timeout. Runs OUTSIDE the op lock so a slow
     * join does not block urgent ops.
     */
    private suspend fun awaitHeldJoin(call: ManagedCall): CallRegistryError? {
        return try {
            withTimeout(WebRtcResilienceConstants.HELD_JOIN_TIMEOUT_MS) {
                val settled = call.session.state.first { state ->
                    state.phase == CallPhase.Waiting ||
                        state.phase == CallPhase.InCall ||
                        state.phase == CallPhase.Error
                }
                if (settled.phase == CallPhase.Error) {
                    CallRegistryError.JoinFailed(settled.error?.toString() ?: "Held room join failed")
                } else {
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            CallRegistryError.JoinFailed("Held room join timed out")
        } catch (e: CancellationException) {
            throw e
        }
    }

    // --- Helpers ---

    private fun createHeldSession(room: RoomRef): SerenadaSession {
        sessionFactory?.let { return it(room, CallMediaRole.HELD) }
        val resolvedCore = core ?: error("SerenadaCallRegistry requires a SerenadaCore or a session factory")
        return resolvedCore.joinInternal(room, initialMediaRole = CallMediaRole.HELD)
    }

    private fun markCallFailed(call: ManagedCall, error: CallRegistryError) {
        call.activationError = error
    }

    /**
     * Drive [call] to its terminal teardown WITHOUT removing it from the map: stop
     * its state collector, close the session, and mark it ended. The call lingers
     * (showing its final phase + [ManagedCallState.qualitySummary]) until the host
     * [dismissCall]s it (contract §11). When no non-ended call remains the registry
     * drops its REGISTRY mode claim so the process is free again (contract §2 rule
     * 4).
     */
    private fun teardownCall(call: ManagedCall) {
        call.ended = true
        call.stateJob?.cancel()
        call.stateJob = null
        runCatching { call.session.close() }
        // Refresh the lingering state from the now-closed session (final phase +
        // finalized quality summary) ONCE, since the collector is gone.
        if (managedCalls.values.none { !it.ended } && modeClaimed) {
            runCatching { ForegroundMediaArbiter.releaseMode(modeRef) }
            modeClaimed = false
        }
        publish()
    }

    /**
     * Run [block] under the op mutex with [CallRegistryState.registryOperationInProgress]
     * published true for the duration (contract §7 operation serialization).
     */
    private suspend fun <T> withOp(block: suspend () -> T): T {
        return opMutex.withLock {
            _state.value = _state.value.copy(registryOperationInProgress = true)
            publish()
            try {
                block()
            } finally {
                _state.value = _state.value.copy(registryOperationInProgress = false)
                publish()
            }
        }
    }

    /** Recompute and emit the aggregate registry state from the managed sessions. */
    private fun publish() {
        val calls = managedCalls.values.map { it.toManagedCallState() }
        _state.value = _state.value.copy(calls = calls, activeCallId = activeCallId)
    }

    private fun ManagedCall.toManagedCallState(): ManagedCallState {
        val s = session.state.value
        // The role is the registry's authoritative view (lease ownership), NOT the
        // session's mutable `mediaRole`: a session-internal teardown resets the
        // latter to FOREGROUND, which would mislabel an ended/held call.
        val role = if (!ended && foregroundToken != null) CallMediaRole.FOREGROUND else CallMediaRole.HELD
        // Held/ended calls sit at INACTIVE; the live session value is only
        // meaningful while the call is the foreground one being activated. Keep a
        // surfaced NEEDS_PERMISSION/FAILED (set on the call without a transition).
        val activation = when {
            role == CallMediaRole.FOREGROUND -> session.mediaActivationStateForTest()
            session.mediaActivationStateForTest() == MediaActivationState.NEEDS_PERMISSION ->
                MediaActivationState.NEEDS_PERMISSION
            session.mediaActivationStateForTest() == MediaActivationState.FAILED ->
                MediaActivationState.FAILED
            else -> MediaActivationState.INACTIVE
        }
        return ManagedCallState(
            callId = callId,
            roomId = session.roomId,
            roomUrl = session.roomUrl,
            membershipPhase = s.phase,
            mediaRole = role,
            mediaActivationState = activation,
            desiredAudioEnabled = session.desiredAudioEnabledForTest(),
            desiredVideoMode = session.desiredVideoModeForTest(),
            actualAudioPublished = session.actualAudioPublishedForTest(),
            actualVideoPublished = session.actualVideoPublishedForTest(),
            participantCount = s.participantCount,
            localCid = s.localCid,
            held = role == CallMediaRole.HELD,
            displayName = s.localDisplayName,
            activationError = activationError,
            qualitySummary = if (ended || s.phase == CallPhase.Ending || s.phase == CallPhase.Idle) {
                session.qualitySummary
            } else {
                null
            },
        )
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaCallRegistry must be used on the main thread"
        }
    }
}
