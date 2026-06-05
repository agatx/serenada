package app.serenada.core.call

/**
 * Canonical signaling protocol wire constants shared across Serenada clients.
 * Run `node scripts/check-signaling-protocol-constants.mjs` to verify cross-platform parity.
 */
internal object SignalingProtocolConstants {
    const val MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION = "local track negotiation"
}
