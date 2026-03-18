import CoreMedia
import Foundation
import ReplayKit

private enum BroadcastConstants {
    static let appGroupIdentifier = "group.app.serenada.ios"
    static let sharedFileName = "broadcast_frame.dat"
    static let headerSize = 64
    static let maxFrameFileSize = 64 + 3840 * 2160 * 4 // header + 4K BGRA upper bound

    static let darwinNotifyStarted = "app.serenada.ios.broadcast.started"
    static let darwinNotifyFinished = "app.serenada.ios.broadcast.finished"
    static let darwinNotifyRequestStop = "app.serenada.ios.broadcast.requestStop"
}

private enum BroadcastSharedMemoryIO {
    static let timestampOffset = 36

    static func storeInt64(_ value: Int64, to ptr: UnsafeMutableRawPointer, byteOffset: Int) {
        var mutableValue = value
        withUnsafeBytes(of: &mutableValue) { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            memcpy(ptr.advanced(by: byteOffset), baseAddress, buffer.count)
        }
    }
}

final class SampleHandler: RPBroadcastSampleHandler {
    private var mmapPtr: UnsafeMutableRawPointer?
    private var mmapSize: Int = 0
    private var fileDescriptor: Int32 = -1
    private var isObservingStop = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard openSharedMemory() else {
            finishBroadcastWithError(NSError(domain: "SerenadaBroadcast", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Failed to open shared memory.",
            ]))
            return
        }

        registerStopObserver()
        postDarwinNotification(BroadcastConstants.darwinNotifyStarted)
    }

    override func broadcastPaused() {
        // No-op: iOS calls this when recording is paused (e.g., low memory).
    }

    override func broadcastResumed() {
        // No-op: recording resumed.
    }

    override func broadcastFinished() {
        postDarwinNotification(BroadcastConstants.darwinNotifyFinished)
        unregisterStopObserver()
        closeSharedMemory()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard let ptr = mmapPtr else { return }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let planeCount = max(CVPixelBufferGetPlaneCount(pixelBuffer), 1)
        let timestampNs = Int64(CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds * 1_000_000_000)

        let rotation = extractRotation(from: sampleBuffer)

        // Calculate total data size needed
        var totalDataSize = 0
        var planeInfos: [(bytesPerRow: Int, height: Int, dataSize: Int)] = []

        if CVPixelBufferIsPlanar(pixelBuffer) {
            for plane in 0 ..< planeCount {
                let bpr = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, plane)
                let h = CVPixelBufferGetHeightOfPlane(pixelBuffer, plane)
                let size = bpr * h
                planeInfos.append((bytesPerRow: bpr, height: h, dataSize: size))
                totalDataSize += size
            }
        } else {
            let bpr = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let size = bpr * height
            planeInfos.append((bytesPerRow: bpr, height: height, dataSize: size))
            totalDataSize = size
        }

        let requiredSize = BroadcastConstants.headerSize + totalDataSize
        guard requiredSize <= mmapSize else { return }

        // Write pixel data first (before updating header/seqNo)
        var dataOffset = BroadcastConstants.headerSize
        if CVPixelBufferIsPlanar(pixelBuffer) {
            for plane in 0 ..< planeCount {
                guard let baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, plane) else { continue }
                memcpy(ptr.advanced(by: dataOffset), baseAddress, planeInfos[plane].dataSize)
                dataOffset += planeInfos[plane].dataSize
            }
        } else {
            guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
            memcpy(ptr.advanced(by: dataOffset), baseAddress, planeInfos[0].dataSize)
        }

        // Write header fields (offsets 4..47)
        ptr.storeBytes(of: UInt32(width), toByteOffset: 4, as: UInt32.self)
        ptr.storeBytes(of: UInt32(height), toByteOffset: 8, as: UInt32.self)
        ptr.storeBytes(of: pixelFormat, toByteOffset: 12, as: UInt32.self)
        ptr.storeBytes(of: UInt32(planeCount), toByteOffset: 16, as: UInt32.self)

        let plane0 = planeInfos.indices.contains(0) ? planeInfos[0] : (bytesPerRow: 0, height: 0, dataSize: 0)
        let plane1 = planeInfos.indices.contains(1) ? planeInfos[1] : (bytesPerRow: 0, height: 0, dataSize: 0)

        ptr.storeBytes(of: UInt32(plane0.bytesPerRow), toByteOffset: 20, as: UInt32.self)
        ptr.storeBytes(of: UInt32(plane0.height), toByteOffset: 24, as: UInt32.self)
        ptr.storeBytes(of: UInt32(plane1.bytesPerRow), toByteOffset: 28, as: UInt32.self)
        ptr.storeBytes(of: UInt32(plane1.height), toByteOffset: 32, as: UInt32.self)
        BroadcastSharedMemoryIO.storeInt64(
            timestampNs,
            to: ptr,
            byteOffset: BroadcastSharedMemoryIO.timestampOffset
        )
        ptr.storeBytes(of: UInt32(rotation), toByteOffset: 44, as: UInt32.self)

        // Increment seqNo last (atomic store acts as publish barrier)
        let currentSeq = ptr.load(fromByteOffset: 0, as: UInt32.self)
        ptr.storeBytes(of: currentSeq &+ 1, toByteOffset: 0, as: UInt32.self)
    }

    // MARK: - Shared Memory

    private func openSharedMemory() -> Bool {
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: BroadcastConstants.appGroupIdentifier
        ) else { return false }

        let fileURL = containerURL.appendingPathComponent(BroadcastConstants.sharedFileName)
        let path = fileURL.path

        // Create file if needed
        if !FileManager.default.fileExists(atPath: path) {
            FileManager.default.createFile(atPath: path, contents: nil)
        }

        fileDescriptor = open(path, O_RDWR)
        guard fileDescriptor >= 0 else { return false }

        let size = BroadcastConstants.maxFrameFileSize
        // Ensure file is large enough
        ftruncate(fileDescriptor, off_t(size))

        guard let mapped = mmap(nil, size, PROT_READ | PROT_WRITE, MAP_SHARED, fileDescriptor, 0),
              mapped != MAP_FAILED
        else {
            close(fileDescriptor)
            fileDescriptor = -1
            return false
        }

        mmapPtr = mapped
        mmapSize = size

        // Zero out the header
        memset(mapped, 0, BroadcastConstants.headerSize)
        return true
    }

    private func closeSharedMemory() {
        if let ptr = mmapPtr {
            munmap(ptr, mmapSize)
            mmapPtr = nil
        }
        if fileDescriptor >= 0 {
            close(fileDescriptor)
            fileDescriptor = -1
        }
    }

    // MARK: - Darwin Notifications

    private static let darwinCenter = CFNotificationCenterGetDarwinNotifyCenter()

    private func postDarwinNotification(_ name: String) {
        CFNotificationCenterPostNotification(
            Self.darwinCenter,
            CFNotificationName(name as CFString),
            nil, nil, true
        )
    }

    private func registerStopObserver() {
        guard !isObservingStop else { return }
        isObservingStop = true
        CFNotificationCenterAddObserver(
            Self.darwinCenter,
            Unmanaged.passUnretained(self).toOpaque(),
            { _, observer, _, _, _ in
                guard let observer else { return }
                let handler = Unmanaged<SampleHandler>.fromOpaque(observer).takeUnretainedValue()
                handler.finishBroadcastWithError(NSError(domain: "SerenadaBroadcast", code: 0, userInfo: [
                    NSLocalizedDescriptionKey: "Screen sharing stopped by app.",
                ]))
            },
            BroadcastConstants.darwinNotifyRequestStop as CFString,
            nil,
            .deliverImmediately
        )
    }

    private func unregisterStopObserver() {
        guard isObservingStop else { return }
        isObservingStop = false
        CFNotificationCenterRemoveObserver(
            Self.darwinCenter,
            Unmanaged.passUnretained(self).toOpaque(),
            CFNotificationName(BroadcastConstants.darwinNotifyRequestStop as CFString),
            nil
        )
    }

    // MARK: - Rotation

    private func extractRotation(from sampleBuffer: CMSampleBuffer) -> UInt32 {
        // RPVideoSampleOrientationKey maps to CGImagePropertyOrientation values
        guard let orientationAttachment = CMGetAttachment(
            sampleBuffer,
            key: RPVideoSampleOrientationKey as CFString,
            attachmentModeOut: nil
        ) else {
            return 0 // RTCVideoRotation._0
        }

        guard let orientationValue = orientationAttachment as? NSNumber else {
            return 0
        }

        // CGImagePropertyOrientation → RTCVideoRotation raw value
        switch orientationValue.uint32Value {
        case 1: return 0 // .up → ._0
        case 3: return 180 // .down → ._180
        case 6: return 270 // .right → ._270 (camera right = 90° CW = 270° video)
        case 8: return 90 // .left → ._90 (camera left = 90° CCW = 90° video)
        default: return 0
        }
    }
}
