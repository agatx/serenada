import Foundation

struct PushRecipientPublicKey: Codable, Equatable {
    let kty: String
    let crv: String
    let x: String
    let y: String
}

struct PushRecipient: Codable, Equatable {
    let id: Int
    let publicKey: PushRecipientPublicKey
}

struct PushSnapshotRecipient: Codable, Equatable {
    let id: Int
    let wrappedKey: String
    let wrappedKeyIv: String
}

struct PushSnapshotUploadRequest: Codable, Equatable {
    let ciphertext: String
    let snapshotIv: String
    let snapshotSalt: String
    let snapshotEphemeralPubKey: String
    let snapshotMime: String
    let recipients: [PushSnapshotRecipient]
}

struct PushSubscribeRequest: Encodable, Equatable {
    let transport: String
    let endpoint: String
    let locale: String
    let encPublicKey: PushRecipientPublicKey?
    let auth: String?
    let p256dh: String?

    private enum CodingKeys: String, CodingKey {
        case transport
        case endpoint
        case locale
        case keys
        case encPublicKey
    }

    private struct KeysPayload: Encodable {
        let auth: String
        let p256dh: String
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(transport, forKey: .transport)
        try container.encode(endpoint, forKey: .endpoint)
        try container.encode(locale, forKey: .locale)
        if let encPublicKey {
            try container.encode(encPublicKey, forKey: .encPublicKey)
        }
        let cleanAuth = auth?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanP256dh = p256dh?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !cleanAuth.isEmpty && !cleanP256dh.isEmpty {
            try container.encode(KeysPayload(auth: cleanAuth, p256dh: cleanP256dh), forKey: .keys)
        }
    }
}

final class APIClient {
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

    func fetchPushRecipients(host: String, roomId: String) async throws -> [PushRecipient] {
        guard let url = buildHTTPSURL(host: host, path: "/api/push/recipients", query: ["roomId": roomId]) else {
            throw APIError.invalidHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push recipients request failed")
        }

        let decoded = try JSONDecoder().decode([PushRecipient].self, from: data)
        return decoded.filter { recipient in
            recipient.id > 0 &&
                recipient.publicKey.kty == "EC" &&
                recipient.publicKey.crv == "P-256" &&
                !recipient.publicKey.x.isEmpty &&
                !recipient.publicKey.y.isEmpty
        }
    }

    func subscribePush(host: String, roomId: String, request payload: PushSubscribeRequest) async throws {
        guard let url = buildHTTPSURL(host: host, path: "/api/push/subscribe", query: ["roomId": roomId]) else {
            throw APIError.invalidHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push subscribe failed")
        }
    }

    func uploadPushSnapshot(host: String, request payload: PushSnapshotUploadRequest) async throws -> String {
        guard let url = buildHTTPSURL(host: host, path: "/api/push/snapshot") else {
            throw APIError.invalidHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push snapshot upload failed")
        }

        let decoded = try JSONDecoder().decode(PushSnapshotIDResponse.self, from: data)
        let id = decoded.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw APIError.invalidResponse("Snapshot ID missing")
        }
        return id
    }

    func fetchPushSnapshotCiphertext(host: String, snapshotId: String) async throws -> Data {
        guard let url = buildHTTPSURL(host: host, path: "/api/push/snapshot/\(snapshotId)") else {
            throw APIError.invalidHost
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push snapshot download failed")
        }
        guard !data.isEmpty else {
            throw APIError.invalidResponse("Snapshot payload is empty")
        }
        return data
    }

    func sendPushInvite(host: String, roomId: String, endpoint: String?) async throws {
        guard let url = buildHTTPSURL(host: host, path: "/api/push/invite", query: ["roomId": roomId]) else {
            throw APIError.invalidHost
        }

        struct InvitePayload: Codable {
            let endpoint: String?
        }

        let trimmedEndpoint = endpoint?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = InvitePayload(endpoint: (trimmedEndpoint?.isEmpty == true) ? nil : trimmedEndpoint)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push invite failed")
        }
    }

    private func parseRoomIdResponse(_ data: Data) throws -> String {
        let decoded = try JSONDecoder().decode(RoomIdResponse.self, from: data)
        guard !decoded.roomId.isEmpty else {
            throw APIError.invalidResponse("Room ID missing")
        }
        return decoded.roomId
    }

    private func buildHTTPSURL(host: String, path: String, query: [String: String] = [:]) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        var components = URLComponents()
        components.scheme = "https"
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

private struct PushSnapshotIDResponse: Codable {
    let id: String
}

enum APIError: Error, LocalizedError {
    case invalidHost
    case invalidResponse(String)
    case http(String)

    var errorDescription: String? {
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
