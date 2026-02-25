import CryptoKit
import Foundation

struct PushEncryptionPublicKey: Equatable {
    let kty: String
    let crv: String
    let x: String
    let y: String
}

final class PushKeyStore {
    private enum Constants {
        static let keyStorageKey = "serenada_push_ecdh_private_key_v1"
        static let hkdfInfo = "serenada-push-snapshot"
        static let aesKeyBytes = 32
        static let ivBytes = 12
        static let gcmTagBytes = 16
        static let uncompressedEcPointPrefix: UInt8 = 0x04
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func getPublicJwk() -> PushEncryptionPublicKey? {
        guard let privateKey = getOrCreatePrivateKey() else { return nil }
        let raw = privateKey.publicKey.rawRepresentation
        guard raw.count == 65, raw.first == Constants.uncompressedEcPointPrefix else { return nil }

        let x = raw.subdata(in: 1..<33).base64URLEncodedStringNoPadding()
        let y = raw.subdata(in: 33..<65).base64URLEncodedStringNoPadding()

        return PushEncryptionPublicKey(kty: "EC", crv: "P-256", x: x, y: y)
    }

    func decryptWrappedSnapshotKey(
        snapshotSaltB64: String,
        snapshotEphemeralPubB64: String,
        wrappedKeyB64: String,
        wrappedKeyIvB64: String
    ) -> Data? {
        guard let privateKey = getOrCreatePrivateKey() else { return nil }
        guard let salt = Data(base64Encoded: snapshotSaltB64) else { return nil }
        guard let ephemeralRaw = Data(base64Encoded: snapshotEphemeralPubB64) else { return nil }
        guard let wrappedKey = Data(base64Encoded: wrappedKeyB64) else { return nil }
        guard let wrappedIV = Data(base64Encoded: wrappedKeyIvB64), wrappedIV.count == Constants.ivBytes else { return nil }

        guard let ephemeralKey = try? P256.KeyAgreement.PublicKey(rawRepresentation: ephemeralRaw) else {
            return nil
        }

        guard let wrapKey = deriveWrapKey(
            privateKey: privateKey,
            peerPublicKey: ephemeralKey,
            salt: salt
        ) else {
            return nil
        }

        return decryptAESGCM(ciphertextAndTag: wrappedKey, keyData: wrapKey, iv: wrappedIV)
    }

    func decryptSnapshot(ciphertext: Data, snapshotKey: Data, snapshotIvB64: String) -> Data? {
        guard snapshotKey.count == Constants.aesKeyBytes else { return nil }
        guard let iv = Data(base64Encoded: snapshotIvB64), iv.count == Constants.ivBytes else { return nil }
        return decryptAESGCM(ciphertextAndTag: ciphertext, keyData: snapshotKey, iv: iv)
    }

    private func deriveWrapKey(
        privateKey: P256.KeyAgreement.PrivateKey,
        peerPublicKey: P256.KeyAgreement.PublicKey,
        salt: Data
    ) -> Data? {
        guard let sharedSecret = try? privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey) else {
            return nil
        }
        let derived = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data(Constants.hkdfInfo.utf8),
            outputByteCount: Constants.aesKeyBytes
        )
        return derived.withUnsafeBytes { Data($0) }
    }

    private func decryptAESGCM(ciphertextAndTag: Data, keyData: Data, iv: Data) -> Data? {
        guard ciphertextAndTag.count > Constants.gcmTagBytes else { return nil }
        let ciphertext = ciphertextAndTag.dropLast(Constants.gcmTagBytes)
        let tag = ciphertextAndTag.suffix(Constants.gcmTagBytes)

        guard let nonce = try? AES.GCM.Nonce(data: iv) else { return nil }
        let symmetricKey = SymmetricKey(data: keyData)
        guard let sealed = try? AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: ciphertext,
            tag: tag
        ) else {
            return nil
        }
        return try? AES.GCM.open(sealed, using: symmetricKey)
    }

    private func getOrCreatePrivateKey() -> P256.KeyAgreement.PrivateKey? {
        if let existingRaw = defaults.string(forKey: Constants.keyStorageKey),
           let existingData = Data(base64Encoded: existingRaw),
           let existing = try? P256.KeyAgreement.PrivateKey(rawRepresentation: existingData) {
            return existing
        }

        let created = P256.KeyAgreement.PrivateKey()
        let raw = created.rawRepresentation.base64EncodedString()
        defaults.set(raw, forKey: Constants.keyStorageKey)
        return created
    }
}

private extension Data {
    func base64URLEncodedStringNoPadding() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
