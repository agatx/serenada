package app.serenada.android.call

data class RoomState(
    val hostCid: String,
    val participants: List<Participant>,
    val maxParticipants: Int? = null,
)

data class Participant(
    val cid: String,
    val joinedAt: Long?
)
