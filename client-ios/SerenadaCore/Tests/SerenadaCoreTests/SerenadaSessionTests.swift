@testable import SerenadaCore
import XCTest

@MainActor
final class SerenadaSessionTests: XCTestCase {
    func testJoinUrlUsesDeepLinkHostInsteadOfDefaultConfigHost() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let url = URL(string: "https://serenada-app.ru/call/\(roomId)")!
        let core = SerenadaCore(config: SerenadaConfig(serverHost: "serenada.app"))

        let session = core.join(url: url)

        XCTAssertEqual(session.serverHost, "serenada-app.ru")
        XCTAssertEqual(session.roomUrl, url)

        session.cancelJoin()
    }

    func testJoinRoomBuildsRoomUrlWithLocalPort() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let core = SerenadaCore(config: SerenadaConfig(serverHost: "localhost:8080"))

        let session = core.join(roomId: roomId)

        XCTAssertEqual(session.roomUrl?.absoluteString, "http://localhost:8080/call/\(roomId)")
        XCTAssertEqual(session.serverHost, "localhost:8080")

        session.cancelJoin()
    }

    func testSessionStartsInJoiningPhaseBeforeAsyncJoinBegins() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let session = SerenadaSession(
            roomId: roomId,
            config: SerenadaConfig(serverHost: "serenada.app")
        )

        XCTAssertEqual(session.state.phase, .joining)
        XCTAssertEqual(session.state.roomId, roomId)

        session.cancelJoin()
    }

    func testProviderModeJoinByRoomIdHasNoRoomUrl() {
        let provider = FakeSignalingProvider()
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let session = core.join(roomId: "provider-room")

        XCTAssertNil(session.roomUrl)
        session.cancelJoin()
    }

    func testDefaultVideoDisabledDoesNotRequireCameraBeforeJoin() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                defaultVideoEnabled: false,
                cameraModes: [.selfie, .world]
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await Task.yield()
        await Task.yield()
        await Task.yield()

        if session.state.phase == .awaitingPermissions {
            XCTAssertFalse(session.state.requiredPermissions?.contains(.camera) ?? false)
            session.resumeJoin()
            await Task.yield()
            await Task.yield()
        }

        XCTAssertEqual(media.startLocalMediaCalls.first, false)
        session.cancelJoin()
    }

    func testAdjustCameraZoomAllowedWhileWaitingWithContentCamera() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                cameraModes: [.world]
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
        provider.simulateConnected()
        provider.simulateJoined(
            peerId: "local-cid-1",
            participants: [SignalingProviderParticipant(peerId: "local-cid-1", joinedAt: 1)],
            hostPeerId: "local-cid-1"
        )
        await yieldToMainActor()

        XCTAssertEqual(session.state.phase, .waiting)
        XCTAssertEqual(session.state.localParticipant.cameraMode, .world)
        XCTAssertEqual(session.adjustCameraZoom(by: 1.2), 1.25)
        XCTAssertEqual(media.adjustCaptureZoomCalls, [1.2])
        session.cancelJoin()
    }

    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }
}
