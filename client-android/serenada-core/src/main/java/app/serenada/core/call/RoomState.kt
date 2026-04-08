package app.serenada.core.call

internal data class RoomState(
    val hostCid: String,
    val participants: List<Participant>,
    val maxParticipants: Int? = null,
)

internal data class Participant(
    val cid: String,
    val joinedAt: Long?,
    val displayName: String? = null,
)
