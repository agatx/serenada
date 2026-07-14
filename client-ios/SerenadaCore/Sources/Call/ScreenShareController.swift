import Foundation
import WebRTC
import SerenadaBroadcastExtensionSupport

@MainActor
final class ScreenShareController {

    private(set) var isScreenSharing = false

    // Screen-share capturers. Both capture paths are compiled unconditionally;
    // the active one is selected at runtime from `screenShareMode` (this replaced
    // the former `BROADCAST_EXTENSION` compile flag).
    /// Shared-memory reader for the broadcast (full-device) path. Non-nil only
    /// while a broadcast share is pending or live.
    private var broadcastFrameReader: BroadcastFrameReading?
    /// Pending-start timeout task. Held on the controller (not just captured by
    /// the start closure) so a STOP during the pending window — broadcast not yet
    /// confirmed — can cancel it. Cleared once it fires, is cancelled, or the
    /// broadcast starts.
    private var startTimeoutTask: Task<Void, Never>?
#if canImport(ReplayKit)
    /// In-app ReplayKit capturer for the `.inAppOnly` path.
    private var replayKitCapturer: ReplayKitVideoCapturer?
#endif

    // MARK: - Dependencies

    private let cameraController: CameraCaptureController
    private let localVideoSourceProvider: () -> RTCVideoSource?
    /// CONTENT source for the independent path. The BroadcastFrameReader /
    /// ReplayKit capturer delegates to this source so the screen rides a
    /// separate content track, leaving the camera source untouched.
    private let localContentVideoSourceProvider: () -> RTCVideoSource?
    private let isLocalVideoTrackEnabled: () -> Bool
    private let setLocalVideoTrackEnabled: (Bool) -> Void
    var onScreenShareStopped: () -> Void
    private let onStateChanged: (Bool) -> Void
    /// When true, screen share rides a SEPARATE content source and the camera
    /// capture is left untouched.
    private let independentContentEnabled: Bool
    private let logger: SerenadaLogger?

    /// Runtime capture selection (`.broadcast`/`.inAppOnly`/`.disabled`). Replaces
    /// the former `BROADCAST_EXTENSION` compile flag.
    private let screenShareMode: ScreenShareMode
    /// Test seam: injects a fake broadcast frame reader so unit tests can drive
    /// the pending-broadcast window directly. `nil` in production, where the real
    /// reader is built from the `.broadcast` config.
    private let makeBroadcastFrameReader: ((RTCVideoSource) -> BroadcastFrameReading)?

    /// Idempotency latch for the shared stop path (API / external broadcast
    /// finished). Ensures capture stops once and the reader is torn down once per
    /// logical stop, regardless of how stop is entered (pitfall #9).
    private var stopInFlight = false

    // MARK: - Init

    init(
        cameraController: CameraCaptureController,
        localVideoSourceProvider: @escaping () -> RTCVideoSource?,
        localContentVideoSourceProvider: @escaping () -> RTCVideoSource? = { nil },
        isLocalVideoTrackEnabled: @escaping () -> Bool,
        setLocalVideoTrackEnabled: @escaping (Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onStateChanged: @escaping (Bool) -> Void,
        independentContentEnabled: Bool = false,
        screenShareMode: ScreenShareMode = .disabled,
        logger: SerenadaLogger? = nil
    ) {
        self.cameraController = cameraController
        self.localVideoSourceProvider = localVideoSourceProvider
        self.localContentVideoSourceProvider = localContentVideoSourceProvider
        self.isLocalVideoTrackEnabled = isLocalVideoTrackEnabled
        self.setLocalVideoTrackEnabled = setLocalVideoTrackEnabled
        self.onScreenShareStopped = onScreenShareStopped
        self.onStateChanged = onStateChanged
        self.independentContentEnabled = independentContentEnabled
        self.screenShareMode = screenShareMode
        self.makeBroadcastFrameReader = nil
        self.logger = logger
    }

    /// Test seam: inject a broadcast-reader factory so unit tests can drive the
    /// pending-broadcast window. Not used in production; implies broadcast mode.
    init(
        cameraController: CameraCaptureController,
        localVideoSourceProvider: @escaping () -> RTCVideoSource?,
        localContentVideoSourceProvider: @escaping () -> RTCVideoSource? = { nil },
        isLocalVideoTrackEnabled: @escaping () -> Bool,
        setLocalVideoTrackEnabled: @escaping (Bool) -> Void,
        onScreenShareStopped: @escaping () -> Void,
        onStateChanged: @escaping (Bool) -> Void,
        independentContentEnabled: Bool = false,
        logger: SerenadaLogger? = nil,
        makeBroadcastFrameReader: @escaping (RTCVideoSource) -> BroadcastFrameReading
    ) {
        self.cameraController = cameraController
        self.localVideoSourceProvider = localVideoSourceProvider
        self.localContentVideoSourceProvider = localContentVideoSourceProvider
        self.isLocalVideoTrackEnabled = isLocalVideoTrackEnabled
        self.setLocalVideoTrackEnabled = setLocalVideoTrackEnabled
        self.onScreenShareStopped = onScreenShareStopped
        self.onStateChanged = onStateChanged
        self.independentContentEnabled = independentContentEnabled
        self.screenShareMode = .broadcast(
            BroadcastIPCConfig(appGroupIdentifier: "test.group", extensionBundleId: "test.broadcast")
        )
        self.makeBroadcastFrameReader = makeBroadcastFrameReader
        self.logger = logger
    }

    /// Build the broadcast frame reader for a start. Uses the injected test
    /// factory when present, otherwise the real shared-memory reader built from
    /// the `.broadcast` config. Returns `nil` if the mode is not `.broadcast`.
    private func makeReader(_ source: RTCVideoSource) -> BroadcastFrameReading? {
        if let makeBroadcastFrameReader {
            return makeBroadcastFrameReader(source)
        }
        if case let .broadcast(ipcConfig) = screenShareMode {
            return BroadcastFrameReader(delegate: source, config: ipcConfig)
        }
        return nil
    }

    // MARK: - Screen Share

    func startScreenShare(onComplete: ((Bool) -> Void)? = nil) -> Bool {
        // `.disabled` exposes no capture path.
        if case .disabled = screenShareMode {
            onComplete?(false)
            return false
        }
        // Independent mode delegates broadcast frames to the dedicated CONTENT
        // source (camera untouched); legacy mode reuses the single camera source.
        guard let captureSource = independentContentEnabled
            ? localContentVideoSourceProvider()
            : localVideoSourceProvider() else {
            onComplete?(false)
            return false
        }
        if isScreenSharing {
            onComplete?(true)
            return true
        }
        stopInFlight = false

        let previousSource = cameraController.localCameraSource
        cameraController.preScreenShareCameraSource = previousSource

        if case .broadcast = screenShareMode {
            // Defer camera teardown until broadcast actually starts (user confirms picker)
            logger?.log(.info, tag: "ScreenShare", "startScreenShare: broadcast path, creating BroadcastFrameReader")
            guard let reader = makeReader(captureSource) else {
                onComplete?(false)
                return false
            }
            broadcastFrameReader = reader

            reader.onBroadcastStarted = { [weak self] in
                Task { @MainActor in
                    self?.logger?.log(.info, tag: "ScreenShare", "startScreenShare: onBroadcastStarted callback fired")
                    guard let self else { return }
                    // A STOP during the pending window nulls the reader (and cancels
                    // the timeout). The identity check makes a late onBroadcastStarted
                    // a no-op, so a cancelled pending start cannot resurrect the share.
                    guard self.broadcastFrameReader === reader else {
                        self.logger?.log(.error, tag: "ScreenShare", "startScreenShare: reader mismatch, ignoring (likely cancelled pending start)")
                        return
                    }
                    self.startTimeoutTask?.cancel()
                    self.startTimeoutTask = nil
                    if self.independentContentEnabled {
                        // Independent: leave the camera capturing; the screen rides a
                        // separate content track (pitfall #6).
                        self.isScreenSharing = true
                        self.onStateChanged(true)
                        onComplete?(true)
                        return
                    }
                    // Legacy: tear down camera — broadcast is confirmed.
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

            // Timeout: if broadcast doesn't start within 30s, restore camera.
            // Held on the controller so a STOP during the pending window cancels it.
            startTimeoutTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard !Task.isCancelled else { return }
                guard let self else { return }
                self.startTimeoutTask = nil
                guard self.broadcastFrameReader === reader, !self.isScreenSharing else { return }
                self.broadcastFrameReader?.stopListening()
                self.broadcastFrameReader = nil
                // Independent mode never preempted the camera, so there is nothing to
                // restore. Legacy mode restores the prior camera source.
                if !self.independentContentEnabled, self.cameraController.canCaptureVideo {
                    _ = self.cameraController.restartVideoCapturer(source: previousSource)
                    self.cameraController.notifyCameraModeAndFlash()
                }
                onComplete?(false)
            }

            return true
        }

        // `.inAppOnly` — in-app ReplayKit capture (this app's own content only).
#if canImport(ReplayKit)
        // Legacy mode preempts the camera; independent mode leaves it running.
        if !independentContentEnabled {
            cameraController.stopAllCapturers()
        }

        let capturer = ReplayKitVideoCapturer(delegate: captureSource)
        replayKitCapturer = capturer

        return capturer.startCapture { [weak self] started in
            Task { @MainActor in
                guard let self else { return }
                if started {
                    if self.independentContentEnabled {
                        self.isScreenSharing = true
                        self.onStateChanged(true)
                        onComplete?(true)
                        return
                    }
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
                if !self.independentContentEnabled, self.cameraController.canCaptureVideo {
                    _ = self.cameraController.restartVideoCapturer(source: previousSource)
                    self.cameraController.notifyCameraModeAndFlash()
                }
                onComplete?(false)
            }
        }
#else
        onComplete?(false)
        return false
#endif
    }

    /// Shared idempotent stop path (API, external broadcast finished). The latch
    /// makes a second entry (e.g. an onBroadcastFinished re-entry after a
    /// programmatic stop) a no-op: capture stops once, the reader is torn down
    /// once, and the state callbacks fire once per logical stop (pitfall #9).
    func stopScreenShare() -> Bool {
        if independentContentEnabled {
            return stopScreenShareIndependent()
        }
        return stopScreenShareLegacy()
    }

    /// True when an independent start is PENDING: the BroadcastFrameReader is
    /// listening but `onBroadcastStarted` has not fired yet, so `isScreenSharing`
    /// is still false. A stop in this window must cancel the pending start. Only
    /// the broadcast path is observably pending (the ReplayKit path flips
    /// `isScreenSharing` from its start completion, so it is never pending here).
    private var hasPendingIndependentStart: Bool {
        return broadcastFrameReader != nil && !isScreenSharing
    }

    private func stopScreenShareIndependent() -> Bool {
        // Stoppable either when the share is live (started) OR when an
        // independent start is still pending (reader listening, broadcast not yet
        // confirmed). A stop in the pending window must cancel the start so the
        // reader stops listening, the 30s timeout is disarmed, and the session
        // can clear its pending-start latch.
        guard isScreenSharing || hasPendingIndependentStart else { return true }
        guard !stopInFlight else { return true }
        stopInFlight = true
        isScreenSharing = false
        // Cancel a pending start: disarm the timeout and tear down the reader.
        // Nulling the reader also makes a late onBroadcastStarted a no-op (its
        // identity check fails), so a cancelled pending start cannot resurrect.
        startTimeoutTask?.cancel()
        startTimeoutTask = nil
        broadcastFrameReader?.stopListening()
        broadcastFrameReader = nil
#if canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
#endif
        // Camera is intentionally left untouched (pitfall #6). onStateChanged
        // drives the engine's content teardown; onScreenShareStopped drives the
        // session's stop bookkeeping/signaling. Exactly once per logical stop,
        // whether stopping a started share or cancelling a pending one.
        cameraController.preScreenShareCameraSource = .selfie
        onStateChanged(false)
        onScreenShareStopped()
        stopInFlight = false
        return true
    }

    private func stopScreenShareLegacy() -> Bool {
        // Tear down whichever capturer is active (only one is ever non-nil) and
        // disarm a pending broadcast start's timeout.
        startTimeoutTask?.cancel()
        startTimeoutTask = nil
        broadcastFrameReader?.stopListening()
        broadcastFrameReader = nil
#if canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
#endif
        if isScreenSharing {
            isScreenSharing = false
            cameraController.isScreenSharing = false
            onStateChanged(false)
            let restoreSource = cameraController.preScreenShareCameraSource
            cameraController.preScreenShareCameraSource = .selfie
            if !cameraController.canCaptureVideo {
                setLocalVideoTrackEnabled(false)
                cameraController.localCameraSource = restoreSource
                cameraController.notifyCameraModeAndFlash()
            } else if isLocalVideoTrackEnabled() {
                _ = cameraController.restartVideoCapturer(source: restoreSource)
            } else {
                cameraController.localCameraSource = restoreSource
                cameraController.notifyCameraModeAndFlash()
            }
            onScreenShareStopped()
        }
        return true
    }

    /// Stop all screen share capturers without triggering state callbacks.
    /// Called by WebRtcEngine during stopLocalMedia cleanup.
    func stopAllCapturers() {
        // Disarm a pending start's timeout too (teardown may race the window).
        startTimeoutTask?.cancel()
        startTimeoutTask = nil
        broadcastFrameReader?.stopListening()
        broadcastFrameReader = nil
#if canImport(ReplayKit)
        replayKitCapturer?.stopCapture()
        replayKitCapturer = nil
#endif
        isScreenSharing = false
        cameraController.isScreenSharing = false
    }
}
