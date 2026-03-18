import Foundation

public struct Participant: Codable, Equatable {
    public let cid: String
    public let joinedAt: Int64?

    public init(cid: String, joinedAt: Int64?) {
        self.cid = cid
        self.joinedAt = joinedAt
    }
}
