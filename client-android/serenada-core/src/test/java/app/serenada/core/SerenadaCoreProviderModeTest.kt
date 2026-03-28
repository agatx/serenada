package app.serenada.core

import app.serenada.core.fakes.FakeAudioController
import app.serenada.core.fakes.FakeAPIClient
import app.serenada.core.fakes.FakeMediaEngine
import app.serenada.core.fakes.FakeSessionClock
import app.serenada.core.fakes.FakeSignalingProvider
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
