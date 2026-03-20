package app.serenada.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomOccupanciesTest {

    @Test
    fun mergeStatusesPayload_acceptsLegacyNumericCounts() {
        val previous = mapOf("A" to RoomOccupancy(count = 1))
        val payload =
            mapOf(
                "A" to 2,
                "B" to 3,
            )

        val merged = RoomOccupancies.mergeStatusesPayload(previous = previous, payload = payload)

        assertEquals(RoomOccupancy(count = 2), merged["A"])
        assertEquals(RoomOccupancy(count = 3), merged["B"])
    }

    @Test
    fun mergeStatusesPayload_acceptsNestedStatusObjects() {
        val previous = mapOf("A" to RoomOccupancy(count = 1))
        val payload =
            mapOf(
                "A" to mapOf("count" to 2, "maxParticipants" to 4),
                "B" to mapOf("count" to 0),
            )

        val merged = RoomOccupancies.mergeStatusesPayload(previous = previous, payload = payload)

        assertEquals(RoomOccupancy(count = 2, maxParticipants = 4), merged["A"])
        assertEquals(RoomOccupancy(count = 0), merged["B"])
    }

    @Test
    fun mergeStatusUpdatePayload_preservesKnownCapacityWhenUpdateOmitsIt() {
        val previous = mapOf("A" to RoomOccupancy(count = 1, maxParticipants = 4))
        val payload = mapOf("rid" to "A", "count" to 3)

        val merged = RoomOccupancies.mergeStatusUpdatePayload(previous = previous, payload = payload)

        assertEquals(RoomOccupancy(count = 3, maxParticipants = 4), merged["A"])
    }
}
