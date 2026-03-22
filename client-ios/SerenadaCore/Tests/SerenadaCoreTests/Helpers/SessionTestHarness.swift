import Foundation
@testable import SerenadaCore

@MainActor
final class SessionTestHarness {
    let session: SerenadaSession
    let fakeSignaling: FakeSignaling
    let fakeAPI: FakeAPIClient
    let fakeAudio: FakeAudioController
    let fakeMedia: FakeMediaEngine

    init(
        roomId: String = "test-room-id",
        serverHost: String = "test.serenada.app",
        config: SerenadaConfig? = nil
    ) {
        let resolvedConfig = config ?? SerenadaConfig(serverHost: serverHost)
        self.fakeSignaling = FakeSignaling()
        self.fakeAPI = FakeAPIClient()
        self.fakeAudio = FakeAudioController()
        self.fakeMedia = FakeMediaEngine()

        self.session = SerenadaSession(
            roomId: roomId,
            serverHost: serverHost,
            config: resolvedConfig,
            signaling: fakeSignaling,
            apiClient: fakeAPI,
            audioController: fakeAudio,
            mediaEngine: fakeMedia
        )
    }

    /// Yield to main actor to let init's async Task run (which calls beginJoinIfNeeded).
    /// If permissions are not yet granted, calls resumeJoin() to advance past the gate.
    func advancePastPermissions() async {
        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
    }

    func openSignaling(transport: String = "ws") {
        fakeSignaling.simulateOpen(transport: transport)
    }

    func simulateJoinedResponse(
        cid: String = "local-cid-1",
        participants: [(cid: String, joinedAt: Int)] = [],
        hostCid: String? = nil,
        turnToken: String? = nil
    ) {
        var payloadDict: [String: JSONValue] = [:]

        let resolvedHost = hostCid ?? cid
        payloadDict["hostCid"] = .string(resolvedHost)

        var participantList: [JSONValue] = []
        if participants.isEmpty {
            participantList.append(.object([
                "cid": .string(cid),
                "joinedAt": .number(1)
            ]))
        } else {
            for p in participants {
                participantList.append(.object([
                    "cid": .string(p.cid),
                    "joinedAt": .number(Double(p.joinedAt))
                ]))
            }
        }
        payloadDict["participants"] = .array(participantList)

        if let turnToken {
            payloadDict["turnToken"] = .string(turnToken)
        }

        let msg = SignalingMessage(
            type: "joined",
            rid: session.roomId,
            cid: cid,
            payload: .object(payloadDict)
        )
        fakeSignaling.simulateMessage(msg)
    }

    func simulateRoomState(
        participants: [(cid: String, joinedAt: Int)],
        hostCid: String
    ) {
        var participantList: [JSONValue] = []
        for p in participants {
            participantList.append(.object([
                "cid": .string(p.cid),
                "joinedAt": .number(Double(p.joinedAt))
            ]))
        }

        let msg = SignalingMessage(
            type: "room_state",
            rid: session.roomId,
            payload: .object([
                "hostCid": .string(hostCid),
                "participants": .array(participantList)
            ])
        )
        fakeSignaling.simulateMessage(msg)
    }

    func simulateError(code: String, message: String) {
        let msg = SignalingMessage(
            type: "error",
            rid: session.roomId,
            payload: .object([
                "code": .string(code),
                "message": .string(message)
            ])
        )
        fakeSignaling.simulateMessage(msg)
    }

    func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
    }

    func tearDown() {
        session.cancelJoin()
    }
}
