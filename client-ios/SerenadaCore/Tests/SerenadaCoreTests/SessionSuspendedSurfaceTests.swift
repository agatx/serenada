import XCTest
@testable import SerenadaCore

/// Failure modes #3 (per-CID UI presentation timer for suspended remote peers)
/// and #6 (`SignalingState` surface for the local transport) from
/// `docs/resilience-failure-modes.md`. Verifies that:
///  - A remote peer in `.suspended` flips `presumedLost=true` after 30s.
///  - The flag clears when the peer reattaches or leaves the room.
///  - Local `SignalingState` tracks connected → suspended transitions with
///    a hard-eviction estimate.
@MainActor
final class SessionSuspendedSurfaceTests: XCTestCase {

    private var harness: SessionTestHarness!

    override func setUp() async throws {
        harness = SessionTestHarness(handlesReconnection: true)
    }

    override func tearDown() async throws {
        harness.tearDown()
        harness = nil
    }

    private func participant(cid: String, joinedAt: Int64, status: ParticipantSignalingStatus) -> SignalingProviderParticipant {
        SignalingProviderParticipant(peerId: cid, joinedAt: joinedAt, signalingStatus: status)
    }

    func testRemoteFlipsPresumedLostAfterPeerSuspendedUiTimeout() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
                participant(cid: "remote", joinedAt: 2, status: .suspended),
            ],
            hostCid: "alpha"
        )
        // Yield enough to let the timer Task register its sleep on FakeSessionClock.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.presumedLostRemoteCount, 0, "Should not be presumed lost yet")
        XCTAssertEqual(
            harness.session.state.remoteParticipants.first { $0.cid == "remote" }?.signalingStatus,
            .suspended
        )

        // Advance past the timeout
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.peerSuspendedUiTimeoutMs) + 1)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.presumedLostRemoteCount, 1)
        let flagged = harness.session.state.remoteParticipants.first { $0.cid == "remote" }
        XCTAssertEqual(flagged?.presumedLost, true)
    }

    func testPresumedLostClearsWhenPeerReattachesAsActive() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
                participant(cid: "remote", joinedAt: 2, status: .suspended),
            ],
            hostCid: "alpha"
        )
        // Yield enough to let the timer Task register its sleep on FakeSessionClock.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.peerSuspendedUiTimeoutMs) + 1)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.presumedLostRemoteCount, 1)

        // Peer reattaches as active
        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
                participant(cid: "remote", joinedAt: 2, status: .active),
            ],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.presumedLostRemoteCount, 0)
        let cleared = harness.session.state.remoteParticipants.first { $0.cid == "remote" }
        XCTAssertEqual(cleared?.signalingStatus, .active)
        XCTAssertEqual(cleared?.presumedLost, false)
    }

    func testLocalSignalingStateTransitionsConnectedSuspendedConnected() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )
        XCTAssertEqual(harness.session.state.signalingState, .connected)

        harness.fakeProvider.simulateDisconnected(reason: "test")
        await harness.yieldToMainActor()

        if case let .suspended(suspendedSinceMs, estimatedHardEvictionAtMs) = harness.session.state.signalingState {
            XCTAssertEqual(
                estimatedHardEvictionAtMs,
                suspendedSinceMs + Int64(WebRtcResilience.suspendHardEvictionTimeoutMs)
            )
        } else {
            XCTFail("Expected .suspended, got \(harness.session.state.signalingState)")
        }

        harness.fakeProvider.simulateConnected()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.state.signalingState, .connected)
    }

    func testSubsequentRoomStateUpdatesWithPeerStillSuspendedDoNotRescheduleTimer() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
                participant(cid: "remote", joinedAt: 2, status: .suspended),
            ],
            hostCid: "alpha"
        )
        // Yield enough to let the timer Task register its sleep on FakeSessionClock.
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.peerSuspendedUiTimeoutMs) + 1)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.presumedLostRemoteCount, 1)

        // Several more room_state updates while still suspended must not arm new timers.
        for _ in 0..<3 {
            harness.simulateRoomStateWith(
                participants: [
                    participant(cid: "alpha", joinedAt: 1, status: .active),
                    participant(cid: "remote", joinedAt: 2, status: .suspended),
                ],
                hostCid: "alpha"
            )
            await harness.yieldToMainActor()
            await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.peerSuspendedUiTimeoutMs) + 1)
            await harness.yieldToMainActor()
        }

        XCTAssertEqual(harness.session.presumedLostRemoteCount, 1)
    }

    func testPresumedLostTrackingClearsWhenPresumedLostPeerLeavesRoom() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
                participant(cid: "remote", joinedAt: 2, status: .suspended),
            ],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.peerSuspendedUiTimeoutMs) + 1)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.session.presumedLostRemoteCount, 1)

        // Peer leaves entirely
        harness.simulateRoomStateWith(
            participants: [
                participant(cid: "alpha", joinedAt: 1, status: .active),
            ],
            hostCid: "alpha"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.session.presumedLostRemoteCount, 0)
    }

    func testLocalSignalingStateReportsFailedOnTerminalError() async {
        await harness.advanceToInCallWithTurn(
            localCid: "alpha",
            remoteCid: "remote",
            localJoinedAt: 1,
            remoteJoinedAt: 2
        )

        harness.simulateError(code: "ROOM_ENDED", message: "Room is gone")
        await harness.yieldToMainActor()

        if case let .failed(reason) = harness.session.state.signalingState {
            XCTAssertEqual(reason, .roomEnded)
        } else {
            XCTFail("Expected .failed, got \(harness.session.state.signalingState)")
        }
    }
}
