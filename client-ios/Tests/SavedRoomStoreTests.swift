import XCTest
@testable import SerenadaiOS

final class SavedRoomStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var store: SavedRoomStore!

    override func setUp() {
        super.setUp()
        suiteName = "SavedRoomStoreTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: defaultsSuiteName)
        store = SavedRoomStore(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: defaultsSuiteName)
        defaults = nil
        suiteName = nil
        store = nil
        super.tearDown()
    }

    private var defaultsSuiteName: String {
        suiteName ?? ""
    }

    func testSaveDedupesAndMovesRoomToTop() {
        let r1 = String(repeating: "A", count: 27)
        let r2 = String(repeating: "B", count: 27)

        store.saveRoom(SavedRoom(roomId: r1, name: "Alpha", createdAt: 1, host: nil, lastJoinedAt: nil))
        store.saveRoom(SavedRoom(roomId: r2, name: "Beta", createdAt: 2, host: nil, lastJoinedAt: nil))
        store.saveRoom(SavedRoom(roomId: r1, name: "Alpha 2", createdAt: 3, host: nil, lastJoinedAt: nil))

        let rooms = store.getSavedRooms()
        XCTAssertEqual(rooms.count, 2)
        XCTAssertEqual(rooms[0].roomId, r1)
        XCTAssertEqual(rooms[0].name, "Alpha 2")
        XCTAssertEqual(rooms[1].roomId, r2)
    }

    func testMarkRoomJoinedSetsLastJoinedAt() {
        let roomId = String(repeating: "C", count: 27)
        store.saveRoom(SavedRoom(roomId: roomId, name: "Gamma", createdAt: 1, host: nil, lastJoinedAt: nil))

        let changed = store.markRoomJoined(roomId: roomId, joinedAt: 12345)
        XCTAssertTrue(changed)

        let room = store.getSavedRooms().first { $0.roomId == roomId }
        XCTAssertEqual(room?.lastJoinedAt, 12345)
    }

    func testHostNormalization() {
        let roomId = String(repeating: "D", count: 27)
        store.saveRoom(SavedRoom(roomId: roomId, name: "Delta", createdAt: 1, host: "HTTPS://Example.com:444/", lastJoinedAt: nil))

        let room = store.getSavedRooms().first { $0.roomId == roomId }
        XCTAssertEqual(room?.host, "example.com:444")
    }
}
