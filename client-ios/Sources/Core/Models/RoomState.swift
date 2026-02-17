import Foundation

struct RoomState: Codable, Equatable {
    let hostCid: String
    let participants: [Participant]
}
