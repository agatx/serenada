import AVFoundation
import Foundation
import os.log
#if !BROADCAST_EXTENSION && canImport(ReplayKit)
import ReplayKit
#endif
#if canImport(WebRTC)
import WebRTC
#endif

#if canImport(WebRTC)

#if BROADCAST_EXTENSION
final class BroadcastFrameReader: RTCVideoCapturer {
    private static let log = OSLog(subsystem: "app.serenada.ios", category: "BroadcastFrameReader")

    var onBroadcastStarted: (() -> Void)?
    var onBroadcastFinished: (() -> Void)?

    private var mmapPtr: UnsafeMutableRawPointer?
    private var mmapSize: Int = 0
    private var fileDescriptor: Int32 = -1
    private var lastSeqNo: UInt32 = 0
    private var frameCount: UInt64 = 0

    private var pollTimer: DispatchSourceTimer?
    private var isListening = false

    private static let darwinCenter = CFNotificationCenterGetDarwinNotifyCenter()

    deinit {
        stopListening()
    }

    func startListening() {
        guard !isListening else {
            os_log("startListening: already listening, skipping", log: Self.log, type: .info)
            return
        }
        isListening = true
        os_log("startListening: registered Darwin observers for broadcastStarted/Finished", log: Self.log, type: .info)

        let observer = Unmanaged.passUnretained(self).toOpaque()

        CFNotificationCenterAddObserver(
            Self.darwinCenter, observer,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let reader = Unmanaged<BroadcastFrameReader>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { reader.handleBroadcastStarted() }
            },
            BroadcastShared.darwinNotifyStarted as CFString,
            nil, .deliverImmediately
        )

        CFNotificationCenterAddObserver(
            Self.darwinCenter, observer,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let reader = Unmanaged<BroadcastFrameReader>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { reader.handleBroadcastFinished() }
            },
            BroadcastShared.darwinNotifyFinished as CFString,
            nil, .deliverImmediately
        )
    }

    func stopListening() {
        guard isListening else { return }
        isListening = false

        // Request the extension to stop
        requestExtensionStop()

        stopPolling()
        closeSharedMemory()

        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterRemoveObserver(Self.darwinCenter, observer, nil, nil)

        onBroadcastStarted = nil
        onBroadcastFinished = nil
    }

    private func requestExtensionStop() {
        CFNotificationCenterPostNotification(
            Self.darwinCenter,
            CFNotificationName(BroadcastShared.darwinNotifyRequestStop as CFString),
            nil, nil, true
        )
    }

    // MARK: - Darwin Notification Handlers

    private func handleBroadcastStarted() {
        os_log("handleBroadcastStarted: isListening=%{public}d", log: Self.log, type: .info, isListening)
        guard isListening else { return }
        let memOk = openSharedMemory()
        os_log("handleBroadcastStarted: openSharedMemory=%{public}d", log: Self.log, type: .info, memOk)
        guard memOk else { return }
        startPolling()
        os_log("handleBroadcastStarted: calling onBroadcastStarted callback (nil=%{public}d)", log: Self.log, type: .info, onBroadcastStarted == nil)
        onBroadcastStarted?()
    }

    private func handleBroadcastFinished() {
        os_log("handleBroadcastFinished: isListening=%{public}d", log: Self.log, type: .info, isListening)
        guard isListening else { return }
        stopPolling()
        closeSharedMemory()
        onBroadcastFinished?()
    }

    // MARK: - Polling

    private func startPolling() {
        stopPolling()
        lastSeqNo = 0
        frameCount = 0
        os_log("startPolling: beginning frame polling at %dms interval", log: Self.log, type: .info, BroadcastShared.pollIntervalMs)

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInteractive))
        timer.schedule(
            deadline: .now(),
            repeating: .milliseconds(BroadcastShared.pollIntervalMs)
        )
        timer.setEventHandler { [weak self] in
            self?.pollFrame()
        }
        pollTimer = timer
        timer.resume()
    }

    private func stopPolling() {
        pollTimer?.cancel()
        pollTimer = nil
    }

    private func pollFrame() {
        guard let ptr = mmapPtr else { return }

        // Seqlock read: load seqNo, read header + data, re-check seqNo.
        // If the writer changed seqNo mid-read, skip this frame to avoid torn data.
        let seqNo = ptr.load(fromByteOffset: BroadcastHeaderOffset.seqNo, as: UInt32.self)
        guard seqNo != lastSeqNo else { return }

        let width = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.width, as: UInt32.self))
        let height = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.height, as: UInt32.self))
        let pixelFormat = ptr.load(fromByteOffset: BroadcastHeaderOffset.pixelFormat, as: UInt32.self)
        let planeCount = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.planeCount, as: UInt32.self))
        let plane0BytesPerRow = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.plane0BytesPerRow, as: UInt32.self))
        let plane0Height = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.plane0Height, as: UInt32.self))
        let plane1BytesPerRow = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.plane1BytesPerRow, as: UInt32.self))
        let plane1Height = Int(ptr.load(fromByteOffset: BroadcastHeaderOffset.plane1Height, as: UInt32.self))
        let timestampNs = BroadcastSharedMemoryIO.loadInt64(
            from: UnsafeRawPointer(ptr),
            byteOffset: BroadcastHeaderOffset.timestampNs
        )
        let rotationRaw = ptr.load(fromByteOffset: BroadcastHeaderOffset.rotation, as: UInt32.self)

        guard width > 0, height > 0 else { return }

        let rotation: RTCVideoRotation
        switch rotationRaw {
        case 90: rotation = ._90
        case 180: rotation = ._180
        case 270: rotation = ._270
        default: rotation = ._0
        }

        let dataStart = ptr.advanced(by: BroadcastShared.headerSize)
        let pixelBufferAttrs: [String: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]

        var pixelBuffer: CVPixelBuffer?

        if planeCount > 1 {
            // Multi-planar (NV12 / 420v / 420f)
            let plane0Size = plane0BytesPerRow * plane0Height
            let plane1Size = plane1BytesPerRow * plane1Height

            let status = CVPixelBufferCreate(
                kCFAllocatorDefault,
                width, height,
                OSType(pixelFormat),
                pixelBufferAttrs as CFDictionary,
                &pixelBuffer
            )
            guard status == kCVReturnSuccess, let pixelBuffer else { return }

            CVPixelBufferLockBaseAddress(pixelBuffer, [])
            if let dest0 = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) {
                let destBpr0 = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
                if destBpr0 == plane0BytesPerRow {
                    memcpy(dest0, dataStart, plane0Size)
                } else {
                    for row in 0 ..< plane0Height {
                        memcpy(dest0.advanced(by: row * destBpr0), dataStart.advanced(by: row * plane0BytesPerRow), min(destBpr0, plane0BytesPerRow))
                    }
                }
            }
            if let dest1 = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1) {
                let destBpr1 = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
                let srcPlane1 = dataStart.advanced(by: plane0Size)
                if destBpr1 == plane1BytesPerRow {
                    memcpy(dest1, srcPlane1, plane1Size)
                } else {
                    for row in 0 ..< plane1Height {
                        memcpy(dest1.advanced(by: row * destBpr1), srcPlane1.advanced(by: row * plane1BytesPerRow), min(destBpr1, plane1BytesPerRow))
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        } else {
            // Single-plane (BGRA)
            let status = CVPixelBufferCreate(
                kCFAllocatorDefault,
                width, height,
                OSType(pixelFormat),
                pixelBufferAttrs as CFDictionary,
                &pixelBuffer
            )
            guard status == kCVReturnSuccess, let pixelBuffer else { return }

            CVPixelBufferLockBaseAddress(pixelBuffer, [])
            if let dest = CVPixelBufferGetBaseAddress(pixelBuffer) {
                let destBpr = CVPixelBufferGetBytesPerRow(pixelBuffer)
                if destBpr == plane0BytesPerRow {
                    memcpy(dest, dataStart, plane0BytesPerRow * height)
                } else {
                    for row in 0 ..< height {
                        memcpy(dest.advanced(by: row * destBpr), dataStart.advanced(by: row * plane0BytesPerRow), min(destBpr, plane0BytesPerRow))
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        }

        // Seqlock validation: if the writer produced a new frame while we were reading,
        // discard this frame to avoid delivering torn/mixed data.
        let seqNoAfter = ptr.load(fromByteOffset: BroadcastHeaderOffset.seqNo, as: UInt32.self)
        guard seqNoAfter == seqNo else { return }

        lastSeqNo = seqNo
        frameCount += 1

        if frameCount == 1 {
            os_log("pollFrame: first frame — seqNo=%u width=%d height=%d pixelFormat=0x%x planes=%d", log: Self.log, type: .info, seqNo, width, height, pixelFormat, planeCount)
        } else if frameCount % 100 == 0 {
            os_log("pollFrame: frame #%llu — seqNo=%u width=%d height=%d", log: Self.log, type: .info, frameCount, seqNo, width, height)
        }

        guard let deliverBuffer = pixelBuffer else { return }
        let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: deliverBuffer)
        let frame = RTCVideoFrame(buffer: rtcBuffer, rotation: rotation, timeStampNs: timestampNs)
        delegate?.capturer(self, didCapture: frame)
    }

    // MARK: - Shared Memory

    private func openSharedMemory() -> Bool {
        guard mmapPtr == nil else {
            os_log("openSharedMemory: already mapped", log: Self.log, type: .info)
            return true
        }
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: BroadcastShared.appGroupIdentifier
        ) else {
            os_log("openSharedMemory: containerURL is nil for group %{public}@", log: Self.log, type: .error, BroadcastShared.appGroupIdentifier)
            return false
        }

        let fileURL = containerURL.appendingPathComponent(BroadcastShared.sharedFileName)
        let path = fileURL.path
        os_log("openSharedMemory: path=%{public}@", log: Self.log, type: .info, path)

        let exists = FileManager.default.fileExists(atPath: path)
        os_log("openSharedMemory: fileExists=%{public}d", log: Self.log, type: .info, exists)
        guard exists else { return false }

        fileDescriptor = open(path, O_RDONLY)
        os_log("openSharedMemory: fd=%d", log: Self.log, type: .info, fileDescriptor)
        guard fileDescriptor >= 0 else { return false }

        var stat = stat()
        fstat(fileDescriptor, &stat)
        let size = Int(stat.st_size)
        os_log("openSharedMemory: fileSize=%d headerSize=%d", log: Self.log, type: .info, size, BroadcastShared.headerSize)
        guard size > BroadcastShared.headerSize else {
            os_log("openSharedMemory: file too small", log: Self.log, type: .error)
            close(fileDescriptor)
            fileDescriptor = -1
            return false
        }

        guard let mapped = mmap(nil, size, PROT_READ, MAP_SHARED, fileDescriptor, 0),
              mapped != MAP_FAILED
        else {
            os_log("openSharedMemory: mmap failed, errno=%d", log: Self.log, type: .error, errno)
            close(fileDescriptor)
            fileDescriptor = -1
            return false
        }

        os_log("openSharedMemory: mmap OK, size=%d", log: Self.log, type: .info, size)
        mmapPtr = mapped
        mmapSize = size
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
}
#else
final class ReplayKitVideoCapturer: RTCVideoCapturer {
    private let recorder = RPScreenRecorder.shared()
    private var isRunning = false

    @discardableResult
    func startCapture(onReady: @escaping (Bool) -> Void) -> Bool {
        guard !isRunning else {
            onReady(true)
            return true
        }

        recorder.isMicrophoneEnabled = false
        recorder.startCapture(
            handler: { [weak self] sampleBuffer, sampleType, _ in
                guard let self else { return }
                guard sampleType == .video else { return }
                guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

                let timestampNs = Int64(CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds * 1_000_000_000)
                let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
                let frame = RTCVideoFrame(buffer: rtcBuffer, rotation: ._0, timeStampNs: timestampNs)
                self.delegate?.capturer(self, didCapture: frame)
            },
            completionHandler: { [weak self] error in
                let success = (error == nil)
                self?.isRunning = success
                onReady(success)
            }
        )

        return true
    }

    func stopCapture() {
        guard isRunning else { return }
        recorder.stopCapture { [weak self] _ in
            self?.isRunning = false
        }
    }
}
#endif

#endif
