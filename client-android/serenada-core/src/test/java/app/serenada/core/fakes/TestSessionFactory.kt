package app.serenada.core.fakes

import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaSession
import app.serenada.core.call.SignalingMessage
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper

class TestSessionFactory(
    val roomId: String = "test-room-id",
    val serverHost: String = "test.serenada.app",
    config: SerenadaConfig? = null,
) {
    val fakeSignaling = FakeSignaling()
    val fakeAPI = FakeAPIClient()
    val fakeAudio = FakeAudioController()
    val fakeMedia = FakeMediaEngine()

    val session: SerenadaSession = SerenadaSession(
        roomId = roomId,
        roomUrl = null,
        serverHost = serverHost,
        config = config ?: SerenadaConfig(serverHost = serverHost),
        context = RuntimeEnvironment.getApplication(),
        delegate = null,
        okHttpClient = OkHttpClient(),
        signaling = fakeSignaling,
        apiClient = fakeAPI,
        audioController = fakeAudio,
        mediaEngine = fakeMedia,
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
        fakeSignaling.simulateOpen(transport)
        ShadowLooper.idleMainLooper()
    }

    fun simulateJoinedResponse(
        cid: String = "local-cid-1",
        participants: List<Pair<String, Long>> = emptyList(),
        hostCid: String? = null,
        turnToken: String? = null,
    ) {
        val payload = JSONObject()
        val resolvedHost = hostCid ?: cid
        payload.put("hostCid", resolvedHost)

        val participantsArray = JSONArray()
        if (participants.isEmpty()) {
            participantsArray.put(JSONObject().apply {
                put("cid", cid)
                put("joinedAt", 1L)
            })
        } else {
            for ((pCid, joinedAt) in participants) {
                participantsArray.put(JSONObject().apply {
                    put("cid", pCid)
                    put("joinedAt", joinedAt)
                })
            }
        }
        payload.put("participants", participantsArray)

        if (turnToken != null) {
            payload.put("turnToken", turnToken)
        }

        val msg = SignalingMessage(
            type = "joined",
            rid = roomId,
            sid = null,
            cid = cid,
            to = null,
            payload = payload,
        )
        fakeSignaling.simulateMessage(msg)
        ShadowLooper.idleMainLooper()
    }

    fun simulateRoomState(
        participants: List<Pair<String, Long>>,
        hostCid: String,
    ) {
        val payload = JSONObject()
        payload.put("hostCid", hostCid)
        val participantsArray = JSONArray()
        for ((cid, joinedAt) in participants) {
            participantsArray.put(JSONObject().apply {
                put("cid", cid)
                put("joinedAt", joinedAt)
            })
        }
        payload.put("participants", participantsArray)

        val msg = SignalingMessage(
            type = "room_state",
            rid = roomId,
            sid = null,
            cid = null,
            to = null,
            payload = payload,
        )
        fakeSignaling.simulateMessage(msg)
        ShadowLooper.idleMainLooper()
    }

    fun simulateError(code: String, message: String) {
        val payload = JSONObject().apply {
            put("code", code)
            put("message", message)
        }
        val msg = SignalingMessage(
            type = "error",
            rid = roomId,
            sid = null,
            cid = null,
            to = null,
            payload = payload,
        )
        fakeSignaling.simulateMessage(msg)
        ShadowLooper.idleMainLooper()
    }

    fun tearDown() {
        session.cancelJoin()
        ShadowLooper.idleMainLooper()
    }
}
