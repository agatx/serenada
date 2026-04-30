/**
 * Tests for SignalingClient.forcePingWithDeadline (resilience #8).
 *
 * Verifies that the foreground force-ping path sends a synthetic ping,
 * closes the transport when no pong arrives within the deadline, and
 * stays no-op when not connected.
 */
package app.serenada.core

import android.os.Handler
import android.os.Looper
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import app.serenada.core.call.SessionSignaling
import app.serenada.core.fakes.FakeSignalingTransport
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalingClientForcePingTest {

    private val transports = mutableListOf<FakeSignalingTransport>()
    private lateinit var client: SignalingClient
    private lateinit var listener: RecordingListener

    @Before
    fun setUp() {
        listener = RecordingListener()
        client = SignalingClient(
            okHttpClient = OkHttpClient(),
            handler = Handler(Looper.getMainLooper()),
            transportFactory = { kind ->
                FakeSignalingTransport(kind).also { transports += it }
            },
        )
        client.listener = listener
    }

    @After
    fun tearDown() {
        client.close()
        ShadowLooper.idleMainLooper()
    }

    private val wsTransport: FakeSignalingTransport
        get() = transports.first { it.kind == SignalingClient.TransportKind.WS }

    private fun connectAndOpen() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()
        wsTransport.simulateOpen()
        ShadowLooper.idleMainLooper()
        assertTrue(client.isConnected())
        wsTransport.sentMessages.clear()
    }

    @Test
    fun `forcePing sends synthetic ping immediately`() {
        connectAndOpen()

        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper()

        val pings = wsTransport.sentMessages.filter { it.type == "ping" }
        assertEquals(1, pings.size)
    }

    @Test
    fun `forcePing closes transport when pong does not arrive before deadline`() {
        connectAndOpen()

        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper()

        // Advance past deadline without a pong.
        ShadowLooper.idleMainLooper(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(client.isConnected())
        assertEquals(listOf("foreground_force_ping_timeout"), listener.closeReasons)
    }

    @Test
    fun `forcePing does not close when pong arrives before deadline`() {
        connectAndOpen()

        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper()

        // Pong arrives well before deadline.
        client.recordPong()

        // Advance past deadline.
        ShadowLooper.idleMainLooper(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue(client.isConnected())
        assertTrue(listener.closeReasons.isEmpty())
    }

    @Test
    fun `forcePing is no-op when not connected`() {
        // Never connected.
        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        // No ping was sent and no close fired.
        val ws = transports.firstOrNull { it.kind == SignalingClient.TransportKind.WS }
        assertEquals(0, ws?.sentMessages?.size ?: 0)
        assertTrue(listener.closeReasons.isEmpty())
    }

    @Test
    fun `forcePing called twice cancels earlier deadline`() {
        connectAndOpen()

        client.forcePingWithDeadline(5_000L)
        ShadowLooper.idleMainLooper()
        // Advance partway, then issue a fresh force-ping with a new deadline.
        ShadowLooper.idleMainLooper(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)

        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper()
        // Pong arrives before the second deadline (and well before the first).
        client.recordPong()
        ShadowLooper.idleMainLooper(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue(client.isConnected())
        assertTrue(listener.closeReasons.isEmpty())
    }

    @Test
    fun `forcePing after close is no-op`() {
        connectAndOpen()
        client.close()
        ShadowLooper.idleMainLooper()
        wsTransport.sentMessages.clear()

        client.forcePingWithDeadline(2_000L)
        ShadowLooper.idleMainLooper(2_500, java.util.concurrent.TimeUnit.MILLISECONDS)

        // Nothing sent post-close.
        assertEquals(0, wsTransport.sentMessages.size)
    }

    // ── Recording listener ──────────────────────────────────────────────

    private class RecordingListener : SessionSignaling.Listener {
        val openTransports = mutableListOf<String>()
        val messages = mutableListOf<SignalingMessage>()
        val closeReasons = mutableListOf<String>()

        override fun onOpen(activeTransport: String) {
            openTransports += activeTransport
        }

        override fun onMessage(message: SignalingMessage) {
            messages += message
        }

        override fun onClosed(reason: String) {
            closeReasons += reason
        }
    }
}
