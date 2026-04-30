package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus

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
