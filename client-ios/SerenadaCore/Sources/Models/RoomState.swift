import Foundation

public struct RoomState: Codable, Equatable {
    public let hostCid: String
    public let participants: [Participant]
    public let maxParticipants: Int?

    public init(hostCid: String, participants: [Participant], maxParticipants: Int?) {
        self.hostCid = hostCid
        self.participants = participants
        self.maxParticipants = maxParticipants
    }
}
