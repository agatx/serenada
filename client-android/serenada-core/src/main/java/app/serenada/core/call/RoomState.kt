package app.serenada.core.call

import app.serenada.core.ParticipantSignalingStatus

internal data class RoomState(
    val hostCid: String,
    val participants: List<Participant>,
    val maxParticipants: Int? = null,
    /**
     * Server room-state epoch. Monotonic per room. SDKs gate ICE restart on
     * receiving an authoritative post-reconnect snapshot, instead of acting
     * on a stale in-memory peer map.
     */
    val epoch: Long? = null,
)

internal data class Participant(
    val cid: String,
    val joinedAt: Long?,
    val displayName: String? = null,
    /** Host-supplied stable identity; opaque to the SDK, surfaced for avatar lookup. */
    val peerId: String? = null,
    val audioEnabled: Boolean? = null,
    val videoEnabled: Boolean? = null,
    val signalingStatus: ParticipantSignalingStatus = ParticipantSignalingStatus.ACTIVE,
    /**
     * Latest ephemeral content metadata for this participant (screen share,
     * content camera mode). Stored on the server's participant record so a
     * peer reconnecting after a suspension can reconstruct UI without
     * waiting for the sender to toggle again.
     */
    val contentState: ParticipantContentState? = null,
)

internal data class ParticipantContentState(
    val active: Boolean,
    val contentType: String? = null,
    val updatedAtMs: Long? = null,
    val epoch: Long? = null,
)

/**
 * Disposition of a join, surfaced by the server in `joined.reconnect`.
 * Drives whether the SDK preserves media-active peer connections,
 * schedules dirty-pair renegotiation, or starts ground-up.
 */
internal enum class ReconnectOutcome {
    FRESH, REATTACHED, RECOVERED;

    companion object {
        fun fromWireValue(value: String?): ReconnectOutcome? = when (value) {
            "fresh" -> FRESH
            "reattached" -> REATTACHED
            "recovered" -> RECOVERED
            else -> null
        }
    }
}
