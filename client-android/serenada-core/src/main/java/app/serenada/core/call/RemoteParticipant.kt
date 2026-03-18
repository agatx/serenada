package app.serenada.core.call

data class RemoteParticipant(
    val cid: String,
    val videoEnabled: Boolean,
    val connectionState: String,
)
