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

    // --- Candidate A: ownership-checked clear (multi-call safety) ---

    private fun record(roomId: String, cid: String) = RecoveryRecord(
        roomId = roomId,
        cid = cid,
        reconnectToken = "tok",
        lastEpoch = null,
        sessionStartTs = System.currentTimeMillis(),
        expiresAtMs = System.currentTimeMillis() + 60_000,
    )

    @Test
    fun `clearIfOwned clears when roomId and cid match`() {
        storage.save(record("room-1", "C-abc"))
        storage.clearIfOwned("room-1", "C-abc")
        assertNull(storage.load())
    }

    @Test
    fun `clearIfOwned keeps a record owned by a different call`() {
        // A different foreground call's record must survive a stale call's teardown.
        storage.save(record("room-2", "C-other"))
        storage.clearIfOwned("room-1", "C-abc")
        assertEquals(record("room-2", "C-other").roomId, storage.load()?.roomId)
        assertEquals("C-other", storage.load()?.cid)
    }

    @Test
    fun `clearIfOwned keeps a record when only the room matches`() {
        storage.save(record("room-1", "C-other"))
        storage.clearIfOwned("room-1", "C-abc")
        assertEquals("C-other", storage.load()?.cid)
    }

    @Test
    fun `clearIfOwned with a null cid clears nothing`() {
        // A session that never completed the join handshake owns no record.
        storage.save(record("room-1", "C-abc"))
        storage.clearIfOwned("room-1", cid = null)
        assertEquals("C-abc", storage.load()?.cid)
    }
}
