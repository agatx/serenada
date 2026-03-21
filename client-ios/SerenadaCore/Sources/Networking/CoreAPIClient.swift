import Foundation

final class CoreAPIClient: SessionAPIClient {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func validateServerHost(_ host: String) async throws {
        guard let url = buildHTTPSURL(host: host, path: "/api/room-id") else {
            throw APIError.invalidHost
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Host validation failed")
        }
        _ = try parseRoomIdResponse(data)
    }

    func createRoomId(host: String) async throws -> String {
        guard let url = buildHTTPSURL(host: host, path: "/api/room-id") else {
            throw APIError.invalidHost
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Room ID request failed")
        }
        return try parseRoomIdResponse(data)
    }

    func fetchTurnCredentials(host: String, token: String) async throws -> TurnCredentials {
        guard let url = buildHTTPSURL(host: host, path: "/api/turn-credentials", query: ["token": token]) else {
            throw APIError.invalidHost
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("TURN credentials failed")
        }

        let decoded = try JSONDecoder().decode(TurnCredentials.self, from: data)
        guard !decoded.username.isEmpty, !decoded.password.isEmpty, !decoded.uris.isEmpty else {
            throw APIError.invalidResponse("Invalid TURN credentials")
        }

        return decoded
    }

    func fetchDiagnosticToken(host: String) async throws -> String {
        guard let url = buildHTTPSURL(host: host, path: "/api/diagnostic-token") else {
            throw APIError.invalidHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Diagnostic token failed")
        }

        let decoded = try JSONDecoder().decode(DiagnosticTokenResponse.self, from: data)
        let token = decoded.token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            throw APIError.invalidResponse("Diagnostic token missing")
        }
        return token
    }

    private func parseRoomIdResponse(_ data: Data) throws -> String {
        let decoded = try JSONDecoder().decode(RoomIdResponse.self, from: data)
        guard !decoded.roomId.isEmpty else {
            throw APIError.invalidResponse("Room ID missing")
        }
        return decoded.roomId
    }

    func buildHTTPSURL(host: String, path: String, query: [String: String] = [:]) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        let isLocal = parsedHost.host == "localhost" || parsedHost.host.hasPrefix("127.")
        var components = URLComponents()
        components.scheme = isLocal ? "http" : "https"
        components.host = parsedHost.host
        components.port = parsedHost.port
        components.path = path
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return components.url
    }
}

private struct RoomIdResponse: Codable {
    let roomId: String
}

private struct DiagnosticTokenResponse: Codable {
    let token: String
}

public enum APIError: Error, LocalizedError {
    case invalidHost
    case invalidResponse(String)
    case http(String)

    public var errorDescription: String? {
        switch self {
        case .invalidHost:
            return "Invalid host"
        case .invalidResponse(let message):
            return message
        case .http(let message):
            return message
        }
    }
}
