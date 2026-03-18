import Foundation

public struct CallStats: Equatable {
    public var bitrate: Double?
    public var packetLoss: Double?
    public var jitter: Double?
    public var codec: String?
    public var iceCandidatePair: String?
    public var roundTripTime: Double?
    public var audioRxKbps: Double?
    public var audioTxKbps: Double?
    public var videoRxKbps: Double?
    public var videoTxKbps: Double?
    public var videoFps: Double?
    public var videoResolution: String?
    public var updatedAtMs: Int64 = 0

    public static let empty = CallStats()

    public init() {}

    public init(from realtimeStats: RealtimeCallStats) {
        self.bitrate = realtimeStats.availableOutgoingKbps
        self.packetLoss = realtimeStats.videoRxPacketLossPct ?? realtimeStats.audioRxPacketLossPct
        self.jitter = realtimeStats.audioJitterMs
        self.roundTripTime = realtimeStats.rttMs
        self.audioRxKbps = realtimeStats.audioRxKbps
        self.audioTxKbps = realtimeStats.audioTxKbps
        self.videoRxKbps = realtimeStats.videoRxKbps
        self.videoTxKbps = realtimeStats.videoTxKbps
        self.videoFps = realtimeStats.videoFps
        self.videoResolution = realtimeStats.videoResolution
        self.iceCandidatePair = realtimeStats.transportPath
        self.updatedAtMs = realtimeStats.updatedAtMs
    }
}
