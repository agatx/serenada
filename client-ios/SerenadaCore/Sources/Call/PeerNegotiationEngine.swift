import Foundation

@MainActor
final class PeerNegotiationEngine {
    // Clock
    private let clock: SessionClock

    // State readers
    private let getClientId: () -> String?
    private let getHostCid: () -> String?
    private let getInternalPhase: () -> CallPhase
    private let getParticipantCount: () -> Int
    private let getCurrentRoomState: () -> RoomState?
    private let isSignalingConnected: () -> Bool
    private let hasIceServers: () -> Bool

    // Slot access (session owns peerSlots)
    private let getSlot: (String) -> (any PeerConnectionSlotProtocol)?
    private let getAllSlots: () -> [String: any PeerConnectionSlotProtocol]
    private let setSlot: (String, any PeerConnectionSlotProtocol) -> Void
    private let removeSlotEntry: (String) -> (any PeerConnectionSlotProtocol)?

    // WebRTC engine
    private let createSlotViaEngine: (
        _ remoteCid: String,
        _ onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
        _ onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
        _ onConnectionStateChange: @escaping (String, String) -> Void,
        _ onIceConnectionStateChange: @escaping (String, String) -> Void,
        _ onSignalingStateChange: @escaping (String, String) -> Void,
        _ onRenegotiationNeeded: @escaping (String) -> Void
    ) -> (any PeerConnectionSlotProtocol)?
    private let engineRemoveSlot: (any PeerConnectionSlotProtocol) -> Void

    // Callbacks to session
    private let sendMessage: (String, JSONValue?, String?) -> Void
    private let onRemoteParticipantsChanged: () -> Void
    private let onAggregatePeerStateChanged: (IceConnectionState, PeerConnectionState, SignalingState) -> Void
    private let onConnectionStatusUpdate: () -> Void

    init(
        clock: SessionClock,
        getClientId: @escaping () -> String?,
        getHostCid: @escaping () -> String?,
        getInternalPhase: @escaping () -> CallPhase,
        getParticipantCount: @escaping () -> Int,
        getCurrentRoomState: @escaping () -> RoomState?,
        isSignalingConnected: @escaping () -> Bool,
        hasIceServers: @escaping () -> Bool,
        getSlot: @escaping (String) -> (any PeerConnectionSlotProtocol)?,
        getAllSlots: @escaping () -> [String: any PeerConnectionSlotProtocol],
        setSlot: @escaping (String, any PeerConnectionSlotProtocol) -> Void,
        removeSlotEntry: @escaping (String) -> (any PeerConnectionSlotProtocol)?,
        createSlotViaEngine: @escaping (
            _ remoteCid: String,
            _ onLocalIceCandidate: @escaping (String, IceCandidatePayload) -> Void,
            _ onRemoteVideoTrack: @escaping (String, AnyObject?) -> Void,
            _ onConnectionStateChange: @escaping (String, String) -> Void,
            _ onIceConnectionStateChange: @escaping (String, String) -> Void,
            _ onSignalingStateChange: @escaping (String, String) -> Void,
            _ onRenegotiationNeeded: @escaping (String) -> Void
        ) -> (any PeerConnectionSlotProtocol)?,
        engineRemoveSlot: @escaping (any PeerConnectionSlotProtocol) -> Void,
        sendMessage: @escaping (String, JSONValue?, String?) -> Void,
        onRemoteParticipantsChanged: @escaping () -> Void,
        onAggregatePeerStateChanged: @escaping (IceConnectionState, PeerConnectionState, SignalingState) -> Void,
        onConnectionStatusUpdate: @escaping () -> Void
    ) {
        self.clock = clock
        self.getClientId = getClientId
        self.getHostCid = getHostCid
        self.getInternalPhase = getInternalPhase
        self.getParticipantCount = getParticipantCount
        self.getCurrentRoomState = getCurrentRoomState
        self.isSignalingConnected = isSignalingConnected
        self.hasIceServers = hasIceServers
        self.getSlot = getSlot
        self.getAllSlots = getAllSlots
        self.setSlot = setSlot
        self.removeSlotEntry = removeSlotEntry
        self.createSlotViaEngine = createSlotViaEngine
        self.engineRemoveSlot = engineRemoveSlot
        self.sendMessage = sendMessage
        self.onRemoteParticipantsChanged = onRemoteParticipantsChanged
        self.onAggregatePeerStateChanged = onAggregatePeerStateChanged
        self.onConnectionStatusUpdate = onConnectionStatusUpdate
    }

    // MARK: - Public API

    func syncPeers(roomState: RoomState) {
        let remoteCids = Set(roomState.participants.filter { $0.cid != getClientId() }.map(\.cid))
        let remoteParticipants = roomState.participants.filter { $0.cid != getClientId() }

        let departing = Set(getAllSlots().keys).subtracting(remoteCids)
        for remoteCid in departing {
            removePeerSlot(remoteCid: remoteCid)
        }

        if remoteParticipants.isEmpty {
            clearOfferTimeout()
            clearIceRestartTimer()
            clearNonHostOfferFallback()
        }

        if remoteParticipants.count >= 1 {
            for participant in remoteParticipants {
                let slot = getOrCreateSlot(remoteCid: participant.cid)
                _ = slot.ensurePeerConnection()
                if shouldIOffer(remoteCid: participant.cid, roomState: roomState) {
                    clearNonHostOfferFallback(remoteCid: participant.cid)
                    maybeSendOffer(slot: slot)
                } else {
                    maybeScheduleNonHostOfferFallback(remoteCid: participant.cid, reason: "participants")
                }
            }
        }

        updateAggregatePeerState()
    }

    func processSignalingPayload(_ message: SignalingMessage) {
        guard let fromCid = message.payload?.objectValue?["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !fromCid.isEmpty else {
            return
        }

        let slot = getOrCreateSlot(remoteCid: fromCid)
        if !slot.isReady(), !slot.ensurePeerConnection() {
            return
        }

        switch message.type {
        case "offer":
            clearNonHostOfferFallback(remoteCid: fromCid)
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            slot.setRemoteDescription(type: .offer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                guard success else {
                    self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "offer-apply-failed")
                    return
                }
                self.clearNonHostOfferFallback(remoteCid: fromCid)
                slot.createAnswer(onSdp: { [weak self] answerSdp in
                    self?.sendMessage(
                        "answer",
                        .object(["sdp": .string(answerSdp)]),
                        fromCid
                    )
                }, onComplete: { [weak self] answerSuccess in
                    Task { @MainActor in
                        guard let self else { return }
                        if !answerSuccess {
                            self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "answer-create-failed")
                        }
                    }
                })
            }

        case "answer":
            clearNonHostOfferFallback(remoteCid: fromCid)
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            slot.setRemoteDescription(type: .answer, sdp: sdp) { [weak self] success in
                guard let self else { return }
                if success {
                    self.clearOfferTimeout(remoteCid: fromCid)
                    self.getSlot(fromCid)?.clearPendingIceRestart()
                    self.updateAggregatePeerState()
                    self.onConnectionStatusUpdate()
                } else if self.shouldIOffer(remoteCid: fromCid) {
                    self.scheduleIceRestart(remoteCid: fromCid, reason: "answer-apply-failed", delayMs: 0)
                } else {
                    self.maybeScheduleNonHostOfferFallback(remoteCid: fromCid, reason: "answer-apply-failed")
                }
            }

        case "ice":
            guard let candidateObject = message.payload?.objectValue?["candidate"]?.objectValue,
                  let candidate = candidateObject["candidate"]?.stringValue else {
                return
            }
            slot.addIceCandidate(
                IceCandidatePayload(
                    sdpMid: candidateObject["sdpMid"]?.stringValue,
                    sdpMLineIndex: Int32(candidateObject["sdpMLineIndex"]?.intValue ?? 0),
                    candidate: candidate
                )
            )

        default:
            break
        }
    }

    func onIceServersReady() {
        maybeSendOffer()
        maybeScheduleNonHostOfferFallback(reason: "ice-ready")
    }

    func scheduleIceRestart(reason: String, delayMs: Int) {
        for slot in getAllSlots().values where shouldIOffer(remoteCid: slot.remoteCid) {
            scheduleIceRestart(remoteCid: slot.remoteCid, reason: reason, delayMs: delayMs)
        }
    }

    func triggerIceRestart(reason: String) {
        for slot in getAllSlots().values where shouldIOffer(remoteCid: slot.remoteCid) {
            triggerIceRestart(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    func resetAll() {
        clearOfferTimeout()
        clearIceRestartTimer()
        clearNonHostOfferFallback()
    }

    // MARK: - Slot Lifecycle

    private func getOrCreateSlot(remoteCid: String) -> any PeerConnectionSlotProtocol {
        if let slot = getSlot(remoteCid) {
            return slot
        }

        guard let slot = createSlotViaEngine(
            remoteCid,
            { [weak self] cid, candidate in
                Task { @MainActor in
                    guard let self else { return }
                    self.sendMessage(
                        "ice",
                        .object([
                            "candidate": .object([
                                "candidate": .string(candidate.candidate),
                                "sdpMid": candidate.sdpMid.map(JSONValue.string) ?? .null,
                                "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex))
                            ])
                        ]),
                        cid
                    )
                }
            },
            { [weak self] _, _ in
                Task { @MainActor in
                    self?.onRemoteParticipantsChanged()
                }
            },
            { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case "CONNECTED":
                        self.clearIceRestartTimer(remoteCid: cid)
                        self.getSlot(cid)?.clearPendingIceRestart()
                    case "DISCONNECTED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "conn-disconnected", delayMs: 2000)
                    case "FAILED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "conn-failed", delayMs: 0)
                    default:
                        break
                    }
                    self.onRemoteParticipantsChanged()
                    self.updateAggregatePeerState()
                    self.onConnectionStatusUpdate()
                }
            },
            { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case "CONNECTED", "COMPLETED":
                        self.clearIceRestartTimer(remoteCid: cid)
                        self.getSlot(cid)?.clearPendingIceRestart()
                    case "DISCONNECTED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "ice-disconnected", delayMs: 2000)
                    case "FAILED":
                        self.scheduleIceRestart(remoteCid: cid, reason: "ice-failed", delayMs: 0)
                    default:
                        break
                    }
                    self.onRemoteParticipantsChanged()
                    self.updateAggregatePeerState()
                    self.onConnectionStatusUpdate()
                }
            },
            { [weak self] cid, state in
                Task { @MainActor in
                    guard let self else { return }
                    if state == "STABLE" {
                        self.clearOfferTimeout(remoteCid: cid)
                        if self.getSlot(cid)?.pendingIceRestart == true {
                            self.getSlot(cid)?.clearPendingIceRestart()
                            self.triggerIceRestart(remoteCid: cid, reason: "pending-retry")
                        }
                    }
                    self.updateAggregatePeerState()
                    self.onConnectionStatusUpdate()
                }
            },
            { [weak self] cid in
                Task { @MainActor in
                    guard let self, let slot = self.getSlot(cid) else { return }
                    self.maybeSendOffer(slot: slot, force: true, iceRestart: false)
                }
            }
        ) else {
            preconditionFailure("WebRTC peer slot factory is unavailable")
        }

        setSlot(remoteCid, slot)
        return slot
    }

    private func removePeerSlot(remoteCid: String) {
        guard let slot = removeSlotEntry(remoteCid) else { return }
        clearOfferTimeout(remoteCid: remoteCid)
        clearIceRestartTimer(remoteCid: remoteCid)
        clearNonHostOfferFallback(remoteCid: remoteCid)
        engineRemoveSlot(slot)
        slot.closePeerConnection()
    }

    // MARK: - Offer Logic

    private func shouldIOffer(remoteCid: String, roomState: RoomState? = nil) -> Bool {
        let roomState = roomState ?? getCurrentRoomState()
        guard let roomState, let myCid = getClientId() else { return false }
        let myJoinedAt = roomState.participants.first(where: { $0.cid == myCid })?.joinedAt ?? 0
        let theirJoinedAt = roomState.participants.first(where: { $0.cid == remoteCid })?.joinedAt ?? 0
        return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
    }

    private func canOffer(slot: any PeerConnectionSlotProtocol) -> Bool {
        guard getParticipantCount() > 1 else { return false }
        guard isSignalingConnected() else { return false }
        guard shouldIOffer(remoteCid: slot.remoteCid) else { return false }
        return slot.isReady() || slot.ensurePeerConnection()
    }

    private func maybeSendOffer(force: Bool = false, iceRestart: Bool = false) {
        for slot in getAllSlots().values where shouldIOffer(remoteCid: slot.remoteCid) {
            maybeSendOffer(slot: slot, force: force, iceRestart: iceRestart)
        }
    }

    private func maybeSendOffer(slot: any PeerConnectionSlotProtocol, force: Bool = false, iceRestart: Bool = false) {
        if slot.isMakingOffer {
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        if !force && slot.sentOffer {
            return
        }

        if !canOffer(slot: slot) {
            return
        }

        if slot.getSignalingState() != "STABLE" {
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        slot.beginOffer()
        let started = slot.createOffer(
            iceRestart: iceRestart,
            onSdp: { [weak self] sdp in
                self?.sendMessage(
                    "offer",
                    .object(["sdp": .string(sdp)]),
                    slot.remoteCid
                )
                self?.scheduleOfferTimeout(remoteCid: slot.remoteCid)
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    slot.completeOffer()
                    if !success {
                        if iceRestart {
                            self.scheduleIceRestart(remoteCid: slot.remoteCid, reason: "offer-failed", delayMs: 500)
                        } else if self.shouldIOffer(remoteCid: slot.remoteCid) {
                            self.maybeSendOffer(slot: slot)
                        }
                    }
                }
            }
        )

        if !started {
            slot.completeOffer()
            if iceRestart {
                slot.markPendingIceRestart()
            }
            return
        }

        if !force {
            slot.markOfferSent()
        }
    }

    // MARK: - Offer Timeout

    private func scheduleOfferTimeout(
        remoteCid: String,
        triggerIceRestart: Bool = true,
        onTimedOut: (() -> Void)? = nil
    ) {
        clearOfferTimeout(remoteCid: remoteCid)
        guard let slot = getSlot(remoteCid) else { return }

        slot.setOfferTimeoutTask(Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.offerTimeoutNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.getSlot(remoteCid) else { return }
                guard slot.getSignalingState() == "HAVE_LOCAL_OFFER" else { return }
                if triggerIceRestart {
                    slot.markPendingIceRestart()
                }
                slot.rollbackLocalDescription { _ in
                    Task { @MainActor in
                        if triggerIceRestart {
                            if self.shouldIOffer(remoteCid: remoteCid) {
                                self.scheduleIceRestart(remoteCid: remoteCid, reason: "offer-timeout", delayMs: 0)
                            } else {
                                self.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-timeout")
                            }
                        } else {
                            onTimedOut?()
                        }
                    }
                }
            }
        })
    }

    private func clearOfferTimeout(remoteCid: String? = nil) {
        if let remoteCid {
            getSlot(remoteCid)?.cancelOfferTimeout()
            return
        }

        for slot in getAllSlots().values {
            slot.cancelOfferTimeout()
        }
    }

    // MARK: - ICE Restart

    func scheduleIceRestart(remoteCid: String, reason: String, delayMs: Int) {
        guard let slot = getSlot(remoteCid) else { return }
        if !canOffer(slot: slot) {
            slot.markPendingIceRestart()
            return
        }

        guard slot.iceRestartTask == nil else { return }

        let now = Double(clock.nowMs())
        guard slot.lastIceRestartAt <= 0 || now - slot.lastIceRestartAt >= Double(WebRtcResilience.iceRestartCooldownMs) else { return }

        slot.setIceRestartTask(Task { [weak self] in
            guard let clock = self?.clock else { return }
            if delayMs > 0 {
                try? await clock.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.triggerIceRestart(remoteCid: remoteCid, reason: reason)
            }
        })
    }

    private func clearIceRestartTimer(remoteCid: String? = nil) {
        if let remoteCid {
            getSlot(remoteCid)?.cancelIceRestartTask()
            return
        }

        for slot in getAllSlots().values {
            slot.cancelIceRestartTask()
        }
    }

    private func triggerIceRestart(remoteCid: String, reason: String) {
        guard let slot = getSlot(remoteCid) else { return }
        slot.cancelIceRestartTask()

        guard canOffer(slot: slot) else {
            slot.markPendingIceRestart()
            return
        }

        if slot.isMakingOffer {
            slot.markPendingIceRestart()
            return
        }

        slot.recordIceRestart(nowMs: clock.nowMs())
        maybeSendOffer(slot: slot, force: true, iceRestart: true)
    }

    // MARK: - Non-Host Offer Fallback

    private func maybeScheduleNonHostOfferFallback(reason: String) {
        for slot in getAllSlots().values where !shouldIOffer(remoteCid: slot.remoteCid) {
            maybeScheduleNonHostOfferFallback(remoteCid: slot.remoteCid, reason: reason)
        }
    }

    private func maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        guard let slot = getSlot(remoteCid) else { return }
        guard getParticipantCount() > 1 else {
            clearNonHostOfferFallback(remoteCid: remoteCid)
            return
        }
        guard !shouldIOffer(remoteCid: remoteCid) else {
            clearNonHostOfferFallback(remoteCid: remoteCid)
            return
        }
        guard isSignalingConnected() else { return }
        guard slot.nonHostFallbackTask == nil else { return }
        guard slot.nonHostFallbackAttempts < WebRtcResilience.nonHostFallbackMaxAttempts else { return }

        slot.setNonHostFallbackTask(Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.nonHostFallbackDelayNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.getSlot(remoteCid) else { return }
                slot.clearNonHostFallbackTask()
                slot.incrementNonHostFallbackAttempts()
                self.maybeSendNonHostFallbackOffer(remoteCid: remoteCid)
            }
        })
    }

    private func clearNonHostOfferFallback(remoteCid: String? = nil) {
        if let remoteCid {
            getSlot(remoteCid)?.cancelNonHostFallbackTask()
            return
        }

        for slot in getAllSlots().values {
            slot.cancelNonHostFallbackTask()
        }
    }

    private func maybeSendNonHostFallbackOffer(remoteCid: String) {
        guard let slot = getSlot(remoteCid) else { return }
        guard getParticipantCount() > 1 else { return }
        guard !shouldIOffer(remoteCid: remoteCid) else { return }
        guard isSignalingConnected() else { return }
        guard slot.isReady() || slot.ensurePeerConnection() else { return }
        guard slot.getSignalingState() == "STABLE" else {
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "signaling-not-stable")
            return
        }
        guard !slot.hasRemoteDescription() else { return }
        guard !slot.isMakingOffer else {
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "already-making-offer")
            return
        }

        slot.beginOffer()
        let started = slot.createOffer(
            iceRestart: false,
            onSdp: { [weak self] sdp in
                self?.sendMessage(
                    "offer",
                    .object(["sdp": .string(sdp)]),
                    remoteCid
                )
                self?.scheduleOfferTimeout(
                    remoteCid: remoteCid,
                    triggerIceRestart: false,
                    onTimedOut: { [weak self] in
                        self?.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-timeout")
                    }
                )
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    slot.completeOffer()
                    if !success {
                        self.maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-failed")
                    }
                }
            }
        )

        if !started {
            slot.completeOffer()
            maybeScheduleNonHostOfferFallback(remoteCid: remoteCid, reason: "offer-not-started")
        }
    }

    // MARK: - Aggregate Peer State

    private static let icePriority: [String: Int] = [
        "FAILED": 0, "DISCONNECTED": 1, "CHECKING": 2, "NEW": 3, "CONNECTED": 4, "COMPLETED": 5, "CLOSED": 6, "COUNT": 7, "UNKNOWN": 8,
    ]
    private static let connectionPriority: [SerenadaPeerConnectionState: Int] = [
        .failed: 0, .disconnected: 1, .connecting: 2, .new: 3, .connected: 4, .closed: 5,
    ]
    private static let signalingPriority: [String: Int] = [
        "HAVE_LOCAL_OFFER": 0, "HAVE_REMOTE_OFFER": 1, "HAVE_LOCAL_PRANSWER": 2, "HAVE_REMOTE_PRANSWER": 3, "STABLE": 4, "CLOSED": 5, "UNKNOWN": 6,
    ]

    private func updateAggregatePeerState() {
        var bestIcePri = Int.max
        var nextIceState = "NEW"
        var bestConnPri = Int.max
        var nextConnectionState: SerenadaPeerConnectionState = .new
        var bestSigPri = Int.max
        var nextSignalingState = "STABLE"

        for slot in getAllSlots().values {
            let icePri = Self.icePriority[slot.getIceConnectionState()] ?? .max
            if icePri < bestIcePri {
                bestIcePri = icePri
                nextIceState = slot.getIceConnectionState()
            }

            let connPri = Self.connectionPriority[slot.getConnectionState()] ?? .max
            if connPri < bestConnPri {
                bestConnPri = connPri
                nextConnectionState = slot.getConnectionState()
            }

            let sigPri = Self.signalingPriority[slot.getSignalingState()] ?? .max
            if sigPri < bestSigPri {
                bestSigPri = sigPri
                nextSignalingState = slot.getSignalingState()
            }
        }

        onAggregatePeerStateChanged(
            IceConnectionState(rawValueOrUnknown: nextIceState),
            PeerConnectionState(rawValueOrUnknown: nextConnectionState.rawValue),
            SignalingState(rawValueOrUnknown: nextSignalingState)
        )
    }
}
