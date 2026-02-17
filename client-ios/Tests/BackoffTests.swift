import XCTest
@testable import SerenadaiOS

final class BackoffTests: XCTestCase {
    func testReconnectBackoffCapsAtFiveSeconds() {
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 1), 500)
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 2), 1000)
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 3), 2000)
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 4), 4000)
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 5), 5000)
        XCTAssertEqual(Backoff.reconnectDelayMs(attempt: 10), 5000)
    }
}
