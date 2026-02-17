import Foundation

enum DeepLinkParser {
    static func extractRoomId(from url: URL) -> String? {
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count >= 2 else { return nil }
        guard components[0].lowercased() == "call" else { return nil }
        let roomId = components[1].trimmingCharacters(in: .whitespacesAndNewlines)
        return roomId.isEmpty ? nil : roomId
    }
}
