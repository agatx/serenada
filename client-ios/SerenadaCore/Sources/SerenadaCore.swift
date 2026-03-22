import Foundation

public enum EndReason: Equatable, Sendable {
    case localLeft
    case remoteEnded
    case error(String)
}

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

public struct CreateRoomResult {
    public let url: URL
    public let roomId: String
    public let session: SerenadaSession

    public init(url: URL, roomId: String, session: SerenadaSession) {
        self.url = url
        self.roomId = roomId
        self.session = session
    }
}

@MainActor
public final class SerenadaCore {
    public static let version = "0.1.0"

    public let config: SerenadaConfig
    public weak var delegate: SerenadaCoreDelegate?
    public var logger: SerenadaLogger?

    public init(config: SerenadaConfig) {
        self.config = config
    }

    public func join(url: URL) -> SerenadaSession {
        let roomId = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        let target = DeepLinkParser.parseTarget(from: url)
        let serverHost = target?.host
            ?? DeepLinkParser.normalizeHostValue(authorityHost(from: url))
            ?? config.serverHost
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            serverHost: serverHost,
            config: config,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger
        )
        return session
    }

    public func join(roomId: String) -> SerenadaSession {
        let url = buildRoomURL(host: config.serverHost, roomId: roomId)

        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            serverHost: config.serverHost,
            config: config,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger
        )
        return session
    }

    public func createRoom() async throws -> CreateRoomResult {
        let apiClient = CoreAPIClient()
        let serverHost = config.serverHost
        let config = self.config
        let roomId = try await apiClient.createRoomId(host: serverHost)
        guard let url = buildRoomURL(host: serverHost, roomId: roomId) else {
            throw APIError.invalidResponse("Failed to build room URL")
        }

        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            serverHost: serverHost,
            config: config,
            delegateProvider: { [weak self] in self?.delegate },
            logger: logger
        )
        return CreateRoomResult(url: url, roomId: roomId, session: session)
    }

    /// Create a room ID without starting a session.
    /// Use this when you only need a room ID (e.g., for invite links).
    public func createRoomId() async throws -> String {
        let apiClient = CoreAPIClient()
        return try await apiClient.createRoomId(host: config.serverHost)
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
}
