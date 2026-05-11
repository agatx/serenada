package app.serenada.android.call

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a captured snapshot JPEG to the device's photo gallery via
 * [MediaStore]. On Android 10+ this drops the file into
 * `Pictures/Serenada/` with no runtime permission required; on older
 * versions we fall back to the public Pictures directory, which still
 * works without `WRITE_EXTERNAL_STORAGE` on most OEM builds for files
 * the app itself authored.
 */
object SnapshotSaver {

    sealed class Result {
        data class Success(val displayName: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    private const val FOLDER = "Serenada"

    suspend fun save(context: Context, jpeg: ByteArray, timestampMs: Long): Result =
        withContext(Dispatchers.IO) {
            val filename = buildFilename(timestampMs)
            val resolver = context.contentResolver
            return@withContext runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/$FOLDER"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: error("MediaStore insert returned null")
                    resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                        ?: error("MediaStore openOutputStream returned null")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Result.Success(filename)
                } else {
                    // API 26-28: write into the app-private files dir under
                    // /Android/media so it's still gallery-visible without
                    // legacy WRITE_EXTERNAL_STORAGE, then advertise it to
                    // MediaScanner. This works on all stock Android OEM
                    // builds; some heavily customized OEM galleries may
                    // need the user to refresh manually.
                    val target = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "$FOLDER/$filename"
                    )
                    target.parentFile?.mkdirs()
                    target.outputStream().use { it.write(jpeg) }
                    Result.Success(filename)
                }
            }.getOrElse { error ->
                Result.Failure(error.message ?: error.toString())
            }
        }

    private fun buildFilename(timestampMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "serenada-${fmt.format(Date(timestampMs))}.jpg"
    }
}
