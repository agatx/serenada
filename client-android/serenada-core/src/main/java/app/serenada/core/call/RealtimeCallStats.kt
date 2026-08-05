package app.serenada.core.call

data class RealtimeCallStats(
    val transportPath: String? = null,
    val rttMs: Double? = null,
    val availableOutgoingKbps: Double? = null,
    val audioRxCodec: String? = null,
    val audioTxCodec: String? = null,
    val audioRxPacketLossPct: Double? = null,
    val audioTxPacketLossPct: Double? = null,
    val audioJitterMs: Double? = null,
    val audioPlayoutDelayMs: Double? = null,
    val audioConcealedPct: Double? = null,
    val audioRxKbps: Double? = null,
    val audioTxKbps: Double? = null,
    val videoRxCodec: String? = null,
    val videoTxCodec: String? = null,
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
    /** Cumulative inbound-video `framesDecoded`, summed across peer slots. */
    val videoFramesDecoded: Long? = null,
    /** Cumulative inbound-video `framesDropped`, summed across peer slots. */
    val videoFramesDropped: Long? = null,
    /** Cumulative inbound-audio `packetsLost`, summed across peer slots. */
    val audioPacketsLost: Long? = null,
    /** Cumulative inbound-audio `packetsReceived`, summed across peer slots. */
    val audioPacketsReceived: Long? = null,
    val updatedAtMs: Long = 0L
)
