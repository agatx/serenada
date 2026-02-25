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

    func testParseJoinTargetWithTrustedHost() {
        let url = URL(string: "https://serenada.app/call/ABCDEFGHIJKLMNOPQRSTUVWXYZa?host=serenada.app")!
        let target = DeepLinkParser.parseTarget(from: url)

        XCTAssertEqual(target?.action, .join)
        XCTAssertEqual(target?.roomId, "ABCDEFGHIJKLMNOPQRSTUVWXYZa")
        XCTAssertEqual(target?.host, "serenada.app")
    }

    func testParseSaveRoomTargetWhenNameIsPresent() {
        let url = URL(string: "https://serenada.app/call/ABCDEFGHIJKLMNOPQRSTUVWXYZa?host=demo.example.com&name=Family")!
        let target = DeepLinkParser.parseTarget(from: url)

        XCTAssertEqual(target?.action, .saveRoom)
        XCTAssertEqual(target?.savedRoomName, "Family")
        XCTAssertEqual(target?.host, "demo.example.com")
    }

    func testHostPolicyTreatsTrustedHostAsPersisted() {
        let policy = DeepLinkParser.resolveHostPolicy(host: "serenada-app.ru")
        XCTAssertEqual(policy.persistedHost, "serenada-app.ru")
        XCTAssertNil(policy.oneOffHost)
    }

    func testHostPolicyTreatsUntrustedHostAsOneOff() {
        let policy = DeepLinkParser.resolveHostPolicy(host: "custom.example.com:444")
        XCTAssertNil(policy.persistedHost)
        XCTAssertEqual(policy.oneOffHost, "custom.example.com:444")
    }
}
