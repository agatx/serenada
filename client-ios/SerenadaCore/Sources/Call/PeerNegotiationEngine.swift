import Foundation

@MainActor
final class PeerNegotiationEngine {
    private static let legacyOfferId = "__legacy__"

    // Clock
    private let clock: SessionClock

    // State readers
    private let getClientId: () -> String?
    private let deferInitialAnswer: () -> Bool
    private let getInternalPhase: () -> CallPhase
    private let getParticipantCount: () -> Int
    private let getCurrentRoomState: () -> RoomState?
    private let isSignalingConnected: () -> Bool
    private let hasIceServers: () -> Bool
    private let isLocalMediaReady: () -> Bool

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
        _ onRenegotiationNeeded: @escaping (String) -> Void,
        _ supportsIndependentContentVideo: Bool,
        _ isOfferOwner: @escaping () -> Bool
    ) -> (any PeerConnectionSlotProtocol)?
    private let engineRemoveSlot: (any PeerConnectionSlotProtocol) -> Void
    /// Resolve the per-peer independent-content capability (local flag AND both
    /// ends' videoMediaEnabled AND peer's independentContentVideo). Defaults to
    /// `false` so flag-off / un-wired callers use the legacy single-video path.
    private let peerIndependentContentSupported: (String) -> Bool

    // Callbacks to session
    private let sendMessage: (String, JSONValue?, String?) -> Void
    private let onRemoteParticipantsChanged: () -> Void
    private let onAggregatePeerStateChanged: (IceConnectionState, PeerConnectionState, RtcSignalingState) -> Void
    private let onConnectionStatusUpdate: () -> Void
    private let logger: SerenadaLogger?

    private var offerSequence: Int64 = 0
    private var pendingLocalOfferIds: [String: String] = [:]
    private var acceptedRemoteOfferIds: [String: String] = [:]
    private var currentNegotiationIds: [String: String] = [:]
    private var ignoredOfferIds: [String: String] = [:]
    private var settingRemoteAnswerCids: Set<String> = []
    private var initialAnswerReceivedCids: Set<String> = []
    private var pendingRemoteIceByOfferId: [String: [String: [IceCandidatePayload]]] = [:]
    private var participantStatuses: [String: ParticipantSignalingStatus] = [:]
    private var outboundMediaWatchByCid: [String: OutboundMediaWatch] = [:]
    private var lastMediaRestartHandledAtByCid: [String: Int64] = [:]

    private struct OutboundMediaWatch {
        var lastSample: OutboundMediaSample?
        var stallSamples = 0
        var inFlight = false
        var lastRecoveryAtMs: Int64?
    }

    init(
        clock: SessionClock,
        getClientId: @escaping () -> String?,
        deferInitialAnswer: @escaping () -> Bool = { false },
        getInternalPhase: @escaping () -> CallPhase,
        getParticipantCount: @escaping () -> Int,
        getCurrentRoomState: @escaping () -> RoomState?,
        isSignalingConnected: @escaping () -> Bool,
        hasIceServers: @escaping () -> Bool,
        isLocalMediaReady: @escaping () -> Bool = { true },
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
            _ onRenegotiationNeeded: @escaping (String) -> Void,
            _ supportsIndependentContentVideo: Bool,
            _ isOfferOwner: @escaping () -> Bool
        ) -> (any PeerConnectionSlotProtocol)?,
        engineRemoveSlot: @escaping (any PeerConnectionSlotProtocol) -> Void,
        peerIndependentContentSupported: @escaping (String) -> Bool = { _ in false },
        sendMessage: @escaping (String, JSONValue?, String?) -> Void,
        onRemoteParticipantsChanged: @escaping () -> Void,
        onAggregatePeerStateChanged: @escaping (IceConnectionState, PeerConnectionState, RtcSignalingState) -> Void,
        onConnectionStatusUpdate: @escaping () -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.clock = clock
        self.getClientId = getClientId
        self.deferInitialAnswer = deferInitialAnswer
        self.getInternalPhase = getInternalPhase
        self.getParticipantCount = getParticipantCount
        self.getCurrentRoomState = getCurrentRoomState
        self.isSignalingConnected = isSignalingConnected
        self.hasIceServers = hasIceServers
        self.isLocalMediaReady = isLocalMediaReady
        self.getSlot = getSlot
        self.getAllSlots = getAllSlots
        self.setSlot = setSlot
        self.removeSlotEntry = removeSlotEntry
        self.createSlotViaEngine = createSlotViaEngine
        self.engineRemoveSlot = engineRemoveSlot
        self.peerIndependentContentSupported = peerIndependentContentSupported
        self.sendMessage = sendMessage
        self.onRemoteParticipantsChanged = onRemoteParticipantsChanged
        self.onAggregatePeerStateChanged = onAggregatePeerStateChanged
        self.onConnectionStatusUpdate = onConnectionStatusUpdate
        self.logger = logger
    }

    // MARK: - Public API

    func syncPeers(roomState: RoomState) {
        let remoteCids = Set(roomState.participants.filter { $0.cid != getClientId() }.map(\.cid))
        let remoteParticipants = roomState.participants.filter { $0.cid != getClientId() }

        let departing = Set(getAllSlots().keys).subtracting(remoteCids)
        for remoteCid in departing {
            removePeerSlot(remoteCid: remoteCid)
        }
        for remoteCid in Set(participantStatuses.keys).subtracting(remoteCids) {
            participantStatuses.removeValue(forKey: remoteCid)
        }

        if remoteParticipants.isEmpty {
            clearOfferTimeout()
            clearIceRestartTimer()
            participantStatuses.removeAll()
            initialAnswerReceivedCids.removeAll()
        }

        if remoteParticipants.count >= 1 {
            for participant in remoteParticipants {
                let previousStatus = participantStatuses[participant.cid]
                participantStatuses[participant.cid] = participant.signalingStatus
                let becameActive = previousStatus == .suspended && participant.signalingStatus == .active
                let existedBefore = getSlot(participant.cid) != nil
                let slot = getOrCreateSlot(remoteCid: participant.cid)
                _ = slot.ensurePeerConnection()
                // Capability-transition slot handling (FIX 1, independent-content
                // mode). A slot snapshots its independent-content capability at
                // CREATION; a peer first announced before its caps arrive (an early
                // offer / peer_joined with no capabilities) is built LEGACY, and a
                // later cap-bearing room_state only updates the stored room caps —
                // the existing connection stays immutably legacy, so a
                // late-announced CAPABLE peer would never bind the content
                // transceiver. Only reconcile a slot that EXISTED before this loop
                // (a freshly created slot already snapshotted the current caps).
                if existedBefore, reconcilePeerCapability(remoteCid: participant.cid) {
                    // The slot was recreated with the correct camera/content m-line
                    // layout; the recreate path already re-offers from the
                    // deterministic offer owner, so skip the generic offer block for
                    // this peer this pass.
                    continue
                }
                if shouldIOffer(remoteCid: participant.cid, roomState: roomState) {
                    if becameActive {
                        scheduleIceRestart(remoteCid: participant.cid, reason: "peer-reattached", delayMs: 0)
                    } else {
                        maybeSendOffer(slot: slot)
                    }
                }
            }
        }

        updateAggregatePeerState()
    }

    func onLocalMediaReady() {
        maybeSendOffer()
    }

    func processSignalingPayload(_ message: SignalingMessage) {
        guard let fromCid = message.payload?.objectValue?["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
              !fromCid.isEmpty else {
            return
        }
        if fromCid == getClientId() {
            return
        }
        if let roomState = getCurrentRoomState(), !roomState.participants.contains(where: { $0.cid == fromCid }) {
            return
        }

        let slot = getOrCreateSlot(remoteCid: fromCid)
        if !slot.isReady(), !slot.ensurePeerConnection() {
            return
        }

        switch message.type {
        case "offer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            handleRemoteOffer(slot: slot, remoteCid: fromCid, sdp: sdp, offerId: offerId(from: message.payload))

        case "answer":
            guard let sdp = message.payload?.objectValue?["sdp"]?.stringValue, !sdp.isEmpty else { return }
            handleRemoteAnswer(slot: slot, remoteCid: fromCid, sdp: sdp, offerId: offerId(from: message.payload))

        case "ice":
            guard let candidateObject = message.payload?.objectValue?["candidate"]?.objectValue,
                  let candidate = candidateObject["candidate"]?.stringValue,
                  !candidate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return
            }
            let sdpMLineIndex = Int32(candidateObject["sdpMLineIndex"]?.intValue ?? 0)
            let trimmedMid = candidateObject["sdpMid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
            let sdpMid = trimmedMid.flatMap { $0.isEmpty ? nil : $0 }
            handleRemoteIce(
                slot: slot,
                remoteCid: fromCid,
                candidate: IceCandidatePayload(
                    sdpMid: sdpMid,
                    sdpMLineIndex: sdpMLineIndex,
                    candidate: candidate
                ),
                offerId: offerId(from: message.payload)
            )

        case "media_restart_request":
            let reason = message.payload?.objectValue?["reason"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            handleMediaRestartRequest(slot: slot, remoteCid: fromCid, reason: reason)

        default:
            break
        }
    }

    func onIceServersReady() {
        maybeSendOffer()
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

    func handleSignalingReconnect() {
        for slot in getAllSlots().values {
            if shouldIOffer(remoteCid: slot.remoteCid) {
                triggerIceRestart(remoteCid: slot.remoteCid, reason: "signaling-reconnect")
            }
        }
    }

    func scheduleDirtyPairRestart(remoteCid: String) {
        guard getSlot(remoteCid) != nil else { return }
        if shouldIOffer(remoteCid: remoteCid) {
            scheduleIceRestart(remoteCid: remoteCid, reason: "negotiation-dirty", delayMs: 0)
        }
    }

    func resetAll() {
        clearOfferTimeout()
        clearIceRestartTimer()
        clearNegotiationState()
        participantStatuses.removeAll()
        outboundMediaWatchByCid.removeAll()
        lastMediaRestartHandledAtByCid.removeAll()
        initialAnswerReceivedCids.removeAll()
    }

    func recoverStalledOutboundMedia() {
        let slots = getAllSlots()
        for remoteCid in Array(outboundMediaWatchByCid.keys) where slots[remoteCid] == nil {
            outboundMediaWatchByCid.removeValue(forKey: remoteCid)
        }
        for remoteCid in Array(lastMediaRestartHandledAtByCid.keys) where slots[remoteCid] == nil {
            lastMediaRestartHandledAtByCid.removeValue(forKey: remoteCid)
        }
        guard isSignalingConnected() else { return }
        for (remoteCid, slot) in slots {
            recoverStalledOutboundMedia(remoteCid: remoteCid, slot: slot)
        }
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
                    var payload: [String: JSONValue] = [
                        "candidate": .object([
                            "candidate": .string(candidate.candidate),
                            "sdpMid": candidate.sdpMid.map(JSONValue.string) ?? .null,
                            "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex))
                        ])
                    ]
                    if let offerId = self.currentLocalOfferId(remoteCid: cid) {
                        payload["offerId"] = .string(offerId)
                    }
                    self.sendMessage(
                        "ice",
                        .object(payload),
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
                    self?.handleRenegotiationNeeded(remoteCid: cid)
                }
            },
            peerIndependentContentSupported(remoteCid),
            // Lazily evaluated so the slot's owner pre-creation / answerer-bind
            // choice tracks ownership the same way the negotiation path does.
            { [weak self] in self?.shouldIOffer(remoteCid: remoteCid) ?? false }
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
        clearNegotiationState(remoteCid: remoteCid)
        initialAnswerReceivedCids.remove(remoteCid)
        participantStatuses.removeValue(forKey: remoteCid)
        outboundMediaWatchByCid.removeValue(forKey: remoteCid)
        lastMediaRestartHandledAtByCid.removeValue(forKey: remoteCid)
        engineRemoveSlot(slot)
        slot.closePeerConnection()
    }

    private func replacePeerSlotForRemoteOffer(
        remoteCid: String,
        offerId: String
    ) -> (any PeerConnectionSlotProtocol)? {
        let pendingForOffer = pendingRemoteIceByOfferId[remoteCid]?[offerId]
        clearOfferTimeout(remoteCid: remoteCid)
        clearIceRestartTimer(remoteCid: remoteCid)
        clearNegotiationState(remoteCid: remoteCid)
        if let pendingForOffer, !pendingForOffer.isEmpty {
            pendingRemoteIceByOfferId[remoteCid] = [offerId: pendingForOffer]
        }
        if let oldSlot = removeSlotEntry(remoteCid) {
            engineRemoveSlot(oldSlot)
            oldSlot.closePeerConnection()
        }
        let newSlot = getOrCreateSlot(remoteCid: remoteCid)
        return (newSlot.isReady() || newSlot.ensurePeerConnection()) ? newSlot : nil
    }

    private func replacePeerSlotForMediaRecovery(
        remoteCid: String
    ) -> (any PeerConnectionSlotProtocol)? {
        clearOfferTimeout(remoteCid: remoteCid)
        clearIceRestartTimer(remoteCid: remoteCid)
        clearNegotiationState(remoteCid: remoteCid)
        if let oldSlot = removeSlotEntry(remoteCid) {
            engineRemoveSlot(oldSlot)
            oldSlot.closePeerConnection()
        }
        let newSlot = getOrCreateSlot(remoteCid: remoteCid)
        return (newSlot.isReady() || newSlot.ensurePeerConnection()) ? newSlot : nil
    }

    // MARK: - Negotiation Identity / Perfect Negotiation

    private func nextOfferId(remoteCid: String) -> String {
        offerSequence += 1
        return "\(getClientId() ?? ""):\(remoteCid):\(clock.nowMs()):\(offerSequence)"
    }

    private func offerId(from payload: JSONValue?) -> String {
        guard let object = payload?.objectValue else { return Self.legacyOfferId }
        if let offerId = object["offerId"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
           !offerId.isEmpty {
            return offerId
        }
        if let negotiationId = object["negotiationId"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
           !negotiationId.isEmpty {
            return negotiationId
        }
        return Self.legacyOfferId
    }

    private func currentLocalOfferId(remoteCid: String) -> String? {
        pendingLocalOfferIds[remoteCid]
            ?? acceptedRemoteOfferIds[remoteCid]
            ?? currentNegotiationIds[remoteCid]
    }

    private func clearNegotiationState(remoteCid: String? = nil) {
        if let remoteCid {
            pendingLocalOfferIds.removeValue(forKey: remoteCid)
            acceptedRemoteOfferIds.removeValue(forKey: remoteCid)
            currentNegotiationIds.removeValue(forKey: remoteCid)
            ignoredOfferIds.removeValue(forKey: remoteCid)
            settingRemoteAnswerCids.remove(remoteCid)
            pendingRemoteIceByOfferId.removeValue(forKey: remoteCid)
            return
        }
        pendingLocalOfferIds.removeAll()
        acceptedRemoteOfferIds.removeAll()
        currentNegotiationIds.removeAll()
        ignoredOfferIds.removeAll()
        settingRemoteAnswerCids.removeAll()
        pendingRemoteIceByOfferId.removeAll()
    }

    private func handleRemoteOffer(
        slot: any PeerConnectionSlotProtocol,
        remoteCid: String,
        sdp: String,
        offerId: String
    ) {
        let signalingState = slot.getSignalingState()
        let readyForOffer = !slot.isMakingOffer &&
            (signalingState == "STABLE" || settingRemoteAnswerCids.contains(remoteCid))
        let offerCollision = !readyForOffer
        let polite = !shouldIOffer(remoteCid: remoteCid)

        if offerCollision && !polite {
            ignoredOfferIds[remoteCid] = offerId
            return
        }

        func applyOffer(to targetSlot: any PeerConnectionSlotProtocol, allowPeerReset: Bool) {
            ignoredOfferIds.removeValue(forKey: remoteCid)
            pendingLocalOfferIds.removeValue(forKey: remoteCid)
            clearOfferTimeout(remoteCid: remoteCid)
            targetSlot.setRemoteDescription(type: .offer, sdp: sdp) { [weak self, weak targetSlot] success in
                Task { @MainActor in
                    guard let self, let targetSlot else { return }
                    guard success else {
                        if allowPeerReset,
                           let replacementSlot = self.replacePeerSlotForRemoteOffer(remoteCid: remoteCid, offerId: offerId) {
                            applyOffer(to: replacementSlot, allowPeerReset: false)
                            return
                        }
                        self.logger?.log(.warning, tag: "PeerNegotiationEngine", "Failed to apply remote offer from \(remoteCid)")
                        self.scheduleIceRestart(remoteCid: remoteCid, reason: "remote-offer-apply-failed", delayMs: 0)
                        return
                    }
                    self.acceptedRemoteOfferIds[remoteCid] = offerId
                    self.currentNegotiationIds[remoteCid] = offerId
                    self.flushPendingRemoteIce(remoteCid: remoteCid, offerId: offerId, slot: targetSlot)
                    targetSlot.createAnswer(onSdp: { [weak self] answerSdp in
                        Task { @MainActor in
                            guard let self, self.acceptedRemoteOfferIds[remoteCid] == offerId else { return }
                            self.sendMessage(
                                "answer",
                                .object(["sdp": .string(answerSdp), "offerId": .string(offerId)]),
                                remoteCid
                            )
                        }
                    }, onComplete: { [weak self, weak targetSlot] success in
                        guard !success else { return }
                        Task { @MainActor in
                            guard let self, targetSlot != nil else { return }
                            self.logger?.log(.warning, tag: "PeerNegotiationEngine", "Answer creation failed for \(remoteCid); resetting peer")
                            if allowPeerReset,
                               let replacementSlot = self.replacePeerSlotForRemoteOffer(remoteCid: remoteCid, offerId: offerId) {
                                applyOffer(to: replacementSlot, allowPeerReset: false)
                            } else {
                                self.scheduleIceRestart(remoteCid: remoteCid, reason: "answer-failed", delayMs: 0)
                            }
                        }
                    })
                }
            }
        }

        if offerCollision && signalingState == "HAVE_LOCAL_OFFER" {
            pendingLocalOfferIds.removeValue(forKey: remoteCid)
            clearOfferTimeout(remoteCid: remoteCid)
            slot.rollbackLocalDescription { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    guard success else {
                        self.logger?.log(.warning, tag: "PeerNegotiationEngine", "Rollback before remote offer failed for \(remoteCid)")
                        if let replacementSlot = self.replacePeerSlotForRemoteOffer(remoteCid: remoteCid, offerId: offerId) {
                            applyOffer(to: replacementSlot, allowPeerReset: false)
                        } else {
                            self.scheduleIceRestart(remoteCid: remoteCid, reason: "rollback-failed", delayMs: 0)
                        }
                        return
                    }
                    applyOffer(to: slot, allowPeerReset: true)
                }
            }
            return
        }

        applyOffer(to: slot, allowPeerReset: true)
    }

    private func handleRemoteAnswer(
        slot: any PeerConnectionSlotProtocol,
        remoteCid: String,
        sdp: String,
        offerId: String
    ) {
        let pendingOfferId = pendingLocalOfferIds[remoteCid]
        guard slot.getSignalingState() == "HAVE_LOCAL_OFFER" else { return }
        if offerId != Self.legacyOfferId && pendingOfferId != offerId {
            return
        }

        settingRemoteAnswerCids.insert(remoteCid)
        slot.setRemoteDescription(type: .answer, sdp: sdp) { [weak self, weak slot] success in
            Task { @MainActor in
                guard let self else { return }
                self.settingRemoteAnswerCids.remove(remoteCid)
                guard let slot else { return }
                if success {
                    self.initialAnswerReceivedCids.insert(remoteCid)
                    // The offer this answer completes is whatever was pending when we validated
                    // it above; `pendingOfferId` also covers the legacy/no-offerId path, where
                    // `offerId` is the sentinel rather than our real local id.
                    let completedOfferId = pendingOfferId ?? offerId
                    // Finalize negotiation state only while the slot's pending offer is still the
                    // one we completed. A renegotiation offer (e.g. a "pending-retry" ICE restart
                    // from the STABLE signaling callback) can replace it during this async
                    // setRemoteDescription; finalizing then would clobber the newer offer's id and
                    // cancel its per-peer offer-timeout / pending-retry.
                    if self.pendingLocalOfferIds[remoteCid] == completedOfferId {
                        self.pendingLocalOfferIds.removeValue(forKey: remoteCid)
                        self.currentNegotiationIds[remoteCid] = completedOfferId
                        self.ignoredOfferIds.removeValue(forKey: remoteCid)
                        self.clearOfferTimeout(remoteCid: remoteCid)
                        slot.clearPendingIceRestart()
                    }
                    self.flushPendingRemoteIce(remoteCid: remoteCid, offerId: completedOfferId, slot: slot)
                    self.updateAggregatePeerState()
                    self.onConnectionStatusUpdate()
                } else if self.shouldIOffer(remoteCid: remoteCid) {
                    self.scheduleIceRestart(
                        remoteCid: remoteCid,
                        reason: "answer-apply-failed",
                        delayMs: 0,
                        allowBeforeFirstAnswer: true
                    )
                }
            }
        }
    }

    private func handleRemoteIce(
        slot: any PeerConnectionSlotProtocol,
        remoteCid: String,
        candidate: IceCandidatePayload,
        offerId: String
    ) {
        if ignoredOfferIds[remoteCid] == offerId { return }
        if offerId != Self.legacyOfferId, !isKnownNegotiationId(remoteCid: remoteCid, offerId: offerId) {
            var pendingByOffer = pendingRemoteIceByOfferId[remoteCid] ?? [:]
            var pending = pendingByOffer[offerId] ?? []
            if pending.count < WebRtcResilience.iceCandidateBufferMax {
                pending.append(candidate)
            }
            pendingByOffer[offerId] = pending
            pendingRemoteIceByOfferId[remoteCid] = pendingByOffer
            return
        }
        slot.addIceCandidate(candidate)
    }

    private func isKnownNegotiationId(remoteCid: String, offerId: String) -> Bool {
        pendingLocalOfferIds[remoteCid] == offerId ||
            acceptedRemoteOfferIds[remoteCid] == offerId ||
            currentNegotiationIds[remoteCid] == offerId
    }

    private func flushPendingRemoteIce(
        remoteCid: String,
        offerId: String,
        slot: any PeerConnectionSlotProtocol
    ) {
        guard var pendingByOffer = pendingRemoteIceByOfferId[remoteCid],
              let candidates = pendingByOffer.removeValue(forKey: offerId) else {
            return
        }
        candidates.forEach(slot.addIceCandidate)
        if pendingByOffer.isEmpty {
            pendingRemoteIceByOfferId.removeValue(forKey: remoteCid)
        } else {
            pendingRemoteIceByOfferId[remoteCid] = pendingByOffer
        }
    }

    // MARK: - Offer Logic

    /// True while this peer is the deferred-answer offerer awaiting its first answer from `remoteCid`.
    /// Gates the initial offer-timeout/ICE-restart/media-restart suppression; renegotiations after
    /// the first answer behave normally.
    private func isDeferringInitialNegotiation(_ remoteCid: String) -> Bool {
        deferInitialAnswer() && shouldIOffer(remoteCid: remoteCid) && !initialAnswerReceivedCids.contains(remoteCid)
    }

    private func shouldIOffer(remoteCid: String, roomState: RoomState? = nil) -> Bool {
        guard let myCid = getClientId() else { return false }
        let activeRoomState = roomState ?? getCurrentRoomState()
        if let activeRoomState,
           !activeRoomState.participants.contains(where: { $0.cid == remoteCid }) {
            return false
        }
        if deferInitialAnswer(), let activeRoomState {
            let participantCids = Set(activeRoomState.participants.map(\.cid))
            let hostCid = participantCids.contains(activeRoomState.hostCid) ? activeRoomState.hostCid : nil
            if participantCids.count <= 2, let hostCid {
                return myCid == hostCid
            }
        }
        return myCid < remoteCid
    }

    private func canOffer(slot: any PeerConnectionSlotProtocol) -> Bool {
        guard let roomState = getCurrentRoomState() else { return false }
        guard getParticipantCount() > 1 else { return false }
        guard isSignalingConnected() else { return false }
        guard isLocalMediaReady() else { return false }
        guard shouldIOffer(remoteCid: slot.remoteCid, roomState: roomState) else { return false }
        guard roomState.participants.first(where: { $0.cid == slot.remoteCid })?.signalingStatus == .active else { return false }
        return slot.isReady() || slot.ensurePeerConnection()
    }

    private func isParticipantActive(remoteCid: String) -> Bool {
        getCurrentRoomState()?.participants.first(where: { $0.cid == remoteCid })?.signalingStatus == .active
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

        let offerId = nextOfferId(remoteCid: slot.remoteCid)
        let remoteCid = slot.remoteCid
        pendingLocalOfferIds[slot.remoteCid] = offerId
        acceptedRemoteOfferIds.removeValue(forKey: slot.remoteCid)
        ignoredOfferIds.removeValue(forKey: slot.remoteCid)
        slot.beginOffer()
        let started = slot.createOffer(
            iceRestart: iceRestart,
            onSdp: { [weak self] sdp in
                Task { @MainActor in
                    guard let self else { return }
                    guard self.pendingLocalOfferIds[remoteCid] == offerId else { return }
                    self.sendMessage(
                        "offer",
                        .object(["sdp": .string(sdp), "offerId": .string(offerId)]),
                        remoteCid
                    )
                    self.scheduleOfferTimeout(remoteCid: remoteCid)
                }
            },
            onComplete: { [weak self] success in
                Task { @MainActor in
                    guard let self else { return }
                    slot.completeOffer()
                    if !success {
                        self.pendingLocalOfferIds.removeValue(forKey: slot.remoteCid)
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
            pendingLocalOfferIds.removeValue(forKey: slot.remoteCid)
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

    private func handleRenegotiationNeeded(remoteCid: String) {
        guard let slot = getSlot(remoteCid) else { return }
        if shouldIOffer(remoteCid: remoteCid, roomState: getCurrentRoomState()) {
            maybeSendOffer(slot: slot, force: true, iceRestart: false)
        } else {
            requestPeerLocalTrackNegotiation(remoteCid: remoteCid, slot: slot)
        }
    }

    private func handleMediaRestartRequest(slot: any PeerConnectionSlotProtocol, remoteCid: String, reason: String) {
        if reason == SignalingProtocolConstants.mediaRestartReasonLocalTrackNegotiation {
            handleLocalTrackNegotiationRequest(slot: slot, remoteCid: remoteCid)
            return
        }
        if isDeferringInitialNegotiation(remoteCid) {
            logger?.log(.debug, tag: "PeerNegotiationEngine", "Ignoring media restart for \(remoteCid) before first answer")
            return
        }
        guard canOffer(slot: slot) else { return }
        let now = clock.nowMs()
        if let lastHandledAt = lastMediaRestartHandledAtByCid[remoteCid],
           now - lastHandledAt < Int64(WebRtcResilience.outboundMediaRecoveryCooldownMs) {
            return
        }
        lastMediaRestartHandledAtByCid[remoteCid] = now
        logger?.log(.warning, tag: "PeerNegotiationEngine", "Recreating peer after media restart request from \(remoteCid)")
        guard let replacement = replacePeerSlotForMediaRecovery(remoteCid: remoteCid) else { return }
        maybeSendOffer(slot: replacement)
    }

    private func handleLocalTrackNegotiationRequest(slot: any PeerConnectionSlotProtocol, remoteCid: String) {
        guard canOffer(slot: slot), slot.getSignalingState() == "STABLE" else { return }
        logger?.log(.debug, tag: "PeerNegotiationEngine", "Creating offer after peer local track negotiation request from \(remoteCid)")
        maybeSendOffer(slot: slot, force: true)
    }

    // MARK: - Outbound Media Watchdog

    private func recoverStalledOutboundMedia(remoteCid: String, slot: any PeerConnectionSlotProtocol) {
        var watch = outboundMediaWatchByCid[remoteCid] ?? OutboundMediaWatch()
        if watch.inFlight { return }
        if !isPeerMediaConnected(slot: slot) {
            resetOutboundMediaSample(&watch)
            outboundMediaWatchByCid[remoteCid] = watch
            return
        }

        watch.inFlight = true
        outboundMediaWatchByCid[remoteCid] = watch
        slot.collectOutboundMediaSample { [weak self] sample in
            Task { @MainActor in
                guard let self else { return }
                guard let current = self.getSlot(remoteCid), current === slot else {
                    self.outboundMediaWatchByCid[remoteCid]?.inFlight = false
                    return
                }
                self.finalizeOutboundMediaSample(remoteCid: remoteCid, slot: slot, sample: sample)
            }
        }
    }

    private func finalizeOutboundMediaSample(
        remoteCid: String,
        slot: any PeerConnectionSlotProtocol,
        sample: OutboundMediaSample?
    ) {
        var watch = outboundMediaWatchByCid[remoteCid] ?? OutboundMediaWatch()
        defer {
            watch.inFlight = false
            outboundMediaWatchByCid[remoteCid] = watch
        }

        guard let sample, sample.expectsAudio || sample.expectsVideo else {
            resetOutboundMediaSample(&watch)
            return
        }

        let previous = watch.lastSample
        watch.lastSample = sample
        guard let previous else {
            watch.stallSamples = 0
            return
        }

        let videoStalled = sample.expectsVideo &&
            sample.videoBytesSent <= previous.videoBytesSent &&
            sample.videoFramesSent <= previous.videoFramesSent
        let audioOnlyStalled = !sample.expectsVideo &&
            sample.expectsAudio &&
            sample.audioBytesSent <= previous.audioBytesSent
        guard videoStalled || audioOnlyStalled else {
            watch.stallSamples = 0
            return
        }

        watch.stallSamples += 1
        guard watch.stallSamples >= WebRtcResilience.outboundMediaStallSamples else { return }

        let now = clock.nowMs()
        if let lastRecoveryAtMs = watch.lastRecoveryAtMs,
           now - lastRecoveryAtMs < Int64(WebRtcResilience.outboundMediaRecoveryCooldownMs) {
            return
        }

        watch.lastRecoveryAtMs = now
        resetOutboundMediaSample(&watch)
        if shouldIOffer(remoteCid: remoteCid) {
            recreatePeerForMediaRecovery(remoteCid: remoteCid, reason: "stalled outbound media")
        } else {
            requestPeerMediaRecovery(remoteCid: remoteCid, reason: "stalled outbound media")
        }
    }

    private func isPeerMediaConnected(slot: any PeerConnectionSlotProtocol) -> Bool {
        slot.getSignalingState() == "STABLE" &&
            slot.getConnectionState() == .connected &&
            (slot.getIceConnectionState() == "CONNECTED" || slot.getIceConnectionState() == "COMPLETED")
    }

    private func resetOutboundMediaSample(_ watch: inout OutboundMediaWatch) {
        watch.lastSample = nil
        watch.stallSamples = 0
    }

    private func requestPeerMediaRecovery(remoteCid: String, reason: String) {
        guard isSignalingConnected(), isParticipantActive(remoteCid: remoteCid) else { return }
        logger?.log(.warning, tag: "PeerNegotiationEngine", "Requesting media restart from \(remoteCid) after \(reason)")
        sendMessage("media_restart_request", .object(["reason": .string(reason)]), remoteCid)
    }

    private func requestPeerLocalTrackNegotiation(remoteCid: String, slot: any PeerConnectionSlotProtocol) {
        guard isSignalingConnected(), isLocalMediaReady(), isParticipantActive(remoteCid: remoteCid) else { return }
        guard slot.getSignalingState() == "STABLE" else { return }
        logger?.log(.debug, tag: "PeerNegotiationEngine", "Requesting local track negotiation offer from \(remoteCid)")
        sendMessage(
            "media_restart_request",
            .object(["reason": .string(SignalingProtocolConstants.mediaRestartReasonLocalTrackNegotiation)]),
            remoteCid
        )
    }

    private func recreatePeerForMediaRecovery(remoteCid: String, reason: String) {
        guard let current = getSlot(remoteCid), canOffer(slot: current) else { return }
        logger?.log(.warning, tag: "PeerNegotiationEngine", "Recreating peer after \(reason) for \(remoteCid)")
        guard let replacement = replacePeerSlotForMediaRecovery(remoteCid: remoteCid) else { return }
        maybeSendOffer(slot: replacement)
    }

    /// FIX 1: detect when an EXISTING peer's computed independent-content
    /// capability now differs from the capability its current slot was built with
    /// and recreate the slot so it re-runs role binding from scratch with the
    /// correct camera/content m-line layout. Returns true when a recreate was
    /// performed (the caller skips its generic offer block for this peer).
    ///
    /// Flag-off byte-identical: `peerIndependentContentSupported` is always false
    /// when the local flag is off, and every legacy slot is built with
    /// `supportsIndependentContentVideo == false`, so the capability never flips
    /// and this is inert (no recreate ever).
    private func reconcilePeerCapability(remoteCid: String) -> Bool {
        guard let slot = getSlot(remoteCid) else { return false }
        let capableNow = peerIndependentContentSupported(remoteCid)
        guard capableNow != slot.supportsIndependentContentVideo else { return false }
        recreatePeerForCapabilityChange(remoteCid: remoteCid)
        return true
    }

    /// Close and recreate a slot whose independent-content capability flipped.
    /// Reuses `replacePeerSlotForMediaRecovery`'s close+recreate, but is NOT
    /// `canOffer`/`shouldIOffer`-gated: BOTH ends saw the same capability change in
    /// the shared room_state, so each side must rebuild its own slot.
    /// `getOrCreateSlot` (inside the replace) re-snapshots the new capability and
    /// re-creates the correct transceiver layout (owner pre-creates camera+content;
    /// answerer pre-creates nothing). The deterministic offer owner re-offers here;
    /// the answerer's fresh slot waits for that re-offer. An in-progress screen
    /// share re-attaches automatically: `WebRtcEngine.createSlot` runs
    /// `attachLocalTracksToSlot`, which fills a recreated capable peer's content
    /// sender (via the pending-content mechanism) or a recreated legacy peer's
    /// single sender (the legacy swap).
    private func recreatePeerForCapabilityChange(remoteCid: String) {
        guard isParticipantActive(remoteCid: remoteCid) else { return }
        logger?.log(.debug, tag: "PeerNegotiationEngine", "Recreating peer after independent-content capability change for \(remoteCid)")
        guard let replacement = replacePeerSlotForMediaRecovery(remoteCid: remoteCid) else { return }
        // Re-offer only from the deterministic offer owner; the answerer's fresh
        // slot waits for that re-offer (mirrors web's `recreatePeerForCapabilityChange`).
        if shouldIOffer(remoteCid: remoteCid) {
            maybeSendOffer(slot: replacement)
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
        if isDeferringInitialNegotiation(remoteCid) {
            logger?.log(.debug, tag: "PeerNegotiationEngine", "Deferring initial offer timeout for \(remoteCid)")
            return
        }

        slot.setOfferTimeoutTask(Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: WebRtcResilience.offerTimeoutNs)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, let slot = self.getSlot(remoteCid) else { return }
                guard slot.getSignalingState() == "HAVE_LOCAL_OFFER" else {
                    if triggerIceRestart, self.shouldIOffer(remoteCid: remoteCid) {
                        self.scheduleIceRestart(remoteCid: remoteCid, reason: "offer-timeout-stale", delayMs: 0)
                    }
                    return
                }
                if triggerIceRestart {
                    slot.markPendingIceRestart()
                }
                self.pendingLocalOfferIds.removeValue(forKey: remoteCid)
                slot.rollbackLocalDescription { _ in
                    Task { @MainActor in
                        if triggerIceRestart {
                            if self.shouldIOffer(remoteCid: remoteCid) {
                                self.scheduleIceRestart(remoteCid: remoteCid, reason: "offer-timeout", delayMs: 0)
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

    func scheduleIceRestart(
        remoteCid: String,
        reason: String,
        delayMs: Int,
        allowBeforeFirstAnswer: Bool = false
    ) {
        guard let slot = getSlot(remoteCid) else { return }
        if !allowBeforeFirstAnswer && isDeferringInitialNegotiation(remoteCid) {
            return
        }
        if !canOffer(slot: slot) {
            slot.markPendingIceRestart()
            return
        }

        guard slot.iceRestartTask == nil else { return }

        // Inside the cooldown window, defer to its expiry instead of dropping:
        // ICE state changes are edge-triggered, so a dropped restart for a
        // connection parked in failed would never be retried. Clamp to one
        // cooldown: nowMs() is wall-clock, so a backwards step would otherwise
        // park the restart for the full skew.
        let now = Double(clock.nowMs())
        let cooldownRemainingMs = slot.lastIceRestartAt > 0
            ? min(Double(WebRtcResilience.iceRestartCooldownMs), max(0, slot.lastIceRestartAt + Double(WebRtcResilience.iceRestartCooldownMs) - now))
            : 0
        let effectiveDelayMs = max(Double(delayMs), cooldownRemainingMs)

        slot.setIceRestartTask(Task { [weak self] in
            guard let clock = self?.clock else { return }
            if effectiveDelayMs > 0 {
                try? await clock.sleep(nanoseconds: UInt64(effectiveDelayMs) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                self?.triggerIceRestart(
                    remoteCid: remoteCid,
                    reason: reason,
                    allowBeforeFirstAnswer: allowBeforeFirstAnswer
                )
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

    private func triggerIceRestart(
        remoteCid: String,
        reason: String,
        allowBeforeFirstAnswer: Bool = false
    ) {
        guard let slot = getSlot(remoteCid) else { return }
        slot.cancelIceRestartTask()
        if !allowBeforeFirstAnswer && isDeferringInitialNegotiation(remoteCid) {
            logger?.log(.debug, tag: "PeerNegotiationEngine", "Suppressing ICE restart for \(remoteCid) before first answer (\(reason))")
            return
        }

        guard canOffer(slot: slot) else {
            slot.markPendingIceRestart()
            return
        }

        if slot.isMakingOffer {
            slot.markPendingIceRestart()
            return
        }

        let signalingState = slot.getSignalingState()
        guard signalingState == "STABLE" else {
            slot.markPendingIceRestart()
            if signalingState == "HAVE_LOCAL_OFFER" {
                pendingLocalOfferIds.removeValue(forKey: remoteCid)
                rollbackStaleLocalOfferAndRetryIceRestart(
                    slot: slot,
                    remoteCid: remoteCid,
                    reason: reason,
                    allowBeforeFirstAnswer: allowBeforeFirstAnswer
                )
            }
            return
        }

        slot.recordIceRestart(nowMs: clock.nowMs())
        maybeSendOffer(slot: slot, force: true, iceRestart: true)
    }

    private func rollbackStaleLocalOfferAndRetryIceRestart(
        slot: any PeerConnectionSlotProtocol,
        remoteCid: String,
        reason: String,
        allowBeforeFirstAnswer: Bool = false
    ) {
        slot.rollbackLocalDescription { [weak self] success in
            Task { @MainActor in
                guard let self else { return }
                if success {
                    guard let currentSlot = self.getSlot(remoteCid),
                          currentSlot.getSignalingState() == "STABLE",
                          currentSlot.pendingIceRestart || allowBeforeFirstAnswer else {
                        return
                    }
                    self.triggerIceRestart(
                        remoteCid: remoteCid,
                        reason: "\(reason)-rollback",
                        allowBeforeFirstAnswer: allowBeforeFirstAnswer
                    )
                } else {
                    self.scheduleOfferTimeout(remoteCid: remoteCid)
                }
            }
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
            RtcSignalingState(rawValueOrUnknown: nextSignalingState)
        )
    }
}
