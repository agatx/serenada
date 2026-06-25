package app.serenada.core.call

import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal data class OutboundMediaSample(
    val expectsAudio: Boolean,
    val expectsVideo: Boolean,
    val audioBytesSent: Long,
    val videoBytesSent: Long,
    val videoFramesSent: Long,
)

/**
 * Cumulative inbound video `bytesReceived` for a peer, split by the bound
 * transceiver role: [contentBytes] is the inbound-rtp video matched to the
 * peer's bound CONTENT receiver track; [cameraBytes] is everything else (the
 * camera role, the single legacy video, and any video not positively
 * attributable to content). The session diffs these against the previous sample
 * to derive per-role liveness booleans (camera/content receiving). Audio is
 * excluded (audio liveness stays in [collectInboundBytes]).
 */
internal data class InboundRoleBytes(
    val cameraBytes: Long,
    val contentBytes: Long,
)

/** Combined inbound liveness sample from one WebRTC stats report. */
internal data class InboundLivenessSample(
    val inboundBytes: Long,
    val roleBytes: InboundRoleBytes,
)

/**
 * Per-role inbound liveness derived from successive [InboundRoleBytes] samples:
 * a role is `true` when its inbound video bytes advanced since the previous
 * sample. Surfaced to the public remote participant as
 * `cameraReceiving` / `contentReceiving`. Both `false` before the first sample
 * (conservative) and for a peer with no baseline yet.
 */
internal data class RoleLiveness(
    val camera: Boolean = false,
    val content: Boolean = false,
)

internal interface PeerConnectionSlotProtocol {
    // Properties
    val remoteCid: String
    /**
     * Per-peer independent-content routing flag. True ⇒ this peer carries camera
     * and screen share on separate transceivers; false ⇒ legacy single-video.
     */
    val supportsIndependentContentVideo: Boolean get() = false
    val sentOffer: Boolean
    val isMakingOffer: Boolean
    val pendingIceRestart: Boolean
    val lastIceRestartAt: Long
    val offerTimeoutTask: Runnable?
    val iceRestartTask: Runnable?

    // Offer lifecycle
    fun beginOffer()
    fun completeOffer()
    fun markOfferSent()

    // ICE restart lifecycle
    fun markPendingIceRestart()
    fun clearPendingIceRestart()
    fun recordIceRestart(nowMs: Long)

    // Task management
    fun setOfferTimeoutTask(task: Runnable)
    fun cancelOfferTimeout()
    fun setIceRestartTask(task: Runnable)
    fun cancelIceRestartTask()

    // WebRTC operations
    fun setIceServers(servers: List<PeerConnection.IceServer>)
    fun ensurePeerConnection(): Boolean

    /**
     * Role-aware local-track attach.
     *
     * - Legacy peers ([supportsIndependentContentVideo] false): exactly today's
     *   single-video behavior. [contentTrack] is ignored unless the engine has
     *   routed the active screen share onto [cameraTrack] for the legacy swap;
     *   a single video transceiver carries whichever video track is supplied.
     * - Independent-capable peers ([supportsIndependentContentVideo] true): the
     *   offer owner pre-creates camera then content video transceivers
     *   (send-capable up front); the answerer binds roles once by m-line order
     *   from the applied remote offer. [cameraTrack] attaches to the bound camera
     *   sender, [contentTrack] to the bound content sender (or stays pending until
     *   the content transceiver binds).
     */
    fun attachLocalTracks(
        audioTrack: AudioTrack?,
        cameraTrack: VideoTrack?,
        contentTrack: VideoTrack? = null,
        supportsIndependentContentVideo: Boolean = false,
    )
    fun setAudioTrack(track: AudioTrack?)

    /**
     * Attach a renderer/sink to this peer's CONTENT (screen share) video track
     * specifically. For legacy peers this is the single video track when the
     * peer is presenting content; for independent-capable peers it is the
     * content-role track bound by m-line order. Camera renderers continue to use
     * [attachRemoteRenderer]/[attachRemoteSink].
     */
    fun attachRemoteContentSink(sink: VideoSink)
    fun detachRemoteContentSink(sink: VideoSink)
    fun closePeerConnection(deferDispose: Boolean = false)
    fun createOffer(
        iceRestart: Boolean = false,
        onSdp: (String) -> Unit,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Boolean
    fun createAnswer(onSdp: (String) -> Unit, onComplete: ((Boolean) -> Unit)? = null)
    fun setRemoteDescription(
        type: SessionDescription.Type,
        sdp: String,
        onComplete: ((Boolean) -> Unit)? = null,
    )
    fun rollbackLocalDescription(onComplete: ((Boolean) -> Unit)? = null)
    fun addIceCandidate(candidate: IceCandidate)

    // State queries
    fun isReady(): Boolean
    fun getConnectionState(): PeerConnection.PeerConnectionState
    fun getIceConnectionState(): PeerConnection.IceConnectionState
    fun getSignalingState(): PeerConnection.SignalingState
    fun hasRemoteDescription(): Boolean
    fun isRemoteVideoTrackEnabled(): Boolean
    fun duckPlayback(ducked: Boolean)

    // Renderer/stats
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun detachRemoteRenderer(renderer: SurfaceViewRenderer)
    fun attachRemoteSink(sink: VideoSink)
    fun detachRemoteSink(sink: VideoSink)
    fun collectWebRtcStats(onComplete: (String, RealtimeCallStats?) -> Unit)
    /**
     * Asynchronously samples cumulative inbound liveness from one stats report:
     * all inbound RTP bytes for server `media_liveness` plus role-split
     * inbound video bytes for camera/content stall diagnostics.
     */
    fun collectInboundLiveness(onComplete: (InboundLivenessSample) -> Unit)

    /**
     * Asynchronously samples the cumulative inbound `bytesReceived` across all
     * inbound-rtp stats for this peer. Kept for focused callers; the session
     * uses [collectInboundLiveness] to avoid duplicate stats collection on each
     * media-liveness tick.
     */
    fun collectInboundBytes(onComplete: (Long) -> Unit)

    /**
     * Asynchronously samples inbound video `bytesReceived` for this peer, SPLIT
     * by the bound transceiver role (camera vs content), for the per-role stall
     * diagnostics (see SerenadaSession role-liveness sampling). Each inbound-rtp
     * video stat is matched to the bound CONTENT receiver track by
     * `trackIdentifier`; everything else (camera role, legacy single video, any
     * video not positively attributable to content) is counted as camera. Audio
     * is excluded. Kept for focused callers; the session uses
     * [collectInboundLiveness] to avoid duplicate stats collection on each
     * media-liveness tick.
     */
    fun collectInboundRoleBytes(onComplete: (InboundRoleBytes) -> Unit)

    /**
     * Asynchronously samples cumulative outbound media counters and whether
     * local enabled tracks are expected to be flowing on this peer.
     */
    fun collectOutboundMediaSample(onComplete: (OutboundMediaSample?) -> Unit)

    /**
     * Lightweight stats fetch that extracts only `audioLevel` (W3C webrtc-stats):
     * the inbound-rtp audio level for the remote participant on this slot, and
     * the media-source audio level for the locally captured mic. Either may
     * be null if stats haven't populated yet. The callback thread is not
     * guaranteed; post to the appropriate handler/executor if needed.
     */
    fun collectAudioLevels(onComplete: (inboundLevel: Float?, mediaSourceLevel: Float?) -> Unit)
    fun applyVideoSenderParameters(policy: WebRtcEngine.VideoSenderPolicy)

    /**
     * Returns the last observed path type for the selected candidate pair:
     * true if direct (host/srflx/prflx), false if relayed through TURN, null
     * if no stats sample has been collected yet. Updated by the stats poller
     * on each WebRTC stats cycle. Used by the TURN refresh gate to decide
     * whether the credentials can be allowed to expire without impact.
     */
    fun isPathDirect(): Boolean?
}
