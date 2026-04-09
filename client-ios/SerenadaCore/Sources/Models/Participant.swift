import Foundation

public struct Participant: Codable, Equatable {
    public let cid: String
    public let joinedAt: Int64?
    public let displayName: String?

    public init(cid: String, joinedAt: Int64?, displayName: String? = nil) {
        self.cid = cid
        self.joinedAt = joinedAt
        self.displayName = displayName
    }
}
