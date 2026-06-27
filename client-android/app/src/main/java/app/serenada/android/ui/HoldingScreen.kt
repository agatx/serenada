package app.serenada.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.serenada.android.R
import app.serenada.core.CallId
import app.serenada.core.CallRegistryState
import app.serenada.core.ManagedCallState

/**
 * "Calls on hold" surface (multi-call session, P5-4 fix). Shown when the active
 * call has ended but live held calls remain in the registry: the registry does NOT
 * auto-promote (Core Invariant 5), so without this surface those held calls would
 * be unreachable behind the Join screen. Each held call offers Resume
 * ([onSwitchToCall], which runs `switchToCall` and re-foregrounds it) and Leave
 * ([onLeaveCall]).
 *
 * This is only routed to via [RootRouting.shouldShowHoldingSurface]; with zero
 * live calls the host falls back to Join (single-call UX preserved).
 */
@Composable
fun HoldingScreen(
    state: CallRegistryState,
    roomLabel: (ManagedCallState) -> String,
    onSwitchToCall: (CallId) -> Unit,
    onLeaveCall: (CallId) -> Unit,
) {
    val heldCalls = state.calls.filter { !RootRouting.isTerminal(it.membershipPhase) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.holding_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.holding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(heldCalls, key = { it.callId }) { call ->
                    HoldingCallRow(
                        label = roomLabel(call),
                        onResume = { onSwitchToCall(call.callId) },
                        onLeave = { onLeaveCall(call.callId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldingCallRow(
    label: String,
    onResume: () -> Unit,
    onLeave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.call_switcher_on_hold),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onResume) {
                Text(stringResource(R.string.call_switcher_resume))
            }
            TextButton(onClick = onLeave) {
                Text(stringResource(R.string.call_switcher_leave))
            }
        }
    }
}
