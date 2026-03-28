import Foundation

public typealias SignalingPayload = [String: JSONValue]

public struct ProviderCapabilities: Equatable, Sendable {
    public var handlesReconnection: Bool

    public init(handlesReconnection: Bool = false) {
        self.handlesReconnection = handlesReconnection
    }
}

public struct ConnectionInfo: Equatable, Sendable {
    public var transport: String?

    public init(transport: String? = nil) {
        self.transport = transport
    }
}

public struct JoinOptions: Equatable, Sendable {
    public var reconnectPeerId: String?
    public var maxParticipants: Int?

    public init(reconnectPeerId: String? = nil, maxParticipants: Int? = nil) {
        self.reconnectPeerId = reconnectPeerId
        self.maxParticipants = maxParticipants
    }
}

public struct SignalingProviderParticipant: Equatable, Sendable {
    public let peerId: String
    public let joinedAt: Int64?

    public init(peerId: String, joinedAt: Int64? = nil) {
        self.peerId = peerId
        self.joinedAt = joinedAt
    }
}

public struct JoinedEvent: Equatable, Sendable {
    public let peerId: String
    public let participants: [SignalingProviderParticipant]
    public let hostPeerId: String?
    public let maxParticipants: Int?

    public init(
        peerId: String,
        participants: [SignalingProviderParticipant],
        hostPeerId: String? = nil,
        maxParticipants: Int? = nil
    ) {
        self.peerId = peerId
        self.participants = participants
        self.hostPeerId = hostPeerId
        self.maxParticipants = maxParticipants
    }
}

public struct RoomStateEvent: Equatable, Sendable {
    public let participants: [SignalingProviderParticipant]
    public let hostPeerId: String?
    public let maxParticipants: Int?

    public init(
        participants: [SignalingProviderParticipant],
        hostPeerId: String? = nil,
        maxParticipants: Int? = nil
    ) {
        self.participants = participants
        self.hostPeerId = hostPeerId
        self.maxParticipants = maxParticipants
    }
}

public struct PeerEvent: Equatable, Sendable {
    public let peerId: String
    public let joinedAt: Int64?

    public init(peerId: String, joinedAt: Int64? = nil) {
        self.peerId = peerId
        self.joinedAt = joinedAt
    }
}

public struct PeerMessage: Equatable, Sendable {
    public let from: String
    public let type: String
    public let payload: SignalingPayload?

    public init(from: String, type: String, payload: SignalingPayload? = nil) {
        self.from = from
        self.type = type
        self.payload = payload
    }
}

public struct RoomEndedEvent: Equatable, Sendable {
    public let by: String?
    public let reason: String

    public init(by: String? = nil, reason: String) {
        self.by = by
        self.reason = reason
    }
}

public struct ErrorEvent: Equatable, Sendable {
    public let code: String
    public let message: String

    public init(code: String, message: String) {
        self.code = code
        self.message = message
    }
}

/// Receives provider events. Implementations may invoke these callbacks from
/// any thread; the SDK session layer hops back to the main actor before
/// mutating observable SDK state.
public protocol SignalingProviderDelegate: AnyObject {
    func signalingProviderDidConnect(_ info: ConnectionInfo)
    func signalingProviderDidDisconnect(reason: String?)
    func signalingProviderDidJoin(_ event: JoinedEvent)
    func signalingProviderDidUpdateRoomState(_ event: RoomStateEvent)
    func signalingProviderDidJoinPeer(_ event: PeerEvent)
    func signalingProviderDidLeavePeer(_ event: PeerEvent)
    func signalingProviderDidReceiveMessage(_ message: PeerMessage)
    func signalingProviderDidEndRoom(_ event: RoomEndedEvent)
    func signalingProviderDidReceiveError(_ event: ErrorEvent)
    func signalingProviderDidChangeIceServers(_ iceServers: [IceServerConfig])
}

public extension SignalingProviderDelegate {
    func signalingProviderDidConnect(_ info: ConnectionInfo) {}
    func signalingProviderDidDisconnect(reason: String?) {}
    func signalingProviderDidJoin(_ event: JoinedEvent) {}
    func signalingProviderDidUpdateRoomState(_ event: RoomStateEvent) {}
    func signalingProviderDidJoinPeer(_ event: PeerEvent) {}
    func signalingProviderDidLeavePeer(_ event: PeerEvent) {}
    func signalingProviderDidReceiveMessage(_ message: PeerMessage) {}
    func signalingProviderDidEndRoom(_ event: RoomEndedEvent) {}
    func signalingProviderDidReceiveError(_ event: ErrorEvent) {}
    func signalingProviderDidChangeIceServers(_ iceServers: [IceServerConfig]) {}
}

/// Transport-agnostic signaling contract for iOS SDK sessions.
///
/// The SDK invokes provider methods and property access from the main actor.
/// Custom providers should therefore remain main-thread confined unless they
/// perform their own synchronization internally.
public protocol SignalingProvider: AnyObject {
    var version: Int { get }
    var capabilities: ProviderCapabilities { get }
    var delegate: SignalingProviderDelegate? { get set }

    func connect()
    func disconnect()
    func joinRoom(_ roomId: String, options: JoinOptions)
    func leaveRoom()
    func endRoom()
    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?)
    func broadcast(type: String, payload: SignalingPayload?)
    func getIceServers() async throws -> [IceServerConfig]
}

public extension SignalingProvider {
    var version: Int { SUPPORTED_SIGNALING_PROVIDER_VERSION }
    var capabilities: ProviderCapabilities { ProviderCapabilities() }

    func joinRoom(_ roomId: String) {
        joinRoom(roomId, options: JoinOptions())
    }
}
