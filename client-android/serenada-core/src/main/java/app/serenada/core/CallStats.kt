package app.serenada.core

import app.serenada.core.call.RealtimeCallStats

/**
 * Aggregated call statistics exposed to SDK consumers.
 * Populated from WebRTC getStats() periodically during an active call.
 */
data class CallStats(
    val bitrate: Double? = null,
    val packetLoss: Double? = null,
    val jitter: Double? = null,
    val codec: String? = null,
    val iceCandidatePair: String? = null,
    val roundTripTime: Double? = null,
    val audioRxKbps: Double? = null,
    val audioTxKbps: Double? = null,
    val videoRxKbps: Double? = null,
    val videoTxKbps: Double? = null,
    val videoFps: Double? = null,
    val videoResolution: String? = null,
    val realtimeStats: RealtimeCallStats? = null,
    val updatedAtMs: Long = 0L,
)
