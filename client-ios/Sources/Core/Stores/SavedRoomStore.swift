import Foundation

final class SavedRoomStore {
    private enum Key {
        static let entries = "saved_rooms_entries"
    }

    private let defaults: UserDefaults
    private let maxSavedRooms = 50
    private let maxRoomNameLength = 120
    private let roomIdRegex = try? NSRegularExpression(pattern: "^[A-Za-z0-9_-]{27}$")

    init(defaults: UserDefaults = SavedRoomStore.defaultStore()) {
        self.defaults = defaults
    }

    func saveRoom(_ room: SavedRoom) {
        guard isValidRoomId(room.roomId) else { return }
        guard let normalizedName = normalizeName(room.name) else { return }

        var rooms = getSavedRooms()
        let existing = rooms.first { $0.roomId == room.roomId }
        rooms.removeAll { $0.roomId == room.roomId }

        let normalizedHost = normalizeHostValue(room.host)
        let entry = SavedRoom(
            roomId: room.roomId,
            name: normalizedName,
            createdAt: max(1, room.createdAt),
            host: normalizedHost,
            lastJoinedAt: validTimestamp(room.lastJoinedAt) ?? validTimestamp(existing?.lastJoinedAt)
        )
        rooms.insert(entry, at: 0)
        persist(Array(rooms.prefix(maxSavedRooms)))
    }

    func getSavedRooms() -> [SavedRoom] {
        guard let data = defaults.data(forKey: Key.entries) else { return [] }
        guard let decoded = try? JSONDecoder().decode([SavedRoom].self, from: data) else { return [] }

        var deduped: [SavedRoom] = []
        var seen = Set<String>()
        for room in decoded {
            guard isValidRoomId(room.roomId) else { continue }
            guard let normalizedName = normalizeName(room.name) else { continue }
            guard !seen.contains(room.roomId) else { continue }
            seen.insert(room.roomId)

            deduped.append(
                SavedRoom(
                    roomId: room.roomId,
                    name: normalizedName,
                    createdAt: max(1, room.createdAt),
                    host: normalizeHostValue(room.host),
                    lastJoinedAt: validTimestamp(room.lastJoinedAt)
                )
            )
            if deduped.count >= maxSavedRooms {
                break
            }
        }

        if deduped != decoded {
            persist(deduped)
        }

        return deduped
    }

    func removeRoom(roomId: String) {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return }
        let rooms = getSavedRooms()
        let filtered = rooms.filter { $0.roomId != normalizedRoomId }
        guard filtered.count != rooms.count else { return }
        persist(filtered)
    }

    @discardableResult
    func markRoomJoined(roomId: String, joinedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return false }

        var rooms = getSavedRooms()
        guard let index = rooms.firstIndex(where: { $0.roomId == normalizedRoomId }) else { return false }

        let normalizedJoinedAt = max(1, joinedAt)
        if rooms[index].lastJoinedAt == normalizedJoinedAt {
            return false
        }

        let room = rooms[index]
        rooms[index] = SavedRoom(
            roomId: room.roomId,
            name: room.name,
            createdAt: room.createdAt,
            host: room.host,
            lastJoinedAt: normalizedJoinedAt
        )
        persist(rooms)
        return true
    }

    func hasRoom(roomId: String) -> Bool {
        let normalizedRoomId = roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRoomId.isEmpty else { return false }
        return getSavedRooms().contains { $0.roomId == normalizedRoomId }
    }

    private func persist(_ rooms: [SavedRoom]) {
        guard let data = try? JSONEncoder().encode(rooms) else { return }
        defaults.set(data, forKey: Key.entries)
    }

    private func isValidRoomId(_ roomId: String) -> Bool {
        guard let regex = roomIdRegex else { return false }
        let range = NSRange(location: 0, length: roomId.utf16.count)
        return regex.firstMatch(in: roomId, options: [], range: range) != nil
    }

    private func normalizeName(_ rawName: String?) -> String? {
        let trimmed = (rawName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxRoomNameLength))
    }

    private func validTimestamp(_ value: Int64?) -> Int64? {
        guard let value, value > 0 else { return nil }
        return value
    }

    private func normalizeHostValue(_ hostInput: String?) -> String? {
        let raw = (hostInput ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }

        let withScheme: String
        if raw.lowercased().hasPrefix("http://") || raw.lowercased().hasPrefix("https://") {
            withScheme = raw
        } else {
            withScheme = "https://\(raw)"
        }

        guard let components = URLComponents(string: withScheme) else { return nil }
        guard components.user != nil ? false : true else { return nil }
        guard components.password != nil ? false : true else { return nil }
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

    private static func defaultStore() -> UserDefaults {
        UserDefaults(suiteName: AppConstants.appGroupIdentifier) ?? .standard
    }
}
