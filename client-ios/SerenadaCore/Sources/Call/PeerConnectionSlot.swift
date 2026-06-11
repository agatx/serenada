import Foundation
#if canImport(WebRTC)
@preconcurrency import WebRTC
#endif

@MainActor
internal final class PeerConnectionSlot: PeerConnectionSlotProtocol {
    public let remoteCid: String

    public private(set) var sentOffer = false
    public private(set) var isMakingOffer = false
    public private(set) var pendingIceRestart = false
    public private(set) var lastIceRestartAt: TimeInterval = 0
    public private(set) var offerTimeoutTask: Task<Void, Never>?
    public private(set) var iceRestartTask: Task<Void, Never>?
    private var playbackDucked = false

    // MARK: - Offer Lifecycle

    public func beginOffer() { isMakingOffer = true }
    public func completeOffer() { isMakingOffer = false }
    public func markOfferSent() { sentOffer = true }

    // MARK: - ICE Restart Lifecycle

    public func markPendingIceRestart() { pendingIceRestart = true }
    public func clearPendingIceRestart() { pendingIceRestart = false }

    public func recordIceRestart(nowMs: Int64) {
        lastIceRestartAt = TimeInterval(nowMs)
        pendingIceRestart = false
    }

    // MARK: - Task Management

    public func setOfferTimeoutTask(_ task: Task<Void, Never>) {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = task
    }

    public func cancelOfferTimeout() {
        offerTimeoutTask?.cancel()
        offerTimeoutTask = nil
    }

    public func setIceRestartTask(_ task: Task<Void, Never>) {
        iceRestartTask?.cancel()
        iceRestartTask = task
    }

    public func cancelIceRestartTask() {
        iceRestartTask?.cancel()
        iceRestartTask = nil
    }

#if canImport(WebRTC)
    private struct RealtimeStatsSample {
        let timestampMs: Int64
        let audioRxBytes: Int64
        let audioTxBytes: Int64
        let videoRxBytes: Int64
        let videoTxBytes: Int64
        let videoFramesDecoded: Int64
        let videoNackCount: Int64
        let videoPliCount: Int64
        let videoFirCount: Int64
    }

    private struct FreezeSample {
        let timestampMs: Int64
        let freezeCount: Int64
        let freezeDurationSeconds: Double
    }

    private struct MediaTotals {
        var inboundPacketsReceived: Int64 = 0
        var inboundPacketsLost: Int64 = 0
        var inboundBytes: Int64 = 0

        var outboundPacketsSent: Int64 = 0
        var outboundBytes: Int64 = 0
        var outboundPacketsRetransmitted: Int64 = 0

        var remoteInboundPacketsLost: Int64 = 0

        var inboundJitterSumSeconds: Double = 0
        var inboundJitterCount: Int64 = 0

        var inboundJitterBufferDelaySeconds: Double = 0
        var inboundJitterBufferEmittedCount: Int64 = 0
        var inboundConcealedSamples: Int64 = 0
        var inboundTotalSamples: Int64 = 0

        var inboundFpsSum: Double = 0
        var inboundFpsCount: Int64 = 0
        var inboundFrameWidth: Int = 0
        var inboundFrameHeight: Int = 0
        var inboundFramesDecoded: Int64 = 0
        var inboundFramesDropped: Int64 = 0

        var inboundFreezeCount: Int64 = 0
        var inboundFreezeDurationSeconds: Double = 0

        var inboundNackCount: Int64 = 0
        var inboundPliCount: Int64 = 0
        var inboundFirCount: Int64 = 0

        // Number of inbound-rtp stats seen for this kind. Distinguishes a
        // genuine 0 from "no inbound-rtp stat" so telemetry counters surface
        // nil (unknown) rather than a fake 0.
        var inboundRtpCount: Int = 0
        // Per-counter presence. A row can exist (inboundRtpCount > 0) yet omit
        // a specific member; surface nil for that member alone, not a fake 0.
        var sawPacketsReceived = false
        var sawPacketsLost = false
        var sawFramesDecoded = false
        var sawFramesDropped = false
    }

    private enum Constants {
        static let freezeWindowMs: Int64 = 60_000
    }

    private let factory: RTCPeerConnectionFactory?
    private var iceServers: [IceServerConfig]?
    private var localAudioTrack: RTCAudioTrack?
    private var localVideoTrack: RTCVideoTrack?
    private let onLocalIceCandidate: (String, IceCandidatePayload) -> Void
    private let onRemoteVideoTrack: (String, RTCVideoTrack?) -> Void
    private let onConnectionStateChange: (String, String) -> Void
    private let onIceConnectionStateChange: (String, String) -> Void
    private let onSignalingStateChange: (String, String) -> Void
    private let onRenegotiationNeeded: (String) -> Void
    private let logger: SerenadaLogger?
    private let rendererAttachmentQueue: DispatchQueue

    private var peerConnection: RTCPeerConnection?
    private var observerProxy: SlotPeerConnectionObserverProxy?
    private var remoteVideoTrack: RTCVideoTrack?
    private var pendingRemoteIceCandidates: [IceCandidatePayload] = []
    private var remoteRenderers: [WeakRendererBox] = []
    private var lastRealtimeStatsSample: RealtimeStatsSample?
    // Written from the stats callback queue, read from the main actor gate.
    private let pathDirectLock = NSLock()
    private var lastPathIsDirectBacking: Bool?
    private var lastPathIsDirectValue: Bool? {
        get { pathDirectLock.lock(); defer { pathDirectLock.unlock() }; return lastPathIsDirectBacking }
        set { pathDirectLock.lock(); lastPathIsDirectBacking = newValue; pathDirectLock.unlock() }
    }
    private var freezeSamples: [FreezeSample] = []
#endif

#if canImport(WebRTC)
    public init(
        remoteCid: String,
        factory: RTCPeerConnectionFactory?,
        iceServers: [IceServerConfig]?,
        localAudioTrack: RTCAudioTrack?,
        localVideoTrack: RTCVideoTrack?,
        onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        onRemoteVideoTrack: @escaping (String, RTCVideoTrack?) -> Void,
        onConnectionStateChange: @escaping (String, String) -> Void,
        onIceConnectionStateChange: @escaping (String, String) -> Void,
        onSignalingStateChange: @escaping (String, String) -> Void,
        onRenegotiationNeeded: @escaping (String) -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.remoteCid = remoteCid
        self.factory = factory
        self.iceServers = iceServers
        self.localAudioTrack = localAudioTrack
        self.localVideoTrack = localVideoTrack
        self.onLocalIceCandidate = onLocalIceCandidate
        self.onRemoteVideoTrack = onRemoteVideoTrack
        self.onConnectionStateChange = onConnectionStateChange
        self.onIceConnectionStateChange = onIceConnectionStateChange
        self.onSignalingStateChange = onSignalingStateChange
        self.onRenegotiationNeeded = onRenegotiationNeeded
        self.logger = logger
        self.rendererAttachmentQueue = DispatchQueue(
            label: "serenada.ios.webrtc.slot-renderer-\(remoteCid)",
            qos: .userInitiated
        )
    }
#else
    public init(remoteCid: String) {
        self.remoteCid = remoteCid
    }
#endif

    public func setIceServers(_ servers: [IceServerConfig]) {
#if canImport(WebRTC)
        iceServers = servers
        if let peerConnection {
            // Apply refreshed credentials to the live connection so a later
            // ICE restart gathers relay candidates with current (not expired)
            // TURN credentials.
            let config = RTCConfiguration()
            config.iceServers = servers.map {
                RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
            }
            config.sdpSemantics = .unifiedPlan
            if !peerConnection.setConfiguration(config) {
                logger?.log(.warning, tag: "PeerConnection", "[\(remoteCid)] Failed to apply refreshed ICE servers")
            }
            return
        }
        _ = ensurePeerConnection()
#endif
    }

    @discardableResult
    public func ensurePeerConnection() -> Bool {
#if canImport(WebRTC)
        if peerConnection != nil { return true }
        guard let factory else { return false }
        guard let iceServers else { return false }

        let rtcServers = iceServers.map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }
        let config = RTCConfiguration()
        config.iceServers = rtcServers
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let observer = SlotPeerConnectionObserverProxy(
            onIceCandidate: { [weak self] candidate in
                Task { @MainActor in
                    guard let self else { return }
                    self.onLocalIceCandidate(
                        self.remoteCid,
                        IceCandidatePayload(
                            sdpMid: candidate.sdpMid,
                            sdpMLineIndex: candidate.sdpMLineIndex,
                            candidate: candidate.sdp
                        )
                    )
                }
            },
            onConnectionState: { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    self.onConnectionStateChange(self.remoteCid, connectionStateString(state))
                }
            },
            onIceConnectionState: { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    self.onIceConnectionStateChange(self.remoteCid, iceConnectionStateString(state))
                }
            },
            onSignalingState: { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    self.onSignalingStateChange(self.remoteCid, signalingStateString(state))
                }
            },
            onRenegotiationNeeded: { [weak self] in
                Task { @MainActor in
                    guard let self else { return }
                    self.onRenegotiationNeeded(self.remoteCid)
                }
            },
            onRemoteVideoTrack: { [weak self] track in
                Task { @MainActor in
                    guard let self else { return }
                    self.remoteVideoTrack = track
                    self.attachRemoteTrackToRegisteredRenderers()
                    self.onRemoteVideoTrack(self.remoteCid, track)
                }
            },
            onRemoteAudioTrack: { [weak self] track in
                Task { @MainActor in
                    guard let self else { return }
                    self.applyPlaybackDuck(to: track)
                }
            }
        )
        observerProxy = observer

        guard let peerConnection = factory.peerConnection(with: config, constraints: constraints, delegate: observer) else {
            return false
        }

        self.peerConnection = peerConnection
        attachLocalTracks(audioTrack: localAudioTrack, videoTrack: localVideoTrack)
        ensureReceiveTransceivers(on: peerConnection)
        return true
#else
        return false
#endif
    }

    public func attachLocalTracks(audioTrack: AnyObject? = nil, videoTrack: AnyObject? = nil) {
#if canImport(WebRTC)
        if let audioTrack = audioTrack as? RTCAudioTrack {
            self.localAudioTrack = audioTrack
        } else if audioTrack == nil {
            self.localAudioTrack = nil
        }
        if let videoTrack = videoTrack as? RTCVideoTrack {
            self.localVideoTrack = videoTrack
        } else if videoTrack == nil {
            self.localVideoTrack = nil
        }

        guard let peerConnection = peerConnection ?? (ensurePeerConnection() ? self.peerConnection : nil) else {
            return
        }

        attachTrackToTransceiver(peerConnection: peerConnection, track: localAudioTrack, mediaType: .audio)
        attachTrackToTransceiver(peerConnection: peerConnection, track: localVideoTrack, mediaType: .video)
#endif
    }

#if canImport(WebRTC)
    // Look up the transceiver by its stable mediaType (Unified Plan) instead of
    // sender.track?.kind. The latter mis-reports when the sender's track is nil
    // (e.g. the recv-only transceiver created by ensureReceiveTransceivers), which
    // would cause peerConnection.add to append a duplicate transceiver.
    private func attachTrackToTransceiver(
        peerConnection: RTCPeerConnection,
        track: RTCMediaStreamTrack?,
        mediaType: RTCRtpMediaType
    ) {
        if let transceiver = peerConnection.transceivers.first(where: { $0.mediaType == mediaType }) {
            if transceiver.sender.track !== track {
                transceiver.sender.track = track
            }
            let targetDirection: RTCRtpTransceiverDirection = (track != nil) ? .sendRecv : .recvOnly
            if transceiver.direction != targetDirection {
                transceiver.setDirection(targetDirection, error: nil)
            }
        } else if let track {
            _ = peerConnection.add(track, streamIds: ["serenada"])
        }
    }
#endif

    public func closePeerConnection() {
        cancelOfferTimeout()
        cancelIceRestartTask()

#if canImport(WebRTC)
        detachRemoteTrackFromRegisteredRenderers()
        peerConnection?.close()
        peerConnection = nil
        observerProxy = nil
        remoteVideoTrack = nil
        pendingRemoteIceCandidates.removeAll()
        remoteRenderers.removeAll()
        lastRealtimeStatsSample = nil
        freezeSamples.removeAll()
        onRemoteVideoTrack(remoteCid, nil)
#endif
    }

    @discardableResult
    public func createOffer(
        iceRestart: Bool = false,
        onSdp: @escaping (String) -> Void,
        onComplete: ((Bool) -> Void)? = nil
    ) -> Bool {
#if canImport(WebRTC)
        guard let peerConnection = peerConnection ?? (ensurePeerConnection() ? self.peerConnection : nil) else {
            onComplete?(false)
            return false
        }
        guard peerConnection.signalingState == .stable else {
            onComplete?(false)
            return false
        }

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: iceRestart ? ["IceRestart": "true"] : nil
        )

        peerConnection.offer(for: constraints) { description, error in
            guard error == nil, let description else {
                onComplete?(false)
                return
            }

            peerConnection.setLocalDescription(description) { setError in
                if setError == nil {
                    onSdp(description.sdp)
                    onComplete?(true)
                } else {
                    onComplete?(false)
                }
            }
        }
        return true
#else
        onComplete?(false)
        return false
#endif
    }

    public func createAnswer(onSdp: @escaping (String) -> Void, onComplete: ((Bool) -> Void)? = nil) {
#if canImport(WebRTC)
        guard let peerConnection = peerConnection ?? (ensurePeerConnection() ? self.peerConnection : nil) else {
            onComplete?(false)
            return
        }

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.answer(for: constraints) { description, error in
            guard error == nil, let description else {
                onComplete?(false)
                return
            }

            peerConnection.setLocalDescription(description) { setError in
                if setError == nil {
                    onSdp(description.sdp)
                    onComplete?(true)
                } else {
                    onComplete?(false)
                }
            }
        }
#else
        onComplete?(false)
#endif
    }

    public func setRemoteDescription(
        type: SessionDescriptionType,
        sdp: String,
        onComplete: ((Bool) -> Void)? = nil
    ) {
#if canImport(WebRTC)
        guard let peerConnection = peerConnection ?? (ensurePeerConnection() ? self.peerConnection : nil) else {
            onComplete?(false)
            return
        }

        let rtcType: RTCSdpType
        switch type {
        case .offer:
            rtcType = .offer
        case .answer:
            rtcType = .answer
        case .rollback:
            rtcType = .rollback
        }

        peerConnection.setRemoteDescription(RTCSessionDescription(type: rtcType, sdp: sdp)) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                if error == nil {
                    self.flushPendingIceCandidates()
                    onComplete?(true)
                } else {
                    onComplete?(false)
                }
            }
        }
#else
        onComplete?(false)
#endif
    }

    public func rollbackLocalDescription(onComplete: ((Bool) -> Void)? = nil) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete?(false)
            return
        }

        peerConnection.setLocalDescription(RTCSessionDescription(type: .rollback, sdp: "")) { error in
            onComplete?(error == nil)
        }
#else
        onComplete?(false)
#endif
    }

    public func addIceCandidate(_ candidate: IceCandidatePayload) {
#if canImport(WebRTC)
        guard let safeCandidate = sanitizeIceCandidate(candidate, remoteCid: remoteCid) else {
            return
        }
        guard let peerConnection = peerConnection ?? (ensurePeerConnection() ? self.peerConnection : nil) else {
            return
        }

        if peerConnection.remoteDescription == nil {
            if pendingRemoteIceCandidates.count < WebRtcResilience.iceCandidateBufferMax {
                pendingRemoteIceCandidates.append(safeCandidate)
            }
            return
        }

        peerConnection.add(
            RTCIceCandidate(
                sdp: safeCandidate.candidate,
                sdpMLineIndex: safeCandidate.sdpMLineIndex,
                sdpMid: safeCandidate.sdpMid
            )
        ) { _ in }
#endif
    }

    public func attachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        remoteRenderers.append(WeakRendererBox(value: renderer))
        compactRenderers()
        guard let renderer = renderer as? RTCVideoRenderer else { return }
        let track = remoteVideoTrack
        rendererAttachmentQueue.async {
            track?.add(renderer)
        }
#endif
    }

    public func detachRemoteRenderer(_ renderer: AnyObject) {
#if canImport(WebRTC)
        if let renderer = renderer as? RTCVideoRenderer {
            let track = remoteVideoTrack
            rendererAttachmentQueue.async {
                track?.remove(renderer)
            }
        }
        remoteRenderers.removeAll { $0.value === renderer || $0.value == nil }
#endif
    }

    public func collectRealtimeCallStats(onComplete: @escaping (RealtimeCallStats) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(.empty)
            return
        }

        peerConnection.statistics { [weak self] report in
            Task { @MainActor in
                guard let self else {
                    onComplete(.empty)
                    return
                }
                onComplete(self.buildRealtimeCallStats(report))
            }
        }
#else
        onComplete(.empty)
#endif
    }

    public func collectRealtimeCallStatsAndSummary(
        onComplete: @escaping (RealtimeCallStats, String) -> Void
    ) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(.empty, "pc=none")
            return
        }
        peerConnection.statistics { [weak self] report in
            Task { @MainActor in
                let summary = "stats=\(report.statistics.count)"
                guard let self else {
                    onComplete(.empty, summary)
                    return
                }
                onComplete(self.buildRealtimeCallStats(report), summary)
            }
        }
#else
        onComplete(.empty, "pc=stub")
#endif
    }

    public func collectInboundBytes(onComplete: @escaping (Int64) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(0)
            return
        }
        peerConnection.statistics { report in
            var bytes: Int64 = 0
            for stat in report.statistics.values where stat.type == "inbound-rtp" {
                bytes += memberInt64(stat, key: "bytesReceived") ?? 0
            }
            Task { @MainActor in onComplete(bytes) }
        }
#else
        onComplete(0)
#endif
    }

    public func collectOutboundMediaSample(onComplete: @escaping (OutboundMediaSample?) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(nil)
            return
        }

        let expectsAudio = peerConnection.senders.contains {
            $0.track?.kind == kRTCMediaStreamTrackKindAudio && $0.track?.isEnabled == true
        }
        let expectsVideo = peerConnection.senders.contains {
            $0.track?.kind == kRTCMediaStreamTrackKindVideo && $0.track?.isEnabled == true
        }

        peerConnection.statistics { report in
            var audioBytesSent: Int64 = 0
            var videoBytesSent: Int64 = 0
            var videoFramesSent: Int64 = 0
            for stat in report.statistics.values where stat.type == "outbound-rtp" {
                switch mediaKind(for: stat) {
                case "audio":
                    audioBytesSent += memberInt64(stat, key: "bytesSent") ?? 0
                case "video":
                    videoBytesSent += memberInt64(stat, key: "bytesSent") ?? 0
                    videoFramesSent += memberInt64(stat, key: "framesSent")
                        ?? memberInt64(stat, key: "framesEncoded")
                        ?? 0
                default:
                    break
                }
            }
            Task { @MainActor in
                onComplete(OutboundMediaSample(
                    expectsAudio: expectsAudio,
                    expectsVideo: expectsVideo,
                    audioBytesSent: audioBytesSent,
                    videoBytesSent: videoBytesSent,
                    videoFramesSent: videoFramesSent
                ))
            }
        }
#else
        onComplete(nil)
#endif
    }

    public func collectAudioLevels(onComplete: @escaping (_ inboundLevel: Float?, _ mediaSourceLevel: Float?) -> Void) {
#if canImport(WebRTC)
        guard let peerConnection else {
            onComplete(nil, nil)
            return
        }
        peerConnection.statistics { report in
            var inbound: Float?
            var mediaSource: Float?
            for stat in report.statistics.values {
                switch stat.type {
                case "inbound-rtp":
                    if mediaKind(for: stat) == "audio" {
                        inbound = clampedAudioLevel(memberDouble(stat, key: "audioLevel"))
                    }
                case "media-source":
                    if mediaKind(for: stat) == "audio" {
                        mediaSource = clampedAudioLevel(memberDouble(stat, key: "audioLevel"))
                    }
                default:
                    break
                }
            }
            Task { @MainActor in onComplete(inbound, mediaSource) }
        }
#else
        onComplete(nil, nil)
#endif
    }

    public func isReady() -> Bool {
#if canImport(WebRTC)
        peerConnection != nil
#else
        false
#endif
    }

    public func getConnectionState() -> SerenadaPeerConnectionState {
#if canImport(WebRTC)
        guard let peerConnection else { return .new }
        return peerConnectionState(peerConnection.connectionState)
#else
        return .new
#endif
    }

    public func isPathDirect() -> Bool? { lastPathIsDirectValue }

    public func getIceConnectionState() -> String {
#if canImport(WebRTC)
        guard let peerConnection else { return "NEW" }
        return iceConnectionStateString(peerConnection.iceConnectionState)
#else
        return "NEW"
#endif
    }

    public func getSignalingState() -> String {
#if canImport(WebRTC)
        guard let peerConnection else { return "STABLE" }
        return signalingStateString(peerConnection.signalingState)
#else
        return "STABLE"
#endif
    }

    public func hasRemoteDescription() -> Bool {
#if canImport(WebRTC)
        peerConnection?.remoteDescription != nil
#else
        false
#endif
    }

    public func isRemoteVideoTrackEnabled() -> Bool {
#if canImport(WebRTC)
        remoteVideoTrack?.isEnabled ?? false
#else
        false
#endif
    }

    public func duckPlayback(ducked: Bool) {
        playbackDucked = ducked
#if canImport(WebRTC)
        guard let peerConnection = peerConnection else { return }
        for receiver in peerConnection.receivers {
            if let audioTrack = receiver.track as? RTCAudioTrack {
                applyPlaybackDuck(to: audioTrack)
            }
        }
#endif
    }
}

#if canImport(WebRTC)
private extension PeerConnectionSlot {
    private func applyPlaybackDuck(to track: RTCAudioTrack) {
        track.source.volume = playbackDucked ? 0.15 : 1.0
    }

    private func ensureReceiveTransceivers(on peerConnection: RTCPeerConnection) {
        if peerConnection.transceivers.contains(where: { $0.mediaType == .audio }) == false {
            let transceiverInit = RTCRtpTransceiverInit()
            transceiverInit.direction = .recvOnly
            _ = peerConnection.addTransceiver(of: .audio, init: transceiverInit)
        }
        if peerConnection.transceivers.contains(where: { $0.mediaType == .video }) == false {
            let transceiverInit = RTCRtpTransceiverInit()
            transceiverInit.direction = .recvOnly
            _ = peerConnection.addTransceiver(of: .video, init: transceiverInit)
        }
    }

    private func flushPendingIceCandidates() {
        guard let peerConnection else { return }
        guard peerConnection.remoteDescription != nil else { return }
        let pending = pendingRemoteIceCandidates
        pendingRemoteIceCandidates.removeAll()
        for candidate in pending {
            peerConnection.add(
                RTCIceCandidate(
                    sdp: candidate.candidate,
                    sdpMLineIndex: candidate.sdpMLineIndex,
                    sdpMid: candidate.sdpMid
                )
            ) { _ in }
        }
    }

    private func attachRemoteTrackToRegisteredRenderers() {
        compactRenderers()
        guard let remoteVideoTrack else { return }
        let renderers = remoteRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
                remoteVideoTrack.add(renderer)
            }
        }
    }

    private func detachRemoteTrackFromRegisteredRenderers() {
        compactRenderers()
        guard let remoteVideoTrack else { return }
        let renderers = remoteRenderers.compactMap { $0.value as? RTCVideoRenderer }
        rendererAttachmentQueue.async {
            for renderer in renderers {
                remoteVideoTrack.remove(renderer)
            }
        }
    }

    private func compactRenderers() {
        remoteRenderers.removeAll { $0.value == nil }
    }

    private func buildRealtimeCallStats(_ report: RTCStatisticsReport) -> RealtimeCallStats {
        let stats = Array(report.statistics.values)
        var audio = MediaTotals()
        var video = MediaTotals()

        var selectedCandidatePair: RTCStatistics?
        var fallbackCandidatePair: RTCStatistics?
        var remoteInboundRttSumSeconds = 0.0
        var remoteInboundRttCount: Int64 = 0

        for stat in stats {
            if stat.type == "candidate-pair" {
                let isSelected = memberBool(stat, key: "selected") == true
                let isNominated = memberBool(stat, key: "nominated") == true
                let pairState = memberString(stat, key: "state")
                if isSelected {
                    selectedCandidatePair = stat
                } else if fallbackCandidatePair == nil && isNominated && pairState == "succeeded" {
                    fallbackCandidatePair = stat
                }
                continue
            }

            guard let kind = mediaKind(for: stat) else { continue }
            if kind == "audio" {
                collectMediaStat(
                    stat,
                    into: &audio,
                    remoteInboundRttSumSeconds: &remoteInboundRttSumSeconds,
                    remoteInboundRttCount: &remoteInboundRttCount
                )
            } else {
                collectMediaStat(
                    stat,
                    into: &video,
                    remoteInboundRttSumSeconds: &remoteInboundRttSumSeconds,
                    remoteInboundRttCount: &remoteInboundRttCount
                )
            }
        }

        let selectedPair = selectedCandidatePair ?? fallbackCandidatePair
        let localCandidate = selectedPair.flatMap { pair -> RTCStatistics? in
            guard let id = memberString(pair, key: "localCandidateId"), !id.isEmpty else { return nil }
            return report.statistics[id]
        }
        let remoteCandidate = selectedPair.flatMap { pair -> RTCStatistics? in
            guard let id = memberString(pair, key: "remoteCandidateId"), !id.isEmpty else { return nil }
            return report.statistics[id]
        }

        let localCandidateType = memberString(localCandidate, key: "candidateType")
        let remoteCandidateType = memberString(remoteCandidate, key: "candidateType")
        let localProtocol = memberString(localCandidate, key: "protocol")
        let remoteProtocol = memberString(remoteCandidate, key: "protocol")
        let isRelay = localCandidateType == "relay" || remoteCandidateType == "relay"
        // Cache path type for the TURN refresh gate. Stays nil until we have
        // a candidate type so the gate errs on the side of refreshing.
        if localCandidateType != nil || remoteCandidateType != nil {
            let direct = !isRelay
            if lastPathIsDirectValue != direct { lastPathIsDirectValue = direct }
        }
        let transportPath: String? = {
            guard localCandidateType != nil || remoteCandidateType != nil else { return nil }
            return "\(isRelay ? "TURN relay" : "Direct") (\(localCandidateType ?? "n/a") -> \(remoteCandidateType ?? "n/a"), \(localProtocol ?? remoteProtocol ?? "n/a"))"
        }()

        let candidateRttSeconds = memberDouble(selectedPair, key: "currentRoundTripTime")
        let remoteInboundRttSeconds: Double? = remoteInboundRttCount > 0
            ? (remoteInboundRttSumSeconds / Double(remoteInboundRttCount))
            : nil
        let chosenRttSeconds = candidateRttSeconds ?? remoteInboundRttSeconds
        let rttMs = chosenRttSeconds.map { $0 * 1000.0 }
        let availableOutgoingKbps = memberDouble(selectedPair, key: "availableOutgoingBitrate").map { $0 / 1000.0 }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let elapsedSeconds: Double = {
            guard let lastRealtimeStatsSample else { return 0 }
            return max(0, Double(now - lastRealtimeStatsSample.timestampMs) / 1000.0)
        }()

        let audioRxKbps = lastRealtimeStatsSample.flatMap {
            calculateBitrateKbps(previousBytes: $0.audioRxBytes, currentBytes: audio.inboundBytes, elapsedSeconds: elapsedSeconds)
        }
        let audioTxKbps = lastRealtimeStatsSample.flatMap {
            calculateBitrateKbps(previousBytes: $0.audioTxBytes, currentBytes: audio.outboundBytes, elapsedSeconds: elapsedSeconds)
        }
        let videoRxKbps = lastRealtimeStatsSample.flatMap {
            calculateBitrateKbps(previousBytes: $0.videoRxBytes, currentBytes: video.inboundBytes, elapsedSeconds: elapsedSeconds)
        }
        let videoTxKbps = lastRealtimeStatsSample.flatMap {
            calculateBitrateKbps(previousBytes: $0.videoTxBytes, currentBytes: video.outboundBytes, elapsedSeconds: elapsedSeconds)
        }

        let videoFps: Double? = {
            if video.inboundFpsCount > 0 {
                return video.inboundFpsSum / Double(video.inboundFpsCount)
            }
            if let lastRealtimeStatsSample, elapsedSeconds > 0, video.inboundFramesDecoded >= lastRealtimeStatsSample.videoFramesDecoded {
                return Double(video.inboundFramesDecoded - lastRealtimeStatsSample.videoFramesDecoded) / elapsedSeconds
            }
            return nil
        }()

        freezeSamples.append(
            FreezeSample(
                timestampMs: now,
                freezeCount: video.inboundFreezeCount,
                freezeDurationSeconds: video.inboundFreezeDurationSeconds
            )
        )
        freezeSamples.removeAll { now - $0.timestampMs > Constants.freezeWindowMs }
        let freezeWindowBase = freezeSamples.first
        let videoFreezeCount60s = freezeWindowBase.map { max(0, video.inboundFreezeCount - $0.freezeCount) }
        let videoFreezeDuration60s = freezeWindowBase.map { max(0, video.inboundFreezeDurationSeconds - $0.freezeDurationSeconds) }

        let audioRxPacketLossPct = ratioPercent(
            numerator: audio.inboundPacketsLost,
            denominator: audio.inboundPacketsLost + audio.inboundPacketsReceived
        )
        let audioTxPacketLossPct = ratioPercent(
            numerator: audio.remoteInboundPacketsLost,
            denominator: audio.remoteInboundPacketsLost + audio.outboundPacketsSent
        )
        let videoRxPacketLossPct = ratioPercent(
            numerator: video.inboundPacketsLost,
            denominator: video.inboundPacketsLost + video.inboundPacketsReceived
        )
        let videoTxPacketLossPct = ratioPercent(
            numerator: video.remoteInboundPacketsLost,
            denominator: video.remoteInboundPacketsLost + video.outboundPacketsSent
        )

        let audioJitterMs = audio.inboundJitterCount > 0
            ? ((audio.inboundJitterSumSeconds / Double(audio.inboundJitterCount)) * 1000.0)
            : nil
        let audioPlayoutDelayMs = audio.inboundJitterBufferEmittedCount > 0
            ? ((audio.inboundJitterBufferDelaySeconds / Double(audio.inboundJitterBufferEmittedCount)) * 1000.0)
            : nil
        let audioConcealedPct = ratioPercent(
            numerator: audio.inboundConcealedSamples,
            denominator: audio.inboundConcealedSamples + audio.inboundTotalSamples
        )
        let videoRetransmitPct = ratioPercent(
            numerator: video.outboundPacketsRetransmitted,
            denominator: video.outboundPacketsSent
        )

        let videoNackPerMin = lastRealtimeStatsSample.flatMap {
            positiveRatePerMinute(currentValue: video.inboundNackCount, previousValue: $0.videoNackCount, elapsedSeconds: elapsedSeconds)
        }
        let videoPliPerMin = lastRealtimeStatsSample.flatMap {
            positiveRatePerMinute(currentValue: video.inboundPliCount, previousValue: $0.videoPliCount, elapsedSeconds: elapsedSeconds)
        }
        let videoFirPerMin = lastRealtimeStatsSample.flatMap {
            positiveRatePerMinute(currentValue: video.inboundFirCount, previousValue: $0.videoFirCount, elapsedSeconds: elapsedSeconds)
        }

        let videoResolution: String? = (video.inboundFrameWidth > 0 && video.inboundFrameHeight > 0)
            ? "\(video.inboundFrameWidth)x\(video.inboundFrameHeight)"
            : nil

        lastRealtimeStatsSample = RealtimeStatsSample(
            timestampMs: now,
            audioRxBytes: audio.inboundBytes,
            audioTxBytes: audio.outboundBytes,
            videoRxBytes: video.inboundBytes,
            videoTxBytes: video.outboundBytes,
            videoFramesDecoded: video.inboundFramesDecoded,
            videoNackCount: video.inboundNackCount,
            videoPliCount: video.inboundPliCount,
            videoFirCount: video.inboundFirCount
        )

        return RealtimeCallStats(
            transportPath: transportPath,
            rttMs: rttMs,
            availableOutgoingKbps: availableOutgoingKbps,
            audioRxPacketLossPct: audioRxPacketLossPct,
            audioTxPacketLossPct: audioTxPacketLossPct,
            audioJitterMs: audioJitterMs,
            audioPlayoutDelayMs: audioPlayoutDelayMs,
            audioConcealedPct: audioConcealedPct,
            audioRxKbps: audioRxKbps,
            audioTxKbps: audioTxKbps,
            videoRxPacketLossPct: videoRxPacketLossPct,
            videoTxPacketLossPct: videoTxPacketLossPct,
            videoRxKbps: videoRxKbps,
            videoTxKbps: videoTxKbps,
            videoFps: videoFps,
            videoResolution: videoResolution,
            videoFreezeCount60s: videoFreezeCount60s,
            videoFreezeDuration60s: videoFreezeDuration60s,
            videoRetransmitPct: videoRetransmitPct,
            videoNackPerMin: videoNackPerMin,
            videoPliPerMin: videoPliPerMin,
            videoFirPerMin: videoFirPerMin,
            // Nil (unknown) when the specific counter
            // member was never present, never a fake 0. Per-field presence,
            // not just per-kind.
            videoFramesDecoded: video.sawFramesDecoded ? video.inboundFramesDecoded : nil,
            videoFramesDropped: video.sawFramesDropped ? video.inboundFramesDropped : nil,
            audioPacketsLost: audio.sawPacketsLost ? audio.inboundPacketsLost : nil,
            audioPacketsReceived: audio.sawPacketsReceived ? audio.inboundPacketsReceived : nil,
            updatedAtMs: now
        )
    }

    private func collectMediaStat(
        _ stat: RTCStatistics,
        into totals: inout MediaTotals,
        remoteInboundRttSumSeconds: inout Double,
        remoteInboundRttCount: inout Int64
    ) {
        switch stat.type {
        case "inbound-rtp":
            totals.inboundRtpCount += 1
            if let packetsReceived = memberInt64(stat, key: "packetsReceived") {
                totals.inboundPacketsReceived += packetsReceived
                totals.sawPacketsReceived = true
            }
            if let packetsLost = memberInt64(stat, key: "packetsLost") {
                totals.inboundPacketsLost += max(0, packetsLost)
                totals.sawPacketsLost = true
            }
            totals.inboundBytes += memberInt64(stat, key: "bytesReceived") ?? 0

            if let jitter = memberDouble(stat, key: "jitter") {
                totals.inboundJitterSumSeconds += jitter
                totals.inboundJitterCount += 1
            }

            totals.inboundJitterBufferDelaySeconds += memberDouble(stat, key: "jitterBufferDelay") ?? 0
            totals.inboundJitterBufferEmittedCount += memberInt64(stat, key: "jitterBufferEmittedCount") ?? 0
            totals.inboundConcealedSamples += memberInt64(stat, key: "concealedSamples") ?? 0
            totals.inboundTotalSamples += memberInt64(stat, key: "totalSamplesReceived") ?? 0

            if let fps = memberDouble(stat, key: "framesPerSecond") {
                totals.inboundFpsSum += fps
                totals.inboundFpsCount += 1
            }

            totals.inboundFrameWidth = max(totals.inboundFrameWidth, Int(memberInt64(stat, key: "frameWidth") ?? 0))
            totals.inboundFrameHeight = max(totals.inboundFrameHeight, Int(memberInt64(stat, key: "frameHeight") ?? 0))
            if let framesDecoded = memberInt64(stat, key: "framesDecoded") {
                totals.inboundFramesDecoded += framesDecoded
                totals.sawFramesDecoded = true
            }
            if let framesDropped = memberInt64(stat, key: "framesDropped") {
                totals.inboundFramesDropped += framesDropped
                totals.sawFramesDropped = true
            }

            totals.inboundFreezeCount += memberInt64(stat, key: "freezeCount") ?? 0
            totals.inboundFreezeDurationSeconds += memberDouble(stat, key: "totalFreezesDuration") ?? 0
            totals.inboundNackCount += memberInt64(stat, key: "nackCount") ?? 0
            totals.inboundPliCount += memberInt64(stat, key: "pliCount") ?? 0
            totals.inboundFirCount += memberInt64(stat, key: "firCount") ?? 0

        case "outbound-rtp":
            totals.outboundPacketsSent += memberInt64(stat, key: "packetsSent") ?? 0
            totals.outboundBytes += memberInt64(stat, key: "bytesSent") ?? 0
            totals.outboundPacketsRetransmitted += memberInt64(stat, key: "retransmittedPacketsSent") ?? 0

        case "remote-inbound-rtp":
            totals.remoteInboundPacketsLost += max(0, memberInt64(stat, key: "packetsLost") ?? 0)
            if let remoteRtt = memberDouble(stat, key: "roundTripTime") {
                remoteInboundRttSumSeconds += remoteRtt
                remoteInboundRttCount += 1
            }

        default:
            break
        }
    }

}

private final class SlotPeerConnectionObserverProxy: NSObject, RTCPeerConnectionDelegate {
    private let onIceCandidate: (RTCIceCandidate) -> Void
    private let onConnectionState: (RTCPeerConnectionState) -> Void
    private let onIceConnectionState: (RTCIceConnectionState) -> Void
    private let onSignalingState: (RTCSignalingState) -> Void
    private let onRenegotiationNeeded: () -> Void
    private let onRemoteVideoTrack: (RTCVideoTrack?) -> Void
    private let onRemoteAudioTrack: (RTCAudioTrack) -> Void

    init(
        onIceCandidate: @escaping (RTCIceCandidate) -> Void,
        onConnectionState: @escaping (RTCPeerConnectionState) -> Void,
        onIceConnectionState: @escaping (RTCIceConnectionState) -> Void,
        onSignalingState: @escaping (RTCSignalingState) -> Void,
        onRenegotiationNeeded: @escaping () -> Void,
        onRemoteVideoTrack: @escaping (RTCVideoTrack?) -> Void,
        onRemoteAudioTrack: @escaping (RTCAudioTrack) -> Void
    ) {
        self.onIceCandidate = onIceCandidate
        self.onConnectionState = onConnectionState
        self.onIceConnectionState = onIceConnectionState
        self.onSignalingState = onSignalingState
        self.onRenegotiationNeeded = onRenegotiationNeeded
        self.onRemoteVideoTrack = onRemoteVideoTrack
        self.onRemoteAudioTrack = onRemoteAudioTrack
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        onSignalingState(stateChanged)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if let track = stream.audioTracks.first {
            onRemoteAudioTrack(track)
        }
        onRemoteVideoTrack(stream.videoTracks.first)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        onRemoteVideoTrack(nil)
    }

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        onRenegotiationNeeded()
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        onIceConnectionState(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        onConnectionState(newState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onIceCandidate(candidate)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let track = rtpReceiver.track as? RTCAudioTrack {
            onRemoteAudioTrack(track)
        } else if let track = rtpReceiver.track as? RTCVideoTrack {
            onRemoteVideoTrack(track)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChangeLocalCandidate local: RTCIceCandidate, remoteCandidate remote: RTCIceCandidate, lastReceivedMs: Int32, changeReason reason: String) {}
}

private final class WeakRendererBox {
    weak var value: AnyObject?

    init(value: AnyObject) {
        self.value = value
    }
}
#endif
