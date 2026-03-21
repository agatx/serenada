package app.serenada.core.network

interface SessionAPIClient {
    fun fetchTurnCredentials(host: String, token: String, onResult: (Result<TurnCredentials>) -> Unit)
}
