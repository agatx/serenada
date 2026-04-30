package app.serenada.core

import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.RemoteParticipant

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
    /** Whether local audio is currently enabled. */
    val localAudioEnabled: Boolean = true,
    /** Whether local video is currently enabled. */
    val localVideoEnabled: Boolean = true,
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
    /** Current camera mode (selfie, world, or composite). */
    val localCameraMode: LocalCameraMode = LocalCameraMode.SELFIE,
    /**
     * Camera modes the user can cycle through, in preference order. Derived
     * from [SerenadaConfig.cameraModes] minus modes unsupported on this
     * device. Empty means video is unavailable — call UIs should hide the
     * video toggle.
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
