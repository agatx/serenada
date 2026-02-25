import Foundation

struct SavedRoom: Codable, Equatable, Identifiable {
    var id: String { roomId }
    let roomId: String
    let name: String
    let createdAt: Int64
    let host: String?
    let lastJoinedAt: Int64?
}
