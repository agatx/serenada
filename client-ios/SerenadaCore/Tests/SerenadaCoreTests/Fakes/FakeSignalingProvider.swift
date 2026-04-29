import Foundation
@testable import SerenadaCore

final class FakeSignalingProvider: SignalingProvider {
    let version: Int
    let capabilities: ProviderCapabilities
    weak var delegate: SignalingProviderDelegate?

    private(set) var connectCalls = 0
    private(set) var disconnectCalls = 0
    private(set) var joinCalls: [(roomId: String, options: JoinOptions)] = []
    private(set) var leaveCalls = 0
    private(set) var endCalls = 0
    private(set) var sentToPeer: [(peerId: String, type: String, payload: SignalingPayload?)] = []
    private(set) var broadcasts: [(type: String, payload: SignalingPayload?)] = []
    private(set) var getIceServersCallCount = 0

    var iceServerResults: [Result<[IceServerConfig], Error>] = [.success([])]

    init(version: Int = SUPPORTED_SIGNALING_PROVIDER_VERSION, handlesReconnection: Bool = false) {
        self.version = version
        self.capabilities = ProviderCapabilities(handlesReconnection: handlesReconnection)
    }

    func connect() {
        connectCalls += 1
    }

    func disconnect() {
        disconnectCalls += 1
    }

    func joinRoom(_ roomId: String, options: JoinOptions) {
        joinCalls.append((roomId: roomId, options: options))
    }

    func leaveRoom() {
        leaveCalls += 1
    }

    func endRoom() {
        endCalls += 1
    }

    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {
        sentToPeer.append((peerId: peerId, type: type, payload: payload))
    }

    func broadcast(type: String, payload: SignalingPayload?) {
        broadcasts.append((type: type, payload: payload))
    }

    func getIceServers() async throws -> [IceServerConfig] {
        getIceServersCallCount += 1
        let result = iceServerResults.isEmpty ? .success([]) : iceServerResults.removeFirst()
        return try result.get()
    }

    func sentPeerMessages(ofType type: String) -> [(peerId: String, type: String, payload: SignalingPayload?)] {
        sentToPeer.filter { $0.type == type }
    }

    func broadcastMessages(ofType type: String) -> [(type: String, payload: SignalingPayload?)] {
        broadcasts.filter { $0.type == type }
    }

    func enqueueIceServerResult(_ result: Result<[IceServerConfig], Error>) {
        iceServerResults.append(result)
    }

    func simulateConnected(transport: String = "ws") {
        delegate?.signalingProviderDidConnect(ConnectionInfo(transport: transport))
    }

    func simulateDisconnected(reason: String = "test") {
        delegate?.signalingProviderDidDisconnect(reason: reason)
    }

    func simulateJoined(
        peerId: String = "local-cid-1",
        participants: [SignalingProviderParticipant] = [],
        hostPeerId: String? = nil,
        maxParticipants: Int? = nil
    ) {
        let resolvedParticipants = participants.isEmpty
            ? [SignalingProviderParticipant(peerId: peerId, joinedAt: 1)]
            : participants
        delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: peerId,
                participants: resolvedParticipants,
                hostPeerId: hostPeerId ?? peerId,
                maxParticipants: maxParticipants
            )
        )
    }

    func simulateJoinedWithoutHost(
        peerId: String = "local-cid-1",
        participants: [SignalingProviderParticipant] = [],
        maxParticipants: Int? = nil
    ) {
        let resolvedParticipants = participants.isEmpty
            ? [SignalingProviderParticipant(peerId: peerId, joinedAt: 1)]
            : participants
        delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: peerId,
                participants: resolvedParticipants,
                hostPeerId: nil,
                maxParticipants: maxParticipants
            )
        )
    }

    func simulateRoomState(
        participants: [SignalingProviderParticipant],
        hostPeerId: String,
        maxParticipants: Int? = nil
    ) {
        delegate?.signalingProviderDidUpdateRoomState(
            RoomStateEvent(
                participants: participants,
                hostPeerId: hostPeerId,
                maxParticipants: maxParticipants
            )
        )
    }

    func simulatePeerJoined(peerId: String, joinedAt: Int64? = nil) {
        delegate?.signalingProviderDidJoinPeer(PeerEvent(peerId: peerId, joinedAt: joinedAt))
    }

    func simulatePeerLeft(peerId: String, joinedAt: Int64? = nil) {
        delegate?.signalingProviderDidLeavePeer(PeerEvent(peerId: peerId, joinedAt: joinedAt))
    }

    func simulateMessage(from: String, type: String, payload: SignalingPayload? = nil) {
        delegate?.signalingProviderDidReceiveMessage(PeerMessage(from: from, type: type, payload: payload))
    }

    func simulateRoomEnded(by: String? = nil, reason: String = "room ended") {
        delegate?.signalingProviderDidEndRoom(RoomEndedEvent(by: by, reason: reason))
    }

    func simulateError(code: String, message: String) {
        delegate?.signalingProviderDidReceiveError(ErrorEvent(code: code, message: message))
    }

    func simulateIceServersChanged(_ iceServers: [IceServerConfig]) {
        delegate?.signalingProviderDidChangeIceServers(iceServers)
    }

    func simulateNegotiationDirty(withCid: String) {
        delegate?.signalingProviderDidReceiveNegotiationDirty(NegotiationDirtyEvent(withCid: withCid))
    }

    func simulateRelayFailed(reason: String, targets: [String], of: String? = nil) {
        delegate?.signalingProviderDidReceiveRelayFailed(RelayFailedEvent(reason: reason, targets: targets, of: of))
    }
}
