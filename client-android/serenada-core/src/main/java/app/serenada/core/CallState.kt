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
    val phase: CallPhase = CallPhase.Idle,
    val roomId: String? = null,
    val localCid: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = true,
    val remoteParticipants: List<RemoteParticipant> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
    val localCameraMode: LocalCameraMode = LocalCameraMode.SELFIE,
    val error: CallError? = null,
    val requiredPermissions: List<MediaCapability> = emptyList(),
) {
    val remoteVideoEnabled: Boolean
        get() = remoteParticipants.firstOrNull()?.videoEnabled ?: false
}

enum class MediaCapability {
    CAMERA,
    MICROPHONE,
}
