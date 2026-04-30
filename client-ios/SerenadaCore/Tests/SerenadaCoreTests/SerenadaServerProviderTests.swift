import XCTest
@testable import SerenadaCore

@MainActor
final class SerenadaServerProviderTests: XCTestCase {
    private final class RecordingDelegate: SignalingProviderDelegate {
        var connectionInfos: [ConnectionInfo] = []
        var joinedEvents: [JoinedEvent] = []
        var roomStateEvents: [RoomStateEvent] = []
        var peerJoinedEvents: [PeerEvent] = []
        var peerLeftEvents: [PeerEvent] = []
        var peerMessages: [PeerMessage] = []
        var roomEndedEvents: [RoomEndedEvent] = []

        func signalingProviderDidConnect(_ info: ConnectionInfo) {
            connectionInfos.append(info)
        }

        func signalingProviderDidJoin(_ event: JoinedEvent) {
            joinedEvents.append(event)
        }

        func signalingProviderDidUpdateRoomState(_ event: RoomStateEvent) {
            roomStateEvents.append(event)
        }

        func signalingProviderDidJoinPeer(_ event: PeerEvent) {
            peerJoinedEvents.append(event)
        }

        func signalingProviderDidLeavePeer(_ event: PeerEvent) {
            peerLeftEvents.append(event)
        }

        func signalingProviderDidReceiveMessage(_ message: PeerMessage) {
            peerMessages.append(message)
        }

        func signalingProviderDidEndRoom(_ event: RoomEndedEvent) {
            roomEndedEvents.append(event)
        }
    }

    private var signaling: FakeSessionSignaling!
    private var apiClient: FakeAPIClient!
    private var delegate: RecordingDelegate!
    private var provider: SerenadaServerProvider!

    override func setUp() {
        super.setUp()
        signaling = FakeSessionSignaling()
        apiClient = FakeAPIClient()
        delegate = RecordingDelegate()
        provider = SerenadaServerProvider(
            serverHost: "serenada.app",
            apiClient: apiClient,
            signaling: signaling
        )
        provider.delegate = delegate
    }

    override func tearDown() {
        provider.disconnect()
        provider = nil
        delegate = nil
        apiClient = nil
        signaling = nil
        super.tearDown()
    }

    func testJoinWaitsForConnectAndIncludesReconnectPeerId() throws {
        provider.joinRoom("room-1", options: JoinOptions(reconnectPeerId: "local-cid", maxParticipants: 6))

        XCTAssertEqual(signaling.connectHosts, ["serenada.app"])
        XCTAssertTrue(signaling.sentMessages.isEmpty)

        signaling.simulateOpen(activeTransport: "sse")

        XCTAssertEqual(delegate.connectionInfos, [ConnectionInfo(transport: "sse")])
        let joinMessage = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(joinMessage.type, "join")
        XCTAssertEqual(joinMessage.rid, "room-1")
        XCTAssertEqual(joinMessage.payload?.objectValue?["reconnectCid"]?.stringValue, "local-cid")
        XCTAssertEqual(joinMessage.payload?.objectValue?["createMaxParticipants"]?.intValue, 6)
    }

    func testAutoRejoinAfterTransportDropCarriesServerAssignedCidAndToken() throws {
        // Initial fresh join — no reconnect peer id from the host app.
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen(activeTransport: "ws")

        // Server assigns a CID and reconnect token via JOINED.
        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "server-assigned",
                payload: .object([
                    "reconnectToken": .string("token-xyz"),
                    "participants": .array([
                        .object([
                            "cid": .string("server-assigned"),
                            "joinedAt": .number(1)
                        ])
                    ])
                ])
            )
        )
        signaling.clearSentMessages()

        // Transport drop and reopen — auto-rejoin path.
        signaling.simulateClosed()
        signaling.simulateOpen(activeTransport: "ws")

        let rejoin = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(rejoin.type, "join")
        XCTAssertEqual(rejoin.payload?.objectValue?["reconnectCid"]?.stringValue, "server-assigned")
        XCTAssertEqual(rejoin.payload?.objectValue?["reconnectToken"]?.stringValue, "token-xyz")
    }

    func testJoinedTurnTokenIsUsedForIceServerFetch() async throws {
        apiClient.turnCredentialsResult = .success(
            TurnCredentials(
                username: "turn-user",
                password: "turn-pass",
                uris: ["turn:turn-a.example.com:3478", "turns:turn-b.example.com:5349"],
                ttl: 3600
            )
        )

        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "local-cid",
                payload: .object([
                    "hostCid": .string("local-cid"),
                    "turnToken": .string("turn-token"),
                    "participants": .array([
                        .object([
                            "cid": .string("local-cid"),
                            "joinedAt": .number(1)
                        ])
                    ])
                ])
            )
        )

        let iceServers = try await provider.getIceServers()

        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.count, 1)
        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.first?.host, "serenada.app")
        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.first?.token, "turn-token")
        XCTAssertEqual(iceServers.map(\.urls), [["turn:turn-a.example.com:3478"], ["turns:turn-b.example.com:5349"]])
        XCTAssertEqual(delegate.joinedEvents.last?.peerId, "local-cid")
    }

    func testRoomStateEmitsParticipantDiffsAndRoomEndedUsesPayloadFields() {
        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "local-cid",
                payload: .object([
                    "hostCid": .string("local-cid"),
                    "participants": .array([
                        .object([
                            "cid": .string("local-cid"),
                            "joinedAt": .number(1)
                        ]),
                        .object([
                            "cid": .string("peer-a"),
                            "joinedAt": .number(2)
                        ])
                    ])
                ])
            )
        )

        signaling.simulateMessage(
            SignalingMessage(
                type: "room_state",
                rid: "room-1",
                payload: .object([
                    "hostCid": .string("peer-b"),
                    "participants": .array([
                        .object([
                            "cid": .string("local-cid"),
                            "joinedAt": .number(1)
                        ]),
                        .object([
                            "cid": .string("peer-b"),
                            "joinedAt": .number(3)
                        ])
                    ])
                ])
            )
        )

        XCTAssertEqual(delegate.peerJoinedEvents, [PeerEvent(peerId: "peer-b", joinedAt: 3)])
        XCTAssertEqual(delegate.peerLeftEvents, [PeerEvent(peerId: "peer-a", joinedAt: 2)])
        XCTAssertEqual(delegate.roomStateEvents.last?.hostPeerId, "peer-b")

        signaling.simulateMessage(
            SignalingMessage(
                type: "room_ended",
                rid: "room-1",
                payload: .object([
                    "by": .string("peer-b"),
                    "reason": .string("host ended")
                ])
            )
        )

        XCTAssertEqual(delegate.roomEndedEvents.last, RoomEndedEvent(by: "peer-b", reason: "host ended"))
    }

    func testRoomStateForwardsSuspendedConnectionStatusToDelegate() {
        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "local-cid",
                payload: .object([
                    "hostCid": .string("local-cid"),
                    "participants": .array([
                        .object(["cid": .string("local-cid"), "joinedAt": .number(1)]),
                        .object(["cid": .string("peer-a"), "joinedAt": .number(2)]),
                    ]),
                ])
            )
        )

        signaling.simulateMessage(
            SignalingMessage(
                type: "room_state",
                rid: "room-1",
                payload: .object([
                    "hostCid": .string("local-cid"),
                    "participants": .array([
                        .object(["cid": .string("local-cid"), "joinedAt": .number(1)]),
                        .object([
                            "cid": .string("peer-a"),
                            "joinedAt": .number(2),
                            "connectionStatus": .string("suspended"),
                        ]),
                    ]),
                ])
            )
        )

        let lastEvent = delegate.roomStateEvents.last
        XCTAssertNotNil(lastEvent)
        let peer = lastEvent?.participants.first(where: { $0.peerId == "peer-a" })
        XCTAssertEqual(peer?.signalingStatus, .suspended, "suspended status must reach SignalingProviderParticipant; dropping it breaks the reconnecting UI")
    }

    func testSendToPeerAndBroadcastForwardRawMessages() {
        provider.sendToPeer("peer-1", type: "offer", payload: ["sdp": .string("offer-sdp")])
        provider.broadcast(type: "content_state", payload: ["active": .bool(true)])

        XCTAssertEqual(signaling.sentMessages.map(\.type), ["offer", "content_state"])
        XCTAssertEqual(signaling.sentMessages.first?.to, "peer-1")
        XCTAssertEqual(signaling.sentMessages.first?.payload?.objectValue?["sdp"]?.stringValue, "offer-sdp")
        XCTAssertNil(signaling.sentMessages.last?.to)
        XCTAssertEqual(signaling.sentMessages.last?.payload?.objectValue?["active"]?.boolValue, true)
    }
}
