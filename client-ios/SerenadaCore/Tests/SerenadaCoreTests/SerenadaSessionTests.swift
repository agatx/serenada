import SerenadaCore
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
            serverHost: "serenada.app",
            config: SerenadaConfig(serverHost: "serenada.app")
        )

        XCTAssertEqual(session.state.phase, .joining)
        XCTAssertEqual(session.state.roomId, roomId)

        session.cancelJoin()
    }
}
