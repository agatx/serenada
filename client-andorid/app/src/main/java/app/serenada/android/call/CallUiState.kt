package app.serenada.android.call

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val roomId: String? = null,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val localAudioEnabled: Boolean = true,
    val localVideoEnabled: Boolean = true,
    val remoteVideoEnabled: Boolean = false
)
