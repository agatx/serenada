@testable import SerenadaCore
import XCTest

@MainActor
final class JoinFlowCoordinatorTests: XCTestCase {
    private var clock: FakeSessionClock!
    private var timeoutCount = 0
    private var ensureConnectionCount = 0
    private var recoveryCount = 0
    private var lastRecoveryParticipantHint: Int? = nil
    private var lastRecoveryPreferInCall = false

    // Configurable state readers
    private var phase: CallPhase = .joining
    private var roomId = "room-1"
    private var joinAttemptSerial: Int64 = 1
    private var joinSignalStarted = false
    private var joinAcknowledged = false
    private var signalingConnected = true

    private var coordinator: JoinFlowCoordinator!

    override func setUp() {
        super.setUp()
        clock = FakeSessionClock()
        timeoutCount = 0
        ensureConnectionCount = 0
        recoveryCount = 0
        lastRecoveryParticipantHint = nil
        lastRecoveryPreferInCall = false

        coordinator = JoinFlowCoordinator(
            clock: clock,
            getRoomId: { [unowned self] in self.roomId },
            getJoinAttemptSerial: { [unowned self] in self.joinAttemptSerial },
            getInternalPhase: { [unowned self] in self.phase },
            hasJoinSignalStarted: { [unowned self] in self.joinSignalStarted },
            hasJoinAcknowledged: { [unowned self] in self.joinAcknowledged },
            isSignalingConnected: { [unowned self] in self.signalingConnected },
            onJoinTimeout: { [unowned self] in self.timeoutCount += 1 },
            onEnsureSignalingConnection: { [unowned self] in self.ensureConnectionCount += 1 },
            onRecovery: { [unowned self] hint, preferInCall in
                self.recoveryCount += 1
                self.lastRecoveryParticipantHint = hint
                self.lastRecoveryPreferInCall = preferInCall
            }
        )
    }

    override func tearDown() {
        coordinator.clearAllTimers()
        coordinator = nil
        clock = nil
        super.tearDown()
    }

    /// Yield to let Task callbacks reach their clock.sleep suspension point.
    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
    }

    private func waitUntil(
        attempts: Int = 32,
        condition: () -> Bool
    ) async {
        for _ in 0..<attempts {
            if condition() {
                return
            }
            await yieldToMainActor()
        }
    }

    private func waitForPendingSleeps(_ expectedCount: Int, attempts: Int = 32) async {
        await waitUntil(attempts: attempts) { [unowned self] in
            self.clock.pendingSleepCount == expectedCount
        }
    }

    // MARK: - Join Timeout

    func testJoinTimeoutFires() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 1)
    }

    func testJoinTimeoutDoesNotFireIfPhaseChanged() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        phase = .inCall
        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 0)
    }

    func testJoinTimeoutDoesNotFireIfSerialChanged() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        joinAttemptSerial = 2
        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 0)
    }

    func testJoinTimeoutDoesNotFireIfRoomIdChanged() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        roomId = "room-2"
        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 0)
    }

    func testClearJoinTimeoutCancels() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)
        coordinator.clearJoinTimeout()

        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 0)
    }

    // MARK: - Join Connect Kickstart

    func testKickstartFiresWhenJoinSignalNotStarted() async {
        coordinator.scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinConnectKickstartMs))
        XCTAssertEqual(ensureConnectionCount, 1)
    }

    func testKickstartDoesNotFireWhenJoinSignalStarted() async {
        joinSignalStarted = true
        coordinator.scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttemptSerial)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinConnectKickstartMs))
        XCTAssertEqual(ensureConnectionCount, 0)
    }

    // MARK: - Join Recovery

    func testRecoveryFiresWhenConnectedAndAcknowledged() async {
        signalingConnected = true
        joinAcknowledged = true
        coordinator.scheduleJoinRecovery(for: roomId)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinRecoveryMs))
        XCTAssertEqual(recoveryCount, 1)
        XCTAssertNil(lastRecoveryParticipantHint)
        XCTAssertFalse(lastRecoveryPreferInCall)
    }

    func testRecoveryRejoinsWhenConnectedButNotAcknowledged() async {
        signalingConnected = true
        joinAcknowledged = false
        phase = .joining
        coordinator.scheduleJoinRecovery(for: roomId)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinRecoveryMs))
        await waitUntil { [unowned self] in
            self.ensureConnectionCount == 1
        }
        XCTAssertEqual(recoveryCount, 0)
        XCTAssertEqual(ensureConnectionCount, 1, "Should re-ensure signaling connection")
    }

    func testRecoveryDoesNotFireWhenDisconnected() async {
        signalingConnected = false
        joinAcknowledged = true
        coordinator.scheduleJoinRecovery(for: roomId)
        await waitForPendingSleeps(1)

        await clock.advance(byMs: Int64(WebRtcResilience.joinRecoveryMs))
        XCTAssertEqual(recoveryCount, 0)
    }

    // MARK: - Clear All

    func testClearAllTimersCancelsEverything() async {
        coordinator.scheduleJoinTimeout(roomId: roomId, joinAttempt: joinAttemptSerial)
        coordinator.scheduleJoinConnectKickstart(roomId: roomId, joinAttempt: joinAttemptSerial)
        coordinator.scheduleJoinRecovery(for: roomId)
        await waitForPendingSleeps(3)

        coordinator.clearAllTimers()

        await clock.advance(byMs: Int64(WebRtcResilience.joinHardTimeoutMs))
        XCTAssertEqual(timeoutCount, 0)
        XCTAssertEqual(ensureConnectionCount, 0)
        XCTAssertEqual(recoveryCount, 0)
    }
}
