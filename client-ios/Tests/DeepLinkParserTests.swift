import XCTest
@testable import SerenadaiOS

final class DeepLinkParserTests: XCTestCase {
    func testExtractRoomIdFromCallPath() {
        let url = URL(string: "https://serenada.app/call/ABCDEFGHIJKLMNOPQRSTUVWXYZa")!
        XCTAssertEqual(DeepLinkParser.extractRoomId(from: url), "ABCDEFGHIJKLMNOPQRSTUVWXYZa")
    }

    func testRejectInvalidPath() {
        let url = URL(string: "https://serenada.app/settings")!
        XCTAssertNil(DeepLinkParser.extractRoomId(from: url))
    }
}
