package app.serenada.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWatcherTest {

    @Test
    fun `watchRooms requires serverHost`() {
        val watcher = RoomWatcher()

        try {
            watcher.watchRooms(listOf("room-1"), null)
            fail("Expected RoomWatcher to require serverHost")
        } catch (error: IllegalStateException) {
            assertEquals("requires serverHost", error.message)
        }
    }
}
