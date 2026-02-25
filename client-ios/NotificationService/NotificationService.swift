import Foundation
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private enum Constants {
        static let appGroupIdentifier = "group.app.serenada.ios"
        static let defaultHost = "serenada.app"
    }

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttemptContent: UNMutableNotificationContent?

    private let defaults = UserDefaults(suiteName: Constants.appGroupIdentifier) ?? .standard
    private lazy var pushKeyStore = PushKeyStore(defaults: defaults)

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        guard let mutable = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        self.bestAttemptContent = mutable

        Task {
            let resolved = await enrichNotification(mutable)
            self.contentHandler?(resolved)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        guard let contentHandler, let bestAttemptContent else { return }
        contentHandler(bestAttemptContent)
    }

    private func enrichNotification(_ content: UNMutableNotificationContent) async -> UNMutableNotificationContent {
        let payload = parsePayload(content.userInfo)

        if payload.kind == "invite" && shouldSuppressInvite(payload: payload) {
            content.title = ""
            content.subtitle = ""
            content.body = ""
            content.sound = nil
            return content
        }

        guard payload.kind != "invite" else { return content }
        guard let host = payload.host, !host.isEmpty else { return content }
        guard let snapshotId = payload.snapshotId,
              let snapshotSalt = payload.snapshotSalt,
              let snapshotEphemeralPub = payload.snapshotEphemeralPub,
              let snapshotKey = payload.snapshotKey,
              let snapshotKeyIv = payload.snapshotKeyIv,
              let snapshotIv = payload.snapshotIv else {
            return content
        }

        guard let wrappedSnapshotKey = pushKeyStore.decryptWrappedSnapshotKey(
            snapshotSaltB64: snapshotSalt,
            snapshotEphemeralPubB64: snapshotEphemeralPub,
            wrappedKeyB64: snapshotKey,
            wrappedKeyIvB64: snapshotKeyIv
        ) else {
            return content
        }

        guard let encryptedSnapshot = try? await fetchSnapshotCiphertext(host: host, snapshotId: snapshotId) else {
            return content
        }
        guard let decrypted = pushKeyStore.decryptSnapshot(
            ciphertext: encryptedSnapshot,
            snapshotKey: wrappedSnapshotKey,
            snapshotIvB64: snapshotIv
        ) else {
            return content
        }

        guard let attachment = createImageAttachment(data: decrypted, mime: payload.snapshotMime) else {
            return content
        }
        content.attachments = [attachment]
        return content
    }

    private func shouldSuppressInvite(payload: PushPayload) -> Bool {
        let inviteEnabled: Bool = {
            if defaults.object(forKey: "room_invite_notifications_enabled") == nil {
                return true
            }
            return defaults.bool(forKey: "room_invite_notifications_enabled")
        }()
        guard inviteEnabled else { return true }

        guard let roomId = payload.roomId else { return true }
        guard let data = defaults.data(forKey: "saved_rooms_entries"),
              let saved = try? JSONDecoder().decode([SavedRoomRecord].self, from: data) else {
            return true
        }
        return !saved.contains { $0.roomId == roomId }
    }

    private func createImageAttachment(data: Data, mime: String?) -> UNNotificationAttachment? {
        let ext: String
        switch mime?.lowercased() {
        case "image/png":
            ext = "png"
        default:
            ext = "jpg"
        }

        let tempDir = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let fileURL = tempDir.appendingPathComponent("serenada_snapshot_\(UUID().uuidString).\(ext)")

        do {
            try data.write(to: fileURL, options: .atomic)
            return try UNNotificationAttachment(identifier: "snapshot", url: fileURL, options: nil)
        } catch {
            return nil
        }
    }

    private func parsePayload(_ userInfo: [AnyHashable: Any]) -> PushPayload {
        let data = Dictionary(uniqueKeysWithValues: userInfo.compactMap { key, value -> (String, String)? in
            guard let key = key as? String else { return nil }
            if let value = value as? String {
                return (key, value)
            }
            if let value = value as? NSNumber {
                return (key, value.stringValue)
            }
            return nil
        })

        let callPath = normalizeCallPath(data["url"])
        let roomId = extractRoomId(callPath)
        let kind = data["kind"]?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().isEmpty == false
            ? data["kind"]!.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            : "join"

        return PushPayload(
            kind: kind,
            host: resolveHost(data: data),
            roomId: roomId,
            snapshotId: sanitizeSnapshotId(data["snapshotId"]),
            snapshotSalt: trimNonEmpty(data["snapshotSalt"]),
            snapshotEphemeralPub: trimNonEmpty(data["snapshotEphemeralPubKey"]),
            snapshotKey: trimNonEmpty(data["snapshotKey"]),
            snapshotKeyIv: trimNonEmpty(data["snapshotKeyIv"]),
            snapshotIv: trimNonEmpty(data["snapshotIv"]),
            snapshotMime: trimNonEmpty(data["snapshotMime"])
        )
    }

    private func resolveHost(data: [String: String]) -> String? {
        if let payloadHost = normalizePayloadHost(data["host"]) {
            return payloadHost
        }
        if let absoluteHost = extractHostFromAbsoluteURL(data["url"]) {
            return absoluteHost
        }
        let current = defaults.string(forKey: "host")?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let current, !current.isEmpty {
            return current
        }
        return Constants.defaultHost
    }

    private func normalizePayloadHost(_ rawHost: String?) -> String? {
        let value = rawHost?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !value.isEmpty else { return nil }
        if let absolute = extractHostFromAbsoluteURL(value) {
            return absolute
        }
        let normalized = value
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return normalized.isEmpty ? nil : normalized
    }

    private func extractHostFromAbsoluteURL(_ rawURL: String?) -> String? {
        let raw = rawURL?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !raw.isEmpty else { return nil }
        guard raw.lowercased().hasPrefix("http://") || raw.lowercased().hasPrefix("https://") else {
            return nil
        }
        guard let url = URL(string: raw), let host = url.host?.trimmingCharacters(in: .whitespacesAndNewlines), !host.isEmpty else {
            return nil
        }
        if let port = url.port, port > 0, port != 443 {
            return "\(host):\(port)"
        }
        return host
    }

    private func normalizeCallPath(_ rawURL: String?) -> String {
        let normalized = rawURL?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !normalized.isEmpty else { return "/" }
        if normalized.lowercased().hasPrefix("http://") || normalized.lowercased().hasPrefix("https://") {
            guard let url = URL(string: normalized) else { return "/" }
            return url.path.hasPrefix("/") ? url.path : "/\(url.path)"
        }
        return normalized.hasPrefix("/") ? normalized : "/\(normalized)"
    }

    private func extractRoomId(_ path: String) -> String? {
        let segments = path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).split(separator: "/").map(String.init)
        guard segments.count >= 2, segments[0] == "call" else { return nil }
        let roomId = segments[1].trimmingCharacters(in: .whitespacesAndNewlines)
        return roomId.isEmpty ? nil : roomId
    }

    private func trimNonEmpty(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private func sanitizeSnapshotId(_ value: String?) -> String? {
        let candidate = trimNonEmpty(value) ?? ""
        guard !candidate.isEmpty else { return nil }
        if candidate.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) {
            return candidate
        }
        return nil
    }

    private func fetchSnapshotCiphertext(host: String, snapshotId: String) async throws -> Data {
        let cleanHost = host
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !cleanHost.isEmpty else { throw URLError(.badURL) }

        var components = URLComponents()
        components.scheme = "https"
        components.host = cleanHost
        components.path = "/api/push/snapshot/\(snapshotId)"
        guard let url = components.url else { throw URLError(.badURL) }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        guard !data.isEmpty else {
            throw URLError(.zeroByteResource)
        }
        return data
    }
}

private struct SavedRoomRecord: Codable {
    let roomId: String
}

private struct PushPayload {
    let kind: String
    let host: String?
    let roomId: String?
    let snapshotId: String?
    let snapshotSalt: String?
    let snapshotEphemeralPub: String?
    let snapshotKey: String?
    let snapshotKeyIv: String?
    let snapshotIv: String?
    let snapshotMime: String?
}
