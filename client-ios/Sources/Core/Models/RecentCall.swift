import Foundation

struct RecentCall: Codable, Equatable, Identifiable {
    var id: String { roomId }
    let roomId: String
    let startTime: Int64
    let durationSeconds: Int
}
