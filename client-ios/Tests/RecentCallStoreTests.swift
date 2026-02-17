import XCTest
@testable import SerenadaiOS

final class RecentCallStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var store: RecentCallStore!

    override func setUp() {
        super.setUp()
        suiteName = "RecentCallStoreTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: defaultsSuiteName)
        store = RecentCallStore(defaults: defaults)
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

    func testSaveDedupesAndLimitsToThree() {
        let r1 = String(repeating: "A", count: 27)
        let r2 = String(repeating: "B", count: 27)
        let r3 = String(repeating: "C", count: 27)
        let r4 = String(repeating: "D", count: 27)

        store.saveCall(RecentCall(roomId: r1, startTime: 1000, durationSeconds: 1))
        store.saveCall(RecentCall(roomId: r2, startTime: 2000, durationSeconds: 2))
        store.saveCall(RecentCall(roomId: r3, startTime: 3000, durationSeconds: 3))
        store.saveCall(RecentCall(roomId: r2, startTime: 4000, durationSeconds: 4))
        store.saveCall(RecentCall(roomId: r4, startTime: 5000, durationSeconds: 5))

        let calls = store.getRecentCalls()
        XCTAssertEqual(calls.count, 3)
        XCTAssertEqual(calls[0].roomId, r4)
        XCTAssertEqual(calls[1].roomId, r2)
        XCTAssertEqual(calls[2].roomId, r3)
    }

    func testInvalidRoomIdIsRejected() {
        store.saveCall(RecentCall(roomId: "bad", startTime: 1000, durationSeconds: 1))
        XCTAssertTrue(store.getRecentCalls().isEmpty)
    }
}
