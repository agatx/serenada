import CoreImage
import Foundation
import UIKit
@preconcurrency import WebRTC

/// Captures a single full-resolution JPEG frame from a renderer-attachable
/// source — used by `SerenadaSession.captureSnapshot` for both local and
/// remote streams. Caller supplies the attach/detach callbacks bound to the
/// chosen track; this type owns the renderer, the timeout, and JPEG encoding.
@MainActor
internal final class FrameSnapshotCapturer {
    private let attachRenderer: @MainActor (AnyObject) -> Void
    private let detachRenderer: @MainActor (AnyObject) -> Void
    private let jpegQuality: CGFloat

    init(
        attachRenderer: @escaping @MainActor (AnyObject) -> Void,
        detachRenderer: @escaping @MainActor (AnyObject) -> Void,
        jpegQuality: CGFloat = 0.95
    ) {
        self.attachRenderer = attachRenderer
        self.detachRenderer = detachRenderer
        self.jpegQuality = jpegQuality
    }

    func capture(timeoutMs: Int) async throws -> (jpegData: Data, width: Int, height: Int) {
        let outcome = await captureFrame(timeoutMs: timeoutMs, quality: jpegQuality)
        switch outcome {
        case .success(let data, let width, let height):
            return (jpegData: data, width: width, height: height)
        case .timeout:
            throw SnapshotError.captureTimeout
        case .failed(let reason):
            throw SnapshotError.captureFailed(reason)
        }
    }

    fileprivate enum CaptureOutcome {
        case success(Data, Int, Int)
        case timeout
        case failed(String)
    }

    private func captureFrame(timeoutMs: Int, quality: CGFloat) async -> CaptureOutcome {
        let timeoutNs = UInt64(max(timeoutMs, 1)) * 1_000_000
        let detach = self.detachRenderer
        return await withCheckedContinuation { (continuation: CheckedContinuation<CaptureOutcome, Never>) in
            let state = SnapshotCaptureState(continuation: continuation, detach: detach)

            // Capture state strongly in both the renderer and the timeout
            // task: once the `withCheckedContinuation` closure returns its
            // local `state` ref drops, and a weak capture would let the
            // state (and continuation) deallocate before resume — hanging
            // the caller forever. The strong cycle through state →
            // attachedRenderer/timeoutTask is broken inside `finish`.
            let renderer = SnapshotRenderer { [state] frame in
                guard let encoded = encodeFullResolutionJpeg(frame: frame, quality: quality) else {
                    return false
                }
                Task { @MainActor in
                    state.finish(.success(encoded.jpegData, encoded.width, encoded.height))
                }
                return true
            }

            state.attachedRenderer = renderer
            attachRenderer(renderer)

            let timeoutTask = Task { @MainActor [state] in
                try? await Task.sleep(nanoseconds: timeoutNs)
                state.finish(.timeout)
            }
            state.timeoutTask = timeoutTask
        }
    }
}

/// Owns the lifecycle for a single in-flight snapshot capture: renderer,
/// timeout, and the resume-once continuation. All accesses are main-actor
/// isolated so the renderer/timeout race is decided on a single queue.
@MainActor
private final class SnapshotCaptureState {
    var attachedRenderer: AnyObject?
    var timeoutTask: Task<Void, Never>?
    private var finished = false
    private let continuation: CheckedContinuation<FrameSnapshotCapturer.CaptureOutcome, Never>
    private let detach: @MainActor (AnyObject) -> Void

    init(
        continuation: CheckedContinuation<FrameSnapshotCapturer.CaptureOutcome, Never>,
        detach: @escaping @MainActor (AnyObject) -> Void
    ) {
        self.continuation = continuation
        self.detach = detach
    }

    func finish(_ outcome: FrameSnapshotCapturer.CaptureOutcome) {
        guard !finished else { return }
        finished = true
        timeoutTask?.cancel()
        timeoutTask = nil
        if let renderer = attachedRenderer {
            detach(renderer)
            attachedRenderer = nil
        }
        continuation.resume(returning: outcome)
    }
}

private final class SnapshotRenderer: NSObject, RTCVideoRenderer {
    private let onFrame: (RTCVideoFrame) -> Bool
    private let lock = NSLock()
    private var consumed = false

    init(onFrame: @escaping (RTCVideoFrame) -> Bool) {
        self.onFrame = onFrame
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        lock.lock()
        if consumed {
            lock.unlock()
            return
        }
        lock.unlock()

        let consumedNow = onFrame(frame)
        if consumedNow {
            lock.lock()
            consumed = true
            lock.unlock()
        }
    }
}

private struct EncodedFrame {
    let jpegData: Data
    let width: Int
    let height: Int
}

private func encodeFullResolutionJpeg(frame: RTCVideoFrame, quality: CGFloat) -> EncodedFrame? {
    let pixelBuffer: CVPixelBuffer?
    if let cv = frame.buffer as? RTCCVPixelBuffer {
        pixelBuffer = cv.pixelBuffer
    } else {
        // Software-decoded path: convert I420 planes into a fresh CVPixelBuffer
        // so the rest of the encode pipeline (CIImage → JPEG) works unchanged.
        let i420 = frame.buffer.toI420()
        pixelBuffer = i420ToCVPixelBuffer(i420)
    }
    guard let pixelBuffer else { return nil }

    var ciImage = CIImage(cvPixelBuffer: pixelBuffer)
    if let orientation = cgOrientation(for: frame.rotation) {
        ciImage = ciImage.oriented(orientation)
    }
    let extent = ciImage.extent
    if extent.isEmpty || extent.width <= 0 || extent.height <= 0 {
        return nil
    }

    let context = CIContext()
    guard let cgImage = context.createCGImage(ciImage, from: extent) else { return nil }
    let uiImage = UIImage(cgImage: cgImage)
    guard let data = uiImage.jpegData(compressionQuality: quality) else { return nil }

    return EncodedFrame(jpegData: data, width: cgImage.width, height: cgImage.height)
}

private func i420ToCVPixelBuffer(_ i420: RTCI420BufferProtocol) -> CVPixelBuffer? {
    let width = Int(i420.width)
    let height = Int(i420.height)
    guard width > 0, height > 0 else { return nil }
    // NV12 (BiPlanar Y + interleaved UV) is iOS's native YUV layout and the
    // one CIImage will actually render through `createCGImage`. The 3-plane
    // 420YpCbCr8Planar format is not IOSurface-compatible, so CIImage
    // silently produces no output and the snapshot capture times out.
    var buffer: CVPixelBuffer?
    let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:]]
    let status = CVPixelBufferCreate(
        kCFAllocatorDefault,
        width,
        height,
        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        attrs as CFDictionary,
        &buffer
    )
    guard status == kCVReturnSuccess, let pb = buffer else { return nil }

    CVPixelBufferLockBaseAddress(pb, [])
    defer { CVPixelBufferUnlockBaseAddress(pb, []) }

    guard let yDst = CVPixelBufferGetBaseAddressOfPlane(pb, 0),
          let uvDst = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else {
        return nil
    }
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
    let yBytes = yDst.assumingMemoryBound(to: UInt8.self)
    let uvBytes = uvDst.assumingMemoryBound(to: UInt8.self)

    let srcYStride = Int(i420.strideY)
    for row in 0..<height {
        memcpy(
            yBytes.advanced(by: row * yStride),
            i420.dataY.advanced(by: row * srcYStride),
            width
        )
    }

    let chromaWidth = width / 2
    let chromaHeight = height / 2
    let strideU = Int(i420.strideU)
    let strideV = Int(i420.strideV)
    for row in 0..<chromaHeight {
        let srcURow = i420.dataU.advanced(by: row * strideU)
        let srcVRow = i420.dataV.advanced(by: row * strideV)
        let dstRow = uvBytes.advanced(by: row * uvStride)
        for x in 0..<chromaWidth {
            dstRow[x * 2] = srcURow[x]
            dstRow[x * 2 + 1] = srcVRow[x]
        }
    }

    return pb
}

private func cgOrientation(for rotation: RTCVideoRotation) -> CGImagePropertyOrientation? {
    switch Int(rotation.rawValue) {
    case 0: return .up
    case 90: return .right
    case 180: return .down
    case 270: return .left
    default: return nil
    }
}
