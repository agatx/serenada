@testable import SerenadaCore
import XCTest
import WebRTC

/// Controller-level coverage for the independent screen-share PENDING-start
/// window on the broadcast path: `ScreenShareController` returns true
/// from `startScreenShare` as soon as the BroadcastFrameReader begins listening,
/// but `isScreenSharing` only flips later when `onBroadcastStarted` fires (after
/// the user confirms the system broadcast picker). A STOP during that window must
/// CANCEL the pending start — tear down the reader, disarm the 30s timeout, and
/// report stop so the session clears its pending-start latch — and a late
/// `onBroadcastStarted` must be a no-op (no resurrection of the share).
///
/// The controller is driven in broadcast mode via the injected
/// `makeBroadcastFrameReader` factory.
@MainActor
final class ScreenShareControllerPendingStopTests: XCTestCase {

    /// Fake reader: records start/stop listening and exposes the broadcast
    /// callbacks so the test can drive the pending → started transition (or a
    /// late callback after cancel) directly, without a real extension.
    private final class FakeBroadcastFrameReader: BroadcastFrameReading {
        var onBroadcastStarted: (() -> Void)?
        var onBroadcastFinished: (() -> Void)?
        private(set) var startListeningCalls = 0
        private(set) var stopListeningCalls = 0

        func startListening() { startListeningCalls += 1 }
        func stopListening() {
            stopListeningCalls += 1
            // Mirror the real reader, which clears its callbacks on stop.
            onBroadcastStarted = nil
            onBroadcastFinished = nil
        }
    }

    private func makeFactory() -> CameraCaptureController {
        CameraCaptureController(
            localVideoSource: nil,
            isHdVideoExperimentalEnabled: false,
            onCameraFacingChanged: { _ in },
            onCameraModeChanged: { _ in },
            onFlashlightStateChanged: { _, _ in },
            onZoomFactorChanged: { _ in },
            onFeatureDegradation: { _ in }
        )
    }

    private func makeContentSource() -> RTCVideoSource {
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        let factory = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        return factory.videoSource()
    }

    /// Build a controller in independent mode wired to a single fake reader, and
    /// capture the engine-side `onStateChanged` and session-side
    /// `onScreenShareStopped` callbacks.
    private func makeHarness() -> (ScreenShareController, FakeBroadcastFrameReader, Box) {
        let camera = makeFactory()
        let contentSource = makeContentSource()
        let reader = FakeBroadcastFrameReader()
        let box = Box()
        let controller = ScreenShareController(
            cameraController: camera,
            localVideoSourceProvider: { nil },
            localContentVideoSourceProvider: { contentSource },
            isLocalVideoTrackEnabled: { false },
            setLocalVideoTrackEnabled: { _ in },
            onScreenShareStopped: { box.stoppedCount += 1 },
            onStateChanged: { box.stateChanges.append($0) },
            independentContentEnabled: true,
            makeBroadcastFrameReader: { _ in reader }
        )
        return (controller, reader, box)
    }

    private final class Box {
        var stateChanges: [Bool] = []
        var stoppedCount = 0
    }

    /// The controller's `onBroadcastStarted` closure hops through
    /// `Task { @MainActor in ... }`, so let those drain before asserting.
    private func drain() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    // MARK: - Pending-start window

    func testStartEntersPendingWithoutSharingFlag() {
        let (controller, reader, box) = makeHarness()
        let started = controller.startScreenShare()
        XCTAssertTrue(started, "startScreenShare returns true once the reader is listening")
        XCTAssertEqual(reader.startListeningCalls, 1)
        // PENDING: reader listening but onBroadcastStarted has not fired yet.
        XCTAssertFalse(controller.isScreenSharing, "pending window — not yet sharing")
        XCTAssertTrue(box.stateChanges.isEmpty, "no onStateChanged until broadcast starts/stops")
        XCTAssertEqual(box.stoppedCount, 0)
    }

    func testStopDuringPendingCancelsTheStart() {
        let (controller, reader, box) = makeHarness()
        _ = controller.startScreenShare()
        XCTAssertFalse(controller.isScreenSharing)

        // STOP during the pending window. Before the fix this returned
        // immediately (guard isScreenSharing) leaving the reader listening, the
        // timeout armed, and the session never learned the pending start ended.
        let stopped = controller.stopScreenShare()

        XCTAssertTrue(stopped)
        XCTAssertEqual(reader.stopListeningCalls, 1, "pending reader torn down")
        XCTAssertFalse(controller.isScreenSharing)
        XCTAssertEqual(box.stateChanges, [false], "engine content teardown reported once")
        XCTAssertEqual(box.stoppedCount, 1, "session stop signaled once so pending state clears")
    }

    func testLateBroadcastStartedAfterPendingCancelIsNoOp() async {
        let (controller, reader, box) = makeHarness()
        _ = controller.startScreenShare()
        // Capture the callback BEFORE stop tears the reader down (stopListening
        // nils it on the reader), then fire it post-cancel.
        let lateOnStarted = reader.onBroadcastStarted
        XCTAssertNotNil(lateOnStarted)

        _ = controller.stopScreenShare()
        box.stateChanges.removeAll()
        let stoppedAfterCancel = box.stoppedCount

        // A late onBroadcastStarted (race: picker confirmed just after STOP) must
        // NOT resurrect the share — the reader identity check fails (reader nil).
        lateOnStarted?()
        await drain()

        XCTAssertFalse(controller.isScreenSharing, "no resurrection of the cancelled share")
        XCTAssertTrue(box.stateChanges.isEmpty, "no onStateChanged(true) after cancel")
        XCTAssertEqual(box.stoppedCount, stoppedAfterCancel, "no further stop signaling")
    }

    // MARK: - Started-then-stopped still works (no regression)

    func testStartedThenStoppedTearsDownOnce() async {
        let (controller, reader, box) = makeHarness()
        _ = controller.startScreenShare()
        // Confirm the broadcast → started.
        reader.onBroadcastStarted?()
        await drain()
        XCTAssertTrue(controller.isScreenSharing, "broadcast confirmed → sharing")
        XCTAssertEqual(box.stateChanges, [true])

        let stopped = controller.stopScreenShare()
        XCTAssertTrue(stopped)
        XCTAssertFalse(controller.isScreenSharing)
        XCTAssertEqual(box.stateChanges, [true, false], "teardown reported once")
        XCTAssertEqual(box.stoppedCount, 1)

        // Idempotent: a second stop is a no-op.
        _ = controller.stopScreenShare()
        XCTAssertEqual(box.stateChanges, [true, false], "second stop adds no state change")
        XCTAssertEqual(box.stoppedCount, 1, "second stop signals nothing extra")
    }

    func testStopAfterPendingIsIdempotent() {
        let (controller, _, box) = makeHarness()
        _ = controller.startScreenShare()
        _ = controller.stopScreenShare()
        let stateAfterFirst = box.stateChanges
        let stoppedAfterFirst = box.stoppedCount

        // A second stop on an already-cancelled pending start is a no-op.
        _ = controller.stopScreenShare()
        XCTAssertEqual(box.stateChanges, stateAfterFirst)
        XCTAssertEqual(box.stoppedCount, stoppedAfterFirst)
    }
}
