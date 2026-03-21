import Foundation

protocol SessionAPIClient {
    func fetchTurnCredentials(host: String, token: String) async throws -> TurnCredentials
}
