package app.serenada.core

import app.serenada.core.call.CallMediaRole
import app.serenada.core.fakes.CountingTestCoordinator
import app.serenada.core.fakes.TestSessionFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Candidate A (durable recovery is FOREGROUND-only): a foreground session writes
 * the cross-launch recovery record; a HELD session keeps its reconnect identity in
 * memory only. Resume-to-foreground persists from the in-memory credentials, and a
 * stale/held teardown never clears a record another foreground call owns.
 *
 * The SDK's [RecoveryStorage] is a shared app-private SharedPreferences store, so a
 * session and a test-owned [RecoveryStorage] over the same app context see the same
 * record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecoveryPersistenceTest {

    private val storage = RecoveryStorage(RuntimeEnvironment.getApplication())
    private val factories = mutableListOf<TestSessionFactory>()

    @Before
    fun setUp() {
        storage.clear()
    }

    @After
    fun tearDown() {
        factories.forEach { it.tearDown() }
        factories.clear()
        storage.clear()
    }

    private fun factory(
        roomId: String,
        role: CallMediaRole = CallMediaRole.FOREGROUND,
    ): TestSessionFactory {
        val f = TestSessionFactory(
            roomId = roomId,
            initialMediaRole = role,
            audioCoordinator = CountingTestCoordinator(),
        )
        // Align the session clock with the wall clock: RecoveryStorage.load() drops
        // records whose expiresAtMs is in the past per System.currentTimeMillis(), and
        // persistRecoveryRecord() computes expiresAtMs from the (otherwise 0) fake
        // clock. Production uses a live clock, so this only rehydrates the test infra.
        f.fakeClock.advance(System.currentTimeMillis())
        factories.add(f)
        return f
    }

    @Test
    fun `a foreground session persists a recovery record on join`() {
        val f = factory("room-a")
        f.advanceToInCallWithTurn(localCid = "cid-a", reconnectToken = "tok-a")
        ShadowLooper.idleMainLooper()

        val record = storage.load()
        assertNotNull("foreground join must persist a durable record", record)
        assertEquals("room-a", record!!.roomId)
        assertEquals("cid-a", record.cid)
        assertEquals("tok-a", record.reconnectToken)
    }

    @Test
    fun `a held session does not persist a recovery record on join`() {
        val f = factory("room-b", role = CallMediaRole.HELD)
        f.advanceToHeldInCall(localCid = "cid-b", reconnectToken = "tok-b")
        ShadowLooper.idleMainLooper()

        assertNull("a held session keeps recovery identity in memory only", storage.load())
    }

    @Test
    fun `a held token refresh does not persist`() {
        val f = factory("room-b", role = CallMediaRole.HELD)
        f.advanceToHeldInCall(localCid = "cid-b", reconnectToken = "tok-b")
        ShadowLooper.idleMainLooper()
        assertNull(storage.load())

        // A refreshed token while still held must NOT write a durable record.
        f.fakeProvider.simulateReconnectTokenRefreshed("tok-b2")
        ShadowLooper.idleMainLooper()
        assertNull("held token refresh stays in memory only", storage.load())
    }

    @Test
    fun `resuming a held session to foreground persists from in-memory credentials`() {
        val f = factory("room-b", role = CallMediaRole.HELD)
        f.advanceToHeldInCall(localCid = "cid-b", reconnectToken = "tok-b")
        ShadowLooper.idleMainLooper()
        assertNull(storage.load())

        // Resume to foreground: the in-memory reconnect identity is now persisted.
        runBlocking { f.session.applyForegroundRoleInternal() }
        ShadowLooper.idleMainLooper()

        val record = storage.load()
        assertNotNull("resume-to-foreground must persist the record", record)
        assertEquals("room-b", record!!.roomId)
        assertEquals("cid-b", record.cid)
        assertEquals("tok-b", record.reconnectToken)
    }

    @Test
    fun `switching foreground from A to B updates the record to B`() {
        val a = factory("room-a")
        a.advanceToInCallWithTurn(localCid = "cid-a", reconnectToken = "tok-a")
        ShadowLooper.idleMainLooper()
        assertEquals("room-a", storage.load()?.roomId)

        val b = factory("room-b", role = CallMediaRole.HELD)
        b.advanceToHeldInCall(localCid = "cid-b", reconnectToken = "tok-b")
        ShadowLooper.idleMainLooper()
        // B is held: the record still names A.
        assertEquals("room-a", storage.load()?.roomId)

        // Switch: hold A (drain), resume B (activate). The record now names B.
        runBlocking { a.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()
        runBlocking { b.session.applyForegroundRoleInternal() }
        ShadowLooper.idleMainLooper()

        assertEquals("room-b", storage.load()?.roomId)
        assertEquals("cid-b", storage.load()?.cid)
    }

    @Test
    fun `a stale held call teardown does not clear the foreground call's record`() {
        val a = factory("room-a", role = CallMediaRole.HELD)
        a.advanceToHeldInCall(localCid = "cid-a", reconnectToken = "tok-a")
        ShadowLooper.idleMainLooper()

        val b = factory("room-b")
        b.advanceToInCallWithTurn(localCid = "cid-b", reconnectToken = "tok-b")
        ShadowLooper.idleMainLooper()
        // B (foreground) owns the record.
        assertEquals("room-b", storage.load()?.roomId)

        // A (held) tears down: it must NOT clear B's record (ownership check).
        a.session.leave()
        ShadowLooper.idleMainLooper()

        assertEquals("stale held teardown must not wipe B's record", "room-b", storage.load()?.roomId)
        assertEquals("cid-b", storage.load()?.cid)
    }

    @Test
    fun `a failed switch rollback keeps the record naming the restored owner`() {
        val a = factory("room-a")
        a.advanceToInCallWithTurn(localCid = "cid-a", reconnectToken = "tok-a")
        ShadowLooper.idleMainLooper()
        assertEquals("room-a", storage.load()?.roomId)

        // Switch step 1 drains A (hold); the target activation then fails and the
        // switch rolls back by re-foregrounding A. Through all of it the record must
        // consistently name A (never a half-written other owner).
        runBlocking { a.session.applyHeldRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertEquals("room-a", storage.load()?.roomId)

        runBlocking { a.session.applyForegroundRoleInternal() }
        ShadowLooper.idleMainLooper()
        assertEquals("room-a", storage.load()?.roomId)
        assertEquals("cid-a", storage.load()?.cid)
    }
}
