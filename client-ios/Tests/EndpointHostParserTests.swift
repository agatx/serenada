import XCTest
@testable import SerenadaiOS

final class EndpointHostParserTests: XCTestCase {
    func testSplitHostAndPortWithPortOnlyHostInput() {
        let parsed = EndpointHostParser.splitHostAndPort(from: "demo.example.com:8443")

        XCTAssertEqual(parsed?.host, "demo.example.com")
        XCTAssertEqual(parsed?.port, 8443)
    }

    func testSplitHostAndPortWithSchemeAndTrailingSlash() {
        let parsed = EndpointHostParser.splitHostAndPort(from: "https://demo.example.com:9443/")

        XCTAssertEqual(parsed?.host, "demo.example.com")
        XCTAssertEqual(parsed?.port, 9443)
    }

    func testSplitHostAndPortWithoutPort() {
        let parsed = EndpointHostParser.splitHostAndPort(from: "serenada.app")

        XCTAssertEqual(parsed?.host, "serenada.app")
        XCTAssertNil(parsed?.port)
    }

    func testSplitHostAndPortRejectsOutOfRangePort() {
        XCTAssertNil(EndpointHostParser.splitHostAndPort(from: "demo.example.com:70000"))
    }
}
