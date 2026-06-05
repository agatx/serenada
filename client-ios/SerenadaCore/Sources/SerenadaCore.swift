import Foundation

/// Reason a call ended.
public enum EndReason: Equatable, Sendable {
    case localLeft
    case remoteEnded
    case error(String)
}

/// Reason a dropout began, carried so hosts can distinguish recovery causes.
public enum DropoutTrigger: Equatable, Sendable {
    /// Dropout began with signaling/network loss.
    case networkLost
    /// Dropout cause could not be attributed to network loss (e.g. ICE/peer-level).
    case unknown
}

/// Connection-quality event emitted by the SDK through
/// ``SerenadaCoreDelegate/sessionDidEmitConnectionEvent(_:event:)``.
public enum ConnectionEvent: Equatable, Sendable {
    /// A dropout recovered. Maps to the host's reconnect analytics.
    /// - Parameters:
    ///   - downtimeMs: downtime of the recovered dropout, in ms.
    ///   - reason: `networkLost` if the dropout began with signaling/network loss, else `unknown`.
    case reconnected(downtimeMs: Int64, reason: DropoutTrigger)

    /// Recovery was abandoned. Maps to the host's reconnect-failed analytics.
    case reconnectFailed(reason: ReconnectFailedReason)

    public enum ReconnectFailedReason: Equatable, Sendable {
        /// Recovery window elapsed.
        case timeout
        /// No network / transport available.
        case networkConnectivity
    }
}

/// Delegate for session lifecycle events (state changes, permissions, call end).
@MainActor
public protocol SerenadaCoreDelegate: AnyObject {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability])
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState)
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason)
    /// Called when the SDK raises a connection-quality event.
    /// Additive, default no-op — read aggregate quality via
    /// ``SerenadaSession/qualitySummary``.
    func sessionDidEmitConnectionEvent(_ session: SerenadaSession, event: ConnectionEvent)
}

public extension SerenadaCoreDelegate {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability]) {}
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState) {}
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason) {}
    func sessionDidEmitConnectionEvent(_ session: SerenadaSession, event: ConnectionEvent) {}
}

/// Result of creating a new room. Call `join` to start the call.
public struct CreateRoomResult {
    /// Full URL for the created room.
    public let url: URL
    /// Room identifier.
    public let roomId: String

    public init(url: URL, roomId: String) {
        self.url = url
        self.roomId = roomId
    }
}

/// Main entry point for the Serenada SDK. Create an instance with ``SerenadaConfig``,
/// then use ``join(url:displayName:peerId:)`` or ``createRoom()`` to start a call.
@MainActor
public final class SerenadaCore {
    /// SDK version string.
    public static let version = "0.8.3"

    /// SDK configuration.
    public let config: SerenadaConfig
    private let resolvedConfig: ResolvedSerenadaConfig
    /// Delegate for session lifecycle callbacks.
    public weak var delegate: SerenadaCoreDelegate?
    /// Optional logger for SDK diagnostics.
    public var logger: SerenadaLogger?

    /// Cross-launch recovery store for in-flight call state. Default
    /// `UserDefaults.standard`; the host app can replace it with an
    /// app-group-scoped store before opening any session.
    public let recoveryStorage: RecoveryStorage

    public init(config: SerenadaConfig, recoveryStorage: RecoveryStorage = RecoveryStorage()) {
        self.config = config
        self.recoveryStorage = recoveryStorage
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
    }

    /// Returns a recoverable session if the previous process ended abruptly
    /// (force-quit, jetsam, OS kill) while a call was active and the
    /// persisted reconnect token is still within its TTL. Host apps should
    /// call this on launch and surface a "Rejoin call?" prompt — calling
    /// `join(roomId:)` with the returned `roomId` reattaches under the same
    /// CID. Returns `nil` when there is nothing to recover.
    public func getRecoverableSession() -> RecoveryRecord? {
        recoveryStorage.load()
    }

    /// Drops any persisted recovery record. Call this when the user
    /// explicitly declines the rejoin prompt so subsequent launches do not
    /// keep offering the same dead session.
    public func discardRecoverableSession() {
        recoveryStorage.clear()
    }

    /// Join an existing call by URL. Returns a session that begins connecting immediately.
    ///
    /// - Parameters:
    ///   - url: Full Serenada call URL.
    ///   - displayName: Optional display name for the local participant.
    ///   - peerId: Optional host-supplied stable identity for this user
    ///     (distinct from the per-call client ID). Surfaced on remote participants so
    ///     the call UI can resolve avatars via `SerenadaCallFlowConfig.avatarProvider`.
    public func join(url: URL, displayName: String? = nil, peerId: String? = nil) -> SerenadaSession {
        let roomId = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        let target = DeepLinkParser.parseTarget(from: url)
        let serverHost = target?.host
            ?? DeepLinkParser.normalizeHostValue(authorityHost(from: url))
            ?? resolvedConfig.serverHost
        let sessionConfig: SerenadaConfig
        if resolvedConfig.serverHost != nil {
            sessionConfig = SerenadaConfig(
                serverHost: serverHost,
                signalingProvider: nil,
                defaultAudioEnabled: config.defaultAudioEnabled,
                defaultVideoEnabled: config.defaultVideoEnabled,
                cameraModes: config.cameraModes,
                transports: config.transports,
                proximityMonitoringEnabled: config.proximityMonitoringEnabled,
                audioCoordinator: config.audioCoordinator,
                audioIntent: config.audioIntent
            )
        } else {
            sessionConfig = config
        }
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            config: sessionConfig,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger,
            initialSignalingProvider: createSignalingProvider(for: sessionConfig),
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage
        )
        return session
    }

    /// Join an existing call by room ID. Returns a session that begins connecting immediately.
    ///
    /// - Parameters:
    ///   - roomId: Bare room identifier.
    ///   - displayName: Optional display name for the local participant.
    ///   - peerId: Optional host-supplied stable identity; see the URL ``join(url:displayName:peerId:)`` overload.
    public func join(roomId: String, displayName: String? = nil, peerId: String? = nil) -> SerenadaSession {
        let url = resolvedConfig.serverHost.flatMap { buildRoomURL(host: $0, roomId: roomId) }

        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            config: config,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger,
            initialSignalingProvider: createSignalingProvider(for: config),
            displayName: displayName,
            peerId: peerId,
            recoveryStorage: recoveryStorage
        )
        return session
    }

    /// Create a new room. Returns the room URL and ID. Call ``join(url:displayName:peerId:)`` or ``join(roomId:displayName:peerId:)`` to start the call.
    public func createRoom() async throws -> CreateRoomResult {
        let apiClient = CoreAPIClient()
        let serverHost = try requireServerHost(config)
        let roomId = try await apiClient.createRoomId(host: serverHost)
        guard let url = buildRoomURL(host: serverHost, roomId: roomId) else {
            throw APIError.invalidResponse("Failed to build room URL")
        }
        return CreateRoomResult(url: url, roomId: roomId)
    }

    /// Create a room ID without starting a session.
    /// Use this when you only need a room ID (e.g., for invite links).
    public func createRoomId() async throws -> String {
        let apiClient = CoreAPIClient()
        return try await apiClient.createRoomId(host: requireServerHost(config))
    }

    private func buildRoomURL(host: String, roomId: String) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        let isLocal = parsedHost.host == "localhost" || parsedHost.host.hasPrefix("127.")
        var components = URLComponents()
        components.scheme = isLocal ? "http" : "https"
        components.host = parsedHost.host
        components.port = parsedHost.port
        components.path = "/call/\(roomId)"
        return components.url
    }

    private func authorityHost(from url: URL) -> String? {
        guard let host = url.host else { return nil }
        if let port = url.port {
            return "\(host):\(port)"
        }
        return host
    }

    private func createSignalingProvider(for sessionConfig: SerenadaConfig) -> SignalingProvider {
        let resolved: ResolvedSerenadaConfig
        do {
            resolved = try resolveSerenadaConfig(sessionConfig)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
        if let serverHost = resolved.serverHost {
            return SerenadaServerProvider(
                serverHost: serverHost,
                apiClient: CoreAPIClient(),
                transports: sessionConfig.transports,
                logger: logger
            )
        }
        guard let signalingProvider = resolved.signalingProvider else {
            preconditionFailure("Provide exactly one of serverHost or signalingProvider")
        }
        return signalingProvider
    }
}
