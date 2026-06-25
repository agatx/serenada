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
     * Whether this call can negotiate any video media. Set false for strict
     * audio-only calls such as PSTN: camera capture, screen sharing, and
     * remote video are all disabled. Defaults to true.
     */
    val videoMediaEnabled: Boolean = true,
    /**
     * Static build capability: whether this client can negotiate, send,
     * receive, classify, expose, and render an independent content (screen
     * share) video stream. Advertised at `join` as
     * `capabilities.independentContentVideo`. Immutable per session.
     *
     * Defaults to false so external integrators keep the legacy single-video
     * screen-share behavior until their UI can render the content stream.
     */
    val enableIndependentContentVideo: Boolean = false,
    /**
     * Camera modes available in the call UI, in preference order. The first
     * entry is the initial mode. When only one mode is listed the flip-camera
     * control is hidden; an empty list disables camera capture (the video
     * toggle is hidden and the camera is never requested). Remote video and
     * screen sharing remain available unless [videoMediaEnabled] is false.
     * Modes unsupported on the current device are silently dropped
     * (`COMPOSITE` is dropped on devices without multi-cam). `SCREEN_SHARE` is
     * always ignored — screen sharing is controlled separately. Defaults to
     * `[SELFIE, WORLD, COMPOSITE]`.
     */
    val cameraModes: List<LocalCameraMode>? = null,
    /** Enable experimental HD video capture. */
    val isHdVideoExperimentalEnabled: Boolean = false,
    /**
     * When true, defer the initial-negotiation offer-timeout/ICE-restart while the host peer
     * awaits its FIRST answer. For app-owned calls whose answer is gated on a remote action that
     * may take much longer than the offer timeout (e.g. PSTN human pickup). Normal offer-timeout
     * behavior resumes after the first answer. Default false = unchanged for existing calls.
     */
    val deferInitialAnswer: Boolean = false,
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
