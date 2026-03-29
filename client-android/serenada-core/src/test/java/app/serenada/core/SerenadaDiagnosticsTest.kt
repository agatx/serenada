package app.serenada.core

import app.serenada.core.fakes.FakeSignalingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.webrtc.PeerConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaDiagnosticsTest {

    @Test
    fun `checkSignaling skips provider mode without serverHost`() {
        val diagnostics = SerenadaDiagnostics(
            config = SerenadaConfig(signalingProvider = FakeSignalingProvider()),
            context = RuntimeEnvironment.getApplication(),
        )

        var result: SignalingCheckResult? = null
        diagnostics.checkSignaling { result = it }

        assertTrue(result is SignalingCheckResult.Skipped)
        assertEquals("requires serverHost", (result as SignalingCheckResult.Skipped).reason)
    }

    @Test
    fun `runConnectivityChecks requires serverHost in provider mode`() = runBlocking {
        val diagnostics = SerenadaDiagnostics(
            config = SerenadaConfig(signalingProvider = FakeSignalingProvider()),
            context = RuntimeEnvironment.getApplication(),
        )

        try {
            diagnostics.runConnectivityChecks()
            fail("Expected connectivity checks to require serverHost")
        } catch (error: IllegalStateException) {
            assertEquals("requires serverHost", error.message)
        }
    }

    @Test
    fun `runTurnProbe uses provider ICE servers in provider mode`() = runBlocking {
        val provider = FakeSignalingProvider().apply {
            enqueueIceServers(
                Result.success(
                    listOf(
                        PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                            .setUsername("user")
                            .setPassword("pass")
                            .createIceServer()
                    )
                )
            )
        }
        var capturedIceServers: List<PeerConnection.IceServer>? = null
        val diagnostics = SerenadaDiagnostics.createForTesting(
            config = SerenadaConfig(signalingProvider = provider),
            context = RuntimeEnvironment.getApplication(),
            providerIceProbeRunner = { iceServers, turnsOnly, onCandidateLog ->
                capturedIceServers = iceServers
                onCandidateLog?.invoke("provider probe invoked (turnsOnly=$turnsOnly)")
                IceProbeReport(
                    stunPassed = false,
                    turnPassed = false,
                    logs = listOf("provider probe invoked"),
                    iceServersSummary = iceServers.flatMap { it.urls }.joinToString(),
                )
            },
        )

        val report = diagnostics.runTurnProbe(turnsOnly = false)

        assertFalse(report.turnPassed)
        assertEquals(1, provider.getIceServersCalls)
        assertEquals(
            listOf("turn:turn.example.com:3478"),
            capturedIceServers?.flatMap { it.urls },
        )
        assertTrue(report.iceServersSummary.contains("turn:turn.example.com:3478"))
        assertEquals(listOf("provider probe invoked"), report.logs)
    }
}
