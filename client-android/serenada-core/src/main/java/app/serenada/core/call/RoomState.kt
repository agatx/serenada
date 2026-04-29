package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus

internal data class RoomState(
    val hostCid: String,
    val participants: List<Participant>,
    val maxParticipants: Int? = null,
)

internal data class Participant(
    val cid: String,
    val joinedAt: Long?,
    val displayName: String? = null,
    /** Host-supplied stable identity; opaque to the SDK, surfaced for avatar lookup. */
    val peerId: String? = null,
    val audioEnabled: Boolean? = null,
    val videoEnabled: Boolean? = null,
    val signalingStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE,
)
