package app.serenada.core.fakes

import app.serenada.core.call.SessionSignaling
import app.serenada.core.call.SignalingMessage

class FakeSignaling : SessionSignaling {
    override var listener: SessionSignaling.Listener? = null

    val connectCalls = mutableListOf<String>()
    val sentMessages = mutableListOf<SignalingMessage>()
    var closeCalls = 0
        private set
    var connected = false
        private set

    override fun connect(host: String) {
        connectCalls.add(host)
    }

    override fun isConnected(): Boolean = connected

    override fun send(message: SignalingMessage) {
        sentMessages.add(message)
    }

    override fun close() {
        closeCalls++
        connected = false
    }

    override fun recordPong() {}

    // ── Test drivers ──

    fun simulateOpen(transport: String = "ws") {
        connected = true
        listener?.onOpen(transport)
    }

    fun simulateMessage(message: SignalingMessage) {
        listener?.onMessage(message)
    }

    fun simulateClosed(reason: String = "test") {
        connected = false
        listener?.onClosed(reason)
    }

    fun sentMessages(ofType: String): List<SignalingMessage> =
        sentMessages.filter { it.type == ofType }
}
