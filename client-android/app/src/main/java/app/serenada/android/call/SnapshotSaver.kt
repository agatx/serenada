package app.serenada.android.call

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
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
 * Persists a captured snapshot JPEG to the device's photo gallery.
 *
 * On Android 10+ (`Q`) we use scoped MediaStore writes into
 * `Pictures/Serenada/` with no runtime permission. On older versions
 * (API 26-28) we cannot write to shared media without
 * `WRITE_EXTERNAL_STORAGE`, so we write to the app-specific external
 * media directory and trigger `MediaScannerConnection` so the gallery
 * picks it up. The app keeps minSdk 26, but the API 28- path is rarely
 * exercised in practice.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStoreQ(context, jpeg, filename)
            } else {
                saveLegacy(context, jpeg, filename)
            }
        }

    private fun saveViaMediaStoreQ(
        context: Context,
        jpeg: ByteArray,
        filename: String,
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$FOLDER"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failure("MediaStore insert returned null")

        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: error("MediaStore openOutputStream returned null")
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val updated = resolver.update(uri, publishValues, null, null)
            if (updated <= 0) {
                error("MediaStore update did not match the pending row")
            }
            Result.Success(filename) as Result
        }.getOrElse { error ->
            // Clean up the orphaned pending row so it doesn't stay
            // hidden forever in the user's MediaStore.
            runCatching { resolver.delete(uri, null, null) }
            Result.Failure(error.message ?: error.toString())
        }
    }

    private suspend fun saveLegacy(
        context: Context,
        jpeg: ByteArray,
        filename: String,
    ): Result {
        // API 26-28: scoped storage doesn't yet exist and we have no
        // `WRITE_EXTERNAL_STORAGE` (would require it on 28-). Use the
        // app-specific external media directory, which is writable
        // without runtime permission, then notify MediaScanner so the
        // gallery indexes it.
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: return Result.Failure("External pictures directory unavailable")
        val target = File(baseDir, "$FOLDER/$filename")
        return runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { it.write(jpeg) }
            scanForGallery(context, target)
            Result.Success(filename) as Result
        }.getOrElse { error ->
            Result.Failure(error.message ?: error.toString())
        }
    }

    private fun scanForGallery(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null,
        )
    }

    private fun buildFilename(timestampMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "serenada-${fmt.format(Date(timestampMs))}.jpg"
    }
}
