package app.serenada.core.call

import android.os.Handler
import app.serenada.core.IceConnectionState
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import app.serenada.core.PeerConnectionState
import app.serenada.core.RtcSignalingState
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.json.JSONObject

class PeerNegotiationEngine(
    private val handler: Handler,
    private val clock: SessionClock,
    // State readers
    private val getClientId: () -> String?,
    private val getHostCid: () -> String?,
    private val getParticipantCount: () -> Int,
    private val getCurrentRoomState: () -> RoomState?,
    private val isSignalingConnected: () -> Boolean,
    private val hasIceServers: () -> Boolean,
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
    ) -> PeerConnectionSlotProtocol,
    private val engineRemoveSlot: (PeerConnectionSlotProtocol) -> Unit,
    // Callbacks to session
    private val sendMessage: (String, JSONObject?, String?) -> Unit,
    private val onRemoteParticipantsChanged: () -> Unit,
    private val onAggregatePeerStateChanged: (IceConnectionState, PeerConnectionState, RtcSignalingState) -> Unit,
    private val onConnectionStatusUpdate: () -> Unit,
    private val logger: SerenadaLogger? = null,
) {
    companion object {
        private const val TAG = "PeerNegotiationEngine"
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

    // --- Public API ---

    fun syncPeers(roomState: RoomState) {
        val myCid = getClientId()
        val remotePeers = roomState.participants.filter { it.cid != myCid }
        val remoteCids = remotePeers.map { it.cid }.toSet()

        getAllSlots().keys.filter { it !in remoteCids }.forEach { removePeerSlot(it) }
        if (remotePeers.isEmpty()) {
            clearOfferTimeout()
            clearIceRestartTimer()
            clearNonHostOfferFallback()
        }

        remotePeers.forEach { participant ->
            val slot = getOrCreateSlot(participant.cid)
            slot.ensurePeerConnection()
            if (shouldIOffer(participant.cid, roomState)) {
                clearNonHostOfferFallback(participant.cid)
                maybeSendOffer(slot)
            } else {
                maybeScheduleNonHostOfferFallback(participant.cid, "participants")
            }
        }

        updateAggregatePeerState()
    }

    fun processSignalingPayload(msg: SignalingMessage) {
        val fromCid = msg.payload?.optString("from").orEmpty().ifBlank { return }
        val slot = getOrCreateSlot(fromCid)
        if (!slot.isReady() && !slot.ensurePeerConnection()) {
            return
        }
        when (msg.type) {
            "offer" -> {
                clearNonHostOfferFallback(fromCid)
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                slot.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    slot.createAnswer(onSdp = { answerSdp ->
                        val payload = JSONObject().apply { put("sdp", answerSdp) }
                        sendMessage("answer", payload, fromCid)
                    })
                }
            }
            "answer" -> {
                clearNonHostOfferFallback(fromCid)
                val sdp = msg.payload?.optString("sdp").orEmpty().ifBlank { return }
                slot.setRemoteDescription(SessionDescription.Type.ANSWER, sdp) {
                    clearOfferTimeout(fromCid)
                    slot.clearPendingIceRestart()
                    updateAggregatePeerState()
                    onConnectionStatusUpdate()
                }
            }
            "ice" -> {
                val candidateJson = msg.payload?.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    candidateJson.optString("sdpMid").ifBlank { null },
                    candidateJson.optInt("sdpMLineIndex", 0),
                    candidateJson.optString("candidate", "")
                )
                slot.addIceCandidate(candidate)
            }
        }
    }

    fun onIceServersReady() {
        maybeSendOffer()
        getAllSlots().values.forEach { if (!shouldIOffer(it.remoteCid)) maybeScheduleNonHostOfferFallback(it.remoteCid, "ice-ready") }
    }

    fun scheduleIceRestart(reason: String, delayMs: Long) {
        getAllSlots().values.forEach { if (shouldIOffer(it.remoteCid)) scheduleIceRestart(it.remoteCid, reason, delayMs) }
    }

    fun triggerIceRestart(reason: String) {
        getAllSlots().values.forEach { if (shouldIOffer(it.remoteCid)) triggerIceRestart(it.remoteCid, reason) }
    }

    fun resetAll() {
        clearOfferTimeout()
        clearIceRestartTimer()
        clearNonHostOfferFallback()
    }

    // --- Slot Lifecycle ---

    private fun getOrCreateSlot(remoteCid: String): PeerConnectionSlotProtocol {
        getSlot(remoteCid)?.let { return it }
        val slot = createSlotViaEngine(
            remoteCid,
            { cid: String, candidate: IceCandidate ->
                val payload = JSONObject().apply {
                    val candidateJson = JSONObject()
                    candidateJson.put("candidate", candidate.sdp)
                    candidateJson.put("sdpMid", candidate.sdpMid)
                    candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidateJson)
                }
                sendMessage("ice", payload, cid)
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
                    getSlot(cid)?.let { maybeSendOffer(it, force = true) }
                }
            }
        )
        setSlot(remoteCid, slot)
        return slot
    }

    private fun removePeerSlot(remoteCid: String) {
        clearOfferTimeout(remoteCid)
        clearIceRestartTimer(remoteCid)
        clearNonHostOfferFallback(remoteCid)
        val slot = removeSlotEntry(remoteCid) ?: return
        engineRemoveSlot(slot)
        slot.closePeerConnection()
    }

    // --- Offer Logic ---

    private fun shouldIOffer(remoteCid: String, roomState: RoomState? = getCurrentRoomState()): Boolean {
        val state = roomState ?: return false
        val myCid = getClientId() ?: return false
        val myJoinedAt = state.participants.find { it.cid == myCid }?.joinedAt ?: 0L
        val theirJoinedAt = state.participants.find { it.cid == remoteCid }?.joinedAt ?: 0L
        return myJoinedAt < theirJoinedAt || (myJoinedAt == theirJoinedAt && myCid < remoteCid)
    }

    private fun canOffer(slot: PeerConnectionSlotProtocol): Boolean {
        if (!isSignalingConnected()) return false
        if (!slot.isReady()) return false
        if (!shouldIOffer(slot.remoteCid, getCurrentRoomState())) return false
        val participantCids = getCurrentRoomState()?.participants?.map { it.cid }?.toSet() ?: emptySet()
        return slot.remoteCid in participantCids
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
        slot.beginOffer()
        val started = slot.createOffer(
            iceRestart = iceRestart,
            onSdp = { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp) }
                sendMessage("offer", payload, slot.remoteCid)
                scheduleOfferTimeout(slot.remoteCid)
            },
            onComplete = { success ->
                handler.post {
                    slot.completeOffer()
                    if (!success && iceRestart) scheduleIceRestart(slot.remoteCid, "offer-failed", 500)
                }
            }
        )
        if (!started) { slot.completeOffer(); if (iceRestart) slot.markPendingIceRestart(); return }
        if (!force) slot.markOfferSent()
    }

    // --- Timers ---

    private fun scheduleOfferTimeout(remoteCid: String) {
        val slot = getSlot(remoteCid) ?: return
        clearOfferTimeout(remoteCid)
        val runnable = Runnable {
            slot.cancelOfferTimeout()
            if (slot.getSignalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                slot.markPendingIceRestart()
                slot.rollbackLocalDescription {
                    handler.post {
                        if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout", 0)
                        else maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout")
                    }
                }
            } else {
                if (shouldIOffer(remoteCid)) scheduleIceRestart(remoteCid, "offer-timeout-stale", 0)
                else maybeScheduleNonHostOfferFallback(remoteCid, "offer-timeout-stale")
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

    fun scheduleIceRestart(remoteCid: String, reason: String, delayMs: Long) {
        val slot = getSlot(remoteCid) ?: return
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.iceRestartTask != null) return
        if (slot.lastIceRestartAt > 0 && clock.nowMs() - slot.lastIceRestartAt < WebRtcResilienceConstants.ICE_RESTART_COOLDOWN_MS) return
        val runnable = Runnable { slot.cancelIceRestartTask(); triggerIceRestart(remoteCid, reason) }
        slot.setIceRestartTask(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearIceRestartTimer(remoteCid: String? = null) {
        if (remoteCid != null) {
            getSlot(remoteCid)?.let { slot -> slot.iceRestartTask?.let { handler.removeCallbacks(it) }; slot.cancelIceRestartTask() }
        } else {
            getAllSlots().values.forEach { slot -> slot.iceRestartTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelIceRestartTask() }
        }
    }

    private fun triggerIceRestart(remoteCid: String, reason: String) {
        val slot = getSlot(remoteCid) ?: return
        if (!canOffer(slot)) { slot.markPendingIceRestart(); return }
        if (slot.isMakingOffer) { slot.markPendingIceRestart(); return }
        logger?.log(SerenadaLogLevel.WARNING, "Negotiation", "ICE restart triggered for $remoteCid ($reason)")
        slot.recordIceRestart(clock.nowMs())
        maybeSendOffer(slot, force = true, iceRestart = true)
    }

    private fun maybeScheduleNonHostOfferFallback(remoteCid: String, reason: String) {
        val slot = getSlot(remoteCid) ?: return
        if (shouldIOffer(remoteCid)) { clearNonHostOfferFallback(remoteCid); return }
        if (!isSignalingConnected()) return
        if (slot.nonHostFallbackTask != null) return
        if (slot.nonHostFallbackAttempts >= WebRtcResilienceConstants.NON_HOST_FALLBACK_MAX_ATTEMPTS) return
        val runnable = Runnable {
            slot.clearNonHostFallbackTask()
            slot.incrementNonHostFallbackAttempts()
            logger?.log(SerenadaLogLevel.WARNING, "Negotiation", "Non-host offer fallback for $remoteCid ($reason)")
            maybeSendNonHostFallbackOffer(remoteCid)
        }
        slot.setNonHostFallbackTask(runnable)
        handler.postDelayed(runnable, WebRtcResilienceConstants.NON_HOST_FALLBACK_DELAY_MS)
    }

    private fun clearNonHostOfferFallback(remoteCid: String? = null) {
        if (remoteCid != null) {
            getSlot(remoteCid)?.let { slot -> slot.nonHostFallbackTask?.let { handler.removeCallbacks(it) }; slot.cancelNonHostFallbackTask() }
        } else {
            getAllSlots().values.forEach { slot -> slot.nonHostFallbackTask?.let { r -> handler.removeCallbacks(r) }; slot.cancelNonHostFallbackTask() }
        }
    }

    private fun maybeSendNonHostFallbackOffer(remoteCid: String) {
        val slot = getSlot(remoteCid) ?: return
        if (shouldIOffer(remoteCid)) return
        if (!isSignalingConnected()) return
        if (!slot.isReady() && !slot.ensurePeerConnection()) return
        if (slot.getSignalingState() != PeerConnection.SignalingState.STABLE) return
        if (slot.hasRemoteDescription()) return
        if (slot.isMakingOffer) return
        slot.beginOffer()
        val started = slot.createOffer(
            onSdp = { sdp ->
                sendMessage("offer", JSONObject().apply { put("sdp", sdp) }, remoteCid)
                scheduleOfferTimeout(remoteCid)
            },
            onComplete = { success ->
                handler.post { slot.completeOffer(); if (!success) maybeScheduleNonHostOfferFallback(remoteCid, "offer-failed") }
            }
        )
        if (!started) { slot.completeOffer(); maybeScheduleNonHostOfferFallback(remoteCid, "offer-not-started") }
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
