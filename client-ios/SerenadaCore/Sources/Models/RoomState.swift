import Foundation

internal struct RoomState: Codable, Equatable {
    public let hostCid: String
    public let participants: [Participant]
    public let maxParticipants: Int?
    /// Server room-state epoch. Monotonic per room. SDKs gate ICE restart
    /// on receiving an authoritative post-reconnect snapshot, instead of
    /// acting on a stale in-memory peer map.
    public let epoch: Int64?

    public init(
        hostCid: String,
        participants: [Participant],
        maxParticipants: Int?,
        epoch: Int64? = nil
    ) {
        self.hostCid = hostCid
        self.participants = participants
        self.maxParticipants = maxParticipants
        self.epoch = epoch
    }
}
