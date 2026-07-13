package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.FakePeerConnection
import org.webrtc.FakePeerConnectionFactory
import org.webrtc.FakeRtpTransceiver
import org.webrtc.FakeVideoTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.ShadowMediaStreamTrack
import org.webrtc.ShadowPeerConnectionFactory
import org.webrtc.ShadowRtpReceiver
import org.webrtc.ShadowRtpSender
import org.webrtc.ShadowRtpTransceiver
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Regression coverage for commit b82088d0: the independent-content receive path
 * in [PeerConnectionSlot.onTrack] must classify an inbound video transceiver by
 * its negotiated m-line id (`mid`), NOT by transceiver OBJECT IDENTITY.
 *
 * On device, native libwebrtc hands `onTrack` a DIFFERENT `RtpTransceiver`
 * wrapper than the one cached when camera/content roles were bound (both wrap the
 * same underlying native transceiver). The old code compared the inbound wrapper
 * to the cached `cameraTransceiver` / `contentTransceiver` with `===`, both
 * comparisons failed, and the remote CAMERA track was dropped to the `else`
 * branch -> NO remote video. Web worked because JS returns stable references, and
 * the session-level `FakePeerConnectionSlot` reuses identical objects, so the gap
 * was invisible in every existing test.
 *
 * This test drives the REAL slot: it binds camera (mid "0") and content (mid "1")
 * roles from the fake peer connection's transceiver list, then delivers to the
 * captured observer's `onTrack` a FRESH transceiver instance carrying the SAME
 * mid as a bound role but a DIFFERENT object identity (`assertNotSame` is implied
 * by construction — distinct `FakeRtpTransceiver` objects). With the fix the track
 * routes to the correct role; with only the `===` checks it would be dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        ShadowRtpTransceiver::class,
        ShadowRtpReceiver::class,
        ShadowRtpSender::class,
        ShadowPeerConnectionFactory::class,
        ShadowMediaStreamTrack::class,
    ],
)
class PeerConnectionSlotOnTrackMidTest {

    private companion object {
        const val CAMERA_MID = "0"
        const val CONTENT_MID = "1"
    }

    private class Harness {
        // The transceiver list the slot binds roles from (m-line order):
        // first video m-line -> camera, second -> content. These are the
        // "cached" wrappers, mirroring on-device role binding.
        val boundCamera = FakeRtpTransceiver(midValue = CAMERA_MID)
        val boundContent = FakeRtpTransceiver(midValue = CONTENT_MID)
        val fakePc = FakePeerConnection(
            mutableListOf<RtpTransceiver>(boundCamera, boundContent),
        )
        val fakeFactory = FakePeerConnectionFactory(fakePc)

        // What the slot reported as the remote CAMERA track via onRemoteVideoTrack.
        var reportedCameraTrack: VideoTrack? = null

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = fakeFactory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, track -> reportedCameraTrack = track },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = {
                WebRtcEngine.VideoSenderPolicy(null, null, null, null)
            },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = PeerConnectionDisposeQueue(
                Handler(Looper.getMainLooper()),
            ),
            // Independent-capable peer, answerer (binds roles from the offer's
            // m-line order rather than pre-creating them).
            supportsIndependentContentVideo = true,
            isOfferOwner = { false },
        )

        val observer: PeerConnection.Observer

        init {
            // Materialize the peer connection so the slot registers + binds its
            // onTrack observer, then bind camera/content roles from m-line order.
            check(slot.ensurePeerConnection()) { "fake peer connection should be created" }
            observer = checkNotNull(fakeFactory.capturedObserver) {
                "slot must register a PeerConnection.Observer"
            }
        }

        /** Build a FRESH transceiver wrapper (distinct identity) for [mid]. */
        fun freshInboundTransceiver(mid: String, track: VideoTrack): FakeRtpTransceiver =
            FakeRtpTransceiver(
                midValue = mid,
                mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                receiverTrack = track,
            )
    }

    @Test
    fun `inbound camera track is routed by mid even when the wrapper identity differs`() {
        val h = Harness()
        val cameraTrack = FakeVideoTrack(tag = "remote-camera")
        // A DIFFERENT object than h.boundCamera, but the SAME negotiated mid.
        // This is exactly what native libwebrtc delivers to onTrack on device.
        val inbound = h.freshInboundTransceiver(CAMERA_MID, cameraTrack)
        check(inbound !== h.boundCamera) { "inbound wrapper must be a distinct instance" }

        h.observer.onTrack(inbound)

        // With the mid-based classification the camera track is attached and
        // surfaced. With only `===` identity checks it would fall to the `else`
        // branch ("Ignoring unbound remote video track") and stay null.
        assertSame(
            "remote camera track must be routed to the camera role by mid",
            cameraTrack,
            h.reportedCameraTrack,
        )
    }

    @Test
    fun `inbound content track is routed by mid even when the wrapper identity differs`() {
        val h = Harness()
        // Register a content sink up front. When the content track binds, the slot
        // wires every registered content sink onto it (attachRemoteContentTrack),
        // giving the test an observable signal that the content role was matched.
        val contentSink = VideoSink { }
        h.slot.attachRemoteContentSink(contentSink)

        val contentTrack = FakeVideoTrack(tag = "remote-content")
        // A DIFFERENT object than h.boundContent, but the SAME negotiated mid.
        val inbound = h.freshInboundTransceiver(CONTENT_MID, contentTrack)
        check(inbound !== h.boundContent) { "inbound wrapper must be a distinct instance" }

        h.observer.onTrack(inbound)

        // With the mid-based classification the content track is bound to the
        // content role and the pre-registered content sink is wired onto it. With
        // only `===` identity checks the inbound wrapper matches neither role and
        // falls to the `else` branch ("Ignoring unbound remote video track"): the
        // track stays unbound and never receives the sink.
        org.junit.Assert.assertTrue(
            "content track must be wired to the content role by mid",
            contentTrack.addedSinks.contains(contentSink),
        )
        // And it must NOT be misrouted to the camera role.
        assertNull(
            "content track must not be surfaced as the remote camera track",
            h.reportedCameraTrack,
        )
    }

    @Test
    fun `terminal close detaches state before native close and dispose run`() {
        val h = Harness()

        val nativeTeardown = checkNotNull(h.slot.prepareTerminalClose())
        assertNull("terminal close must be idempotent", h.slot.prepareTerminalClose())

        assertEquals("native close must be deferred", 0, h.fakePc.closeCalls)
        assertEquals("native dispose must be deferred", 0, h.fakePc.disposeCalls)

        nativeTeardown.run()

        assertEquals(1, h.fakePc.closeCalls)
        assertEquals(1, h.fakePc.disposeCalls)
    }
}
