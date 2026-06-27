package app.serenada.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.serenada.android.R
import app.serenada.core.CallId
import app.serenada.core.CallRegistryState
import app.serenada.core.ManagedCallState
import app.serenada.core.call.CallMediaRole

/**
 * Minimal multi-call switcher (multi-call session, Phase 5 deliverable 3). It is
 * driven entirely by the registry's aggregate [CallRegistryState] exposed as
 * `callListState`; it never reads a `SerenadaSession` directly. The bar is only
 * rendered when MORE THAN ONE managed call exists, so single-call UX is untouched
 * (with one call there is nothing to switch between).
 *
 * The active (foreground) call is shown first with a "Hold" action; held calls
 * each offer "Resume" ([onSwitchToCall]) and "Leave" ([onLeaveCall]). Holding the
 * active call sets `activeCallId = null` with NO auto-promote (Core Invariant 5),
 * which the host renders as "no active call".
 */
@Composable
fun CallSwitcherBar(
    state: CallRegistryState,
    roomLabel: (ManagedCallState) -> String,
    onSwitchToCall: (CallId) -> Unit,
    onHoldCall: (CallId) -> Unit,
    onLeaveCall: (CallId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val calls = state.calls
    // Single-call UX is preserved exactly: with zero or one managed call there is
    // nothing to switch between, so the switcher renders nothing.
    if (calls.size < 2) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        LazyRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(calls, key = { it.callId }) { call ->
                CallSwitcherChip(
                    call = call,
                    isActive = call.callId == state.activeCallId,
                    label = roomLabel(call),
                    onSwitchToCall = onSwitchToCall,
                    onHoldCall = onHoldCall,
                    onLeaveCall = onLeaveCall,
                )
            }
        }
    }
}

@Composable
private fun CallSwitcherChip(
    call: ManagedCallState,
    isActive: Boolean,
    label: String,
    onSwitchToCall: (CallId) -> Unit,
    onHoldCall: (CallId) -> Unit,
    onLeaveCall: (CallId) -> Unit,
) {
    val statusResId = when {
        isActive -> R.string.call_switcher_active
        call.mediaRole == CallMediaRole.HELD -> R.string.call_switcher_on_hold
        else -> R.string.call_switcher_connecting
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
            )
            Text(
                text = stringResource(statusResId),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    OutlinedButton(onClick = { onHoldCall(call.callId) }) {
                        Text(stringResource(R.string.call_switcher_hold))
                    }
                } else {
                    OutlinedButton(onClick = { onSwitchToCall(call.callId) }) {
                        Text(stringResource(R.string.call_switcher_resume))
                    }
                }
                TextButton(onClick = { onLeaveCall(call.callId) }) {
                    Text(stringResource(R.string.call_switcher_leave))
                }
            }
        }
    }
}
