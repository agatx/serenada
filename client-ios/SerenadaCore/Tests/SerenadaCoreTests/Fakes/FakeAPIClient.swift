import Foundation
@testable import SerenadaCore

final class FakeAPIClient: SessionAPIClient {
    var turnCredentialsResult: Result<TurnCredentials, Error> = .success(
        TurnCredentials(username: "user", password: "pass", uris: ["turn:turn.example.com:3478"], ttl: 3600)
    )

    private(set) var fetchTurnCredentialsCalls: [(host: String, token: String)] = []

    func fetchTurnCredentials(host: String, token: String) async throws -> TurnCredentials {
        fetchTurnCredentialsCalls.append((host: host, token: token))
        return try turnCredentialsResult.get()
    }
}
