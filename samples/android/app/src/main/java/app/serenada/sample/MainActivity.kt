package app.serenada.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.serenada.callui.SerenadaCallFlow
import app.serenada.callui.SerenadaCallFlowConfig
import app.serenada.core.ConnectionInfo
import app.serenada.core.JoinOptions
import app.serenada.core.JoinedEvent
import app.serenada.core.PeerEvent
import app.serenada.core.PeerMessage
import app.serenada.core.RoomEndedEvent
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaSession
import app.serenada.core.SignalingProvider
import app.serenada.core.SignalingProviderParticipant
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.PeerConnection

private val sampleCallFlowConfig = SerenadaCallFlowConfig(
    screenSharingEnabled = false,
    inviteControlsEnabled = false,
)

private data class ProviderDemoSession(
    val session: SerenadaSession,
    val provider: SampleMockSignalingProvider,
)

class MainActivity : ComponentActivity() {
    private lateinit var serenada: SerenadaCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serenada = SerenadaCore(
            config = SerenadaConfig(serverHost = "serenada.app"),
            context = this,
        )
        setContent {
            MaterialTheme {
                SampleApp(serenada = serenada)
            }
        }
    }
}

@Composable
private fun SampleApp(serenada: SerenadaCore) {
    var callUrl by remember { mutableStateOf<String?>(null) }
    var providerDemo by remember { mutableStateOf<ProviderDemoSession?>(null) }
    val context = LocalContext.current

    when {
        callUrl != null -> SerenadaCallFlow(
            url = callUrl!!,
            config = sampleCallFlowConfig,
            onDismiss = { callUrl = null },
        )

        providerDemo != null -> ProviderDemoScreen(
            demo = providerDemo!!,
            onDismiss = {
                providerDemo?.session?.leave()
                providerDemo = null
            },
        )

        else -> HomeScreen(
            onJoinUrl = { callUrl = it },
            onStartProviderDemo = {
                val provider = SampleMockSignalingProvider()
                val providerCore = SerenadaCore(
                    config = SerenadaConfig(signalingProvider = provider),
                    context = context,
                )
                providerDemo = ProviderDemoSession(
                    session = providerCore.join(roomId = "sample-provider-room"),
                    provider = provider,
                )
            },
            serenada = serenada,
        )
    }
}

@Composable
private fun HomeScreen(
    onJoinUrl: (String) -> Unit,
    onStartProviderDemo: () -> Unit,
    serenada: SerenadaCore,
) {
    var urlText by remember { mutableStateOf("") }
    var isCreatingRoom by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastCreatedRoomUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Serenada Android Sample", style = MaterialTheme.typography.headlineLarge)

        Text(
            "Demonstrates both the built-in Serenada signaling path and a provider-mode smoke demo backed by a local in-memory SignalingProvider.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Built-In Signaling", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Paste a call URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Button(
                    onClick = {
                        errorMessage = null
                        onJoinUrl(urlText)
                    },
                    enabled = urlText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Join Call")
                }

                OutlinedButton(
                    onClick = {
                        errorMessage = null
                        isCreatingRoom = true
                        scope.launch {
                            runCatching { serenada.createRoom() }
                                .onSuccess { result ->
                                    isCreatingRoom = false
                                    lastCreatedRoomUrl = result.roomUrl
                                    onJoinUrl(result.roomUrl)
                                }
                                .onFailure { error ->
                                    isCreatingRoom = false
                                    errorMessage = error.message ?: "Failed to create room"
                                }
                        }
                    },
                    enabled = !isCreatingRoom,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isCreatingRoom) "Creating..." else "Create New Call")
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Custom Provider Smoke Demo", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Starts a session with SerenadaConfig(signalingProvider = ...) and uses incremental peerJoined plus peer-message events without Serenada transport.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onStartProviderDemo,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start Mock Provider Demo")
                }
            }
        }

        if (lastCreatedRoomUrl != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Latest room URL", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        lastCreatedRoomUrl!!,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (errorMessage != null) {
            Text(
                errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProviderDemoScreen(
    demo: ProviderDemoSession,
    onDismiss: () -> Unit,
) {
    val state by demo.session.state.collectAsState()
    val eventLog = demo.provider.eventLog.toList()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Custom Provider Demo", style = MaterialTheme.typography.headlineLarge)

        Text(
            "This screen is driven by a local in-memory SignalingProvider. The session uses provider mode only; no Serenada server APIs or room watcher calls are involved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Session State", style = MaterialTheme.typography.titleMedium)
                Text("Phase: ${state.phase}")
                Text("Participant count: ${state.participantCount}")
                Text("Local CID: ${state.localCid ?: "pending"}")
                Text(
                    "Remote peers: ${
                        state.remoteParticipants.joinToString { it.cid }.ifBlank { "none" }
                    }"
                )
                Text("Is host: ${state.isHost}")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider Event Log", style = MaterialTheme.typography.titleMedium)
                Text(
                    eventLog.joinToString(separator = "\n").ifBlank { "Waiting for provider events..." },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("End Demo")
        }
    }
}

private class SampleMockSignalingProvider : SignalingProvider {
    override var listener: SignalingProvider.Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scheduledTasks = mutableListOf<Runnable>()
    private val remotePeerId = "sample-remote"
    private var localPeerId = "sample-local"

    val eventLog = mutableStateListOf<String>()

    override fun connect() {
        record("connect() -> connected transport=mock")
        listener?.onConnected(ConnectionInfo(transport = "mock"))
    }

    override fun disconnect() {
        clearScheduledTasks()
        record("disconnect()")
    }

    override fun joinRoom(roomId: String, options: JoinOptions) {
        clearScheduledTasks()
        localPeerId = options.reconnectPeerId ?: "sample-local"
        record("joinRoom($roomId) -> joined(local only)")
        listener?.onJoined(
            JoinedEvent(
                peerId = localPeerId,
                participants = listOf(SignalingProviderParticipant(peerId = localPeerId, joinedAt = 1L)),
                hostPeerId = null,
                maxParticipants = 4,
            )
        )
        schedule(400) {
            record("peerJoined($remotePeerId)")
            listener?.onPeerJoined(PeerEvent(peerId = remotePeerId, joinedAt = 2L))
        }
        schedule(700) {
            record("message($remotePeerId -> demo_message)")
            listener?.onMessage(
                PeerMessage(
                    from = remotePeerId,
                    type = "demo_message",
                    payload = JSONObject(mapOf("text" to "Hello from the in-memory Android sample provider.")),
                )
            )
        }
    }

    override fun leaveRoom() {
        clearScheduledTasks()
        record("leaveRoom()")
        listener?.onPeerLeft(PeerEvent(peerId = remotePeerId, joinedAt = 2L))
    }

    override fun endRoom() {
        clearScheduledTasks()
        record("endRoom() -> roomEnded")
        listener?.onRoomEnded(RoomEndedEvent(by = localPeerId, reason = "mock provider demo ended"))
    }

    override fun sendToPeer(peerId: String, type: String, payload: JSONObject?) {
        record("sendToPeer($peerId, $type)")
        echoMessage(type, payload)
    }

    override fun broadcast(type: String, payload: JSONObject?) {
        record("broadcast($type)")
        echoMessage(type, payload)
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        record("getIceServers() -> [] (STUN-only fallback)")
        return emptyList()
    }

    private fun echoMessage(type: String, payload: JSONObject?) {
        schedule(150) {
            record("message($remotePeerId -> ack:$type)")
            listener?.onMessage(
                PeerMessage(
                    from = remotePeerId,
                    type = "ack:$type",
                    payload = JSONObject().apply {
                        put("echoedPayload", payload ?: JSONObject.NULL)
                    },
                )
            )
        }
    }

    private fun record(entry: String) {
        eventLog += entry
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val runnable = Runnable(block)
        scheduledTasks += runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearScheduledTasks() {
        scheduledTasks.forEach(handler::removeCallbacks)
        scheduledTasks.clear()
    }
}
