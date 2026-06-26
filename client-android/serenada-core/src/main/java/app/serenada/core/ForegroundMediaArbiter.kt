package app.serenada.core

import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owning mode of the process-wide foreground media arbiter (Core Invariant 6).
 *
 * A host integrates through direct single-call APIs OR the registry, not both at
 * once. The first user claims a mode; while that side has live sessions/calls the
 * other mode's acquire fails with [ForegroundLeaseUnavailable].
 */
enum class ForegroundArbiterMode {
    /** Claimed by the (Phase 3) registry; holds the process while it has any managed call. */
    REGISTRY,

    /** Claimed by a direct [SerenadaCore.join]; holds the process while a direct session is live. */
    DIRECT,
}

/**
 * Opaque foreground-lease owner token. Identity is the only thing that matters:
 * two tokens are equal iff they are the same object. Minted only by
 * [ForegroundMediaArbiter.acquireForeground]; a direct caller cannot forge one,
 * so a session cannot move itself to foreground without going through the arbiter.
 *
 * @property ownerId the call id (or session ref) the lease was acquired for; for diagnostics only.
 */
class ForegroundOwnerToken internal constructor(val ownerId: String)

/**
 * Thrown when the single foreground media lease cannot be granted: a lease is
 * already live, a prior owner's release is pending/failed, or the requested
 * owning mode conflicts with the mode currently owning the process.
 */
class ForegroundLeaseUnavailable(message: String) : Exception(message)

/**
 * Process-wide foreground media arbiter (multi-call session, contract §2 /
 * design "Process-Wide Resource Arbiter"). EXACTLY ONE instance per process:
 * the [ForegroundMediaArbiter] object below, shared by every [SerenadaCore] and
 * (Phase 3) every `SerenadaCallRegistry`. OS-global resources (mic, camera,
 * screen share, audio focus, `MODE_IN_COMMUNICATION`) are process-global, so a
 * per-core / per-registry arbiter would still let two owners race; this is the
 * single point that grants the one foreground lease.
 *
 * Concurrency: all callers operate on the main thread ([Looper.getMainLooper]),
 * the same thread the session's `Dispatchers.Main.immediate` scope and the
 * audio coordinator run on. State is therefore single-threaded and needs no
 * locks; [acquireForeground]/[releaseLease]/[claimMode]/[releaseMode] assert the
 * main thread. The async release-old-before-acquire-new sequencing lives in the
 * caller (Phase 2 single-call teardown; Phase 3 registry switch), not here; this
 * object is the synchronous bookkeeper of the lease + mode + generation.
 */
object ForegroundMediaArbiter {
    /** The live lease token, or null when no call owns foreground media. */
    private var currentToken: ForegroundOwnerToken? = null

    /**
     * The most recently released token, retained so an idempotent re-release of
     * the SAME token (after it was already released, nothing new granted) is a
     * safe no-op rather than a mismatch error.
     */
    private var lastReleasedToken: ForegroundOwnerToken? = null

    /**
     * True between starting an owner's release and confirming it fully drained.
     * While set (or after a failed release) NO new lease is granted — this is
     * what makes an old-release failure safe (never two owners; contract §2 rule
     * 2). Phase 2's single-call path releases synchronously on teardown and does
     * not set this; the Phase 3 registry switch uses [markReleasePending].
     */
    private var releasePending = false

    /** Monotonic operation generation; bumped per call. Separate from the token. */
    private var operationGeneration: Long = 0

    // --- Owning mode (Core Invariant 6) ---
    /** The mode currently owning the process, or null when no side has live calls. */
    private var mode: ForegroundArbiterMode? = null

    /**
     * Distinct owner references that have claimed the current mode. The mode
     * clears only when the last claimant releases. A `DIRECT` join claims with
     * its own session ref; the registry claims once with itself. Identity-keyed.
     */
    private val modeOwners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    /**
     * Acquire the single foreground media lease for [ownerId]. The optional
     * [mode] claims/asserts the owning mode in the same step (common path: a
     * direct join acquires [ForegroundArbiterMode.DIRECT]; the Phase 3 registry's
     * foreground activation acquires [ForegroundArbiterMode.REGISTRY]). Throws
     * [ForegroundLeaseUnavailable] if a lease is already live, a prior release is
     * pending/failed, or the requested mode conflicts with the owning mode.
     */
    fun acquireForeground(
        ownerId: String,
        mode: ForegroundArbiterMode? = null,
        modeOwnerRef: Any? = null,
    ): ForegroundOwnerToken {
        assertMainThread()
        // Assert mode compatibility BEFORE granting so a cross-mode acquire fails
        // without side effects.
        if (mode != null) assertModeCompatible(mode)
        currentToken?.let { token ->
            throw ForegroundLeaseUnavailable(
                "Foreground lease already held by ${token.ownerId}; $ownerId cannot acquire",
            )
        }
        if (releasePending) {
            throw ForegroundLeaseUnavailable(
                "A prior foreground lease release is pending or failed; $ownerId cannot acquire",
            )
        }
        if (mode != null) claimMode(mode, modeOwnerRef ?: modeRefFor(ownerId))
        val token = ForegroundOwnerToken(ownerId)
        currentToken = token
        return token
    }

    /**
     * Release the lease held by [token]. Only the current owner's token is
     * accepted; a mismatched (non-current) token throws — EXCEPT the idempotent
     * case where [token] is the most recently released token and nothing new has
     * been granted, which is a safe no-op. Lease release is registry-owned (the
     * session never calls this); on the Phase 2 single-call path the session's
     * own teardown calls it.
     */
    fun releaseLease(token: ForegroundOwnerToken) {
        assertMainThread()
        if (currentToken === token) {
            currentToken = null
            releasePending = false
            lastReleasedToken = token
            return
        }
        // Idempotent re-release of the same token after it was already released.
        if (lastReleasedToken === token && currentToken == null) return
        throw ForegroundLeaseUnavailable(
            "releaseLease called with a token that is not the current owner (${token.ownerId})",
        )
    }

    /**
     * Mark that an owner's release has begun but not yet confirmed. Set by the
     * Phase 3 registry switch before it drains the old session; cleared by
     * [releaseLease] on success. While set, a new acquire fails fast so two
     * owners can never exist (contract §2 rule 2).
     */
    fun markReleasePending() {
        assertMainThread()
        releasePending = true
    }

    /** A monotonic operation generation, bumped every call. Not the lease identity. */
    fun nextOperationGeneration(): Long {
        assertMainThread()
        operationGeneration += 1
        return operationGeneration
    }

    /** Whether [token] is the live lease owner (used by the session's fence). */
    fun isCurrentOwner(token: ForegroundOwnerToken?): Boolean {
        return token != null && currentToken === token
    }

    /**
     * Claim the owning [mode] for [ownerRef] (Core Invariant 6). First user wins;
     * subsequent claims of the SAME mode just add the ref. A claim of the OTHER
     * mode while the current mode still has owners throws
     * [ForegroundLeaseUnavailable]. Folded into [acquireForeground]; exposed
     * separately so the Phase 3 registry can claim `REGISTRY` mode for held-only
     * calls (which take no lease).
     */
    fun claimMode(mode: ForegroundArbiterMode, ownerRef: Any) {
        assertMainThread()
        assertModeCompatible(mode)
        this.mode = mode
        modeOwners.add(ownerRef)
    }

    /**
     * Release [ownerRef]'s mode claim. The mode clears only when the last
     * claimant releases (the owning side has no live sessions/calls), after which
     * the other mode may claim it. A ref that never claimed is ignored.
     */
    fun releaseMode(ownerRef: Any) {
        assertMainThread()
        modeOwners.remove(ownerRef)
        if (modeOwners.isEmpty()) mode = null
    }

    /** The owning mode, or null when no side has live calls (diagnostic). */
    val currentMode: ForegroundArbiterMode?
        get() = mode

    private fun assertModeCompatible(mode: ForegroundArbiterMode) {
        val current = this.mode
        if (current != null && current != mode && modeOwners.isNotEmpty()) {
            throw ForegroundLeaseUnavailable(
                "Process is owned in '$current' mode; cannot acquire foreground media in '$mode' mode " +
                    "(direct single-call and registry-managed use cannot mix while either has live calls)",
            )
        }
    }

    // Stable per-ownerId mode-ref when the caller does not supply its own object,
    // so a single owner that claims a mode twice does not leave a dangling ref.
    private val modeRefs = HashMap<String, Any>()

    private fun modeRefFor(ownerId: String): Any = modeRefs.getOrPut(ownerId) { Any() }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ForegroundMediaArbiter must be used on the main thread"
        }
    }

    /**
     * Test-only: reset the process-global state so a lease/mode held by one test
     * cannot make a later sequential test fail to acquire. NOT part of the public
     * SDK surface — wire into the test harness setup/teardown
     * ([app.serenada.core.fakes.TestSessionFactory]). No-op for production code.
     */
    internal fun resetForTests() {
        currentToken = null
        lastReleasedToken = null
        releasePending = false
        operationGeneration = 0
        mode = null
        modeOwners.clear()
        modeRefs.clear()
    }
}
