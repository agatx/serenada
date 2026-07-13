package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.AudioCoordinatorEvent
import app.serenada.core.call.AudioDevice
import app.serenada.core.call.AudioDeviceDirection
import app.serenada.core.call.AudioDeviceKind
import app.serenada.core.call.AudioDeviceStatus
import app.serenada.core.call.AudioIntent
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.WebRtcResilienceConstants
import android.content.Intent
import android.os.Looper
import app.serenada.core.fakes.TestSessionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerenadaSessionContractTest {

    private lateinit var factory: TestSessionFactory

    @Before
    fun setUp() {
        factory = TestSessionFactory()
    }

    @After
    fun tearDown() {
        factory.tearDown()
    }

    // ── Permission gating ───────────────────────────────────────────

    @Test
    fun `start without permissions sets AwaitingPermissions`() {
        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.AwaitingPermissions, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.requiredPermissions.isNotEmpty())
    }

    @Test
    fun `start with default video disabled requires microphone only`() {
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false)

        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.AwaitingPermissions, factory.session.state.value.phase)
        assertEquals(listOf(MediaCapability.MICROPHONE), factory.session.state.value.requiredPermissions)
    }

    @Test
    fun `start with default video disabled does not start camera capture`() {
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(false, factory.fakeMedia.startVideoCaptureCalls.single())
        assertFalse(factory.session.state.value.localVideoEnabled)
    }

    @Test
    fun `strict audio only starts without camera and blocks screen share`() {
        factory.tearDown()
        factory = TestSessionFactory(videoMediaEnabled = false)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        factory.startSession()
        ShadowLooper.idleMainLooper()
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(false, factory.fakeMedia.startVideoCaptureCalls.single())
        assertFalse(factory.session.state.value.localVideoEnabled)
        assertTrue(factory.session.state.value.availableCameraModes.isEmpty())
        assertEquals(0, factory.fakeMedia.startScreenShareCalls)
    }

    @Test
    fun `empty camera modes can still request screen share`() {
        factory.tearDown()
        factory = TestSessionFactory(cameraModes = emptyList())
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        factory.startSession()
        ShadowLooper.idleMainLooper()
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(false, factory.fakeMedia.startVideoCaptureCalls.single())
        assertTrue(factory.session.state.value.availableCameraModes.isEmpty())
        assertEquals(1, factory.fakeMedia.startScreenShareCalls)
    }

    @Test
    fun `turning video on without camera permission requests camera`() {
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        factory.startSession()
        ShadowLooper.idleMainLooper()

        var requestedPermissions: List<MediaCapability>? = null
        factory.session.onPermissionsRequired = { requestedPermissions = it }

        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(MediaCapability.CAMERA), requestedPermissions)
        assertTrue(factory.fakeMedia.toggleVideoCalls.none { it })
        assertFalse(factory.session.state.value.localVideoEnabled)
    }

    @Test
    fun `turning video on after camera permission enables camera`() {
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false)
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        factory.startSession()
        ShadowLooper.idleMainLooper()
        shadowApp.grantPermissions(android.Manifest.permission.CAMERA)

        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()

        assertEquals(true, factory.fakeMedia.toggleVideoCalls.last())
        assertTrue(factory.session.state.value.localVideoEnabled)
    }

    // ── Join → Joined → Waiting ─────────────────────────────────────

    @Test
    fun `join then joined with single participant transitions to Waiting`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        assertTrue("Should request room join", factory.fakeProvider.joinCalls.isNotEmpty())

        factory.simulateJoinedResponse(cid = "my-cid")

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertEquals("my-cid", factory.session.state.value.localCid)
    }

    @Test
    fun `joined without hostPeerId falls back to local participant`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.fakeProvider.simulateJoined(
            peerId = "my-cid",
            participants = listOf("my-cid" to 1L),
            hostPeerId = null,
        )
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.isHost)
    }

    // ── Join → Joined → InCall ──────────────────────────────────────

    @Test
    fun `join then joined with two participants transitions to InCall`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(1, factory.session.state.value.remoteParticipants.size)
        assertEquals("remote-cid", factory.session.state.value.remoteParticipants.first().cid)
        assertTrue(
            "Should create slot for remote participant",
            factory.fakeMedia.createdSlotCids.contains("remote-cid")
        )
    }

    // ── Server error ────────────────────────────────────────────────

    @Test
    fun `server error transitions to Error and cleans up`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateError(code = "ROOM_CAPACITY_UNSUPPORTED", message = "Room is full")

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
        assertNotNull(factory.session.state.value.error)
        assertTrue(factory.session.state.value.error is CallError.RoomFull)
        assertTrue("Engine should be released", factory.fakeMedia.releaseCalls > 0)
        assertTrue("Provider should be disconnected", factory.fakeProvider.disconnectCalls > 0)
    }

    // ── Room state update ───────────────────────────────────────────

    @Test
    fun `room_state with new remote participant transitions from Waiting to InCall`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.simulateRoomState(
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(1, factory.session.state.value.remoteParticipants.size)
    }

    @Test
    fun `incremental peer join and leave work without room_state snapshots`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.fakeProvider.simulatePeerJoined(peerId = "remote-cid", joinedAt = 2L)
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(listOf("remote-cid"), factory.session.state.value.remoteParticipants.map { it.cid })

        factory.fakeProvider.simulatePeerLeft(peerId = "remote-cid", joinedAt = 2L)
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.remoteParticipants.isEmpty())
    }

    // ── callStartedAtMs propagation ─────────────────────────────────

    @Test
    fun `join populates callStartedAtMs with wall-clock time`() {
        val before = System.currentTimeMillis()
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        val after = System.currentTimeMillis()

        val startedAt = factory.session.state.value.callStartedAtMs
        assertNotNull("callStartedAtMs should be set during join", startedAt)
        assertTrue(
            "callStartedAtMs ($startedAt) should be in [$before..$after]",
            startedAt!! in before..after,
        )
    }

    @Test
    fun `plausible participant joinedAt overwrites callStartedAtMs`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        val plausibleJoinedAt = 1_700_000_000_000L
        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to plausibleJoinedAt),
        )

        assertEquals(plausibleJoinedAt, factory.session.state.value.callStartedAtMs)
    }

    @Test
    fun `implausible participant joinedAt does not overwrite callStartedAtMs`() {
        val before = System.currentTimeMillis()
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L),
        )

        val startedAt = factory.session.state.value.callStartedAtMs
        assertNotNull(startedAt)
        assertTrue(
            "Implausible joinedAt should be ignored; got $startedAt",
            startedAt!! >= before,
        )
    }

    @Test
    fun `future participant joinedAt does not overwrite callStartedAtMs`() {
        val before = System.currentTimeMillis()
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        val futureJoinedAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to futureJoinedAt),
        )

        val startedAt = factory.session.state.value.callStartedAtMs
        assertNotNull(startedAt)
        assertNotEquals(futureJoinedAt, startedAt)
        assertTrue(
            "Future joinedAt should be ignored; got $startedAt",
            startedAt!! in before..System.currentTimeMillis(),
        )
    }

    // ── Reconnect on close ──────────────────────────────────────────

    @Test
    fun `signaling close during call schedules reconnect`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        factory.fakeProvider.simulateDisconnected(reason = "connection lost")
        ShadowLooper.idleMainLooper()

        assertNotEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertFalse(factory.session.diagnostics.value.isSignalingConnected)
    }

    @Test
    fun `self-managed reconnect rejoins with reconnect peer id`() {
        factory.tearDown()
        factory = TestSessionFactory(handlesReconnection = false)
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.fakeProvider.simulateDisconnected(reason = "connection lost")
        ShadowLooper.idleMainLooper()

        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        assertEquals(2, factory.fakeProvider.connectCalls.size)

        factory.fakeProvider.simulateConnected()
        ShadowLooper.idleMainLooper()

        val reconnectJoin = factory.fakeProvider.joinCalls.last()
        assertEquals("test-room-id", reconnectJoin.first)
        assertEquals("my-cid", reconnectJoin.second.reconnectPeerId)
    }

    @Test
    fun `room ended resets session to idle`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        factory.fakeProvider.simulateRoomEnded(by = "remote-cid", reason = "host ended")
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue(factory.session.state.value.remoteParticipants.isEmpty())
    }

    // ── Leave cleanup ───────────────────────────────────────────────

    @Test
    fun `leave sends leave message and cleans up resources`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        val releaseBefore = factory.fakeMedia.releaseCalls
        val deactivateBefore = factory.fakeAudio.deactivateCalls

        factory.session.leave()
        ShadowLooper.idleMainLooper()

        assertTrue("Should call provider leaveRoom", factory.fakeProvider.leaveCalls > 0)
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue("Provider should be disconnected", factory.fakeProvider.disconnectCalls > 0)
        assertTrue("Engine should be released", factory.fakeMedia.releaseCalls > releaseBefore)
        assertTrue("Audio should be deactivated", factory.fakeAudio.deactivateCalls > deactivateBefore)
    }

    // ── End cleanup ─────────────────────────────────────────────────

    @Test
    fun `end sends end_room then leave and cleans up`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        factory.session.end()
        ShadowLooper.idleMainLooper()

        assertTrue("Should call provider endRoom", factory.fakeProvider.endCalls > 0)
        assertTrue("Should still leave after end", factory.fakeProvider.leaveCalls > 0)
        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
    }

    // ── TURN credential fetch ───────────────────────────────────────

    @Test
    fun `joined triggers initial ICE fetch and sets ICE servers`() {
        factory.fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    org.webrtc.PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                        .setUsername("user")
                        .setPassword("pass")
                        .createIceServer()
                )
            )
        )
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        assertEquals(1, factory.fakeProvider.getIceServersCalls)
        assertTrue("ICE servers should be set", factory.fakeMedia.iceServersSet)
    }

    // ── TURN credential failure ─────────────────────────────────────

    @Test
    fun `empty ICE server list falls back to default STUN servers`() {
        factory.fakeProvider.enqueueIceServers(Result.success(emptyList()))
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        assertTrue("Default STUN servers should be applied", factory.fakeMedia.iceServersSet)
    }

    @Test
    fun `iceServersChanged updates existing and future peer slots`() {
        factory.fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    PeerConnection.IceServer.builder("turn:initial.example.com:3478")
                        .setUsername("user")
                        .setPassword("pass")
                        .createIceServer()
                )
            )
        )
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-a" to 2L),
            hostCid = "my-cid",
        )

        val existingSlot = factory.fakeMedia.fakeSlots.getValue("remote-a")
        assertTrue(existingSlot.appliedIceServerUrls.any { it == listOf("turn:initial.example.com:3478") })

        factory.fakeProvider.simulateIceServersChanged(
            listOf(
                PeerConnection.IceServer.builder("turn:refreshed.example.com:3478")
                    .setUsername("next-user")
                    .setPassword("next-pass")
                    .createIceServer()
            )
        )
        ShadowLooper.idleMainLooper()

        assertEquals(
            listOf("turn:refreshed.example.com:3478"),
            existingSlot.appliedIceServerUrls.last()
        )

        factory.fakeProvider.simulatePeerJoined(peerId = "remote-b", joinedAt = 3L)
        ShadowLooper.idleMainLooper()

        val futureSlot = factory.fakeMedia.fakeSlots.getValue("remote-b")
        assertEquals(
            listOf("turn:refreshed.example.com:3478"),
            futureSlot.appliedIceServerUrls.last()
        )
    }

    // ── Join timeout ────────────────────────────────────────────────

    @Test
    fun `join hard timeout transitions to Error`() {
        factory.grantPermissionsAndStart()
        // Do not open signaling — session stays in Joining

        assertEquals(CallPhase.Joining, factory.session.state.value.phase)

        // Advance past the join hard timeout
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.JOIN_HARD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
    }

    @Test
    fun `audio coordinator activation timeout fires before signaling starts`() {
        val coordinator = BlockingAudioCoordinator()
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(1, coordinator.activateCalls)
        assertEquals(CallPhase.Joining, factory.session.state.value.phase)

        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.AUDIO_COORDINATOR_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
        assertEquals("Signaling must not start while activation is still suspended", 0, factory.fakeProvider.connectCalls.size)
        assertEquals("Local media must not start while activation is still suspended", 0, factory.fakeMedia.startLocalMediaCalls)
    }

    @Test
    fun `leave while audio activation is suspended does not start stale media`() {
        val coordinator = BlockingAudioCoordinator()
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        factory.startSession()
        ShadowLooper.idleMainLooper()
        factory.session.leave()
        coordinator.completeActivation()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertEquals(0, factory.fakeMedia.startLocalMediaCalls)
        assertTrue(coordinator.deactivateCalls > 0)
    }

    @Test
    fun `next custom coordinator waits for previous custom deactivation`() {
        val firstCoordinator = BlockingDeactivationAudioCoordinator()
        val secondCoordinator = MutableAudioCoordinator()
        val firstFactory = TestSessionFactory(
            roomId = "first-room",
            defaultVideoEnabled = false,
            audioCoordinator = firstCoordinator,
        )
        val secondFactory = TestSessionFactory(
            roomId = "second-room",
            defaultVideoEnabled = false,
            audioCoordinator = secondCoordinator,
        )
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        try {
            firstFactory.startSession()
            ShadowLooper.idleMainLooper()
            assertEquals(1, firstCoordinator.activateCalls)

            firstFactory.session.leave()
            ShadowLooper.idleMainLooper()
            assertEquals(1, firstCoordinator.deactivateCalls)

            secondFactory.startSession()
            ShadowLooper.idleMainLooper()
            assertEquals(
                "The next custom coordinator must not activate during prior custom cleanup",
                0,
                secondCoordinator.activateCalls,
            )

            firstCoordinator.completeDeactivation()
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (secondCoordinator.activateCalls == 0 && System.nanoTime() < deadlineNs) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }

            assertEquals(1, secondCoordinator.activateCalls)
        } finally {
            firstCoordinator.completeDeactivation()
            secondFactory.tearDown()
            firstFactory.tearDown()
        }
    }

    // ── Reconnect backoff ───────────────────────────────────────────

    @Test
    fun `signaling close triggers reconnect after backoff`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        val connectCountBefore = factory.fakeProvider.connectCalls.size
        factory.fakeProvider.simulateDisconnected(reason = "test-disconnect")
        ShadowLooper.idleMainLooper()

        // Advance past the base backoff
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS,
            TimeUnit.MILLISECONDS
        )

        assertTrue(
            "Should reconnect after backoff",
            factory.fakeProvider.connectCalls.size > connectCountBefore
        )
    }

    // ── Permission grant and resume ─────────────────────────────────

    @Test
    fun `granting permissions and calling resumeJoin transitions to Joining`() {
        factory.startSession()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.AwaitingPermissions, factory.session.state.value.phase)

        // Grant permissions
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )

        factory.session.resumeJoin()
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Joining, factory.session.state.value.phase)
        assertTrue("Media engine should start", factory.fakeMedia.startLocalMediaCalls > 0)
        assertTrue("Audio should be activated", factory.fakeAudio.activateCalls > 0)
        assertTrue("Provider should connect", factory.fakeProvider.connectCalls.isNotEmpty())
    }

    // ── ICE server retry exhaustion ─────────────────────────────────

    @Test
    fun `ICE server retry exhaustion transitions to Error`() {
        // Enqueue failures for all retry attempts (4 delays in ICE_FETCH_RETRY_DELAYS_MS)
        repeat(4) {
            factory.fakeProvider.enqueueIceServers(
                Result.failure(RuntimeException("fetch failed"))
            )
        }
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")

        // Advance past all retry delays: 0, 1000, 2000, 4000ms
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.ICE_FETCH_RETRY_DELAYS_MS.sum(),
            TimeUnit.MILLISECONDS
        )

        assertEquals(CallPhase.Error, factory.session.state.value.phase)
        assertNotNull(factory.session.state.value.error)
    }

    // ── Kickstart no-op ─────────────────────────────────────────────

    @Test
    fun `join kickstart is no-op if signaling already started`() {
        factory.grantPermissionsAndStart()
        // After start, provider.connect() is already called (signaling started)
        val connectCountAfterStart = factory.fakeProvider.connectCalls.size

        // Advance past the kickstart delay — should NOT trigger another connect
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.JOIN_CONNECT_KICKSTART_MS,
            TimeUnit.MILLISECONDS
        )

        assertEquals(
            "Kickstart should be no-op since signaling already started",
            connectCountAfterStart,
            factory.fakeProvider.connectCalls.size
        )
    }

    // ── Recovery no-op after joined ─────────────────────────────────

    @Test
    fun `join recovery does not re-trigger after joined`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        // Join acknowledged, transition to waiting
        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        val connectCountBefore = factory.fakeProvider.connectCalls.size

        // Advance past recovery delay — should be harmless
        ShadowLooper.idleMainLooper(
            WebRtcResilienceConstants.JOIN_RECOVERY_MS,
            TimeUnit.MILLISECONDS
        )

        assertEquals(
            "Recovery should not re-trigger after successful join",
            connectCountBefore,
            factory.fakeProvider.connectCalls.size
        )
    }

    // ── Reconnect timing guard ──────────────────────────────────────

    @Test
    fun `reconnect does not fire before backoff elapses`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(cid = "my-cid")
        assertEquals(CallPhase.Waiting, factory.session.state.value.phase)

        val connectCountBefore = factory.fakeProvider.connectCalls.size
        factory.fakeProvider.simulateDisconnected(reason = "test-disconnect")
        ShadowLooper.idleMainLooper()

        // Advance just short of backoff — should NOT reconnect yet
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(WebRtcResilienceConstants.RECONNECT_BACKOFF_BASE_MS - 1, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "Should not reconnect before backoff elapses",
            connectCountBefore,
            factory.fakeProvider.connectCalls.size
        )

        // Advance past backoff — now should reconnect
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(2, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        assertTrue(
            "Should reconnect after backoff",
            factory.fakeProvider.connectCalls.size > connectCountBefore
        )
    }

    // ── Connection status recovering → retrying ─────────────────────

    @Test
    fun `connection status transitions from recovering to retrying`() {
        factory.advanceToInCallWithTurn()
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)

        // Close signaling while in-call to trigger connection degraded
        factory.fakeProvider.simulateDisconnected(reason = "test")
        ShadowLooper.idleMainLooper()

        assertEquals(
            ConnectionStatus.Recovering,
            factory.session.state.value.connectionStatus
        )

        // Advance past the 10-second retrying delay
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(10_000, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper()

        assertEquals(
            ConnectionStatus.Retrying,
            factory.session.state.value.connectionStatus
        )
    }

    // ── Room ended clears remote participants ────────────────────────

    @Test
    fun `room ended clears remote participants before transitioning to idle`() {
        factory.grantPermissionsAndStart()
        factory.openSignaling()

        factory.simulateJoinedResponse(
            cid = "my-cid",
            participants = listOf("my-cid" to 1L, "remote-cid" to 2L),
            hostCid = "my-cid",
        )
        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(1, factory.session.state.value.remoteParticipants.size)

        factory.fakeProvider.simulateRoomEnded(by = "remote-cid", reason = "host ended")
        ShadowLooper.idleMainLooper()

        assertEquals(CallPhase.Idle, factory.session.state.value.phase)
        assertTrue(
            "Remote participants should be cleared",
            factory.session.state.value.remoteParticipants.isEmpty()
        )
    }

    @Test
    fun `audio device collectors stay active after call cleanup`() {
        val coordinator = MutableAudioCoordinator()
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        factory.startSession()
        ShadowLooper.idleMainLooper()
        factory.openSignaling()
        factory.simulateJoinedResponse(cid = "my-cid")

        factory.session.leave()
        ShadowLooper.idleMainLooper()
        val speaker = AudioDevice(
            id = "speaker",
            displayName = "Speaker",
            kind = AudioDeviceKind.Speakerphone,
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE,
        )
        coordinator.publishAvailableDevices(listOf(speaker))
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(speaker), factory.session.availableAudioDevices.value)
    }

    @Test
    fun `close cancels audio device collectors`() {
        val coordinator = MutableAudioCoordinator()
        factory.tearDown()
        factory = TestSessionFactory(defaultVideoEnabled = false, audioCoordinator = coordinator)
        val speaker = AudioDevice(
            id = "speaker",
            displayName = "Speaker",
            kind = AudioDeviceKind.Speakerphone,
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE,
        )
        val earpiece = AudioDevice(
            id = "earpiece",
            displayName = "Earpiece",
            kind = AudioDeviceKind.Earpiece,
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE,
        )

        coordinator.publishAvailableDevices(listOf(speaker))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(speaker), factory.session.availableAudioDevices.value)

        factory.session.close()
        ShadowLooper.idleMainLooper()
        coordinator.publishAvailableDevices(listOf(earpiece))
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(speaker), factory.session.availableAudioDevices.value)
    }
}

private class BlockingAudioCoordinator : SerenadaAudioCoordinator {
    private val activation = CompletableDeferred<Unit>()
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    fun completeActivation() {
        activation.complete(Unit)
    }

    override suspend fun activateCallSession(intent: AudioIntent) {
        activateCalls += 1
        activation.await()
    }

    override suspend fun deactivateCallSession() {
        deactivateCalls += 1
    }
    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices: StateFlow<List<AudioDevice>> = MutableStateFlow(emptyList())
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
}

private class BlockingDeactivationAudioCoordinator : SerenadaAudioCoordinator {
    private val deactivation = CompletableDeferred<Unit>()
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    fun completeDeactivation() {
        deactivation.complete(Unit)
    }

    override suspend fun activateCallSession(intent: AudioIntent) {
        activateCalls += 1
    }

    override suspend fun deactivateCallSession() {
        deactivateCalls += 1
        deactivation.await()
    }

    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices: StateFlow<List<AudioDevice>> = MutableStateFlow(emptyList())
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
}

private class MutableAudioCoordinator : SerenadaAudioCoordinator {
    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices
    override val effectiveInputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = MutableStateFlow(null)
    override val events: SharedFlow<AudioCoordinatorEvent> = MutableSharedFlow()
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    fun publishAvailableDevices(devices: List<AudioDevice>) {
        _availableDevices.value = devices
    }

    override suspend fun activateCallSession(intent: AudioIntent) {
        activateCalls += 1
    }
    override suspend fun deactivateCallSession() {
        deactivateCalls += 1
    }
    override suspend fun applyRouting(device: AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}
}
