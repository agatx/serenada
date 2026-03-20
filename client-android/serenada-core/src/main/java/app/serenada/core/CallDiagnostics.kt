package app.serenada.core

import app.serenada.core.call.RealtimeCallStats

enum class IceConnectionState {
    NEW,
    CHECKING,
    CONNECTED,
    COMPLETED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    COUNT,
    UNKNOWN;

    companion object {
        fun from(rawValue: String): IceConnectionState =
            entries.firstOrNull { it.name == rawValue } ?: UNKNOWN
    }
}

enum class PeerConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    UNKNOWN;

    companion object {
        fun from(rawValue: String): PeerConnectionState =
            entries.firstOrNull { it.name == rawValue } ?: UNKNOWN
    }
}

enum class RtcSignalingState {
    STABLE,
    HAVE_LOCAL_OFFER,
    HAVE_REMOTE_OFFER,
    HAVE_LOCAL_PRANSWER,
    HAVE_REMOTE_PRANSWER,
    CLOSED,
    UNKNOWN;

    companion object {
        fun from(rawValue: String): RtcSignalingState =
            entries.firstOrNull { it.name == rawValue } ?: UNKNOWN
    }
}

enum class FeatureDegradation {
    COMPOSITE_CAMERA_UNAVAILABLE,
}

data class FeatureDegradationState(
    val kind: FeatureDegradation,
    val reason: String? = null,
)

data class CallDiagnostics(
    val isSignalingConnected: Boolean = false,
    val iceConnectionState: IceConnectionState = IceConnectionState.NEW,
    val peerConnectionState: PeerConnectionState = PeerConnectionState.NEW,
    val rtcSignalingState: RtcSignalingState = RtcSignalingState.STABLE,
    val activeTransport: String? = null,
    val callStats: CallStats = CallStats(),
    val realtimeStats: RealtimeCallStats? = null,
    val isFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val isFlashAvailable: Boolean = false,
    val isFlashEnabled: Boolean = false,
    val remoteContentCid: String? = null,
    val remoteContentType: String? = null,
    val featureDegradations: List<FeatureDegradationState> = emptyList(),
)
