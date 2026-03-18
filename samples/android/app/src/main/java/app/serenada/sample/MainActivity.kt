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
import app.serenada.core.SerenadaCore
import app.serenada.core.SerenadaConfig
import app.serenada.callui.SerenadaCallFlow

class MainActivity : ComponentActivity() {
    private val serenada = SerenadaCore(
        config = SerenadaConfig(serverHost = "serenada.app")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SampleApp(serenada = serenada)
            }
        }
    }
}

@Composable
fun SampleApp(serenada: SerenadaCore) {
    var callUrl by remember { mutableStateOf<String?>(null) }

    if (callUrl != null) {
        SerenadaCallFlow(
            url = callUrl!!,
            onDismiss = { callUrl = null }
        )
    } else {
        HomeScreen(
            onJoin = { url -> callUrl = url },
            serenada = serenada
        )
    }
}

@Composable
fun HomeScreen(onJoin: (String) -> Unit, serenada: SerenadaCore) {
    var urlText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Serenada Sample", style = MaterialTheme.typography.headlineLarge)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Paste a call URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onJoin(urlText) },
            enabled = urlText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Call")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                serenada.createRoom { result ->
                    result.onSuccess { room ->
                        // In a real app, share room.url with the other party
                        onJoin(room.url)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create New Call")
        }
    }
}
