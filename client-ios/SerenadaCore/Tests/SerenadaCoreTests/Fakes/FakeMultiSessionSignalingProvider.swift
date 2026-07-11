import Foundation
@testable import SerenadaCore

/// A recordable single-session channel vended by ``FakeMultiSessionSignalingProvider``.
///
/// Behaves like the real per-session surface: on `connect()` it fires `didConnect`,
/// and on `joinRoom()` it fires `didJoin` with its OWN participants (deferred
/// through `Task { @MainActor }` to match the real delegate-proxy hop), so a held
/// room join over this channel settles on its own. Every outbound op and lifecycle
/// call is recorded so a test can assert per-channel isolation. `simulate*` helpers
/// feed inbound events into ONLY this channel's session.
final class RecordingSignalingChannel: SignalingProvider, @unchecked Sendable {
    /// Canonical room this channel is permanently bound to.
    let roomId: String
    private let localCid: String
    private let remoteCid: String?
    private let autoJoin: Bool
    weak var delegate: SignalingProviderDelegate?

    private(set) var connectCalls = 0
    private(set) var disconnectCalls = 0
    private(set) var joinedRoomIds: [String] = []
    private(set) var leaveCalls = 0
    private(set) var endCalls = 0
    private(set) var sentToPeer: [(peerId: String, type: String, payload: SignalingPayload?)] = []
    private(set) var broadcasts: [(type: String, payload: SignalingPayload?)] = []
    private(set) var getIceServersCallCount = 0
    private(set) var forceReconnectCalls = 0

    /// True once the SDK has closed this channel (`disconnect()`), regardless of how
    /// many times close was called (idempotence: a second close is tolerated).
    var isClosed: Bool { disconnectCalls > 0 }

    init(roomId: String, localCid: String, remoteCid: String?, autoJoin: Bool) {
        self.roomId = roomId
        self.localCid = localCid
        self.remoteCid = remoteCid
        self.autoJoin = autoJoin
    }

    func connect() {
        connectCalls += 1
        let delegate = self.delegate
        Task { @MainActor in
            delegate?.signalingProviderDidConnect(ConnectionInfo(transport: "ws"))
        }
    }

    func disconnect() {
        disconnectCalls += 1
    }

    func joinRoom(_ roomId: String, options: JoinOptions) {
        joinedRoomIds.append(roomId)
        guard autoJoin else { return }
        let delegate = self.delegate
        let localCid = self.localCid
        let remoteCid = self.remoteCid
        Task { @MainActor in
            var participants = [SignalingProviderParticipant(peerId: localCid, joinedAt: 1)]
            if let remoteCid {
                participants.append(SignalingProviderParticipant(peerId: remoteCid, joinedAt: 2))
            }
            delegate?.signalingProviderDidJoin(
                JoinedEvent(
                    peerId: localCid,
                    participants: participants,
                    hostPeerId: localCid,
                    maxParticipants: 4
                )
            )
        }
    }

    func leaveRoom() { leaveCalls += 1 }
    func endRoom() { endCalls += 1 }

    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {
        sentToPeer.append((peerId: peerId, type: type, payload: payload))
    }

    func broadcast(type: String, payload: SignalingPayload?) {
        broadcasts.append((type: type, payload: payload))
    }

    func getIceServers() async throws -> [IceServerConfig] {
        getIceServersCallCount += 1
        return []
    }

    func forceReconnectIfStale(timeoutMs: Int) {
        forceReconnectCalls += 1
    }

    // MARK: - Inbound event injection (this channel only)

    @MainActor
    func simulatePeerJoined(peerId: String, joinedAt: Int64? = nil) {
        delegate?.signalingProviderDidJoinPeer(PeerEvent(peerId: peerId, joinedAt: joinedAt))
    }

    @MainActor
    func simulateMessage(from: String, type: String, payload: SignalingPayload? = nil) {
        delegate?.signalingProviderDidReceiveMessage(PeerMessage(from: from, type: type, payload: payload))
    }

    @MainActor
    func simulateRoomEnded(by: String? = nil, reason: String = "room ended") {
        delegate?.signalingProviderDidEndRoom(RoomEndedEvent(by: by, reason: reason))
    }
}

/// ONE global fake v2 service that vends a fresh ``RecordingSignalingChannel`` per
/// session join. Mirrors the built-in server mode (a fresh provider per session)
/// while letting a test drive two concurrent sessions through the real registry
/// path over a single configured service.
final class FakeMultiSessionSignalingProvider: MultiSessionSignalingProvider, @unchecked Sendable {
    let version: Int
    private let autoJoin: Bool
    /// Resolves the `(local, remote)` CIDs a channel reports at join, keyed by room.
    /// Default gives each room distinct CIDs so cross-session isolation is testable.
    private let cidsForRoom: (String) -> (local: String, remote: String?)

    /// Room ids passed to `openSession`, in call order (one per session join).
    private(set) var openedRoomIds: [String] = []
    /// Every channel this service has vended.
    private(set) var channels: [RecordingSignalingChannel] = []
    private(set) var getIceServersCallCount = 0

    init(
        version: Int = MULTI_SESSION_SIGNALING_PROVIDER_VERSION,
        autoJoin: Bool = true,
        cidsForRoom: @escaping (String) -> (local: String, remote: String?) = { room in
            (local: "local-\(room)", remote: "remote-\(room)")
        }
    ) {
        self.version = version
        self.autoJoin = autoJoin
        self.cidsForRoom = cidsForRoom
    }

    func openSession(roomId: String) -> SignalingProvider {
        openedRoomIds.append(roomId)
        let cids = cidsForRoom(roomId)
        let channel = RecordingSignalingChannel(
            roomId: roomId,
            localCid: cids.local,
            remoteCid: cids.remote,
            autoJoin: autoJoin
        )
        channels.append(channel)
        return channel
    }

    func getIceServers() async throws -> [IceServerConfig] {
        getIceServersCallCount += 1
        return []
    }

    /// The first (typically only) channel vended for `roomId`.
    func channel(forRoomId roomId: String) -> RecordingSignalingChannel? {
        channels.first { $0.roomId == roomId }
    }

    /// How many times `openSession` was called for `roomId`.
    func openCount(forRoomId roomId: String) -> Int {
        openedRoomIds.filter { $0 == roomId }.count
    }
}
