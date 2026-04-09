import Foundation

struct RecentCall: Codable, Equatable, Identifiable {
    var id: String { roomId }
    let roomId: String
    let startTime: Int64
    let durationSeconds: Int
    let host: String?
    let callMode: String?

    init(roomId: String, startTime: Int64, durationSeconds: Int, host: String?, callMode: String? = nil) {
        self.roomId = roomId
        self.startTime = startTime
        self.durationSeconds = durationSeconds
        self.host = host
        self.callMode = callMode
    }
}
