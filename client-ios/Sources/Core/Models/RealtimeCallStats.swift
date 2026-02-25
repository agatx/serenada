import Foundation

struct RealtimeCallStats: Equatable {
    var transportPath: String?
    var rttMs: Double?
    var availableOutgoingKbps: Double?
    var audioRxPacketLossPct: Double?
    var audioTxPacketLossPct: Double?
    var audioJitterMs: Double?
    var audioPlayoutDelayMs: Double?
    var audioConcealedPct: Double?
    var audioRxKbps: Double?
    var audioTxKbps: Double?
    var videoRxPacketLossPct: Double?
    var videoTxPacketLossPct: Double?
    var videoRxKbps: Double?
    var videoTxKbps: Double?
    var videoFps: Double?
    var videoResolution: String?
    var videoFreezeCount60s: Int64?
    var videoFreezeDuration60s: Double?
    var videoRetransmitPct: Double?
    var videoNackPerMin: Double?
    var videoPliPerMin: Double?
    var videoFirPerMin: Double?
    var updatedAtMs: Int64 = 0

    static let empty = RealtimeCallStats()
}
