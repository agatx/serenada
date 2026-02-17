import XCTest
@testable import SerenadaiOS

final class RoomStatusesTests: XCTestCase {
    func testMergeStatusesPayload() {
        let previous = ["A": 1]
        let payload: JSONValue = .object([
            "A": .number(2),
            "B": .number(3)
        ])

        let merged = RoomStatuses.mergeStatusesPayload(previous: previous, payload: payload)
        XCTAssertEqual(merged["A"], 2)
        XCTAssertEqual(merged["B"], 3)
    }

    func testMergeStatusUpdatePayload() {
        let previous = ["A": 1]
        let payload: JSONValue = .object([
            "rid": .string("A"),
            "count": .number(5)
        ])

        let merged = RoomStatuses.mergeStatusUpdatePayload(previous: previous, payload: payload)
        XCTAssertEqual(merged["A"], 5)
    }
}
