package app.serenada.core.call

import android.os.Handler
import app.serenada.core.IceConnectionState
import app.serenada.core.ParticipantSignalingStatus
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import app.serenada.core.PeerConnectionState
import app.serenada.core.RtcSignalingState
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.json.JSONObject

internal class PeerNegotiationEngine(
    private val handler: Handler,
    private val clock: SessionClock,
    // State readers
    private val getClientId: () -> String?,
    // When true, the initial-negotiation offer-timeout is deferred while the host peer awaits
    // its FIRST answer (e.g. PSTN, where the answer is gated on human pickup and can take far
    // longer than OFFER_TIMEOUT_MS). Normal offer-timeout resumes after the first answer.
    private val deferInitialAnswer: () -> Boolean = { false },
    private val getParticipantCount: () -> Int,
    private val getCurrentRoomState: () -> RoomState?,
    private val isSignalingConnected: () -> Boolean,
    private val hasIceServers: () -> Boolean,
    private val isLocalMediaReady: () -> Boolean = { true },
    // Slot access (session owns peerSlots)
    private val getSlot: (String) -> PeerConnectionSlotProtocol?,
    private val getAllSlots: () -> Map<String, PeerConnectionSlotProtocol>,
    private val setSlot: (String, PeerConnectionSlotProtocol) -> Unit,
    private val removeSlotEntry: (String) -> PeerConnectionSlotProtocol?,
    // WebRTC engine
    private val createSlotViaEngine: (
        remoteCid: String,
        onLocalIceCandidate: (String, IceCandidate) -> Unit,
        onRemoteVideoTrack: (String, org.webrtc.VideoTrack?) -> Unit,
        onConnectionStateChange: (String, PeerConnection.PeerConnectionState) -> Unit,
        onIceConnectionStateChange: (String, PeerConnection.IceConnectionState) -> Unit,
        onSignalingStateChange: (String, PeerConnection.SignalingState) -> Unit,
        onRenegotiationNeeded: (String) -> Unit,
        supportsIndependentContentVideo: Boolean,
        isOfferOwner: () -> Boolean,
    ) -> PeerConnectionSlotProtocol,
    private val engineRemoveSlot: (PeerConnectionSlotProtocol) -> Unit,
    // Per-peer independent-content gate (local flag AND peer capability AND both
    // videoMediaEnabled, all folded into [supported]). Defaults to legacy
    // (false) so callers that don't supply it keep today's single-video behavior.
    private val peerIndependentContentCapability: (String) -> PeerIndependentContentCapability =
        { PeerIndependentContentCapability(supported = false) },
    // Callbacks to session
    private val sendMessage: (String, JSONObject?, String?) -> Unit,
    private val onRemoteParticipantsChanged: () -> Unit,
    private val onAggregatePeerStateChanged: (IceConnectionState, PeerConnectionState, RtcSignalingState) -> Unit,
    private val onConnectionStatusUpdate: () -> Unit,
    private val logger: SerenadaLogger? = null,
) {
    companion object {
        private const val TAG = "PeerNegotiationEngine"
        private const val LEGACY_OFFER_ID = "__legacy__"
        val ICE_PRIORITY = mapOf(
            PeerConnection.IceConnectionState.FAILED to 0, PeerConnection.IceConnectionState.DISCONNECTED to 1,
            PeerConnection.IceConnectionState.CHECKING to 2, PeerConnection.IceConnectionState.NEW to 3,
            PeerConnection.IceConnectionState.CONNECTED to 4, PeerConnection.IceConnectionState.COMPLETED to 5,
            PeerConnection.IceConnectionState.CLOSED to 6,
        )
        val CONN_PRIORITY = mapOf(
            PeerConnection.PeerConnectionState.FAILED to 0, PeerConnection.PeerConnectionState.DISCONNECTED to 1,
            PeerConnection.PeerConnectionState.CONNECTING to 2, PeerConnection.PeerConnectionState.NEW to 3,
            PeerConnection.PeerConnectionState.CONNECTED to 4, PeerConnection.PeerConnectionState.CLOSED to 5,
        )
        val SIG_PRIORITY = mapOf(
            PeerConnection.SignalingState.CLOSED to 0, PeerConnection.SignalingState.HAVE_LOCAL_OFFER to 1,
            PeerConnection.SignalingState.HAVE_REMOTE_OFFER to 2, PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER to 3,
            PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER to 4, PeerConnection.SignalingState.STABLE to 5,
        )
    }

    /** Resolved per-peer independent-content routing inputs at slot creation. */
    internal data class PeerIndependentContentCapability(
        val supported: Boolean,
    )

    private data class OutboundMediaWatch(
        var lastSample: OutboundMediaSample? = null,
        var stallSamples: Int = 0,
        var inFlight: Boolean = false,
        var lastRecoveryAtMs: Long? = null,
    )

    private var offerSequence = 0L
    private val pendingLocalOfferIds = mutableMapOf<String, String>()
    private val acceptedRemoteOfferIds = mutableMapOf<String, String>()
    private val currentNegotiationIds = mutableMapOf<String, String>()
    private val ignoredOfferIds = mutableMapOf<String, String>()
    private val settingRemoteAnswerCids = mutableSetOf<String>()
    // Remote cids whose FIRST answer has been applied. Gates deferInitialAnswer so only the
    // initial negotiation is deferred; renegotiations after the first answer time out normally.
    private val initialAnswerReceivedCids = mutableSetOf<String>()
    private val pendingRemoteIceByOfferId = mutableMapOf<String, MutableMap<String, MutableList<IceCandidate>>>()
    private val participantStatuses = mutableMapOf<String, ParticipantSignalingStatus>()
    private val outboundMediaWatchByCid = mutableMapOf<String, OutboundMediaWatch>()
    private val lastMediaRestartHandledAtByCid = mutableMapOf<String, Long>()

    // --- Public API ---

    fun syncPeers(roomState: RoomState) {
        val myCid = getClientId()
        val remotePeers = roomState.participants.filter { it.cid != myCid }
        val remoteCids = remotePeers.map { it.cid }.toSet()

        getAllSlots().keys.filter { it !in remoteCids }.forEach { removePeerSlot(it) }
        participantStatuses.keys.filter { it !in remoteCids }.forEach { participantStatuses.remove(it) }
        if (remotePeers.isEmpty()) {
            clearOfferTimeout()
            clearIceRestartTimer()
            participantStatuses.clear()
        }

        remotePeers.forEach { participant ->
            val previousStatus = participantStatuses[participant.cid]
            participantStatuses[participant.cid] = participant.signalingStatus
            val becameActive = previousStatus == ParticipantSignalingStatus.SUSPENDED &&
                participant.signalingStatus == ParticipantSignalingStatus.ACTIVE
            val existing = getSlot(participant.cid)
            if (existing != null) {
                // Capability-transition slot handling (FIX 1). The slot snapshots
                // [supportsIndependentContentVideo] at creation. A peer announced
                // before its caps arrive (early offer or a no-caps room_state) gets
                // a LEGACY slot; the later cap-bearing room_state only updates the
                // stored caps, leaving the slot immutably legacy — so a late
                // CAPABLE peer would never bind the content transceiver. When the
                // freshly computed capability now DIFFERS from what the slot was
                // built with, recreate it so role binding re-runs with the correct
                // m-line layout. Recreated here (not offerer-gated: BOTH ends flip);
                // the recreate re-offers only if WE are the owner. Skip the generic
                // offer block this pass — the recreate already re-offered.
                if (reconcilePeerCapability(participant.cid)) {
                    return@forEach
                }
            }
            val slot = existing ?: getOrCreateSlot(participant.cid)
            slot.ensurePeerConnection()
            if (shouldIOffer(participant.cid, roomState)) {
                if (becameActive) {
                    scheduleIceRestart(participant.cid, "peer-reattached", 0)
                } else {
                    maybeSendOffer(slot)
                }
            }
        }

        updateAggregatePeerState()
    }

    fun onLocalMediaReady() {
        maybeSendOffer()
    }

    fun processSignalingPayload(msg: SignalingMessage) {
        val fromCid = msg.payload?.optString("from").orEmpty().ifBlank { return }
        val roomState = getCurrentRoomState()
        val localCid = getClientId()
        if (fromCid == localCid) return
        if (roomState != null && roomState.participants.none { it.cid == fromCid }) {
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Ignoring ${msg.type} from departed peer $fromCid")
            return
        }
        val slot = getOrCreateSlot(fromCid)
        if (!slot.isReady() && !slot.ensurePeerConnection()) {
            return
        }
        when (msg.type) {
            "offer" -> {
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                handleRemoteOffer(slot, fromCid, sdp, offerIdFromPayload(msg.payload))
            }
            "answer" -> {
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                handleRemoteAnswer(slot, fromCid, sdp, offerIdFromPayload(msg.payload))
            }
            "ice" -> {
                val candidateJson = msg.payload?.optJSONObject("candidate") ?: return
                val candidateSdp = candidateJson.optString("candidate", "")
                if (candidateSdp.isBlank()) return
                val sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0)
                val sdpMid = candidateJson.optString("sdpMid").takeIf { it.isNotBlank() }
                val candidate = IceCandidate(
                    sdpMid,
                    sdpMLineIndex,
                    candidateSdp
                )
                handleRemoteIce(slot, fromCid, candidate, offerIdFromPayload(msg.payload))
            }
            "media_restart_request" -> {
                val reason = msg.payload?.optString("reason").orEmpty().trim()
                handleMediaRestartRequest(slot, fromCid, reason)
            }
        }
    }

    fun onIceServersReady() {
        maybeSendOffer()
    }

    fun scheduleIceRestart(reason: String, delayMs: Long) {
        getAllSlots().values.forEach { if (shouldIOffer(it.remoteCid)) scheduleIceRestart(it.remoteCid, reason, delayMs) }
    }

    fun triggerIceRestart(reason: String) {
        getAllSlots().values.forEach { if (shouldIOffer(it.remoteCid)) triggerIceRestart(it.remoteCid, reason) }
    }

    fun handleSignalingReconnect() {
        getAllSlots().values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid)) {
                triggerIceRestart(slot.remoteCid, "signaling-reconnect")
            }
        }
    }

    fun scheduleDirtyPairRestart(remoteCid: String) {
        getSlot(remoteCid) ?: return
        if (shouldIOffer(remoteCid)) {
            scheduleIceRestart(remoteCid, "negotiation-dirty", 0)
        }
    }

    fun resetAll() {
        clearOfferTimeout()
        clearIceRestartTimer()
        clearNegotiationState()
        participantStatuses.clear()
        outboundMediaWatchByCid.clear()
        lastMediaRestartHandledAtByCid.clear()
        initialAnswerReceivedCids.clear()
    }

    fun recoverStalledOutboundMedia() {
        val slots = getAllSlots()
        outboundMediaWatchByCid.keys.filterNot(slots::containsKey).forEach(outboundMediaWatchByCid::remove)
        lastMediaRestartHandledAtByCid.keys.filterNot(slots::containsKey).forEach(lastMediaRestartHandledAtByCid::remove)
        if (!isSignalingConnected()) return
        for ((remoteCid, slot) in slots) {
            recoverStalledOutboundMedia(remoteCid, slot)
        }
    }

    // --- Slot Lifecycle ---

    private fun getOrCreateSlot(remoteCid: String): PeerConnectionSlotProtocol {
        getSlot(remoteCid)?.let { return it }
        var callbackSlot: PeerConnectionSlotProtocol? = null
        val slot = createSlotViaEngine(
            remoteCid,
            { cid: String, candidate: IceCandidate ->
                // Fires on the WebRTC signaling thread; negotiation state
                // (offer ids) and the transport are main-thread-owned.
                handler.post {
                    if (getSlot(cid) !== callbackSlot) return@post
                    val payload = JSONObject().apply {
                        val candidateJson = JSONObject()
                        candidateJson.put("candidate", candidate.sdp)
                        candidateJson.put("sdpMid", candidate.sdpMid)
                        candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidateJson)
                        currentLocalOfferId(cid)?.let { put("offerId", it) }
                    }
                    sendMessage("ice", payload, cid)
                }
            },
            { _, _ ->
                handler.post { onRemoteParticipantsChanged() }
            },
            { cid, connState ->
                handler.post {
                    when (connState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            clearIceRestartTimer(cid)
                            getSlot(cid)?.clearPendingIceRestart()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED ->
                            scheduleIceRestart(cid, "conn-disconnected", 2000)
                        PeerConnection.PeerConnectionState.FAILED ->
                            scheduleIceRestart(cid, "conn-failed", 0)
                        else -> Unit
                    }
                    onRemoteParticipantsChanged()
                    updateAggregatePeerState()
                    onConnectionStatusUpdate()
                }
            },
            { cid, iceState ->
                handler.post {
                    when (iceState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            clearIceRestartTimer(cid)
                            getSlot(cid)?.clearPendingIceRestart()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            scheduleIceRestart(cid, "ice-disconnected", 2000)
                        PeerConnection.IceConnectionState.FAILED ->
                            scheduleIceRestart(cid, "ice-failed", 0)
                        else -> Unit
                    }
                    onRemoteParticipantsChanged()
                    updateAggregatePeerState()
                    onConnectionStatusUpdate()
                }
            },
            { cid, sigState ->
                handler.post {
                    if (sigState == PeerConnection.SignalingState.STABLE) {
                        clearOfferTimeout(cid)
                        if (getSlot(cid)?.pendingIceRestart == true) {
                            getSlot(cid)?.clearPendingIceRestart()
                            triggerIceRestart(cid, "pending-retry")
                        }
                    }
                    updateAggregatePeerState()
                    onConnectionStatusUpdate()
                }
            },
            { cid ->
                handler.post {
                    handleRenegotiationNeeded(cid)
                }
            },
            peerIndependentContentCapability(remoteCid).supported,
            // Lazily evaluated so the slot's owner pre-creation / answerer-bind
            // choice tracks ownership the same way the negotiation path does.
            { shouldIOffer(remoteCid, getCurrentRoomState()) },
        )
        callbackSlot = slot
        setSlot(remoteCid, slot)
        return slot
    }

    private fun removePeerSlot(remoteCid: String) {
        clearOfferTimeout(remoteCid)
        clearIceRestartTimer(remoteCid)
        clearNegotiationState(remoteCid)
        // Cleared only on true departure (not the mid-call replace* paths that also call
        // clearNegotiationState), so a peer that leaves and rejoins gets a fresh first-answer
        // deferral, while an in-call renegotiation keeps its "answered" state.
        initialAnswerReceivedCids.remove(remoteCid)
        participantStatuses.remove(remoteCid)
        outboundMediaWatchByCid.remove(remoteCid)
        lastMediaRestartHandledAtByCid.remove(remoteCid)
        val slot = removeSlotEntry(remoteCid) ?: return
        engineRemoveSlot(slot)
        slot.closePeerConnection(deferDispose = true)
    }

    private fun replacePeerSlotForRemoteOffer(remoteCid: String, offerId: String): PeerConnectionSlotProtocol? {
        val pendingForOffer = pendingRemoteIceByOfferId[remoteCid]?.get(offerId)?.toMutableList()
        clearOfferTimeout(remoteCid)
        clearIceRestartTimer(remoteCid)
        clearNegotiationState(remoteCid)
        if (!pendingForOffer.isNullOrEmpty()) {
            pendingRemoteIceByOfferId[remoteCid] = mutableMapOf(offerId to pendingForOffer)
        }
        removeSlotEntry(remoteCid)?.let { oldSlot ->
            engineRemoveSlot(oldSlot)
            oldSlot.closePeerConnection(deferDispose = true)
        }
        val newSlot = getOrCreateSlot(remoteCid)
        return newSlot.takeIf { it.isReady() || it.ensurePeerConnection() }
    }

    private fun replacePeerSlotForMediaRecovery(remoteCid: String): PeerConnectionSlotProtocol? {
        clearOfferTimeout(remoteCid)
        clearIceRestartTimer(remoteCid)
        clearNegotiationState(remoteCid)
        removeSlotEntry(remoteCid)?.let { oldSlot ->
            engineRemoveSlot(oldSlot)
            oldSlot.closePeerConnection(deferDispose = true)
        }
        val newSlot = getOrCreateSlot(remoteCid)
        return newSlot.takeIf { it.isReady() || it.ensurePeerConnection() }
    }

    // --- Negotiation Identity / Perfect Negotiation ---

    private fun nextOfferId(remoteCid: String): String {
        offerSequence += 1
        return "${getClientId().orEmpty()}:$remoteCid:${clock.nowMs()}:$offerSequence"
    }

    private fun offerIdFromPayload(payload: JSONObject?): String {
        return payload
            ?.optString("offerId")
            ?.takeIf { it.isNotBlank() }
            ?: payload
                ?.optString("negotiationId")
                ?.takeIf { it.isNotBlank() }
            ?: LEGACY_OFFER_ID
    }

    private fun currentLocalOfferId(remoteCid: String): String? {
        return pendingLocalOfferIds[remoteCid]
            ?: acceptedRemoteOfferIds[remoteCid]
            ?: currentNegotiationIds[remoteCid]
    }

    private fun clearNegotiationState(remoteCid: String? = null) {
        if (remoteCid != null) {
            pendingLocalOfferIds.remove(remoteCid)
            acceptedRemoteOfferIds.remove(remoteCid)
            currentNegotiationIds.remove(remoteCid)
            ignoredOfferIds.remove(remoteCid)
            settingRemoteAnswerCids.remove(remoteCid)
            pendingRemoteIceByOfferId.remove(remoteCid)
            return
        }
        pendingLocalOfferIds.clear()
        acceptedRemoteOfferIds.clear()
        currentNegotiationIds.clear()
        ignoredOfferIds.clear()
        settingRemoteAnswerCids.clear()
        pendingRemoteIceByOfferId.clear()
    }

    private fun handleRemoteOffer(
        slot: PeerConnectionSlotProtocol,
        remoteCid: String,
        sdp: String,
        offerId: String,
    ) {
        val signalingState = slot.getSignalingState()
        val readyForOffer = !slot.isMakingOffer &&
            (signalingState == PeerConnection.SignalingState.STABLE || remoteCid in settingRemoteAnswerCids)
        val offerCollision = !readyForOffer
        val polite = !shouldIOffer(remoteCid)

        if (offerCollision && !polite) {
            ignoredOfferIds[remoteCid] = offerId
            logger?.log(SerenadaLogLevel.WARNING, TAG, "Ignoring colliding offer from impolite peer $remoteCid")
            return
        }

        fun applyOffer(targetSlot: PeerConnectionSlotProtocol, allowPeerReset: Boolean) {
            ignoredOfferIds.remove(remoteCid)
            pendingLocalOfferIds.remove(remoteCid)
            clearOfferTimeout(remoteCid)
            targetSlot.setRemoteDescription(SessionDescription.Type.OFFER, sdp) { success ->
                handler.post {
                    if (!success) {
                        if (allowPeerReset) {
                            val replacementSlot = replacePeerSlotForRemoteOffer(remoteCid, offerId)
                            if (replacementSlot != null) {
                                applyOffer(replacementSlot, allowPeerReset = false)
                                return@post
                            }
                        }
                        logger?.log(SerenadaLogLevel.WARNING, TAG, "Failed to apply remote offer from $remoteCid")
                        scheduleIceRestart(remoteCid, "remote-offer-apply-failed", 0)
                        return@post
                    }
                    acceptedRemoteOfferIds[remoteCid] = offerId
                    currentNegotiationIds[remoteCid] = offerId
                    flushPendingRemoteIce(remoteCid, offerId, targetSlot)
                    targetSlot.createAnswer(
                        onSdp = { answerSdp ->
                            // Fires on the WebRTC signaling thread; the transport and
                            // negotiation bookkeeping are main-thread-owned (same hop
                            // the offer and ICE callbacks make).
                            handler.post {
                                if (getSlot(remoteCid) !== targetSlot) return@post
                                if (acceptedRemoteOfferIds[remoteCid] != offerId) return@post
                                val payload = JSONObject().apply {
                                    put("sdp", answerSdp)
                                    put("offerId", offerId)
                                }
                                sendMessage("answer", payload, remoteCid)
                            }
                        },
                        onComplete = { answerSuccess ->
                            handler.post {
                                if (!answerSuccess) {
                                    logger?.log(SerenadaLogLevel.WARNING, TAG, "Answer creation failed for $remoteCid; resetting peer")
                                    val replacementSlot = if (allowPeerReset) replacePeerSlotForRemoteOffer(remoteCid, offerId) else null
                                    if (replacementSlot != null) {
                                        applyOffer(replacementSlot, allowPeerReset = false)
                                    } else {
                                        scheduleIceRestart(remoteCid, "answer-failed", 0)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        if (offerCollision && signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            pendingLocalOfferIds.remove(remoteCid)
            clearOfferTimeout(remoteCid)
            slot.rollbackLocalDescription { success ->
                handler.post {
                    if (success) {
                        applyOffer(slot, allowPeerReset = true)
                    } else {
                        logger?.log(SerenadaLogLevel.WARNING, TAG, "Failed to roll back colliding offer for $remoteCid")
                        val replacementSlot = replacePeerSlotForRemoteOffer(remoteCid, offerId)
                        if (replacementSlot != null) {
                            applyOffer(replacementSlot, allowPeerReset = false)
                        } else {
                            scheduleIceRestart(remoteCid, "rollback-failed", 0)
                        }
                    }
                }
            }
            return
        }

        applyOffer(slot, allowPeerReset = true)
    }

    private fun handleRemoteAnswer(
        slot: PeerConnectionSlotProtocol,
        remoteCid: String,
        sdp: String,
        offerId: String,
    ) {
        val pendingOfferId = pendingLocalOfferIds[remoteCid]
        if (slot.getSignalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Dropping stale answer from $remoteCid in ${slot.getSignalingState()}")
            return
        }
        if (offerId != LEGACY_OFFER_ID && pendingOfferId != offerId) {
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Dropping answer from $remoteCid for stale offerId=$offerId")
            return
        }

        settingRemoteAnswerCids.add(remoteCid)
        slot.setRemoteDescription(SessionDescription.Type.ANSWER, sdp) { success ->
            handler.post {
                settingRemoteAnswerCids.remove(remoteCid)
                if (success) {
                    initialAnswerReceivedCids.add(remoteCid)
                    // The offer this answer completes is whatever was pending when we validated
                    // it above; `pendingOfferId` also covers the legacy/no-offerId path, where
                    // `offerId` is the sentinel rather than our real local id.
                    val completedOfferId = pendingOfferId ?: offerId
                    // Finalize negotiation state only while the slot's pending offer is still the
                    // one we completed. A renegotiation offer (e.g. a "pending-retry" ICE restart
                    // fired from the STABLE signaling callback) can replace it during this async
                    // setRemoteDescription; finalizing then would clobber the newer offer's id and
                    // cancel its per-peer offer-timeout / pending-retry, stranding it in
                    // HAVE_LOCAL_OFFER if its answer is lost.
                    if (pendingLocalOfferIds[remoteCid] == completedOfferId) {
                        pendingLocalOfferIds.remove(remoteCid)
                        currentNegotiationIds[remoteCid] = completedOfferId
                        ignoredOfferIds.remove(remoteCid)
                        clearOfferTimeout(remoteCid)
                        slot.clearPendingIceRestart()
                    }
                    flushPendingRemoteIce(remoteCid, completedOfferId, slot)
                    updateAggregatePeerState()
                    onConnectionStatusUpdate()
                } else if (shouldIOffer(remoteCid)) {
                    scheduleIceRestart(remoteCid, "answer-apply-failed", 0, allowBeforeFirstAnswer = true)
                } else {
                    logger?.log(SerenadaLogLevel.WARNING, TAG, "Failed to apply answer from $remoteCid")
                }
            }
        }
    }

    private fun handleRemoteIce(
        slot: PeerConnectionSlotProtocol,
        remoteCid: String,
        candidate: IceCandidate,
        offerId: String,
    ) {
        if (ignoredOfferIds[remoteCid] == offerId) return
        if (offerId != LEGACY_OFFER_ID && !isKnownNegotiationId(remoteCid, offerId)) {
            val pendingByOffer = pendingRemoteIceByOfferId.getOrPut(remoteCid) { mutableMapOf() }
            val pending = pendingByOffer.getOrPut(offerId) { mutableListOf() }
            if (pending.size < WebRtcResilienceConstants.ICE_CANDIDATE_BUFFER_MAX) {
                pending.add(candidate)
            }
            return
        }
        slot.addIceCandidate(candidate)
    }

    private fun isKnownNegotiationId(remoteCid: String, offerId: String): Boolean {
        return pendingLocalOfferIds[remoteCid] == offerId ||
            acceptedRemoteOfferIds[remoteCid] == offerId ||
            currentNegotiationIds[remoteCid] == offerId
    }

    private fun flushPendingRemoteIce(remoteCid: String, offerId: String, slot: PeerConnectionSlotProtocol) {
        val pendingByOffer = pendingRemoteIceByOfferId[remoteCid] ?: return
        val candidates = pendingByOffer.remove(offerId) ?: return
        candidates.forEach(slot::addIceCandidate)
        if (pendingByOffer.isEmpty()) pendingRemoteIceByOfferId.remove(remoteCid)
    }

    // --- Offer Logic ---

    /**
     * True while this peer is the deferred-answer offerer awaiting its first answer from [remoteCid].
     * Gates the initial offer-timeout/ICE-restart/media-restart suppression; renegotiations after
     * the first answer behave normally.
     */
    private fun isDeferringInitialNegotiation(remoteCid: String): Boolean =
        deferInitialAnswer() && shouldIOffer(remoteCid) && remoteCid !in initialAnswerReceivedCids

    private fun shouldIOffer(remoteCid: String, roomState: RoomState? = getCurrentRoomState()): Boolean {
        val myCid = getClientId() ?: return false
        roomState ?: return false
        // Host-based offerer election is scoped to deferred-answer (PSTN) calls only. Every other
        // call keeps the historical lexicographic rule, so behavior is byte-identical to older
        // SDKs (e.g. 0.8.5) and mixed-version 1:1/group calls can never disagree on the offerer.
        // (For PSTN the app forces itself host via hostPeerId, so this elects the app.)
        if (deferInitialAnswer()) {
            val participantCids = roomState.participants.map { it.cid }.toSet()
            val hostCid = roomState.hostCid.takeIf { it in participantCids }
            if (participantCids.size <= 2 && hostCid != null) {
                return myCid == hostCid
            }
        }
        return myCid < remoteCid
    }

    private fun canOffer(slot: PeerConnectionSlotProtocol): Boolean {
        if (!isSignalingConnected()) return false
        if (!isLocalMediaReady()) return false
        if (!slot.isReady()) return false
        if (!shouldIOffer(slot.remoteCid, getCurrentRoomState())) return false
        val participant = getCurrentRoomState()?.participants?.firstOrNull { it.cid == slot.remoteCid }
        return participant?.signalingStatus == ParticipantSignalingStatus.ACTIVE
    }

    private fun isParticipantActive(remoteCid: String): Boolean {
        val participant = getCurrentRoomState()?.participants?.firstOrNull { it.cid == remoteCid }
        return participant?.signalingStatus == ParticipantSignalingStatus.ACTIVE
    }

    private fun maybeSendOffer(force: Boolean = false, iceRestart: Boolean = false) {
        getAllSlots().values.forEach { slot ->
            if (shouldIOffer(slot.remoteCid, getCurrentRoomState())) maybeSendOffer(slot, force, iceRestart)
        }
    }

    private fun maybeSendOffer(slot: PeerConnectionSlotProtocol, force: Boolean = false, iceRestart: Boolean = false) {
        if (slot.isMakingOffer) { if (iceRestart) slot.markPendingIceRestart(); return }
        if (!force && slot.sentOffer) return
        if (!canOffer(slot)) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) { if (iceRestart) slot.markPendingIceRestart(); return }
        val offerId = nextOfferId(slot.remoteCid)
        pendingLocalOfferIds[slot.remoteCid] = offerId
        acceptedRemoteOfferIds.remove(slot.remoteCid)
        ignoredOfferIds.remove(slot.remoteCid)
        slot.beginOffer()
        val started = slot.createOffer(
            iceRestart = iceRestart,
            onSdp = { sdp ->
                // Fires on the WebRTC signaling thread; the transport and the
                // offer-timeout bookkeeping are main-thread-owned.
                handler.post {
                    if (pendingLocalOfferIds[slot.remoteCid] != offerId) return@post
                    val payload = JSONObject().apply {
                        put("sdp", sdp)
                        put("offerId", offerId)
                    }
                    sendMessage("offer", payload, slot.remoteCid)
                    scheduleOfferTimeout(slot.remoteCid)
                }
            },
            onComplete = { success ->
                handler.post {
                    slot.completeOffer()
                    if (!success) {
                        pendingLocalOfferIds.remove(slot.remoteCid)
                        if (iceRestart) scheduleIceRestart(slot.remoteCid, "offer-failed", 500)
                    }
                }
            }
        )
        if (!started) {
            pendingLocalOfferIds.remove(slot.remoteCid)
            slot.completeOffer()
            if (iceRestart) slot.markPendingIceRestart()
            return
        }
        if (!force) slot.markOfferSent()
    }

    private fun handleRenegotiationNeeded(remoteCid: String) {
        val slot = getSlot(remoteCid) ?: return
        if (shouldIOffer(remoteCid, getCurrentRoomState())) {
            maybeSendOffer(slot, force = true)
        } else {
            requestPeerLocalTrackNegotiation(remoteCid, slot)
        }
    }

    private fun handleMediaRestartRequest(slot: PeerConnectionSlotProtocol, remoteCid: String, reason: String) {
        if (reason == SignalingProtocolConstants.MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION) {
            handleLocalTrackNegotiationRequest(slot, remoteCid)
            return
        }
        if (isDeferringInitialNegotiation(remoteCid)) {
            // Don't recreate the peer and re-offer before the first answer on a deferred call; a
            // (possibly out-of-order or bridge-sent) media-restart here would replace the in-flight
            // initial offer the bridge will answer at pickup. Resumes after the first answer.
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Ignoring media-restart for $remoteCid before first answer")
            return
        }
        if (!canOffer(slot)) return
        val now = clock.nowMs()
        val lastHandledAt = lastMediaRestartHandledAtByCid[remoteCid]
        if (lastHandledAt != null && now - lastHandledAt < WebRtcResilienceConstants.OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS) return
        lastMediaRestartHandledAtByCid[remoteCid] = now
        logger?.log(SerenadaLogLevel.WARNING, TAG, "Recreating peer after media restart request from $remoteCid")
        val replacement = replacePeerSlotForMediaRecovery(remoteCid) ?: return
        maybeSendOffer(replacement)
    }

    private fun handleLocalTrackNegotiationRequest(slot: PeerConnectionSlotProtocol, remoteCid: String) {
        if (!canOffer(slot) || slot.getSignalingState() != PeerConnection.SignalingState.STABLE) return
        logger?.log(SerenadaLogLevel.DEBUG, TAG, "Creating offer after peer local track negotiation request from $remoteCid")
        maybeSendOffer(slot, force = true)
    }

    // --- Outbound Media Watchdog ---

    private fun recoverStalledOutboundMedia(remoteCid: String, slot: PeerConnectionSlotProtocol) {
        val watch = outboundMediaWatchByCid.getOrPut(remoteCid) { OutboundMediaWatch() }
        if (watch.inFlight) return
        if (!isPeerMediaConnected(slot)) {
            resetOutboundMediaSample(watch)
            return
        }

        watch.inFlight = true
        slot.collectOutboundMediaSample { sample ->
            handler.post {
                if (getSlot(remoteCid) !== slot) {
                    watch.inFlight = false
                    return@post
                }
                finalizeOutboundMediaSample(remoteCid, slot, watch, sample)
            }
        }
    }

    private fun finalizeOutboundMediaSample(
        remoteCid: String,
        slot: PeerConnectionSlotProtocol,
        watch: OutboundMediaWatch,
        sample: OutboundMediaSample?,
    ) {
        try {
            if (sample == null || (!sample.expectsAudio && !sample.expectsVideo)) {
                resetOutboundMediaSample(watch)
                return
            }

            val previous = watch.lastSample
            watch.lastSample = sample
            if (previous == null) {
                watch.stallSamples = 0
                return
            }

            val videoStalled = sample.expectsVideo &&
                sample.videoBytesSent <= previous.videoBytesSent &&
                sample.videoFramesSent <= previous.videoFramesSent
            val audioOnlyStalled = !sample.expectsVideo &&
                sample.expectsAudio &&
                sample.audioBytesSent <= previous.audioBytesSent
            if (!videoStalled && !audioOnlyStalled) {
                watch.stallSamples = 0
                return
            }

            watch.stallSamples += 1
            if (watch.stallSamples < WebRtcResilienceConstants.OUTBOUND_MEDIA_STALL_SAMPLES) return

            val now = clock.nowMs()
            val lastRecoveryAt = watch.lastRecoveryAtMs
            if (lastRecoveryAt != null && now - lastRecoveryAt < WebRtcResilienceConstants.OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS) return

            watch.lastRecoveryAtMs = now
            resetOutboundMediaSample(watch)
            if (shouldIOffer(remoteCid)) {
                recreatePeerForMediaRecovery(remoteCid, "stalled outbound media")
            } else {
                requestPeerMediaRecovery(remoteCid, "stalled outbound media")
            }
        } finally {
            watch.inFlight = false
        }
    }

    private fun isPeerMediaConnected(slot: PeerConnectionSlotProtocol): Boolean =
        slot.getSignalingState() == PeerConnection.SignalingState.STABLE &&
            slot.getConnectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
            (
                slot.getIceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
                    slot.getIceConnectionState() == PeerConnection.IceConnectionState.COMPLETED
                )

    private fun resetOutboundMediaSample(watch: OutboundMediaWatch) {
        watch.lastSample = null
        watch.stallSamples = 0
    }

    private fun requestPeerMediaRecovery(remoteCid: String, reason: String) {
        if (!isSignalingConnected() || !isParticipantActive(remoteCid)) return
        logger?.log(SerenadaLogLevel.WARNING, TAG, "Requesting media restart from $remoteCid after $reason")
        val payload = JSONObject().apply { put("reason", reason) }
        sendMessage("media_restart_request", payload, remoteCid)
    }

    private fun requestPeerLocalTrackNegotiation(remoteCid: String, slot: PeerConnectionSlotProtocol) {
        if (!isSignalingConnected() || !isLocalMediaReady() || !isParticipantActive(remoteCid)) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) return
        logger?.log(SerenadaLogLevel.DEBUG, TAG, "Requesting local track negotiation offer from $remoteCid")
        val payload = JSONObject().apply {
            put("reason", SignalingProtocolConstants.MEDIA_RESTART_REASON_LOCAL_TRACK_NEGOTIATION)
        }
        sendMessage("media_restart_request", payload, remoteCid)
    }

    private fun recreatePeerForMediaRecovery(remoteCid: String, reason: String) {
        val current = getSlot(remoteCid) ?: return
        if (!canOffer(current)) return
        logger?.log(SerenadaLogLevel.WARNING, TAG, "Recreating peer after $reason for $remoteCid")
        val replacement = replacePeerSlotForMediaRecovery(remoteCid) ?: return
        maybeSendOffer(replacement)
    }

    /**
     * Capability-transition slot handling (FIX 1). Compares the per-peer
     * independent-content capability computed NOW (from the just-applied room
     * state, via [peerIndependentContentCapability]) against the capability the
     * existing slot was BUILT with ([PeerConnectionSlotProtocol.supportsIndependentContentVideo]).
     * When they differ — a peer created legacy from an early offer / no-caps
     * room_state whose caps later arrive, or the reverse — recreate the slot so it
     * re-runs role binding from scratch with the correct camera/content m-line
     * layout. Returns true when a recreate was performed (the caller skips its
     * generic offer block for this peer this pass; the recreate already re-offered
     * if we own the offer).
     *
     * NOT offerer-gated for the recreate itself: BOTH ends observe the same flip
     * in the shared room_state, so each rebuilds its own connection. Only the
     * re-offer is offerer-gated ([maybeSendOffer] → [canOffer]); the answerer's
     * fresh slot waits for the owner's re-offer. An in-progress screen share
     * re-attaches for free via the existing pending-content mechanism: the engine
     * re-runs [attachLocalTracksToSlot] on the recreated slot (owner content sender
     * via the role-state-driven attach; legacy peer via the single-sender swap).
     *
     * Flag-off byte-identical: [peerIndependentContentCapability] is always
     * `supported=false` when the flag is off, and every legacy slot is built with
     * `supportsIndependentContentVideo=false`, so the capability never flips and
     * this is inert (no recreate ever).
     */
    private fun reconcilePeerCapability(remoteCid: String): Boolean {
        val slot = getSlot(remoteCid) ?: return false
        val capableNow = peerIndependentContentCapability(remoteCid).supported
        if (capableNow == slot.supportsIndependentContentVideo) return false
        if (!isParticipantActive(remoteCid)) return false
        logger?.log(
            SerenadaLogLevel.DEBUG,
            TAG,
            "Recreating peer after independent-content capability change for $remoteCid",
        )
        val replacement = replacePeerSlotForMediaRecovery(remoteCid) ?: return true
        // Re-offer only if we are the deterministic offer owner; the answerer's
        // fresh slot waits for that re-offer. canOffer (inside maybeSendOffer)
        // enforces ownership + readiness, so a non-owner is a no-op.
        maybeSendOffer(replacement)
        return true
    }

    // --- Timers ---

    private fun scheduleOfferTimeout(remoteCid: String) {
        val slot = getSlot(remoteCid) ?: return
        clearOfferTimeout(remoteCid)
        if (isDeferringInitialNegotiation(remoteCid)) {
            // Deferred-answer call (e.g. PSTN): the first answer may arrive long after
            // OFFER_TIMEOUT_MS (human pickup). Don't arm the re-offer/ICE-restart timer for the
            // initial negotiation; otherwise we'd roll back and re-offer while merely waiting for
            // the far end to answer, and the bridge would discard the re-offer. Normal timeout
            // resumes for renegotiations once the first answer has been applied.
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Deferring initial offer-timeout for $remoteCid (awaiting first answer)")
            return
        }
        val runnable = Runnable {
            slot.cancelOfferTimeout()
            if (slot.getSignalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                slot.markPendingIceRestart()
                pendingLocalOfferIds.remove(remoteCid)
                slot.rollbackLocalDescription {
                    handler.post {
                        if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout", 0)
                    }
                }
            } else {
                if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout-stale", 0)
            }
        }
        slot.setOfferTimeoutTask(runnable)
        handler.postDelayed(runnable, WebRtcResilienceConstants.OFFER_TIMEOUT_MS)
    }

    private fun clearOfferTimeout(remoteCid: String? = null) {
        if (remoteCid != null) {
            getSlot(remoteCid)?.let { slot -> slot.offerTimeoutTask?.let { handler.removeCallbacks(it) }; slot.cancelOfferTimeout() }
        } else {
            getAllSlots().values.forEach { slot -> slot.offerTimeoutTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelOfferTimeout() }
        }
    }

    fun scheduleIceRestart(
        remoteCid: String,
        reason: String,
        delayMs: Long,
        allowBeforeFirstAnswer: Boolean = false,
    ) {
        val slot = getSlot(remoteCid) ?: return
        if (!allowBeforeFirstAnswer && isDeferringInitialNegotiation(remoteCid)) {
            // See triggerIceRestart: no re-offers/ICE-restarts before the first answer on a
            // deferred-answer call. Don't even schedule one.
            return
        }
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.iceRestartTask != null) return
        // Inside the cooldown window, defer to its expiry instead of dropping:
        // ICE state changes are edge-triggered, so a dropped restart for a
        // connection parked in FAILED would never be retried. Clamp to one
        // cooldown: nowMs() is wall-clock, so a backwards step would otherwise
        // park the restart for the full skew.
        val cooldownRemainingMs = if (slot.lastIceRestartAt > 0) {
            (slot.lastIceRestartAt + WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS - clock.nowMs())
                .coerceIn(0L, WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS)
        } else {
            0L
        }
        val runnable = Runnable { slot.cancelIceRestartTask(); triggerIceRestart(remoteCid, reason, allowBeforeFirstAnswer) }
        slot.setIceRestartTask(runnable)
        handler.postDelayed(runnable, maxOf(delayMs, cooldownRemainingMs))
    }

    private fun clearIceRestartTimer(remoteCid: String? = null) {
        if (remoteCid != null) {
            getSlot(remoteCid)?.let { slot -> slot.iceRestartTask?.let { handler.removeCallbacks(it) }; slot.cancelIceRestartTask() }
        } else {
            getAllSlots().values.forEach { slot -> slot.iceRestartTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelIceRestartTask() }
        }
    }

    private fun triggerIceRestart(
        remoteCid: String,
        reason: String,
        allowBeforeFirstAnswer: Boolean = false,
    ) {
        val slot = getSlot(remoteCid) ?: return
        if (!allowBeforeFirstAnswer && isDeferringInitialNegotiation(remoteCid)) {
            // Deferred-answer call awaiting its first answer (e.g. PSTN ringing): suppress the
            // re-offer/ICE-restart. Rolling back HAVE_LOCAL_OFFER and re-offering now (e.g. on a
            // signaling reconnect) would invalidate the in-flight initial offer the bridge will
            // answer at pickup, and the bridge ignores re-offers. Resumes after the first answer.
            logger?.log(SerenadaLogLevel.DEBUG, TAG, "Suppressing ICE restart for $remoteCid before first answer ($reason)")
            return
        }
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.isMakingOffer) { slot.markPendingIceRestart(); return }
        val signalingState = slot.getSignalingState()
        if (signalingState != PeerConnection.SignalingState.STABLE) {
            slot.markPendingIceRestart()
            if (signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                pendingLocalOfferIds.remove(remoteCid)
                rollbackStaleLocalOfferAndRetryIceRestart(slot, remoteCid, reason, allowBeforeFirstAnswer)
            }
            return
        }
        logger?.log(SerenadaLogLevel.WARNING, "Negotiation", "ICE restart triggered for $remoteCid ($reason)")
        slot.recordIceRestart(clock.nowMs())
        maybeSendOffer(slot, force = true, iceRestart = true)
    }

    private fun rollbackStaleLocalOfferAndRetryIceRestart(
        slot: PeerConnectionSlotProtocol,
        remoteCid: String,
        reason: String,
        allowBeforeFirstAnswer: Boolean = false,
    ) {
        slot.rollbackLocalDescription { success ->
            handler.post {
                if (success) {
                    val currentSlot = getSlot(remoteCid) ?: return@post
                    if (
                        currentSlot.getSignalingState() == PeerConnection.SignalingState.STABLE &&
                        (currentSlot.pendingIceRestart || allowBeforeFirstAnswer)
                    ) {
                        triggerIceRestart(remoteCid, "$reason-rollback", allowBeforeFirstAnswer)
                    }
                } else {
                    scheduleOfferTimeout(remoteCid)
                }
            }
        }
    }

    // --- Aggregate Peer State ---

    private fun updateAggregatePeerState() {
        var bestIcePri = Int.MAX_VALUE; var bestIce = "NEW"
        var bestConnPri = Int.MAX_VALUE; var bestConn = "NEW"
        var bestSigPri = Int.MAX_VALUE; var bestSig = "STABLE"
        for (slot in getAllSlots().values) {
            val ip = ICE_PRIORITY[slot.getIceConnectionState()] ?: Int.MAX_VALUE
            if (ip < bestIcePri) { bestIcePri = ip; bestIce = slot.getIceConnectionState().name }
            val cp = CONN_PRIORITY[slot.getConnectionState()] ?: Int.MAX_VALUE
            if (cp < bestConnPri) { bestConnPri = cp; bestConn = slot.getConnectionState().name }
            val sp = SIG_PRIORITY[slot.getSignalingState()] ?: Int.MAX_VALUE
            if (sp < bestSigPri) { bestSigPri = sp; bestSig = slot.getSignalingState().name }
        }
        onAggregatePeerStateChanged(
            IceConnectionState.from(bestIce),
            PeerConnectionState.from(bestConn),
            RtcSignalingState.from(bestSig),
        )
    }
}
