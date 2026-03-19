import SerenadaCore
import XCTest
@testable import SerenadaiOS

final class RoomStatusesTests: XCTestCase {
    func testIndicatorStateUsesCountAndMaxParticipants() {
        XCTAssertEqual(RoomStatuses.indicatorState(for: nil), .hidden)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomOccupancy(count: 0, maxParticipants: 4)), .hidden)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomOccupancy(count: 1, maxParticipants: 4)), .waiting)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomOccupancy(count: 4, maxParticipants: 4)), .full)
        XCTAssertEqual(RoomStatuses.indicatorState(for: RoomOccupancy(count: 2, maxParticipants: nil)), .full)
    }
}
