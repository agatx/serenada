package app.serenada.core

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
    /** Enable experimental HD video capture. */
    val isHdVideoExperimentalEnabled: Boolean = false,
    /** Preferred signaling transports in priority order (default: WS then SSE). */
    val transports: List<SerenadaTransport> = listOf(SerenadaTransport.WS, SerenadaTransport.SSE),
    /** Whether the proximity sensor is used to switch audio to the earpiece and pause video (default false). */
    val proximityMonitoringEnabled: Boolean = false,
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
