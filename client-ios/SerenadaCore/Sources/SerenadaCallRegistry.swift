import Combine
import Foundation

// MARK: - Identity

/// Registry-generated, process-stable identity for a managed call. Distinct from
/// the per-call server-issued `cid` and from the canonical `roomId` (the dedup
/// key). One `CallId` per managed call for its whole life (contract §7).
public typealias CallId = String

/// What the host wants to join: a full room URL or a bare room id, plus optional
/// display/identity hints. The registry canonicalizes the room token from this
/// for dedup, and needs a URL to construct the session (host-agnostic — both
/// `serenada.app` and `serenada-app.ru` collapse to one token).
public struct RoomRef: Equatable, Sendable {
    /// Full room URL when the host has one (deep link / created room).
    public let url: URL?
    /// Bare room id when the host only has the token.
    public let roomId: String?
    /// Optional display name for the local participant in this call.
    public let displayName: String?
    /// Optional host-supplied stable identity (distinct from the per-call cid).
    public let peerId: String?

    public init(url: URL, displayName: String? = nil, peerId: String? = nil) {
        self.url = url
        self.roomId = nil
        self.displayName = displayName
        self.peerId = peerId
    }

    public init(roomId: String, displayName: String? = nil, peerId: String? = nil) {
        self.url = nil
        self.roomId = roomId
        self.displayName = displayName
        self.peerId = peerId
    }
}

// MARK: - Per-call error

/// A per-call activation/release/join failure surfaced on the managed call
/// (contract §11). Registry-level `lastError` is not enough once multiple calls
/// exist, so every failed call carries its own.
public enum CallActivationError: Error, Equatable, Sendable {
    /// The call's desired media needs a mic/camera grant that is not granted.
    /// The host should prompt, then `switchToCall(id:)`.
    case needsPermission([MediaCapability])
    /// Foreground activation failed or timed out (audio session / media).
    case activationFailed(String)
    /// Draining the outgoing call's foreground resources timed out, so the
    /// switch aborted and this call kept the lease (contract §7, Invariant 1).
    case releaseTimedOut
    /// The held room join failed or timed out.
    case joinFailed(String)
    /// The underlying lease could not be acquired (mode conflict / lease live).
    case leaseUnavailable
}

/// Registry-level error surfaced on `lastError` (contract §11) for ops with no
/// natural per-call home (e.g. an op targeting an unknown call id).
public enum CallRegistryError: Error, Equatable, Sendable {
    /// The requested call id is not in the registry.
    case unknownCall(CallId)
    /// A registry-managed call failed; the per-call `activationError` carries the
    /// detail. Mirrored to `lastError` so a registry-only observer still sees it.
    case callFailed(CallId, CallActivationError)
}

// MARK: - Result types

/// Outcome of `joinHeld` (contract §7).
public enum JoinResult: Equatable, Sendable {
    /// The call joined held (no foreground taken). Carries the new `CallId`.
    case joined(CallId)
    /// The join failed. Carries the created `CallId` when one was registered
    /// before the failure (so the host can inspect/dismiss it), else `nil`.
    case failed(CallId?, CallActivationError)
}

/// Outcome of `switchToCall` (contract §7).
public enum SwitchResult: Equatable, Sendable {
    /// The target is now the active foreground call.
    case active
    /// The target needs a mic/camera grant first; the old call is UNCHANGED
    /// (still foreground). The host prompts, then retries `switchToCall(id:)`.
    case needsPermission
    /// The switch failed; the old call's disposition is per contract §7 (kept
    /// foreground on old-release failure; rolled back on activation failure).
    case failed(CallActivationError)
}

/// Outcome of `joinAndSwitch` (contract §7). `needsPermission` MUST carry the
/// `CallId` (the held call already exists; the host prompts then switches to it).
public enum JoinAndSwitchResult: Equatable, Sendable {
    /// Joined and switched: the new call is active foreground.
    case active(CallId)
    /// Joined held, but foregrounding needs a permission grant first. The held
    /// call exists under this `CallId`; the old call (if any) is untouched.
    case needsPermission(CallId)
    /// The join or switch failed. Carries the created `CallId` when one was
    /// registered before the failure, else `nil`.
    case failed(CallId?, CallActivationError)
}

// MARK: - Published per-call state

/// Published, value-type snapshot of one managed call (contract §11). The host
/// renders the active call via `registry.activeCall?.session`; this aggregate
/// drives switcher chips, per-call leave/end, and "on hold" presentation.
public struct ManagedCallState: Equatable, Sendable {
    public let id: CallId
    /// Canonical room token (dedup key).
    public let roomId: String
    /// Full room URL when known.
    public let roomUrl: URL?
    /// Membership phase (one of the three orthogonal axes — NOT collapsed with
    /// `mediaRole`/`mediaActivationState`).
    public let membershipPhase: SerenadaCallPhase
    public let mediaRole: CallMediaRole
    public let mediaActivationState: MediaActivationState
    /// User intent, preserved across hold (NOT what peers observe now).
    public let desiredAudioEnabled: Bool
    public let desiredVideoMode: LocalCameraMode?
    /// What peers observe right now (always false while held).
    public let actualAudioPublished: Bool
    public let actualVideoPublished: Bool
    public let participantCount: Int
    public let localCid: String?
    /// Convenience flag mirroring `mediaRole == .held`.
    public let held: Bool
    public let displayName: String?
    /// Per-call failure (failed activation/release/join, or needed permission).
    public let activationError: CallActivationError?
    /// Aggregate quality summary, populated after the call ends.
    public let qualitySummary: CallQualitySummary?

    public init(
        id: CallId,
        roomId: String,
        roomUrl: URL?,
        membershipPhase: SerenadaCallPhase,
        mediaRole: CallMediaRole,
        mediaActivationState: MediaActivationState,
        desiredAudioEnabled: Bool,
        desiredVideoMode: LocalCameraMode?,
        actualAudioPublished: Bool,
        actualVideoPublished: Bool,
        participantCount: Int,
        localCid: String?,
        held: Bool,
        displayName: String?,
        activationError: CallActivationError?,
        qualitySummary: CallQualitySummary?
    ) {
        self.id = id
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.membershipPhase = membershipPhase
        self.mediaRole = mediaRole
        self.mediaActivationState = mediaActivationState
        self.desiredAudioEnabled = desiredAudioEnabled
        self.desiredVideoMode = desiredVideoMode
        self.actualAudioPublished = actualAudioPublished
        self.actualVideoPublished = actualVideoPublished
        self.participantCount = participantCount
        self.localCid = localCid
        self.held = held
        self.displayName = displayName
        self.activationError = activationError
        self.qualitySummary = qualitySummary
    }
}

// MARK: - Registry ↔ session seam

/// The exact session surface the registry drives. `SerenadaSession` conforms; a
/// test stub conforms to exercise paths the real (synchronous-release) iOS
/// session cannot reach (e.g. a release that never confirms fully-held — the
/// old-release-timeout invariant, contract §7). Mirrors the codebase's
/// `SessionMediaEngine` / `SessionAudioController` DI-seam convention.
@MainActor
protocol RegistryManagedSession: AnyObject {
    var roomId: String { get }
    var roomUrl: URL? { get }
    var mediaRole: CallMediaRole { get }
    var mediaActivationState: MediaActivationState { get }
    var registryMembershipPhase: SerenadaCallPhase { get }
    var registryErrorDescription: String? { get }
    var registryLocalCid: String? { get }
    var registryParticipantCount: Int { get }
    var registryDesiredAudioEnabled: Bool { get }
    var registryDesiredVideoMode: LocalCameraMode? { get }
    var registryActualAudioPublished: Bool { get }
    var registryActualVideoPublished: Bool { get }
    var registryQualitySummary: CallQualitySummary? { get }

    func preflightForeground() -> ForegroundPreflight
    func missingDesiredForegroundPermissions() -> [MediaCapability]
    func activateForeground(_ token: ForegroundOwnerToken, generation: Int) throws
    func releaseForeground(_ token: ForegroundOwnerToken)
    /// Await the OLD session's audio teardown so the registry can sequence
    /// "release old fully before acquiring new" (contract §6).
    func awaitForegroundReleaseSettled() async
    func abortForegroundActivation(_ token: ForegroundOwnerToken)
    func registryLeave()
    func registryEnd()
}

extension SerenadaSession: RegistryManagedSession {
    var registryMembershipPhase: SerenadaCallPhase { state.phase }
    var registryErrorDescription: String? { state.error.map { "\($0)" } }
    var registryLocalCid: String? { state.localParticipant.cid }
    var registryParticipantCount: Int { state.remoteParticipants.count + 1 }
    var registryDesiredAudioEnabled: Bool { desiredAudioEnabledForRegistry }
    var registryDesiredVideoMode: LocalCameraMode? { desiredVideoModeForRegistry }
    var registryActualAudioPublished: Bool { actualAudioPublished }
    var registryActualVideoPublished: Bool { actualVideoPublished }
    var registryQualitySummary: CallQualitySummary? { qualitySummary }
    func registryLeave() { leave() }
    func registryEnd() { end() }
}

// MARK: - Registry-internal managed call

/// Internal mutable record the registry owns per call. Carries the live session,
/// its registry-issued foreground lease token (when active), and the bookkeeping
/// projected into the published `ManagedCallState`.
///
/// Exposed (read-only `session`) via ``SerenadaCallRegistry/activeCall`` so the
/// host can render `SerenadaCallFlow(session: registry.activeCall?.session)`.
@MainActor
public final class ManagedCall {
    public let id: CallId
    /// Canonical room token (dedup key).
    public let roomId: String
    public let roomUrl: URL?
    /// The concrete live session for host rendering, or `nil` when backed by a
    /// test stub. Held or foreground; never destroyed until leave/end.
    public var session: SerenadaSession? { managedSession as? SerenadaSession }
    public let displayName: String?

    /// The registry's session seam (the concrete session in production).
    fileprivate let managedSession: RegistryManagedSession

    /// The registry-issued foreground lease token while this call is foreground,
    /// else `nil`. The registry releases the lease with this token; the SESSION
    /// uses it only to fence late callbacks (contract §3).
    fileprivate var foregroundToken: ForegroundOwnerToken?
    /// Per-call failure surfaced in published state.
    fileprivate var activationError: CallActivationError?
    /// True once the call has ended (left/ended). Ended calls are retained until
    /// dismissed/retention so the host can show a "call ended" chip.
    fileprivate var ended = false
    /// Quality summary captured at end.
    fileprivate var endedQualitySummary: CallQualitySummary?

    fileprivate init(
        id: CallId,
        roomId: String,
        roomUrl: URL?,
        session: RegistryManagedSession,
        displayName: String?
    ) {
        self.id = id
        self.roomId = roomId
        self.roomUrl = roomUrl
        self.managedSession = session
        self.displayName = displayName
    }

    /// The published media role, derived from the REGISTRY-OWNED lease token, NOT
    /// `managedSession.mediaRole` (contract FIX B). A call is `.foreground` iff the
    /// registry currently holds a foreground lease for it; otherwise `.held`. The
    /// session's own `mediaRole` is an unreliable source for published state: on
    /// `leave`/`end` teardown `resetResources` does not flip it back from
    /// `.foreground`, so an ended-while-active call would otherwise keep reporting
    /// `.foreground` and `held == false`. The lease token is the registry's
    /// authoritative record of foreground ownership and is cleared on every
    /// drain/teardown, so it never lies.
    fileprivate var publishedMediaRole: CallMediaRole {
        foregroundToken != nil ? .foreground : .held
    }

    fileprivate func snapshot() -> ManagedCallState {
        let role = publishedMediaRole
        return ManagedCallState(
            id: id,
            roomId: roomId,
            roomUrl: roomUrl,
            membershipPhase: managedSession.registryMembershipPhase,
            mediaRole: role,
            mediaActivationState: managedSession.mediaActivationState,
            desiredAudioEnabled: managedSession.registryDesiredAudioEnabled,
            desiredVideoMode: managedSession.registryDesiredVideoMode,
            actualAudioPublished: managedSession.registryActualAudioPublished,
            actualVideoPublished: managedSession.registryActualVideoPublished,
            participantCount: managedSession.registryParticipantCount,
            localCid: managedSession.registryLocalCid,
            held: role == .held,
            displayName: displayName,
            activationError: activationError,
            qualitySummary: endedQualitySummary ?? managedSession.registryQualitySummary
        )
    }
}

// MARK: - SerenadaCallRegistry

/// Process-wide multi-call manager (contract §7). Creates held/foreground
/// sessions through ``SerenadaCore`` (passing an explicit initial media role),
/// keeps them keyed by a stable ``CallId``, owns the single foreground lease via
/// ``ForegroundMediaArbiter``, and serializes EVERY operation so audio
/// activation/deactivation can never interleave.
///
/// The host renders the active call with:
/// ```swift
/// SerenadaCallFlow(session: registry.activeCall?.session)
/// ```
///
/// Mixing the registry with direct `SerenadaCore.join()` is unsupported in v1
/// (Core Invariant 6): while the registry holds any non-ended call, a direct
/// join fails `ForegroundLeaseUnavailable`, and vice versa.
@MainActor
public final class SerenadaCallRegistry: ObservableObject {

    // MARK: Published state (contract §11)

    /// Snapshot of every managed call (live + recently ended, until dismissed).
    @Published public private(set) var calls: [ManagedCallState] = []
    /// The id of the single foreground call, or `nil` when none is foreground
    /// (all held / no calls). Derived, never two foreground.
    @Published public private(set) var activeCallId: CallId?
    /// True while a serialized registry op is mutating the lease/call map.
    @Published public private(set) var registryOperationInProgress: Bool = false
    /// Last registry-level error (per-call detail lives on each call's
    /// `activationError`).
    @Published public private(set) var lastError: CallRegistryError?

    // MARK: Dependencies

    private let core: SerenadaCore
    private let arbiter: ForegroundMediaArbiter
    private let clock: SessionClock
    private let logger: SerenadaLogger?
    /// Builds a managed session for a room + initial role. Default uses
    /// `core.makeManagedSession` (returns a concrete `SerenadaSession`); tests
    /// inject a factory returning a real session OR a stub.
    private let sessionFactory: (RoomRef, CallMediaRole) -> RegistryManagedSession

    // MARK: Call map + serialization

    /// Registry-owned records keyed by `CallId`, in insertion order.
    private var managed: [ManagedCall] = []
    /// Tail of the serial operation chain. Every queued section appends to this
    /// so sections run one-at-a-time, never interleaving lease/media transitions
    /// (contract "Registry Operation Serialization"). This serializes the SHORT
    /// critical sections only — slow held joins run outside it.
    private var operationTail: Task<Void, Never> = Task {}
    private var nextCallSerial = 0

    // MARK: Init

    /// Construct a registry over a configured ``SerenadaCore``. The registry uses
    /// the SHARED process arbiter by default (Core Invariant 6 — one per
    /// process); tests inject a dedicated arbiter for isolation.
    public convenience init(core: SerenadaCore) {
        self.init(core: core, arbiter: .shared)
    }

    /// Designated init. `clock`/`sessionFactory` are injectable for tests; the
    /// default factory routes through `core.makeManagedSession` so a managed
    /// session signals identically to a direct join (only role + lease ownership
    /// differ).
    init(
        core: SerenadaCore,
        arbiter: ForegroundMediaArbiter = .shared,
        clock: SessionClock? = nil,
        logger: SerenadaLogger? = nil,
        sessionFactory: ((RoomRef, CallMediaRole) -> RegistryManagedSession)? = nil
    ) {
        self.core = core
        self.arbiter = arbiter
        // Default constructed inside the body: `LiveSessionClock()` is
        // `@MainActor`-isolated and cannot be a default parameter value (those
        // evaluate in a nonisolated context). Mirrors `SerenadaSession.init`.
        self.clock = clock ?? LiveSessionClock()
        self.logger = logger ?? core.logger
        if let sessionFactory {
            self.sessionFactory = sessionFactory
        } else {
            self.sessionFactory = { room, role in
                let url = room.url ?? room.roomId.flatMap { core.roomURL(forRoomId: $0) }
                guard let url else {
                    preconditionFailure("RoomRef must carry a URL or a roomId resolvable to a server host")
                }
                return core.makeManagedSession(
                    url: url,
                    initialMediaRole: role,
                    displayName: room.displayName,
                    peerId: room.peerId,
                    arbiter: arbiter
                )
            }
        }
    }

    // MARK: Convenience accessors

    /// The active foreground managed call (with its `.session`), or `nil`. The
    /// host renders `SerenadaCallFlow(session: registry.activeCall?.session)`.
    public var activeCall: ManagedCall? {
        guard let activeCallId else { return nil }
        return managed.first { $0.id == activeCallId }
    }

    /// Look up a managed call by id (read-only; the underlying session is not
    /// hidden — contract §11).
    public func call(id: CallId) -> ManagedCall? {
        managed.first { $0.id == id }
    }

    // MARK: - Public operations (contract §7)

    /// Join a room WITHOUT taking foreground (stable senders, no capture, no
    /// lease). Idempotent by canonical room (a second live join for the same
    /// room returns the existing CallId). Composite: queued create+register
    /// (section A), then a held room join OUTSIDE the queue bounded by
    /// `HELD_JOIN_TIMEOUT`.
    public func joinHeld(_ room: RoomRef) async -> JoinResult {
        let prepared = await runQueued { self.createAndRegister(room) }
        switch prepared {
        case let .existing(id):
            return .joined(id)
        case let .failure(error):
            return .failed(nil, error)
        case let .created(id):
            guard let call = call(id: id) else { return .failed(id, .joinFailed("Call vanished")) }
            if let error = await awaitHeldJoin(call) {
                await runQueued { self.markFailedHeldJoin(id: id, error) }
                await publish()
                return .failed(id, error)
            }
            await publish()
            return .joined(id)
        }
    }

    /// Join a room held, then switch to it (the standard "second call" flow —
    /// design "Incoming/Outgoing Second Call"). Composite parts A/B/C.
    public func joinAndSwitch(_ room: RoomRef) async -> JoinAndSwitchResult {
        let prepared = await runQueued { self.createAndRegister(room) }
        let newId: CallId
        switch prepared {
        case let .existing(id):
            // Already live: just switch to it (idempotent join by room).
            switch await switchToCall(id: id) {
            case .active: return .active(id)
            case .needsPermission: return .needsPermission(id)
            case let .failed(error): return .failed(id, error)
            }
        case let .failure(error):
            return .failed(nil, error)
        case let .created(id):
            newId = id
        }

        guard let call = call(id: newId) else { return .failed(newId, .joinFailed("Call vanished")) }

        // Section B (outside the queue): the held room join, bounded.
        if let error = await awaitHeldJoin(call) {
            await runQueued { self.markFailedHeldJoin(id: newId, error) }
            await publish()
            return .failed(newId, error) // old foreground call untouched
        }

        // Section C (queued): run the switch body. It re-reads activeCallId (the
        // world may have changed between sections A and B).
        switch await switchToCall(id: newId) {
        case .active: return .active(newId)
        case .needsPermission: return .needsPermission(newId)
        case let .failed(error): return .failed(newId, error)
        }
    }

    /// Switch foreground to `id`. Fully serialized; preflight runs INSIDE the
    /// queued op before the old call is touched (contract §7 pseudocode).
    public func switchToCall(id: CallId) async -> SwitchResult {
        await runQueued { await self.switchBody(nextId: id) }
    }

    /// Hold a call: drain its foreground resources, release the lease, set
    /// `activeCallId = nil`. NO auto-promote (Invariant 5). Holding a held / only
    /// call is a no-op (still connected).
    public func holdCall(id: CallId) async {
        await runQueued { await self.holdBody(id: id) }
    }

    /// Leave a call. For the active call: release foreground first, then run the
    /// existing leave teardown. Held calls stay connected and are NOT
    /// auto-promoted.
    public func leaveCall(id: CallId) async {
        await runQueued { await self.teardownBody(id: id, end: false) }
    }

    /// End a call for all participants. Same lease/teardown ordering as `leave`.
    public func endCall(id: CallId) async {
        await runQueued { await self.teardownBody(id: id, end: true) }
    }

    /// Remove an ended call's record from the published list (retention/dismiss).
    /// Live calls are ignored (use leave/end first).
    public func dismissEndedCall(id: CallId) async {
        await runQueued {
            guard let call = self.managed.first(where: { $0.id == id }), call.ended else { return }
            self.managed.removeAll { $0.id == id }
        }
        await publish()
    }

    // MARK: - Queued sections (run one-at-a-time)

    /// Outcome of the short create+register critical section (composite part A).
    /// Carries only the `CallId` (Sendable) — the caller re-fetches the
    /// `ManagedCall` so the non-Sendable record never crosses the serial-task
    /// boundary.
    private enum PreparedJoin: Sendable {
        /// A new held call was created and registered under this id.
        case created(CallId)
        /// A live call already exists for this canonical room (idempotent join).
        case existing(CallId)
        /// Create failed (mode conflict / unresolvable room).
        case failure(CallActivationError)
    }

    /// Composite part A (queued, short): create + register a held managed call,
    /// enforcing the owning-mode guard and room-dedup. Does NOT join (that runs
    /// outside the queue). Reserves the registry owning mode so a concurrent
    /// direct join fails fast even before any foreground lease exists.
    private func createAndRegister(_ room: RoomRef) -> PreparedJoin {
        guard let canonical = canonicalRoomId(room) else {
            return .failure(.joinFailed("Could not resolve a room id from RoomRef"))
        }

        // Dedup: a second live join for an existing non-ended room resolves to it.
        if let existing = managed.first(where: { $0.roomId == canonical && !$0.ended }) {
            return .existing(existing.id)
        }

        // Reserve the registry owning mode (Core Invariant 6). While the registry
        // holds any non-ended call, a direct join must fail; claiming mode here —
        // before any lease — makes an all-held registry still own the process.
        do {
            try arbiter.claimMode(.registry, ownerRef: self)
        } catch {
            return .failure(.leaseUnavailable)
        }

        let id = "call-\(nextCallSerial)-\(UUID().uuidString)"
        nextCallSerial += 1
        let managedSession = sessionFactory(room, .held)
        let call = ManagedCall(
            id: id,
            roomId: canonical,
            roomUrl: room.url ?? managedSession.roomUrl,
            session: managedSession,
            displayName: room.displayName
        )
        managed.append(call)
        return .created(id)
    }

    /// Record a failed/timed-out held join and mark the call ENDED (dismissable),
    /// then release the registry owning-mode when no live (non-ended) call remains
    /// (contract FIX F). A failed held join that stayed "live but never connected"
    /// would otherwise keep the registry's `registry`-mode claim forever, so a
    /// later direct `SerenadaCore.join()` could never proceed (Core Invariant 6).
    /// Marking it ended frees the mode and lets the host dismiss the dead chip.
    /// Idempotent. The call held NO foreground lease (a held join never acquires
    /// one), so there is no lease to release here.
    private func markFailedHeldJoin(id: CallId, _ error: CallActivationError) {
        guard let call = managed.first(where: { $0.id == id }), !call.ended else { return }
        call.activationError = error
        lastError = .callFailed(id, error)
        // Tear down the dead session (stop signaling/timers); it never connected.
        call.managedSession.registryLeave()
        call.ended = true
        call.endedQualitySummary = call.managedSession.registryQualitySummary
        // Free the registry owning-mode when nothing live remains, so a subsequent
        // direct join can claim the process (Core Invariant 6 / contract FIX F).
        if !managed.contains(where: { !$0.ended }) {
            arbiter.releaseMode(ownerRef: self)
        }
    }

    /// The switch body (contract §7). Runs entirely inside the serial queue. Both
    /// the explicit `switchToCall` and the composite `joinAndSwitch` part C route
    /// here, so they share the rollback algorithm verbatim.
    private func switchBody(nextId: CallId) async -> SwitchResult {
        guard let next = managed.first(where: { $0.id == nextId && !$0.ended }) else {
            lastError = .unknownCall(nextId)
            return .failed(.activationFailed("Unknown call"))
        }
        // No-op if already active (re-read activeCallId — the world may have
        // changed between composite sections A and B).
        if nextId == activeCallId { return .active }

        let gen = arbiter.nextOperationGeneration()

        // 0. PREFLIGHT inside the queued op, BEFORE touching the old call
        //    (Core Invariant 4). needsPermission → return, old untouched.
        if next.managedSession.preflightForeground() == .needsPermission {
            let missing = missingDesiredPermissions(next.managedSession)
            next.activationError = .needsPermission(missing)
            await publish()
            return .needsPermission
        }
        next.activationError = nil

        let old = activeCallId.flatMap { id in managed.first { $0.id == id } }

        // 1. Drain the OLD call with ITS token, bounded by RELEASE timeout.
        //    `releaseForeground` is idempotent and stops screen share (foreground-
        //    only) as part of the drain (design "Screen Share During Switch").
        if let old, let oldToken = old.foregroundToken {
            // Mark the arbiter release pending so NO new lease is granted while
            // the old may still own it (Core Invariant 2).
            arbiter.markReleasePending()
            // Release AND settle inside ONE `FOREGROUND_RELEASE_TIMEOUT` window
            // (contract FIX C). The role flip is synchronous, but the coordinator
            // `deactivateCallSession` runs in a fire-and-forget task; a stuck
            // coordinator must NOT hang the serial op queue. Bounding the settle
            // separately (after the timeout gate) left it effectively unbounded.
            let drained = await drainOldForeground(old, token: oldToken)
            if !drained {
                // Timeout (Core Invariant 1): do NOT release the old lease and do
                // NOT grant the next one. The old call retains its lease
                // (`currentToken == oldToken`), so there is no second-owner risk;
                // the still-set `releasePending` flag is cleared by the next switch
                // retry, which re-drains this same old call and releases its lease.
                logger?.log(.error, tag: "Registry",
                            "Switch \(old.id)->\(next.id) aborted: old-release did not confirm fully-held + settled in \(WebRtcResilience.foregroundReleaseTimeoutMs)ms (old keeps the lease)")
                old.activationError = .releaseTimedOut
                lastError = .callFailed(old.id, .releaseTimedOut)
                await publish()
                return .failed(.releaseTimedOut)
            }
            // Registry frees the lease (lease release is registry-owned).
            try? arbiter.releaseLease(oldToken)
            old.foregroundToken = nil
        }
        // After this point activeCallId no longer reflects a foreground owner.
        activeCallId = nil

        // 2. Acquire a FRESH token for next and activate, bounded by ACTIVATE timeout.
        do {
            let newToken = try arbiter.acquireForeground(
                ownerId: next.id,
                mode: .registry,
                ownerRef: self
            )
            next.foregroundToken = newToken
            try next.managedSession.activateForeground(newToken, generation: gen)
            let activated = await awaitSettle(timeoutMs: WebRtcResilience.foregroundActivateTimeoutMs) {
                next.managedSession.mediaActivationState != .activating
            }
            if !activated || next.managedSession.mediaActivationState != .active {
                throw CallActivationError.activationFailed("Foreground activation did not settle")
            }
            // The session flips its own `mediaRole` to `.foreground` inside
            // `completeForegroundActivation`; the registry just records active id.
            activeCallId = next.id
            next.activationError = nil
            await publish()
            return .active
        } catch {
            let activationError = (error as? CallActivationError) ?? .activationFailed("\(error)")
            logger?.log(.error, tag: "Registry",
                        "Switch to \(next.id) activation failed (\(activationError)); rolling back to \(activeCallId.map { _ in "old" } ?? "none")")
            // Clean up the partial activation BEFORE touching the lease.
            if let token = next.foregroundToken {
                next.managedSession.abortForegroundActivation(token)
                try? arbiter.releaseLease(token)
                next.foregroundToken = nil
            }
            next.activationError = activationError
            // 3. Roll back to OLD under a FRESH generation (switch-failure rolls back).
            if let old {
                let rollbackOk = await rollbackToOld(old)
                if rollbackOk {
                    lastError = .callFailed(next.id, activationError)
                    await publish()
                    return .failed(activationError)
                }
                // Rollback also failed: no foreground owner; surface both.
                activeCallId = nil
                lastError = .callFailed(next.id, activationError)
            } else {
                activeCallId = nil
                lastError = .callFailed(next.id, activationError)
            }
            await publish()
            return .failed(activationError)
        }
    }

    /// Re-activate the OLD call as foreground under a FRESH generation after a
    /// failed switch (contract §7 step 3). Returns whether rollback succeeded.
    private func rollbackToOld(_ old: ManagedCall) async -> Bool {
        let rollbackGen = arbiter.nextOperationGeneration()
        do {
            let token = try arbiter.acquireForeground(
                ownerId: old.id,
                mode: .registry,
                ownerRef: self
            )
            old.foregroundToken = token
            try old.managedSession.activateForeground(token, generation: rollbackGen)
            let ok = await awaitSettle(timeoutMs: WebRtcResilience.foregroundActivateTimeoutMs) {
                old.managedSession.mediaActivationState != .activating
            }
            if !ok || old.managedSession.mediaActivationState != .active {
                throw CallActivationError.activationFailed("Rollback activation did not settle")
            }
            activeCallId = old.id
            return true
        } catch {
            if let token = old.foregroundToken {
                old.managedSession.abortForegroundActivation(token)
                try? arbiter.releaseLease(token)
                old.foregroundToken = nil
            }
            old.activationError = (error as? CallActivationError) ?? .activationFailed("\(error)")
            return false
        }
    }

    /// `hold` body (contract §7). Holding the active call drains it and releases
    /// the lease; activeCallId becomes nil with NO auto-promote.
    private func holdBody(id: CallId) async {
        guard let call = managed.first(where: { $0.id == id && !$0.ended }) else {
            lastError = .unknownCall(id)
            return
        }
        // hold(held) / hold(only-call) is a no-op (already not foreground).
        guard call.id == activeCallId, let token = call.foregroundToken else {
            await publish()
            return
        }
        arbiter.markReleasePending()
        // Release AND settle in ONE timeout window (contract FIX C): a hung
        // coordinator deactivation must not leave the lease half-released.
        let drained = await drainOldForeground(call, token: token)
        if drained {
            try? arbiter.releaseLease(token)
            call.foregroundToken = nil
            activeCallId = nil
        } else {
            // Could not confirm fully-held: keep the lease (no auto-promote, no
            // second owner — the call retains `currentToken`). Mark failed and
            // leave it foreground; a later hold/leave retry re-drains + releases.
            call.activationError = .releaseTimedOut
            lastError = .callFailed(call.id, .releaseTimedOut)
        }
        await publish()
    }

    /// `leave`/`end` body (contract §7). Active call: release foreground +
    /// lease, then existing teardown. Held call: signaling/room cleanup only,
    /// no arbiter interaction beyond asserting it holds no lease.
    private func teardownBody(id: CallId, end: Bool) async {
        guard let call = managed.first(where: { $0.id == id && !$0.ended }) else {
            lastError = .unknownCall(id)
            return
        }

        // Active call: drain foreground + release the registry-owned lease FIRST.
        if call.id == activeCallId, let token = call.foregroundToken {
            arbiter.markReleasePending()
            call.managedSession.releaseForeground(token)
            _ = await awaitSettle(timeoutMs: WebRtcResilience.foregroundReleaseTimeoutMs) {
                call.managedSession.mediaRole == .held && call.managedSession.mediaActivationState == .inactive
            }
            // leave/end teardown proceeds regardless (the session is going away);
            // free the lease so a future call can claim it.
            try? arbiter.releaseLease(token)
            call.foregroundToken = nil
            activeCallId = nil
        } else if let token = call.foregroundToken {
            // Defense in depth: a held call must hold no lease. If it somehow does,
            // release it (leaked lease guard, contract §7).
            try? arbiter.releaseLease(token)
            call.foregroundToken = nil
        }

        // Existing leave/end teardown (sends leave/end, tears down membership).
        if end {
            call.managedSession.registryEnd()
        } else {
            call.managedSession.registryLeave()
        }

        // Mark ended, capture quality, retain the record (host dismisses later).
        call.ended = true
        call.endedQualitySummary = call.managedSession.registryQualitySummary

        // Release the registry owning mode when no non-ended call remains, so the
        // process frees up for a direct join (Core Invariant 6).
        if !managed.contains(where: { !$0.ended }) {
            arbiter.releaseMode(ownerRef: self)
        }
        await publish()
    }

    // MARK: - Held-join wait (composite part B, outside the queue)

    /// Wait for a freshly created held session to leave `.joining`/`.idle`
    /// (signaling connected, room joined), bounded by `HELD_JOIN_TIMEOUT`.
    /// Returns a `CallActivationError` on failure/timeout, else `nil`.
    private func awaitHeldJoin(_ call: ManagedCall) async -> CallActivationError? {
        let session = call.managedSession
        let settled = await awaitSettle(timeoutMs: WebRtcResilience.heldJoinTimeoutMs) {
            let phase = session.registryMembershipPhase
            return phase == .waiting || phase == .inCall || phase == .error
        }
        if !settled {
            return .joinFailed("Held room join timed out")
        }
        if session.registryMembershipPhase == .error {
            return .joinFailed("Held room join failed: \(session.registryErrorDescription ?? "unknown")")
        }
        return nil
    }

    // MARK: - Bounded foreground drain

    /// Drive the OLD foreground call to fully-held AND wait for its audio
    /// coordinator teardown to settle, ALL inside a SINGLE
    /// `FOREGROUND_RELEASE_TIMEOUT` window (contract FIX C). Returns `true` only
    /// when the session reached `.held`/`.inactive` AND
    /// `awaitForegroundReleaseSettled()` completed before the deadline.
    ///
    /// Why one window: the role/activation flip is synchronous, but the actual
    /// coordinator `deactivateCallSession()` runs in a fire-and-forget lifecycle
    /// task. Awaiting that AFTER a separate timeout gate left it effectively
    /// unbounded — a stuck coordinator could hang the serial op queue forever. The
    /// settle is folded into the same bounded poll: the condition becomes true only
    /// once both the role is held AND the settle task has finished. On timeout the
    /// caller keeps the old call foreground (Invariant 1) and never releases the
    /// lease.
    ///
    /// `releaseForeground` is idempotent and must not throw, so it is safe to call
    /// once here; the settle task is started immediately after so its await runs
    /// concurrently with the poll.
    private func drainOldForeground(_ call: ManagedCall, token: ForegroundOwnerToken) async -> Bool {
        let session = call.managedSession
        session.releaseForeground(token)

        // Track the coordinator-teardown settle independently: a hung
        // `awaitForegroundReleaseSettled()` must be bounded by the same deadline,
        // not awaited unconditionally afterwards.
        var settled = false
        let settleTask = Task { @MainActor in
            await session.awaitForegroundReleaseSettled()
            settled = true
        }
        defer { settleTask.cancel() }

        return await awaitSettle(timeoutMs: WebRtcResilience.foregroundReleaseTimeoutMs) {
            session.mediaRole == .held
                && session.mediaActivationState == .inactive
                && settled
        }
    }

    // MARK: - Serialization + settling

    /// Run `body` after every previously-queued section completes (contract
    /// "Registry Operation Serialization"). Each section runs to completion
    /// (including its arbiter calls) before the next begins, on the main actor, so
    /// foreground-lease + call-map mutations from different ops never interleave.
    /// Slow network I/O (held room joins) runs OUTSIDE this — only short critical
    /// sections are serialized here.
    @discardableResult
    private func runQueued<T: Sendable>(_ body: @escaping @MainActor () async -> T) async -> T {
        let previous = operationTail
        let task = Task { @MainActor () -> T in
            _ = await previous.value
            registryOperationInProgress = true
            defer { registryOperationInProgress = false }
            return await body()
        }
        // Advance the serial tail (typed `Task<Void, Never>`): the next enqueued
        // section will await THIS one before running.
        operationTail = Task { @MainActor in _ = await task.value }
        return await task.value
    }

    /// Poll `condition` until true, bounded by `timeoutMs` on the injected clock.
    ///
    /// Two phases, both required for deterministic tests AND correct production:
    ///
    /// 1. A generous **yield-only** budget first: a normal settle (coordinator
    ///    completion, join handlers) needs only a handful of main-actor hops, so
    ///    this resolves the common path WITHOUT ever calling `clock.sleep`. This
    ///    matters because the test fake clock only resolves a `sleep` on an
    ///    explicit `advance`, so a sleep on the success path would deadlock.
    /// 2. A **sleep + deadline** phase for a genuinely stuck operation (a blocked
    ///    coordinator). A live clock trips the deadline on real elapsed time; a
    ///    fake clock trips it when the test drives `advance(byMs:)` past the
    ///    timeout. Returns whether the condition was met before the deadline.
    private func awaitSettle(timeoutMs: Int, condition: @MainActor () -> Bool) async -> Bool {
        // Phase 1: yield-only fast path (no clock dependence).
        for _ in 0..<64 {
            if condition() { return true }
            await Task.yield()
        }
        if condition() { return true }

        // Phase 2: bounded sleep against the deadline.
        let deadline = clock.monotonicMs() + Int64(timeoutMs)
        let pollIntervalNs: UInt64 = 20 * 1_000_000 // 20ms granularity
        while clock.monotonicMs() < deadline {
            do {
                try await clock.sleep(nanoseconds: pollIntervalNs)
            } catch {
                return condition()
            }
            for _ in 0..<4 { await Task.yield() }
            if condition() { return true }
        }
        return condition()
    }

    // MARK: - Permission helpers

    /// The mic/camera capabilities the session's DESIRED media needs that are not
    /// granted, for the `CallActivationError.needsPermission` payload. Mirrors
    /// `preflightForeground`'s decision.
    private func missingDesiredPermissions(_ session: RegistryManagedSession) -> [MediaCapability] {
        session.missingDesiredForegroundPermissions()
    }

    // MARK: - Publishing

    private func publish() async {
        calls = managed.map { $0.snapshot() }
    }

    private func canonicalRoomId(_ room: RoomRef) -> String? {
        if let url = room.url {
            return DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        }
        return room.roomId
    }
}
