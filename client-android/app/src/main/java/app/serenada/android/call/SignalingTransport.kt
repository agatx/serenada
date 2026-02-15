package app.serenada.android.call

internal interface SignalingTransport {
    val kind: SignalingClient.TransportKind

    fun connect(
        host: String,
        onOpen: () -> Unit,
        onMessage: (SignalingMessage) -> Unit,
        onClosed: (String) -> Unit
    )

    fun send(message: SignalingMessage)

    fun close()

    fun resetSession() {}
}
