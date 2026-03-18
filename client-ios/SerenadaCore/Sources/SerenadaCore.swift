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

    public init(config: SerenadaConfig) {
        self.config = config
    }

    public func join(url: URL) -> SerenadaSession {
        let roomId = DeepLinkParser.extractRoomId(from: url) ?? url.lastPathComponent
        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            serverHost: config.serverHost,
            config: config
        )
        return session
    }

    public func join(roomId: String) -> SerenadaSession {
        var components = URLComponents()
        components.scheme = "https"
        components.host = config.serverHost
        components.path = "/call/\(roomId)"
        let url = components.url

        let session = SerenadaSession(
            roomId: roomId,
            roomUrl: url,
            serverHost: config.serverHost,
            config: config
        )
        return session
    }

    public func createRoom(completion: @escaping (Result<CreateRoomResult, Error>) -> Void) {
        let apiClient = CoreAPIClient()
        let serverHost = config.serverHost
        let config = self.config
        Task {
            do {
                let roomId = try await apiClient.createRoomId(host: serverHost)
                var components = URLComponents()
                components.scheme = "https"
                components.host = serverHost
                components.path = "/call/\(roomId)"
                guard let url = components.url else {
                    completion(.failure(APIError.invalidResponse("Failed to build room URL")))
                    return
                }

                let session = SerenadaSession(
                    roomId: roomId,
                    roomUrl: url,
                    serverHost: serverHost,
                    config: config
                )
                completion(.success(CreateRoomResult(url: url, roomId: roomId, session: session)))
            } catch {
                completion(.failure(error))
            }
        }
    }
}
