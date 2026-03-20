package app.serenada.android.call

import app.serenada.core.RoomOccupancy

enum class RoomStatusIndicatorState {
    Hidden,
    Waiting,
    Full,
}

object RoomStatuses {
    fun indicatorState(status: RoomOccupancy?): RoomStatusIndicatorState {
        val count = status?.count ?: 0
        if (count <= 0) {
            return RoomStatusIndicatorState.Hidden
        }

        val maxParticipants = status?.maxParticipants
        val normalizedMaxParticipants =
            if (maxParticipants != null && maxParticipants >= 2) {
                maxParticipants
            } else {
                2
            }

        return if (count >= normalizedMaxParticipants) {
            RoomStatusIndicatorState.Full
        } else {
            RoomStatusIndicatorState.Waiting
        }
    }
}
