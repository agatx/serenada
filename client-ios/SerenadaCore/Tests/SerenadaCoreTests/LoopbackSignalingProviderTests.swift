/// End-to-end test validating the SignalingProvider contract with a
/// LoopbackSignalingProvider — an in-memory provider that routes messages
/// between two SerenadaSession instances without any server.

import XCTest
@testable import SerenadaCore

// MARK: - LoopbackRoom

private final class LoopbackRoom {
    private var participants: [(peerId: String, provider: LoopbackSignalingProvider)] = []
    private var hostPeerId: String?

    func join(_ provider: LoopbackSignalingProvider) {
        let peerId = provider.peerId
        participants.append((peerId, provider))
        if hostPeerId == nil { hostPeerId = peerId }

        let list = participants.enumerated().map { i, p in
            SignalingProviderParticipant(peerId: p.peerId, joinedAt: Int64(i + 1))
        }

        // Notify existing participants about the new peer.
        for (existingId, existing) in participants where existingId != peerId {
            existing.delegate?.signalingProviderDidJoinPeer(
                PeerEvent(peerId: peerId, joinedAt: Int64(list.count))
            )
        }

        // Tell the joining provider it has joined.
        provider.delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: peerId,
                participants: list,
                hostPeerId: hostPeerId,
                maxParticipants: 4
            )
        )
    }

    func routeToPeer(from: String, to: String, type: String, payload: SignalingPayload?) {
        guard let target = participants.first(where: { $0.peerId == to }) else { return }
        target.provider.delegate?.signalingProviderDidReceiveMessage(
            PeerMessage(from: from, type: type, payload: payload)
        )
    }

    func routeBroadcast(from: String, type: String, payload: SignalingPayload?) {
        for (peerId, provider) in participants where peerId != from {
            provider.delegate?.signalingProviderDidReceiveMessage(
                PeerMessage(from: from, type: type, payload: payload)
            )
        }
    }

    func leave(_ peerId: String) {
        participants.removeAll { $0.peerId == peerId }
        if hostPeerId == peerId, let first = participants.first {
            hostPeerId = first.peerId
        }
        for (_, provider) in participants {
            provider.delegate?.signalingProviderDidLeavePeer(
                PeerEvent(peerId: peerId, joinedAt: nil)
            )
        }
    }

    func end(by: String) {
        for (_, provider) in participants {
            provider.delegate?.signalingProviderDidEndRoom(
                RoomEndedEvent(by: by, reason: "host_ended")
            )
        }
        participants.removeAll()
        hostPeerId = nil
    }
}

// MARK: - LoopbackSignalingProvider

private final class LoopbackSignalingProvider: SignalingProvider {
    let version = SUPPORTED_SIGNALING_PROVIDER_VERSION
    let capabilities: ProviderCapabilities
    weak var delegate: SignalingProviderDelegate?

    let peerId: String
    private let room: LoopbackRoom
    private var currentRoomId: String?

    init(room: LoopbackRoom, peerId: String) {
        self.room = room
        self.peerId = peerId
        self.capabilities = ProviderCapabilities(handlesReconnection: true)
    }

    func connect() {
        delegate?.signalingProviderDidConnect(ConnectionInfo(transport: "loopback"))
    }

    func disconnect() {
        if currentRoomId != nil {
            room.leave(peerId)
            currentRoomId = nil
        }
    }

    func joinRoom(_ roomId: String, options: JoinOptions) {
        currentRoomId = roomId
        room.join(self)
    }

    func leaveRoom() {
        guard currentRoomId != nil else { return }
        room.leave(peerId)
        currentRoomId = nil
    }

    func endRoom() {
        room.end(by: peerId)
        currentRoomId = nil
    }

    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {
        room.routeToPeer(from: self.peerId, to: peerId, type: type, payload: payload)
    }

    func broadcast(type: String, payload: SignalingPayload?) {
        room.routeBroadcast(from: peerId, type: type, payload: payload)
    }

    func getIceServers() async throws -> [IceServerConfig] {
        return []
    }
}

// MARK: - Tests

@MainActor
final class LoopbackSignalingProviderTests: XCTestCase {

    private func makeSession(
        provider: LoopbackSignalingProvider,
        roomId: String = "room-1"
    ) -> (session: SerenadaSession, media: FakeMediaEngine) {
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: roomId,
            config: SerenadaConfig(signalingProvider: provider, audioCoordinator: FakeAudioCoordinator()),
            initialSignalingProvider: provider,
            audioController: FakeAudioController(),
            mediaEngine: media,
            clock: FakeSessionClock()
        )
        return (session, media)
    }

    /// Yield enough times to let chained Task { @MainActor } dispatches settle.
    /// The loopback provider fires events synchronously, but the session's
    /// delegate proxy defers each via Task — so we need extra yields.
    private func settle() async {
        for _ in 0..<20 {
            await Task.yield()
        }
    }

    private func advancePastPermissions(_ session: SerenadaSession) async {
        await settle()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await settle()
        }
        // After permissions, connect fires → connected Task → joinRoom → joined Task.
        // Yield enough to drain the full chain.
        await settle()
    }

    // MARK: - Tests

    func testSessionJoinsAloneAndReachesWaitingPhase() async {
        let room = LoopbackRoom()
        let provider = LoopbackSignalingProvider(room: room, peerId: "alice")
        let (session, _) = makeSession(provider: provider)

        await advancePastPermissions(session)
        await settle()

        XCTAssertEqual(session.state.phase, .waiting)
        XCTAssertEqual(session.state.localParticipant.cid, "alice")
        XCTAssertEqual(session.state.remoteParticipants.count, 0)

        session.cancelJoin()
    }

    func testTwoSessionsJoinAndBothReachInCallPhase() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, _) = makeSession(provider: providerA)
        await advancePastPermissions(sessionA)
        await settle()
        XCTAssertEqual(sessionA.state.phase, .waiting)

        let (sessionB, _) = makeSession(provider: providerB)
        await advancePastPermissions(sessionB)
        await settle()

        XCTAssertEqual(sessionA.state.phase, .inCall)
        XCTAssertEqual(sessionA.state.remoteParticipants.count, 1)
        XCTAssertEqual(sessionA.state.remoteParticipants.first?.cid, "bob")

        XCTAssertEqual(sessionB.state.phase, .inCall)
        XCTAssertEqual(sessionB.state.localParticipant.cid, "bob")
        XCTAssertEqual(sessionB.state.remoteParticipants.count, 1)
        XCTAssertEqual(sessionB.state.remoteParticipants.first?.cid, "alice")

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }

    func testSendToPeerDeliversOfferToRemoteSlot() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, mediaA) = makeSession(provider: providerA)
        mediaA.setIceServers([])
        await advancePastPermissions(sessionA)
        let (sessionB, mediaB) = makeSession(provider: providerB)
        mediaB.setIceServers([])
        await advancePastPermissions(sessionB)
        await settle()

        XCTAssertTrue(mediaB.createdSlotCids.contains("alice"),
                      "Bob's media engine should have a slot for alice")

        // Alice sends an offer to Bob.
        providerA.sendToPeer("bob", type: "offer", payload: [
            "from": .string("alice"),
            "sdp": .string("alice-sdp"),
        ])
        await settle()

        let bobSlotForAlice = mediaB.fakeSlots["alice"]
        XCTAssertNotNil(bobSlotForAlice, "Bob should have a slot for alice")
        XCTAssertFalse(bobSlotForAlice!.setRemoteDescriptionCalls.isEmpty,
                       "Bob's slot should have received the offer")

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }

    func testBroadcastRoutesToAllOtherSessions() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, mediaA) = makeSession(provider: providerA)
        mediaA.setIceServers([])
        await advancePastPermissions(sessionA)
        let (sessionB, mediaB) = makeSession(provider: providerB)
        mediaB.setIceServers([])
        await advancePastPermissions(sessionB)
        await settle()

        // Alice broadcasts content_state to the room.
        providerA.broadcast(type: "content_state", payload: [
            "from": .string("alice"),
            "active": .bool(true),
            "contentType": .string("screenShare"),
        ])
        await settle()

        // Bob's session should reflect the remote content state.
        XCTAssertEqual(sessionB.diagnostics.remoteContentParticipantId, "alice",
                       "Bob should see alice as the remote content source")

        // Alice should NOT see herself as the remote content source.
        XCTAssertNil(sessionA.diagnostics.remoteContentParticipantId,
                     "Alice should not see her own broadcast as remote content")

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }

    func testPeerLeavingTransitionsRemainingSessionToWaiting() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, _) = makeSession(provider: providerA)
        await advancePastPermissions(sessionA)
        let (sessionB, _) = makeSession(provider: providerB)
        await advancePastPermissions(sessionB)
        await settle()
        XCTAssertEqual(sessionA.state.phase, .inCall)

        providerB.leaveRoom()
        await settle()

        XCTAssertEqual(sessionA.state.phase, .waiting)
        XCTAssertEqual(sessionA.state.remoteParticipants.count, 0)

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }

    func testEndRoomTransitionsBothSessionsToEndingPhase() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, _) = makeSession(provider: providerA)
        await advancePastPermissions(sessionA)
        let (sessionB, _) = makeSession(provider: providerB)
        await advancePastPermissions(sessionB)
        await settle()

        providerA.endRoom()
        await settle()

        XCTAssertEqual(sessionA.state.phase, .ending)
        XCTAssertEqual(sessionB.state.phase, .ending)

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }

    func testMediaEngineReceivesRoomStateUpdates() async {
        let room = LoopbackRoom()
        let providerA = LoopbackSignalingProvider(room: room, peerId: "alice")
        let providerB = LoopbackSignalingProvider(room: room, peerId: "bob")

        let (sessionA, mediaA) = makeSession(provider: providerA)
        await advancePastPermissions(sessionA)
        await settle()

        // Alice's media engine should know about the initial room state.
        XCTAssertFalse(mediaA.createdSlotCids.isEmpty == false && mediaA.createdSlotCids.contains("alice"),
                       "Should not create a slot for self")
        let (sessionB, _) = makeSession(provider: providerB)
        await advancePastPermissions(sessionB)
        await settle()

        // Alice's media engine should now have a slot for Bob.
        XCTAssertTrue(mediaA.createdSlotCids.contains("bob"), "Should create slot for remote participant bob")

        sessionA.cancelJoin()
        sessionB.cancelJoin()
    }
}
