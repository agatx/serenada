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
    /**
     * Static build capabilities advertised by this participant at `join`,
     * forwarded verbatim by the server. Absent for older clients; callers
     * apply per-field defaults (e.g. `independentContentVideo` → false).
     */
    val capabilities: ParticipantCapabilities? = null,
    /**
     * Per-session media policy advertised by this participant at `join`,
     * forwarded verbatim by the server. Absent for older clients; callers
     * default `videoMediaEnabled` → true.
     */
    val mediaPolicy: ParticipantMediaPolicy? = null,
)

internal data class ParticipantContentState(
    val active: Boolean,
    val contentType: String? = null,
    val updatedAtMs: Long? = null,
    val epoch: Long? = null,
    /**
     * Per-`(cid, sid)` monotonic revision of this content state. Lets the
     * receiver order quick stop/start toggles and discard stale, out-of-order
     * updates. Absent on older senders that do not stamp a revision.
     */
    val revision: Long? = null,
)

/**
 * Allowlisted static capabilities advertised by a participant. Only known keys
 * are modeled; unknown keys are dropped by the server before forwarding.
 */
internal data class ParticipantCapabilities(
    /**
     * Whether the participant can negotiate, send, receive, classify, expose,
     * and render an independent content (screen share) video stream. Defaults
     * to false when absent.
     */
    val independentContentVideo: Boolean = false,
)

/**
 * Allowlisted per-session media policy advertised by a participant.
 */
internal data class ParticipantMediaPolicy(
    /**
     * Whether this participant negotiates any video media at all. Defaults to
     * true when absent (no deployed audio-only client predates this signal).
     */
    val videoMediaEnabled: Boolean = true,
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
