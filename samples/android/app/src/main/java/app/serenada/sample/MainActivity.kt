package app.serenada.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.serenada.core.CreateRoomResult
import app.serenada.core.SerenadaConfig
import app.serenada.core.SerenadaCore
import app.serenada.callui.SerenadaCallFlow
import app.serenada.callui.SerenadaCallFlowConfig
import kotlinx.coroutines.launch

private val sampleCallFlowConfig = SerenadaCallFlowConfig(
    screenSharingEnabled = false,
    inviteControlsEnabled = false,
)

private sealed interface ActiveCall {
    data class InviteURL(val url: String) : ActiveCall
    data class CreatedRoom(val result: CreateRoomResult) : ActiveCall
}

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
    var activeCall by remember { mutableStateOf<ActiveCall?>(null) }

    when (val call = activeCall) {
        is ActiveCall.InviteURL -> SerenadaCallFlow(
            url = call.url,
            config = sampleCallFlowConfig,
            onDismiss = { activeCall = null },
        )

        is ActiveCall.CreatedRoom -> SerenadaCallFlow(
            session = call.result.session,
            config = sampleCallFlowConfig,
            onDismiss = { activeCall = null },
        )

        null -> HomeScreen(
            onStartCall = { activeCall = it },
            serenada = serenada,
        )
    }
}

@Composable
private fun HomeScreen(onStartCall: (ActiveCall) -> Unit, serenada: SerenadaCore) {
    var urlText by remember { mutableStateOf("") }
    var isCreatingRoom by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastCreatedRoomUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Serenada Sample", style = MaterialTheme.typography.headlineLarge)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Minimal Android host app using serenada-core and serenada-call-ui directly from this repo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Join an existing call", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Paste a call URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                errorMessage = null
                onStartCall(ActiveCall.InviteURL(urlText))
            },
            enabled = urlText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Join Call")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Create a new call", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "createRoom() already returns a joined session, so the sample reuses it instead of joining twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                errorMessage = null
                isCreatingRoom = true
                scope.launch {
                    runCatching { serenada.createRoom() }
                        .onSuccess { result ->
                            isCreatingRoom = false
                            lastCreatedRoomUrl = result.roomUrl
                            onStartCall(ActiveCall.CreatedRoom(result))
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

        if (lastCreatedRoomUrl != null) {
            Spacer(modifier = Modifier.height(16.dp))
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
