package app.serenada.core.call

import org.webrtc.PeerConnection

/** Per-peer connection state exposed on [RemoteParticipant]. */
enum class SerenadaPeerConnectionState(val value: String) {
    NEW("NEW"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED"),
    DISCONNECTED("DISCONNECTED"),
    FAILED("FAILED"),
    CLOSED("CLOSED");

    companion object {
        fun fromRtcState(state: PeerConnection.PeerConnectionState): SerenadaPeerConnectionState =
            when (state) {
                PeerConnection.PeerConnectionState.NEW -> NEW
                PeerConnection.PeerConnectionState.CONNECTING -> CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> FAILED
                PeerConnection.PeerConnectionState.CLOSED -> CLOSED
            }
    }
}
