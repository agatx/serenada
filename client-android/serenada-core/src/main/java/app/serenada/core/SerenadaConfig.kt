package app.serenada.core

/**
 * Configuration for the Serenada SDK.
 */
data class SerenadaConfig(
    val serverHost: String,
    val defaultAudioEnabled: Boolean = true,
    val defaultVideoEnabled: Boolean = true,
    val isHdVideoExperimentalEnabled: Boolean = false,
    val transports: List<SerenadaTransport> = listOf(SerenadaTransport.WS, SerenadaTransport.SSE),
)

enum class SerenadaTransport {
    WS,
    SSE,
}
