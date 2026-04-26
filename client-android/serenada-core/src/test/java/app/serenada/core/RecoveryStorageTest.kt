package app.serenada.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryStorageTest {
    private val storage = RecoveryStorage(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        storage.clear()
    }

    @Test
    fun `load returns null when nothing stored`() {
        assertNull(storage.load())
    }

    @Test
    fun `round-trips a valid record`() {
        val record = RecoveryRecord(
            roomId = "room-1",
            cid = "C-abc",
            reconnectToken = "tok",
            lastEpoch = 7L,
            sessionStartTs = System.currentTimeMillis() - 10_000,
            expiresAtMs = System.currentTimeMillis() + 60_000,
        )
        storage.save(record)
        assertEquals(record, storage.load())
    }

    @Test
    fun `lastEpoch may be null`() {
        val record = RecoveryRecord(
            roomId = "room-1",
            cid = "C-abc",
            reconnectToken = "tok",
            lastEpoch = null,
            sessionStartTs = System.currentTimeMillis(),
            expiresAtMs = System.currentTimeMillis() + 60_000,
        )
        storage.save(record)
        assertNull(storage.load()?.lastEpoch)
    }

    @Test
    fun `expired records are dropped on load`() {
        val record = RecoveryRecord(
            roomId = "room-1",
            cid = "C-abc",
            reconnectToken = "tok",
            lastEpoch = null,
            sessionStartTs = System.currentTimeMillis() - 100_000,
            expiresAtMs = System.currentTimeMillis() - 1,
        )
        storage.save(record)
        assertNull(storage.load())
        // The slot should be empty after the expired-record drop.
        assertNull(storage.load())
    }

    @Test
    fun `clear removes any stored value`() {
        storage.save(
            RecoveryRecord(
                roomId = "room-1",
                cid = "C-abc",
                reconnectToken = "tok",
                lastEpoch = 1L,
                sessionStartTs = System.currentTimeMillis(),
                expiresAtMs = System.currentTimeMillis() + 60_000,
            )
        )
        storage.clear()
        assertTrue(storage.load() == null)
    }
}
