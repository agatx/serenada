package app.serenada.core.call

internal interface SessionSignaling {
    interface Listener {
        fun onOpen(activeTransport: String)
        fun onMessage(message: SignalingMessage)
        fun onClosed(reason: String)
    }

    var listener: Listener?
    fun connect(host: String)
    fun isConnected(): Boolean
    fun send(message: SignalingMessage)
    fun close()
    fun recordPong()

    /**
     * Send a synthetic ping and arm a short deadline; if no pong arrives,
     * force-close the transport so the normal reconnect path kicks in.
     * Used by the session's foreground/Doze-release lifecycle hook so a
     * stalled WS that the OS killed during background gets detected
     * immediately instead of waiting for the regular `pingIntervalMs`
     * cycle. Default is no-op for transports that don't support it.
     */
    fun forcePingWithDeadline(timeoutMs: Long) {}
}
