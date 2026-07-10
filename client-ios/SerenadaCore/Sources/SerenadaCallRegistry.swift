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

    /// The session's membership-phase stream, so the registry can observe
    /// session-DRIVEN terminal state (`room_ended` / remote end / fatal error →
    /// `cleanupCall`) and release its own lease + clear `activeCallId` even though
    /// the session's reset releases only its DIRECT token (registry-created
    /// sessions hold none — contract §7). The concrete session maps its published
    /// `$state.phase`; stubs publish through a subject so a test can drive a
    /// terminal phase deterministically. Emits the current phase on subscribe.
    var registryPhasePublisher: AnyPublisher<SerenadaCallPhase, Never> { get }

    /// Coalesced "a snapshot-affecting field changed" stream. Emits whenever ANY
    /// field projected into `ManagedCallState` changes (participant count,
    /// membership phase, actual-published flags, quality summary, etc.), so the
    /// registry can refresh the published `calls` entry for a HELD call between
    /// ops — not just on the next registry op or a terminal phase. The concrete
    /// session maps `objectWillChange` (every `@Published` change); stubs fire a
    /// subject when they mutate a registry* field. Emissions may be redundant
    /// (e.g. a stats tick that does not alter the snapshot); the registry dedupes
    /// against the last published snapshot, so redundant emits are cheap no-ops.
    ///
    /// NOTE: `objectWillChange` fires in `willSet` (BEFORE the value updates), so
    /// the registry observer MUST defer reading the snapshot to the next
    /// main-actor turn (it hops through `Task { @MainActor in ... }`).
    var registrySnapshotPublisher: AnyPublisher<Void, Never> { get }

    func preflightForeground() -> ForegroundPreflight
    func missingDesiredForegroundPermissions() -> [MediaCapability]
    /// Drive the session to foreground, AWAITING the async coordinator bring-up.
    /// Returns once the activation has committed `.active` or throws when it was
    /// superseded/failed (so the registry rolls back). The registry bounds this
    /// with a timeout (`FOREGROUND_ACTIVATE_TIMEOUT`).
    func activateForeground(_ token: ForegroundOwnerToken, generation: Int) async throws
    /// Drive the session to fully-held and AWAIT its audio teardown so that, on
    /// return, the OLD session is fully settled — this is how the registry
    /// sequences "release old fully before acquiring new" (contract §6). Idempotent
    /// / no-throw. The registry bounds this with `FOREGROUND_RELEASE_TIMEOUT`.
    func releaseForeground(_ token: ForegroundOwnerToken) async
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
    var registryPhasePublisher: AnyPublisher<SerenadaCallPhase, Never> {
        // `$state` emits the CURRENT value on subscribe and on every change, so the
        // registry observer sees both the present phase and the eventual terminal
        // transition (`.ending`/`.idle`/`.error`) driven by `cleanupCall`.
        $state.map(\.phase).eraseToAnyPublisher()
    }
    var registrySnapshotPublisher: AnyPublisher<Void, Never> {
        // `objectWillChange` fires for every `@Published` change (state,
        // qualitySummary, mic/route flags, ...), which covers every field
        // `snapshot()` reads. It fires in `willSet`, BEFORE the property updates,
        // so the registry observer reads the snapshot on the NEXT main-actor turn.
        objectWillChange.map { _ in () }.eraseToAnyPublisher()
    }
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
    /// Subscription to the session's membership-phase stream. Drives the registry's
    /// session-DRIVEN terminal handling (contract §7); cancelled when the call is
    /// removed (`dismissEndedCall`) or replaced.
    fileprivate var phaseObserver: AnyCancellable?
    /// Subscription to the session's coalesced snapshot-change stream. Drives live
    /// republish of a held call's projected state between ops; cancelled when the
    /// call is removed (`dismissEndedCall`).
    fileprivate var stateObserver: AnyCancellable?
    /// Last `ManagedCallState` published for this call, used to dedupe redundant
    /// snapshot emits (e.g. stats ticks that do not change the projection).
    fileprivate var lastSnapshot: ManagedCallState?

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
    /// Set by ``close()``. Once closed, a queued create that lands after teardown
    /// (an in-flight `joinHeld`/`joinAndSwitch` whose section A runs post-dismiss)
    /// no-ops instead of resurrecting a call/session nothing would leave. Mirrors
    /// the web/Android registry `close()`.
    private var closed = false

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
                // Total (never crashes): `createAndRegister` validates
                // `canonicalRoomId(room)` BEFORE claiming the registry mode, so a
                // room id is always resolvable here (a RoomRef carries a url or a
                // bare roomId). Resolve a URL only in server mode — provider mode has
                // no server host, so `roomURL` stays nil and the managed session
                // opens the provider channel with the bare room id (mirrors the
                // direct `join(roomId:)` path).
                let roomId = room.roomId
                    ?? room.url.map { DeepLinkParser.extractRoomId(from: $0) ?? $0.lastPathComponent }
                    ?? ""
                let roomURL = room.url ?? core.roomURL(forRoomId: roomId)
                return core.makeManagedSession(
                    roomId: roomId,
                    roomURL: roomURL,
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
            // Await settlement for the REUSED call too: dedup can return a call
            // whose held join is still in flight (a second join racing the
            // first), and `.joined` must mean membership actually exists.
            // awaitHeldJoin short-circuits once settled.
            guard let existing = call(id: id) else { return .failed(id, .joinFailed("Call vanished")) }
            if let error = await awaitHeldJoin(existing) {
                await runQueued { self.markFailedHeldJoin(id: id, error) }
                await publish()
                return .failed(id, error)
            }
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
            // Already registered for this room — but its held join may still be
            // in flight (a second joinAndSwitch double-tapping the first). Await
            // settlement before switching: activating a session with no room
            // membership captures media for a room that may never join, and a
            // later join timeout would strand the acquired lease (Core
            // Invariant 1). awaitHeldJoin short-circuits once settled, so a
            // genuinely-live reused call switches immediately.
            guard let existing = call(id: id) else { return .failed(id, .joinFailed("Call vanished")) }
            if let error = await awaitHeldJoin(existing) {
                await runQueued { self.markFailedHeldJoin(id: id, error) }
                await publish()
                return .failed(id, error)
            }
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
    /// Gated on held-join settlement first (OUTSIDE the queue, like composite
    /// part B): switching to a still-joining call would activate capture on a
    /// session with no room membership. Unknown/ended targets skip the gate and
    /// fail inside `switchBody` as before.
    public func switchToCall(id: CallId) async -> SwitchResult {
        if let call = call(id: id), !call.ended {
            if let error = await awaitHeldJoin(call) {
                await runQueued { self.markFailedHeldJoin(id: id, error) }
                await publish()
                return .failed(error)
            }
        }
        return await runQueued { await self.switchBody(nextId: id) }
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
            // Stop observing the session's phase + snapshot stream; the call is
            // leaving the registry.
            call.phaseObserver?.cancel()
            call.phaseObserver = nil
            call.stateObserver?.cancel()
            call.stateObserver = nil
            self.managed.removeAll { $0.id == id }
        }
        await publish()
    }

    /// Tear down the registry: refuse new creates and leave EVERY managed call
    /// (releasing the foreground lease + owning mode), iterating the authoritative
    /// `managed` list. Hosts call this on teardown (e.g. the screen is dismissed).
    /// `closed` is set synchronously first so an in-flight queued create no-ops
    /// instead of leaking a session nothing would leave. Mirrors web/Android.
    public func close() async {
        closed = true
        for id in managed.map(\.id) {
            await runQueued { await self.teardownBody(id: id, end: false) }
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
        // Registry was closed while this create sat in the serial queue (host
        // dismissed between joinHeld() and this section running). Do not create a
        // session that close() already iterated past and nothing will leave.
        if closed {
            return .failure(.joinFailed("Registry is closed"))
        }
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
        observeTerminalPhase(call)
        observeStateForPublish(call)
        return .created(id)
    }

    /// Subscribe to a managed call's membership-phase stream so the registry
    /// observes session-DRIVEN terminal state (contract §7). When `room_ended` /
    /// remote end / a fatal error drives the session's `cleanupCall` to `.ending`
    /// (treated as terminal for lease purposes — released promptly), `.idle`, or
    /// `.error`, the registry enqueues a SERIALIZED terminal op so it releases its
    /// OWN foreground lease (which the session's reset never touches for a
    /// registry-created call) and clears `activeCallId` (NO auto-promote —
    /// Invariant 5). The op is idempotent + queue-safe: a registry-initiated
    /// `leaveCall`/`endCall` that already released the lease and marked the call
    /// ended makes this a no-op. The subscription holds a STRONG reference to the
    /// registry only for the closure's lifetime; it is cancelled when the call is
    /// removed (`dismissEndedCall`).
    private func observeTerminalPhase(_ call: ManagedCall) {
        let id = call.id
        call.phaseObserver = call.managedSession.registryPhasePublisher
            .sink { [weak self] phase in
                guard let self else { return }
                guard phase == .ending || phase == .idle || phase == .error else { return }
                Task { @MainActor in
                    await self.runQueued { await self.terminalBody(id: id) }
                }
            }
    }

    /// Subscribe to a managed call's coalesced snapshot-change stream so the
    /// registry refreshes that call's published `ManagedCallState` whenever a
    /// snapshot-affecting field changes (P3 #7: held-call participantCount /
    /// membershipPhase / actual-published / qualitySummary changes were invisible
    /// in `calls` until the next op or terminal phase). This is a STATE REFRESH,
    /// NOT an operation: it is deliberately NOT routed through `runQueued` (it
    /// touches no lease/call-map invariant, only re-derives a published value) and
    /// it dedupes against the call's `lastSnapshot` so a stats tick that does not
    /// change the projection is a no-op (no `calls` churn).
    ///
    /// The emit hops to the NEXT main-actor turn because the concrete session maps
    /// `objectWillChange`, which fires in `willSet` BEFORE the property updates;
    /// reading the snapshot synchronously would observe the OLD value.
    private func observeStateForPublish(_ call: ManagedCall) {
        call.stateObserver = call.managedSession.registrySnapshotPublisher
            .sink { [weak self, weak call] in
                guard let self, let call else { return }
                Task { @MainActor in
                    await self.republishIfChanged(call)
                }
            }
    }

    /// Re-derive `call`'s snapshot and republish only if it changed since the last
    /// publish (dedupe). Keeps `calls` coherent with live held-call state between
    /// ops without spamming subscribers on no-op emits.
    @MainActor
    private func republishIfChanged(_ call: ManagedCall) async {
        let next = call.snapshot()
        if call.lastSnapshot == next { return }
        call.lastSnapshot = next
        await publish()
    }

    /// Record a failed/timed-out held join and mark the call ENDED (dismissable),
    /// then release the registry owning-mode when no live (non-ended) call remains
    /// (contract FIX F). A failed held join that stayed "live but never connected"
    /// would otherwise keep the registry's `registry`-mode claim forever, so a
    /// later direct `SerenadaCore.join()` could never proceed (Core Invariant 6).
    /// Marking it ended frees the mode and lets the host dismiss the dead chip.
    /// Idempotent. With settlement gating on every switch entry point a failed
    /// held join can never hold the foreground lease — but release one
    /// defensively if a future path regresses that invariant: `ended` below
    /// makes the terminal observer skip this call, which would otherwise strand
    /// the process-wide lease forever.
    private func markFailedHeldJoin(id: CallId, _ error: CallActivationError) {
        guard let call = managed.first(where: { $0.id == id }), !call.ended else { return }
        call.activationError = error
        lastError = .callFailed(id, error)
        if let token = call.foregroundToken {
            call.foregroundToken = nil
            try? arbiter.releaseLease(token)
            if activeCallId == id { activeCallId = nil }
        }
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
            let missing = next.managedSession.missingDesiredForegroundPermissions()
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
            // Activate, bounded by the ACTIVATE timeout. `activateForeground` awaits
            // the coordinator bring-up and THROWS if it was superseded/failed (the
            // catch below rolls back); `withTimeout` returns `nil` if it never
            // settled (treated as activation failure, same as today).
            let activated = try await withTimeout(WebRtcResilience.foregroundActivateTimeoutMs) {
                try await next.managedSession.activateForeground(newToken, generation: gen)
            }
            if activated == nil || next.managedSession.mediaActivationState != .active {
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
            lastError = .callFailed(next.id, activationError)
            // 3. Roll back to OLD under a FRESH generation (switch-failure rolls back).
            //    On success `rollbackToOld` restores `activeCallId = old.id`; if it
            //    fails (or there was no old call) there is no foreground owner, so
            //    `activeCallId` stays nil (set at the start of the activation step).
            if let old {
                _ = await rollbackToOld(old)
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
            let ok = try await withTimeout(WebRtcResilience.foregroundActivateTimeoutMs) {
                try await old.managedSession.activateForeground(token, generation: rollbackGen)
            }
            if ok == nil || old.managedSession.mediaActivationState != .active {
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
            // Bound the awaited release so a stuck coordinator can't hang teardown;
            // teardown proceeds regardless of the outcome (the session is going
            // away). `releaseForeground` is no-throw, so this never rethrows.
            _ = try? await withTimeout(WebRtcResilience.foregroundReleaseTimeoutMs) {
                await call.managedSession.releaseForeground(token)
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

    /// Session-DRIVEN terminal handler (contract §7). Runs inside the serial queue,
    /// triggered when a managed call's session reaches a terminal phase ON ITS OWN
    /// (`room_ended` / remote end / fatal error → `cleanupCall`), NOT via a
    /// registry `leaveCall`/`endCall`. Without this the registry never observes the
    /// session's terminal state: the session's reset releases only its DIRECT token
    /// (registry-created sessions hold none), so the registry would keep
    /// `activeCallId` and leak its `foregroundToken` lease forever.
    ///
    /// IDEMPOTENT + queue-safe. A registry-initiated teardown already cleared the
    /// lease and set `ended` BEFORE the session's terminal phase fires, so this
    /// guards on `!ended` and is a no-op then (no double-release). For a genuinely
    /// session-driven terminal it: releases the still-held registry lease (if any),
    /// clears `activeCallId` when this was the active call (NO auto-promote —
    /// Invariant 5), marks the call ended/non-live (dismissable, out of "live"
    /// counts), releases the registry owning-mode when no live call remains, and
    /// publishes.
    private func terminalBody(id: CallId) async {
        // No-op if the call is gone or already terminal (registry teardown won the
        // race, or this terminal op already ran). Prevents double-release.
        guard let call = managed.first(where: { $0.id == id }), !call.ended else { return }

        // Release the registry-owned foreground lease this call still holds, if any.
        // `releaseLease` is idempotent/no-throw for a stale token, so a partially
        // raced teardown cannot crash here.
        if let token = call.foregroundToken {
            try? arbiter.releaseLease(token)
            call.foregroundToken = nil
        }
        // Clear active if this was the foreground call. NO auto-promote (Invariant 5).
        if activeCallId == id {
            activeCallId = nil
        }

        // Mark ended/non-live and capture the quality summary the session finalized
        // on its own teardown.
        call.ended = true
        call.endedQualitySummary = call.managedSession.registryQualitySummary

        // Release the registry owning mode when no live call remains, so the process
        // frees up for a direct join (Core Invariant 6).
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
    /// when `releaseForeground` returned within the deadline — and it only returns
    /// once the session reached `.held`/`.inactive` AND the coordinator deactivation
    /// finished (the session now awaits its own teardown internally, contract §6).
    ///
    /// Why one window: the role/activation flip is synchronous, but the actual
    /// coordinator `deactivateCallSession()` runs in a fire-and-forget lifecycle
    /// task that `releaseForeground` awaits before returning. A stuck coordinator
    /// must not hang the serial op queue forever, so the whole awaited release is
    /// bounded by `withTimeout`. On timeout the caller keeps the old call foreground
    /// (Invariant 1) and never releases the lease.
    ///
    /// `releaseForeground` is idempotent and must not throw, so `withTimeout` here
    /// only ever yields a value (success) or `nil` (timeout) — never a throw.
    private func drainOldForeground(_ call: ManagedCall, token: ForegroundOwnerToken) async -> Bool {
        let session = call.managedSession
        let completed = (try? await withTimeout(WebRtcResilience.foregroundReleaseTimeoutMs) {
            await session.releaseForeground(token)
        }) != nil
        return completed
    }

    // MARK: - Serialization + settling

    /// Race `op` against a `timeoutMs` deadline measured on the INJECTED `clock`
    /// (so a fake clock's `advance(byMs:)` deterministically trips it, and the test
    /// can wait for `pendingSleepCount > 0` before advancing). Returns `op`'s result
    /// when it finishes first, `nil` on timeout. If `op` THROWS before the deadline
    /// the error propagates out (callers wrapping a throwing op — activation — catch
    /// it; callers of a no-throw op — release — only ever see a value or `nil`).
    ///
    /// `op` and the timeout sleep run as UNSTRUCTURED tasks so the timeout can win
    /// and this function can RETURN even while `op` is still suspended on something
    /// that does not respond to cancellation (e.g. a hung coordinator's
    /// `Task<Void, Never>.value`). The loser is always cancelled: on timeout `op` is
    /// cancelled; on success the sleep is cancelled. The first arm to finish resumes
    /// the continuation; the second arm's later completion is dropped (the latch
    /// ensures resume happens exactly once).
    private func withTimeout<T: Sendable>(
        _ timeoutMs: Int,
        _ op: @escaping @MainActor () async throws -> T
    ) async throws -> T? {
        let timeoutNs = UInt64(timeoutMs) * 1_000_000
        let opTask = Task { @MainActor in try await op() }
        let timeoutTask = Task { @MainActor [clock] in
            try await clock.sleep(nanoseconds: timeoutNs)
        }
        // `@MainActor` latch so only the FIRST arm to finish resumes the
        // continuation; the loser's later completion is dropped.
        let latch = TimeoutLatch()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T?, Error>) in
                // Arm 1: the operation. On finish (value or throw) it wins the race
                // and cancels the pending timeout sleep.
                Task { @MainActor in
                    let result: Result<T, Error>
                    do {
                        result = .success(try await opTask.value)
                    } catch {
                        result = .failure(error)
                    }
                    guard latch.claim() else { return }
                    timeoutTask.cancel()
                    continuation.resume(with: result.map { Optional($0) })
                }
                // Arm 2: the timeout. If the sleep completes (was not cancelled by a
                // winning op) it returns `nil` and cancels the still-running op.
                Task { @MainActor in
                    let didTimeOut = (try? await timeoutTask.value) != nil
                    guard didTimeOut, latch.claim() else { return }
                    opTask.cancel()
                    continuation.resume(returning: nil)
                }
            }
        } onCancel: {
            opTask.cancel()
            timeoutTask.cancel()
        }
    }

    /// One-shot, main-actor-confined latch: `claim()` returns `true` for the FIRST
    /// caller and `false` thereafter, so the `withTimeout` race resumes exactly once.
    @MainActor
    private final class TimeoutLatch {
        private var claimed = false
        func claim() -> Bool {
            if claimed { return false }
            claimed = true
            return true
        }
    }

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

    // MARK: - Publishing

    private func publish() async {
        // Cache each call's snapshot as we map so an op-driven publish keeps the
        // per-call `lastSnapshot` coherent; a following snapshot-stream emit then
        // dedupes against it (no redundant republish right after an op).
        calls = managed.map { c in
            let s = c.snapshot()
            c.lastSnapshot = s
            return s
        }
    }

    private func canonicalRoomId(_ room: RoomRef) -> String? {
        let token: String?
        if let url = room.url {
            token = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        } else {
            token = room.roomId
        }
        // Reject an empty token so an invalid RoomRef fails BEFORE claiming the
        // registry mode (no leaked claim) rather than constructing a room-less
        // session. This is the validation gate the default sessionFactory relies on
        // to stay total.
        guard let token, !token.isEmpty else { return nil }
        return token
    }
}
