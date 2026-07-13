package app.serenada.core.fakes

import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaSession
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.SessionClock
import app.serenada.core.call.audioCoordinatorTeardownFence
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper

internal class FakeSessionClock(private var currentTimeMs: Long = 0L) : SessionClock {
    override fun nowMs(): Long = currentTimeMs
    // Monotonic advances in lockstep with wall-clock for deterministic tests.
    override fun monotonicMs(): Long = currentTimeMs
    fun advance(byMs: Long) { currentTimeMs += byMs }
}

internal class TestSessionFactory(
    val roomId: String = "test-room-id",
    val handlesReconnection: Boolean = false,
    defaultVideoEnabled: Boolean = true,
    videoMediaEnabled: Boolean = true,
    enableIndependentContentVideo: Boolean = false,
    cameraModes: List<LocalCameraMode>? = null,
    deferInitialAnswer: Boolean = false,
    audioCoordinator: SerenadaAudioCoordinator? = null,
    config: SerenadaConfig? = null,
    delegate: app.serenada.core.SerenadaCoreDelegate? = null,
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
            videoMediaEnabled = videoMediaEnabled,
            enableIndependentContentVideo = enableIndependentContentVideo,
            cameraModes = cameraModes,
            deferInitialAnswer = deferInitialAnswer,
            audioCoordinator = audioCoordinator,
        ),
        context = RuntimeEnvironment.getApplication(),
        delegate = delegate?.let { d -> { d } },
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

    fun simulateOfferFromRemote(fromCid: String, sdp: String = "remote-offer-sdp", offerId: String? = null) {
        val payload = JSONObject().apply {
            put("from", fromCid)
            put("sdp", sdp)
            offerId?.let { put("offerId", it) }
        }
        fakeProvider.simulateMessage(from = fromCid, type = "offer", payload = payload)
        ShadowLooper.idleMainLooper()
    }

    fun simulateAnswerFromRemote(fromCid: String, sdp: String = "remote-answer-sdp", offerId: String? = null) {
        val payload = JSONObject().apply {
            put("from", fromCid)
            put("sdp", sdp)
            offerId?.let { put("offerId", it) }
        }
        fakeProvider.simulateMessage(from = fromCid, type = "answer", payload = payload)
        ShadowLooper.idleMainLooper()
    }

    fun simulateIceCandidateFromRemote(
        fromCid: String,
        candidate: String = "candidate:test",
        sdpMid: String? = "0",
        sdpMLineIndex: Int = 0,
        offerId: String? = null,
    ) {
        val payload = JSONObject().apply {
            put("from", fromCid)
            offerId?.let { put("offerId", it) }
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
        hostCid: String = minOf(localCid, remoteCid),
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
            hostCid = hostCid,
        )
    }

    /**
     * Drive to in-call with a SINGLE remote peer whose advertised capabilities
     * are explicit, so independent-content per-peer routing can be exercised at
     * the session level. [remoteIndependentCapable] controls
     * `capabilities.independentContentVideo`; [remoteVideoMediaEnabled] controls
     * the peer's `mediaPolicy.videoMediaEnabled`.
     */
    fun advanceToInCallWithCapablePeer(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-2",
        remoteIndependentCapable: Boolean = true,
        remoteVideoMediaEnabled: Boolean = true,
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
            participants = listOf(localCid to 1L),
            hostCid = localCid,
        )
        simulateRoomStateWithCapabilities(
            participants = listOf(
                app.serenada.core.SignalingProviderParticipant(peerId = localCid, joinedAt = 1L),
                app.serenada.core.SignalingProviderParticipant(
                    peerId = remoteCid,
                    joinedAt = 2L,
                    capabilities = if (remoteIndependentCapable) {
                        app.serenada.core.SignalingProviderParticipantCapabilities(independentContentVideo = true)
                    } else {
                        null
                    },
                    mediaPolicy = app.serenada.core.SignalingProviderParticipantMediaPolicy(
                        videoMediaEnabled = remoteVideoMediaEnabled,
                    ),
                ),
            ),
            hostCid = minOf(localCid, remoteCid),
        )
    }

    fun simulateRoomStateWithCapabilities(
        participants: List<app.serenada.core.SignalingProviderParticipant>,
        hostCid: String,
    ) {
        fakeProvider.simulateRoomStateUpdatedWith(
            participants = participants,
            hostPeerId = hostCid,
        )
        ShadowLooper.idleMainLooper()
    }

    fun tearDown() {
        session.close()
        val deadlineNs = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (audioCoordinatorTeardownFence.hasPending() && System.nanoTime() < deadlineNs) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        check(!audioCoordinatorTeardownFence.hasPending()) {
            "Audio coordinator teardown did not complete during test cleanup"
        }
    }
}
