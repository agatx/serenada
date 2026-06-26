import Foundation

/// Opaque owner token issued by ``ForegroundMediaArbiter`` to the holder of the
/// single process-wide foreground media lease. Two tokens are equal only when
/// they identify the same lease grant. The session uses it to fence stale async
/// activation callbacks; the registry uses it to release the lease.
public struct ForegroundOwnerToken: Equatable, Sendable {
    /// Process-unique identity of this lease grant (monotonic, never reused).
    fileprivate let id: Int
    /// The call/owner id this token was minted for (diagnostics only).
    public let ownerId: String

    fileprivate init(id: Int, ownerId: String) {
        self.id = id
        self.ownerId = ownerId
    }
}

/// Raised when a foreground lease cannot be acquired: a lease is already live, a
/// previous owner's release is still pending/failed, or the requested owning mode
/// conflicts with the mode currently in use (Core Invariants 1, 2, 6).
public struct ForegroundLeaseUnavailable: Error, Equatable, Sendable {
    /// Diagnostic reason for the failed acquisition.
    public enum Reason: String, Sendable {
        /// A lease is currently held by another owner.
        case leaseLive
        /// The previous owner's release has not yet been confirmed.
        case releasePending
        /// The other owning mode (registry vs direct) has live owners.
        case modeConflict
    }

    public let reason: Reason
    public init(reason: Reason) { self.reason = reason }
}

/// Which integration path owns the process-wide foreground media. Direct
/// single-call `SerenadaCore.join()` and the registry cannot be mixed while
/// either has live owners (Core Invariant 6).
public enum ForegroundOwningMode: String, Sendable {
    /// A `SerenadaCallRegistry` owns the process.
    case registry
    /// One or more direct `SerenadaCore.join()` sessions own the process.
    case direct
}

/// Process-wide arbiter granting at most one foreground media lease at a time.
///
/// Exactly one instance per process — `ForegroundMediaArbiter.shared`. Shared by
/// every `SerenadaCore` (direct single-call path) and every
/// `SerenadaCallRegistry`. OS audio session, mic, camera, and screen share are
/// process-global, so a per-core or per-registry arbiter would still let two
/// owners race (design "Add a Process-Wide Resource Arbiter").
///
/// The arbiter does NOT itself drive the audio coordinator or capture; it only
/// arbitrates the single lease + owning mode and vends operation generations for
/// fencing. The session+coordinator own the actual media transitions.
@MainActor
public final class ForegroundMediaArbiter {
    /// The single process-wide arbiter instance.
    public static let shared = ForegroundMediaArbiter()

    /// The live lease's token, or `nil` when no lease is held. A token is also
    /// retained here while a release is pending/failed (see ``releasePending``).
    private var currentToken: ForegroundOwnerToken?
    /// True between handing a token to a caller and that caller confirming the
    /// lease is released. While true, a new acquire fails (Core Invariant 2).
    /// In v1 the iOS release path is synchronous from the arbiter's point of
    /// view (the registry calls `releaseLease` only after the session confirms
    /// fully-held), so this is reset inside `releaseLease`; it exists as the
    /// explicit guard the contract requires.
    private var releasePending = false
    /// Monotonic id source for tokens (never reused, distinct from generation).
    private var nextTokenId = 1
    /// Monotonic operation-generation counter, bumped per activation attempt
    /// (including rollback). Separate from the token identity.
    private var operationGeneration = 0

    /// The owning mode claimed on first use, or `nil` when no side has live
    /// owners. Tracked separately from the lease: a registry holding only held
    /// calls (no foreground lease) still owns the process.
    private(set) var owningMode: ForegroundOwningMode?
    /// Set of live owner refs per mode. A direct join registers itself here on
    /// acquire and clears on release; the registry registers its managed-call
    /// presence (Phase 3). Mode clears when this drops to empty.
    private var liveOwnerRefs: Set<ObjectIdentifier> = []

    /// Public for the diagnostics/tests; the singleton is the only intended
    /// instance, but a fresh arbiter is useful for isolating unit tests.
    public init() {}

    // MARK: - Owning mode (Core Invariant 6)

    /// Claim the process owning mode for `ownerRef`. The first user wins; while
    /// the owning side has any live owner, claiming the other mode throws
    /// `ForegroundLeaseUnavailable(.modeConflict)`. Idempotent for an already
    /// registered ref in the same mode.
    func claimMode(_ mode: ForegroundOwningMode, ownerRef: AnyObject) throws {
        if let owningMode, owningMode != mode, !liveOwnerRefs.isEmpty {
            throw ForegroundLeaseUnavailable(reason: .modeConflict)
        }
        owningMode = mode
        liveOwnerRefs.insert(ObjectIdentifier(ownerRef))
    }

    /// Release `ownerRef`'s mode registration. When the owning side has no live
    /// owners left, the mode clears and the other mode may claim it.
    func releaseMode(ownerRef: AnyObject) {
        liveOwnerRefs.remove(ObjectIdentifier(ownerRef))
        if liveOwnerRefs.isEmpty {
            owningMode = nil
        }
    }

    // MARK: - Foreground lease

    /// Grant the single foreground lease to `ownerId`, returning a unique owner
    /// token. Throws `ForegroundLeaseUnavailable` if a lease is already live, a
    /// prior release is still pending/failed, or the caller is in a conflicting
    /// owning mode (Core Invariants 1, 2, 6).
    @discardableResult
    func acquireForeground(ownerId: String) throws -> ForegroundOwnerToken {
        if releasePending {
            throw ForegroundLeaseUnavailable(reason: .releasePending)
        }
        if currentToken != nil {
            throw ForegroundLeaseUnavailable(reason: .leaseLive)
        }
        let token = ForegroundOwnerToken(id: nextTokenId, ownerId: ownerId)
        nextTokenId += 1
        currentToken = token
        return token
    }

    /// Release the lease held under `token`. Only the current owner's token is
    /// accepted; a mismatched token is a programming error. Releasing the same
    /// token twice is idempotent (a no-op after the first release).
    func releaseLease(_ token: ForegroundOwnerToken) {
        guard let current = currentToken else {
            // Idempotent: already released. Tolerate a duplicate release of the
            // last-known token; reject an unrelated token.
            return
        }
        precondition(current == token, "releaseLease called with a non-owner token")
        currentToken = nil
        releasePending = false
    }

    /// Vend the next monotonic operation generation. Bumped for every activation
    /// attempt (including rollback) and passed into `activateForeground` so a
    /// superseded switch's late async callback can be discarded. NOT the lease
    /// identity.
    func nextOperationGeneration() -> Int {
        operationGeneration += 1
        return operationGeneration
    }

    /// The token of the live lease, or `nil`. Used by the session to fence late
    /// activation callbacks against the CURRENT owner (the second fence beyond
    /// the operation generation).
    var currentOwnerToken: ForegroundOwnerToken? { currentToken }

    // MARK: - Test support

    /// Reset all arbiter state. **Test-only** — the process singleton would
    /// otherwise leak a live lease/mode across XCTest cases and make a later
    /// `acquireForeground` fail. Call from test setUp/tearDown.
    func resetForTests() {
        currentToken = nil
        releasePending = false
        nextTokenId = 1
        operationGeneration = 0
        owningMode = nil
        liveOwnerRefs.removeAll()
    }
}
