package app.serenada.callui

import app.serenada.core.call.CallPhase
import app.serenada.core.call.ConnectionStatus
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.ParticipantContent
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
    val callStartedAtMs: Long? = null,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = true,
    /**
     * Camera video specifically. In the flag-off build this mirrors
     * [localVideoEnabled]; in independent mode it tracks camera-active separately
     * from screen share so the owner camera tile/PIP can render alongside content.
     */
    val localCameraEnabled: Boolean = true,
    /**
     * Precise local content (screen share) presentation state. Null when the
     * local user is not sharing content. Drives content tiles WITHOUT inferring
     * from [localCameraMode] / [isScreenSharing].
     */
    val localContent: ParticipantContent? = null,
    val localDisplayName: String? = null,
    val localAudioLevel: Float = 0f,
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
    val availableCameraModes: List<LocalCameraMode> = app.serenada.core.DEFAULT_CAMERA_MODES,
    val isFlashAvailable: Boolean = false,
    val isFlashEnabled: Boolean = false,
    val remoteContentCid: String? = null,
    val remoteContentType: String? = null,
    /**
     * Whether the local build negotiates an independent content (screen share)
     * video track ([app.serenada.core.SerenadaConfig.enableIndependentContentVideo]).
     * Default false ⇒ no content track can exist ⇒ the UI treats every share as
     * the legacy single-video-as-content path (byte-identical to today).
     */
    val independentContentEnabled: Boolean = false,
    /**
     * Whether the local user can receive any video media at all
     * ([app.serenada.core.SerenadaConfig.videoMediaEnabled]). Default true.
     * When false (PSTN / audio-only) the UI suppresses ALL content UI even if
     * room state reports another participant sharing.
     */
    val videoMediaEnabled: Boolean = true,
) {
    val remoteVideoEnabled: Boolean
        get() = remoteParticipants.firstOrNull()?.videoEnabled ?: false
}
