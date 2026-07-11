@testable import SerenadaCore
import Combine
import XCTest

@MainActor
final class SerenadaCoreProviderModeTests: XCTestCase {
    private static let exactlyOneSourceMessage =
        "Provide exactly one of serverHost, signalingProvider, or multiSessionSignalingProvider"

    func testMissingServerHostAndProviderIsRejected() {
        XCTAssertThrowsError(try resolveSerenadaConfig(SerenadaConfig())) { error in
            XCTAssertEqual(error.localizedDescription, Self.exactlyOneSourceMessage)
        }
    }

    func testServerHostAndProviderTogetherAreRejected() {
        XCTAssertThrowsError(
            try resolveSerenadaConfig(
                SerenadaConfig(
                    serverHost: "serenada.app",
                    signalingProvider: FakeSignalingProvider()
                )
            )
        ) { error in
            XCTAssertEqual(error.localizedDescription, Self.exactlyOneSourceMessage)
        }
    }

    func testV1AndMultiSessionProviderTogetherAreRejected() {
        XCTAssertThrowsError(
            try resolveSerenadaConfig(
                SerenadaConfig(
                    signalingProvider: FakeSignalingProvider(),
                    multiSessionSignalingProvider: FakeMultiSessionSignalingProvider()
                )
            )
        ) { error in
            XCTAssertEqual(error.localizedDescription, Self.exactlyOneSourceMessage)
        }
    }

    func testUnsupportedSignalingProviderVersionIsRejected() {
        XCTAssertThrowsError(
            try resolveSerenadaConfig(SerenadaConfig(signalingProvider: FakeSignalingProvider(version: 2)))
        ) { error in
            XCTAssertEqual(error.localizedDescription, "Unsupported signalingProvider version: 2")
        }
    }

    func testUnsupportedMultiSessionProviderVersionIsRejected() {
        XCTAssertThrowsError(
            try resolveSerenadaConfig(
                SerenadaConfig(multiSessionSignalingProvider: FakeMultiSessionSignalingProvider(version: 1))
            )
        ) { error in
            XCTAssertEqual(error.localizedDescription, "Unsupported multiSessionSignalingProvider version: 1")
        }
    }

    func testMultiSessionProviderModeSessionExposesNilServerHostAndRoomUrl() {
        let core = SerenadaCore(config: SerenadaConfig(multiSessionSignalingProvider: FakeMultiSessionSignalingProvider()))
        let session = core.join(roomId: "room-123")

        XCTAssertEqual(session.roomId, "room-123")
        XCTAssertNil(session.roomUrl)
        XCTAssertNil(session.serverHost)

        session.cancelJoin()
    }

    // MARK: - v1 single-session liveness guard (F2)

    func testDirectJoinReusesV1ProviderSequentially() {
        let provider = FakeSignalingProvider()
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let first = core.join(roomId: "room-1")
        XCTAssertNil(first.state.error, "the first direct join binds the v1 provider")
        first.leave()

        // Sequential reuse: once the first session is terminal, a fresh join may
        // bind the same v1 provider again.
        let second = core.join(roomId: "room-2")
        XCTAssertNil(second.state.error, "a sequential reuse of the v1 provider must succeed")
        second.leave()
    }

    func testConcurrentDirectJoinOnV1ProviderFailsWithProviderUnavailable() async {
        let provider = FakeSignalingProvider()
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let first = core.join(roomId: "room-1")
        let second = core.join(roomId: "room-2")

        // The doomed join reports its error on the next main-actor turn.
        await Task.yield()
        await Task.yield()

        XCTAssertNil(first.state.error, "the first (live) session is unaffected")
        XCTAssertEqual(second.state.error, .providerUnavailable,
                       "a second concurrent join on a single-session v1 provider fails typed")
        XCTAssertEqual(second.state.phase, .error)

        first.leave()
        second.leave()
    }

    func testConcurrentJoinAcrossCoresSharingOneV1ProviderFailsWithProviderUnavailable() async {
        // The v1 liveness guard is keyed by provider OBJECT identity process-wide,
        // so two independent cores sharing the same provider object cannot both bind
        // it (which would cross-talk the single `delegate` slot).
        let provider = FakeSignalingProvider()
        let coreA = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))
        let coreB = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let first = coreA.join(roomId: "room-1")
        let second = coreB.join(roomId: "room-2")

        // The doomed join reports its error on the next main-actor turn.
        await Task.yield()
        await Task.yield()

        XCTAssertNil(first.state.error, "the first (live) session is unaffected")
        XCTAssertEqual(second.state.error, .providerUnavailable,
                       "a second core sharing the same v1 provider object fails typed")
        XCTAssertEqual(second.state.phase, .error)

        first.leave()
        second.leave()
    }

    func testSecondCoreReusesV1ProviderOnceFirstSessionIsTerminal() {
        let provider = FakeSignalingProvider()
        let coreA = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))
        let coreB = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let first = coreA.join(roomId: "room-1")
        XCTAssertNil(first.state.error, "the first core binds the shared v1 provider")
        first.leave()

        // Once the first session is terminal the binding is released, so a different
        // core may bind the same provider object.
        let second = coreB.join(roomId: "room-2")
        XCTAssertNil(second.state.error, "a second core reuses the freed v1 provider")
        second.leave()
    }

    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    /// Build a registry-bound session on the shared v1 `provider` with fake
    /// media/audio and granted capabilities, so the join settles past the
    /// permission gate in the simulator (mirrors SessionTestHarness wiring)
    /// and can reach a REAL terminal phase instead of the awaitingPermissions
    /// mask. Wraps the provider in a ``V1LivenessChannel`` and binds that channel
    /// in ``V1ProviderRegistry`` exactly as `SerenadaCore.join()` does, so the
    /// session drives the shared provider through the same fence.
    private func makeBoundSession(
        roomId: String,
        provider: FakeSignalingProvider
    ) -> SerenadaSession {
        var config = SerenadaConfig(signalingProvider: provider)
        config.audioCoordinator = FakeAudioCoordinator()
        let channel = V1LivenessChannel(underlying: provider)
        let session = SerenadaSession(
            roomId: roomId,
            config: config,
            initialSignalingProvider: channel,
            audioController: FakeAudioController(),
            mediaEngine: FakeMediaEngine(),
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true }
        )
        V1ProviderRegistry.bind(provider, to: channel)
        return session
    }

    private func advancePastPermissions(_ session: SerenadaSession) async {
        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
    }

    func testTerminalSessionLateLeaveDoesNotClobberRebinder() async {
        // A terminal session's handle stays callable. Once a NEWER session rebinds
        // the same shared v1 provider, a late leave() on the old handle must NOT
        // disconnect the channel or clear the delegate the newer session installed.
        let provider = FakeSignalingProvider()

        let sessionA = makeBoundSession(roomId: "room-a", provider: provider)
        await advancePastPermissions(sessionA)

        // Drive A to a terminal .error while it owns the bind. A's own teardown
        // disconnects the channel once (expected) and frees the bind.
        provider.simulateConnected()
        provider.simulateError(code: "ROOM_CAPACITY_UNSUPPORTED", message: "Room is full")
        await yieldToMainActor()
        XCTAssertEqual(sessionA.state.phase, .error)
        XCTAssertEqual(provider.disconnectCalls, 1, "A's own error teardown disconnects once")

        // B binds the same provider now that A is terminal, and installs its delegate.
        XCTAssertFalse(V1ProviderRegistry.isInUse(provider), "terminal A no longer holds the bind")
        let sessionB = makeBoundSession(roomId: "room-b", provider: provider)
        XCTAssertNotNil(provider.delegate, "B installed its delegate on the shared provider")

        let disconnectsBeforeLateLeave = provider.disconnectCalls
        let delegateBeforeLateLeave = provider.delegate

        // A's stale handle is still callable — a late leave() must be a no-op on
        // the shared provider that B now owns.
        sessionA.leave()

        XCTAssertEqual(provider.disconnectCalls, disconnectsBeforeLateLeave,
                       "A's late leave must not disconnect the provider B owns")
        XCTAssertTrue(provider.delegate === delegateBeforeLateLeave,
                      "A's late leave must not clear B's delegate")
        XCTAssertNil(sessionB.state.error, "B is unaffected by A's late leave")

        // Double-leave stays safe.
        sessionA.leave()
        XCTAssertEqual(provider.disconnectCalls, disconnectsBeforeLateLeave)
        XCTAssertTrue(provider.delegate === delegateBeforeLateLeave)

        // B still owns the bind, so B's teardown DOES disconnect the channel.
        sessionB.leave()
        XCTAssertEqual(provider.disconnectCalls, disconnectsBeforeLateLeave + 1,
                       "the bind owner's teardown disconnects the provider")
    }

    func testSynchronousRebindFromEndingObserverIsFencedUntilTeardownCompletes() async {
        // Finding 1: cleanupCall publishes `.ending` BEFORE resetResources runs.
        // A host reacting to that phase and immediately starting a new call on the
        // same shared v1 provider must NOT rebind mid-teardown — the bind is held
        // until A's channel retires at the END of teardown. So the synchronous
        // rebind fails cleanly with `.providerUnavailable` (acceptable per
        // contract), and A's own teardown still disconnects its transport exactly
        // once (no stale transport left alive under a new owner).
        let provider = FakeSignalingProvider()
        let sessionA = makeBoundSession(roomId: "room-a", provider: provider)
        await advancePastPermissions(sessionA)
        provider.simulateConnected()
        await yieldToMainActor()

        let coreB = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))
        var rebindResult: SerenadaSession?
        var isInUseAtEnding: Bool?
        var cancellable: AnyCancellable?
        cancellable = sessionA.$state.sink { newState in
            // React exactly once, synchronously, to the `.ending` transition —
            // mirroring a host that starts a new call from a phase observer.
            guard newState.phase == .ending, rebindResult == nil else { return }
            isInUseAtEnding = V1ProviderRegistry.isInUse(provider)
            rebindResult = coreB.join(roomId: "room-b")
        }

        // Remote end drives A through cleanupCall(.ending) -> resetResources.
        provider.simulateRoomEnded()
        // The rebind session surfaces its startup error on a later main-actor
        // turn; poll until it settles.
        for _ in 0..<100 where rebindResult?.state.error == nil {
            await Task.yield()
        }
        cancellable?.cancel()

        XCTAssertEqual(isInUseAtEnding, true,
                       "A still owns the bind while it is only `.ending` (pre-teardown)")
        XCTAssertNotNil(rebindResult, "the observer attempted a synchronous rebind")
        XCTAssertEqual(rebindResult?.state.error, .providerUnavailable,
                       "a synchronous rebind during A's teardown is fenced, not silently cross-wired")
        XCTAssertEqual(provider.disconnectCalls, 1,
                       "A's own teardown disconnects its transport exactly once")

        rebindResult?.leave()
        sessionA.leave()
    }

    func testTerminalSessionProviderOpsAreNoOpsAfterRelease() async {
        // Finding 2: after A releases its bind at the end of teardown, A's handle
        // stays callable. Stale public ops that would mutate the SHARED provider —
        // end() -> endRoom(), resumeJoin() -> connect()/joinRoom() — must be fenced
        // so they never touch the provider a newer session B now owns.
        let provider = FakeSignalingProvider()
        let sessionA = makeBoundSession(roomId: "room-a", provider: provider)
        await advancePastPermissions(sessionA)
        provider.simulateConnected()

        // Drive A to a terminal .error while it owns the bind; its teardown
        // disconnects once and releases the bind.
        provider.simulateError(code: "ROOM_CAPACITY_UNSUPPORTED", message: "Room is full")
        await yieldToMainActor()
        XCTAssertEqual(sessionA.state.phase, .error)
        XCTAssertFalse(V1ProviderRegistry.isInUse(provider), "terminal A released the bind")

        // B binds the now-free provider and installs its delegate.
        let sessionB = makeBoundSession(roomId: "room-b", provider: provider)
        await advancePastPermissions(sessionB)
        XCTAssertTrue(V1ProviderRegistry.isInUse(provider), "B owns the shared provider")

        let delegateAfterB = provider.delegate
        let endCallsBaseline = provider.endCalls
        let connectCallsBaseline = provider.connectCalls
        let joinCallsBaseline = provider.joinCalls.count
        let leaveCallsBaseline = provider.leaveCalls
        let disconnectBaseline = provider.disconnectCalls

        // Stale A ops: end() forwards endRoom(); resumeJoin() reschedules the join
        // (connect/joinRoom). Both must be suppressed by A's retired channel.
        sessionA.end()
        sessionA.resumeJoin()
        await yieldToMainActor()

        XCTAssertEqual(provider.endCalls, endCallsBaseline,
                       "stale end() must not endRoom the provider B owns")
        XCTAssertEqual(provider.connectCalls, connectCallsBaseline,
                       "stale resumeJoin() must not connect the provider B owns")
        XCTAssertEqual(provider.joinCalls.count, joinCallsBaseline,
                       "stale resumeJoin() must not joinRoom on the provider B owns")
        XCTAssertEqual(provider.leaveCalls, leaveCallsBaseline,
                       "stale ops must not leaveRoom the provider B owns")
        XCTAssertEqual(provider.disconnectCalls, disconnectBaseline,
                       "stale ops must not disconnect B's transport")
        XCTAssertTrue(provider.delegate === delegateAfterB,
                      "stale A must not clear B's delegate")
        XCTAssertNil(sessionB.state.error, "B is unaffected by A's stale ops")

        sessionA.leave()
        sessionB.leave()
    }

    func testRegistryReleasesBindingsOnTeardown() {
        // Fill the registry with several LIVE v1 bindings, then tear every session
        // down. Each session's liveness channel releases its own bind on teardown,
        // so the process-wide map returns to baseline and never grows without
        // bound for hosts that cycle through a fresh provider + core per call.
        _ = V1ProviderRegistry.isInUse(FakeSignalingProvider())
        let baseline = V1ProviderRegistry.rawBindingCountForTesting()

        var sessions: [SerenadaSession] = []
        for i in 0..<5 {
            let provider = FakeSignalingProvider()
            let core = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))
            sessions.append(core.join(roomId: "room-\(i)"))
        }
        XCTAssertEqual(V1ProviderRegistry.rawBindingCountForTesting(), baseline + 5,
                       "live sessions are retained in the registry")

        // leave() is synchronous and terminal; each channel's disconnect retires
        // and releases its bind.
        for session in sessions { session.leave() }

        _ = V1ProviderRegistry.isInUse(FakeSignalingProvider())
        XCTAssertEqual(V1ProviderRegistry.rawBindingCountForTesting(), baseline,
                       "each terminal session released its own bind")
    }

    func testProviderModeSessionExposesNilServerHostAndRoomUrl() {
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: FakeSignalingProvider()))
        let session = core.join(roomId: "room-123")

        XCTAssertEqual(session.roomId, "room-123")
        XCTAssertNil(session.roomUrl)
        XCTAssertNil(session.serverHost)

        session.cancelJoin()
    }

    func testCreateRoomIdRequiresServerHostInProviderMode() async {
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: FakeSignalingProvider()))

        do {
            _ = try await core.createRoomId()
            XCTFail("Expected createRoomId() to fail without serverHost")
        } catch {
            XCTAssertEqual(error.localizedDescription, "requires serverHost")
        }
    }

    func testBuiltInServerProviderOwnsReconnectHandling() {
        let provider = SerenadaServerProvider(
            serverHost: "serenada.app",
            apiClient: FakeAPIClient()
        )

        XCTAssertTrue(provider.capabilities.handlesReconnection)
        provider.disconnect()
    }
}
