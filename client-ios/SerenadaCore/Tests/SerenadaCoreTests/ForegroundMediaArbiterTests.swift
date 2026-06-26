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
}
