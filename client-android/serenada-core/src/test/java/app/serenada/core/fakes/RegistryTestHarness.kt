package app.serenada.core.fakes

import app.serenada.core.ForegroundMediaArbiter
import app.serenada.core.JoinAndSwitchResult
import app.serenada.core.JoinResult
import app.serenada.core.RoomRef
import app.serenada.core.SerenadaCallRegistry
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaSession
import app.serenada.core.SwitchResult
import app.serenada.core.call.CallMediaRole
import app.serenada.core.call.SerenadaAudioCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import org.webrtc.PeerConnection

/**
 * One fake-backed managed session created by the registry under test. Bundles the
 * session with its fakes so a test can assert per-call media/coordinator behavior.
 */
internal class FakeManagedSession(
    val roomId: String,
    val coordinator: CountingTestCoordinator,
    val fakeProvider: FakeSignalingProvider,
    val fakeAudio: FakeAudioController,
    val fakeMedia: FakeMediaEngine,
    val session: SerenadaSession,
) {
    /**
     * Drive this held session's signaling to settle (Joining -> Waiting/InCall) so
     * the registry's bounded held-join await resolves. [peers] is the number of
     * participants reported in the joined event (>=2 => InCall).
     */
    fun settle(localCid: String = "cid-$roomId", peers: Int = 1) {
        fakeProvider.simulateConnected("ws")
        ShadowLooper.idleMainLooper()
        val participants = buildList {
            add(localCid to 1L)
            for (i in 1 until peers) add("remote-$roomId-$i" to (i + 1).toLong())
        }
        fakeProvider.simulateJoined(
            peerId = localCid,
            participants = participants,
            hostPeerId = localCid,
        )
        ShadowLooper.idleMainLooper()
    }

    /** Force the held room join to fail (the registry returns JoinFailed). */
    fun fail(code: String = "join_failed", message: String = "boom") {
        fakeProvider.simulateError(code, message)
        ShadowLooper.idleMainLooper()
    }
}

/**
 * Test harness for [SerenadaCallRegistry]. Supplies the registry's internal
 * session-factory seam with fake-backed sessions so each created managed call is
 * fully observable (per-call coordinator/media/provider) without real WebRTC,
 * permissions, or network. Each registry gets a DEDICATED process-global arbiter
 * (reset on construction) so a lease held by a prior sequential test cannot leak
 * in (contract §2 / Phase 2 green-gate note).
 */
internal class RegistryTestHarness(
    private val grantPermissions: Boolean = true,
    private val defaultVideoEnabled: Boolean = false,
) {
    /** Created sessions, in creation order, keyed by canonical room id. */
    val created = LinkedHashMap<String, FakeManagedSession>()

    init {
        ForegroundMediaArbiter.resetForTests()
        if (grantPermissions) {
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
            )
        }
    }

    val registry: SerenadaCallRegistry = SerenadaCallRegistry(
        core = null,
        sessionFactory = { room, role -> buildSession(room, role) },
    )

    // Same Robolectric main looper the registry/session scopes use.
    private val opScope = CoroutineScope(Dispatchers.Main.immediate)

    /**
     * Idle the main looper until [deferred] completes, then return its value.
     * Advances virtual time in steps so a registry `withTimeout` (release/activate
     * drain) fires deterministically when a test injects a hung release/activation.
     */
    private fun <T> drain(deferred: Deferred<T>): T {
        var guard = 0
        while (!deferred.isCompleted && guard < 200) {
            ShadowLooper.idleMainLooper()
            if (!deferred.isCompleted) {
                // Advance past the longest registry timeout (activate, 12s) so any
                // pending withTimeout cancels.
                Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(13_000L))
            }
            guard++
        }
        check(deferred.isCompleted) { "registry op did not complete" }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return deferred.getCompleted()
    }

    /**
     * Run [SerenadaCallRegistry.joinHeld]: launch the op, idle so part A creates +
     * starts the held session, settle that session's signaling so the bounded
     * held-join await resolves, then drain to the result. The newly created
     * session is settled with [peers] participants.
     */
    fun joinHeld(room: RoomRef, peers: Int = 1): JoinResult {
        val before = created.keys.toSet()
        val deferred = opScope.async { registry.joinHeld(room) }
        ShadowLooper.idleMainLooper()
        settleNewlyCreated(before, peers)
        return drain(deferred)
    }

    /** Run [SerenadaCallRegistry.joinAndSwitch] with the same settle orchestration. */
    fun joinAndSwitch(room: RoomRef, peers: Int = 1): JoinAndSwitchResult {
        val before = created.keys.toSet()
        val deferred = opScope.async { registry.joinAndSwitch(room) }
        ShadowLooper.idleMainLooper()
        settleNewlyCreated(before, peers)
        return drain(deferred)
    }

    /**
     * Run [SerenadaCallRegistry.joinHeld] but make the held room join FAIL instead
     * of settling (the session is driven to Error). Returns the [JoinResult].
     */
    fun joinHeldFailing(room: RoomRef): JoinResult {
        val before = created.keys.toSet()
        val deferred = opScope.async { registry.joinHeld(room) }
        ShadowLooper.idleMainLooper()
        newlyCreated(before)?.fail()
        return drain(deferred)
    }

    /** Run [SerenadaCallRegistry.joinAndSwitch] but make the held room join FAIL. */
    fun joinAndSwitchFailing(room: RoomRef): JoinAndSwitchResult {
        val before = created.keys.toSet()
        val deferred = opScope.async { registry.joinAndSwitch(room) }
        ShadowLooper.idleMainLooper()
        newlyCreated(before)?.fail()
        return drain(deferred)
    }

    fun switchToCall(callId: String): SwitchResult {
        val deferred = opScope.async { registry.switchToCall(callId) }
        return drain(deferred)
    }

    fun holdCall(callId: String) {
        drain(opScope.async { registry.holdCall(callId) })
    }

    fun leaveCall(callId: String) {
        drain(opScope.async { registry.leaveCall(callId) })
    }

    fun endCall(callId: String) {
        drain(opScope.async { registry.endCall(callId) })
    }

    fun dismissCall(callId: String) {
        drain(opScope.async { registry.dismissCall(callId) })
    }

    private fun newlyCreated(before: Set<String>): FakeManagedSession? {
        val newKey = created.keys.firstOrNull { it !in before } ?: return null
        return created[newKey]
    }

    private fun settleNewlyCreated(before: Set<String>, peers: Int) {
        newlyCreated(before)?.settle(peers = peers)
    }

    private fun roomKeyFor(room: RoomRef): String = when (room) {
        is RoomRef.Id -> room.roomId
        is RoomRef.Url -> room.url
    }

    private fun roomIdFor(room: RoomRef): String = when (room) {
        is RoomRef.Id -> room.roomId
        is RoomRef.Url -> room.url.trimEnd('/').substringAfterLast('/')
    }

    private fun buildSession(room: RoomRef, role: CallMediaRole): SerenadaSession {
        val roomId = roomIdFor(room)
        val coordinator = CountingTestCoordinator()
        val fakeProvider = FakeSignalingProvider()
        val fakeAudio = FakeAudioController()
        val fakeMedia = FakeMediaEngine()
        fakeProvider.enqueueIceServers(
            Result.success(
                listOf(
                    PeerConnection.IceServer.builder("turn:turn.example.com:3478")
                        .setUsername("user")
                        .setPassword("pass")
                        .createIceServer(),
                ),
            ),
        )
        val session = SerenadaSession(
            roomId = roomId,
            roomUrl = null,
            config = SerenadaConfig(
                signalingProvider = fakeProvider,
                defaultVideoEnabled = defaultVideoEnabled,
                videoMediaEnabled = true,
                audioCoordinator = coordinator,
            ),
            context = RuntimeEnvironment.getApplication(),
            delegate = null,
            okHttpClient = OkHttpClient(),
            initialSignalingProvider = fakeProvider,
            audioController = fakeAudio,
            mediaEngine = fakeMedia,
            clock = FakeSessionClock(),
            initialMediaRole = role,
            // The registry owns the lease; the session must NOT self-acquire.
            acquireForegroundLease = false,
        )
        // Mirror SerenadaCore.joinInternal: start the session immediately.
        session.start()
        created[roomKeyFor(room)] = FakeManagedSession(
            roomId = roomId,
            coordinator = coordinator,
            fakeProvider = fakeProvider,
            fakeAudio = fakeAudio,
            fakeMedia = fakeMedia,
            session = session,
        )
        return session
    }

    fun tearDown() {
        runCatching { registry.close() }
        ShadowLooper.idleMainLooper()
        ForegroundMediaArbiter.resetForTests()
    }
}

/** Audio coordinator that counts activate/deactivate calls; never suspends. */
internal class CountingTestCoordinator : SerenadaAudioCoordinator {
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    /** Append-only log of "activate"/"deactivate" to assert ORDERING across calls. */
    val ops = mutableListOf<String>()

    override suspend fun activateCallSession(intent: app.serenada.core.call.AudioIntent) {
        activateCalls += 1
        ops += "activate"
    }

    override suspend fun deactivateCallSession() {
        deactivateCalls += 1
        ops += "deactivate"
    }

    override suspend fun applyRouting(device: app.serenada.core.call.AudioDevice) {}
    override suspend fun setMicMuted(muted: Boolean) {}

    override val availableDevices =
        kotlinx.coroutines.flow.MutableStateFlow<List<app.serenada.core.call.AudioDevice>>(emptyList())
    override val effectiveInputDevice =
        kotlinx.coroutines.flow.MutableStateFlow<app.serenada.core.call.AudioDevice?>(null)
    override val effectiveOutputDevice =
        kotlinx.coroutines.flow.MutableStateFlow<app.serenada.core.call.AudioDevice?>(null)
    override val events =
        kotlinx.coroutines.flow.MutableSharedFlow<app.serenada.core.call.AudioCoordinatorEvent>()
}
