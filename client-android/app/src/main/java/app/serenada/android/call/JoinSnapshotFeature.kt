package app.serenada.android.call

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import app.serenada.android.network.HostApiClient
import app.serenada.android.network.PushRecipient
import app.serenada.android.network.PushSnapshotRecipient
import app.serenada.android.network.PushSnapshotUploadRequest
import app.serenada.core.call.WebRtcResilienceConstants
import java.io.ByteArrayOutputStream
import java.math.BigInteger
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

class JoinSnapshotFeature(
    private val apiClient: HostApiClient,
    private val handler: Handler,
    private val captureLocalSnapshot: ((ByteArray?) -> Unit) -> Unit,
) {
    private val secureRandom = SecureRandom()

    private data class JoinSnapshotImage(
        val bytes: ByteArray,
        val mime: String
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
                captureLocalSnapshot { snapshotBytes ->
                    if (finished.get()) return@captureLocalSnapshot
                    if (snapshotBytes == null) {
                        finish(null)
                        return@captureLocalSnapshot
                    }
                    if (!isJoinAttemptActive()) {
                        finish(null)
                        return@captureLocalSnapshot
                    }
                    thread(name = "join-snapshot-upload", start = true) {
                        if (finished.get()) return@thread
                        val snapshot = JoinSnapshotImage(snapshotBytes, "image/jpeg")
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
        const val JOIN_SNAPSHOT_AES_KEY_BYTES = 32
        const val JOIN_SNAPSHOT_IV_BYTES = 12
        const val JOIN_SNAPSHOT_SALT_BYTES = 16
        const val JOIN_SNAPSHOT_EC_COORD_BYTES = 32
        const val JOIN_SNAPSHOT_HKDF_INFO = "serenada-push-snapshot"
    }
}
