import Foundation

public struct TurnCredentials: Codable, Equatable {
    public let username: String
    public let password: String
    public let uris: [String]
    public let ttl: Int

    public init(username: String, password: String, uris: [String], ttl: Int) {
        self.username = username
        self.password = password
        self.uris = uris
        self.ttl = ttl
    }
}
