import Foundation

final class RecentCallStore {
    private enum Key {
        static let entries = "entries"
    }

    private let defaults: UserDefaults
    private let maxRecentCalls = 3
    private let roomIdRegex = try? NSRegularExpression(pattern: "^[A-Za-z0-9_-]{27}$")

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func saveCall(_ call: RecentCall) {
        guard isValidRoomId(call.roomId) else { return }

        var history = getRecentCalls()
        history.removeAll { $0.roomId == call.roomId }
        history.insert(
            RecentCall(
                roomId: call.roomId,
                startTime: call.startTime,
                durationSeconds: max(0, call.durationSeconds)
            ),
            at: 0
        )

        persist(Array(history.prefix(maxRecentCalls)))
    }

    func getRecentCalls() -> [RecentCall] {
        guard let data = defaults.data(forKey: Key.entries) else { return [] }
        guard let decoded = try? JSONDecoder().decode([RecentCall].self, from: data) else { return [] }

        var seen = Set<String>()
        var deduped: [RecentCall] = []

        for item in decoded {
            guard isValidRoomId(item.roomId) else { continue }
            guard item.startTime > 0 else { continue }
            guard !seen.contains(item.roomId) else { continue }
            seen.insert(item.roomId)
            deduped.append(
                RecentCall(
                    roomId: item.roomId,
                    startTime: item.startTime,
                    durationSeconds: max(0, item.durationSeconds)
                )
            )
            if deduped.count >= maxRecentCalls { break }
        }

        if deduped != decoded {
            persist(deduped)
        }

        return deduped
    }

    func removeCall(roomId: String) {
        guard !roomId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let history = getRecentCalls()
        let filtered = history.filter { $0.roomId != roomId }
        guard filtered.count != history.count else { return }
        persist(filtered)
    }

    private func persist(_ calls: [RecentCall]) {
        guard let data = try? JSONEncoder().encode(calls) else { return }
        defaults.set(data, forKey: Key.entries)
    }

    private func isValidRoomId(_ roomId: String) -> Bool {
        guard let regex = roomIdRegex else { return false }
        let range = NSRange(location: 0, length: roomId.utf16.count)
        return regex.firstMatch(in: roomId, options: [], range: range) != nil
    }
}
