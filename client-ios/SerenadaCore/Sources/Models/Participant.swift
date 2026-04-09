import Foundation

public struct Participant: Codable, Equatable {
    public let cid: String
    public let joinedAt: Int64?
    public let displayName: String?
    public let audioEnabled: Bool?
    public let videoEnabled: Bool?

    public init(cid: String, joinedAt: Int64?, displayName: String? = nil, audioEnabled: Bool? = nil, videoEnabled: Bool? = nil) {
        self.cid = cid
        self.joinedAt = joinedAt
        self.displayName = displayName
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
    }
}
