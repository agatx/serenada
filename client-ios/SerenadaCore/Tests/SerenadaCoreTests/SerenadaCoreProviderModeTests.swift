@testable import SerenadaCore
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
    /// mask. Binds the provider in ``V1ProviderRegistry`` exactly as
    /// `SerenadaCore.join()` does.
    private func makeBoundSession(
        roomId: String,
        provider: FakeSignalingProvider
    ) -> SerenadaSession {
        var config = SerenadaConfig(signalingProvider: provider)
        config.audioCoordinator = FakeAudioCoordinator()
        let session = SerenadaSession(
            roomId: roomId,
            config: config,
            initialSignalingProvider: provider,
            audioController: FakeAudioController(),
            mediaEngine: FakeMediaEngine(),
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true }
        )
        V1ProviderRegistry.bind(provider, to: session)
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

    func testRegistryCompactsTerminalBindings() {
        // Fill the registry with several LIVE v1 bindings, then let every session
        // reach a terminal phase. The next bind/check must sweep them all so the
        // process-wide map does not grow without bound for hosts that cycle
        // through a fresh provider + core per call.
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

        // leave() is synchronous and terminal (.idle), like the sequential-reuse test.
        for session in sessions { session.leave() }

        // A single check compacts every terminal key back to the baseline.
        _ = V1ProviderRegistry.isInUse(FakeSignalingProvider())
        XCTAssertEqual(V1ProviderRegistry.rawBindingCountForTesting(), baseline,
                       "compaction swept the terminal bindings")
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
