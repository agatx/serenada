package app.serenada.android.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import app.serenada.android.network.ApiClient
import app.serenada.android.network.PushRecipient
import app.serenada.android.network.PushSnapshotRecipient
import app.serenada.android.network.PushSnapshotUploadRequest
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.min
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class JoinSnapshotFeature(
    private val apiClient: ApiClient,
    private val handler: Handler,
    private val attachLocalSink: (VideoSink) -> Unit,
    private val detachLocalSink: (VideoSink) -> Unit
) {
    private val secureRandom = SecureRandom()

    private data class JoinSnapshotImage(
        val bytes: ByteArray,
        val mime: String
    )

    private data class EncodedSnapshotResult(
        val snapshot: JoinSnapshotImage?,
        val isLikelyBlackFrame: Boolean
    )

    fun prepareSnapshotId(
        host: String,
        roomId: String,
        isVideoEnabled: () -> Boolean,
        isJoinAttemptActive: () -> Boolean,
        onReady: (String?) -> Unit
    ) {
        if (!isJoinAttemptActive()) {
            onReady(null)
            return
        }
        if (!isVideoEnabled()) {
            onReady(null)
            return
        }

        val finished = AtomicBoolean(false)
        fun finish(snapshotId: String?) {
            if (!finished.compareAndSet(false, true)) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onReady(snapshotId)
            } else {
                handler.post { onReady(snapshotId) }
            }
        }

        handler.postDelayed(
            { finish(null) },
            JOIN_SNAPSHOT_PREP_TIMEOUT_MS
        )

        apiClient.fetchPushRecipients(host, roomId) { recipientsResult ->
            if (finished.get()) return@fetchPushRecipients
            val recipients = recipientsResult.getOrNull().orEmpty()
            if (recipientsResult.isFailure) {
                Log.w("CallManager", "Failed to fetch push recipients", recipientsResult.exceptionOrNull())
                finish(null)
                return@fetchPushRecipients
            }
            if (recipients.isEmpty()) {
                finish(null)
                return@fetchPushRecipients
            }

            handler.post {
                if (finished.get()) return@post
                if (!isJoinAttemptActive()) {
                    finish(null)
                    return@post
                }
                captureJoinSnapshot { snapshot ->
                    if (finished.get()) return@captureJoinSnapshot
                    if (snapshot == null) {
                        finish(null)
                        return@captureJoinSnapshot
                    }
                    if (!isJoinAttemptActive()) {
                        finish(null)
                        return@captureJoinSnapshot
                    }
                    thread(name = "join-snapshot-upload", start = true) {
                        if (finished.get()) return@thread
                        val request = encryptSnapshotForRecipients(snapshot, recipients)
                        if (request == null) {
                            finish(null)
                            return@thread
                        }
                        if (finished.get()) return@thread
                        apiClient.uploadPushSnapshot(host, request) { uploadResult ->
                            if (uploadResult.isFailure) {
                                Log.w("CallManager", "Failed to upload join snapshot", uploadResult.exceptionOrNull())
                            } else {
                                Log.d("CallManager", "Join snapshot uploaded successfully")
                            }
                            finish(uploadResult.getOrNull())
                        }
                    }
                }
            }
        }
    }

    private fun captureJoinSnapshot(onResult: (JoinSnapshotImage?) -> Unit) {
        val completed = AtomicBoolean(false)
        val sawLikelyBlackFrame = AtomicBoolean(false)
        lateinit var sink: VideoSink
        var timeoutRunnable: Runnable? = null

        fun complete(snapshot: JoinSnapshotImage?) {
            if (!completed.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            handler.post {
                detachLocalSink(sink)
                if (snapshot == null && sawLikelyBlackFrame.get()) {
                    Log.d("CallManager", "Join snapshot skipped after black-frame detection")
                }
                onResult(snapshot)
            }
        }

        timeoutRunnable = Runnable { complete(null) }

        sink = VideoSink { frame ->
            if (completed.get()) return@VideoSink
            frame.retain()
            thread(name = "join-snapshot-frame", start = true) {
                val encoded = runCatching { encodeSnapshotFrame(frame) }.getOrNull()
                    ?: EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
                frame.release()
                if (completed.get()) return@thread
                if (encoded.isLikelyBlackFrame) {
                    sawLikelyBlackFrame.set(true)
                    return@thread
                }
                val snapshot = encoded.snapshot ?: return@thread
                complete(snapshot)
            }
        }

        attachLocalSink(sink)
        handler.postDelayed(timeoutRunnable, JOIN_SNAPSHOT_FRAME_TIMEOUT_MS)
    }

    private fun encodeSnapshotFrame(frame: VideoFrame): EncodedSnapshotResult {
        val i420 = frame.buffer.toI420() ?: return EncodedSnapshotResult(
            snapshot = null,
            isLikelyBlackFrame = false
        )
        return try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) {
                return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            }
            if (isLikelyBlackFrame(i420)) {
                return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = true)
            }

            val nv21 = i420ToNv21(i420)
            val rawJpeg = ByteArrayOutputStream().use { output ->
                val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                if (!image.compressToJpeg(Rect(0, 0, width, height), 90, output)) {
                    return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
                }
                output.toByteArray()
            }

            val source = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                ?: return EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            var rotated: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                rotated = rotateBitmapIfNeeded(source, frame.rotation)
                scaled = scaleBitmapIfNeeded(rotated, JOIN_SNAPSHOT_MAX_WIDTH_PX)
                val qualities = intArrayOf(70, 60, 50, 40, 30)
                for (quality in qualities) {
                    val encoded = compressBitmapAsJpeg(scaled, quality) ?: continue
                    if (encoded.size <= JOIN_SNAPSHOT_MAX_BYTES) {
                        return EncodedSnapshotResult(
                            snapshot = JoinSnapshotImage(encoded, "image/jpeg"),
                            isLikelyBlackFrame = false
                        )
                    }
                }
                EncodedSnapshotResult(snapshot = null, isLikelyBlackFrame = false)
            } finally {
                if (scaled !== null && scaled !== rotated && !scaled.isRecycled) scaled.recycle()
                if (rotated !== null && rotated !== source && !rotated.isRecycled) rotated.recycle()
                if (!source.isRecycled) source.recycle()
            }
        } finally {
            i420.release()
        }
    }

    private fun isLikelyBlackFrame(buffer: VideoFrame.I420Buffer): Boolean {
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return true

        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        val yPlane = buffer.dataY.duplicate()

        var sampleCount = 0L
        var sampleSum = 0L
        var sampleSumSquares = 0L
        var sampleMax = 0

        for (y in 0 until height step stepY) {
            val rowStart = y * buffer.strideY
            for (x in 0 until width step stepX) {
                val value = yPlane.get(rowStart + x).toInt() and 0xFF
                sampleCount += 1
                sampleSum += value
                sampleSumSquares += value.toLong() * value.toLong()
                if (value > sampleMax) sampleMax = value
            }
        }

        if (sampleCount == 0L) return true

        val meanTimes100 = (sampleSum * 100L) / sampleCount
        val meanSquareTimes100 = (sampleSumSquares * 100L) / sampleCount
        val meanSquaredTimes100 = (meanTimes100 * meanTimes100) / 100L
        val varianceTimes100 = (meanSquareTimes100 - meanSquaredTimes100).coerceAtLeast(0L)

        return meanTimes100 < 800L && varianceTimes100 < 2500L && sampleMax < 32
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotation: Int): Bitmap {
        val normalized = ((rotation % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
        }.getOrElse { bitmap }
    }

    private fun compressBitmapAsJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                return@use null
            }
            output.toByteArray()
        }
    }

    private fun i420ToNv21(buffer: VideoFrame.I420Buffer): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvSize = chromaWidth * chromaHeight

        val out = ByteArray(ySize + uvSize * 2)
        copyPlane(
            src = buffer.dataY,
            srcStride = buffer.strideY,
            width = width,
            height = height,
            dst = out,
            dstOffset = 0,
            dstStride = width
        )

        val u = ByteArray(uvSize)
        val v = ByteArray(uvSize)
        copyPlane(
            src = buffer.dataU,
            srcStride = buffer.strideU,
            width = chromaWidth,
            height = chromaHeight,
            dst = u,
            dstOffset = 0,
            dstStride = chromaWidth
        )
        copyPlane(
            src = buffer.dataV,
            srcStride = buffer.strideV,
            width = chromaWidth,
            height = chromaHeight,
            dst = v,
            dstOffset = 0,
            dstStride = chromaWidth
        )

        var offset = ySize
        for (i in 0 until uvSize) {
            out[offset++] = v[i]
            out[offset++] = u[i]
        }
        return out
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
        dstOffset: Int,
        dstStride: Int
    ) {
        val rowBuffer = ByteArray(width)
        val source = src.duplicate()
        var dstIndex = dstOffset
        for (row in 0 until height) {
            source.position(row * srcStride)
            source.get(rowBuffer, 0, width)
            System.arraycopy(rowBuffer, 0, dst, dstIndex, width)
            dstIndex += dstStride
        }
    }

    private fun encryptSnapshotForRecipients(
        snapshot: JoinSnapshotImage,
        recipients: List<PushRecipient>
    ): PushSnapshotUploadRequest? {
        if (snapshot.bytes.isEmpty() || recipients.isEmpty()) return null

        val snapshotKey = randomBytes(JOIN_SNAPSHOT_AES_KEY_BYTES)
        val snapshotIv = randomBytes(JOIN_SNAPSHOT_IV_BYTES)
        val ciphertext = aesGcmEncrypt(snapshotKey, snapshotIv, snapshot.bytes) ?: return null

        val keyPair = runCatching {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        }.getOrNull() ?: return null
        val ephemeralPublic = keyPair.public as? ECPublicKey ?: return null
        val ephemeralPublicRaw = encodeEcPublicKey(ephemeralPublic) ?: return null
        val salt = randomBytes(JOIN_SNAPSHOT_SALT_BYTES)
        val info = JOIN_SNAPSHOT_HKDF_INFO.toByteArray(Charsets.UTF_8)

        val wrappedRecipients = mutableListOf<PushSnapshotRecipient>()
        for (recipient in recipients) {
            val recipientPublic = parseRecipientPublicKey(recipient, ephemeralPublic) ?: continue
            val sharedSecret = deriveSharedSecret(keyPair.private, recipientPublic) ?: continue
            val wrapKey = hkdfSha256(
                ikm = sharedSecret,
                salt = salt,
                info = info,
                outputLength = JOIN_SNAPSHOT_AES_KEY_BYTES
            ) ?: continue
            val wrapIv = randomBytes(JOIN_SNAPSHOT_IV_BYTES)
            val wrappedKey = aesGcmEncrypt(wrapKey, wrapIv, snapshotKey) ?: continue
            wrappedRecipients.add(
                PushSnapshotRecipient(
                    id = recipient.id,
                    wrappedKey = Base64.encodeToString(wrappedKey, Base64.NO_WRAP),
                    wrappedKeyIv = Base64.encodeToString(wrapIv, Base64.NO_WRAP)
                )
            )
        }

        if (wrappedRecipients.isEmpty()) return null

        return PushSnapshotUploadRequest(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            snapshotIv = Base64.encodeToString(snapshotIv, Base64.NO_WRAP),
            snapshotSalt = Base64.encodeToString(salt, Base64.NO_WRAP),
            snapshotEphemeralPubKey = Base64.encodeToString(ephemeralPublicRaw, Base64.NO_WRAP),
            snapshotMime = snapshot.mime,
            recipients = wrappedRecipients
        )
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    private fun parseRecipientPublicKey(recipient: PushRecipient, ephemeralPublic: ECPublicKey): PublicKey? {
        val xRaw = decodeBase64Url(recipient.publicKey.x) ?: return null
        val yRaw = decodeBase64Url(recipient.publicKey.y) ?: return null
        val x = toFixedLength(xRaw, JOIN_SNAPSHOT_EC_COORD_BYTES) ?: return null
        val y = toFixedLength(yRaw, JOIN_SNAPSHOT_EC_COORD_BYTES) ?: return null
        val point = java.security.spec.ECPoint(BigInteger(1, x), BigInteger(1, y))
        val keySpec = ECPublicKeySpec(point, ephemeralPublic.params)
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(keySpec)
        }.getOrNull()
    }

    private fun decodeBase64Url(value: String): ByteArray? {
        if (value.isBlank()) return null
        val normalized = value
            .replace('-', '+')
            .replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull()
    }

    private fun toFixedLength(input: ByteArray, size: Int): ByteArray? {
        if (input.size == size) return input
        if (input.size > size) {
            if (input.size == size + 1 && input.first() == 0.toByte()) {
                return input.copyOfRange(1, input.size)
            }
            return null
        }
        val out = ByteArray(size)
        System.arraycopy(input, 0, out, size - input.size, input.size)
        return out
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray? {
        return runCatching {
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)
            val secret = keyAgreement.generateSecret()
            toFixedLength(secret, JOIN_SNAPSHOT_AES_KEY_BYTES) ?: return null
        }.getOrNull()
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray? {
        return runCatching {
            val saltOrZeros = if (salt.isNotEmpty()) salt else ByteArray(32)
            val extractMac = Mac.getInstance("HmacSHA256")
            extractMac.init(SecretKeySpec(saltOrZeros, "HmacSHA256"))
            val prk = extractMac.doFinal(ikm)

            val okm = ByteArray(outputLength)
            var t = ByteArray(0)
            var generated = 0
            var counter = 1
            while (generated < outputLength) {
                val expandMac = Mac.getInstance("HmacSHA256")
                expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
                expandMac.update(t)
                expandMac.update(info)
                expandMac.update(counter.toByte())
                t = expandMac.doFinal()
                val copyLength = min(t.size, outputLength - generated)
                System.arraycopy(t, 0, okm, generated, copyLength)
                generated += copyLength
                counter += 1
            }
            okm
        }.getOrNull()
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.doFinal(plaintext)
        }.getOrNull()
    }

    private fun encodeEcPublicKey(publicKey: ECPublicKey): ByteArray? {
        val x = toFixedLength(publicKey.w.affineX.toByteArray(), JOIN_SNAPSHOT_EC_COORD_BYTES) ?: return null
        val y = toFixedLength(publicKey.w.affineY.toByteArray(), JOIN_SNAPSHOT_EC_COORD_BYTES) ?: return null
        return ByteArray(1 + x.size + y.size).apply {
            this[0] = 0x04
            System.arraycopy(x, 0, this, 1, x.size)
            System.arraycopy(y, 0, this, 1 + x.size, y.size)
        }
    }

    private companion object {
        val JOIN_SNAPSHOT_PREP_TIMEOUT_MS = WebRtcResilienceConstants.SNAPSHOT_PREPARE_TIMEOUT_MS
        const val JOIN_SNAPSHOT_FRAME_TIMEOUT_MS = 900L
        const val JOIN_SNAPSHOT_MAX_WIDTH_PX = 320
        const val JOIN_SNAPSHOT_MAX_BYTES = 200 * 1024
        const val JOIN_SNAPSHOT_AES_KEY_BYTES = 32
        const val JOIN_SNAPSHOT_IV_BYTES = 12
        const val JOIN_SNAPSHOT_SALT_BYTES = 16
        const val JOIN_SNAPSHOT_EC_COORD_BYTES = 32
        const val JOIN_SNAPSHOT_HKDF_INFO = "serenada-push-snapshot"
    }
}
