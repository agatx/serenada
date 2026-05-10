@testable import SerenadaCore
import XCTest

@MainActor
final class SnapshotCaptureTests: XCTestCase {
    func testCaptureSnapshotLocalThrowsStreamNotActiveBeforeJoin() async {
        // Default phase after construction is `.joining` — captureSnapshot
        // should refuse until we reach `.waiting` or `.inCall`.
        let session = SerenadaSession(
            roomId: "room-id",
            config: SerenadaConfig(serverHost: "test.local")
        )

        await assertSnapshotError(session: session, source: .local, expected: .streamNotActive)

        session.cancelJoin()
    }

    func testCaptureSnapshotLocalThrowsStreamNotActiveWhenVideoDisabled() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: "room-id",
            config: SerenadaConfig(
                signalingProvider: provider,
                defaultVideoEnabled: false
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        // Drive the session forward so phase is at least past joining; even
        // then, with video off, snapshot should return streamNotActive
        // without ever attaching a renderer.
        await yieldMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldMainActor()
        }

        await assertSnapshotError(session: session, source: .local, expected: .streamNotActive)
        XCTAssertEqual(media.attachLocalRendererCalls.count, 0)

        session.cancelJoin()
    }

    func testCaptureSnapshotRemoteThrowsStreamNotActiveWhenSlotMissing() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: "room-id",
            config: SerenadaConfig(signalingProvider: provider),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await assertSnapshotError(
            session: session,
            source: .remote(cid: "never-joined"),
            expected: .streamNotActive
        )

        session.cancelJoin()
    }

    func testCaptureSnapshotRemoteThrowsStreamNotActiveWhenRemoteVideoOff() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(
            localCid: "local-cid",
            remoteCid: "remote-cid"
        )

        // Default for FakePeerConnectionSlot is `remoteVideoTrackEnabledOverride = false`
        // — capture should refuse without ever calling attachRemoteRenderer.
        let slot = harness.fakeMedia.fakeSlots["remote-cid"]
        XCTAssertNotNil(slot)
        XCTAssertEqual(slot?.isRemoteVideoTrackEnabled(), false)

        await assertSnapshotError(
            session: harness.session,
            source: .remote(cid: "remote-cid"),
            expected: .streamNotActive
        )
        XCTAssertEqual(slot?.attachRemoteRendererCalls.count, 0)

        harness.tearDown()
    }

    func testSnapshotErrorEquality() {
        XCTAssertEqual(SnapshotError.streamNotActive, SnapshotError.streamNotActive)
        XCTAssertEqual(SnapshotError.captureTimeout, SnapshotError.captureTimeout)
        XCTAssertNotEqual(SnapshotError.streamNotActive, SnapshotError.captureTimeout)

        XCTAssertEqual(SnapshotError.captureFailed("bad"), SnapshotError.captureFailed("bad"))
        XCTAssertNotEqual(SnapshotError.captureFailed("bad"), SnapshotError.captureFailed("worse"))
    }

    func testSnapshotSourceEquality() {
        XCTAssertEqual(SnapshotSource.local, SnapshotSource.local)
        XCTAssertEqual(SnapshotSource.remote(cid: "abc"), SnapshotSource.remote(cid: "abc"))
        XCTAssertNotEqual(SnapshotSource.remote(cid: "abc"), SnapshotSource.remote(cid: "xyz"))
        XCTAssertNotEqual(SnapshotSource.local, SnapshotSource.remote(cid: "x"))
    }

    func testSnapshotResultRoundTrip() {
        let result = SnapshotResult(
            jpegData: Data([0xFF, 0xD8]),
            width: 1280,
            height: 720,
            timestampMs: 1_700_000_000_000,
            source: .local
        )
        XCTAssertEqual(result.width, 1280)
        XCTAssertEqual(result.height, 720)
        XCTAssertEqual(result.jpegData, Data([0xFF, 0xD8]))
        XCTAssertEqual(result.source, .local)
    }

    /// Validates the fix for the renderer-leak race in FrameSnapshotCapturer:
    /// when no frame ever arrives, the capture must throw `captureTimeout`
    /// AND the renderer must be detached exactly once. The previous
    /// `withTaskGroup`/`withCheckedContinuation` combo blocked on exit because
    /// the frame-waiting child never resumed — this test would hang on it.
    func testFrameSnapshotCapturerTimesOutAndDetachesRenderer() async {
        var attached: [AnyObject] = []
        var detached: [AnyObject] = []
        let capturer = FrameSnapshotCapturer(
            attachRenderer: { renderer in attached.append(renderer) },
            detachRenderer: { renderer in detached.append(renderer) }
        )

        do {
            _ = try await capturer.capture(timeoutMs: 50)
            XCTFail("Expected captureTimeout")
        } catch SnapshotError.captureTimeout {
            // expected
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(attached.count, 1, "Renderer should be attached exactly once")
        XCTAssertEqual(detached.count, 1, "Renderer should be detached exactly once after timeout")
        XCTAssertTrue(attached.first === detached.first, "Same renderer should be attached and detached")
    }

    // MARK: - Helpers

    private func assertSnapshotError(
        session: SerenadaSession,
        source: SnapshotSource,
        expected: SnapshotError,
        file: StaticString = #file,
        line: UInt = #line
    ) async {
        do {
            _ = try await session.captureSnapshot(source: source)
            XCTFail("Expected \(expected) but capture succeeded", file: file, line: line)
        } catch let error as SnapshotError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("Unexpected error type: \(error)", file: file, line: line)
        }
    }

    private func yieldMainActor() async {
        for _ in 0..<4 {
            await Task.yield()
        }
    }
}
