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

    // Set by [close]. Once closed, a queued create (a joinHeld/joinAndSwitch
    // suspended at the op mutex when close() ran) no-ops instead of re-claiming
    // REGISTRY mode and creating a session nothing will ever leave (parity with
    // the web/iOS `closed` guards).
    private var closed = false

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
    // STORED (not an O(N) scan on every read): kept in lockstep with the single
    // foreground lease by [setForeground]/[clearForeground], the ONLY token writers.
    private var activeCallId: CallId? = null

    // --- Foreground-token writers (the ONLY places a foregroundToken is assigned) ---
    // All token writes route through these so the stored [activeCallId] can never
    // drift from [ManagedCall.foregroundToken]. The single foreground lease means at
    // most one call holds a non-null token, so at most one is the active id.

    /** Take the foreground lease for [call]: store its token and mark it active. */
    private fun setForeground(call: ManagedCall, token: ForegroundOwnerToken) {
        call.foregroundToken = token
        activeCallId = call.callId
    }

    /** Drop [call]'s foreground lease: clear its token and the active id if it was active. */
    private fun clearForeground(call: ManagedCall) {
        call.foregroundToken = null
        if (activeCallId == call.callId) activeCallId = null
    }

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

        /**
         * Last published [ManagedCallState] snapshot for this call. The per-session
         * collector compares the freshly-recomputed snapshot against this and skips
         * [publish] when nothing a published field cares about changed (e.g. the ~1s
         * stats ticks, none of whose fields appear in [ManagedCallState]). [publish]
         * refreshes it as it maps each call so op-driven publishes keep caches coherent.
         */
        var lastSnapshot: ManagedCallState? = null
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
            is RegisterOutcome.Existing -> {
                // Await settlement for the REUSED call too: dedup can return a call
                // whose held join is still in flight (a second join racing the
                // first), and Joined must mean membership actually exists.
                // awaitHeldJoin returns immediately once the phase settled.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    withOp { failHeldJoin(created.call, joinError) }
                    return JoinResult.Failed(created.call.callId, joinError)
                }
                return JoinResult.Joined(created.call.callId)
            }
            is RegisterOutcome.Failed -> return JoinResult.Failed(null, created.error)
            is RegisterOutcome.Created -> {
                // Part B (OUTSIDE the lock): held room join, bounded + cancellable.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    withOp { failHeldJoin(created.call, joinError) }
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
            is RegisterOutcome.Existing -> {
                // The reused call's held join may still be in flight (a second
                // joinAndSwitch double-tapping the first). Await settlement before
                // switching: activating a session with no room membership captures
                // media for a room that may never join, and a later join timeout
                // would strand the acquired lease (Core Invariant 1). No-op once
                // settled, so a genuinely-live reused call switches immediately.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    withOp { failHeldJoin(created.call, joinError) }
                    return JoinAndSwitchResult.Failed(created.call.callId, joinError)
                }
                created.call
            }
            is RegisterOutcome.Failed -> return JoinAndSwitchResult.Failed(null, created.error)
            is RegisterOutcome.Created -> {
                // Part B (OUTSIDE the lock): held room join, bounded + cancellable.
                val joinError = awaitHeldJoin(created.call)
                if (joinError != null) {
                    // Old active call untouched; clean up the failed new call.
                    withOp { failHeldJoin(created.call, joinError) }
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

    /**
     * Move the foreground lease to [callId] (contract §7 switch algorithm).
     * Gated on held-join settlement first (OUTSIDE the lock, like composite part
     * B): switching to a still-joining call would activate capture on a session
     * with no room membership. Unknown/ended targets skip the gate and fail
     * inside [switchBody] as before.
     */
    suspend fun switchToCall(callId: CallId): SwitchResult {
        assertMainThread()
        managedCalls[callId]?.takeIf { !it.ended }?.let { call ->
            val joinError = awaitHeldJoin(call)
            if (joinError != null) {
                withOp { failHeldJoin(call, joinError) }
                return SwitchResult.Failed(joinError)
            }
        }
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
     * Permanently dispose the registry: leave every managed call through the same
     * drain path as [leaveCall] (releasing the active call's foreground lease),
     * clear the arbiter mode claim, and refuse new joins. After this the process
     * is free for a fresh registry or direct join. Suspends for the bounded
     * foreground drain — parity with web `close()` (which runs `leave()` per call)
     * and iOS `close()` (which runs `teardownBody` per call); a plain
     * `session.close()` would strand the REGISTRY-owned lease token, since
     * sessions self-release only a DIRECT lease.
     */
    suspend fun close() {
        assertMainThread()
        // Set BEFORE taking the op mutex so an in-flight joinHeld/joinAndSwitch
        // suspended at the lock re-checks it in registerOrDedup and no-ops.
        closed = true
        managedCalls.values.toList().forEach { call ->
            withOp {
                val live = managedCalls[call.callId]?.takeIf { !it.ended } ?: return@withOp
                // Same lease-token keying + unconditional release on the going-away
                // path as [leaveCall] (FIX B / FIX E).
                if (live.foregroundToken != null) {
                    drainAndReleaseForegroundForTeardown(live)
                }
                runCatching { live.session.leave() }
                teardownCall(live)
            }
        }
        withOp {
            managedCalls.clear()
            // teardownCall released the mode once no live call remained; this is a
            // backstop for a registry closed with only ended calls in the map.
            if (modeClaimed) {
                runCatching { ForegroundMediaArbiter.releaseMode(modeRef) }
                modeClaimed = false
            }
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
        // The registry was closed while this create sat at the op mutex (host
        // disposed it between joinHeld() and this locked section running). Do not
        // re-claim REGISTRY mode or create a session that close() already iterated
        // past and nothing will ever leave (parity with web createOrReuseCall and
        // iOS createAndRegister).
        if (closed) {
            return RegisterOutcome.Failed(CallRegistryError.JoinFailed("Registry is closed"))
        }
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
            // participant count, local cid, etc.). A session that reaches a TERMINAL
            // phase on its OWN (room_ended / remote end / fatal error — distinct from
            // a registry-initiated leave/end) must also run the serialized terminal
            // op so the registry releases its foreground lease + mode and clears
            // active; the session reset only releases a DIRECT lease (registry
            // sessions have none), so without this the lease + active slot leak.
            session.state.collect { state ->
                if (isTerminalPhase(state.phase)) {
                    // SERIALIZED through the same op mutex as every other mutation.
                    // Idempotent: a registry leave/end that already tore the call
                    // down makes this a no-op (guarded inside). Once the call is
                    // removed the collector has nothing left to do.
                    runTerminalCleanup(call)
                    return@collect
                }
                // Skip the full snapshot recompute when THIS call's published view did
                // not change (e.g. the ~1s stats ticks, none of whose fields appear in
                // ManagedCallState). Recompute only this call's snapshot; if it equals
                // the cached one, nothing a published field cares about moved, so skip
                // publish(). StateFlow would dedupe the equal emit anyway — this avoids
                // the per-tick recompute-all of every managed call. role/activation here
                // read the registry's authoritative token (correct on this thread).
                val snapshot = call.toManagedCallState()
                if (snapshot == call.lastSnapshot) return@collect
                call.lastSnapshot = snapshot
                publish()
            }
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
                // The target stays HELD (it never foregrounds; old is untouched), so
                // its mediaActivationState stays INACTIVE per the cross-platform
                // contract. The needed permission is carried ONLY on activationError.
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
                // Timeout: the old call KEEPS its lease (release pending stays set so
                // nothing new is granted) and stays foreground (Invariant 1). The
                // release failure is carried ONLY on activationError; its
                // mediaActivationState stays its live foreground value (cross-platform
                // contract — web/iOS surface this on activationError alone).
                old.activationError = CallRegistryError.ReleaseFailed(
                    "Releasing foreground of ${old.callId} timed out; it stays foreground",
                )
                publish()
                return SwitchResult.Failed(old.activationError!!)
            }
            // Release confirmed fully-held: now the registry frees the lease.
            if (oldToken != null) runCatching { ForegroundMediaArbiter.releaseLease(oldToken) }
            clearForeground(old)
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
        clearForeground(next)
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
            clearForeground(old)
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
            setForeground(call, token)
            withTimeout(timeoutMs) {
                call.session.activateForeground(token, generation)
            }
            call.activationError = null
            null
        } catch (e: ForegroundLeaseUnavailable) {
            // Lease never granted: nothing to abort.
            clearForeground(call)
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
     * the lease is KEPT — the [ForegroundOwnerToken] and `activeCallId` stay put and
     * a [ReleaseFailed] error is surfaced on [ManagedCallState.activationError]
     * (the failure is NOT mirrored onto [MediaActivationState] — cross-platform
     * contract). Releasing the lease on a half-drained call could leave two
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
            // releasePending stays set so nothing new is granted; do NOT release the
            // lease or null the token. The release failure is carried ONLY on
            // activationError; mediaActivationState stays its live foreground value
            // (cross-platform contract — web/iOS surface this on activationError alone).
            call.activationError = CallRegistryError.ReleaseFailed(
                "Holding ${call.callId} timed out draining its foreground; it stays foreground",
            )
            publish()
            return
        }
        // Drained fully-held: now free the lease (clears the pending flag) and drop
        // out of the active slot. No auto-promote (Core Invariant 5).
        runCatching { ForegroundMediaArbiter.releaseLease(token) }
        clearForeground(call)
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
        clearForeground(call)
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
     * Locked teardown for a held join that failed or timed out. IDEMPOTENT: two
     * callers can await the same reused failing join (settlement gating), and only
     * the first runs teardown. DEFENSE: with settlement gating on every switch
     * entry point a failed join can never hold the foreground lease, but release
     * one if a future path regresses that invariant — [teardownCall] alone would
     * strand it (the collector is cancelled and [runTerminalCleanup] skips ended
     * calls), wedging the process arbiter forever.
     */
    private fun failHeldJoin(call: ManagedCall, error: CallRegistryError) {
        if (call.ended) return
        markCallFailed(call, error)
        call.foregroundToken?.let { token ->
            runCatching { ForegroundMediaArbiter.releaseLease(token) }
            clearForeground(call)
        }
        teardownCall(call)
    }

    /**
     * Terminal session phases for lease purposes. `Ending` is treated as terminal
     * (release promptly rather than wait for the trailing `Idle`); `Idle` and
     * `Error` cover a session that settled or failed on its own. Live phases
     * (Joining/Waiting/InCall) and the transient setup phases are NOT terminal.
     */
    private fun isTerminalPhase(phase: CallPhase): Boolean =
        phase == CallPhase.Ending || phase == CallPhase.Idle || phase == CallPhase.Error

    /**
     * Serialized terminal cleanup for a call that reached a terminal phase on its
     * OWN (room_ended / remote end / fatal session error), NOT via a
     * registry-initiated [leaveCall]/[endCall]. Runs through the op mutex so it
     * cannot interleave a switch/hold/leave. The session's own reset already drove
     * it to fully-held + released any DIRECT lease, but a registry-created session
     * holds NO direct lease; the registry owns the [ManagedCall.foregroundToken]
     * lease and the active slot, which would otherwise leak forever.
     *
     * IDEMPOTENT + queue-safe (the #1 concern): a registry leave/end that already
     * released the lease and marked the call ended makes this a no-op. The guards
     * are (a) `call.ended` (leave/end teardown ran) and (b) a missing
     * `foregroundToken` is fine — releasing the lease/active is conditional on the
     * token still being set, so we never double-release.
     */
    private suspend fun runTerminalCleanup(call: ManagedCall) {
        withOp {
            // Already torn down by a registry-initiated leave/end (which released the
            // lease + marked ended): nothing to do. Without this guard a racing
            // collector emission would double-handle teardown.
            if (call.ended) return@withOp
            // If the call still holds the registry-owned foreground lease, release it
            // (the session reset never touched it) and drop the active slot. NO
            // auto-promote (Core Invariant 5). A held call has no token: skip both.
            call.foregroundToken?.let { token ->
                runCatching { ForegroundMediaArbiter.releaseLease(token) }
                clearForeground(call)
            }
            // Mark ended/non-live: excluded from "live" counts, dismissable, role view
            // collapses to held. teardownCall also stops the collector and releases
            // REGISTRY mode when no live call remains, then publishes.
            teardownCall(call)
        }
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
        // Defensive: every teardown path clears the token first (clearForeground also
        // drops the active id), so this is normally a no-op. Keep the stored active id
        // from ever pointing at an ended call even if some future path forgets to.
        if (activeCallId == call.callId) activeCallId = null
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
        // Refresh each call's cached snapshot as we map it so op-driven publishes keep
        // the per-call caches coherent with what the collector compares against.
        val calls = managedCalls.values.map { call ->
            call.toManagedCallState().also { call.lastSnapshot = it }
        }
        _state.value = _state.value.copy(calls = calls, activeCallId = activeCallId)
    }

    private fun ManagedCall.toManagedCallState(): ManagedCallState {
        val s = session.state.value
        // The role is the registry's authoritative view (lease ownership), NOT the
        // session's mutable `mediaRole`: a session-internal teardown resets the
        // latter to FOREGROUND, which would mislabel an ended/held call.
        val role = if (!ended && foregroundToken != null) CallMediaRole.FOREGROUND else CallMediaRole.HELD
        // mediaActivationState is a pure function of the call's OWN foreground
        // lifecycle (cross-platform contract, see MediaActivationState doc): a held
        // call sits at INACTIVE. needsPermission/failure for a held call is surfaced
        // ONLY on the orthogonal `activationError` field — NOT re-derived here — so a
        // host reads the same value across web/iOS/Android (web/iOS keep this a plain
        // passthrough of the session's own state).
        val activation = if (role == CallMediaRole.FOREGROUND) {
            session.mediaActivationStateForTest()
        } else {
            MediaActivationState.INACTIVE
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
