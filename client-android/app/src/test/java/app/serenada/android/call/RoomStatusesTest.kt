package app.serenada.android.call

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomStatusesTest {

    @Test
    fun mergeStatusesPayload_acceptsLegacyNumericCounts() {
        val previous = mapOf("A" to RoomStatus(count = 1))
        val payload =
            mapOf(
                "A" to 2,
                "B" to 3
            )

        val merged = RoomStatuses.mergeStatusesPayload(previous = previous, payload = payload)

        assertEquals(RoomStatus(count = 2), merged["A"])
        assertEquals(RoomStatus(count = 3), merged["B"])
    }

    @Test
    fun mergeStatusesPayload_acceptsNestedStatusObjects() {
        val previous = mapOf("A" to RoomStatus(count = 1))
        val payload =
            mapOf(
                "A" to mapOf("count" to 2, "maxParticipants" to 4),
                "B" to mapOf("count" to 0)
            )

        val merged = RoomStatuses.mergeStatusesPayload(previous = previous, payload = payload)

        assertEquals(RoomStatus(count = 2, maxParticipants = 4), merged["A"])
        assertEquals(RoomStatus(count = 0), merged["B"])
    }

    @Test
    fun mergeStatusUpdatePayload_preservesKnownCapacityWhenUpdateOmitsIt() {
        val previous = mapOf("A" to RoomStatus(count = 1, maxParticipants = 4))
        val payload = mapOf("rid" to "A", "count" to 3)

        val merged = RoomStatuses.mergeStatusUpdatePayload(previous = previous, payload = payload)

        assertEquals(RoomStatus(count = 3, maxParticipants = 4), merged["A"])
    }

    @Test
    fun indicatorState_usesCountAndMaxParticipants() {
        assertEquals(RoomStatusIndicatorState.Hidden, RoomStatuses.indicatorState(status = null))
        assertEquals(
            RoomStatusIndicatorState.Hidden,
            RoomStatuses.indicatorState(RoomStatus(count = 0, maxParticipants = 4))
        )
        assertEquals(
            RoomStatusIndicatorState.Waiting,
            RoomStatuses.indicatorState(RoomStatus(count = 1, maxParticipants = 4))
        )
        assertEquals(
            RoomStatusIndicatorState.Full,
            RoomStatuses.indicatorState(RoomStatus(count = 4, maxParticipants = 4))
        )
        assertEquals(
            RoomStatusIndicatorState.Full,
            RoomStatuses.indicatorState(RoomStatus(count = 2, maxParticipants = null))
        )
    }
}
