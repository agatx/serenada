@testable import SerenadaCore
import XCTest

final class SignalingPayloadsTests: XCTestCase {

    // MARK: - JoinedPayload

    func testJoinedPayloadFullParse() {
        let payload: JSONValue = .object([
            "hostCid": .string("C-host"),
            "turnToken": .string("tok123"),
            "turnTokenTTLMs": .number(60000),
            "reconnectToken": .string("rk-abc"),
            "reconnectTokenTTLMs": .number(1_200_000),
            "participants": .array([
                .object(["cid": .string("C-host"), "joinedAt": .number(1000)]),
                .object(["cid": .string("C-guest")]),
            ]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.hostCid, "C-host")
        XCTAssertEqual(parsed.turnToken, "tok123")
        XCTAssertEqual(parsed.turnTokenTTLMs, 60000)
        XCTAssertEqual(parsed.reconnectToken, "rk-abc")
        XCTAssertEqual(parsed.reconnectTokenTTLMs, 1_200_000)
        XCTAssertEqual(parsed.participants?.count, 2)
        XCTAssertEqual(parsed.participants?[0].cid, "C-host")
        XCTAssertEqual(parsed.participants?[0].joinedAt, 1000)
        XCTAssertNil(parsed.participants?[1].joinedAt)
        XCTAssertEqual(parsed.participantCount, 2)
    }

    func testJoinedPayloadNilPayload() {
        let parsed = JoinedPayload(from: nil)
        XCTAssertNil(parsed.hostCid)
        XCTAssertNil(parsed.participants)
        XCTAssertNil(parsed.turnToken)
        XCTAssertNil(parsed.turnTokenTTLMs)
        XCTAssertNil(parsed.reconnectToken)
        XCTAssertNil(parsed.participantCount)
    }

    func testJoinedPayloadSingleParticipantCountMinOne() {
        let payload: JSONValue = .object([
            "participants": .array([
                .object(["cid": .string("C-me")]),
            ]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.participantCount, 1)
    }

    func testReconnectTokenRefreshedPayloadFullParse() {
        let payload: JSONValue = .object([
            "reconnectToken": .string("rk-new"),
            "reconnectTokenTTLMs": .number(1_200_000),
        ])
        let parsed = ReconnectTokenRefreshedPayload(from: payload)
        XCTAssertEqual(parsed?.reconnectToken, "rk-new")
        XCTAssertEqual(parsed?.reconnectTokenTTLMs, 1_200_000)
    }

    func testReconnectTokenRefreshedPayloadMissingTokenReturnsNil() {
        let payload: JSONValue = .object([
            "reconnectTokenTTLMs": .number(1_200_000),
        ])
        XCTAssertNil(ReconnectTokenRefreshedPayload(from: payload))
    }

    func testJoinedPayloadEmptyParticipants() {
        let payload: JSONValue = .object([
            "participants": .array([]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.participants?.count, 0)
        XCTAssertEqual(parsed.participantCount, 1, "participantCount should be at least 1")
    }

    func testJoinedPayloadSkipsParticipantsWithEmptyCid() {
        let payload: JSONValue = .object([
            "participants": .array([
                .object(["cid": .string("")]),
                .object(["cid": .string("C-valid")]),
            ]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.participants?.count, 1)
        XCTAssertEqual(parsed.participants?[0].cid, "C-valid")
    }

    func testJoinedPayloadParsesSuspendedConnectionStatus() {
        let payload: JSONValue = .object([
            "participants": .array([
                .object(["cid": .string("C-me")]),
                .object(["cid": .string("C-peer"), "connectionStatus": .string("suspended")]),
            ]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.participants?[0].signalingStatus, .active, "missing status defaults to active")
        XCTAssertEqual(parsed.participants?[1].signalingStatus, .suspended, "suspended wire value parsed")
    }

    func testJoinedPayloadUnknownConnectionStatusDefaultsToActive() {
        let payload: JSONValue = .object([
            "participants": .array([
                .object(["cid": .string("C-peer"), "connectionStatus": .string("bogus")]),
            ]),
        ])
        let parsed = JoinedPayload(from: payload)
        XCTAssertEqual(parsed.participants?[0].signalingStatus, .active)
    }

    // MARK: - ErrorPayload

    func testErrorPayloadFullParse() {
        let payload: JSONValue = .object([
            "code": .string("ROOM_CAPACITY_UNSUPPORTED"),
            "message": .string("Room is full"),
        ])
        let parsed = ErrorPayload(from: payload)
        XCTAssertEqual(parsed.code, "ROOM_CAPACITY_UNSUPPORTED")
        XCTAssertEqual(parsed.message, "Room is full")
    }

    func testErrorPayloadNilPayload() {
        let parsed = ErrorPayload(from: nil)
        XCTAssertNil(parsed.code)
        XCTAssertNil(parsed.message)
    }

    func testToCallErrorRoomFull() {
        let payload: JSONValue = .object(["code": .string("ROOM_CAPACITY_UNSUPPORTED")])
        let error = ErrorPayload(from: payload).toCallError()
        XCTAssertEqual(error, .roomFull)
    }

    func testToCallErrorConnectionFailed() {
        let payload: JSONValue = .object(["code": .string("CONNECTION_FAILED")])
        let error = ErrorPayload(from: payload).toCallError()
        XCTAssertEqual(error, .connectionFailed)
    }

    func testToCallErrorSignalingTimeout() {
        let payload: JSONValue = .object(["code": .string("JOIN_TIMEOUT")])
        let error = ErrorPayload(from: payload).toCallError()
        XCTAssertEqual(error, .signalingTimeout)
    }

    func testToCallErrorRoomEnded() {
        let payload: JSONValue = .object(["code": .string("ROOM_ENDED")])
        let error = ErrorPayload(from: payload).toCallError()
        XCTAssertEqual(error, .roomEnded)
    }

    func testToCallErrorUnknownCode() {
        let payload: JSONValue = .object([
            "code": .string("SOMETHING_NEW"),
            "message": .string("Details here"),
        ])
        let error = ErrorPayload(from: payload).toCallError()
        XCTAssertEqual(error, .serverError("Details here"))
    }

    func testToCallErrorNilCode() {
        let error = ErrorPayload(from: nil).toCallError()
        XCTAssertEqual(error, .unknown("Unknown error"))
    }

    // MARK: - ContentStatePayload

    func testContentStatePayloadActive() {
        let payload: JSONValue = .object([
            "from": .string("C-peer"),
            "active": .bool(true),
            "contentType": .string("screen"),
        ])
        let parsed = ContentStatePayload(from: payload)
        XCTAssertEqual(parsed.fromCid, "C-peer")
        XCTAssertTrue(parsed.active)
        XCTAssertEqual(parsed.contentType, "screen")
    }

    func testContentStatePayloadInactive() {
        let payload: JSONValue = .object([
            "from": .string("C-peer"),
            "active": .bool(false),
            "contentType": .string("screen"),
        ])
        let parsed = ContentStatePayload(from: payload)
        XCTAssertFalse(parsed.active)
        XCTAssertNil(parsed.contentType, "contentType should be nil when inactive")
    }

    func testContentStatePayloadNilPayload() {
        let parsed = ContentStatePayload(from: nil)
        XCTAssertNil(parsed.fromCid)
        XCTAssertFalse(parsed.active)
        XCTAssertNil(parsed.contentType)
        XCTAssertNil(parsed.revision)
        XCTAssertNil(parsed.sid)
    }

    // MARK: - ContentStatePayload revision + sid (independent content video)

    func testContentStatePayloadParsesRevision() {
        let payload: JSONValue = .object([
            "from": .string("C-peer"),
            "active": .bool(true),
            "contentType": .string("screenShare"),
            "revision": .number(7),
        ])
        let parsed = ContentStatePayload(from: payload, sid: "S-1")
        XCTAssertEqual(parsed.revision, 7)
        XCTAssertEqual(parsed.sid, "S-1")
    }

    func testContentStatePayloadMissingRevisionIsNil() {
        let payload: JSONValue = .object([
            "from": .string("C-peer"),
            "active": .bool(true),
            "contentType": .string("screenShare"),
        ])
        let parsed = ContentStatePayload(from: payload)
        XCTAssertNil(parsed.revision)
    }

    func testContentStatePayloadMissingActiveIsInvalid() {
        let payload: JSONValue = .object([
            "from": .string("C-peer"),
            "contentType": .string("screenShare"),
        ])
        let parsed = ContentStatePayload(from: payload)
        XCTAssertNil(parsed.fromCid)
        XCTAssertFalse(parsed.active)
        XCTAssertNil(parsed.revision)
    }

    // MARK: - parseContentState revision

    func testParseContentStateParsesRevision() {
        let value: JSONValue = .object([
            "active": .bool(true),
            "contentType": .string("screenShare"),
            "updatedAtMs": .number(1000),
            "epoch": .number(2),
            "revision": .number(5),
        ])
        let parsed = parseContentState(from: value)
        XCTAssertEqual(parsed?.revision, 5)
        XCTAssertEqual(parsed?.active, true)
        XCTAssertEqual(parsed?.contentType, "screenShare")
    }

    func testParseContentStateMissingRevisionIsNil() {
        let value: JSONValue = .object([
            "active": .bool(true),
            "contentType": .string("screenShare"),
        ])
        let parsed = parseContentState(from: value)
        XCTAssertNil(parsed?.revision)
    }

    func testParseContentStateIgnoresMalformedNumericRevisions() {
        for revision in [-1.0, 1.5, 9_007_199_254_740_992.0] {
            let value: JSONValue = .object([
                "active": .bool(true),
                "contentType": .string("screenShare"),
                "revision": .number(revision),
            ])
            let parsed = parseContentState(from: value)
            XCTAssertNil(parsed?.revision)
        }
    }

    // MARK: - Participant capabilities / mediaPolicy parse + defaults

    func testParseParticipantsParsesCapabilitiesAndMediaPolicy() {
        let value: [JSONValue] = [
            .object([
                "cid": .string("C-1"),
                "capabilities": .object(["independentContentVideo": .bool(true)]),
                "mediaPolicy": .object(["videoMediaEnabled": .bool(false)]),
            ]),
        ]
        let parsed = parseParticipants(from: value)
        XCTAssertEqual(parsed?.first?.capabilities?.independentContentVideo, true)
        XCTAssertEqual(parsed?.first?.mediaPolicy?.videoMediaEnabled, false)
    }

    func testParseParticipantsMissingCapabilitiesAndPolicyAreNil() {
        // Absent objects parse to nil so callers apply documented defaults
        // (independentContentVideo → false, videoMediaEnabled → true).
        let value: [JSONValue] = [.object(["cid": .string("C-1")])]
        let parsed = parseParticipants(from: value)
        XCTAssertNil(parsed?.first?.capabilities)
        XCTAssertNil(parsed?.first?.mediaPolicy)
    }

    func testParseParticipantsDropsUnknownCapabilityKeys() {
        // Unknown keys inside the objects are ignored; only allowlisted keys read.
        let value: [JSONValue] = [
            .object([
                "cid": .string("C-1"),
                "capabilities": .object([
                    "independentContentVideo": .bool(true),
                    "futureUnknownCap": .bool(true),
                ]),
                "mediaPolicy": .object([
                    "videoMediaEnabled": .bool(true),
                    "futureUnknownPolicy": .string("x"),
                ]),
            ]),
        ]
        let parsed = parseParticipants(from: value)
        XCTAssertEqual(parsed?.first?.capabilities?.independentContentVideo, true)
        XCTAssertEqual(parsed?.first?.mediaPolicy?.videoMediaEnabled, true)
    }

    func testParseParticipantsParsesContentStateRevision() {
        let value: [JSONValue] = [
            .object([
                "cid": .string("C-1"),
                "contentState": .object([
                    "active": .bool(true),
                    "contentType": .string("screenShare"),
                    "revision": .number(3),
                ]),
            ]),
        ]
        let parsed = parseParticipants(from: value)
        XCTAssertEqual(parsed?.first?.contentState?.revision, 3)
    }

    func testParseCapabilitiesAbsentObjectReturnsNil() {
        XCTAssertNil(parseCapabilities(from: nil))
        XCTAssertNil(parseMediaPolicy(from: nil))
    }
}
