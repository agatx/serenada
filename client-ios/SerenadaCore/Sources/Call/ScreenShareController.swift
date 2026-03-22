import Foundation
#if canImport(WebRTC)
import WebRTC
#endif

@MainActor
final class ScreenShareController {

    private(set) var isScreenSharing = false

    // Screen share capturers
#if canImport(WebRTC)
    #if BROADCAST_EXTENSION
    private var broadcastFrameReader: BroadcastFrameReader?
    #else
    private var replayKitCapturer: ReplayKitVideoCapturer?
    #endif
#endif

    // MARK: - Dependencies

    private let cameraController: CameraCaptureController
#if canImport(WebRTC)
    private let localVideoSourceProvider: () -> RTCVideoSource?
    private let isLocalVideoTrackEnabled: () -> Bool
#endif
    private let setLocalVideoTrackEnabled: (Bool) -> Void
    var onScreenShareStopped: () -> Void
    private let onStateChanged: (Bool) -> Void
    private let logger: SerenadaLogger?

    // MARK: - Init

#if canImport(WebRTC)
    init(
        cameraController: CameraCaptureController,
        localVideoSourceProvider: @escaping () -> RTCVideoSource?,
        isLocalVideoTrackEnabled: @escaping () -> Bool,
        setLocalVideoTrackEnabled: @escaping (Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onStateChanged: @escaping (Bool) -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.cameraController = cameraController
        self.localVideoSourceProvider = localVideoSourceProvider
        self.isLocalVideoTrackEnabled = isLocalVideoTrackEnabled
        self.setLocalVideoTrackEnabled = setLocalVideoTrackEnabled
        self.onScreenShareStopped = onScreenShareStopped
        self.onStateChanged = onStateChanged
        self.logger = logger
    }
#else
    init(
        cameraController: CameraCaptureController,
        setLocalVideoTrackEnabled: @escaping (Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onStateChanged: @escaping (Bool) -> Void,
        logger: SerenadaLogger? = nil
    ) {
        self.cameraController = cameraController
        self.setLocalVideoTrackEnabled = setLocalVideoTrackEnabled
        self.onScreenShareStopped = onScreenShareStopped
        self.onStateChanged = onStateChanged
        self.logger = logger
    }
#endif

    // MARK: - Screen Share

    func startScreenShare(onComplete: ((Bool) -> Void)? = nil) -> Bool {
#if canImport(WebRTC)
        guard let localVideoSource = localVideoSourceProvider() else {
            onComplete?(false)
            return false
        }
        if isScreenSharing {
            onComplete?(true)
            return true
        }

        let previousSource = cameraController.localCameraSource
        cameraController.preScreenShareCameraSource = previousSource

    #if BROADCAST_EXTENSION
        // Defer camera teardown until broadcast actually starts (user confirms picker)
        logger?.log(.info, tag: "ScreenShare", "startScreenShare: BROADCAST_EXTENSION path, creating BroadcastFrameReader")
        let reader = BroadcastFrameReader(delegate: localVideoSource)
        broadcastFrameReader = reader

        var startTimeoutTask: Task<Void, Never>?

        reader.onBroadcastStarted = { [weak self] in
            Task { @MainActor in
                self?.logger?.log(.info, tag: "ScreenShare", "startScreenShare: onBroadcastStarted callback fired")
                startTimeoutTask?.cancel()
                guard let self else { return }
                guard self.broadcastFrameReader === reader else {
                    self.logger?.log(.error, tag: "ScreenShare", "startScreenShare: reader mismatch, ignoring")
                    return
                }
                // Now tear down camera — broadcast is confirmed
                self.logger?.log(.info, tag: "ScreenShare", "startScreenShare: tearing down camera, setting isScreenSharing=true")
                self.cameraController.stopAllCapturers()
                self.isScreenSharing = true
                self.cameraController.isScreenSharing = true
                self.cameraController.notifyCameraModeAndFlash()
                self.setLocalVideoTrackEnabled(true)
                self.onStateChanged(true)
                self.logger?.log(.info, tag: "ScreenShare", "startScreenShare: calling onComplete(true)")
                onComplete?(true)
            }
        }

        reader.onBroadcastFinished = { [weak self] in
            Task { @MainActor in
                self?.logger?.log(.info, tag: "ScreenShare", "startScreenShare: onBroadcastFinished callback fired")
                guard let self else { return }
                guard self.broadcastFrameReader === reader else { return }
                _ = self.stopScreenShare()
            }
        }

        reader.startListening()

        // Timeout: if broadcast doesn't start within 30s, restore camera
        startTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard self.broadcastFrameReader === reader, !self.isScreenSharing else { return }
            self.broadcastFrameReader?.stopListening()
            self.broadcastFrameReader = nil
            _ = self.cameraController.restartVideoCapturer(source: previousSource)
            self.cameraController.notifyCameraModeAndFlash()
            onComplete?(false)
        }

        return true
    #else
        cameraController.stopAllCapturers()

        let capturer = ReplayKitVideoCapturer(delegate: localVideoSource)
        replayKitCapturer = capturer

        return capturer.startCapture { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                if started {
                    self.isScreenSharing = true
                    self.cameraController.isScreenSharing = true
                    self.cameraController.notifyCameraModeAndFlash()
                    self.setLocalVideoTrackEnabled(true)
                    self.onStateChanged(true)
                    onComplete?(true)
                    return
                }

                self.replayKitCapturer = nil
                self.isScreenSharing = false
                self.cameraController.isScreenSharing = false
                self.onStateChanged(false)
                _ = self.cameraController.restartVideoCapturer(source: previousSource)
                self.cameraController.notifyCameraModeAndFlash()
                onComplete?(false)
            }
        }
    #endif
#else
        onComplete?(false)
        return false
#endif
    }

    func stopScreenShare() -> Bool {
#if canImport(WebRTC)
    #if BROADCAST_EXTENSION
        broadcastFrameReader?.stopListening()
        broadcastFrameReader = nil
    #else
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
    #endif
#endif
        if isScreenSharing {
            isScreenSharing = false
            cameraController.isScreenSharing = false
            onStateChanged(false)
            let restoreSource = cameraController.preScreenShareCameraSource
            cameraController.preScreenShareCameraSource = .selfie
#if canImport(WebRTC)
            if isLocalVideoTrackEnabled() {
                _ = cameraController.restartVideoCapturer(source: restoreSource)
            } else {
                cameraController.localCameraSource = restoreSource
                cameraController.notifyCameraModeAndFlash()
            }
#endif
            onScreenShareStopped()
        }
        return true
    }

    /// Stop all screen share capturers without triggering state callbacks.
    /// Called by WebRtcEngine during stopLocalMedia cleanup.
    func stopAllCapturers() {
#if canImport(WebRTC)
    #if BROADCAST_EXTENSION
        broadcastFrameReader?.stopListening()
        broadcastFrameReader = nil
    #else
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
    #endif
#endif
        isScreenSharing = false
        cameraController.isScreenSharing = false
    }
}
