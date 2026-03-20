package app.serenada.callui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

data class SerenadaCallFlowTheme(
    val accentColor: Color = Color(0xFF2F81F7),
    val backgroundColor: Color = Color(0xFF0D1117),
)

@Composable
fun SerenadaTheme(
    theme: SerenadaCallFlowTheme = SerenadaCallFlowTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(theme) {
        darkColorScheme(
            primary = theme.accentColor,
            onPrimary = Color.White,
            background = theme.backgroundColor,
            onBackground = Color.White,
            surface = Color(0xFF161B22),
            onSurface = Color.White,
            secondary = Color(0xFF8B949E),
            onSecondary = Color.White,
            surfaceVariant = Color(0xFF30363D),
            onSurfaceVariant = Color(0xFF8B949E),
            error = Color(0xFFF85149),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
