import Foundation

internal final class SerenadaServerProvider: SignalingProvider {
    let capabilities = ProviderCapabilities(handlesReconnection: true)
    weak var delegate: SignalingProviderDelegate?

    private let serverHost: String
    private let apiClient: SessionAPIClient
    private let clock: SessionClock
    private let signaling: SessionSignaling
    private let logger: SerenadaLogger?

    @MainActor private var turnManager: TurnManager?
    @MainActor private var reconnectAttempts = 0
    @MainActor private var reconnectTask: Task<Void, Never>?
    @MainActor private var currentRoomId: String?
    @MainActor private var currentMaxParticipants = 4
    @MainActor private var currentReconnectPeerId: String?
    @MainActor private var currentDisplayName: String?
    @MainActor private var currentTurnToken: String?
    @MainActor private var reconnectToken: String?
    @MainActor private var clientId: String?
    @MainActor private var currentHostPeerId: String?
    @MainActor private var previousParticipants: [String: SignalingProviderParticipant] = [:]
    @MainActor private var pendingJoinRoomId: String?
    @MainActor private var joinAttemptSerial: Int64 = 0
    @MainActor private var closedByClient = false

    init(
        serverHost: String,
        apiClient: SessionAPIClient,
        signaling: SessionSignaling? = nil,
        transports: [SerenadaTransport] = [.ws, .sse],
        clock: SessionClock? = nil,
        logger: SerenadaLogger? = nil
    ) {
        self.serverHost = serverHost
        self.apiClient = apiClient
        precondition(Thread.isMainThread, "SerenadaServerProvider must be created on the main thread")
        self.clock = clock ?? MainActor.assumeIsolated { LiveSessionClock() }
        self.logger = logger
        self.signaling = signaling ?? MainActor.assumeIsolated {
            SignalingClient(forceSseSignaling: !transports.contains(.ws))
        }
        MainActor.assumeIsolated {
            self.signaling.listener = self
            self.turnManager = TurnManager(
                clock: self.clock,
                serverHost: serverHost,
                apiClient: apiClient,
                getJoinAttemptSerial: { [weak self] in self?.joinAttemptSerial ?? 0 },
                getRoomId: { [weak self] in self?.currentRoomId ?? "" },
                getPhase: { [weak self] in
                    guard let self, self.currentRoomId != nil else { return .idle }
                    return .joining
                },
                isSignalingConnected: { [weak self] in self?.signaling.isConnected() ?? false },
                setIceServers: { [weak self] iceServers in
                    self?.delegate?.signalingProviderDidChangeIceServers(iceServers)
                },
                onIceServersReady: {},
                sendTurnRefresh: { [weak self] in self?.sendRawMessage(type: "turn-refresh") }
            )
        }
    }

    func connect() {
        precondition(Thread.isMainThread, "SerenadaServerProvider.connect() must be called on the main thread")
        MainActor.assumeIsolated {
            closedByClient = false
            signaling.connect(host: serverHost)
        }
    }

    func disconnect() {
        precondition(Thread.isMainThread, "SerenadaServerProvider.disconnect() must be called on the main thread")
        MainActor.assumeIsolated {
            closedByClient = true
            clearReconnect()
            clearRoomState()
            pendingJoinRoomId = nil
            signaling.close()
        }
    }

    func joinRoom(_ roomId: String, options: JoinOptions) {
        precondition(Thread.isMainThread, "SerenadaServerProvider.joinRoom() must be called on the main thread")
        MainActor.assumeIsolated {
            currentRoomId = roomId
            pendingJoinRoomId = roomId
            joinAttemptSerial += 1
            currentMaxParticipants = options.maxParticipants ?? currentMaxParticipants
            currentReconnectPeerId = options.reconnectPeerId
            currentDisplayName = options.displayName ?? currentDisplayName
            if signaling.isConnected() {
                pendingJoinRoomId = nil
                sendJoin(roomId: roomId)
            } else {
                closedByClient = false
                signaling.connect(host: serverHost)
            }
        }
    }

    func leaveRoom() {
        precondition(Thread.isMainThread, "SerenadaServerProvider.leaveRoom() must be called on the main thread")
        MainActor.assumeIsolated {
            sendRawMessage(type: "leave")
            clearRoomState()
        }
    }

    func endRoom() {
        precondition(Thread.isMainThread, "SerenadaServerProvider.endRoom() must be called on the main thread")
        MainActor.assumeIsolated {
            sendRawMessage(type: "end_room")
        }
    }

    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                sendRawMessage(type: type, payload: payload, to: peerId)
            }
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.sendRawMessage(type: type, payload: payload, to: peerId)
            }
        }
    }

    func broadcast(type: String, payload: SignalingPayload?) {
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                sendRawMessage(type: type, payload: payload)
            }
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.sendRawMessage(type: type, payload: payload)
            }
        }
    }

    func getIceServers() async throws -> [IceServerConfig] {
        let token = await MainActor.run {
            currentTurnToken?.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard let token, !token.isEmpty else {
            return []
        }
        let credentials = try await apiClient.fetchTurnCredentials(host: serverHost, token: token)
        return credentials.uris.map {
            IceServerConfig(urls: [$0], username: credentials.username, credential: credentials.password)
        }
    }
}

@MainActor
extension SerenadaServerProvider: SignalingClientListener {
    func onOpen(activeTransport: String) {
        reconnectAttempts = 0
        clearReconnect()
        delegate?.signalingProviderDidConnect(ConnectionInfo(transport: activeTransport))
        if let roomId = pendingJoinRoomId {
            pendingJoinRoomId = nil
            sendJoin(roomId: roomId)
        }
    }

    func onMessage(_ message: SignalingMessage) {
        switch message.type {
        case "joined":
            handleJoined(message)
        case "room_state":
            handleRoomState(message)
        case "room_ended":
            let endedBy = message.payload?.objectValue?["by"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
                ?? currentHostPeerId
            let reason = message.payload?.objectValue?["reason"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
                ?? "room ended"
            clearReconnect()
            clearRoomState()
            delegate?.signalingProviderDidEndRoom(RoomEndedEvent(by: endedBy, reason: reason))
        case "error":
            let payload = ErrorPayload(from: message.payload)
            delegate?.signalingProviderDidReceiveError(
                ErrorEvent(
                    code: payload.code ?? "UNKNOWN",
                    message: payload.message ?? "Unknown error"
                )
            )
        case "turn-refreshed":
            currentTurnToken = SignalingMessageRouter.turnToken(from: message.payload)
            turnManager?.handleTurnRefreshed(payload: message.payload)
        case "offer", "answer", "ice", "content_state":
            emitPeerMessage(message)
        case "pong":
            signaling.recordPong()
        default:
            break
        }
    }

    func onClosed(reason: String) {
        delegate?.signalingProviderDidDisconnect(reason: reason)
        guard !closedByClient, currentRoomId != nil else { return }
        pendingJoinRoomId = currentRoomId
        scheduleReconnect()
    }
}

@MainActor
private extension SerenadaServerProvider {
    func handleJoined(_ message: SignalingMessage) {
        let payload = JoinedPayload(from: message.payload)
        let peerId = message.cid?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
            ?? clientId
        guard let peerId else { return }

        clientId = peerId
        reconnectToken = payload.reconnectToken ?? reconnectToken
        currentHostPeerId = payload.hostCid
        currentTurnToken = payload.turnToken
        if let ttl = payload.turnTokenTTLMs {
            turnManager?.handleJoinedTTL(ttlMs: Int64(ttl))
        } else {
            turnManager?.cancelRefresh()
        }

        let participants = dedupeProviderParticipants(
            participants: (payload.participants ?? []).map {
                SignalingProviderParticipant(peerId: $0.cid, joinedAt: $0.joinedAt, displayName: $0.displayName)
            },
            localPeerId: peerId
        )
        previousParticipants = Dictionary(uniqueKeysWithValues: participants.map { ($0.peerId, $0) })
        delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: peerId,
                participants: participants,
                hostPeerId: payload.hostCid,
                maxParticipants: payload.maxParticipants
            )
        )
    }

    func handleRoomState(_ message: SignalingMessage) {
        guard let event = roomStateEvent(from: message.payload) else { return }
        currentHostPeerId = event.hostPeerId
        emitParticipantDiffs(nextParticipants: event.participants)
        previousParticipants = Dictionary(uniqueKeysWithValues: event.participants.map { ($0.peerId, $0) })
        delegate?.signalingProviderDidUpdateRoomState(event)
    }

    func emitParticipantDiffs(nextParticipants: [SignalingProviderParticipant]) {
        let nextMap = Dictionary(uniqueKeysWithValues: nextParticipants.map { ($0.peerId, $0) })
        for (peerId, participant) in nextMap where previousParticipants[peerId] == nil {
            delegate?.signalingProviderDidJoinPeer(PeerEvent(peerId: peerId, joinedAt: participant.joinedAt, displayName: participant.displayName))
        }
        for (peerId, participant) in previousParticipants where nextMap[peerId] == nil {
            delegate?.signalingProviderDidLeavePeer(PeerEvent(peerId: peerId, joinedAt: participant.joinedAt, displayName: participant.displayName))
        }
    }

    func emitPeerMessage(_ message: SignalingMessage) {
        let from = message.payload?.objectValue?["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
            ?? message.cid?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard let from else { return }
        delegate?.signalingProviderDidReceiveMessage(
            PeerMessage(
                from: from,
                type: message.type,
                payload: message.payload?.objectValue
            )
        )
    }

    func roomStateEvent(from payload: JSONValue?) -> RoomStateEvent? {
        guard let object = payload?.objectValue else { return nil }
        let participants = dedupeProviderParticipants(
            participants: (parseParticipants(from: object["participants"]?.arrayValue) ?? []).map {
                SignalingProviderParticipant(peerId: $0.cid, joinedAt: $0.joinedAt, displayName: $0.displayName)
            },
            localPeerId: clientId
        )
        var hostPeerId = object["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? currentHostPeerId
            ?? clientId
        if let currentHost = hostPeerId, !participants.isEmpty, !participants.contains(where: { $0.peerId == currentHost }) {
            hostPeerId = participants.first?.peerId
        }
        guard hostPeerId != nil || !participants.isEmpty else { return nil }
        return RoomStateEvent(
            participants: participants,
            hostPeerId: hostPeerId,
            maxParticipants: object["maxParticipants"]?.intValue
        )
    }

    func sendJoin(roomId: String) {
        currentRoomId = roomId
        let payload: SignalingPayload = [
            "device": .string("ios"),
            "capabilities": .object([
                "trickleIce": .bool(true),
                "maxParticipants": .number(Double(currentMaxParticipants))
            ]),
            "createMaxParticipants": .number(Double(currentMaxParticipants))
        ]
        var joinPayload = payload
        if let reconnectToken, !reconnectToken.isEmpty {
            joinPayload["reconnectToken"] = .string(reconnectToken)
        }
        if let currentReconnectPeerId, !currentReconnectPeerId.isEmpty {
            joinPayload["reconnectCid"] = .string(currentReconnectPeerId)
        }
        if let currentDisplayName, !currentDisplayName.isEmpty {
            joinPayload["displayName"] = .string(currentDisplayName)
        }
        sendRawMessage(type: "join", rid: roomId, payload: joinPayload)
    }

    func sendRawMessage(
        type: String,
        rid: String? = nil,
        payload: SignalingPayload? = nil,
        to: String? = nil
    ) {
        signaling.send(
            SignalingMessage(
                type: type,
                rid: rid ?? currentRoomId,
                cid: clientId,
                to: to,
                payload: payload.map(JSONValue.object)
            )
        )
    }

    func scheduleReconnect() {
        clearReconnect()
        reconnectAttempts += 1
        let backoff = Backoff.reconnectDelayMs(attempt: reconnectAttempts)
        reconnectTask = Task { [weak self] in
            guard let self else { return }
            try? await self.clock.sleep(nanoseconds: UInt64(backoff) * 1_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard !self.closedByClient, self.currentRoomId != nil else { return }
                self.signaling.connect(host: self.serverHost)
            }
        }
    }

    func clearReconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
    }

    func clearRoomState() {
        clearReconnect()
        turnManager?.cancelRefresh()
        currentRoomId = nil
        currentReconnectPeerId = nil
        currentTurnToken = nil
        currentHostPeerId = nil
        previousParticipants = [:]
        clientId = nil
        reconnectToken = nil
    }

    func dedupeProviderParticipants(
        participants: [SignalingProviderParticipant],
        localPeerId: String?
    ) -> [SignalingProviderParticipant] {
        dedupeParticipants(
            participants: participants,
            localPeerId: localPeerId,
            makeLocalParticipant: { SignalingProviderParticipant(peerId: $0, joinedAt: nil) }
        )
    }
}
