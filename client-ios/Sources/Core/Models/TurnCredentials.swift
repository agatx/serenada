import Foundation

struct TurnCredentials: Codable, Equatable {
    let username: String
    let password: String
    let uris: [String]
    let ttl: Int
}
