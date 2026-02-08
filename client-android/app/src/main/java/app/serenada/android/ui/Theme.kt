package app.serenada.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2F81F7),
    onPrimary = Color.White,
    background = Color(0xFF0D1117),
    onBackground = Color.White,
    surface = Color(0xFF161B22),
    onSurface = Color.White,
    secondary = Color(0xFF8B949E),
    onSecondary = Color.White,
    surfaceVariant = Color(0xFF30363D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149)
)

@Composable
fun SerenadaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
