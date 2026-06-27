package app.serenada.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.serenada.callui.SerenadaCallFlow
import app.serenada.callui.SerenadaCallFlowConfig
import app.serenada.core.JoinAndSwitchResult
import app.serenada.core.JoinResult
import app.serenada.core.ManagedCallState
import app.serenada.core.RoomRef
import app.serenada.core.SerenadaCallRegistry
import app.serenada.core.SerenadaCore
import app.serenada.core.SwitchResult
import kotlinx.coroutines.launch

private val multiCallFlowConfig = SerenadaCallFlowConfig(
    screenSharingEnabled = false,
    inviteControlsEnabled = false,
)

/**
 * Minimal multi-call host screen. Demonstrates keeping several Serenada calls
 * joined at once via [SerenadaCallRegistry] and moving the single foreground
 * media owner between them:
 *
 *  - construct one registry over a [SerenadaCore]
 *  - [SerenadaCallRegistry.joinAndSwitch] / [SerenadaCallRegistry.joinHeld]
 *  - render the active call's [SerenadaSession] with the prebuilt call UI
 *  - list held calls and [SerenadaCallRegistry.switchToCall]
 *  - [SerenadaCallRegistry.holdCall] / leaveCall / endCall
 *  - handle the "no active call but held calls remain" state
 *
 * The registry owns the process-wide foreground lease for its calls, so a host
 * integrates through the registry OR direct [SerenadaCore.join], not both.
 */
@Composable
fun MultiCallScreen(
    serenada: SerenadaCore,
    onDismiss: () -> Unit,
) {
    // One registry per host integration. It is the only foreground-lease owner.
    val registry = remember(serenada) { SerenadaCallRegistry(serenada) }
    val state by registry.state.collectAsState()
    val scope = rememberCoroutineScope()

    var roomInput by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<String?>(null) }

    // The active call's session is exposed (not hidden) so we can render it with
    // the prebuilt UI. Null when no call is foreground (all held / none joined).
    val activeSession = if (state.activeCallId != null) registry.activeSession else null

    if (activeSession != null) {
        // A call holds the foreground lease: render it. Hold (keep joined, drop
        // foreground) returns to the multi-call list; leave tears the call down.
        SerenadaCallFlow(
            session = activeSession,
            config = multiCallFlowConfig,
            onEndCall = {
                state.activeCallId?.let { id -> scope.launch { registry.leaveCall(id) } }
            },
            onDismiss = {
                state.activeCallId?.let { id -> scope.launch { registry.holdCall(id) } }
            },
        )
        return
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Multi-Call Registry", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Keep several calls joined at once. Exactly one call owns capture and " +
                "audio (foreground); the rest stay connected but held.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- Join controls ---
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Join a Room", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = roomInput,
                    onValueChange = { roomInput = it },
                    label = { Text("Call URL or room id") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                // joinAndSwitch: join held, then take foreground (the common new-call flow).
                Button(
                    onClick = {
                        val room = roomInput.toRoomRef()
                        if (room != null) {
                            scope.launch { lastResult = registry.joinAndSwitch(room).describe() }
                        }
                    },
                    enabled = roomInput.isNotBlank() && !state.registryOperationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Join and Switch")
                }
                // joinHeld: join in the background without taking foreground.
                OutlinedButton(
                    onClick = {
                        val room = roomInput.toRoomRef()
                        if (room != null) {
                            scope.launch { lastResult = registry.joinHeld(room).describe() }
                        }
                    },
                    enabled = roomInput.isNotBlank() && !state.registryOperationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Join Held (background)")
                }
            }
        }

        // --- "No active call, held calls remain" state ---
        // The registry never auto-promotes a held call to foreground (Core
        // Invariant 5): after holding/leaving the active call the host decides
        // which held call, if any, to switch to.
        val liveCalls = state.calls.filter { it.membershipPhase != app.serenada.core.call.CallPhase.Idle }
        if (state.activeCallId == null && liveCalls.any { it.held }) {
            Text(
                "No call is in the foreground. Pick a held call below to switch to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // --- Managed call list ---
        if (state.calls.isEmpty()) {
            Text(
                "No calls yet. Join a room to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.calls.forEach { call ->
                ManagedCallRow(
                    call = call,
                    isActive = call.callId == state.activeCallId,
                    busy = state.registryOperationInProgress,
                    onSwitch = { scope.launch { lastResult = registry.switchToCall(call.callId).describe() } },
                    onHold = { scope.launch { registry.holdCall(call.callId) } },
                    onLeave = { scope.launch { registry.leaveCall(call.callId) } },
                    onEnd = { scope.launch { registry.endCall(call.callId) } },
                    onDismiss = { scope.launch { registry.dismissCall(call.callId) } },
                )
            }
        }

        lastResult?.let { Text("Last op: $it", style = MaterialTheme.typography.bodySmall) }
        state.lastError?.let {
            Text(
                "Registry error: ${it.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        // close() leaves every managed call and frees the process for a fresh
        // registry or a direct join.
        OutlinedButton(
            onClick = {
                registry.close()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close Registry")
        }
    }
}

@Composable
private fun ManagedCallRow(
    call: ManagedCallState,
    isActive: Boolean,
    busy: Boolean,
    onSwitch: () -> Unit,
    onHold: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ended = call.membershipPhase == app.serenada.core.call.CallPhase.Idle ||
        call.membershipPhase == app.serenada.core.call.CallPhase.Ending
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(call.roomId, style = MaterialTheme.typography.titleMedium)
            Text(
                "Phase: ${call.membershipPhase} · Role: ${call.mediaRole} · Peers: ${call.participantCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            call.activationError?.let {
                Text(
                    it.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    ended -> {
                        TextButton(onClick = onDismiss, enabled = !busy) { Text("Dismiss") }
                    }
                    isActive -> {
                        OutlinedButton(onClick = onHold, enabled = !busy) { Text("Hold") }
                        TextButton(onClick = onLeave, enabled = !busy) { Text("Leave") }
                        TextButton(onClick = onEnd, enabled = !busy) { Text("End") }
                    }
                    else -> {
                        Button(onClick = onSwitch, enabled = !busy) { Text("Switch to") }
                        TextButton(onClick = onLeave, enabled = !busy) { Text("Leave") }
                    }
                }
            }
        }
    }
}

/** Parse host input into a [RoomRef]: a URL when it looks like one, else a bare id. */
private fun String.toRoomRef(): RoomRef? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        RoomRef.Url(trimmed)
    } else {
        RoomRef.Id(trimmed)
    }
}

private fun JoinAndSwitchResult.describe(): String = when (this) {
    is JoinAndSwitchResult.Active -> "active ($callId)"
    is JoinAndSwitchResult.NeedsPermission -> "needs permission ($callId) — grant, then switchToCall"
    is JoinAndSwitchResult.Failed -> "failed: ${error.message}"
}

private fun JoinResult.describe(): String = when (this) {
    is JoinResult.Joined -> "joined held ($callId)"
    is JoinResult.Failed -> "failed: ${error.message}"
}

private fun SwitchResult.describe(): String = when (this) {
    is SwitchResult.Active -> "active"
    is SwitchResult.NeedsPermission -> "needs permission — grant, then retry switchToCall"
    is SwitchResult.Failed -> "failed: ${error.message}"
}
