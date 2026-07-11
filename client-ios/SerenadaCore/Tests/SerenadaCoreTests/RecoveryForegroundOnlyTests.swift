@testable import SerenadaCore
import XCTest

/// Candidate A: durable cross-launch recovery is FOREGROUND-ONLY. Only the
/// foreground call owns the persisted "Rejoin?" record; a held call keeps its
/// reconnect credentials in memory but must NOT write (or clobber) the durable
/// record. On resume-to-foreground the record is written immediately from the
/// in-memory credentials, and a stale/held call tearing down clears the record
/// only when it still owns it (roomId + cid match).
@MainActor
final class RecoveryForegroundOnlyTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "serenada.recovery.fg.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func makeStorage() -> RecoveryStorage {
        RecoveryStorage(defaults: defaults)
    }

    private func waitUntil(attempts: Int = 64, condition: () -> Bool) async {
        for _ in 0..<attempts {
            if condition() { return }
            await Task.yield(); await Task.yield(); await Task.yield(); await Task.yield()
        }
    }

    // MARK: - Foreground writes

    func testForegroundJoinWritesRecoveryRecord() async {
        let storage = makeStorage()
        let h = SessionTestHarness(
            roomId: "room-A",
            initialMediaRole: .foreground,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await h.advanceToInCallWithTurn(
            localCid: "cid-A", remoteCid: "remote-A",
            reconnectToken: "tok-A", reconnectTokenTTLMs: 60_000
        )

        let record = storage.load()
        XCTAssertEqual(record?.roomId, "room-A")
        XCTAssertEqual(record?.cid, "cid-A")
        XCTAssertEqual(record?.reconnectToken, "tok-A")
        h.tearDown()
    }

    // MARK: - Held does not write; resume writes

    func testHeldJoinDoesNotWriteButResumeToForegroundDoes() async {
        let storage = makeStorage()
        let h = SessionTestHarness(
            roomId: "room-B",
            initialMediaRole: .held,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await h.advanceToInCallHeld(
            localCid: "cid-B", remoteCid: "remote-B",
            reconnectToken: "tok-B", reconnectTokenTTLMs: 60_000
        )

        // Held call must NOT own the durable record.
        XCTAssertNil(storage.load(), "A held call must not write the durable recovery record")
        XCTAssertEqual(h.session.mediaRole, .held)

        // Resume to foreground: the record is written immediately from the
        // in-memory credentials cached on the held join.
        h.session.applyForegroundRoleInternal()
        await waitUntil { h.session.mediaRole == .foreground }

        let record = storage.load()
        XCTAssertEqual(record?.roomId, "room-B", "Resume-to-foreground must write the record")
        XCTAssertEqual(record?.cid, "cid-B")
        XCTAssertEqual(record?.reconnectToken, "tok-B")
        h.tearDown()
    }

    // MARK: - Held token refresh does not write

    func testHeldReconnectTokenRefreshDoesNotWrite() async {
        let storage = makeStorage()
        let h = SessionTestHarness(
            roomId: "room-C",
            initialMediaRole: .held,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await h.advanceToInCallHeld(
            localCid: "cid-C", remoteCid: "remote-C",
            reconnectToken: "tok-C", reconnectTokenTTLMs: 60_000
        )
        XCTAssertNil(storage.load())

        h.fakeProvider.simulateReconnectTokenRefreshed(reconnectToken: "tok-C2", reconnectTokenTTLMs: 60_000)
        await Task.yield(); await Task.yield()

        XCTAssertNil(storage.load(), "A held call's token refresh must not write the durable record")
        h.tearDown()
    }

    // MARK: - Failed resume writes no (false) record

    func testFailedForegroundActivationDoesNotWriteRecord() async {
        let storage = makeStorage()
        let throwingCoord = ThrowingActivateCoordinator()
        throwingCoord.throwNextActivation = true
        // A throwaway provider only satisfies config validation; the harness drives
        // signaling through its own injected `fakeProvider`.
        let config = SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            defaultVideoEnabled: false,
            audioCoordinator: throwingCoord
        )
        let h = SessionTestHarness(
            roomId: "room-D",
            config: config,
            initialMediaRole: .held,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await h.advanceToInCallHeld(
            localCid: "cid-D", remoteCid: "remote-D",
            reconnectToken: "tok-D", reconnectTokenTTLMs: 60_000
        )
        XCTAssertNil(storage.load())

        // Resume attempt: the coordinator activation throws, so the session stays
        // held (failForegroundActivation). No durable record may be written.
        h.session.applyForegroundRoleInternal()
        await waitUntil { h.session.mediaActivationState == .failed }

        XCTAssertEqual(h.session.mediaRole, .held, "A failed activation keeps the call held")
        XCTAssertNil(storage.load(), "A failed foreground activation must not write a durable record")
        h.tearDown()
    }

    // MARK: - Ownership: switch A->B updates promptly; stale A teardown keeps B

    func testForegroundHandoffUpdatesRecordAndStaleTeardownKeepsIt() async {
        let storage = makeStorage()

        // A: foreground owner writes its record.
        let hA = SessionTestHarness(
            roomId: "room-A",
            initialMediaRole: .foreground,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await hA.advanceToInCallWithTurn(
            localCid: "cid-A", remoteCid: "remote-A",
            reconnectToken: "tok-A", reconnectTokenTTLMs: 60_000
        )
        XCTAssertEqual(storage.load()?.cid, "cid-A", "Precondition: A owns the record")

        // B joins held (does not disturb A's record), then resumes to foreground:
        // the record is updated PROMPTLY to describe B.
        let hB = SessionTestHarness(
            roomId: "room-B",
            initialMediaRole: .held,
            acquireForegroundLease: false,
            isCapabilityGranted: { _ in true },
            recoveryStorage: storage
        )
        await hB.advanceToInCallHeld(
            localCid: "cid-B", remoteCid: "remote-B",
            reconnectToken: "tok-B", reconnectTokenTTLMs: 60_000
        )
        XCTAssertEqual(storage.load()?.cid, "cid-A", "A held join must not touch A's record")

        // A hands off foreground; B takes over.
        hA.session.applyHeldRoleInternal()
        await waitUntil { hA.session.mediaRole == .held }
        hB.session.applyForegroundRoleInternal()
        await waitUntil { hB.session.mediaRole == .foreground }

        XCTAssertEqual(storage.load()?.cid, "cid-B", "Resume-to-foreground must update the record to B")
        XCTAssertEqual(storage.load()?.roomId, "room-B")

        // Stale A (now held) tears down. Its clear must NOT remove B's record.
        hA.session.leave()
        await waitUntil { hA.session.state.phase == .idle || hA.session.state.phase == .ending }
        await Task.yield(); await Task.yield()

        XCTAssertEqual(storage.load()?.cid, "cid-B", "Stale A teardown must not clear B's record")

        hA.tearDown()
        hB.tearDown()
    }
}
