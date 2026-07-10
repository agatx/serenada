package app.serenada.core

import app.serenada.core.fakes.FakeAudioController
import app.serenada.core.fakes.FakeAPIClient
import app.serenada.core.fakes.FakeMediaEngine
import app.serenada.core.fakes.FakeMultiSessionSignalingProvider
import app.serenada.core.fakes.FakeProviderChannel
import app.serenada.core.fakes.FakeSessionClock
import app.serenada.core.fakes.FakeSignalingProvider
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaCoreProviderModeTest {

    @Test
    fun `missing serverHost and signalingProvider is rejected`() {
        try {
            resolveSerenadaConfig(SerenadaConfig())
            fail("Expected missing serverHost/signalingProvider to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Provide exactly one of serverHost or signalingProvider", error.message)
        }
    }

    @Test
    fun `serverHost and signalingProvider together are rejected`() {
        try {
            resolveSerenadaConfig(
                SerenadaConfig(
                    serverHost = "serenada.app",
                    signalingProvider = FakeSignalingProvider(),
                )
            )
            fail("Expected serverHost + signalingProvider to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Provide exactly one of serverHost or signalingProvider", error.message)
        }
    }

    @Test
    fun `unsupported signalingProvider version is rejected`() {
        val provider = object : SignalingProvider {
            override val version: Int = 2
            override val capabilities: ProviderCapabilities = ProviderCapabilities(handlesReconnection = false)
            override var listener: SignalingProvider.Listener? = null

            override fun connect() = Unit

            override fun disconnect() = Unit

            override fun joinRoom(roomId: String, options: JoinOptions) = Unit

            override fun leaveRoom() = Unit

            override fun endRoom() = Unit

            override fun sendToPeer(peerId: String, type: String, payload: org.json.JSONObject?) = Unit

            override fun broadcast(type: String, payload: org.json.JSONObject?) = Unit

            override suspend fun getIceServers() = emptyList<org.webrtc.PeerConnection.IceServer>()
        }

        try {
            resolveSerenadaConfig(SerenadaConfig(signalingProvider = provider))
            fail("Expected unsupported signalingProvider version to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Unsupported signalingProvider version: 2", error.message)
        }
    }

    @Test
    fun `provider mode session can use a null roomUrl`() {
        val provider = FakeSignalingProvider()
        val session = SerenadaSession(
            roomId = "room-123",
            roomUrl = null,
            config = SerenadaConfig(signalingProvider = provider),
            context = RuntimeEnvironment.getApplication(),
            delegate = null,
            okHttpClient = okhttp3.OkHttpClient(),
            initialSignalingProvider = provider,
            audioController = FakeAudioController(),
            mediaEngine = FakeMediaEngine(),
            clock = FakeSessionClock(),
        )

        assertEquals("room-123", session.roomId)
        assertNull(session.roomUrl)
        assertNull(session.host)
    }

    @Test
    fun `createRoomId requires serverHost in provider mode`() = runBlocking {
        val provider = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = provider),
            context = RuntimeEnvironment.getApplication(),
        )

        try {
            core.createRoomId()
            fail("Expected createRoomId to require serverHost")
        } catch (error: IllegalStateException) {
            assertEquals("requires serverHost", error.message)
        }
    }

    @Test
    fun `built-in server provider owns reconnect handling`() {
        val provider = SerenadaServerProvider(
            serverHost = "serenada.app",
            handler = Handler(Looper.getMainLooper()),
            okHttpClient = OkHttpClient(),
            apiClient = FakeAPIClient(),
        )

        assertTrue(provider.capabilities.handlesReconnection)
        provider.disconnect()
    }

    // --- Multi-session (v2) provider + v1 liveness guard (F2) ---

    @Test
    fun `signalingProvider and multiSessionSignalingProvider together are rejected`() {
        try {
            resolveSerenadaConfig(
                SerenadaConfig(
                    signalingProvider = FakeSignalingProvider(),
                    multiSessionSignalingProvider = FakeMultiSessionSignalingProvider(),
                ),
            )
            fail("Expected both provider fields to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "Provide only one of signalingProvider or multiSessionSignalingProvider",
                error.message,
            )
        }
    }

    @Test
    fun `unsupported multiSessionSignalingProvider version is rejected`() {
        val provider = object : MultiSessionSignalingProvider {
            override val version: Int = 3
            override fun openSession(roomId: String): SignalingProvider = FakeSignalingProvider()
            override suspend fun getIceServers() = emptyList<org.webrtc.PeerConnection.IceServer>()
        }
        try {
            resolveSerenadaConfig(SerenadaConfig(multiSessionSignalingProvider = provider))
            fail("Expected unsupported multiSessionSignalingProvider version to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Unsupported multiSessionSignalingProvider version: 3", error.message)
        }
    }

    @Test
    fun `multiSession provider vends one channel per room via openSession`() {
        val service = FakeMultiSessionSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(multiSessionSignalingProvider = service),
            context = RuntimeEnvironment.getApplication(),
        )

        val channelA = core.createSignalingProvider(core.config, "room-A")
        val channelB = core.createSignalingProvider(core.config, "room-B")

        assertEquals(listOf("room-A", "room-B"), service.openSessionRoomIds)
        assertNotSame(channelA, channelB)
        assertEquals("room-A", (channelA as FakeProviderChannel).channelRoomId)
        assertEquals("room-B", (channelB as FakeProviderChannel).channelRoomId)
    }

    @Test
    fun `v1 provider second concurrent bind fails with a typed error`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )

        // First session binds the v1 object.
        core.createSignalingProvider(core.config, "room-1")

        try {
            core.createSignalingProvider(core.config, "room-2")
            fail("Expected a second concurrent v1 bind to fail")
        } catch (error: SingleSessionProviderInUseException) {
            assertEquals(SINGLE_SESSION_PROVIDER_IN_USE_MESSAGE, error.message)
            assertEquals(CallError.ProviderUnavailable, error.callError)
        }
    }

    @Test
    fun `v1 provider sequential reuse works after the prior channel closes`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )

        val first = core.createSignalingProvider(core.config, "room-1")
        // Teardown releases the bind (the call every session close makes).
        first.disconnect()

        // A later session may reuse the same v1 object — the guard is concurrency-only.
        val second = core.createSignalingProvider(core.config, "room-2")
        assertNotNull(second)
    }

    @Test
    fun `v1 bind is released when session construction throws so a later join succeeds`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )

        // A join claims the v1 bind (createSignalingProvider) and then constructs the
        // session; if construction throws AFTER the provider was created, no session
        // exists to release the bind — the join seam must release it before rethrowing.
        val provider = core.createSignalingProvider(core.config, "room-1")
        try {
            core.buildSessionReleasingProviderOnFailure(provider) {
                throw RuntimeException("boom during session construction")
            }
            fail("Expected the construction failure to propagate")
        } catch (error: RuntimeException) {
            assertEquals("boom during session construction", error.message)
        }

        // Bind released: a subsequent v1 join on the same core binds instead of
        // failing SingleSessionProviderInUseException.
        val second = core.createSignalingProvider(core.config, "room-2")
        assertNotNull(second)
        second.disconnect()
    }

    @Test
    fun `v1 guard is process-wide so two cores sharing one v1 provider cannot both bind`() {
        val v1 = FakeSignalingProvider()
        val coreA = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )
        val coreB = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )

        val channelA = coreA.createSignalingProvider(coreA.config, "room-1")

        // The second core shares the SAME v1 object. The guard is per provider object
        // (process-wide), not per core, so binding from the other core must fail rather
        // than clobber the shared listener.
        try {
            coreB.createSignalingProvider(coreB.config, "room-2")
            fail("Expected the second core's bind of the shared v1 provider to fail")
        } catch (error: SingleSessionProviderInUseException) {
            assertEquals(SINGLE_SESSION_PROVIDER_IN_USE_MESSAGE, error.message)
            assertEquals(CallError.ProviderUnavailable, error.callError)
        }

        // Closing the first channel releases the process-wide bind; the second core may
        // now bind the same provider object.
        channelA.disconnect()
        val channelB = coreB.createSignalingProvider(coreB.config, "room-3")
        assertNotNull(channelB)
        channelB.disconnect()
    }

    @Test
    fun `v1 channel detaches the listener on close so late events are dropped`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )
        val channel = core.createSignalingProvider(core.config, "room-1")

        val listener = object : SignalingProvider.Listener {}
        channel.listener = listener
        assertSame(listener, v1.listener)

        channel.disconnect()
        // Closed-guard: the underlying listener is detached and cannot be re-attached.
        assertNull(v1.listener)
        channel.listener = listener
        assertNull(v1.listener)
    }

    @Test
    fun `retired v1 channel does not disconnect a provider a newer channel rebound`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )

        // Session A binds the shared v1 object and reaches a terminal error: its
        // teardown (resetResources -> signalingProvider.disconnect()) retires channel A
        // and releases the bind.
        val channelA = core.createSignalingProvider(core.config, "room-A")
        channelA.listener = object : SignalingProvider.Listener {}
        channelA.disconnect()
        assertEquals(1, v1.disconnectCalls)
        assertNull(v1.listener)

        // The same v1 object is now rebound for a NEW live session B.
        val channelB = core.createSignalingProvider(core.config, "room-B")
        var bReceived = 0
        val listenerB = object : SignalingProvider.Listener {
            override fun onPeerJoined(event: PeerEvent) {
                bReceived += 1
            }
        }
        channelB.listener = listenerB
        assertSame(listenerB, v1.listener)

        // Host now closes the dead session A. A late disconnect on the already-retired
        // channel must be fully inert: it must NOT forward to the shared provider (no
        // extra disconnect) nor detach B's listener.
        channelA.disconnect()
        assertEquals(1, v1.disconnectCalls)
        assertSame(listenerB, v1.listener)

        // B is still wired up and receiving.
        v1.simulatePeerJoined("peer-1")
        assertEquals(1, bReceived)

        channelB.disconnect()
    }

    @Test
    fun `double close on a v1 channel disconnects the underlying provider once`() {
        val v1 = FakeSignalingProvider()
        val core = SerenadaCore(
            config = SerenadaConfig(signalingProvider = v1),
            context = RuntimeEnvironment.getApplication(),
        )
        val channel = core.createSignalingProvider(core.config, "room-1")

        channel.disconnect()
        channel.disconnect()

        assertEquals(1, v1.disconnectCalls)

        // The bind was released on the first close, so the same object rebinds cleanly.
        val reused = core.createSignalingProvider(core.config, "room-2")
        assertNotNull(reused)
        reused.disconnect()
    }
}
