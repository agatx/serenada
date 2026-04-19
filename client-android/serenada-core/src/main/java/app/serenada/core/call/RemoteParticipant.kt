package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus

data class RemoteParticipant(
    val cid: String,
    val displayName: String? = null,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean,
    val connectionState: SerenadaPeerConnectionState,
    val signalingStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE,
)
