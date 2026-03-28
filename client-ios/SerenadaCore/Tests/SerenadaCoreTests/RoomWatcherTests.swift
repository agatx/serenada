@testable import SerenadaCore
import XCTest

@MainActor
final class RoomWatcherTests: XCTestCase {
    func testWatchRoomsRequiresServerHost() {
        let watcher = RoomWatcher()

        XCTAssertThrowsError(try watcher.watchRooms(roomIds: ["room-1"], host: nil)) { error in
            XCTAssertEqual(error.localizedDescription, "requires serverHost")
        }
    }
}
