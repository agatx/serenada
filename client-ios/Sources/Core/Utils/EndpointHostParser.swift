import Foundation

enum EndpointHostParser {
    static func splitHostAndPort(from rawHost: String) -> (host: String, port: Int?)? {
        let trimmed = rawHost
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !trimmed.isEmpty else { return nil }

        let lower = trimmed.lowercased()
        let withScheme: String
        if lower.hasPrefix("https://") || lower.hasPrefix("http://") || lower.hasPrefix("wss://") || lower.hasPrefix("ws://") {
            withScheme = trimmed
        } else {
            withScheme = "https://\(trimmed)"
        }

        guard let parsed = URLComponents(string: withScheme) else { return nil }
        guard parsed.user == nil else { return nil }
        guard parsed.password == nil else { return nil }
        guard parsed.query == nil else { return nil }
        guard parsed.fragment == nil else { return nil }
        guard parsed.path.isEmpty || parsed.path == "/" else { return nil }

        guard let host = parsed.host?.trimmingCharacters(in: .whitespacesAndNewlines), !host.isEmpty else {
            return nil
        }

        if let port = parsed.port {
            guard (1...65535).contains(port) else { return nil }
            return (host: host, port: port)
        }
        return (host: host, port: nil)
    }
}
