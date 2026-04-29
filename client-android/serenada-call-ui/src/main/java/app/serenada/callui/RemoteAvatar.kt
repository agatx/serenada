package app.serenada.callui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AVATAR_FETCH_TIMEOUT_MS = 10_000

/**
 * Lazily resolves and caches avatars for the lifetime of the call UI.
 * Each `peerId` is sent through [AvatarProvider.resolve] at most once per call,
 * with the result (decoded [Bitmap] or null) cached for the rest of the call.
 */
internal class AvatarCache(private val provider: AvatarProvider?) {
    private val entries = mutableMapOf<String, MutableState<Bitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun get(peerId: String): MutableState<Bitmap?> {
        entries[peerId]?.let { return it }
        val state = mutableStateOf<Bitmap?>(null)
        entries[peerId] = state
        if (provider != null) {
            scope.launch {
                val bitmap = runCatching {
                    // Hop off the main thread so a host's pre-suspension sync work
                    // doesn't jank the UI.
                    withContext(Dispatchers.IO) {
                        provider.resolve(peerId)?.let(::materialize)
                    }
                }.onFailure { error ->
                    Log.w("Serenada", "avatarProvider failed for $peerId", error)
                }.getOrNull()
                state.value = bitmap
            }
        }
        return state
    }

    fun release() {
        scope.cancel()
        entries.clear()
    }

    private fun materialize(source: AvatarSource): Bitmap? = when (source) {
        is AvatarSource.Bitmap -> source.bitmap
        is AvatarSource.Bytes -> BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
        is AvatarSource.Url -> fetchBitmap(source.url)
    }

    private fun fetchBitmap(urlString: String): Bitmap? {
        val connection = (URL(urlString).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.connectTimeout = AVATAR_FETCH_TIMEOUT_MS
            connection.readTimeout = AVATAR_FETCH_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }
}

internal val LocalAvatarCache = staticCompositionLocalOf<AvatarCache?> { null }

@Composable
internal fun rememberAvatarCache(provider: AvatarProvider?): AvatarCache {
    val cache = remember(provider) { AvatarCache(provider) }
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }
    return cache
}

@Composable
internal fun RemoteAvatar(
    peerId: String?,
    displayName: String?,
    size: Dp,
    fontSize: TextUnit,
) {
    val cache = LocalAvatarCache.current
    val bitmapState = if (peerId != null && cache != null) cache.get(peerId) else null
    val bitmap = bitmapState?.value

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = initialsFor(displayName).ifBlank { "•" },
                color = Color.White.copy(alpha = 0.85f),
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

internal fun initialsFor(displayName: String?): String {
    if (displayName.isNullOrBlank()) return ""
    val initials = mutableListOf<String>()
    for (part in displayName.trim().split(Regex("\\s+"))) {
        for (ch in part) {
            if (ch.isLetterOrDigit()) {
                initials.add(ch.toString().uppercase())
                break
            }
        }
    }
    return when {
        initials.isEmpty() -> ""
        initials.size == 1 -> initials.first()
        else -> initials.first() + initials.last()
    }
}
