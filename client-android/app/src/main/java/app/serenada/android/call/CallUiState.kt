package app.serenada.android.call

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val roomId: String? = null,
    val statusMessageResId: Int? = null,
    val errorMessageResId: Int? = null,
    val errorMessageText: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = true,
    val remoteVideoEnabled: Boolean = false,
    val isReconnecting: Boolean = false,
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
    val isFlashEnabled: Boolean = false
)
