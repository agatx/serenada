import Foundation

/// Reason a call ended.
public enum EndReason: Equatable, Sendable {
    case localLeft
    case remoteEnded
    case error(String)
}

/// Delegate for session lifecycle events (state changes, permissions, call end).
@MainActor
public protocol SerenadaCoreDelegate: AnyObject {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability])
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState)
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason)
}

public extension SerenadaCoreDelegate {
    func sessionRequiresPermissions(_ session: SerenadaSession, permissions: [MediaCapability]) {}
    func sessionDidChangeState(_ session: SerenadaSession, state: CallState) {}
    func sessionDidEnd(_ session: SerenadaSession, reason: EndReason) {}
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

/// Main entry point for the Serenada SDK. Create an instance with ``SerenadaConfig``, then use ``join(url:)`` or ``createRoom()`` to start a call.
@MainActor
public final class SerenadaCore {
    /// SDK version string.
    public static let version = "0.5.1"

    /// SDK configuration.
    public let config: SerenadaConfig
    private let resolvedConfig: ResolvedSerenadaConfig
    /// Delegate for session lifecycle callbacks.
    public weak var delegate: SerenadaCoreDelegate?
    /// Optional logger for SDK diagnostics.
    public var logger: SerenadaLogger?

    public init(config: SerenadaConfig) {
        self.config = config
        do {
            self.resolvedConfig = try resolveSerenadaConfig(config)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
    }

    /// Join an existing call by URL. Returns a session that begins connecting immediately.
    ///
    /// - Parameter peerId: Optional host-supplied stable identity for this user
    ///   (distinct from the per-call client ID). Surfaced on remote participants so
    ///   the call UI can resolve avatars via `SerenadaCallFlowConfig.avatarProvider`.
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
                transports: config.transports,
                proximityMonitoringEnabled: config.proximityMonitoringEnabled
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
            peerId: peerId
        )
        return session
    }

    /// Join an existing call by room ID. Returns a session that begins connecting immediately.
    ///
    /// - Parameter peerId: Optional host-supplied stable identity — see the URL ``join(url:displayName:peerId:)`` overload.
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
            peerId: peerId
        )
        return session
    }

    /// Create a new room. Returns the room URL and ID. Call ``join(url:displayName:)`` or ``join(roomId:displayName:)`` to start the call.
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
