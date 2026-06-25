import AVFoundation
import Foundation
import os.log
#if canImport(ReplayKit)
import ReplayKit
#endif
#if canImport(WebRTC)
import WebRTC
#endif

#if canImport(WebRTC)
import SerenadaBroadcastExtensionSupport

/// Listening seam for the broadcast frame reader. The concrete
/// `BroadcastFrameReader` reads frames off shared memory and only flips its
/// `onBroadcastStarted`/`onBroadcastFinished` callbacks in response to real
/// Darwin notifications from the broadcast upload extension (device-only). The
/// protocol lets `ScreenShareController` be exercised with a fake reader that
/// drives those callbacks directly in unit tests, so the pending-broadcast
/// window (reader listening, `onBroadcastStarted` not yet fired) is reachable.
protocol BroadcastFrameReading: AnyObject {
    var onBroadcastStarted: (() -> Void)? { get set }
    var onBroadcastFinished: (() -> Void)? { get set }
    func startListening()
    func stopListening()
}

final class BroadcastFrameReader: RTCVideoCapturer, BroadcastFrameReading {
    private static let log = OSLog(subsystem: "app.serenada.ios", category: "BroadcastFrameReader")

    var onBroadcastStarted: (() -> Void)?
    var onBroadcastFinished: (() -> Void)?

    private let config: BroadcastIPCConfig

    private var mmapPtr: UnsafeMutableRawPointer?
    private var mmapSize: Int = 0
    private var fileDescriptor: Int32 = -1
    private var lastSeqNo: UInt32 = 0
    private var frameCount: UInt64 = 0

    private var pollTimer: DispatchSourceTimer?
    private var isListening = false

    // Session lifecycle (R-IPC1): a per-share generation plus an active-call /
    // heartbeat marker in the sidecar lets the extension recognize a live reader
    // and lets this reader reject frames stamped by a stale session.
    private var sessionStore: BroadcastSessionStore?
    private var sessionId = ""
    private var currentGeneration: UInt32 = 0
    private var heartbeatTimer: DispatchSourceTimer?
    /// Serial queue for heartbeat writes so teardown can drain an in-flight beat
    /// before clearing the session — without it a beat could rewrite the marker
    /// after `clear()` and leave a stale "live" session.
    private let heartbeatQueue = DispatchQueue(label: "app.serenada.ios.broadcast.heartbeat")
    private static let heartbeatQueueKey = DispatchSpecificKey<Bool>()

    private static let darwinCenter = CFNotificationCenterGetDarwinNotifyCenter()

    init(delegate: RTCVideoCapturerDelegate, config: BroadcastIPCConfig) {
        self.config = config
        super.init(delegate: delegate)
        heartbeatQueue.setSpecific(key: Self.heartbeatQueueKey, value: true)
    }

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

        // Establish the session before the picker can launch the extension: bump
        // the generation, then publish the active-call marker + first heartbeat so
        // a broadcast that starts now is recognized as live (R-IPC1/R-IPC2).
        let store = BroadcastSessionStore(config: config)
        sessionStore = store
        let gen = store.nextGeneration()
        currentGeneration = gen
        let sid = UUID().uuidString
        sessionId = sid
        store.write(BroadcastSessionSidecar(
            sessionId: sid,
            generation: gen,
            activeCall: true,
            heartbeatMs: BroadcastSessionStore.nowMs()
        ))
        startHeartbeat()

        let observer = Unmanaged.passUnretained(self).toOpaque()

        CFNotificationCenterAddObserver(
            Self.darwinCenter, observer,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let reader = Unmanaged<BroadcastFrameReader>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { reader.handleBroadcastStarted() }
            },
            config.darwinNotifyStarted as CFString,
            nil, .deliverImmediately
        )

        CFNotificationCenterAddObserver(
            Self.darwinCenter, observer,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let reader = Unmanaged<BroadcastFrameReader>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { reader.handleBroadcastFinished() }
            },
            config.darwinNotifyFinished as CFString,
            nil, .deliverImmediately
        )
    }

    func stopListening() {
        guard isListening else { return }
        isListening = false

        // Stop the heartbeat first, then clear the session so a later Control
        // Center start cannot read a stale active marker as live (R-IPC1).
        stopHeartbeat()

        // Request the extension to stop
        requestExtensionStop()

        stopPolling()
        closeSharedMemory()

        sessionStore?.clear()
        sessionStore = nil
        currentGeneration = 0

        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterRemoveObserver(Self.darwinCenter, observer, nil, nil)

        onBroadcastStarted = nil
        onBroadcastFinished = nil
    }

    private func requestExtensionStop() {
        CFNotificationCenterPostNotification(
            Self.darwinCenter,
            CFNotificationName(config.darwinNotifyRequestStop as CFString),
            nil, nil, true
        )
    }

    // MARK: - Heartbeat

    private func startHeartbeat() {
        stopHeartbeat()
        let timer = DispatchSource.makeTimerSource(queue: heartbeatQueue)
        timer.schedule(
            deadline: .now() + .milliseconds(BroadcastShared.heartbeatIntervalMs),
            repeating: .milliseconds(BroadcastShared.heartbeatIntervalMs)
        )
        timer.setEventHandler { [weak self] in
            guard let self, self.isListening, let store = self.sessionStore else { return }
            store.write(BroadcastSessionSidecar(
                sessionId: self.sessionId,
                generation: self.currentGeneration,
                activeCall: true,
                heartbeatMs: BroadcastSessionStore.nowMs()
            ))
        }
        heartbeatTimer = timer
        timer.resume()
    }

    private func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        // Drain any in-flight beat so a heartbeat cannot write the sidecar after
        // the caller clears it. Skip the drain if we are already on the heartbeat
        // queue (a sync onto the current queue would deadlock) — teardown normally
        // runs on the main actor.
        if DispatchQueue.getSpecific(key: Self.heartbeatQueueKey) != true {
            heartbeatQueue.sync {}
        }
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

        // Odd/even seqlock: an odd seqNo means a write is in progress, so skip it.
        // After reading the frame we re-check seqNo to confirm it was stable across
        // the read (matching the writer's odd→even publish).
        let seqNo = ptr.load(fromByteOffset: BroadcastHeaderOffset.seqNo, as: UInt32.self)
        guard seqNo & 1 == 0 else { return }
        guard seqNo != lastSeqNo else { return }
        OSMemoryBarrier()

        let generation = ptr.load(fromByteOffset: BroadcastHeaderOffset.generation, as: UInt32.self)
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
        guard let frameLayout = validateFrameLayout(
            width: width,
            height: height,
            planeCount: planeCount,
            plane0BytesPerRow: plane0BytesPerRow,
            plane0Height: plane0Height,
            plane1BytesPerRow: plane1BytesPerRow,
            plane1Height: plane1Height
        ) else { return }

        // Confirm the writer did not start another publish after we sampled the
        // header but before we trust its dimensions for allocation/copy sizes.
        OSMemoryBarrier()
        let seqNoBeforeCopy = ptr.load(fromByteOffset: BroadcastHeaderOffset.seqNo, as: UInt32.self)
        guard seqNoBeforeCopy == seqNo else { return }

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
            let plane0Size = frameLayout.plane0Size
            let plane1Size = frameLayout.plane1Size

            let status = CVPixelBufferCreate(
                kCFAllocatorDefault,
                width, height,
                OSType(pixelFormat),
                pixelBufferAttrs as CFDictionary,
                &pixelBuffer
            )
            guard status == kCVReturnSuccess, let pixelBuffer else { return }

            CVPixelBufferLockBaseAddress(pixelBuffer, [])
            guard plane0Height <= CVPixelBufferGetHeightOfPlane(pixelBuffer, 0),
                  plane1Height <= CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
            else {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
                return
            }
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
            guard height <= CVPixelBufferGetHeight(pixelBuffer) else {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
                return
            }
            if let dest = CVPixelBufferGetBaseAddress(pixelBuffer) {
                let destBpr = CVPixelBufferGetBytesPerRow(pixelBuffer)
                if destBpr == plane0BytesPerRow {
                    memcpy(dest, dataStart, frameLayout.plane0Size)
                } else {
                    for row in 0 ..< height {
                        memcpy(dest.advanced(by: row * destBpr), dataStart.advanced(by: row * plane0BytesPerRow), min(destBpr, plane0BytesPerRow))
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        }

        // Seqlock validation: re-read seqNo after the data read. If it changed (or
        // went odd) the writer raced us — discard this torn frame.
        OSMemoryBarrier()
        let seqNoAfter = ptr.load(fromByteOffset: BroadcastHeaderOffset.seqNo, as: UInt32.self)
        guard seqNoAfter == seqNo else { return }

        // Reject frames stamped by a stale session (a prior share, an app kill, or
        // a start with no live call), and never accept generation 0 (a zeroed
        // header). currentGeneration is set at startListening and is never 0.
        guard generation != 0, generation == currentGeneration else { return }

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

    private struct BroadcastFrameLayout {
        let plane0Size: Int
        let plane1Size: Int
    }

    private func validateFrameLayout(
        width: Int,
        height: Int,
        planeCount: Int,
        plane0BytesPerRow: Int,
        plane0Height: Int,
        plane1BytesPerRow: Int,
        plane1Height: Int
    ) -> BroadcastFrameLayout? {
        guard width > 0, height > 0 else { return nil }
        guard mmapSize > BroadcastShared.headerSize else { return nil }
        let availableBytes = UInt64(mmapSize - BroadcastShared.headerSize)

        func byteCount(bytesPerRow: Int, rows: Int) -> UInt64? {
            guard bytesPerRow > 0, rows > 0 else { return nil }
            let count = UInt64(bytesPerRow) * UInt64(rows)
            return count <= availableBytes ? count : nil
        }

        let plane0Rows = planeCount > 1 ? plane0Height : height
        guard let plane0Size64 = byteCount(bytesPerRow: plane0BytesPerRow, rows: plane0Rows) else {
            return nil
        }

        if planeCount > 1 {
            guard planeCount == 2,
                  let plane1Size64 = byteCount(bytesPerRow: plane1BytesPerRow, rows: plane1Height)
            else { return nil }
            let (total, overflow) = plane0Size64.addingReportingOverflow(plane1Size64)
            guard !overflow, total <= availableBytes else { return nil }
            return BroadcastFrameLayout(plane0Size: Int(plane0Size64), plane1Size: Int(plane1Size64))
        }

        guard planeCount == 1, plane0Size64 <= availableBytes else { return nil }
        return BroadcastFrameLayout(plane0Size: Int(plane0Size64), plane1Size: 0)
    }

    // MARK: - Shared Memory

    private func openSharedMemory() -> Bool {
        guard mmapPtr == nil else {
            os_log("openSharedMemory: already mapped", log: Self.log, type: .info)
            return true
        }
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: config.appGroupIdentifier
        ) else {
            os_log("openSharedMemory: containerURL is nil for group %{public}@", log: Self.log, type: .error, config.appGroupIdentifier)
            return false
        }

        let fileURL = containerURL.appendingPathComponent(config.sharedFileName)
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
#if canImport(ReplayKit)
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
