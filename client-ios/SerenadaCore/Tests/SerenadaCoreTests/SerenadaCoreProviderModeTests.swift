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
