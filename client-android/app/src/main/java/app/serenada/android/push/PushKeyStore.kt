package app.serenada.android.push

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class PushKeyStore {
    fun getPublicJwk(): JSONObject? {
        val entry = getOrCreatePrivateEntry() ?: return null
        val publicKey = entry.certificate.publicKey as? ECPublicKey ?: return null
        val x = toFixedLength(publicKey.w.affineX.toByteArray(), EC_COORD_BYTES) ?: return null
        val y = toFixedLength(publicKey.w.affineY.toByteArray(), EC_COORD_BYTES) ?: return null
        return JSONObject().apply {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", encodeBase64UrlNoPadding(x))
            put("y", encodeBase64UrlNoPadding(y))
        }
    }

    fun decryptWrappedSnapshotKey(
        snapshotSaltB64: String,
        snapshotEphemeralPubB64: String,
        wrappedKeyB64: String,
        wrappedKeyIvB64: String
    ): ByteArray? {
        val entry = getOrCreatePrivateEntry() ?: return null
        val publicKey = entry.certificate.publicKey as? ECPublicKey ?: return null

        val salt = decodeBase64(snapshotSaltB64) ?: return null
        val ephemeralRaw = decodeBase64(snapshotEphemeralPubB64) ?: return null
        val wrappedKey = decodeBase64(wrappedKeyB64) ?: return null
        val wrappedIv = decodeBase64(wrappedKeyIvB64) ?: return null
        if (wrappedIv.size != IV_BYTES || wrappedKey.isEmpty()) return null

        val ephemeralPublicKey = decodeEcPublicKey(ephemeralRaw, publicKey) ?: return null
        val sharedSecret = deriveSharedSecret(entry.privateKey, ephemeralPublicKey) ?: return null
        val wrapKey = hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = HKDF_INFO.toByteArray(Charsets.UTF_8),
            outputLength = AES_KEY_BYTES
        ) ?: return null

        return aesGcmDecrypt(wrapKey, wrappedIv, wrappedKey)
    }

    fun decryptSnapshot(ciphertext: ByteArray, snapshotKey: ByteArray, snapshotIvB64: String): ByteArray? {
        val snapshotIv = decodeBase64(snapshotIvB64) ?: return null
        if (snapshotIv.size != IV_BYTES) return null
        if (snapshotKey.size != AES_KEY_BYTES) return null
        return aesGcmDecrypt(snapshotKey, snapshotIv, ciphertext)
    }

    private fun getOrCreatePrivateEntry(): KeyStore.PrivateKeyEntry? {
        return runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) {
                return@runCatching existing
            }

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()

            keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        }.onFailure {
            Log.e("Push", "Failed to initialize push ECDH key", it)
        }.getOrNull()
    }

    private fun decodeEcPublicKey(raw: ByteArray, curveSource: ECPublicKey): PublicKey? {
        if (raw.size != 1 + EC_COORD_BYTES * 2 || raw[0] != 0x04.toByte()) return null
        val xBytes = raw.copyOfRange(1, 1 + EC_COORD_BYTES)
        val yBytes = raw.copyOfRange(1 + EC_COORD_BYTES, raw.size)
        val point = ECPoint(BigInteger(1, xBytes), BigInteger(1, yBytes))
        val spec = ECPublicKeySpec(point, curveSource.params)
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }.getOrNull()
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray? {
        return runCatching {
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(publicKey, true)
            val secret = agreement.generateSecret()
            toFixedLength(secret, AES_KEY_BYTES) ?: return null
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

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.isBlank()) return null
        return runCatching {
            Base64.decode(value, Base64.DEFAULT)
        }.getOrNull()
    }

    private fun encodeBase64UrlNoPadding(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "serenada_push_ecdh_v1"
        const val HKDF_INFO = "serenada-push-snapshot"
        const val AES_KEY_BYTES = 32
        const val IV_BYTES = 12
        const val EC_COORD_BYTES = 32
    }
}
