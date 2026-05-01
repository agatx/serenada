import Combine
import Foundation

/// Polls the primer peer connection for local `media-source.audioLevel`
/// (via `collectLocalLevel`) and each peer slot for inbound (remote) level
/// every `AudioLevelMonitor.updateIntervalSeconds`, runs the raw values
/// through per-cid `AudioLevelMonitor`s for dBFS+EMA smoothing, and reports
/// the smoothed values on the main actor.
///
/// The primer keeps WebRTC's audio pipeline live throughout a session, so
/// the local level is consistent across Waiting and InCall — and works on
/// the very first tick after a peer leaves and we're back to alone in the
/// room. Mirrors the web SDK's `AudioLevelMonitor` smoothing pipeline so
/// the indicator visual is consistent across platforms.
@MainActor
final class AudioLevelPoller {
    private let clock: SessionClock
    private let isActivePhase: () -> Bool
    private let getPeerSlots: () -> [any PeerConnectionSlotProtocol]
    private let collectLocalLevel: (@escaping @Sendable (Float?) -> Void) -> Void
    private let onLevelsUpdated: (_ localLevel: Float, _ remoteLevels: [String: Float]) -> Void

    private var pollTimerCancellable: AnyCancellable?
    private var requestInFlight = false
    private let localMonitor = AudioLevelMonitor()
    private var remoteMonitors: [String: AudioLevelMonitor] = [:]
    /// Bumped on each `start()`/`stop()` so any stats request started in a
    /// previous run can be discarded when its async result returns.
    private var generation: UInt64 = 0

    init(
        clock: SessionClock,
        isActivePhase: @escaping () -> Bool,
        getPeerSlots: @escaping () -> [any PeerConnectionSlotProtocol],
        collectLocalLevel: @escaping (@escaping @Sendable (Float?) -> Void) -> Void,
        onLevelsUpdated: @escaping (_ localLevel: Float, _ remoteLevels: [String: Float]) -> Void
    ) {
        self.clock = clock
        self.isActivePhase = isActivePhase
        self.getPeerSlots = getPeerSlots
        self.collectLocalLevel = collectLocalLevel
        self.onLevelsUpdated = onLevelsUpdated
    }

    func start() {
        stop()
        generation &+= 1
        pollTimerCancellable = clock.scheduleRepeating(intervalSeconds: AudioLevelMonitor.updateIntervalSeconds) { [weak self] in
            self?.tick()
        }
    }

    func stop() {
        pollTimerCancellable?.cancel()
        pollTimerCancellable = nil
        // Bumping the generation invalidates any stats request kicked off by
        // a previous tick — when its async completion fires, applyAndEmit
        // will drop the result instead of emitting after teardown.
        generation &+= 1
        requestInFlight = false
        localMonitor.reset()
        remoteMonitors.removeAll()
    }

    private func tick() {
        guard pollTimerCancellable != nil, isActivePhase() else { return }
        if requestInFlight { return }
        let slots = getPeerSlots()

        requestInFlight = true
        let tickGeneration = generation
        let group = DispatchGroup()
        let lock = NSLock()
        var rawRemote: [String: Float?] = [:]
        var rawLocal: Float?

        group.enter()
        collectLocalLevel { level in
            lock.lock()
            rawLocal = level
            lock.unlock()
            group.leave()
        }

        for slot in slots {
            let cid = slot.remoteCid
            group.enter()
            slot.collectAudioLevels { inbound, _ in
                lock.lock()
                rawRemote[cid] = inbound
                lock.unlock()
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            Task { @MainActor in self.applyAndEmit(rawLocal: rawLocal, rawRemote: rawRemote, tickGeneration: tickGeneration) }
        }
    }

    private func applyAndEmit(rawLocal: Float?, rawRemote: [String: Float?], tickGeneration: UInt64) {
        // Drop late results from a previous start()/stop() cycle.
        guard tickGeneration == generation, pollTimerCancellable != nil else {
            requestInFlight = false
            return
        }
        requestInFlight = false
        let local = localMonitor.update(rawLevel: rawLocal ?? 0)
        // Drop monitors for peers no longer present. Snapshot the keys so we
        // don't mutate the dictionary while iterating it.
        let activeCids = Set(rawRemote.keys)
        for cid in Array(remoteMonitors.keys) where !activeCids.contains(cid) {
            remoteMonitors.removeValue(forKey: cid)
        }
        var remote: [String: Float] = [:]
        for (cid, raw) in rawRemote {
            let monitor = remoteMonitors[cid] ?? AudioLevelMonitor()
            remoteMonitors[cid] = monitor
            remote[cid] = monitor.update(rawLevel: raw ?? 0)
        }
        onLevelsUpdated(local, remote)
    }
}
