package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.ParticipantContent
import app.serenada.core.call.RemoteParticipant

/**
 * Richer view of the local signaling transport state. Apps can use this to
 * render reconnect spinners, "you have been disconnected" UI, and a hard-
 * eviction countdown when applicable. [CallState.connectionStatus] remains
 * the simpler tri-value summary.
 */
sealed interface SignalingState {
    object Connected : SignalingState

    /**
     * Actively retrying to (re)connect.
     * @property attempt consecutive reconnect attempts since the transport last dropped.
     * @property nextRetryAtMs wall-clock ms for the next scheduled retry, or `null` if a retry is in flight.
     */
    data class Reconnecting(val attempt: Int, val nextRetryAtMs: Long? = null) : SignalingState

    /**
     * Mid-call transport drop. The server is holding the participant slot
     * for `suspendHardEvictionTimeout` (10 min); apps can render a countdown
     * using [estimatedHardEvictionAtMs].
     * @property suspendedSinceMs wall-clock ms when the local transport last dropped.
     * @property estimatedHardEvictionAtMs computed locally from
     *   `suspendedSinceMs + SUSPEND_HARD_EVICTION_TIMEOUT_MS`. Best-effort —
     *   server media-liveness hints can extend retention.
     */
    data class Suspended(
        val suspendedSinceMs: Long,
        val estimatedHardEvictionAtMs: Long,
    ) : SignalingState

    /** Terminal failure; see [reason]. */
    data class Failed(val reason: CallError) : SignalingState
}

/**
 * SDK-native call state. This is the primary observable state for SDK consumers.
 * Does not include host-app concerns (saved rooms, settings, etc.).
 */
data class CallState(
    /** Current call lifecycle phase. */
    val phase: CallPhase = CallPhase.Idle,
    /** Room identifier for this call. */
    val roomId: String? = null,
    /** Local client identifier assigned by the server. */
    val localCid: String? = null,
    /** Whether the local user created this room. */
    val isHost: Boolean = false,
    /** Number of participants currently in the call. */
    val participantCount: Int = 0,
    /** Wall-clock timestamp in milliseconds when the local participant joined this call. */
    val callStartedAtMs: Long? = null,
    /** Whether local audio is currently enabled. */
    val localAudioEnabled: Boolean = true,
    /** Whether local video is currently enabled. */
    val localVideoEnabled: Boolean = true,
    /**
     * Whether the local camera video specifically is enabled. In the current
     * (flag-off) build this mirrors [localVideoEnabled]; once independent
     * content video ships it tracks camera-active independently from screen
     * share.
     */
    val localCameraEnabled: Boolean = true,
    /**
     * Local content (screen share) presentation state. Null when the local
     * user is not sharing content.
     */
    val localContent: ParticipantContent? = null,
    /** Local participant display name, if set. */
    val localDisplayName: String? = null,
    /**
     * Smoothed voice activity level (0..1) for the locally captured mic.
     * Updated at ~10 Hz while the call is active; intended to drive UI
     * activity indicators. Always 0 when [localAudioEnabled] is false.
     */
    val localAudioLevel: Float = 0f,
    /** List of remote participants with their current state. */
    val remoteParticipants: List<RemoteParticipant> = emptyList(),
    /** Network connection status. */
    val connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
    /**
     * Richer signaling-transport state with timing details. Apps that don't
     * need the extra detail can stick with [connectionStatus].
     */
    val signalingState: SignalingState = SignalingState.Connected,
    /** Current camera mode (selfie, world, or composite). */
    val localCameraMode: LocalCameraMode = LocalCameraMode.SELFIE,
    /**
     * Camera modes the user can cycle through, in preference order. Derived
     * from [SerenadaConfig.cameraModes] minus modes unsupported on this
     * device. Empty means camera video is unavailable — call UIs should hide
     * the camera video toggle.
     */
    val availableCameraModes: List<LocalCameraMode> = DEFAULT_CAMERA_MODES,
    /** Current error, if any. */
    val error: CallError? = null,
    /** Permissions needed before joining (empty when all granted). */
    val requiredPermissions: List<MediaCapability> = emptyList(),
) {
    val remoteVideoEnabled: Boolean
        get() = remoteParticipants.firstOrNull()?.videoEnabled ?: false
}

/** Device media capabilities required for a call. */
enum class MediaCapability {
    CAMERA,
    MICROPHONE,
}
