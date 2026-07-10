package app.serenada.core.call

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.FakeAudioTrack
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
import org.webrtc.VideoTrack

/**
 * Codex-review fixes for Phase 1 hold/resume, exercised against the REAL
 * [PeerConnectionSlot] (the session-level fake slot cannot reach these paths):
 *
 * - FIX A2: [PeerConnectionSlot.clearLocalVideoTracks] nulls the camera + content
 *   video SENDER tracks on hold, the way [PeerConnectionSlot.setAudioTrack]`(null)`
 *   already nulls the audio sender.
 * - FIX A3: sticky remote deafen — once [PeerConnectionSlot.setRemotePlaybackEnabled]
 *   `(false)` runs, a remote AudioTrack delivered to `onTrack` AFTER that (a peer
 *   that joins or renegotiates while held) comes up disabled, composing with the
 *   volume duck. Resume re-enables.
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
class PeerConnectionSlotHoldTest {

    private companion object {
        const val CAMERA_MID = "0"
        const val CONTENT_MID = "1"
    }

    private fun disposeQueue() = PeerConnectionDisposeQueue(Handler(Looper.getMainLooper()))

    // --- FIX A2: video sender tracks nulled on hold ----------------------------

    @Test
    fun `clearLocalVideoTracks nulls camera and content senders on a capable peer`() {
        // Offer owner pre-creates ordered camera + content transceivers; attach a
        // camera and content track to their senders, then hold must null both.
        val camera = FakeRtpTransceiver(midValue = CAMERA_MID)
        val content = FakeRtpTransceiver(midValue = CONTENT_MID)
        val fakePc = FakePeerConnection(mutableListOf<RtpTransceiver>(camera, content))
        val factory = FakePeerConnectionFactory(fakePc)

        val cameraTrack: VideoTrack = FakeVideoTrack(tag = "camera")
        val contentTrack: VideoTrack = FakeVideoTrack(tag = "content")

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = true,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())

        // Attach camera + content tracks onto the bound senders.
        slot.attachLocalTracks(
            audioTrack = null,
            cameraTrack = cameraTrack,
            contentTrack = contentTrack,
            supportsIndependentContentVideo = true,
        )
        assertTrue("camera sender should carry the camera track before hold", camera.sender.track() === cameraTrack)
        assertTrue("content sender should carry the content track before hold", content.sender.track() === contentTrack)

        // Hold: both video senders must be nulled (no renegotiation).
        slot.clearLocalVideoTracks()

        assertNull("camera sender track must be null after hold", camera.sender.track())
        assertNull("content sender track must be null after hold", content.sender.track())
    }

    @Test
    fun `clearLocalVideoTracks nulls the single video sender on a legacy peer`() {
        // Legacy single video transceiver in the m-line list.
        val videoTransceiver = FakeRtpTransceiver(
            midValue = CAMERA_MID,
            mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
        )
        val fakePc = FakePeerConnection(mutableListOf<RtpTransceiver>(videoTransceiver))
        val factory = FakePeerConnectionFactory(fakePc)
        val cameraTrack: VideoTrack = FakeVideoTrack(tag = "camera")

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = false,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())

        slot.attachLocalTracks(
            audioTrack = null,
            cameraTrack = cameraTrack,
            contentTrack = null,
            supportsIndependentContentVideo = false,
        )
        assertTrue("legacy video sender should carry the camera track before hold", videoTransceiver.sender.track() === cameraTrack)

        slot.clearLocalVideoTracks()

        assertNull("legacy video sender track must be null after hold", videoTransceiver.sender.track())
    }

    // --- Candidate B: hold/resume must not flip transceiver direction ----------

    @Test
    fun `hold then resume keep the audio sender stable and never flip direction`() {
        val audioTransceiver = FakeRtpTransceiver(
            midValue = CAMERA_MID,
            mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
        )
        val fakePc = FakePeerConnection(mutableListOf<RtpTransceiver>(audioTransceiver))
        val factory = FakePeerConnectionFactory(fakePc)

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = false,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())

        // Attach the initial audio track.
        val audioTrack = FakeAudioTrack(tag = "audio-1")
        slot.setAudioTrack(audioTrack)
        val senderAfterAttach = audioTransceiver.sender
        assertTrue("audio sender should carry the track after attach", senderAfterAttach.track() === audioTrack)
        // Reset the history: we only care about direction writes across hold/resume,
        // and the initial SEND_RECV transceiver needs none anyway.
        audioTransceiver.setDirectionCalls.clear()

        // Hold: detach the audio track. No direction flip, sender unchanged.
        slot.setAudioTrack(null)
        assertNull("audio sender track must be null after hold", audioTransceiver.sender.track())
        assertTrue("audio sender identity must be stable across hold", audioTransceiver.sender === senderAfterAttach)
        assertEquals(
            "audio transceiver must stay SEND_RECV across hold",
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
            audioTransceiver.direction,
        )

        // Resume: re-attach a track. Still no direction flip, sender unchanged.
        val resumedTrack = FakeAudioTrack(tag = "audio-2")
        slot.setAudioTrack(resumedTrack)
        assertTrue("audio sender should carry the resumed track", audioTransceiver.sender.track() === resumedTrack)
        assertTrue("audio sender identity must be stable across resume", audioTransceiver.sender === senderAfterAttach)
        assertEquals(
            "audio transceiver must stay SEND_RECV across resume",
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
            audioTransceiver.direction,
        )

        assertTrue(
            "hold/resume must trigger zero direction changes (no renegotiation)",
            audioTransceiver.setDirectionCalls.isEmpty(),
        )
    }

    @Test
    fun `clearLocalVideoTracks never flips the legacy video transceiver direction`() {
        val videoTransceiver = FakeRtpTransceiver(
            midValue = CAMERA_MID,
            mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
        )
        val fakePc = FakePeerConnection(mutableListOf<RtpTransceiver>(videoTransceiver))
        val factory = FakePeerConnectionFactory(fakePc)
        val cameraTrack: VideoTrack = FakeVideoTrack(tag = "camera")

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = false,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())

        slot.attachLocalTracks(
            audioTrack = null,
            cameraTrack = cameraTrack,
            contentTrack = null,
            supportsIndependentContentVideo = false,
        )
        val sender = videoTransceiver.sender
        assertTrue("legacy video sender should carry the camera track", sender.track() === cameraTrack)
        videoTransceiver.setDirectionCalls.clear()

        slot.clearLocalVideoTracks()

        assertNull("legacy video sender track must be null after hold", videoTransceiver.sender.track())
        assertTrue("legacy video sender identity must be stable across hold", videoTransceiver.sender === sender)
        assertEquals(
            "legacy video transceiver must stay SEND_RECV across hold",
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
            videoTransceiver.direction,
        )
        assertTrue(
            "hold must trigger zero direction changes (no renegotiation)",
            videoTransceiver.setDirectionCalls.isEmpty(),
        )
    }

    // --- FIX A3: sticky remote deafen ------------------------------------------

    @Test
    fun `remote audio track created after hold is deafened`() {
        val fakePc = FakePeerConnection(mutableListOf())
        val factory = FakePeerConnectionFactory(fakePc)

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = false,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())
        val observer = checkNotNull(factory.capturedObserver)

        // Hold the slot: deafen sticks for tracks created AFTER this.
        slot.setRemotePlaybackEnabled(false)

        // A peer joins / renegotiates while held: a NEW remote audio track arrives.
        val remoteAudio = FakeAudioTrack(tag = "remote-audio")
        observer.onTrack(FakeRtpTransceiver(
            midValue = "0",
            mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            receiverTrack = remoteAudio,
        ))

        assertTrue("a remote audio track created while held must be set disabled", remoteAudio.enabledHistory.isNotEmpty())
        assertFalse("sticky deafen: the new remote audio must come up disabled", remoteAudio.enabledHistory.last())
    }

    @Test
    fun `resume re-enables a remote audio track created after hold`() {
        val fakePc = FakePeerConnection(mutableListOf())
        val factory = FakePeerConnectionFactory(fakePc)

        val slot = PeerConnectionSlot(
            remoteCid = "remote",
            factory = factory,
            iceServers = emptyList(),
            localAudioTrack = null,
            localVideoTrack = null,
            videoReceiveEnabled = true,
            onLocalIceCandidate = { _, _ -> },
            onRemoteVideoTrack = { _, _ -> },
            onConnectionStateChange = { _, _ -> },
            onIceConnectionStateChange = { _, _ -> },
            onSignalingStateChange = { _, _ -> },
            onRenegotiationNeeded = { },
            applyAudioSenderParameters = { },
            currentVideoSenderPolicy = { WebRtcEngine.VideoSenderPolicy(null, null, null, null) },
            isRemoteBlackFrameAnalysisEnabled = { false },
            peerConnectionDisposeQueue = disposeQueue(),
            supportsIndependentContentVideo = false,
            isOfferOwner = { false },
        )
        check(slot.ensurePeerConnection())
        val observer = checkNotNull(factory.capturedObserver)

        slot.setRemotePlaybackEnabled(false)
        // Resume re-enables; later tracks must come up enabled.
        slot.setRemotePlaybackEnabled(true)

        val remoteAudio = FakeAudioTrack(tag = "remote-audio")
        observer.onTrack(FakeRtpTransceiver(
            midValue = "0",
            mediaTypeValue = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            receiverTrack = remoteAudio,
        ))

        assertTrue("after resume a new remote audio track must be enabled", remoteAudio.enabledHistory.last())
    }
}
