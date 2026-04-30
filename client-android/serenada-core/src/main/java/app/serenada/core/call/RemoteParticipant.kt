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
     * Smoothed voice activity level (0..1) for this peer's inbound audio.
     * Updated at ~10 Hz while the call is active; intended to drive UI
     * activity indicators. Always 0 when [audioEnabled] is false.
     */
    val audioLevel: Float = 0f,
)
