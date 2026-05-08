package app.serenada.core.fakes

import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaSession
import app.serenada.core.call.SessionClock
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper

internal class FakeSessionClock(private var currentTimeMs: Long = 0L) : SessionClock {
    override fun nowMs(): Long = currentTimeMs
    fun advance(byMs: Long) { currentTimeMs += byMs }
}

internal class TestSessionFactory(
    val roomId: String = "test-room-id",
    val handlesReconnection: Boolean = false,
    defaultVideoEnabled: Boolean = true,
    config: SerenadaConfig? = null,
) {
    val fakeProvider = FakeSignalingProvider(handlesReconnection = handlesReconnection)
    val fakeAudio = FakeAudioController()
    val fakeMedia = FakeMediaEngine()
    val fakeClock = FakeSessionClock()

    val session: SerenadaSession = SerenadaSession(
        roomId = roomId,
        roomUrl = null,
        config = config ?: SerenadaConfig(
            signalingProvider = fakeProvider,
            defaultVideoEnabled = defaultVideoEnabled,
        ),
        context = RuntimeEnvironment.getApplication(),
        delegate = null,
        okHttpClient = OkHttpClient(),
        initialSignalingProvider = fakeProvider,
        audioController = fakeAudio,
        mediaEngine = fakeMedia,
        clock = fakeClock,
    )

    fun startSession() {
        session.start()
    }

    fun grantPermissionsAndStart() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )
        session.start()
        ShadowLooper.idleMainLooper()
    }

    fun openSignaling(transport: String = "ws") {
        fakeProvider.simulateConnected(transport)
        ShadowLooper.idleMainLooper()
    }

    fun simulateJoinedResponse(
        cid: String = "local-cid-1",
        participants: List<Pair<String, Long>> = emptyList(),
        hostCid: String? = null,
    ) {
        val resolvedHost = hostCid ?: cid
        val resolvedParticipants = if (participants.isEmpty()) {
            listOf(cid to 1L)
        } else {
            participants
        }
        fakeProvider.simulateJoined(
            peerId = cid,
            participants = resolvedParticipants,
            hostPeerId = resolvedHost,
        )
        ShadowLooper.idleMainLooper()
    }

    fun simulateRoomState(
        participants: List<Pair<String, Long>>,
        hostCid: String,
    ) {
        fakeProvider.simulateRoomStateUpdated(
            participants = participants,
            hostPeerId = hostCid,
        )
        ShadowLooper.idleMainLooper()
    }

    fun simulateError(code: String, message: String) {
        fakeProvider.simulateError(code = code, message = message)
        ShadowLooper.idleMainLooper()
    }

    fun simulateOfferFromRemote(fromCid: String, sdp: String = "remote-offer-sdp") {
        val payload = JSONObject().apply {
            put("from", fromCid)
            put("sdp", sdp)
        }
        fakeProvider.simulateMessage(from = fromCid, type = "offer", payload = payload)
        ShadowLooper.idleMainLooper()
    }

    fun simulateAnswerFromRemote(fromCid: String, sdp: String = "remote-answer-sdp") {
        val payload = JSONObject().apply {
            put("from", fromCid)
            put("sdp", sdp)
        }
        fakeProvider.simulateMessage(from = fromCid, type = "answer", payload = payload)
        ShadowLooper.idleMainLooper()
    }

    fun simulateIceCandidateFromRemote(
        fromCid: String,
        candidate: String = "candidate:test",
        sdpMid: String? = "0",
        sdpMLineIndex: Int = 0,
    ) {
        val payload = JSONObject().apply {
            put("from", fromCid)
            put("candidate", JSONObject().apply {
                put("candidate", candidate)
                sdpMid?.let { put("sdpMid", it) }
                put("sdpMLineIndex", sdpMLineIndex)
            })
        }
        fakeProvider.simulateMessage(from = fromCid, type = "ice", payload = payload)
        ShadowLooper.idleMainLooper()
    }

    fun advanceToInCallWithTurn(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        localJoinedAt: Long = 1L,
        remoteJoinedAt: Long = 2L,
        iceServers: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                .setUsername("user")
                .setPassword("pass")
                .createIceServer()
        ),
    ) {
        fakeProvider.enqueueIceServers(Result.success(iceServers))
        grantPermissionsAndStart()
        openSignaling()
        simulateJoinedResponse(
            cid = localCid,
            participants = listOf(localCid to localJoinedAt, remoteCid to remoteJoinedAt),
            hostCid = localCid,
        )
    }

    fun tearDown() {
        session.cancelJoin()
        ShadowLooper.idleMainLooper()
    }
}
