/**
 * Transport fallback tests for SignalingClient.
 *
 * Mirrors the web SDK's SignalingEngine fallback tests. Uses FakeSignalingTransport
 * injected via the transportFactory parameter to verify WS→SSE fallback logic.
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
class SignalingClientFallbackTest {

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

    private val wsTransport: FakeSignalingTransport?
        get() = transports.firstOrNull { it.kind == SignalingClient.TransportKind.WS }

    private val sseTransport: FakeSignalingTransport?
        get() = transports.firstOrNull { it.kind == SignalingClient.TransportKind.SSE }

    // ── Transport fallback: WS never connected → SSE ─────────────────

    @Test
    fun `falls back to SSE when WS has never connected`() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        val ws = wsTransport
        assertNotNull("Should create WS transport first", ws)
        assertEquals(1, ws!!.connectCalls)

        // WS fails without ever opening.
        ws.simulateClose("error")
        ShadowLooper.idleMainLooper()

        val sse = sseTransport
        assertNotNull("Should create SSE transport as fallback", sse)
        assertEquals(1, sse!!.connectCalls)
    }

    // ── Transport fallback: WS drops with timeout → SSE ─────────────

    @Test
    fun `falls back to SSE when WS drops with timeout`() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        val ws = wsTransport!!
        ws.simulateOpen()
        ShadowLooper.idleMainLooper()

        assertTrue(client.isConnected())
        assertEquals(listOf("ws"), listener.openTransports)

        ws.simulateClose("timeout")
        ShadowLooper.idleMainLooper()

        val sse = sseTransport
        assertNotNull("Should fall back to SSE after timeout", sse)
        assertEquals(1, sse!!.connectCalls)
    }

    // ── Transport fallback: WS unsupported → SSE ────────────────────

    @Test
    fun `falls back to SSE when WS unsupported`() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        wsTransport!!.simulateClose("unsupported")
        ShadowLooper.idleMainLooper()

        val sse = sseTransport
        assertNotNull("Should fall back to SSE when WS unsupported", sse)
        assertEquals(1, sse!!.connectCalls)
    }

    // ── SSE connects successfully after fallback ─────────────────────

    @Test
    fun `SSE connects successfully after WS fallback`() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        wsTransport!!.simulateClose("error")
        ShadowLooper.idleMainLooper()

        sseTransport!!.simulateOpen()
        ShadowLooper.idleMainLooper()

        assertTrue(client.isConnected())
        assertEquals("sse", listener.openTransports.last())
    }

    // ── No fallback with single transport ────────────────────────────

    @Test
    fun `no fallback with single transport`() {
        client.close()
        transports.clear()
        client = SignalingClient(
            okHttpClient = OkHttpClient(),
            handler = Handler(Looper.getMainLooper()),
            forceSse = true,
            transportFactory = { kind ->
                FakeSignalingTransport(kind).also { transports += it }
            },
        )
        client.listener = listener

        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        sseTransport!!.simulateClose("error")
        ShadowLooper.idleMainLooper()

        assertEquals(1, listener.closeReasons.size)
        assertEquals("error", listener.closeReasons.first())
    }

    // ── Messages routed through active transport ─────────────────────

    @Test
    fun `messages routed through active transport`() {
        client.connect("example.com")
        ShadowLooper.idleMainLooper()

        wsTransport!!.simulateOpen()
        ShadowLooper.idleMainLooper()

        val msg = SignalingMessage(type = "join", rid = "room-1", sid = null, cid = null, to = null, payload = null)
        client.send(msg)

        assertEquals(1, wsTransport!!.sentMessages.size)
        assertEquals("join", wsTransport!!.sentMessages.first().type)
    }

    // ── Recording listener ───────────────────────────────────────────

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
