import Foundation
@testable import SerenadaCore

@MainActor
final class SessionTestHarness {
    let session: SerenadaSession
    let fakeProvider: FakeSignalingProvider
    let fakeAPI: FakeAPIClient
    let fakeAudio: FakeAudioController
    let fakeMedia: FakeMediaEngine
    let fakeClock: FakeSessionClock

    init(
        roomId: String = "test-room-id",
        handlesReconnection: Bool = false,
        config: SerenadaConfig? = nil
    ) {
        self.fakeProvider = FakeSignalingProvider(handlesReconnection: handlesReconnection)
        let resolvedConfig = config ?? SerenadaConfig(signalingProvider: fakeProvider)
        self.fakeAPI = FakeAPIClient()
        self.fakeAudio = FakeAudioController()
        self.fakeMedia = FakeMediaEngine()
        self.fakeClock = FakeSessionClock()

        self.session = SerenadaSession(
            roomId: roomId,
            config: resolvedConfig,
            initialSignalingProvider: fakeProvider,
            apiClient: fakeAPI,
            audioController: fakeAudio,
            mediaEngine: fakeMedia,
            clock: fakeClock
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
        fakeProvider.simulateConnected(transport: transport)
    }

    func simulateJoinedResponse(
        cid: String = "local-cid-1",
        participants: [(cid: String, joinedAt: Int)] = [],
        hostCid: String? = nil
    ) {
        let resolvedHost = hostCid ?? cid
        let participantList = participants.isEmpty
            ? [SignalingProviderParticipant(peerId: cid, joinedAt: 1)]
            : participants.map { SignalingProviderParticipant(peerId: $0.cid, joinedAt: Int64($0.joinedAt)) }
        fakeProvider.simulateJoined(
            peerId: cid,
            participants: participantList,
            hostPeerId: resolvedHost
        )
    }

    func simulateRoomState(
        participants: [(cid: String, joinedAt: Int)],
        hostCid: String
    ) {
        fakeProvider.simulateRoomState(
            participants: participants.map { SignalingProviderParticipant(peerId: $0.cid, joinedAt: Int64($0.joinedAt)) },
            hostPeerId: hostCid
        )
    }

    /// Variant that lets a test pass full ``SignalingProviderParticipant``
    /// records — needed when the test cares about per-participant
    /// `connectionStatus` (active/suspended), `displayName`, etc.
    func simulateRoomStateWith(
        participants: [SignalingProviderParticipant],
        hostCid: String
    ) {
        fakeProvider.simulateRoomState(participants: participants, hostPeerId: hostCid)
    }

    func simulateError(code: String, message: String) {
        fakeProvider.simulateError(code: code, message: message)
    }

    func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    // MARK: - Negotiation Test Helpers

    /// Advance to inCall state with TURN credentials ready and ICE servers set.
    func advanceToInCallWithTurn(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        localJoinedAt: Int = 1,
        remoteJoinedAt: Int = 2,
        iceServers: [IceServerConfig] = [IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")]
    ) async {
        fakeProvider.iceServerResults = [.success(iceServers)]
        await advancePastPermissions()
        openSignaling()
        simulateJoinedResponse(
            cid: localCid,
            participants: [
                (cid: localCid, joinedAt: localJoinedAt),
                (cid: remoteCid, joinedAt: remoteJoinedAt)
            ],
            hostCid: localCid
        )
        await yieldToMainActor()
        await fakeClock.advance(byMs: 100)
        await yieldToMainActor()
    }

    func simulateOfferFromRemote(fromCid: String, sdp: String = "remote-offer-sdp") {
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "offer",
            payload: [
                "from": .string(fromCid),
                "sdp": .string(sdp)
            ]
        )
    }

    func simulateAnswerFromRemote(fromCid: String, sdp: String = "remote-answer-sdp") {
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "answer",
            payload: [
                "from": .string(fromCid),
                "sdp": .string(sdp)
            ]
        )
    }

    func simulateIceCandidateFromRemote(
        fromCid: String,
        candidate: String = "candidate:1 1 udp 2130706431 192.168.1.1 12345 typ host",
        sdpMid: String = "0",
        sdpMLineIndex: Int = 0
    ) {
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "ice",
            payload: [
                "from": .string(fromCid),
                "candidate": .object([
                    "candidate": .string(candidate),
                    "sdpMid": .string(sdpMid),
                    "sdpMLineIndex": .number(Double(sdpMLineIndex))
                ])
            ]
        )
    }

    func tearDown() {
        session.cancelJoin()
    }
}
