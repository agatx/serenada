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

        var iceServersChangedEvents: [[IceServerConfig]] = []

        func signalingProviderDidChangeIceServers(_ iceServers: [IceServerConfig]) {
            iceServersChangedEvents.append(iceServers)
        }

        /// Ice-server change events that actually carry TURN credentials
        /// (TurnManager's first ensure also emits a default STUN apply).
        var turnServerEvents: [[IceServerConfig]] {
            iceServersChangedEvents.filter { servers in
                servers.contains { $0.urls.contains { $0.hasPrefix("turn") } }
            }
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

    /// Waits until exactly one sleep is parked on the fake clock and the count
    /// holds across several main-actor yields. The transient single sleep
    /// (the about-to-be-cancelled fetch-timeout) is drained by the yields, so
    /// a stable count of one means the retry delay is parked and its deadline
    /// is anchored at the current fake time.
    private func waitForStableRetrySleep() async {
        for _ in 0..<32 {
            await waitUntil { self.fakeClock.pendingSleepCount == 1 }
            var stable = true
            for _ in 0..<8 {
                await Task.yield()
                if fakeClock.pendingSleepCount != 1 {
                    stable = false
                    break
                }
            }
            if stable {
                return
            }
        }
        XCTFail("Retry sleep never stabilized on the fake clock")
    }

    private func settle(yields: Int = 16) async {
        for _ in 0..<yields {
            await Task.yield()
        }
    }

    private func deliverTurnRefreshed(token: String) {
        signaling.simulateMessage(
            SignalingMessage(
                type: "turn-refreshed",
                rid: "room-1",
                cid: "local-cid",
                payload: .object(["turnToken": .string(token)])
            )
        )
    }

    func testTurnRefreshRetriesAfterTransientFailureWithoutErrorEvent() async throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        apiClient.turnCredentialsResult = .failure(URLError(.networkConnectionLost))

        deliverTurnRefreshed(token: "fresh-token")
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 1 }
        XCTAssertTrue(delegate.turnServerEvents.isEmpty)

        apiClient.turnCredentialsResult = .success(
            TurnCredentials(username: "user", password: "pass", uris: ["turn:turn.example.com:3478"], ttl: 3600)
        )
        // Wait for the retry delay to be parked on the fake clock before advancing.
        await waitForStableRetrySleep()
        await fakeClock.advance(byMs: 1_000)
        await waitUntil { self.delegate.turnServerEvents.count == 1 }

        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.count, 2)
        XCTAssertEqual(delegate.turnServerEvents.count, 1)
        XCTAssertTrue(delegate.errorEvents.isEmpty, "TURN refresh failures must never surface as error events")
    }

    func testExhaustedTurnRefreshAllowsTheSameTokenToRetry() async throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        apiClient.turnCredentialsResult = .failure(URLError(.networkConnectionLost))

        deliverTurnRefreshed(token: "fresh-token")
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 1 }
        await waitForStableRetrySleep()
        await fakeClock.advance(byMs: 1_000)
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 2 }
        await waitForStableRetrySleep()
        await fakeClock.advance(byMs: 2_000)
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 3 }
        await waitForStableRetrySleep()
        await fakeClock.advance(byMs: 4_000)
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 4 }
        // Let the exhausted loop clear the token latch before re-delivering.
        await settle()

        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.count, 4)
        XCTAssertTrue(delegate.turnServerEvents.isEmpty)
        XCTAssertTrue(delegate.errorEvents.isEmpty)

        // The server re-sends the same token (it has no fresher one until the
        // TTL rolls). Exhaustion must clear the dedupe latch so this retries.
        apiClient.turnCredentialsResult = .success(
            TurnCredentials(username: "user", password: "pass", uris: ["turn:turn.example.com:3478"], ttl: 3600)
        )
        deliverTurnRefreshed(token: "fresh-token")
        await waitUntil { self.delegate.turnServerEvents.count == 1 }

        XCTAssertEqual(apiClient.fetchTurnCredentialsCalls.count, 5)
        XCTAssertEqual(delegate.turnServerEvents.count, 1)
    }

    func testEmptyTurnCredentialRefreshIsRetriedNotApplied() async throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        apiClient.turnCredentialsResult = .success(
            TurnCredentials(username: "user", password: "pass", uris: [], ttl: 3600)
        )

        deliverTurnRefreshed(token: "fresh-token")
        await waitUntil { self.apiClient.fetchTurnCredentialsCalls.count == 1 }
        XCTAssertTrue(
            delegate.turnServerEvents.isEmpty,
            "An empty credential list must not strip TURN from the live call"
        )

        apiClient.turnCredentialsResult = .success(
            TurnCredentials(username: "user", password: "pass", uris: ["turn:turn.example.com:3478"], ttl: 3600)
        )
        await waitForStableRetrySleep()
        await fakeClock.advance(byMs: 1_000)
        await waitUntil { self.delegate.turnServerEvents.count == 1 }

        XCTAssertEqual(delegate.turnServerEvents.count, 1)
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

    // MARK: - Independent content video: join advertisement

    func testJoinDefaultsAdvertiseIndependentContentVideoFalseAndVideoPolicyTrue() throws {
        // Phase 1: the flag is off by default → independentContentVideo=false,
        // and the session-media policy defaults to videoMediaEnabled=true.
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen()

        let joinMessage = try XCTUnwrap(signaling.sentMessages.last)
        let capabilities = try XCTUnwrap(joinMessage.payload?.objectValue?["capabilities"]?.objectValue)
        XCTAssertEqual(capabilities["independentContentVideo"]?.boolValue, false)
        XCTAssertEqual(capabilities["trickleIce"]?.boolValue, true)
        XCTAssertEqual(capabilities["maxParticipants"]?.intValue, 4)

        let mediaPolicy = try XCTUnwrap(joinMessage.payload?.objectValue?["mediaPolicy"]?.objectValue)
        XCTAssertEqual(mediaPolicy["videoMediaEnabled"]?.boolValue, true)
    }

    func testJoinThreadsCapabilityAndPolicyFromOptions() throws {
        provider.joinRoom(
            "room-1",
            options: JoinOptions(maxParticipants: 4, independentContentVideo: true, videoMediaEnabled: false)
        )
        signaling.simulateOpen()

        let joinMessage = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(
            joinMessage.payload?.objectValue?["capabilities"]?.objectValue?["independentContentVideo"]?.boolValue,
            true
        )
        XCTAssertEqual(
            joinMessage.payload?.objectValue?["mediaPolicy"]?.objectValue?["videoMediaEnabled"]?.boolValue,
            false
        )
    }

    // MARK: - Independent content video: inbound sid threading

    func testRelayedContentStateSurfacesSenderSidOnPeerMessage() throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen()

        signaling.simulateMessage(
            SignalingMessage(
                type: "content_state",
                rid: "room-1",
                sid: "S-sender-9",
                cid: "peer-a",
                payload: .object([
                    "from": .string("peer-a"),
                    "active": .bool(true),
                    "contentType": .string("screenShare"),
                    "revision": .number(4),
                ])
            )
        )

        let peerMessage = try XCTUnwrap(delegate.peerMessages.last)
        XCTAssertEqual(peerMessage.type, "content_state")
        XCTAssertEqual(peerMessage.from, "peer-a")
        XCTAssertEqual(peerMessage.sid, "S-sender-9", "sender sid must reach the session for (cid, sid) revision tracking")
        XCTAssertEqual(peerMessage.payload?["revision"]?.intValue, 4)
    }

    func testInboundParticipantCapabilitiesAndPolicyReachJoinedEvent() throws {
        provider.joinRoom("room-1", options: JoinOptions(maxParticipants: 4))
        signaling.simulateOpen()

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
                            "joinedAt": .number(1),
                        ]),
                        .object([
                            "cid": .string("peer-a"),
                            "joinedAt": .number(2),
                            "capabilities": .object(["independentContentVideo": .bool(true)]),
                            "mediaPolicy": .object(["videoMediaEnabled": .bool(false)]),
                        ]),
                    ]),
                ])
            )
        )

        let joined = try XCTUnwrap(delegate.joinedEvents.last)
        let peer = try XCTUnwrap(joined.participants.first(where: { $0.peerId == "peer-a" }))
        XCTAssertEqual(peer.capabilities?.independentContentVideo, true)
        XCTAssertEqual(peer.mediaPolicy?.videoMediaEnabled, false)
    }
}
