package app.serenada.android.call

data class RealtimeCallStats(
    val transportPath: String? = null,
    val rttMs: Double? = null,
    val availableOutgoingKbps: Double? = null,
    val audioRxPacketLossPct: Double? = null,
    val audioTxPacketLossPct: Double? = null,
    val audioJitterMs: Double? = null,
    val audioPlayoutDelayMs: Double? = null,
    val audioConcealedPct: Double? = null,
    val audioRxKbps: Double? = null,
    val audioTxKbps: Double? = null,
    val videoRxPacketLossPct: Double? = null,
    val videoTxPacketLossPct: Double? = null,
    val videoRxKbps: Double? = null,
    val videoTxKbps: Double? = null,
    val videoFps: Double? = null,
    val videoResolution: String? = null,
    val videoFreezeCount60s: Long? = null,
    val videoFreezeDuration60s: Double? = null,
    val videoRetransmitPct: Double? = null,
    val videoNackPerMin: Double? = null,
    val videoPliPerMin: Double? = null,
    val videoFirPerMin: Double? = null,
    val updatedAtMs: Long = 0L
)
