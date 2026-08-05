import Combine
import Foundation

@MainActor
final class StatsPoller {
    private let clock: SessionClock
    private let isActivePhase: () -> Bool
    private let getPeerSlots: () -> [any PeerConnectionSlotProtocol]
    private let onStatsUpdated: (RealtimeCallStats) -> Void
    private let onRefreshRemoteParticipants: () -> Void

    private var pollTimerCancellable: AnyCancellable?
    private var webrtcStatsRequestInFlight = false
    private var lastWebRtcStatsPollAtMs: Int64 = 0

    init(
        clock: SessionClock,
        isActivePhase: @escaping () -> Bool,
        getPeerSlots: @escaping () -> [any PeerConnectionSlotProtocol],
        onStatsUpdated: @escaping (RealtimeCallStats) -> Void,
        onRefreshRemoteParticipants: @escaping () -> Void
    ) {
        self.clock = clock
        self.isActivePhase = isActivePhase
        self.getPeerSlots = getPeerSlots
        self.onStatsUpdated = onStatsUpdated
        self.onRefreshRemoteParticipants = onRefreshRemoteParticipants
    }

    func start() {
        stop()

        pollTimerCancellable = clock.scheduleRepeating(intervalSeconds: 0.5) { [weak self] in
            guard let self else { return }
            self.onRefreshRemoteParticipants()
            self.pollWebRtcStats()
        }
    }

    func stop() {
        pollTimerCancellable?.cancel()
        pollTimerCancellable = nil
        webrtcStatsRequestInFlight = false
        lastWebRtcStatsPollAtMs = 0
    }

    private func pollWebRtcStats() {
        guard isActivePhase() else { return }

        let now = clock.nowMs()
        if webrtcStatsRequestInFlight { return }
        if now - lastWebRtcStatsPollAtMs < 2000 { return }

        webrtcStatsRequestInFlight = true

        let slots = getPeerSlots()
        guard !slots.isEmpty else {
            webrtcStatsRequestInFlight = false
            lastWebRtcStatsPollAtMs = now
            onStatsUpdated(.empty)
            return
        }

        let group = DispatchGroup()
        var stats: [RealtimeCallStats] = []
        let lock = NSLock()

        for slot in slots {
            group.enter()
            slot.collectRealtimeCallStats { realtimeStats in
                lock.lock()
                stats.append(realtimeStats)
                lock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            self.webrtcStatsRequestInFlight = false
            self.lastWebRtcStatsPollAtMs = self.clock.nowMs()
            self.onStatsUpdated(Self.mergeRealtimeStats(stats))
        }
    }

    static func mergeRealtimeStats(_ stats: [RealtimeCallStats]) -> RealtimeCallStats {
        guard !stats.isEmpty else { return .empty }

        func sumNonNil(_ values: [Double]) -> Double? {
            guard !values.isEmpty else { return nil }
            return values.reduce(0, +)
        }

        func sumNonNilInt64(_ values: [Int64]) -> Int64? {
            guard !values.isEmpty else { return nil }
            return values.reduce(0, +)
        }

        var merged = RealtimeCallStats.empty
        merged.transportPath = Array(Set(stats.compactMap(\.transportPath))).sorted().joined(separator: " | ")
        if merged.transportPath?.isEmpty == true {
            merged.transportPath = nil
        }
        merged.rttMs = stats.compactMap(\.rttMs).max()
        merged.availableOutgoingKbps = stats.compactMap(\.availableOutgoingKbps).min()
        merged.audioRxCodec = joinCodecMimeTypes(stats.compactMap(\.audioRxCodec))
        merged.audioTxCodec = joinCodecMimeTypes(stats.compactMap(\.audioTxCodec))
        merged.audioRxPacketLossPct = stats.compactMap(\.audioRxPacketLossPct).max()
        merged.audioTxPacketLossPct = stats.compactMap(\.audioTxPacketLossPct).max()
        merged.audioJitterMs = stats.compactMap(\.audioJitterMs).max()
        merged.audioPlayoutDelayMs = stats.compactMap(\.audioPlayoutDelayMs).max()
        merged.audioConcealedPct = stats.compactMap(\.audioConcealedPct).max()
        merged.audioRxKbps = sumNonNil(stats.compactMap(\.audioRxKbps))
        merged.audioTxKbps = sumNonNil(stats.compactMap(\.audioTxKbps))
        merged.videoRxCodec = joinCodecMimeTypes(stats.compactMap(\.videoRxCodec))
        merged.videoTxCodec = joinCodecMimeTypes(stats.compactMap(\.videoTxCodec))
        merged.videoRxPacketLossPct = stats.compactMap(\.videoRxPacketLossPct).max()
        merged.videoTxPacketLossPct = stats.compactMap(\.videoTxPacketLossPct).max()
        merged.videoRxKbps = sumNonNil(stats.compactMap(\.videoRxKbps))
        merged.videoTxKbps = sumNonNil(stats.compactMap(\.videoTxKbps))
        merged.videoFps = stats.compactMap(\.videoFps).min()
        let resolutions = Array(Set(stats.compactMap(\.videoResolution))).sorted()
        merged.videoResolution = resolutions.isEmpty ? nil : resolutions.joined(separator: " | ")
        merged.videoFreezeCount60s = stats.compactMap(\.videoFreezeCount60s).reduce(0, +)
        merged.videoFreezeDuration60s = sumNonNil(stats.compactMap(\.videoFreezeDuration60s))
        merged.videoRetransmitPct = stats.compactMap(\.videoRetransmitPct).max()
        merged.videoNackPerMin = sumNonNil(stats.compactMap(\.videoNackPerMin))
        merged.videoPliPerMin = sumNonNil(stats.compactMap(\.videoPliPerMin))
        merged.videoFirPerMin = sumNonNil(stats.compactMap(\.videoFirPerMin))
        // Sum cumulative counters across slots (nil when
        // no slot reported the kind). CallQualityTracker / the host segment
        // diff rebaseline when the *aggregate* delta goes negative (counter
        // reset / slot teardown). In the dominant 1:1 case there is a single
        // slot so this is exact; for mesh, a simultaneous reset of one slot
        // masked by another slot's increase is an accepted approximation.
        merged.videoFramesDecoded = sumNonNilInt64(stats.compactMap(\.videoFramesDecoded))
        merged.videoFramesDropped = sumNonNilInt64(stats.compactMap(\.videoFramesDropped))
        merged.audioPacketsLost = sumNonNilInt64(stats.compactMap(\.audioPacketsLost))
        merged.audioPacketsReceived = sumNonNilInt64(stats.compactMap(\.audioPacketsReceived))
        merged.updatedAtMs = stats.map(\.updatedAtMs).max() ?? 0
        return merged
    }
}
