package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus

/**
 * Content (screen share) presentation state for a participant. Present only
 * while the participant is sharing content; null otherwise.
 */
data class ParticipantContent(
    /** Whether content is currently active for this participant. */
    val active: Boolean,
    /** Content kind, e.g. `"screenShare"`. */
    val type: String,
    /**
     * Per-`(cid, sid)` monotonic revision of the content state. Orders quick
     * stop/start toggles. 0 when the sender did not stamp a revision.
     */
    val revision: Long,
)

data class RemoteParticipant(
    val cid: String,
    val displayName: String? = null,
    /**
     * Host-supplied stable identity passed to [SerenadaCore.join]'s `peerId`.
     * Distinct from [cid] (per-call, server-issued). Surfaced for the call UI
     * to look up avatars or correlate to host-side records.
     */
    val peerId: String? = null,
    val audioEnabled: Boolean = true,
    val videoEnabled: Boolean,
    /**
     * Camera video specifically. In the current (flag-off) build this mirrors
     * [videoEnabled]; in independent content mode `videoEnabled` is also kept as
     * the camera-specific compatibility signal while `content` carries screen
     * share state.
     */
    val cameraEnabled: Boolean = videoEnabled,
    /**
     * Content (screen share) presentation state, sourced from the peer's
     * `content_state`. Null when the peer is not sharing content.
     */
    val content: ParticipantContent? = null,
    /**
     * Per-role inbound media liveness, sampled from this peer's inbound RTP on
     * the media-liveness cadence. [cameraReceiving] is true while the peer's
     * CAMERA video bytes are advancing; [contentReceiving] is true while its
     * CONTENT (screen share) video bytes are advancing. Each is only meaningful
     * when that role's track is expected/active: derive a content stall as
     * `content?.active == true && !contentReceiving` (and the camera analog from
     * [cameraEnabled]). The SDK exposes the liveness primitive, not a
     * pre-computed "stalled" flag, so consumers compose it with the expected
     * state and read the peer identity from [cid].
     *
     * Flag off / legacy peers: the single inbound video routes to the camera
     * role, so [cameraReceiving] tracks that one video and [contentReceiving]
     * stays false. Both default to false before the first liveness sample.
     * Audio liveness is not split here (it stays in the global `media_liveness`
     * signal). Additive: existing fields are unchanged.
     */
    val cameraReceiving: Boolean = false,
    val contentReceiving: Boolean = false,
    /**
     * Whether this peer advertised independent content video at join
     * (`capabilities.independentContentVideo`). Defaults to false when absent.
     *
     * The call UI gates INDEPENDENT content rendering on this PER PEER: a peer's
     * [content] is rendered as a dedicated content stream only when the local
     * build negotiates independent content AND the peer advertised the
     * capability. A legacy peer (capability false) routes its share through the
     * single-video path, so its [content] must be presented via the peer's
     * normal video sink, not a separate content sink that never exists.
     */
    val supportsIndependentContentVideo: Boolean = false,
    val connectionState: SerenadaPeerConnectionState,
    val signalingStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE,
    /**
     * `true` when this peer has been suspended longer than
     * [WebRtcResilienceConstants.PEER_SUSPENDED_UI_TIMEOUT_MS] and the SDK has
     * flipped its UI presentation to "presumed lost." The peer connection is
     * intentionally left open so media can resume immediately if the peer
     * reattaches; this flag is purely a UI hint that call shells can use to
     * move the participant out of the active grid or show a "connection lost"
     * badge. Cleared when the peer transitions back to
     * [ParticipantSignalingStatus.ACTIVE].
     */
    val presumedLost: Boolean = false,
    /**
     * Smoothed voice activity level (0..1) for this peer's inbound audio.
     * Updated at ~10 Hz while the call is active; intended to drive UI
     * activity indicators. Always 0 when [audioEnabled] is false.
     */
    val audioLevel: Float = 0f,
)
