import Foundation

public struct RealtimeCallStats: Equatable {
    public var transportPath: String?
    public var rttMs: Double?
    public var availableOutgoingKbps: Double?
    public var audioRxPacketLossPct: Double?
    public var audioTxPacketLossPct: Double?
    public var audioJitterMs: Double?
    public var audioPlayoutDelayMs: Double?
    public var audioConcealedPct: Double?
    public var audioRxKbps: Double?
    public var audioTxKbps: Double?
    public var videoRxPacketLossPct: Double?
    public var videoTxPacketLossPct: Double?
    public var videoRxKbps: Double?
    public var videoTxKbps: Double?
    public var videoFps: Double?
    public var videoResolution: String?
    public var videoFreezeCount60s: Int64?
    public var videoFreezeDuration60s: Double?
    public var videoRetransmitPct: Double?
    public var videoNackPerMin: Double?
    public var videoPliPerMin: Double?
    public var videoFirPerMin: Double?
    public var updatedAtMs: Int64 = 0

    public static let empty = RealtimeCallStats()

    public init() {}

    public init(
        transportPath: String? = nil, rttMs: Double? = nil, availableOutgoingKbps: Double? = nil,
        audioRxPacketLossPct: Double? = nil, audioTxPacketLossPct: Double? = nil,
        audioJitterMs: Double? = nil, audioPlayoutDelayMs: Double? = nil,
        audioConcealedPct: Double? = nil, audioRxKbps: Double? = nil, audioTxKbps: Double? = nil,
        videoRxPacketLossPct: Double? = nil, videoTxPacketLossPct: Double? = nil,
        videoRxKbps: Double? = nil, videoTxKbps: Double? = nil,
        videoFps: Double? = nil, videoResolution: String? = nil,
        videoFreezeCount60s: Int64? = nil, videoFreezeDuration60s: Double? = nil,
        videoRetransmitPct: Double? = nil, videoNackPerMin: Double? = nil,
        videoPliPerMin: Double? = nil, videoFirPerMin: Double? = nil,
        updatedAtMs: Int64 = 0
    ) {
        self.transportPath = transportPath
        self.rttMs = rttMs
        self.availableOutgoingKbps = availableOutgoingKbps
        self.audioRxPacketLossPct = audioRxPacketLossPct
        self.audioTxPacketLossPct = audioTxPacketLossPct
        self.audioJitterMs = audioJitterMs
        self.audioPlayoutDelayMs = audioPlayoutDelayMs
        self.audioConcealedPct = audioConcealedPct
        self.audioRxKbps = audioRxKbps
        self.audioTxKbps = audioTxKbps
        self.videoRxPacketLossPct = videoRxPacketLossPct
        self.videoTxPacketLossPct = videoTxPacketLossPct
        self.videoRxKbps = videoRxKbps
        self.videoTxKbps = videoTxKbps
        self.videoFps = videoFps
        self.videoResolution = videoResolution
        self.videoFreezeCount60s = videoFreezeCount60s
        self.videoFreezeDuration60s = videoFreezeDuration60s
        self.videoRetransmitPct = videoRetransmitPct
        self.videoNackPerMin = videoNackPerMin
        self.videoPliPerMin = videoPliPerMin
        self.videoFirPerMin = videoFirPerMin
        self.updatedAtMs = updatedAtMs
    }
}
