package app.serenada.core.fakes

import app.serenada.core.ForegroundMediaArbiter
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaSession
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.SerenadaAudioCoordinator
import app.serenada.core.call.SessionClock
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
    // Multi-call session (Phase 2). A HELD initial role creates a held call that
    // owns no capture/lease (registry-internal join). acquireForegroundLease
    // routes the foreground join through the process-wide arbiter (mode DIRECT),
    // mirroring the public SerenadaCore.join() path.
    initialMediaRole: CallMediaRole = CallMediaRole.FOREGROUND,
    acquireForegroundLease: Boolean = false,
    // Reset the process-global arbiter on construction (default). A test that needs
    // TWO coexisting sessions (e.g. a second direct join failing while the first is
    // live) opts OUT so the first session's lease survives the second's construction.
    resetArbiterOnInit: Boolean = true,
) {
    init {
        // Reset the PROCESS-GLOBAL arbiter before each session is built so a
        // lease/mode held by a prior (sequential) Robolectric test session cannot
        // make this one fail to acquire (contract §2 / Phase 2 green-gate note).
        if (resetArbiterOnInit) ForegroundMediaArbiter.resetForTests()
    }

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
        initialMediaRole = initialMediaRole,
        acquireForegroundLease = acquireForegroundLease,
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
        reconnectToken: String? = null,
        reconnectTokenTTLMs: Long? = null,
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
            reconnectToken = reconnectToken,
            reconnectTokenTTLMs = reconnectTokenTTLMs,
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
        reconnectToken: String? = null,
        reconnectTokenTTLMs: Long? = null,
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
            reconnectToken = reconnectToken,
            reconnectTokenTTLMs = reconnectTokenTTLMs,
        )
    }

    /**
     * Drive a HELD-initial session to in-call (multi-call session, Phase 2). The
     * session was constructed with `initialMediaRole = HELD`: it owns no capture
     * and never activates the audio coordinator. Mirrors [advanceToInCallWithTurn]
     * but does NOT depend on permission grants (the held path skips the permission
     * gate). Use to assert held-without-capture invariants.
     */
    fun advanceToHeldInCall(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        localJoinedAt: Long = 1L,
        remoteJoinedAt: Long = 2L,
        hostCid: String = minOf(localCid, remoteCid),
        reconnectToken: String? = null,
        reconnectTokenTTLMs: Long? = null,
        iceServers: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                .setUsername("user")
                .setPassword("pass")
                .createIceServer()
        ),
    ) {
        fakeProvider.enqueueIceServers(Result.success(iceServers))
        session.start()
        ShadowLooper.idleMainLooper()
        openSignaling()
        simulateJoinedResponse(
            cid = localCid,
            participants = listOf(localCid to localJoinedAt, remoteCid to remoteJoinedAt),
            hostCid = hostCid,
            reconnectToken = reconnectToken,
            reconnectTokenTTLMs = reconnectTokenTTLMs,
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
        ShadowLooper.idleMainLooper()
        // Drop any lease/mode this session still held so the next sequential test
        // starts with a clean process-global arbiter.
        ForegroundMediaArbiter.resetForTests()
    }
}
