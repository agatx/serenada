package app.serenada.core

import android.content.Intent
import app.serenada.core.call.CallPhase
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.fakes.TestSessionFactory
import app.serenada.core.SignalingProviderParticipant
import app.serenada.core.SignalingProviderParticipantCapabilities
import app.serenada.core.SignalingProviderParticipantMediaPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Session-level contract tests for the Phase 3a independent screen-share media
 * engine. The fakes are session-level, so these assert the per-peer capability
 * plumbing, screen-share start/stop semantics, content_state ordering, and
 * camera-vs-content lifecycle independence — the deep WebRtcEngine transceiver
 * logic is covered by compilation + on-device interop (out of scope here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndependentScreenShareContractTest {

    private lateinit var factory: TestSessionFactory

    @After
    fun tearDown() {
        if (::factory.isInitialized) factory.tearDown()
    }

    private fun newFactory(
        enableIndependentContentVideo: Boolean = true,
        videoMediaEnabled: Boolean = true,
        cameraModes: List<LocalCameraMode>? = null,
    ): TestSessionFactory {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
            )
        return TestSessionFactory(
            enableIndependentContentVideo = enableIndependentContentVideo,
            videoMediaEnabled = videoMediaEnabled,
            cameraModes = cameraModes,
        )
    }

    private fun contentStates() = factory.fakeProvider.sentMessages("content_state")

    // ── Per-peer capability gate ────────────────────────────────────

    @Test
    fun `capable peer is independent-routed when flag on and peer advertises capability`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        assertEquals(CallPhase.InCall, factory.session.state.value.phase)
        assertEquals(true, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        // local-cid-1 < remote-cid-2 ⇒ local is offer owner.
        assertEquals(true, factory.fakeMedia.createdSlotOfferOwner["remote-cid-2"])
    }

    @Test
    fun `legacy peer (no capability) is NOT independent-routed even with flag on`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = false)

        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
    }

    @Test
    fun `flag off makes every peer legacy even when peer advertises capability`() {
        factory = newFactory(enableIndependentContentVideo = false)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
    }

    @Test
    fun `audio-only peer is not independent-routed even if it advertises capability`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(
            remoteIndependentCapable = true,
            remoteVideoMediaEnabled = false,
        )

        // Peer's videoMediaEnabled=false ⇒ no video at all toward it.
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
    }

    @Test
    fun `local strict audio-only never independent-routes a capable peer`() {
        factory = newFactory(enableIndependentContentVideo = true, videoMediaEnabled = false)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
    }

    // ── Independent-mode screen-share semantics ─────────────────────

    @Test
    fun `independent screen share starts without forcing camera preferred on`() {
        factory = newFactory(enableIndependentContentVideo = true, cameraModes = emptyList())
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        // Camera off (no modes) and not preferred.
        assertFalse(factory.session.state.value.localVideoEnabled)

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(1, factory.fakeMedia.contentShareStartCalls)
        // Independent share must NOT flip camera preference on (pitfall #6).
        assertFalse(factory.session.state.value.localVideoEnabled)
        assertEquals(false, factory.session.state.value.localCameraEnabled)
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
    }

    @Test
    fun `independent screen share never sets cameraMode to screenShare`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(LocalCameraMode.SELFIE, factory.session.state.value.localCameraMode)
    }

    @Test
    fun `independent screen share signals content_state after start succeeds and populates local content`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        val states = contentStates()
        assertTrue("content_state should be broadcast on start", states.isNotEmpty())
        val last = states.last().payload
        assertEquals(true, last?.optBoolean("active"))
        assertEquals(ContentTypeWire.SCREEN_SHARE, last?.optString("contentType"))
        assertTrue("revision should be set", (last?.optLong("revision") ?: 0L) > 0L)

        val localContent = factory.session.state.value.localContent
        assertNotNull(localContent)
        assertEquals(true, localContent?.active)
        assertEquals(ContentTypeWire.SCREEN_SHARE, localContent?.type)
    }

    @Test
    fun `independent screen share start failure does not emit content_state`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.fakeMedia.startScreenShareResult = false

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertFalse(factory.session.diagnostics.value.isScreenSharing)
        assertTrue("failed start should stay silent", contentStates().isEmpty())
        assertNull(factory.session.state.value.localContent)
    }

    @Test
    fun `independent stop bumps revision and clears local content exactly once`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        val afterStart = contentStates().size

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        assertEquals(1, factory.fakeMedia.contentShareStopCalls)
        assertFalse(factory.session.diagnostics.value.isScreenSharing)
        assertNull(factory.session.state.value.localContent)
        val states = contentStates()
        assertEquals("exactly one stop broadcast", afterStart + 1, states.size)
        assertEquals(false, states.last().payload?.optBoolean("active"))
    }

    @Test
    fun `stop is idempotent at the session level`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()
        val countAfterFirstStop = factory.fakeMedia.contentShareStopCalls
        val broadcastsAfterFirstStop = contentStates().size

        // Second stop: diagnostics already cleared ⇒ no-op.
        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        assertEquals(countAfterFirstStop, factory.fakeMedia.contentShareStopCalls)
        assertEquals(broadcastsAfterFirstStop, contentStates().size)
    }

    // ── Camera independence during an independent share (pitfall #6) ──

    @Test
    fun `toggling camera during an independent share does not stop the share`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        assertTrue(factory.session.diagnostics.value.isScreenSharing)

        factory.session.toggleVideo()
        ShadowLooper.idleMainLooper()

        // The share is still active; toggling camera routes to the camera path.
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
        assertEquals(0, factory.fakeMedia.contentShareStopCalls)
        assertTrue(factory.fakeMedia.toggleVideoCalls.isNotEmpty())
    }

    @Test
    fun `flipping camera during an independent share does not broadcast a stop`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        val broadcastsAfterStart = contentStates().size

        factory.session.flipCamera()
        ShadowLooper.idleMainLooper()

        // Flip is allowed during an independent share and emits no content_state.
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
        assertEquals(broadcastsAfterStart, contentStates().size)
        assertEquals(0, factory.fakeMedia.contentShareStopCalls)
    }

    // ── Mixed mesh + late join ──────────────────────────────────────

    @Test
    fun `mixed mesh routes capable and legacy peers independently`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    org.webrtc.PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                        .setUsername("user").setPassword("pass").createIceServer()
                )
            )
        )
        factory.grantPermissionsAndStart()
        factory.openSignaling()
        factory.simulateJoinedResponse(cid = "local-cid-1", participants = listOf("local-cid-1" to 1L), hostCid = "local-cid-1")
        factory.simulateRoomStateWithCapabilities(
            participants = listOf(
                SignalingProviderParticipant(peerId = "local-cid-1", joinedAt = 1L),
                SignalingProviderParticipant(
                    peerId = "remote-capable",
                    joinedAt = 2L,
                    capabilities = SignalingProviderParticipantCapabilities(independentContentVideo = true),
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = true),
                ),
                SignalingProviderParticipant(peerId = "remote-legacy", joinedAt = 3L),
            ),
            hostCid = "local-cid-1",
        )

        assertEquals(true, factory.fakeMedia.createdSlotSupportsIndependent["remote-capable"])
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-legacy"])
    }

    // ── Mode matrix (flag on) ───────────────────────────────────────

    @Test
    fun `no-camera P2P can independent screen share`() {
        factory = newFactory(enableIndependentContentVideo = true, cameraModes = emptyList())
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        assertTrue(factory.session.state.value.availableCameraModes.isEmpty())

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(1, factory.fakeMedia.contentShareStartCalls)
        assertTrue(factory.session.diagnostics.value.isScreenSharing)
    }

    @Test
    fun `strict audio-only blocks screen share even with flag on`() {
        factory = newFactory(enableIndependentContentVideo = true, videoMediaEnabled = false)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        assertEquals(0, factory.fakeMedia.contentShareStartCalls)
        assertFalse(factory.session.diagnostics.value.isScreenSharing)
    }

    // ── Camera-mode content hint restore after an independent stop ──────────

    @Test
    fun `independent stop while in WORLD camera re-emits the world-camera content hint`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        // Camera is in WORLD while sharing: the engine keeps the world framing on
        // the camera track and the camera-mode hint is suppressed for the share.
        factory.fakeMedia.activeCameraMode = LocalCameraMode.WORLD

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        val afterStart = contentStates().size

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        // One broadcast on stop: replace the screen-share content with the
        // suppressed world-camera framing so peers keep presenting the camera
        // without an inactive flicker.
        val states = contentStates()
        assertEquals(afterStart + 1, states.size)
        val restore = states.last().payload
        assertEquals(true, restore?.optBoolean("active"))
        assertEquals(ContentTypeWire.WORLD_CAMERA, restore?.optString("contentType"))
        // The restored hint is mirrored onto local content state.
        val localContent = factory.session.state.value.localContent
        assertEquals(true, localContent?.active)
        assertEquals(ContentTypeWire.WORLD_CAMERA, localContent?.type)
        assertFalse(factory.session.diagnostics.value.isScreenSharing)
    }

    @Test
    fun `independent stop while in COMPOSITE camera re-emits the composite-camera content hint`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.fakeMedia.activeCameraMode = LocalCameraMode.COMPOSITE

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        val restore = contentStates().last().payload
        assertEquals(true, restore?.optBoolean("active"))
        assertEquals(ContentTypeWire.COMPOSITE_CAMERA, restore?.optString("contentType"))
    }

    @Test
    fun `independent stop while in SELFIE camera does not re-emit a camera hint`() {
        // SELFIE has no content framing, so stop clears the share and emits nothing
        // further. localContent ends null (no content presented).
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        factory.fakeMedia.activeCameraMode = LocalCameraMode.SELFIE

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        val afterStart = contentStates().size

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        val states = contentStates()
        assertEquals("exactly one clear broadcast on stop", afterStart + 1, states.size)
        assertEquals(false, states.last().payload?.optBoolean("active"))
        assertNull(factory.session.state.value.localContent)
    }

    @Test
    fun `legacy stop while in WORLD camera does not re-emit via the independent path`() {
        // Flag off: the legacy stop path re-applies the camera preference; the
        // independent camera-hint restore must NOT run (byte-identical to today).
        factory = newFactory(enableIndependentContentVideo = false)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = false)
        factory.fakeMedia.activeCameraMode = LocalCameraMode.WORLD

        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        val afterStart = contentStates().size

        factory.session.stopScreenShare()
        ShadowLooper.idleMainLooper()

        // Legacy: exactly one inactive broadcast on stop, no independent world hint.
        val states = contentStates()
        assertEquals(afterStart + 1, states.size)
        assertEquals(false, states.last().payload?.optBoolean("active"))
    }

    // ── FIX 1: capability-transition slot handling ──────────────────────────

    /**
     * Re-send room_state for the same single remote peer with the capability flag
     * set as given. The peer keeps the same cid/joinedAt so this is a steady-state
     * update, not a leave/rejoin — only the advertised capability changes.
     */
    private fun resendRoomStateWithCapability(
        capable: Boolean,
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-2",
    ) {
        factory.simulateRoomStateWithCapabilities(
            participants = listOf(
                SignalingProviderParticipant(peerId = localCid, joinedAt = 1L),
                SignalingProviderParticipant(
                    peerId = remoteCid,
                    joinedAt = 2L,
                    capabilities = if (capable) {
                        SignalingProviderParticipantCapabilities(independentContentVideo = true)
                    } else {
                        null
                    },
                    mediaPolicy = SignalingProviderParticipantMediaPolicy(videoMediaEnabled = true),
                ),
            ),
            hostCid = minOf(localCid, remoteCid),
        )
    }

    @Test
    fun `peer that becomes capable after creation is recreated as independent-routed`() {
        factory = newFactory(enableIndependentContentVideo = true)
        // Announced legacy (no caps yet) ⇒ legacy slot.
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = false)
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        assertEquals(1, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        val legacySlot = factory.fakeMedia.fakeSlots["remote-cid-2"]
        assertNotNull(legacySlot)

        // Caps arrive on a later room_state ⇒ capability flips legacy → capable.
        resendRoomStateWithCapability(capable = true)

        // Old slot closed; a fresh capable slot created in its place.
        assertTrue("old legacy slot closed", legacySlot!!.closePeerConnectionCalled)
        assertEquals(2, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        assertEquals(true, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        assertEquals(true, factory.fakeMedia.fakeSlots["remote-cid-2"]?.supportsIndependentContentVideo)
        // local-cid-1 < remote-cid-2 ⇒ local is owner ⇒ the recreate re-offers.
        assertTrue(
            "owner re-offers after recreate",
            (factory.fakeMedia.fakeSlots["remote-cid-2"]?.createOfferCalls ?: 0) > 0,
        )
    }

    @Test
    fun `an in-progress share re-attaches to the recreated capable peer`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = false)
        // Start the share BEFORE the flip (legacy peer carries it on its single sender).
        factory.session.startScreenShare(Intent())
        ShadowLooper.idleMainLooper()
        assertTrue(factory.session.diagnostics.value.isScreenSharing)

        // Caps arrive ⇒ recreate as capable. The session-level fake re-runs
        // attachLocalTracks on the fresh slot via the engine's createSlot path;
        // the share stays active across the recreate (no stop broadcast).
        val stopsBefore = factory.fakeMedia.contentShareStopCalls
        resendRoomStateWithCapability(capable = true)

        // Session-observable: the share survives the recreate (no stop broadcast,
        // diagnostics stay sharing) and the peer is now independent-routed. The
        // ACTUAL content re-attach onto the fresh slot's content sender runs inside
        // WebRtcEngine.createSlot → attachLocalTracksToSlot (real-engine / on-device);
        // the session-level FakeMediaEngine does not model the content track, so the
        // re-attach itself is compile-verified, not asserted here.
        assertTrue("share remains active across the recreate", factory.session.diagnostics.value.isScreenSharing)
        assertEquals("recreate must not stop the share", stopsBefore, factory.fakeMedia.contentShareStopCalls)
        assertEquals(true, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        assertEquals(2, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
    }

    @Test
    fun `steady-state room_state with unchanged caps does not recreate the peer`() {
        factory = newFactory(enableIndependentContentVideo = true)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        assertEquals(1, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        val slot = factory.fakeMedia.fakeSlots["remote-cid-2"]

        // Re-send the SAME caps (capable) — no flip ⇒ no recreate, no churn.
        resendRoomStateWithCapability(capable = true)

        assertEquals(1, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        assertEquals(false, slot?.closePeerConnectionCalled)
        assertTrue(factory.fakeMedia.removedSlots.none { it === slot })
    }

    @Test
    fun `flag off never recreates a peer when caps arrive`() {
        factory = newFactory(enableIndependentContentVideo = false)
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = false)
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        val slot = factory.fakeMedia.fakeSlots["remote-cid-2"]

        // Caps arrive, but the local flag is off ⇒ capability never flips
        // (always supported=false) ⇒ the slot stays legacy, never recreated.
        resendRoomStateWithCapability(capable = true)

        assertEquals(1, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        assertEquals(false, slot?.closePeerConnectionCalled)
    }

    @Test
    fun `peer that loses capability after creation is recreated as legacy-routed`() {
        factory = newFactory(enableIndependentContentVideo = true)
        // Announced capable ⇒ independent-routed slot.
        factory.advanceToInCallWithCapablePeer(remoteIndependentCapable = true)
        assertEquals(true, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
        val capableSlot = factory.fakeMedia.fakeSlots["remote-cid-2"]

        // Caps disappear on a later room_state ⇒ capability flips capable → legacy.
        resendRoomStateWithCapability(capable = false)

        assertTrue("old capable slot closed", capableSlot!!.closePeerConnectionCalled)
        assertEquals(2, factory.fakeMedia.createdSlotCids.count { it == "remote-cid-2" })
        assertEquals(false, factory.fakeMedia.createdSlotSupportsIndependent["remote-cid-2"])
    }
}
