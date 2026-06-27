@testable import SerenadaCore
import XCTest

/// Phase 3 registry (contract §7/§9/§11): operation serialization, the rollback
/// switch, call identity, and aggregate state. Every case uses a DEDICATED
/// `ForegroundMediaArbiter()` (not `.shared`) for isolation; sessions are built
/// through an injected factory wired with the existing per-session fakes.
@MainActor
final class SerenadaCallRegistryTests: XCTestCase {

    override func tearDown() {
        // Defensive: never let a leaked SHARED-arbiter lease/mode bleed into the
        // next case (these tests use dedicated arbiters, but a default-constructed
        // SerenadaCore could still touch the singleton).
        ForegroundMediaArbiter.shared.resetForTests()
        super.tearDown()
    }

    // MARK: - Shared async settling

    private func yieldToMainActor() async {
        for _ in 0..<6 { await Task.yield() }
    }

    private func waitUntil(attempts: Int = 200, condition: () -> Bool) async {
        for _ in 0..<attempts {
            if condition() { return }
            await yieldToMainActor()
        }
    }

    // MARK: - joinHeld does not activate the audio coordinator

    func testJoinHeldDoesNotActivateAudioCoordinator() async {
        let h = RegistryTestHarness()
        let result = await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))

        guard case let .joined(id) = result else {
            return XCTFail("joinHeld should succeed, got \(result)")
        }
        let session = h.session(for: id)
        XCTAssertEqual(session?.mediaRole, .held)
        XCTAssertEqual(h.coordinator(for: id)?.activateEvents, 0,
                       "A held join must NOT activate the audio coordinator")
        XCTAssertNil(h.arbiter.currentOwnerToken,
                     "A held join must hold NO foreground lease")
        XCTAssertNil(h.registry.activeCallId)
        // Registry owns the process (Invariant 6) even with only a held call.
        XCTAssertEqual(h.arbiter.owningMode, .registry)

        await h.teardown()
    }

    // MARK: - Deactivate-old before activate-new on switch

    func testSwitchDeactivatesOldBeforeActivatingNew() async {
        let h = RegistryTestHarness()
        // First call becomes foreground via joinAndSwitch.
        let r1 = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(id1) = r1 else { return XCTFail("first joinAndSwitch: \(r1)") }
        XCTAssertEqual(h.registry.activeCallId, id1)

        h.eventLog.clear()

        // Second call joins held, then switch holds the first and activates it.
        let r2 = await h.registry.joinAndSwitch(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        guard case let .active(id2) = r2 else { return XCTFail("second joinAndSwitch: \(r2)") }
        XCTAssertEqual(h.registry.activeCallId, id2)

        // The shared ordered event log must show the OLD coordinator deactivate
        // strictly before the NEW coordinator activate. Coordinator tags are the
        // canonical roomId, so resolve each call's roomId to read the log.
        let roomOld = h.roomId(of: id1)
        let roomNew = h.roomId(of: id2)
        let events = h.eventLog.events
        let deactivateOld = events.firstIndex(of: "\(roomOld):deactivate")
        let activateNew = events.lastIndex(of: "\(roomNew):activate")
        XCTAssertNotNil(deactivateOld, "Old call must deactivate on switch (events=\(events))")
        XCTAssertNotNil(activateNew, "New call must activate on switch (events=\(events))")
        if let d = deactivateOld, let a = activateNew {
            XCTAssertLessThan(d, a, "deactivate-old must precede activate-new (events=\(events))")
        }

        await h.teardown()
    }

    // MARK: - Stale activation callback cannot steal foreground

    /// A blocked (gated) activation that is superseded mid-flight by a registry
    /// release rotating the owner token must NOT commit foreground when it finally
    /// completes (combined generation + owner-token fence, §3). This drives the
    /// session-level fence through the REGISTRY's switch path.
    func testStaleActivationCallbackCannotStealForeground() async {
        let h = RegistryTestHarness()
        let gated = GatedAudioCoordinator()

        // Call A foreground (ungated coordinator).
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }

        // Call B held, with a GATED coordinator that blocks the next activation.
        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb", coordinator: gated))
        guard case let .joined(idB) = rB else { return XCTFail("B join: \(rB)") }
        let sessionB = h.session(for: idB)!

        // Begin a switch to B; it drains A, acquires a fresh token, calls
        // activateForeground on B, then BLOCKS in B's coordinator past the timeout.
        gated.blockNextActivation = true
        let switchTask = Task { await h.registry.switchToCall(id: idB) }
        await waitUntil { gated.activationInFlight }
        XCTAssertEqual(sessionB.mediaActivationState, .activating)

        // Drive the registry's ACTIVATE timeout: the switch should give up on B,
        // abort + rollback to A. (A's coordinator is immediate.) Wait until the
        // registry's bounded wait has registered a sleep on the fake clock so the
        // advance deterministically trips its deadline (no race with the yield-only
        // settle phase).
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.foregroundActivateTimeoutMs + 500))

        // Await the switch outcome while B is still blocked (the registry times out
        // and rolls back to A on its own). Releasing first would race the deadline
        // check and let the late completion commit foreground.
        let switchResult = await switchTask.value
        if case .active = switchResult {
            XCTFail("Switch to a stuck activation must not report active")
        }
        XCTAssertEqual(h.registry.activeCallId, idA,
                       "Foreground must roll back to A, not be stolen by the stale B callback")

        // Release the blocked B activation. Its completion is fenced out (the abort
        // bumped generation + dropped the token), so it must NOT commit foreground.
        gated.releaseActivation()
        await yieldToMainActor()
        await yieldToMainActor()
        XCTAssertEqual(sessionB.mediaRole, .held,
                       "A fenced-out stale activation must NOT commit foreground")

        await h.teardown()
    }

    // MARK: - Failed activation rolls back to previous

    func testFailedActivationRollsBackToPrevious() async {
        let h = RegistryTestHarness()
        let gated = GatedAudioCoordinator()

        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }

        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb", coordinator: gated))
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        // Block B's activation so it times out; the registry must roll back to A.
        gated.blockNextActivation = true
        let switchTask = Task { await h.registry.switchToCall(id: idB) }
        await waitUntil { gated.activationInFlight }
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.foregroundActivateTimeoutMs + 500))

        // Await the switch outcome BEFORE releasing the gated activation: the
        // registry must time out and roll back on its own while B is still blocked
        // (releasing first would race the registry's deadline check and let the
        // late completion commit foreground).
        let result = await switchTask.value
        guard case .failed = result else {
            return XCTFail("A blocked activation must fail the switch, got \(result)")
        }
        XCTAssertEqual(h.registry.activeCallId, idA, "Failed switch must roll back to A")
        XCTAssertEqual(h.session(for: idA)?.mediaRole, .foreground)
        XCTAssertEqual(h.arbiter.currentOwnerToken?.ownerId, idA,
                       "After rollback A holds the single lease")
        // B carries a per-call activation error (contract §11).
        XCTAssertNotNil(h.registry.calls.first { $0.id == idB }?.activationError)

        // Now drain the still-blocked B activation; its late completion is fenced
        // out (the abort bumped generation + dropped the token).
        gated.releaseActivation()
        await yieldToMainActor()
        XCTAssertEqual(h.session(for: idB)?.mediaRole, .held,
                       "The fenced-out late activation must not commit foreground on B")

        await h.teardown()
    }

    // MARK: - Switch where target needs permission

    func testSwitchNeedsPermissionLeavesOldForeground() async {
        let h = RegistryTestHarness()
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }

        // B desires audio but the mic grant is missing -> preflight needsPermission.
        let rB = await h.registry.joinHeld(
            h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   defaultAudioEnabled: true,
                   grant: { $0 != .microphone })
        )
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        let result = await h.registry.switchToCall(id: idB)
        XCTAssertEqual(result, .needsPermission)
        // Old call UNTOUCHED: still foreground with the lease.
        XCTAssertEqual(h.registry.activeCallId, idA)
        XCTAssertEqual(h.session(for: idA)?.mediaRole, .foreground)
        XCTAssertEqual(h.arbiter.currentOwnerToken?.ownerId, idA)
        // The held target reports the needed permission per-call.
        if case .needsPermission(let caps)? = h.registry.calls.first(where: { $0.id == idB })?.activationError {
            XCTAssertTrue(caps.contains(.microphone))
        } else {
            XCTFail("needsPermission must carry the missing mic capability")
        }

        await h.teardown()
    }

    // MARK: - Old-release failure aborts (old keeps lease, next lease never acquired)

    func testOldReleaseTimeoutAbortsKeepingOldForeground() async {
        // The real iOS session confirms `released -> held` SYNCHRONOUSLY, so the
        // old-release timeout cannot trip via a real session. A `StallReleaseSession`
        // stub (injected through the registry's session-factory seam) models a
        // session whose `releaseForeground` never reaches fully-held, exercising
        // Core Invariant 1 directly.
        let h = RegistryTestHarness()
        let stallingOld = StallReleaseSession(roomId: "aaaaaaaaaaaaaaaaaaaaaaaaaaa")
        let rA = await h.registry.joinAndSwitch(
            h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", stub: stallingOld)
        )
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        let tokenA = h.arbiter.currentOwnerToken
        XCTAssertNotNil(tokenA)

        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        // Switch to B: draining A never confirms held; the registry's release timeout
        // must trip on the fake clock.
        stallingOld.stallRelease = true
        let switchTask = Task { await h.registry.switchToCall(id: idB) }
        await waitUntil { stallingOld.releaseForegroundCalls > 0 }
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.foregroundReleaseTimeoutMs + 500))

        let result = await switchTask.value
        guard case .failed = result else {
            return XCTFail("Old-release timeout must fail the switch, got \(result)")
        }
        // Invariant 1: old keeps the lease; the next lease was NEVER acquired.
        XCTAssertEqual(h.registry.activeCallId, idA, "Old call stays active on release timeout")
        XCTAssertEqual(h.arbiter.currentOwnerToken, tokenA,
                       "Old call retains the SAME lease token; no new lease was granted")
        XCTAssertNil(h.session(for: idB)?.foregroundOwnerTokenForTest,
                     "The next call must never have acquired a lease/token")
        XCTAssertNotNil(h.registry.calls.first { $0.id == idA }?.activationError)

        // Let the stub settle so teardown is clean.
        stallingOld.stallRelease = false
        await h.teardown()
    }

    // MARK: - joinAndSwitch holds prior before activating

    func testJoinAndSwitchHoldsPriorBeforeActivating() async {
        let h = RegistryTestHarness()
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }

        let rB = await h.registry.joinAndSwitch(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        guard case let .active(idB) = rB else { return XCTFail("B: \(rB)") }

        // Prior call A is held (not ended), B is foreground. Single lease on B.
        XCTAssertEqual(h.session(for: idA)?.mediaRole, .held,
                       "joinAndSwitch must HOLD the prior foreground call")
        XCTAssertEqual(h.session(for: idB)?.mediaRole, .foreground)
        XCTAssertEqual(h.registry.activeCallId, idB)
        XCTAssertEqual(h.arbiter.currentOwnerToken?.ownerId, idB)
        XCTAssertEqual(h.registry.calls.count, 2)

        await h.teardown()
    }

    // MARK: - joinAndSwitch failing room join leaves prior untouched

    func testJoinAndSwitchFailedRoomJoinLeavesPriorUntouched() async {
        let h = RegistryTestHarness()
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        let tokenA = h.arbiter.currentOwnerToken

        // B's room join never connects (the provider never fires joined). The held
        // join times out; the registry clock drives the deadline.
        let switchTask = Task {
            await h.registry.joinAndSwitch(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb", autoJoin: false))
        }
        // Let section A create+register and the held-join wait register its sleep,
        // then drive past the held-join timeout.
        await waitUntil { h.registry.calls.contains { $0.roomId == "bbbbbbbbbbbbbbbbbbbbbbbbbbb" } }
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.heldJoinTimeoutMs + 500))

        let result = await switchTask.value
        guard case .failed = result else {
            return XCTFail("A never-joining room must fail joinAndSwitch, got \(result)")
        }
        // Prior call A untouched: still foreground, same lease.
        XCTAssertEqual(h.registry.activeCallId, idA)
        XCTAssertEqual(h.session(for: idA)?.mediaRole, .foreground)
        XCTAssertEqual(h.arbiter.currentOwnerToken, tokenA)

        await h.teardown()
    }

    // MARK: - Duplicate live join for a roomId is idempotent

    func testDuplicateLiveJoinIsIdempotent() async {
        let h = RegistryTestHarness()
        let r1 = await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .joined(id1) = r1 else { return XCTFail("first: \(r1)") }

        // A second live join for the SAME canonical room returns the existing id.
        let r2 = await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .joined(id2) = r2 else { return XCTFail("second: \(r2)") }
        XCTAssertEqual(id1, id2, "A duplicate live join must resolve to the existing CallId")
        XCTAssertEqual(h.registry.calls.count, 1, "No duplicate managed call is created")

        // Equivalence across hosts: serenada-app.ru collapses to the same token.
        let r3 = await h.registry.joinHeld(
            RoomRef(url: URL(string: "https://serenada-app.ru/call/aaaaaaaaaaaaaaaaaaaaaaaaaaa")!)
        )
        guard case let .joined(id3) = r3 else { return XCTFail("third: \(r3)") }
        XCTAssertEqual(id1, id3, "Equivalent URLs across hosts must dedup to one CallId")
        XCTAssertEqual(h.registry.calls.count, 1)

        await h.teardown()
    }

    // MARK: - Direct join while registry has a live call fails

    func testDirectJoinWhileRegistryHasLiveCallFails() async {
        // Registry and a direct session SHARE one dedicated arbiter.
        let arbiter = ForegroundMediaArbiter()
        let h = RegistryTestHarness(arbiter: arbiter)

        // Registry holds a (held) call -> it owns the process in `registry` mode.
        let r = await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case .joined = r else { return XCTFail("registry join: \(r)") }
        XCTAssertEqual(arbiter.owningMode, .registry)

        // A DIRECT single-call session on the SAME arbiter must fail to foreground
        // (mode conflict — Core Invariant 6).
        let direct = SessionTestHarness(roomId: "direct-room", arbiter: arbiter)
        await direct.advancePastPermissions()
        await waitUntil { direct.session.state.phase == .error }
        XCTAssertEqual(direct.session.state.phase, .error,
                       "A direct join while a registry owns the process must fail")
        XCTAssertNil(arbiter.currentOwnerToken,
                     "The blocked direct join must not have taken the lease")

        direct.tearDown()
        await h.teardown()
    }

    // MARK: - leave releases the foreground lease

    func testLeaveActiveReleasesForegroundLeaseAndDoesNotAutoPromote() async {
        let h = RegistryTestHarness()
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        await h.registry.leaveCall(id: idA)

        // Active call left -> lease released, activeCallId nil, NO auto-promote of B.
        XCTAssertNil(h.arbiter.currentOwnerToken, "leave(active) must release the lease")
        XCTAssertNil(h.registry.activeCallId, "No held call is auto-promoted (Invariant 5)")
        XCTAssertEqual(h.session(for: idB)?.mediaRole, .held, "Held B stays held")
        // A is marked ended (retained until dismissed).
        XCTAssertEqual(h.registry.calls.first { $0.id == idA }?.membershipPhase, .idle)

        await h.teardown()
    }

    // MARK: - FIX A: coordinator-activation FAILURE rolls back (not silent .active)

    /// When the new call's audio coordinator THROWS during activation, the session
    /// must surface `.failed` (role stays held) instead of silently committing
    /// `.active`. The registry then observes a non-`.active` outcome and rolls back
    /// to the previous call (contract §3/§7, FIX A). Distinct from the existing
    /// blocked-then-timeout case: this exercises the synchronous throw → `.failed`
    /// path, with no timeout involved.
    func testFailedCoordinatorActivationRollsBackToPrevious() async {
        let h = RegistryTestHarness()
        let throwingB = ThrowingActivateCoordinator()

        // A foreground (immediate coordinator).
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        let tokenA = h.arbiter.currentOwnerToken
        XCTAssertNotNil(tokenA)

        // B held, with a coordinator that THROWS on its next activation.
        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb", coordinator: throwingB))
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        throwingB.throwNextActivation = true
        let result = await h.registry.switchToCall(id: idB)

        guard case .failed = result else {
            return XCTFail("A throwing coordinator activation must fail the switch, got \(result)")
        }
        // B's session surfaced the failure (NOT silently active).
        XCTAssertNotEqual(h.session(for: idB)?.mediaActivationState, .active,
                          "A failed coordinator activation must NOT commit .active")
        XCTAssertEqual(h.session(for: idB)?.mediaRole, .held,
                       "A failed activation leaves the session HELD")
        // Foreground rolled back to A (the registry's abort/rollback ran).
        XCTAssertEqual(h.registry.activeCallId, idA, "Failed activation must roll back to A")
        XCTAssertEqual(h.session(for: idA)?.mediaRole, .foreground)
        XCTAssertEqual(h.arbiter.currentOwnerToken?.ownerId, idA,
                       "After rollback A holds the single lease")
        // B carries a per-call activation error (contract §11).
        XCTAssertNotNil(h.registry.calls.first { $0.id == idB }?.activationError)

        await h.teardown()
    }

    // MARK: - FIX B: published role/activeCallId derive from the lease token

    /// Published `mediaRole`/`held`/`activeCallId` must derive from the
    /// registry-owned foreground lease TOKEN, NOT `session.mediaRole` — which is an
    /// unreliable source (it is not flipped back on teardown, and a stale callback
    /// can leave it wrong). A `LyingRoleSession` stub pins its own `mediaRole` at
    /// `.held` forever, so the ONLY way the published role can be correct is by
    /// deriving it from the token (contract FIX B): while the registry holds the
    /// token the published role must read `.foreground` (the lying session
    /// notwithstanding), and after the lease is released it must read `.held`.
    func testPublishedRoleDerivesFromLeaseTokenNotSessionRole() async {
        let h = RegistryTestHarness()
        let lyingA = LyingRoleSession(roomId: "aaaaaaaaaaaaaaaaaaaaaaaaaaa")
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", stub: lyingA))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }

        // The stub INSISTS it is `.held`, but the registry holds its token, so the
        // published role/held/activeCallId derive from the token: `.foreground`.
        XCTAssertEqual(lyingA.mediaRole, .held, "Precondition: the stub session lies (.held)")
        let foreState = h.registry.calls.first { $0.id == idA }
        XCTAssertEqual(foreState?.mediaRole, .foreground,
                       "Published role must derive from the held token, not the lying session role")
        XCTAssertEqual(foreState?.held, false, "held must be false while the token is held")
        XCTAssertEqual(h.registry.activeCallId, idA, "activeCallId is the token holder")

        // Hold A: the registry drains + releases the lease (token cleared). Now the
        // published role flips to `.held` purely from the token, NOT from any
        // session-role change (the stub never moved).
        await h.registry.holdCall(id: idA)

        let heldState = h.registry.calls.first { $0.id == idA }
        XCTAssertEqual(heldState?.mediaRole, .held,
                       "Published role must read .held once the token is released")
        XCTAssertEqual(heldState?.held, true, "held mirrors the token-derived role")
        XCTAssertNil(h.registry.activeCallId,
                     "activeCallId is nil once the lease is released (no auto-promote)")

        await h.teardown()
    }

    // MARK: - FIX C: release + settle bounded by a SINGLE release timeout

    /// The awaited `releaseForeground` (role flip + coordinator-teardown settle)
    /// must be bounded by ONE `FOREGROUND_RELEASE_TIMEOUT` window (contract FIX C).
    /// A coordinator whose deactivation HANGS leaves the role flipped to held
    /// synchronously but never lets `releaseForeground` return; the switch must time
    /// out (not hang the queue), keep the old call foreground with its lease
    /// (Invariant 1), and never acquire the next lease.
    func testReleaseAndSettleBoundedByReleaseTimeout() async {
        let h = RegistryTestHarness()
        let blockingOld = BlockingDeactivateCoordinator()

        // A foreground, with a coordinator whose deactivation can be paused.
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", coordinator: blockingOld))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        let tokenA = h.arbiter.currentOwnerToken
        XCTAssertNotNil(tokenA)

        let rB = await h.registry.joinHeld(h.room("bbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        guard case let .joined(idB) = rB else { return XCTFail("B: \(rB)") }

        // Switch to B: draining A flips the role to held immediately, but A's
        // coordinator deactivation hangs, so the settle never completes. The
        // single release-timeout window must trip on the fake clock.
        blockingOld.blockNextDeactivation = true
        let switchTask = Task { await h.registry.switchToCall(id: idB) }
        await waitUntil { blockingOld.deactivationInFlight }
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.foregroundReleaseTimeoutMs + 500))

        let result = await switchTask.value
        guard case .failed = result else {
            return XCTFail("A hung settle must time out the switch, got \(result)")
        }
        // Invariant 1: old keeps its lease; the next lease was NEVER acquired.
        XCTAssertEqual(h.registry.activeCallId, idA, "Old call stays active on settle timeout")
        XCTAssertEqual(h.arbiter.currentOwnerToken, tokenA,
                       "Old call retains the SAME lease token; no new lease was granted")
        XCTAssertNil(h.session(for: idB)?.foregroundOwnerTokenForTest,
                     "The next call must never have acquired a lease/token")
        XCTAssertNotNil(h.registry.calls.first { $0.id == idA }?.activationError)

        // Let the blocked deactivation finish so teardown is clean.
        blockingOld.releaseDeactivation()
        await h.teardown()
    }

    // MARK: - FIX F: failed held join releases registry mode (direct join can proceed)

    /// A held join that fails/times out must be marked ENDED and must release the
    /// registry owning-mode when no live call remains, so a later DIRECT
    /// `SerenadaCore.join()` on the same process arbiter can proceed instead of
    /// being wedged out by a stale `registry`-mode claim (contract FIX F /
    /// Invariant 6).
    func testFailedHeldJoinReleasesRegistryModeSoDirectJoinSucceeds() async {
        let arbiter = ForegroundMediaArbiter()
        let h = RegistryTestHarness(arbiter: arbiter)

        // A held join whose room never connects (autoJoin = false): the held-join
        // wait times out. The registry claimed `registry` mode in section A.
        let joinTask = Task {
            await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", autoJoin: false))
        }
        await waitUntil { h.registry.calls.contains { $0.roomId == "aaaaaaaaaaaaaaaaaaaaaaaaaaa" } }
        XCTAssertEqual(arbiter.owningMode, .registry, "Section A claimed registry mode")
        await waitUntil { h.fakeRegistryClock.pendingSleepCount > 0 }
        await h.fakeRegistryClock.advance(byMs: Int64(WebRtcResilience.heldJoinTimeoutMs + 500))

        let result = await joinTask.value
        guard case let .failed(failedId, _) = result else {
            return XCTFail("A never-joining held room must fail, got \(result)")
        }
        // The failed held join is marked ENDED (dismissable) and freed the mode.
        XCTAssertEqual(h.registry.calls.first { $0.id == failedId }?.membershipPhase, .idle,
                       "A failed held join is torn down (dismissable), not left live")
        XCTAssertNil(arbiter.owningMode,
                     "No live call remains, so the registry mode is released (FIX F)")

        // A DIRECT single-call join on the SAME arbiter now succeeds (the mode is
        // free). Before FIX F the stale registry claim would force it to .error.
        let direct = SessionTestHarness(roomId: "direct-room", arbiter: arbiter)
        await direct.advanceToInCallWithTurn(localCid: "d-local", remoteCid: "d-remote")
        XCTAssertEqual(direct.session.state.phase, .inCall,
                       "A direct join must succeed once the failed held join freed the registry mode")
        XCTAssertEqual(arbiter.owningMode, .direct,
                       "The direct join now owns the process in direct mode")
        XCTAssertNotNil(arbiter.currentOwnerToken,
                        "The direct join holds the foreground lease")

        direct.tearDown()
        await h.teardown()
    }

    // MARK: - Session-driven terminal: lease leak (Phase 3 round 2)

    /// A session that reaches a terminal phase ON ITS OWN — `room_ended` /
    /// `cleanupCall`, WITHOUT a registry `leaveCall`/`endCall` — must make the
    /// registry release its OWN foreground lease, clear `activeCallId` (NO
    /// auto-promote), mark the call ended, and release the registry owning-mode so a
    /// subsequent DIRECT join succeeds. Before the fix the registry kept
    /// `activeCallId` + leaked the lease forever (the session's reset releases only
    /// its DIRECT token, which a registry-created session never holds — contract §7).
    func testSessionDrivenTerminalReleasesLeaseAndClearsActiveAndFreesMode() async {
        let arbiter = ForegroundMediaArbiter()
        let h = RegistryTestHarness(arbiter: arbiter)

        // A real registry-created call becomes foreground via joinAndSwitch.
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        XCTAssertEqual(h.registry.activeCallId, idA)
        XCTAssertNotNil(arbiter.currentOwnerToken, "Active call holds the lease")
        XCTAssertEqual(arbiter.owningMode, .registry)

        // The server ends the room: the session runs cleanupCall on its own
        // (NO registry leave/end). Drive it on the real session's provider.
        h.provider(for: idA)?.simulateRoomEnded()

        // Async-settle: the session flips to a terminal phase and the registry's
        // serialized terminal op runs.
        await waitUntil { h.registry.activeCallId == nil && arbiter.currentOwnerToken == nil }

        // The registry observed the session-driven terminal and cleaned up.
        XCTAssertNil(arbiter.currentOwnerToken, "The leaked registry lease must be released")
        XCTAssertNil(h.registry.activeCallId, "activeCallId cleared (NO auto-promote)")
        XCTAssertEqual(h.registry.calls.first { $0.id == idA }?.held, true,
                       "An ended call publishes .held (no foreground token)")
        await waitUntil { arbiter.owningMode == nil }
        XCTAssertNil(arbiter.owningMode,
                     "No live call remains, so the registry owning-mode is released")

        // A subsequent DIRECT join on the SAME arbiter now succeeds (mode freed).
        let direct = SessionTestHarness(roomId: "direct-room", arbiter: arbiter)
        await direct.advanceToInCallWithTurn(localCid: "d-local", remoteCid: "d-remote")
        XCTAssertEqual(direct.session.state.phase, .inCall,
                       "A direct join must succeed once the session-driven terminal freed the mode")
        XCTAssertEqual(arbiter.owningMode, .direct)
        XCTAssertNotNil(arbiter.currentOwnerToken, "The direct join holds the lease")

        direct.tearDown()
        await h.teardown()
    }

    /// A remote-ended HELD call (active call still foreground) marks the held call
    /// ended; if it was the LAST live call the registry owning-mode is released.
    /// Uses a `TerminalDrivableSession` stub so the terminal transition can be
    /// driven deterministically without a foreground activation.
    func testRemoteEndedHeldCallMarksEndedAndFreesModeWhenLastLive() async {
        let arbiter = ForegroundMediaArbiter()
        let h = RegistryTestHarness(arbiter: arbiter)

        // A single HELD call (never foregrounded): the registry owns `registry` mode
        // with NO lease (Invariant 6).
        let heldStub = TerminalDrivableSession(roomId: "aaaaaaaaaaaaaaaaaaaaaaaaaaa")
        let r = await h.registry.joinHeld(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", stub: heldStub))
        guard case let .joined(id) = r else { return XCTFail("held join: \(r)") }
        XCTAssertNil(h.registry.activeCallId, "A held call is not active")
        XCTAssertNil(arbiter.currentOwnerToken, "A held call holds no lease")
        XCTAssertEqual(arbiter.owningMode, .registry)

        // The remote ends the room: the held session reaches terminal on its own.
        heldStub.driveTerminal(.ending)

        await waitUntil { (h.registry.calls.first { $0.id == id }?.held ?? false)
            && arbiter.owningMode == nil }

        // The held call is marked ended (the bug left it live forever) and, being
        // the last live call, the registry mode is released.
        XCTAssertTrue(h.registry.calls.first { $0.id == id }?.held ?? false,
                      "Ended held call publishes .held")
        XCTAssertNil(h.registry.activeCallId, "Still no active call")
        XCTAssertNil(arbiter.owningMode,
                     "Last live call ended -> registry owning-mode released")
        // Dismissable now (the host can clear the dead chip).
        await h.registry.dismissEndedCall(id: id)
        XCTAssertTrue(h.registry.calls.isEmpty, "Ended call is dismissable")

        await h.teardown()
    }

    /// When a registry-initiated `endCall()` drove termination, the session's
    /// later terminal-phase emission must be a NO-OP — the lease was already
    /// released and the call already marked ended. Guards against a double-release.
    func testRegistryEndCallThenSessionTerminalDoesNotDoubleRelease() async {
        let arbiter = ForegroundMediaArbiter()
        let h = RegistryTestHarness(arbiter: arbiter)

        let activeStub = TerminalDrivableSession(roomId: "aaaaaaaaaaaaaaaaaaaaaaaaaaa")
        let rA = await h.registry.joinAndSwitch(h.room("aaaaaaaaaaaaaaaaaaaaaaaaaaa", stub: activeStub))
        guard case let .active(idA) = rA else { return XCTFail("A: \(rA)") }
        XCTAssertNotNil(arbiter.currentOwnerToken)

        // Registry-initiated end: releases the lease, marks ended, frees the mode.
        await h.registry.endCall(id: idA)
        XCTAssertNil(arbiter.currentOwnerToken, "endCall released the lease")
        XCTAssertNil(h.registry.activeCallId)
        XCTAssertEqual(activeStub.registryEndCalls, 1, "Session end ran exactly once")
        XCTAssertNil(arbiter.owningMode, "Mode freed by endCall (last live call)")

        // The session NOW emits its terminal phase (cleanupCall runs after end).
        // The registry's observer must treat this as a no-op (already ended): no
        // double lease release, no resurrection of activeCallId.
        activeStub.driveTerminal(.ending)
        // Give the serialized terminal op a chance to run (and be a no-op).
        for _ in 0..<10 { await yieldToMainActor() }

        XCTAssertNil(arbiter.currentOwnerToken, "No double-release / no new lease")
        XCTAssertNil(h.registry.activeCallId, "activeCallId stays nil")
        XCTAssertNil(arbiter.owningMode, "Mode stays released")
        XCTAssertEqual(activeStub.registryEndCalls, 1,
                       "The session end was NOT invoked a second time")

        await h.teardown()
    }
}
