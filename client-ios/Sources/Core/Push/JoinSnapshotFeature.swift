import CryptoKit
import Foundation
import UIKit
#if canImport(WebRTC)
import WebRTC
#endif

final class JoinSnapshotFeature {
    private struct SnapshotImage {
        let bytes: Data
        let mime: String
    }

    private enum Constants {
        static let prepTimeoutNs: UInt64 = 1_500_000_000
        static let frameTimeoutNs: UInt64 = 900_000_000
        static let maxWidthPx: CGFloat = 320
        static let maxBytes = 200 * 1024
        static let aesKeyBytes = 32
        static let ivBytes = 12
        static let saltBytes = 16
        static let ecCoordBytes = 32
        static let gcmTagBytes = 16
        static let hkdfInfo = "serenada-push-snapshot"
        static let jpegQualities: [CGFloat] = [0.7, 0.6, 0.5, 0.4, 0.3]
    }

    private let apiClient: APIClient
    private let attachLocalRenderer: (AnyObject) -> Void
    private let detachLocalRenderer: (AnyObject) -> Void

    init(
        apiClient: APIClient,
        attachLocalRenderer: @escaping (AnyObject) -> Void,
        detachLocalRenderer: @escaping (AnyObject) -> Void
    ) {
        self.apiClient = apiClient
        self.attachLocalRenderer = attachLocalRenderer
        self.detachLocalRenderer = detachLocalRenderer
    }

    func prepareSnapshotId(
        host: String,
        roomId: String,
        isVideoEnabled: @escaping () -> Bool,
        isJoinAttemptActive: @escaping () -> Bool,
        onReady: @escaping (String?) -> Void
    ) {
        guard isJoinAttemptActive() else {
            onReady(nil)
            return
        }
        guard isVideoEnabled() else {
            onReady(nil)
            return
        }

        Task { [weak self] in
            guard let self else {
                onReady(nil)
                return
            }

            let snapshotId = await self.withTimeout(nanoseconds: Constants.prepTimeoutNs) {
                await self.prepareSnapshotIdInternal(
                    host: host,
                    roomId: roomId,
                    isJoinAttemptActive: isJoinAttemptActive
                )
            } ?? nil
            onReady(snapshotId)
        }
    }

    private func prepareSnapshotIdInternal(
        host: String,
        roomId: String,
        isJoinAttemptActive: @escaping () -> Bool
    ) async -> String? {
        guard isJoinAttemptActive() else { return nil }

        let recipients: [PushRecipient]
        do {
            recipients = try await apiClient.fetchPushRecipients(host: host, roomId: roomId)
        } catch {
            return nil
        }
        guard !recipients.isEmpty else { return nil }
        guard isJoinAttemptActive() else { return nil }

        guard let snapshot = await captureJoinSnapshot() else { return nil }
        guard isJoinAttemptActive() else { return nil }

        guard let request = encryptSnapshotForRecipients(snapshot: snapshot, recipients: recipients) else {
            return nil
        }

        return try? await apiClient.uploadPushSnapshot(host: host, request: request)
    }

    private func captureJoinSnapshot() async -> SnapshotImage? {
        #if canImport(WebRTC)
        return await withTaskGroup(of: SnapshotImage?.self) { group in
            group.addTask { [weak self] in
                guard let self else { return nil }
                return await self.captureSingleFrameSnapshot()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: Constants.frameTimeoutNs)
                return nil
            }

            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        #else
        return nil
        #endif
    }

    #if canImport(WebRTC)
    private func captureSingleFrameSnapshot() async -> SnapshotImage? {
        await withCheckedContinuation { continuation in
            let lock = NSLock()
            var completed = false
            var renderer: LocalFrameSnapshotRenderer?

            func finish(_ value: SnapshotImage?) {
                lock.lock()
                if completed {
                    lock.unlock()
                    return
                }
                completed = true
                let currentRenderer = renderer
                lock.unlock()

                if let currentRenderer {
                    detachLocalRenderer(currentRenderer)
                }
                continuation.resume(returning: value)
            }

            renderer = LocalFrameSnapshotRenderer { [weak self] frame in
                guard let self else {
                    finish(nil)
                    return
                }
                let image = self.encodeSnapshot(frame: frame)
                finish(image)
            }

            if let renderer {
                attachLocalRenderer(renderer)
            } else {
                finish(nil)
            }
        }
    }

    private func encodeSnapshot(frame: RTCVideoFrame) -> SnapshotImage? {
        guard let cvBuffer = frame.buffer as? RTCCVPixelBuffer else { return nil }

        var ciImage = CIImage(cvPixelBuffer: cvBuffer.pixelBuffer)
        if let orientation = cgOrientation(for: frame.rotation) {
            ciImage = ciImage.oriented(orientation)
        }

        if isLikelyBlackFrame(ciImage) {
            return nil
        }

        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        let uiImage = UIImage(cgImage: cgImage)
        let scaled = scaleIfNeeded(image: uiImage, maxWidth: Constants.maxWidthPx)

        for quality in Constants.jpegQualities {
            guard let encoded = scaled.jpegData(compressionQuality: quality) else { continue }
            if encoded.count <= Constants.maxBytes {
                return SnapshotImage(bytes: encoded, mime: "image/jpeg")
            }
        }

        return nil
    }

    private func cgOrientation(for rotation: RTCVideoRotation) -> CGImagePropertyOrientation? {
        let normalized = Int(rotation.rawValue)
        switch normalized {
        case 0:
            return .up
        case 90:
            return .right
        case 180:
            return .down
        case 270:
            return .left
        default:
            return nil
        }
    }

    private func scaleIfNeeded(image: UIImage, maxWidth: CGFloat) -> UIImage {
        guard image.size.width > maxWidth, image.size.width > 0, image.size.height > 0 else {
            return image
        }

        let scale = maxWidth / image.size.width
        let target = CGSize(width: maxWidth, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: target)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    private func isLikelyBlackFrame(_ image: CIImage) -> Bool {
        let extent = image.extent
        guard !extent.isEmpty else { return true }

        let filter = CIFilter(
            name: "CIAreaAverage",
            parameters: [
                kCIInputImageKey: image,
                kCIInputExtentKey: CIVector(cgRect: extent)
            ]
        )
        guard let output = filter?.outputImage else { return false }

        let context = CIContext(options: [.workingColorSpace: NSNull()])
        var bitmap = [UInt8](repeating: 0, count: 4)
        context.render(
            output,
            toBitmap: &bitmap,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: nil
        )

        let luminance = (0.2126 * Double(bitmap[0])) +
            (0.7152 * Double(bitmap[1])) +
            (0.0722 * Double(bitmap[2]))
        return luminance < 8.0
    }
    #endif

    private func encryptSnapshotForRecipients(
        snapshot: SnapshotImage,
        recipients: [PushRecipient]
    ) -> PushSnapshotUploadRequest? {
        guard !snapshot.bytes.isEmpty else { return nil }

        let snapshotKey = randomBytes(Constants.aesKeyBytes)
        let snapshotIV = randomBytes(Constants.ivBytes)
        guard let ciphertext = encryptAESGCM(
            plaintext: snapshot.bytes,
            keyData: snapshotKey,
            iv: snapshotIV
        ) else {
            return nil
        }

        let ephemeralPrivate = P256.KeyAgreement.PrivateKey()
        let ephemeralPublicRaw = ephemeralPrivate.publicKey.rawRepresentation
        let salt = randomBytes(Constants.saltBytes)
        let info = Data(Constants.hkdfInfo.utf8)

        var wrappedRecipients: [PushSnapshotRecipient] = []
        for recipient in recipients {
            guard let recipientPublic = parseRecipientPublicKey(recipient.publicKey) else { continue }
            guard let sharedSecret = try? ephemeralPrivate.sharedSecretFromKeyAgreement(with: recipientPublic) else { continue }

            let wrapSymmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
                using: SHA256.self,
                salt: salt,
                sharedInfo: info,
                outputByteCount: Constants.aesKeyBytes
            )
            let wrapKeyData = wrapSymmetricKey.withUnsafeBytes { Data($0) }
            let wrapIV = randomBytes(Constants.ivBytes)

            guard let wrappedKey = encryptAESGCM(
                plaintext: snapshotKey,
                keyData: wrapKeyData,
                iv: wrapIV
            ) else {
                continue
            }

            wrappedRecipients.append(
                PushSnapshotRecipient(
                    id: recipient.id,
                    wrappedKey: wrappedKey.base64EncodedString(),
                    wrappedKeyIv: wrapIV.base64EncodedString()
                )
            )
        }

        guard !wrappedRecipients.isEmpty else { return nil }

        return PushSnapshotUploadRequest(
            ciphertext: ciphertext.base64EncodedString(),
            snapshotIv: snapshotIV.base64EncodedString(),
            snapshotSalt: salt.base64EncodedString(),
            snapshotEphemeralPubKey: ephemeralPublicRaw.base64EncodedString(),
            snapshotMime: snapshot.mime,
            recipients: wrappedRecipients
        )
    }

    private func parseRecipientPublicKey(_ key: PushRecipientPublicKey) -> P256.KeyAgreement.PublicKey? {
        guard let xRaw = Data(base64URLEncoded: key.x),
              let yRaw = Data(base64URLEncoded: key.y) else {
            return nil
        }
        guard let x = xRaw.fixedLength(Constants.ecCoordBytes),
              let y = yRaw.fixedLength(Constants.ecCoordBytes) else {
            return nil
        }

        var raw = Data([0x04])
        raw.append(x)
        raw.append(y)
        return try? P256.KeyAgreement.PublicKey(rawRepresentation: raw)
    }

    private func encryptAESGCM(plaintext: Data, keyData: Data, iv: Data) -> Data? {
        guard let nonce = try? AES.GCM.Nonce(data: iv) else { return nil }
        let key = SymmetricKey(data: keyData)
        guard let sealed = try? AES.GCM.seal(plaintext, using: key, nonce: nonce) else { return nil }
        var combined = Data()
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        return combined
    }

    private func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status != errSecSuccess {
            return Data(repeating: 0, count: count)
        }
        return Data(bytes)
    }

    private func withTimeout<T: Sendable>(
        nanoseconds: UInt64,
        operation: @escaping @Sendable () async -> T?
    ) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask {
                await operation()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: nanoseconds)
                return nil
            }
            let result = await group.next() ?? nil
            group.cancelAll()
            return result
        }
    }
}

#if canImport(WebRTC)
private final class LocalFrameSnapshotRenderer: NSObject, RTCVideoRenderer {
    private let onFrame: (RTCVideoFrame) -> Void
    private let lock = NSLock()
    private var consumed = false

    init(onFrame: @escaping (RTCVideoFrame) -> Void) {
        self.onFrame = onFrame
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        lock.lock()
        let shouldConsume = !consumed
        if shouldConsume {
            consumed = true
        }
        lock.unlock()

        guard shouldConsume else { return }
        onFrame(frame)
    }
}
#endif

private extension Data {
    init?(base64URLEncoded input: String) {
        var normalized = input
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - normalized.count % 4) % 4
        if padding > 0 {
            normalized.append(String(repeating: "=", count: padding))
        }
        self.init(base64Encoded: normalized)
    }

    func fixedLength(_ size: Int) -> Data? {
        if count == size { return self }
        if count == size + 1, first == 0 {
            return dropFirst()
        }
        if count > size {
            return nil
        }
        var out = Data(repeating: 0, count: size - count)
        out.append(self)
        return out
    }
}
