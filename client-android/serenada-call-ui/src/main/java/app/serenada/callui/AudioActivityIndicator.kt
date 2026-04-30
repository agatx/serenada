package app.serenada.callui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Three small green bars that pulse with the speaker's audio level — used
 * in place of the muted mic icon when a participant is unmuted.
 *
 * Mirrors the web SDK's `AudioActivityIndicator` 1:1 — same bar gains,
 * 14% minimum height, animated transitions paced to the 100 ms update
 * cadence of [app.serenada.core.call.AudioLevelMonitor].
 */
@Composable
internal fun AudioActivityIndicator(
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val clamped = level.coerceIn(0f, 1f)
    Row(
        modifier = modifier.size(size),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BAR_GAINS.forEach { gain ->
            val targetFraction = MIN_HEIGHT_FRACTION +
                (MAX_HEIGHT_FRACTION - MIN_HEIGHT_FRACTION) * (clamped * gain).coerceAtMost(1f)
            val animatedFraction by animateFloatAsState(
                targetValue = targetFraction,
                animationSpec = tween(durationMillis = ANIMATION_DURATION_MS),
                label = "audio-bar-height",
            )
            Box(
                modifier = Modifier
                    .width(BAR_WIDTH)
                    .fillMaxHeight(animatedFraction)
                    .background(BAR_COLOR, RoundedCornerShape(1.5.dp))
            )
        }
    }
}

private val BAR_GAINS = listOf(0.7f, 1.0f, 0.55f)
private const val MIN_HEIGHT_FRACTION = 0.14f
private const val MAX_HEIGHT_FRACTION = 1.0f
private val BAR_WIDTH = 3.dp
private val BAR_COLOR = Color(0xFF22C55E)
private const val ANIMATION_DURATION_MS = 100
