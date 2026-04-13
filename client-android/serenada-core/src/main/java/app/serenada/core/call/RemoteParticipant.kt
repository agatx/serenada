package app.serenada.core.call

data class RemoteParticipant(
    val cid: String,
    val displayName: String? = null,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean,
    val connectionState: SerenadaPeerConnectionState,
)
