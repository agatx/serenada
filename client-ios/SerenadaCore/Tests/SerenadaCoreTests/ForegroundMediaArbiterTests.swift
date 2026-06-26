@testable import SerenadaCore
import XCTest

/// Phase 2 process-wide foreground media arbiter (contract §2). A fresh arbiter
/// per case keeps these isolated from the process singleton.
@MainActor
final class ForegroundMediaArbiterTests: XCTestCase {

    func testAcquireThenSecondAcquireFails() throws {
        let arbiter = ForegroundMediaArbiter()
        let token = try arbiter.acquireForeground(ownerId: "call-a")
        XCTAssertEqual(token.ownerId, "call-a")

        XCTAssertThrowsError(try arbiter.acquireForeground(ownerId: "call-b")) { error in
            XCTAssertEqual((error as? ForegroundLeaseUnavailable)?.reason, .leaseLive,
                           "A second acquire while a lease is live must fail leaseLive")
        }
    }

    func testReleaseThenReacquireSucceeds() throws {
        let arbiter = ForegroundMediaArbiter()
        let first = try arbiter.acquireForeground(ownerId: "call-a")
        arbiter.releaseLease(first)
        // Re-acquire succeeds and mints a DISTINCT token (ids never reused).
        let second = try arbiter.acquireForeground(ownerId: "call-b")
        XCTAssertNotEqual(first, second, "Re-acquire must mint a fresh token")
    }

    func testReleaseSameTokenTwiceIsIdempotent() throws {
        let arbiter = ForegroundMediaArbiter()
        let token = try arbiter.acquireForeground(ownerId: "call-a")
        arbiter.releaseLease(token)
        // A second release of the same token is a no-op (does not crash).
        arbiter.releaseLease(token)
        // The lease is free, so a new owner can acquire.
        XCTAssertNoThrow(try arbiter.acquireForeground(ownerId: "call-b"))
    }

    func testGenerationIsMonotonicAndSeparateFromToken() throws {
        let arbiter = ForegroundMediaArbiter()
        let g1 = arbiter.nextOperationGeneration()
        let g2 = arbiter.nextOperationGeneration()
        let g3 = arbiter.nextOperationGeneration()
        XCTAssertEqual([g1, g2, g3], [1, 2, 3], "Generation must be monotonic")
        // Acquiring a lease does not consume or perturb the generation counter.
        _ = try arbiter.acquireForeground(ownerId: "call-a")
        XCTAssertEqual(arbiter.nextOperationGeneration(), 4,
                       "Generation is independent of the lease token")
    }

    func testCrossModeConflictWhileOwnerHasLiveOwners() throws {
        let arbiter = ForegroundMediaArbiter()
        let registryRef = NSObject()
        try arbiter.claimMode(.registry, ownerRef: registryRef)
        XCTAssertEqual(arbiter.owningMode, .registry)

        // A direct acquirer cannot claim `direct` mode while a registry has a live
        // owner — even though no foreground lease is held yet.
        let directRef = NSObject()
        XCTAssertThrowsError(try arbiter.claimMode(.direct, ownerRef: directRef)) { error in
            XCTAssertEqual((error as? ForegroundLeaseUnavailable)?.reason, .modeConflict)
        }

        // After the registry releases its last owner, the mode clears and direct
        // may claim it.
        arbiter.releaseMode(ownerRef: registryRef)
        XCTAssertNil(arbiter.owningMode, "Mode clears when the owning side has no live owners")
        XCTAssertNoThrow(try arbiter.claimMode(.direct, ownerRef: directRef))
        XCTAssertEqual(arbiter.owningMode, .direct)
    }

    func testSameModeMultipleOwnersAllowed() throws {
        // Two direct sessions can coexist at the MODE level (the single-lease check
        // is what stops two foreground owners; mode only blocks cross-mode mixing).
        let arbiter = ForegroundMediaArbiter()
        let a = NSObject()
        let b = NSObject()
        try arbiter.claimMode(.direct, ownerRef: a)
        XCTAssertNoThrow(try arbiter.claimMode(.direct, ownerRef: b))
        arbiter.releaseMode(ownerRef: a)
        XCTAssertEqual(arbiter.owningMode, .direct, "Mode stays while another owner is live")
        arbiter.releaseMode(ownerRef: b)
        XCTAssertNil(arbiter.owningMode)
    }

    func testResetForTestsClearsLeaseAndMode() throws {
        let arbiter = ForegroundMediaArbiter()
        _ = try arbiter.acquireForeground(ownerId: "call-a")
        try arbiter.claimMode(.direct, ownerRef: NSObject())
        arbiter.resetForTests()
        XCTAssertNil(arbiter.owningMode)
        XCTAssertNil(arbiter.currentOwnerToken)
        XCTAssertNoThrow(try arbiter.acquireForeground(ownerId: "call-b"))
    }

    // MARK: - Q2: acquire blocked while a release is pending

    func testAcquireBlockedWhileReleasePending() throws {
        let arbiter = ForegroundMediaArbiter()
        let first = try arbiter.acquireForeground(ownerId: "call-a")
        // The registry begins draining the old owner: mark the release pending
        // BEFORE the release confirms. While pending, NO new lease is granted —
        // this is what makes an old-release failure safe (never two owners).
        arbiter.markReleasePending()

        // A new acquire must fail (the old owner's lease is still live and its
        // release is pending; either guard rejecting it preserves single-lease).
        XCTAssertThrowsError(try arbiter.acquireForeground(ownerId: "call-b")) { error in
            XCTAssertTrue(error is ForegroundLeaseUnavailable,
                          "A new acquire while a release is pending must fail")
        }

        // Confirming the current owner's release clears pending and frees the lease.
        arbiter.releaseLease(first)
        XCTAssertNoThrow(try arbiter.acquireForeground(ownerId: "call-b"),
                         "Once the pending release confirms, a new owner may acquire")
    }

    /// The `releasePending` guard in isolation: when the lease has been drained
    /// (currentToken nil) but a prior release was marked pending and never
    /// confirmed, a fresh acquire still fails `releasePending` (the "old-release
    /// failure" safety case — no two owners).
    func testAcquireFailsReleasePendingWhenNoLeaseButPendingUncleared() throws {
        let arbiter = ForegroundMediaArbiter()
        let first = try arbiter.acquireForeground(ownerId: "call-a")
        // Drain the lease (clears currentToken AND pending) ...
        arbiter.releaseLease(first)
        // ... then a NEW release-pending window opens without a live lease (models
        // a switch that marked pending after the old owner was already cleared).
        arbiter.markReleasePending()
        XCTAssertThrowsError(try arbiter.acquireForeground(ownerId: "call-b")) { error in
            XCTAssertEqual((error as? ForegroundLeaseUnavailable)?.reason, .releasePending,
                           "With no live lease but pending uncleared, acquire fails releasePending")
        }
    }

    // MARK: - Q3: releaseLease stale-token rejection + idempotency

    /// Q3: `releaseLease` is idempotent for the LAST-released token (re-releasing
    /// the same token after it was already released, with nothing new granted, is a
    /// safe no-op) and does not perturb a freshly-acquired different owner.
    /// (Releasing a FOREIGN token while a different owner is live is a programming
    /// error — `preconditionFailure` — and is intentionally not exercised here, the
    /// same crash-on-misuse contract the base `precondition` had.)
    func testReleaseLeaseIsIdempotentForLastReleasedToken() throws {
        let arbiter = ForegroundMediaArbiter()
        let owner = try arbiter.acquireForeground(ownerId: "call-a")
        arbiter.releaseLease(owner)
        XCTAssertNil(arbiter.currentOwnerToken)
        // Re-releasing the same (last-released) token while NOTHING new is granted
        // is a safe no-op (does not crash, lease stays free).
        arbiter.releaseLease(owner)
        XCTAssertNil(arbiter.currentOwnerToken)

        // A new owner can still acquire after the idempotent re-release, and
        // re-releasing the OLD token before this new acquire did not block it.
        let newOwner = try arbiter.acquireForeground(ownerId: "call-b")
        XCTAssertEqual(arbiter.currentOwnerToken, newOwner)
        // The new owner releases idempotently too.
        arbiter.releaseLease(newOwner)
        arbiter.releaseLease(newOwner)
        XCTAssertNil(arbiter.currentOwnerToken)
    }

    // MARK: - Q1: cross-mode acquire fails atomically inside acquireForeground

    func testCrossModeAcquireFailsAtomicallyInsideAcquire() throws {
        let arbiter = ForegroundMediaArbiter()
        // A registry owns the process (mode claimed, no lease yet).
        let registryRef = NSObject()
        try arbiter.claimMode(.registry, ownerRef: registryRef)

        // A direct acquire that ALSO carries `mode: .direct` must fail atomically:
        // the cross-mode conflict is detected and NO lease is minted (the contract
        // requires the mode check and the grant to be one indivisible step).
        XCTAssertThrowsError(
            try arbiter.acquireForeground(ownerId: "call-x", mode: .direct, ownerRef: NSObject())
        ) { error in
            XCTAssertEqual((error as? ForegroundLeaseUnavailable)?.reason, .modeConflict)
        }
        XCTAssertNil(arbiter.currentOwnerToken,
                     "A cross-mode acquire must not mint a lease (atomic failure)")
        XCTAssertEqual(arbiter.owningMode, .registry,
                       "A failed cross-mode acquire must not perturb the owning mode")

        // After the registry releases, a direct atomic acquire succeeds and claims
        // direct mode in the same step.
        arbiter.releaseMode(ownerRef: registryRef)
        let token = try arbiter.acquireForeground(ownerId: "call-x", mode: .direct, ownerRef: NSObject())
        XCTAssertEqual(arbiter.currentOwnerToken, token)
        XCTAssertEqual(arbiter.owningMode, .direct)
    }

    func testIsCurrentOwnerReflectsLiveLease() throws {
        let arbiter = ForegroundMediaArbiter()
        XCTAssertFalse(arbiter.isCurrentOwner(nil))
        let token = try arbiter.acquireForeground(ownerId: "call-a")
        XCTAssertTrue(arbiter.isCurrentOwner(token))
        arbiter.releaseLease(token)
        XCTAssertFalse(arbiter.isCurrentOwner(token),
                       "A released token is no longer the current owner")
    }
}
