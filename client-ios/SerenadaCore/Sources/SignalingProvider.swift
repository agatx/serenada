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
    public var displayName: String?
    /// Host-supplied stable identity. Distinct from `peerId`/cid (per-call,
    /// server-issued) — lets host applications correlate a participant to their
    /// own user identity (avatar lookup, telemetry).
    public var appPeerId: String?

    public init(
        reconnectPeerId: String? = nil,
        maxParticipants: Int? = nil,
        displayName: String? = nil,
        appPeerId: String? = nil
    ) {
        self.reconnectPeerId = reconnectPeerId
        self.maxParticipants = maxParticipants
        self.displayName = displayName
        self.appPeerId = appPeerId
    }
}

public struct SignalingProviderParticipantContentState: Equatable, Sendable {
    public let active: Bool
    public let contentType: String?
    public let updatedAtMs: Int64?
    public let epoch: Int64?

    public init(
        active: Bool,
        contentType: String? = nil,
        updatedAtMs: Int64? = nil,
        epoch: Int64? = nil
    ) {
        self.active = active
        self.contentType = contentType
        self.updatedAtMs = updatedAtMs
        self.epoch = epoch
    }
}

public struct SignalingProviderParticipant: Equatable, Sendable {
    public let peerId: String
    public let joinedAt: Int64?
    public let displayName: String?
    /// Host-supplied stable identity — see `JoinOptions.appPeerId`.
    public let appPeerId: String?
    public let audioEnabled: Bool?
    public let videoEnabled: Bool?
    public let signalingStatus: ParticipantSignalingStatus
    public let contentState: SignalingProviderParticipantContentState?

    public init(
        peerId: String,
        joinedAt: Int64? = nil,
        displayName: String? = nil,
        appPeerId: String? = nil,
        audioEnabled: Bool? = nil,
        videoEnabled: Bool? = nil,
        signalingStatus: ParticipantSignalingStatus = .active,
        contentState: SignalingProviderParticipantContentState? = nil
    ) {
        self.peerId = peerId
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.appPeerId = appPeerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.signalingStatus = signalingStatus
        self.contentState = contentState
    }
}

public struct JoinedEvent: Equatable, Sendable {
    public let peerId: String
    public let participants: [SignalingProviderParticipant]
    public let hostPeerId: String?
    public let maxParticipants: Int?
    /// Server room-state epoch on this transport; monotonic per room.
    public let epoch: Int64?
    /// How the server treated this join. nil for older providers.
    public let reconnectOutcome: ReconnectOutcome?
    /// Server-issued reconnect token from `joined.reconnectToken`. The
    /// session uses this to populate the cross-launch recovery record;
    /// the provider also keeps an internal copy for transport reconnects.
    public let reconnectToken: String?
    /// How long (ms) the server is willing to honor `reconnectToken`.
    public let reconnectTokenTTLMs: Int64?

    public init(
        peerId: String,
        participants: [SignalingProviderParticipant],
        hostPeerId: String? = nil,
        maxParticipants: Int? = nil,
        epoch: Int64? = nil,
        reconnectOutcome: ReconnectOutcome? = nil,
        reconnectToken: String? = nil,
        reconnectTokenTTLMs: Int64? = nil
    ) {
        self.peerId = peerId
        self.participants = participants
        self.hostPeerId = hostPeerId
        self.maxParticipants = maxParticipants
        self.epoch = epoch
        self.reconnectOutcome = reconnectOutcome
        self.reconnectToken = reconnectToken
        self.reconnectTokenTTLMs = reconnectTokenTTLMs
    }
}

public struct RoomStateEvent: Equatable, Sendable {
    public let participants: [SignalingProviderParticipant]
    public let hostPeerId: String?
    public let maxParticipants: Int?
    /// Server room-state epoch on this transport; monotonic per room.
    public let epoch: Int64?

    public init(
        participants: [SignalingProviderParticipant],
        hostPeerId: String? = nil,
        maxParticipants: Int? = nil,
        epoch: Int64? = nil
    ) {
        self.participants = participants
        self.hostPeerId = hostPeerId
        self.maxParticipants = maxParticipants
        self.epoch = epoch
    }
}

public struct PeerEvent: Equatable, Sendable {
    public let peerId: String
    public let joinedAt: Int64?
    public let displayName: String?
    /// Host-supplied stable identity — see `JoinOptions.appPeerId`.
    public let appPeerId: String?

    public init(peerId: String, joinedAt: Int64? = nil, displayName: String? = nil, appPeerId: String? = nil) {
        self.peerId = peerId
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.appPeerId = appPeerId
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

/// Server tells an active peer that a previously-suspended peer has reattached
/// AND there was pending negotiation traffic to it during the suspension. The
/// SDK should perform glare-safe fresh negotiation / ICE restart for the
/// named CID.
public struct NegotiationDirtyEvent: Equatable, Sendable {
    /// The CID that needs fresh renegotiation.
    public let withCid: String

    public init(withCid: String) {
        self.withCid = withCid
    }
}

/// Server tells the sender it could not deliver a relay because the target had no transport.
public struct RelayFailedEvent: Equatable, Sendable {
    /// Server-assigned reason code, e.g. `"target_suspended"`.
    public let reason: String
    /// Target CIDs the relay could not reach.
    public let targets: [String]
    /// Original signaling type that failed, e.g. `"offer" | "answer" | "ice"`.
    public let of: String?

    public init(reason: String, targets: [String], of: String? = nil) {
        self.reason = reason
        self.targets = targets
        self.of = of
    }
}

public struct ReconnectTokenRefreshedEvent: Equatable, Sendable {
    public let reconnectToken: String
    public let reconnectTokenTTLMs: Int64?

    public init(reconnectToken: String, reconnectTokenTTLMs: Int64? = nil) {
        self.reconnectToken = reconnectToken
        self.reconnectTokenTTLMs = reconnectTokenTTLMs
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
    func signalingProviderDidReceiveNegotiationDirty(_ event: NegotiationDirtyEvent)
    func signalingProviderDidReceiveRelayFailed(_ event: RelayFailedEvent)
    func signalingProviderDidRefreshReconnectToken(_ event: ReconnectTokenRefreshedEvent)
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
    func signalingProviderDidReceiveNegotiationDirty(_ event: NegotiationDirtyEvent) {}
    func signalingProviderDidReceiveRelayFailed(_ event: RelayFailedEvent) {}
    func signalingProviderDidRefreshReconnectToken(_ event: ReconnectTokenRefreshedEvent) {}
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

    /// Hook the SDK calls when the host app returns to foreground after a
    /// background period long enough that the OS may have silently killed
    /// the underlying transport. The expected behavior for transport-owning
    /// providers is to send a synthetic ping and arm a `timeoutMs` deadline,
    /// then force-close the transport on miss so the normal reconnect path
    /// runs. Default is no-op for providers that manage their own lifecycle.
    func forceReconnectIfStale(timeoutMs: Int)
}

public extension SignalingProvider {
    var version: Int { SUPPORTED_SIGNALING_PROVIDER_VERSION }
    var capabilities: ProviderCapabilities { ProviderCapabilities() }

    func joinRoom(_ roomId: String) {
        joinRoom(roomId, options: JoinOptions())
    }

    func forceReconnectIfStale(timeoutMs: Int) {}
}
