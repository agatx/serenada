package app.serenada.core

import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.AudioIntent

/** Default preference order for [SerenadaConfig.cameraModes] when left null. */
val DEFAULT_CAMERA_MODES: List<LocalCameraMode> = listOf(
    LocalCameraMode.SELFIE,
    LocalCameraMode.WORLD,
    LocalCameraMode.COMPOSITE,
)

/**
 * Configuration for the Serenada SDK.
 */
data class SerenadaConfig(
    /** Server host or origin (e.g. "serenada.app" or "http://localhost:8080"). */
    val serverHost: String? = null,
    /** Custom signaling provider. Provide exactly one of `serverHost` or `signalingProvider`. */
    val signalingProvider: SignalingProvider? = null,
    /** Whether audio starts enabled (default true). */
    val defaultAudioEnabled: Boolean = true,
    /** Whether video starts enabled (default true). */
    val defaultVideoEnabled: Boolean = true,
    /**
     * Camera modes available in the call UI, in preference order. The first
     * entry is the initial mode. When only one mode is listed the flip-camera
     * control is hidden; an empty list disables video entirely (the video
     * toggle is hidden and the camera is never requested). Modes unsupported
     * on the current device are silently dropped (`COMPOSITE` is dropped on
     * devices without multi-cam). `SCREEN_SHARE` is always ignored — screen
     * sharing is controlled separately. Defaults to `[SELFIE, WORLD, COMPOSITE]`.
     */
    val cameraModes: List<LocalCameraMode>? = null,
    /** Enable experimental HD video capture. */
    val isHdVideoExperimentalEnabled: Boolean = false,
    /** Preferred signaling transports in priority order (default: WS then SSE). */
    val transports: List<SerenadaTransport> = listOf(SerenadaTransport.WS, SerenadaTransport.SSE),
    /** Whether the proximity sensor is used to switch audio to the earpiece and pause video (default false). */
    val proximityMonitoringEnabled: Boolean = false,
    /** Custom audio coordinator. If null, the SDK uses its internal default coordinator. */
    val audioCoordinator: SerenadaAudioCoordinator? = null,
    /** Audio policy passed to the coordinator when a call session activates. */
    val audioIntent: AudioIntent = AudioIntent(),
)

/** Available signaling transport types. */
enum class SerenadaTransport {
    /** WebSocket transport. */
    WS,
    /** Server-Sent Events transport. */
    SSE,
}

internal data class ResolvedSerenadaConfig(
    val serverHost: String?,
    val signalingProvider: SignalingProvider?,
)

internal const val SUPPORTED_SIGNALING_PROVIDER_VERSION = 1

internal fun resolveSerenadaConfig(config: SerenadaConfig): ResolvedSerenadaConfig {
    val serverHost = config.serverHost?.trim()?.takeIf { it.isNotEmpty() }
    val signalingProvider = config.signalingProvider
    require((serverHost == null) != (signalingProvider == null)) {
        "Provide exactly one of serverHost or signalingProvider"
    }
    if (signalingProvider != null && signalingProvider.version != SUPPORTED_SIGNALING_PROVIDER_VERSION) {
        throw IllegalArgumentException("Unsupported signalingProvider version: ${signalingProvider.version}")
    }
    return ResolvedSerenadaConfig(
        serverHost = serverHost,
        signalingProvider = signalingProvider,
    )
}

internal fun requireServerHost(config: SerenadaConfig): String {
    return resolveSerenadaConfig(config).serverHost
        ?: throw IllegalStateException("requires serverHost")
}
