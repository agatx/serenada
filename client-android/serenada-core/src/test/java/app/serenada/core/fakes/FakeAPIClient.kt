package app.serenada.core.fakes

import app.serenada.core.network.SessionAPIClient
import app.serenada.core.network.TurnCredentials

class FakeAPIClient : SessionAPIClient {
    var turnCredentialsResult: Result<TurnCredentials> = Result.success(
        TurnCredentials(
            username = "user",
            password = "pass",
            uris = listOf("turn:turn.example.com:3478"),
            ttl = 3600
        )
    )

    val fetchTurnCredentialsCalls = mutableListOf<Pair<String, String>>()

    override fun fetchTurnCredentials(
        host: String,
        token: String,
        onResult: (Result<TurnCredentials>) -> Unit
    ) {
        fetchTurnCredentialsCalls.add(host to token)
        onResult(turnCredentialsResult)
    }
}
