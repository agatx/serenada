import XCTest
@testable import SerenadaiOS

final class SignalingMessageTests: XCTestCase {
    func testEncodeDecodeRoundTrip() throws {
        let message = SignalingMessage(
            type: "join",
            rid: "RID",
            sid: "SID",
            cid: "CID",
            to: "TO",
            payload: .object([
                "device": .string("ios"),
                "capabilities": .object(["trickleIce": .bool(true)])
            ])
        )

        let encoded = try message.toJSONString()
        let decoded = try SignalingMessage.decode(from: encoded)
        XCTAssertEqual(decoded, message)
    }
}
