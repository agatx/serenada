package app.serenada.core.fakes

import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.SignalingTransport

internal class FakeSignalingTransport(
    override val kind: SignalingClient.TransportKind,
) : SignalingTransport {

    var connectCalls = 0
        private set
    var closeCalls = 0
        private set
    val sentMessages = mutableListOf<SignalingMessage>()

    private var onOpen: (() -> Unit)? = null
    private var onMessage: ((SignalingMessage) -> Unit)? = null
    private var onClosed: ((String) -> Unit)? = null

    override fun connect(
        host: String,
        onOpen: () -> Unit,
        onMessage: (SignalingMessage) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        connectCalls++
        this.onOpen = onOpen
        this.onMessage = onMessage
        this.onClosed = onClosed
    }

    override fun send(message: SignalingMessage) {
        sentMessages += message
    }

    override fun close() {
        closeCalls++
    }

    // ---- Test-side drivers ----

    fun simulateOpen() {
        onOpen?.invoke()
    }

    fun simulateClose(reason: String = "transport-closed") {
        onClosed?.invoke(reason)
    }

    fun simulateMessage(message: SignalingMessage) {
        onMessage?.invoke(message)
    }
}
