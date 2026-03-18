package app.serenada.callui

import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.RemoteParticipant
import app.serenada.core.call.RealtimeCallStats

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val roomId: String? = null,
    val localCid: String? = null,
    val statusMessageResId: Int? = null,
    val errorMessageResId: Int? = null,
    val errorMessageText: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = true,
    val remoteParticipants: List<RemoteParticipant> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
    val isSignalingConnected: Boolean = false,
    val iceConnectionState: String = "NEW",
    val connectionState: String = "NEW",
    val signalingState: String = "STABLE",
    val activeTransport: String? = null,
    val webrtcStatsSummary: String = "",
    val realtimeCallStats: RealtimeCallStats? = null,
    val isFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val localCameraMode: LocalCameraMode = LocalCameraMode.SELFIE,
    val isFlashAvailable: Boolean = false,
    val isFlashEnabled: Boolean = false,
    val remoteContentCid: String? = null,
    val remoteContentType: String? = null,
) {
    val remoteVideoEnabled: Boolean
        get() = remoteParticipants.firstOrNull()?.videoEnabled ?: false
}
