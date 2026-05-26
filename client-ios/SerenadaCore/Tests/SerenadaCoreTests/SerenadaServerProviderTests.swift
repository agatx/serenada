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
        var errorEvents: [ErrorEvent] = []
        var reconnectTokenRefreshedEvents: [ReconnectTokenRefreshedEvent] = []

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

        func signalingProviderDidReceiveError(_ event: ErrorEvent) {
            errorEvents.append(event)
        }

        func signalingProviderDidRefreshReconnectToken(_ event: ReconnectTokenRefreshedEvent) {
            reconnectTokenRefreshedEvents.append(event)
        }
    }

    private var signaling: FakeSessionSignaling!
    private var apiClient: FakeAPIClient!
    private var fakeClock: FakeSessionClock!
    private var delegate: RecordingDelegate!
    private var provider: SerenadaServerProvider!

    @discardableResult
    private func waitUntil(attempts: Int = 32, condition: () -> Bool) async -> Bool {
        for _ in 0..<attempts {
            if condition() {
                return true
            }
            await Task.yield()
        }
        return false
    }

    private func waitForPendingSleeps(_ expectedCount: Int, attempts: Int = 32) async {
        await waitUntil(attempts: attempts) { [unowned self] in
            self.fakeClock.pendingSleepCount == expectedCount
        }
    }

    override func setUp() {
        super.setUp()
        signaling = FakeSessionSignaling()
        apiClient = FakeAPIClient()
        fakeClock = FakeSessionClock()
        delegate = RecordingDelegate()
        provider = SerenadaServerProvider(
            serverHost: "serenada.app",
            apiClient: apiClient,
            signaling: signaling,
            clock: fakeClock
        )
        provider.delegate = delegate
    }

    override func tearDown() {
        provider.disconnect()
        provider = nil
        delegate = nil
        fakeClock = nil
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

    func testReconnectTokenRefreshIsScheduledTenMinutesBeforeExpiry() async throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen(activeTransport: "ws")

        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "server-assigned",
                payload: .object([
                    "reconnectToken": .string("token-1"),
                    "reconnectTokenTTLMs": .number(1_200_000),
                    "participants": .array([
                        .object(["cid": .string("server-assigned")])
                    ])
                ])
            )
        )
        signaling.clearSentMessages()
        await waitForPendingSleeps(1)

        await fakeClock.advance(byMs: 599_999)
        XCTAssertFalse(signaling.sentMessages.contains { $0.type == "reconnect-token-refresh" })

        await fakeClock.advance(byMs: 1)
        await waitUntil { [unowned self] in
            self.signaling.sentMessages.contains { $0.type == "reconnect-token-refresh" }
        }
        XCTAssertEqual(signaling.sentMessages.filter { $0.type == "reconnect-token-refresh" }.count, 1)
    }

    func testReconnectTokenRefreshedUpdatesTokenUsedByNextAutoRejoin() throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen(activeTransport: "ws")

        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "server-assigned",
                payload: .object([
                    "reconnectToken": .string("token-1"),
                    "reconnectTokenTTLMs": .number(1_200_000),
                    "participants": .array([
                        .object(["cid": .string("server-assigned")])
                    ])
                ])
            )
        )
        signaling.simulateMessage(
            SignalingMessage(
                type: "reconnect-token-refreshed",
                rid: "room-1",
                payload: .object([
                    "reconnectToken": .string("token-2"),
                    "reconnectTokenTTLMs": .number(1_200_000)
                ])
            )
        )
        XCTAssertEqual(delegate.reconnectTokenRefreshedEvents.count, 1)
        XCTAssertEqual(delegate.reconnectTokenRefreshedEvents.first?.reconnectToken, "token-2")
        signaling.clearSentMessages()

        signaling.simulateClosed()
        signaling.simulateOpen(activeTransport: "ws")

        let rejoin = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(rejoin.payload?.objectValue?["reconnectCid"]?.stringValue, "server-assigned")
        XCTAssertEqual(rejoin.payload?.objectValue?["reconnectToken"]?.stringValue, "token-2")
    }

    func testInvalidReconnectTokenRetriesAsFreshJoinWithoutSurfacingError() throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen(activeTransport: "ws")
        signaling.simulateMessage(
            SignalingMessage(
                type: "joined",
                rid: "room-1",
                cid: "server-assigned",
                payload: .object([
                    "reconnectToken": .string("expired-token"),
                    "reconnectTokenTTLMs": .number(1_200_000),
                    "participants": .array([
                        .object(["cid": .string("server-assigned")])
                    ])
                ])
            )
        )

        signaling.clearSentMessages()
        signaling.simulateClosed()
        signaling.simulateOpen(activeTransport: "ws")
        let reconnectJoin = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(reconnectJoin.payload?.objectValue?["reconnectCid"]?.stringValue, "server-assigned")
        XCTAssertEqual(reconnectJoin.payload?.objectValue?["reconnectToken"]?.stringValue, "expired-token")

        signaling.simulateMessage(
            SignalingMessage(
                type: "error",
                rid: "room-1",
                payload: .object([
                    "code": .string("INVALID_RECONNECT_TOKEN"),
                    "message": .string("Reconnect token validation failed")
                ])
            )
        )

        let freshJoin = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(freshJoin.type, "join")
        XCTAssertNil(freshJoin.payload?.objectValue?["reconnectCid"]?.stringValue)
        XCTAssertNil(freshJoin.payload?.objectValue?["reconnectToken"]?.stringValue)
        XCTAssertTrue(delegate.errorEvents.isEmpty)
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
