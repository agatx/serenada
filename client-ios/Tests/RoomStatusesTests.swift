import XCTest
@testable import SerenadaiOS

final class RoomStatusesTests: XCTestCase {
    func testMergeStatusesPayloadWithLegacyNumericCounts() {
        let previous = ["A": RoomStatus(count: 1, maxParticipants: nil)]
        let payload: JSONValue = .object([
            "A": .number(2),
            "B": .number(3)
        ])

        let merged = RoomStatuses.mergeStatusesPayload(previous: previous, payload: payload)
        XCTAssertEqual(merged["A"], RoomStatus(count: 2, maxParticipants: nil))
        XCTAssertEqual(merged["B"], RoomStatus(count: 3, maxParticipants: nil))
    }

    func testMergeStatusesPayloadWithNestedStatusObjects() {
        let previous = ["A": RoomStatus(count: 1, maxParticipants: nil)]
        let payload: JSONValue = .object([
            "A": .object([
                "count": .number(2),
                "maxParticipants": .number(4)
            ]),
            "B": .object([
                "count": .number(0)
            ])
        ])

        let merged = RoomStatuses.mergeStatusesPayload(previous: previous, payload: payload)
        XCTAssertEqual(merged["A"], RoomStatus(count: 2, maxParticipants: 4))
        XCTAssertEqual(merged["B"], RoomStatus(count: 0, maxParticipants: nil))
    }

    func testMergeStatusUpdatePayload() {
        let previous = ["A": RoomStatus(count: 1, maxParticipants: 4)]
        let payload: JSONValue = .object([
            "rid": .string("A"),
            "count": .number(5)
        ])

        let merged = RoomStatuses.mergeStatusUpdatePayload(previous: previous, payload: payload)
        XCTAssertEqual(merged["A"], RoomStatus(count: 5, maxParticipants: 4))
    }

    func testIndicatorStateUsesCountAndMaxParticipants() {
        XCTAssertEqual(RoomStatuses.indicatorState(for: nil), .hidden)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomStatus(count: 0, maxParticipants: 4)), .hidden)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomStatus(count: 1, maxParticipants: 4)), .waiting)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomStatus(count: 4, maxParticipants: 4)), .full)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomStatus(count: 2, maxParticipants: nil)), .full)
    }
}
