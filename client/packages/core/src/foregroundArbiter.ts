import {
    ForegroundLeaseUnavailable,
    type ForegroundArbiterMode,
    type ForegroundOwnerToken,
} from './types.js';

/**
 * Process-wide foreground media arbiter (multi-call session, contract §2 /
 * design "Process-Wide Resource Arbiter"). Exactly ONE instance per JS execution
 * context: a module-level singleton shared by every {@link SerenadaCore} and
 * (Phase 3) every `SerenadaCallRegistry`. OS-global resources (mic, camera,
 * screen share, audio routing) are process-global, so a per-core/per-registry
 * arbiter would still let two owners race; this is the single point that grants
 * the one foreground lease.
 *
 * On web there is no OS audio session, but the arbiter still mints a capture
 * lease so only one session holds `getUserMedia()` tracks (the audible
 * foreground call) at a time, and it enforces the owning-mode rule (Core
 * Invariant 6) that direct single-call and registry-managed use cannot mix.
 *
 * Concurrency note: web is single-threaded, so the lease/mode bookkeeping needs
 * no locks. The async sequencing of release-old-before-acquire-new lives in the
 * caller (the registry switch), not here; this class is the synchronous bookkeeper.
 */
class ForegroundMediaArbiter {
    /** The live lease token, or `null` when no call owns foreground media. */
    private currentToken: ForegroundOwnerToken | null = null;
    /**
     * True between starting an owner's release and confirming it fully drained.
     * While set (or after a failed release), NO new lease is granted — this is
     * what makes an old-release failure safe (never two owners; contract §2 rule 2).
     */
    private releasePending = false;
    /** Monotonic operation generation; bumped per activation attempt (incl. rollback). */
    private operationGeneration = 0;

    // --- Owning mode (Core Invariant 6) ---
    /** The mode currently owning the process, or `null` when no side has live calls. */
    private mode: ForegroundArbiterMode | null = null;
    /**
     * Distinct owner references that have claimed the current mode. The mode
     * clears only when the last claimant releases. A `direct` join claims with
     * its own session ref; the registry claims once with itself.
     */
    private readonly modeOwners = new Set<object>();

    /**
     * Acquire the single foreground media lease for `ownerId`. The optional
     * `mode` claims/asserts the owning mode in the same step (the common path:
     * a direct join acquires `direct`, the registry's foreground activation
     * acquires `registry`). Throws {@link ForegroundLeaseUnavailable} if a lease
     * is already live, a prior release is pending/failed, or the requested mode
     * conflicts with the mode currently owning the process.
     */
    acquireForeground(ownerId: string, mode?: ForegroundArbiterMode, modeOwnerRef?: object): ForegroundOwnerToken {
        if (mode) {
            // Assert mode compatibility BEFORE granting so a cross-mode acquire
            // fails without side effects. claimMode itself throws on conflict.
            this.assertModeCompatible(mode);
        }
        if (this.currentToken !== null) {
            throw new ForegroundLeaseUnavailable(
                `Foreground lease already held by ${this.currentToken.ownerId}; ${ownerId} cannot acquire`,
            );
        }
        if (this.releasePending) {
            throw new ForegroundLeaseUnavailable(
                `A prior foreground lease release is pending or failed; ${ownerId} cannot acquire`,
            );
        }
        if (mode) {
            this.claimMode(mode, modeOwnerRef ?? mkModeRef(ownerId));
        }
        const token: ForegroundOwnerToken = {
            ownerId,
            // The brand is type-only; a runtime symbol satisfies the unique-symbol
            // shape without being readable/forgeable in practice.
            __foregroundOwnerToken: BRAND as unknown as ForegroundOwnerToken['__foregroundOwnerToken'],
        };
        this.currentToken = token;
        return token;
    }

    /**
     * Release the lease held by `token`. Only the current owner's token is
     * accepted; a mismatched (non-current) token throws — EXCEPT the idempotent
     * case where `token` is the most recently released token and nothing new has
     * been granted, which is a safe no-op. The registry calls this AFTER the
     * session confirms it is fully held (lease release is registry-owned).
     */
    releaseLease(token: ForegroundOwnerToken): void {
        if (this.currentToken === token) {
            this.currentToken = null;
            this.releasePending = false;
            this.lastReleasedToken = token;
            return;
        }
        // Idempotent re-release of the same token after it was already released.
        if (this.lastReleasedToken === token && this.currentToken === null) {
            return;
        }
        throw new ForegroundLeaseUnavailable(
            `releaseLease called with a token that is not the current owner (${token.ownerId})`,
        );
    }
    private lastReleasedToken: ForegroundOwnerToken | null = null;

    /**
     * Mark that an owner's release has begun but not yet confirmed. Set by the
     * registry switch before it drains the old session; cleared by
     * {@link releaseLease} on success. While set, a new acquire fails fast so two
     * owners can never exist (contract §2 rule 2). Phase 2's single-call path
     * does not use this (it releases synchronously on teardown).
     */
    markReleasePending(): void {
        this.releasePending = true;
    }

    /** A monotonic operation generation, bumped every call. Not the lease identity. */
    nextOperationGeneration(): number {
        this.operationGeneration += 1;
        return this.operationGeneration;
    }

    /** Whether `token` is the live lease owner (used by the session's fence). */
    isCurrentOwner(token: ForegroundOwnerToken | null): boolean {
        return token !== null && this.currentToken === token;
    }

    /**
     * Claim the owning mode for `ownerRef` (Core Invariant 6). First user wins;
     * subsequent claims of the SAME mode just add the ref. A claim of the OTHER
     * mode while the current mode still has owners throws
     * {@link ForegroundLeaseUnavailable}. Safe to fold into
     * {@link acquireForeground}; exposed separately so the registry can claim
     * `registry` mode for held-only calls (which take no lease).
     */
    claimMode(mode: ForegroundArbiterMode, ownerRef: object): void {
        this.assertModeCompatible(mode);
        this.mode = mode;
        this.modeOwners.add(ownerRef);
    }

    /**
     * Release `ownerRef`'s mode claim. The mode clears only when the last
     * claimant releases (the owning side has no live sessions/calls), after which
     * the other mode may claim it. A ref that never claimed is ignored.
     */
    releaseMode(ownerRef: object): void {
        this.modeOwners.delete(ownerRef);
        if (this.modeOwners.size === 0) {
            this.mode = null;
        }
    }

    /** The owning mode, or `null` when no side has live calls (diagnostic). */
    get currentMode(): ForegroundArbiterMode | null {
        return this.mode;
    }

    private assertModeCompatible(mode: ForegroundArbiterMode): void {
        if (this.mode !== null && this.mode !== mode && this.modeOwners.size > 0) {
            throw new ForegroundLeaseUnavailable(
                `Process is owned in '${this.mode}' mode; cannot acquire foreground media in '${mode}' mode ` +
                `(direct single-call and registry-managed use cannot mix while either has live calls)`,
            );
        }
    }

    /**
     * Test-only: reset the singleton so the process-global state does not leak
     * across vitest tests. NOT part of the public SDK surface. Wired into a
     * global `afterEach` (see the vitest setup file).
     */
    __resetForTests(): void {
        this.currentToken = null;
        this.lastReleasedToken = null;
        this.releasePending = false;
        this.operationGeneration = 0;
        this.mode = null;
        this.modeOwners.clear();
    }
}

/** Module-private brand symbol backing the opaque {@link ForegroundOwnerToken}. */
const BRAND: unique symbol = Symbol('foregroundOwnerToken');

/** Stable per-ownerId mode-ref when the caller does not supply its own object. */
const modeRefs = new Map<string, object>();
function mkModeRef(ownerId: string): object {
    let ref = modeRefs.get(ownerId);
    if (!ref) {
        ref = { ownerId };
        modeRefs.set(ownerId, ref);
    }
    return ref;
}

/**
 * The process singleton. One per JS execution context, shared by all
 * {@link SerenadaCore} / registry instances.
 */
export const foregroundArbiter = new ForegroundMediaArbiter();

/** Public type alias for the singleton, for call sites that need it. */
export type { ForegroundMediaArbiter };

/**
 * Test-only reset of the process-singleton arbiter. Call from a global
 * `afterEach` so a lease/mode held by one test cannot make a later test fail
 * with {@link ForegroundLeaseUnavailable}. No-op for production code.
 */
export function __resetForegroundArbiterForTests(): void {
    foregroundArbiter.__resetForTests();
    modeRefs.clear();
}
