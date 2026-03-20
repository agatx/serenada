package app.serenada.android.call

import app.serenada.core.RoomOccupancy
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomStatusesTest {

    @Test
    fun indicatorState_usesCountAndMaxParticipants() {
        assertEquals(RoomStatusIndicatorState.Hidden, RoomStatuses.indicatorState(status = null))
        assertEquals(
            RoomStatusIndicatorState.Hidden,
            RoomStatuses.indicatorState(RoomOccupancy(count = 0, maxParticipants = 4)),
        )
        assertEquals(
            RoomStatusIndicatorState.Waiting,
            RoomStatuses.indicatorState(RoomOccupancy(count = 1, maxParticipants = 4)),
        )
        assertEquals(
            RoomStatusIndicatorState.Full,
            RoomStatuses.indicatorState(RoomOccupancy(count = 4, maxParticipants = 4)),
        )
        assertEquals(
            RoomStatusIndicatorState.Full,
            RoomStatuses.indicatorState(RoomOccupancy(count = 2, maxParticipants = null)),
        )
    }
}
