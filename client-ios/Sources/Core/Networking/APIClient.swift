import Foundation
import SerenadaCore

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

    private func buildURL(host: String, path: String, query: [String: String] = [:]) -> URL? {
        guard let parsed = EndpointHostParser.splitHostAndPort(from: host) else { return nil }
        let isLocal = parsed.host == "localhost" || parsed.host.hasPrefix("127.")
        var components = URLComponents()
        components.scheme = isLocal ? "http" : "https"
        components.host = parsed.host
        components.port = parsed.port
        components.path = path
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return components.url
    }

    func fetchPushRecipients(host: String, roomId: String) async throws -> [PushRecipient] {
        guard let url = buildURL(host: host, path: "/api/push/recipients", query: ["roomId": roomId]) else {
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
        guard let url = buildURL(host: host, path: "/api/push/subscribe", query: ["roomId": roomId]) else {
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
        guard let url = buildURL(host: host, path: "/api/push/snapshot") else {
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
        guard let url = buildURL(host: host, path: "/api/push/snapshot/\(snapshotId)") else {
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
        guard let url = buildURL(host: host, path: "/api/push/invite", query: ["roomId": roomId]) else {
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

    func notifyRoom(host: String, roomId: String, cid: String, snapshotId: String?, pushEndpoint: String?) async throws {
        guard let url = buildURL(host: host, path: "/api/push/notify", query: ["roomId": roomId]) else {
            throw APIError.invalidHost
        }

        struct NotifyPayload: Codable {
            let cid: String
            let snapshotId: String?
            let pushEndpoint: String?
        }

        let trimmedSnapshotId = snapshotId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEndpoint = pushEndpoint?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = NotifyPayload(
            cid: cid,
            snapshotId: (trimmedSnapshotId?.isEmpty == true) ? nil : trimmedSnapshotId,
            pushEndpoint: (trimmedEndpoint?.isEmpty == true) ? nil : trimmedEndpoint
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw APIError.http("Push notify failed")
        }
    }
}

private struct PushSnapshotIDResponse: Codable {
    let id: String
}
