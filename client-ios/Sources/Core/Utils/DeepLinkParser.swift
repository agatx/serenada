import Foundation

enum DeepLinkAction {
    case join
    case saveRoom
}

struct DeepLinkTarget: Equatable {
    let action: DeepLinkAction
    let roomId: String
    let host: String?
    let savedRoomName: String?
}

struct DeepLinkHostPolicy: Equatable {
    let persistedHost: String?
    let oneOffHost: String?
}

enum DeepLinkParser {
    private static let roomIdRegex = try? NSRegularExpression(pattern: "^[A-Za-z0-9_-]{27}$")
    private static let maxSavedRoomNameLength = 120

    static func extractRoomId(from url: URL) -> String? {
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count >= 2 else { return nil }
        guard components[0].lowercased() == "call" else { return nil }
        let roomId = components[1].trimmingCharacters(in: .whitespacesAndNewlines)
        return roomId.isEmpty ? nil : roomId
    }

    static func parseTarget(from url: URL) -> DeepLinkTarget? {
        guard let roomId = extractRoomId(from: url) else { return nil }
        guard isValidRoomId(roomId) else { return nil }

        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let hostFromQuery = normalizeHostValue(components?.queryItems?.first { $0.name == "host" }?.value)
        let hostFromAuthority = normalizeHostValue(authorityHost(from: url))
        let resolvedHost = hostFromQuery ?? hostFromAuthority

        let savedRoomName = normalizeSavedRoomName(components?.queryItems?.first { $0.name == "name" }?.value)
        let action: DeepLinkAction = savedRoomName == nil ? .join : .saveRoom

        return DeepLinkTarget(
            action: action,
            roomId: roomId,
            host: resolvedHost,
            savedRoomName: savedRoomName
        )
    }

    static func resolveHostPolicy(host: String?) -> DeepLinkHostPolicy {
        guard let normalized = normalizeHostValue(host) else {
            return DeepLinkHostPolicy(persistedHost: nil, oneOffHost: nil)
        }
        if isTrustedHost(normalized) {
            return DeepLinkHostPolicy(persistedHost: normalized, oneOffHost: nil)
        }
        return DeepLinkHostPolicy(persistedHost: nil, oneOffHost: normalized)
    }

    static func normalizeHostValue(_ hostInput: String?) -> String? {
        let raw = (hostInput ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }

        let withScheme: String
        if raw.lowercased().hasPrefix("http://") || raw.lowercased().hasPrefix("https://") {
            withScheme = raw
        } else {
            withScheme = "https://\(raw)"
        }

        guard let components = URLComponents(string: withScheme) else { return nil }
        guard components.user == nil else { return nil }
        guard components.password == nil else { return nil }
        guard components.query == nil else { return nil }
        guard components.fragment == nil else { return nil }

        let path = components.path
        guard path.isEmpty || path == "/" else { return nil }

        guard let host = components.host?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !host.isEmpty else {
            return nil
        }

        if let port = components.port {
            guard (1...65535).contains(port) else { return nil }
            return "\(host):\(port)"
        }
        return host
    }

    static func isTrustedHost(_ host: String) -> Bool {
        let normalized = host.lowercased()
        return normalized == AppConstants.defaultHost || normalized == AppConstants.ruHost
    }

    static func normalizeSavedRoomName(_ name: String?) -> String? {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxSavedRoomNameLength))
    }

    private static func authorityHost(from url: URL) -> String? {
        guard let host = url.host else { return nil }
        if let port = url.port {
            return "\(host):\(port)"
        }
        return host
    }

    private static func isValidRoomId(_ roomId: String) -> Bool {
        guard let regex = roomIdRegex else { return false }
        let range = NSRange(location: 0, length: roomId.utf16.count)
        return regex.firstMatch(in: roomId, options: [], range: range) != nil
    }
}
