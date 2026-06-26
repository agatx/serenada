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
        /// `releaseLease` was called with a token that is neither the current owner
        /// nor the last-released token (a genuine foreign/stale release).
        case foreignToken
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
    /// The most recently released token, retained so an idempotent re-release of
    /// the SAME token (after it was already released, nothing new granted) is a
    /// safe no-op rather than a mismatch error (matches web/android).
    private var lastReleasedToken: ForegroundOwnerToken?
    /// True between handing a token to a caller and that caller confirming the
    /// lease is released. While true (or after a failed release), NO new lease is
    /// granted — this is what makes an old-release failure safe (never two owners;
    /// Core Invariant 2). The Phase 2 single-call path releases synchronously on
    /// teardown and does not set this; the Phase 3 registry switch uses
    /// ``markReleasePending()``. Cleared only by a successful current-owner
    /// ``releaseLease(_:)``.
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
        try assertModeCompatible(mode)
        owningMode = mode
        liveOwnerRefs.insert(ObjectIdentifier(ownerRef))
    }

    /// Throw `ForegroundLeaseUnavailable(.modeConflict)` when `mode` differs from
    /// the mode currently owning the process and that mode still has live owners.
    /// Folded into `acquireForeground`/`claimMode` so the check has no side effects
    /// of its own (a cross-mode acquire fails before any state mutates).
    private func assertModeCompatible(_ mode: ForegroundOwningMode) throws {
        if let owningMode, owningMode != mode, !liveOwnerRefs.isEmpty {
            throw ForegroundLeaseUnavailable(reason: .modeConflict)
        }
    }

    /// Stable per-ownerId mode-ref when `acquireForeground` is not handed an
    /// explicit owner object, so a single owner that acquires twice does not leave
    /// a dangling distinct ref behind (mirrors android `modeRefFor`).
    private var modeRefs: [String: AnyObject] = [:]

    private func modeRef(for ownerId: String) -> AnyObject {
        if let existing = modeRefs[ownerId] { return existing }
        let ref = NSObject()
        modeRefs[ownerId] = ref
        return ref
    }

    /// Release `ownerRef`'s mode registration. When the owning side has no live
    /// owners left, the mode clears and the other mode may claim it.
    func releaseMode(ownerRef: AnyObject) {
        releaseMode(ownerIdentity: ObjectIdentifier(ownerRef))
    }

    /// Identity-keyed `releaseMode` variant. Used from a session's `deinit`, which
    /// (being nonisolated and operating on a deallocating object) must NOT pass the
    /// object itself into the main-actor hop; it passes the pre-computed
    /// `ObjectIdentifier` value instead.
    func releaseMode(ownerIdentity: ObjectIdentifier) {
        liveOwnerRefs.remove(ownerIdentity)
        if liveOwnerRefs.isEmpty {
            owningMode = nil
        }
    }

    /// Release a direct lease + mode claim from a session's `deinit`. The session
    /// is being deallocated, so it cannot capture `self` in the main-actor hop;
    /// instead it passes the lease `token` and the pre-computed owner
    /// `ObjectIdentifier`. Releases the lease only when this session is STILL the
    /// current owner, and tolerates a foreign-token throw (recoverable). The mode
    /// claim is always dropped (no-op if never claimed).
    func releaseDirectLeaseFromDeinit(token: ForegroundOwnerToken, ownerIdentity: ObjectIdentifier) {
        if currentToken == token {
            try? releaseLease(token)
        }
        releaseMode(ownerIdentity: ownerIdentity)
    }

    // MARK: - Foreground lease

    /// Grant the single foreground lease to `ownerId`, returning a unique owner
    /// token. The optional `mode` claims/asserts the owning mode in the SAME
    /// atomic step (the common path: a direct join acquires `direct`, the Phase 3
    /// registry's foreground activation acquires `registry`), so cross-mode
    /// enforcement and the lease grant cannot interleave. Throws
    /// `ForegroundLeaseUnavailable` if a lease is already live, a prior release is
    /// still pending/failed, or the requested mode conflicts with the owning mode
    /// (Core Invariants 1, 2, 6).
    @discardableResult
    func acquireForeground(
        ownerId: String,
        mode: ForegroundOwningMode? = nil,
        ownerRef: AnyObject? = nil
    ) throws -> ForegroundOwnerToken {
        // Assert mode compatibility BEFORE granting so a cross-mode acquire fails
        // without side effects.
        if let mode {
            try assertModeCompatible(mode)
        }
        if currentToken != nil {
            throw ForegroundLeaseUnavailable(reason: .leaseLive)
        }
        if releasePending {
            throw ForegroundLeaseUnavailable(reason: .releasePending)
        }
        if let mode {
            try claimMode(mode, ownerRef: ownerRef ?? modeRef(for: ownerId))
        }
        let token = ForegroundOwnerToken(id: nextTokenId, ownerId: ownerId)
        nextTokenId += 1
        currentToken = token
        return token
    }

    /// Release the lease held under `token`. Only the current owner's token is
    /// accepted; releasing a genuine FOREIGN token (one that is neither the current
    /// owner nor the last-released token) throws `ForegroundLeaseUnavailable` —
    /// a recoverable, catchable failure rather than a crash, so a stale/foreign
    /// release on a teardown path can be ignored instead of bringing the process
    /// down (parity with web/android, which throw). The idempotent case where
    /// `token` is the most recently released token and nothing new has been granted
    /// is a safe no-op. Lease release is registry-owned (the session never calls
    /// this on the registry path); the Phase 2 single-call path's own teardown calls it.
    func releaseLease(_ token: ForegroundOwnerToken) throws {
        if currentToken == token {
            currentToken = nil
            releasePending = false
            lastReleasedToken = token
            return
        }
        // Idempotent re-release of the same token after it was already released.
        if lastReleasedToken == token, currentToken == nil { return }
        throw ForegroundLeaseUnavailable(reason: .foreignToken)
    }

    /// Mark that an owner's release has begun but not yet confirmed. Set by the
    /// Phase 3 registry switch before it drains the old session; cleared by
    /// `releaseLease` on success. While set, a new acquire fails fast so two
    /// owners can never exist (Core Invariant 2).
    func markReleasePending() {
        releasePending = true
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

    /// Whether `token` is the live lease owner. Used by the session's late-callback
    /// fence: a resume completion is honored only if the arbiter STILL owns the
    /// lease under the expected token (parity with web `isCurrentOwner` / android),
    /// since after a rollback the session's own field can equal a stale token.
    func isCurrentOwner(_ token: ForegroundOwnerToken?) -> Bool {
        guard let token else { return false }
        return currentToken == token
    }

    // MARK: - Test support

    /// Reset all arbiter state. **Test-only** — the process singleton would
    /// otherwise leak a live lease/mode across XCTest cases and make a later
    /// `acquireForeground` fail. Call from test setUp/tearDown.
    func resetForTests() {
        currentToken = nil
        lastReleasedToken = nil
        releasePending = false
        nextTokenId = 1
        operationGeneration = 0
        owningMode = nil
        liveOwnerRefs.removeAll()
        modeRefs.removeAll()
    }
}
