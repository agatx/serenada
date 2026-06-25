package org.webrtc

/**
 * Minimal JVM-only fakes for the handful of WebRTC objects the receive path in
 * [app.serenada.core.call.PeerConnectionSlot] touches. They live in package
 * `org.webrtc` ONLY so they can reach the package-private constructors
 * (`PeerConnectionFactory(long)`, `RtpParameters(...)`) and pass `0L` native
 * handles. Every overridden method is pure JVM and NEVER calls into native
 * libwebrtc — these objects are never connected to a real peer connection.
 *
 * Why these exist: the real `PeerConnectionSlot.onTrack` classification of an
 * inbound video transceiver (camera vs content) can only be exercised by
 * constructing the REAL slot and invoking its `PeerConnection.Observer.onTrack`
 * with `RtpTransceiver` instances. The session-level `FakePeerConnectionSlot`
 * cannot reach that code at all. See `PeerConnectionSlotOnTrackMidTest`.
 *
 * Crucially, these fakes let a test deliver to `onTrack` a transceiver wrapper
 * that has the SAME `mid` as a bound role transceiver but a DIFFERENT object
 * identity — exactly the wrapper churn native libwebrtc produces on device, and
 * the condition the mid-based classification fix (commit b82088d0) handles.
 */

/**
 * A fake video [MediaStreamTrack] whose sink wiring is recorded instead of going
 * native. Recording the sinks lets a test observe which role a track was wired
 * to (camera vs content) when the slot has no public getter for that role.
 */
internal class FakeVideoTrack(val tag: String = "video") : VideoTrack(0L) {
    val addedSinks = mutableListOf<VideoSink>()
    override fun id(): String = tag
    override fun kind(): String = VIDEO_TRACK_KIND
    override fun addSink(sink: VideoSink) { addedSinks += sink }
    override fun removeSink(sink: VideoSink) { addedSinks.remove(sink) }
    override fun dispose() { /* no native */ }
}

/** A fake receiver that just hands back a fixed track. */
internal class FakeRtpReceiver(private val fakeTrack: MediaStreamTrack?) : RtpReceiver(0L) {
    override fun track(): MediaStreamTrack? = fakeTrack
    override fun id(): String = "fake-receiver"
    override fun dispose() { /* no native */ }
}

/** A fake sender with empty encodings so sender-parameter application is a safe no-op. */
internal class FakeRtpSender : RtpSender(0L) {
    private var currentTrack: MediaStreamTrack? = null
    override fun setTrack(track: MediaStreamTrack?, takeOwnership: Boolean): Boolean {
        currentTrack = track
        return true
    }
    override fun track(): MediaStreamTrack? = currentTrack
    // Empty encodings => PeerConnectionSlot.applySenderParameters returns early
    // (no native parameter round-trip).
    override fun getParameters(): RtpParameters =
        RtpParameters("fake", null, null, emptyList(), emptyList(), emptyList())
    override fun setParameters(parameters: RtpParameters): Boolean = true
    override fun dispose() { /* no native */ }
}

/**
 * A fake [RtpTransceiver] driven entirely from JVM fields. The receive path
 * classifies on [getMid] (the negotiated m-line id) and reads
 * [getMediaType] / [getCurrentDirection] when binding roles, plus
 * [getReceiver] / [getSender]. Object IDENTITY is intentionally meaningful: two
 * instances can share the same [mid] yet be distinct objects, reproducing the
 * native wrapper churn that broke identity-based classification.
 */
internal class FakeRtpTransceiver(
    private val midValue: String?,
    private val mediaTypeValue: MediaStreamTrack.MediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
    private val currentDirectionValue: RtpTransceiverDirection? = RtpTransceiverDirection.SEND_RECV,
    receiverTrack: MediaStreamTrack? = null,
) : RtpTransceiver(0L) {
    private val fakeReceiver = FakeRtpReceiver(receiverTrack)
    private val fakeSender = FakeRtpSender()
    private var directionValue: RtpTransceiverDirection = RtpTransceiverDirection.SEND_RECV

    override fun getMid(): String? = midValue
    override fun getMediaType(): MediaStreamTrack.MediaType = mediaTypeValue
    override fun getReceiver(): RtpReceiver = fakeReceiver
    override fun getSender(): RtpSender = fakeSender
    override fun isStopped(): Boolean = false
    override fun getCurrentDirection(): RtpTransceiverDirection? = currentDirectionValue
    override fun getDirection(): RtpTransceiverDirection = directionValue
    override fun setDirection(direction: RtpTransceiverDirection): Boolean {
        directionValue = direction
        return true
    }
    override fun stop() { /* no native */ }
    override fun stopInternal() { /* no native */ }
    override fun stopStandard() { /* no native */ }
    override fun dispose() { /* no native */ }
}

/**
 * A fake [PeerConnection] that returns a fixed transceiver list and swallows the
 * structural / config calls the slot makes during `ensurePeerConnection`.
 */
internal class FakePeerConnection(
    private val transceiverList: MutableList<RtpTransceiver>,
) : PeerConnection(NativePeerConnectionFactory { 0L }) {

    override fun getTransceivers(): MutableList<RtpTransceiver> = transceiverList
    override fun getSenders(): MutableList<RtpSender> = mutableListOf()
    override fun getReceivers(): MutableList<RtpReceiver> = mutableListOf()

    override fun addTransceiver(
        mediaType: MediaStreamTrack.MediaType,
        init: RtpTransceiver.RtpTransceiverInit?,
    ): RtpTransceiver = FakeRtpTransceiver(midValue = null, mediaTypeValue = mediaType)

    override fun signalingState(): SignalingState = SignalingState.STABLE
    override fun connectionState(): PeerConnectionState = PeerConnectionState.CONNECTED
    override fun iceConnectionState(): IceConnectionState = IceConnectionState.CONNECTED
    override fun setConfiguration(config: RTCConfiguration): Boolean = true
    override fun close() { /* no native */ }
    override fun dispose() { /* no native */ }
}

/**
 * A fake [PeerConnectionFactory] that captures the [PeerConnection.Observer] the
 * slot registers and returns the supplied [FakePeerConnection]. Exposing the
 * observer is the whole point: a test calls `observer.onTrack(transceiver)` to
 * drive the real slot's inbound-track classification.
 */
internal class FakePeerConnectionFactory(
    private val fakePeerConnection: FakePeerConnection,
) : PeerConnectionFactory(0L) {

    var capturedObserver: PeerConnection.Observer? = null
        private set

    override fun createPeerConnection(
        rtcConfig: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer,
    ): PeerConnection {
        capturedObserver = observer
        return fakePeerConnection
    }
}
