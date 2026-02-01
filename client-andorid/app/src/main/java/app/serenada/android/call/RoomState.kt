package app.serenada.android.call

data class RoomState(
    val hostCid: String,
    val participants: List<Participant>
)

data class Participant(
    val cid: String,
    val joinedAt: Long?
)
