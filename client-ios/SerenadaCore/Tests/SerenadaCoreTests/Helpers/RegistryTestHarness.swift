import Combine
import Foundation
@testable import SerenadaCore

// MARK: - Shared ordered event log

/// Ordered, cross-coordinator event log so a switch test can assert
/// "deactivate-old strictly before activate-new" across TWO different sessions'
/// coordinators (the per-coordinator count-only `FakeAudioCoordinator` cannot
/// express ordering between distinct instances).
@MainActor
final class EventLog {
    private(set) var events: [String] = []
    func append(_ event: String) { events.append(event) }
    func clear() { events.removeAll() }
}

// MARK: - Recording audio coordinator

/// Audio coordinator that records tagged activate/deactivate events into a shared
/// ``EventLog`` so registry tests can assert ordering across sessions. Activation
/// is immediate (no block); for blocking variants see `GatedAudioCoordinator`
/// (activation) and `BlockingDeactivateCoordinator` (deactivation).
@MainActor
final class RecordingAudioCoordinator: SerenadaAudioCoordinator, @unchecked Sendable {
    private let tag: String
    private let log: EventLog
    private(set) var activateEvents = 0
    private(set) var deactivateEvents = 0

    init(tag: String, log: EventLog) {
        self.tag = tag
        self.log = log
    }

    func activateCallSession(intent: AudioIntent) async throws {
        activateEvents += 1
        log.append("\(tag):activate")
    }

    func deactivateCallSession() async {
        deactivateEvents += 1
        log.append("\(tag):deactivate")
    }

    func applyRouting(_ device: AudioDevice) async throws {}
    func setMicMuted(_ muted: Bool) async throws {}

    var availableDevices: AsyncStream<[AudioDevice]> { AsyncStream { _ in } }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var events: AsyncStream<AudioCoordinatorEvent> { AsyncStream { _ in } }
}

/// Audio coordinator whose `deactivateCallSession` can be PAUSED so a test can
/// drive the registry's old-release timeout (the old call's drain never confirms
/// fully-held). Activation is immediate.
@MainActor
final class BlockingDeactivateCoordinator: SerenadaAudioCoordinator, @unchecked Sendable {
    var blockNextDeactivation = false
    private(set) var deactivationInFlight = false
    private var continuation: CheckedContinuation<Void, Never>?

    func activateCallSession(intent: AudioIntent) async throws {}

    func deactivateCallSession() async {
        guard blockNextDeactivation else { return }
        blockNextDeactivation = false
        deactivationInFlight = true
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            self.continuation = c
        }
        deactivationInFlight = false
    }

    func releaseDeactivation() {
        let c = continuation
        continuation = nil
        c?.resume()
    }

    func applyRouting(_ device: AudioDevice) async throws {}
    func setMicMuted(_ muted: Bool) async throws {}

    var availableDevices: AsyncStream<[AudioDevice]> { AsyncStream { _ in } }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var events: AsyncStream<AudioCoordinatorEvent> { AsyncStream { _ in } }
}

// MARK: - Auto-joining signaling provider

/// Signaling provider that drives a held room join to completion on its own: on
/// `connect()` it fires `didConnect`, and on `joinRoom()` it fires `didJoin` with
/// the configured participants (deferred through `Task { @MainActor }` to match
/// the real delegate-proxy hop). When `autoJoin == false` it never fires
/// `didJoin`, so the held join times out (used by the failed-room-join test).
final class AutoJoinSignalingProvider: SignalingProvider, @unchecked Sendable {
    let version: Int = SUPPORTED_SIGNALING_PROVIDER_VERSION
    let capabilities = ProviderCapabilities(handlesReconnection: false)
    weak var delegate: SignalingProviderDelegate?

    private let localCid: String
    private let remoteCid: String?
    private let autoJoin: Bool

    init(localCid: String, remoteCid: String?, autoJoin: Bool) {
        self.localCid = localCid
        self.remoteCid = remoteCid
        self.autoJoin = autoJoin
    }

    func connect() {
        let delegate = self.delegate
        Task { @MainActor in
            delegate?.signalingProviderDidConnect(ConnectionInfo(transport: "ws"))
        }
    }

    func disconnect() {}

    func joinRoom(_ roomId: String, options: JoinOptions) {
        guard autoJoin else { return }
        let delegate = self.delegate
        let localCid = self.localCid
        let remoteCid = self.remoteCid
        Task { @MainActor in
            var participants = [SignalingProviderParticipant(peerId: localCid, joinedAt: 1)]
            if let remoteCid {
                participants.append(SignalingProviderParticipant(peerId: remoteCid, joinedAt: 2))
            }
            delegate?.signalingProviderDidJoin(
                JoinedEvent(
                    peerId: localCid,
                    participants: participants,
                    hostPeerId: localCid,
                    maxParticipants: 4
                )
            )
        }
    }

    func leaveRoom() {}
    func endRoom() {}
    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {}
    func broadcast(type: String, payload: SignalingPayload?) {}
    func getIceServers() async throws -> [IceServerConfig] { [] }
}

// MARK: - Stall-release session stub

/// A `RegistryManagedSession` stub whose `releaseForeground` can be made to NEVER
/// confirm fully-held (`stallRelease = true`), so a test can drive the registry's
/// old-release timeout (Core Invariant 1). The real iOS session confirms held
/// synchronously and so cannot reach that path. Otherwise it behaves like a
/// minimal foreground-capable session: it reports `.inCall`, activates to
/// `.active`, and holds to `.held`/`.inactive`.
@MainActor
final class StallReleaseSession: RegistryManagedSession {
    let roomId: String
    let roomUrl: URL?
    private(set) var mediaRole: CallMediaRole = .held
    private(set) var mediaActivationState: MediaActivationState = .inactive
    var registryMembershipPhase: SerenadaCallPhase = .inCall
    var registryErrorDescription: String? { nil }
    var registryLocalCid: String? { "stub-local" }
    var registryParticipantCount: Int { 2 }
    var registryDesiredAudioEnabled: Bool = true
    var registryDesiredVideoMode: LocalCameraMode? = nil
    var registryActualAudioPublished: Bool { mediaRole == .foreground }
    var registryActualVideoPublished: Bool { false }
    var registryQualitySummary: CallQualitySummary? { nil }

    /// When true, `releaseForeground` does NOT drive the session to fully-held, so
    /// the registry's drain-settle never confirms and its release timeout trips.
    var stallRelease = false
    private(set) var releaseForegroundCalls = 0
    private(set) var token: ForegroundOwnerToken?

    init(roomId: String, roomUrl: URL? = nil) {
        self.roomId = roomId
        self.roomUrl = roomUrl
    }

    func preflightForeground() -> ForegroundPreflight { .ok }
    func missingDesiredForegroundPermissions() -> [MediaCapability] { [] }

    func activateForeground(_ token: ForegroundOwnerToken, generation: Int) throws {
        self.token = token
        mediaRole = .foreground
        mediaActivationState = .active
    }

    func releaseForeground(_ token: ForegroundOwnerToken) {
        releaseForegroundCalls += 1
        guard !stallRelease else {
            // Model a release that has begun but not confirmed fully-held.
            mediaActivationState = .activating
            return
        }
        mediaRole = .held
        mediaActivationState = .inactive
    }

    func awaitForegroundReleaseSettled() async {}

    func abortForegroundActivation(_ token: ForegroundOwnerToken) {
        mediaRole = .held
        mediaActivationState = .inactive
    }

    func registryLeave() {
        mediaRole = .held
        mediaActivationState = .inactive
        registryMembershipPhase = .idle
    }

    func registryEnd() { registryLeave() }
}

// MARK: - Registry harness

/// Builds a ``SerenadaCallRegistry`` whose session factory creates sessions wired
/// with per-call fakes (auto-joining signaling, a recording/blocking coordinator,
/// fake media/audio/clock), recording each session + coordinator so a test can
/// inspect and assert. Each harness owns a DEDICATED `ForegroundMediaArbiter` for
/// isolation (no `.shared` cross-talk between cases).
@MainActor
final class RegistryTestHarness {
    let arbiter: ForegroundMediaArbiter
    let eventLog = EventLog()
    /// The registry runs against this fake clock so timeout tests drive deadlines
    /// via `advance(byMs:)`; normal ops settle in `awaitSettle`'s yield-only phase.
    let fakeRegistryClock = FakeSessionClock()
    private(set) var registry: SerenadaCallRegistry!

    /// Per-room build options, looked up by canonical roomId when the registry's
    /// factory is invoked.
    private struct RoomOptions {
        var coordinator: SerenadaAudioCoordinator?
        var defaultAudioEnabled: Bool
        var grant: (MediaCapability) -> Bool
        var autoJoin: Bool
        /// When set, the factory returns this stub instead of a real session — for
        /// paths the real (synchronous-release) session cannot reach.
        var stub: RegistryManagedSession?
    }
    private var roomOptions: [String: RoomOptions] = [:]
    /// The session created per canonical roomId (so `session(for:)` resolves it).
    private var sessionsByRoom: [String: SerenadaSession] = [:]
    /// The coordinator created per canonical roomId.
    private var coordinatorsByRoom: [String: SerenadaAudioCoordinator] = [:]

    init(arbiter: ForegroundMediaArbiter? = nil) {
        // A caller-supplied dedicated arbiter is used as-is; otherwise mint a fresh
        // one so this harness never touches the process singleton.
        self.arbiter = arbiter ?? ForegroundMediaArbiter()
        let dedicatedArbiter = self.arbiter
        // Build a throwaway core only to satisfy the registry's `core` dependency;
        // the injected factory never calls `core.makeManagedSession`, so the core's
        // own signaling config is irrelevant.
        let core = SerenadaCore(config: SerenadaConfig(serverHost: "serenada.app"))
        self.registry = SerenadaCallRegistry(
            core: core,
            arbiter: dedicatedArbiter,
            clock: fakeRegistryClock,
            sessionFactory: { [weak self] room, role in
                self!.makeSession(room: room, role: role, arbiter: dedicatedArbiter)
            }
        )
    }

    /// A `RoomRef` for a 27-char room token, recording per-room build options.
    func room(
        _ token: String,
        coordinator: SerenadaAudioCoordinator? = nil,
        defaultAudioEnabled: Bool = true,
        grant: @escaping (MediaCapability) -> Bool = { _ in true },
        autoJoin: Bool = true,
        stub: RegistryManagedSession? = nil
    ) -> RoomRef {
        roomOptions[token] = RoomOptions(
            coordinator: coordinator,
            defaultAudioEnabled: defaultAudioEnabled,
            grant: grant,
            autoJoin: autoJoin,
            stub: stub
        )
        return RoomRef(url: URL(string: "https://serenada.app/call/\(token)")!)
    }

    func session(for id: CallId) -> SerenadaSession? {
        registry.call(id: id)?.session
    }

    func roomId(of id: CallId) -> String {
        registry.call(id: id)?.roomId ?? ""
    }

    func coordinator(for id: CallId) -> RecordingAudioCoordinator? {
        guard let roomId = registry.call(id: id)?.roomId else { return nil }
        return coordinatorsByRoom[roomId] as? RecordingAudioCoordinator
    }

    private func makeSession(
        room: RoomRef,
        role: CallMediaRole,
        arbiter: ForegroundMediaArbiter
    ) -> RegistryManagedSession {
        let url = room.url!
        let roomId = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        let opts = roomOptions[roomId] ?? RoomOptions(
            coordinator: nil, defaultAudioEnabled: true, grant: { _ in true }, autoJoin: true, stub: nil
        )
        if let stub = opts.stub { return stub }
        let localCid = "local-\(roomId.prefix(4))"
        let remoteCid = opts.autoJoin ? "remote-\(roomId.prefix(4))" : nil
        let provider = AutoJoinSignalingProvider(localCid: localCid, remoteCid: remoteCid, autoJoin: opts.autoJoin)
        let coordinator = opts.coordinator ?? RecordingAudioCoordinator(tag: roomId, log: eventLog)
        coordinatorsByRoom[roomId] = coordinator

        let config = SerenadaConfig(
            signalingProvider: provider,
            defaultAudioEnabled: opts.defaultAudioEnabled,
            defaultVideoEnabled: false,
            audioCoordinator: coordinator
        )
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            config: config,
            initialSignalingProvider: provider,
            audioController: FakeAudioController(),
            mediaEngine: FakeMediaEngine(),
            clock: FakeSessionClock(),
            initialMediaRole: role,
            acquireForegroundLease: false,
            isCapabilityGranted: opts.grant,
            foregroundArbiter: arbiter
        )
        sessionsByRoom[roomId] = session
        return session
    }

    func teardown() async {
        // Leave every non-ended call so each session releases resources and the
        // dedicated arbiter ends clean (no leaked lease/mode).
        for state in registry.calls where state.membershipPhase != .idle {
            await registry.leaveCall(id: state.id)
        }
        arbiter.resetForTests()
        ForegroundMediaArbiter.shared.resetForTests()
    }
}
