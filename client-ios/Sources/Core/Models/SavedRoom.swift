import Foundation

struct SavedRoom: Codable, Equatable, Identifiable {
    var id: String { roomId }
    let roomId: String
    let name: String
    let createdAt: Int64
    let host: String?
    let lastJoinedAt: Int64?
    let callMode: String?

    init(roomId: String, name: String, createdAt: Int64, host: String?, lastJoinedAt: Int64?, callMode: String? = nil) {
        self.roomId = roomId
        self.name = name
        self.createdAt = createdAt
        self.host = host
        self.lastJoinedAt = lastJoinedAt
        self.callMode = callMode
    }
}
