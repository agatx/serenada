package app.serenada.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

@Composable
fun SerenadaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = Typography(),
        content = content
    )
}
