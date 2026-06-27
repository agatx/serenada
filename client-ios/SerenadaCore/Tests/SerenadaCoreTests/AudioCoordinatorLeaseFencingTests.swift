import AVFoundation
@testable import SerenadaCore
import XCTest

/// Phase 4 (iOS): harden the default audio coordinator against stale callbacks
/// from a superseded session (contract §6, design "Audio Session Ownership").
///
/// Each `SerenadaSession` owns its OWN `DefaultAudioCoordinator` instance, but all
/// instances drive the one process-global `AVAudioSession`/`RTCAudioSession`. The
/// central iOS risk is a CROSS-INSTANCE race: an OLD session's deactivation or OS
/// observer callback re-driving the shared audio session AFTER a NEW foreground
/// session has activated it. The fence is the process-global
/// `AudioSessionLeaseRegistry`: a coordinator records the lease it activated under
/// and re-checks it before touching the audio session, so a stale lease is dropped.
///
/// These tests inject a DEDICATED `AudioSessionLeaseRegistry` (not `.shared`) so
/// the process singleton is not polluted across cases. Async settling uses
/// `Task.yield`. `AVAudioSession` side effects are unreliable on the simulator, so
/// assertions target the fence DECISION (lease registry state + per-instance active
/// flag), which is what determines whether the shared session would be re-driven.
@MainActor
final class AudioCoordinatorLeaseFencingTests: XCTestCase {

    override func tearDown() {
        AudioSessionLeaseRegistry.shared.resetForTests()
        ForegroundMediaArbiter.shared.resetForTests()
        super.tearDown()
    }

    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    private func makeCoordinator(_ registry: AudioSessionLeaseRegistry) -> DefaultAudioCoordinator {
        DefaultAudioCoordinator(
            proximityMonitoringEnabled: false,
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: {},
            logger: nil,
            leaseRegistry: registry
        )
    }

    // MARK: - AudioSessionLeaseRegistry fence semantics (pure)

    func testLeaseRegistryNilLeaseIsAlwaysCurrent() {
        let registry = AudioSessionLeaseRegistry()
        // The single-call/direct path never had a registry lease: a nil lease must
        // always be treated as current so single-call behavior is unfenced.
        XCTAssertTrue(registry.isCurrent(nil))
        registry.install(AudioSessionLease(ownerTokenId: 1, generation: 1))
        XCTAssertTrue(registry.isCurrent(nil),
                      "A nil lease stays always-current even while a lease is installed")
    }

    func testLeaseRegistryInstallSupersedesPriorLease() {
        let registry = AudioSessionLeaseRegistry()
        let old = AudioSessionLease(ownerTokenId: 1, generation: 1)
        let new = AudioSessionLease(ownerTokenId: 2, generation: 2)
        registry.install(old)
        XCTAssertTrue(registry.isCurrent(old))

        registry.install(new)
        XCTAssertFalse(registry.isCurrent(old),
                       "Installing a new lease must supersede the old one")
        XCTAssertTrue(registry.isCurrent(new))
    }

    func testLeaseRegistryClearIfCurrentDoesNotDropNewerLease() {
        let registry = AudioSessionLeaseRegistry()
        let old = AudioSessionLease(ownerTokenId: 1, generation: 1)
        let new = AudioSessionLease(ownerTokenId: 2, generation: 2)
        registry.install(old)
        registry.install(new)

        // The OLD owner's deactivation tries to clear ITS lease. It must NOT clear
        // the new owner's live lease (this is the core deactivate-after-activate
        // protection at the registry level).
        registry.clearIfCurrent(old)
        XCTAssertTrue(registry.isCurrent(new),
                      "A stale clear must not drop the newer owner's live lease")
        XCTAssertNotNil(registry.activeLease)
    }

    func testLeaseRegistryClearIfCurrentClearsTheLiveLease() {
        let registry = AudioSessionLeaseRegistry()
        let lease = AudioSessionLease(ownerTokenId: 1, generation: 1)
        registry.install(lease)
        registry.clearIfCurrent(lease)
        XCTAssertNil(registry.activeLease)
        XCTAssertTrue(registry.isCurrent(nil))
    }

    func testLeaseRegistrySameOwnerNewGenerationSupersedes() {
        // Rollback re-activates the same owner under a FRESH generation. A stuck
        // callback from the prior attempt must be fenced even though the owner-token
        // id is unchanged — the generation is the independent second fence.
        let registry = AudioSessionLeaseRegistry()
        let attempt1 = AudioSessionLease(ownerTokenId: 7, generation: 5)
        let attempt2 = AudioSessionLease(ownerTokenId: 7, generation: 6)
        registry.install(attempt1)
        registry.install(attempt2)
        XCTAssertFalse(registry.isCurrent(attempt1),
                       "Same owner, older generation is stale")
        XCTAssertTrue(registry.isCurrent(attempt2))
    }

    // MARK: - Cross-instance coordinator fencing (the central iOS risk)

    /// A stale DEACTIVATE from an OLD coordinator (an old session) must NOT
    /// deactivate the audio session after a NEW coordinator (a new foreground
    /// session) has activated it under a fresh lease. Drives an out-of-order
    /// deactivate: NEW activates after OLD, then OLD's deactivate lands late.
    func testStaleDeactivateDoesNotTearDownAfterNewActivation() async {
        let registry = AudioSessionLeaseRegistry()
        let old = makeCoordinator(registry)
        let new = makeCoordinator(registry)

        let oldLease = AudioSessionLease(ownerTokenId: 1, generation: 1)
        let newLease = AudioSessionLease(ownerTokenId: 2, generation: 2)

        // OLD activates first (it is the foreground owner).
        old.setForegroundLease(oldLease)
        try? await old.activateCallSession(intent: AudioIntent())
        XCTAssertTrue(old.audioSessionActiveForTest)

        // Switch: NEW activates under a fresh lease (supersedes OLD's lease).
        new.setForegroundLease(newLease)
        try? await new.activateCallSession(intent: AudioIntent())
        XCTAssertTrue(new.audioSessionActiveForTest)
        XCTAssertTrue(registry.isCurrent(newLease))

        // OLD's deactivate lands LATE (out of order, after NEW activated). It must
        // be dropped: the process-global lease must stay the NEW owner's.
        await old.deactivateCallSession()
        await yieldToMainActor()

        XCTAssertTrue(registry.isCurrent(newLease),
                      "Stale deactivate must not clear the new owner's live lease")
        XCTAssertEqual(registry.activeLease, newLease,
                       "The audio session must remain owned by the new foreground call")
        XCTAssertTrue(new.audioSessionActiveForTest,
                      "New session's audio activation must survive the old deactivate")
    }

    /// The in-order case (no supersession): the live owner's deactivate DOES tear
    /// down. Proves the fence does not over-block the legitimate path.
    func testCurrentDeactivateTearsDownNormally() async {
        let registry = AudioSessionLeaseRegistry()
        let coordinator = makeCoordinator(registry)
        let lease = AudioSessionLease(ownerTokenId: 1, generation: 1)

        coordinator.setForegroundLease(lease)
        try? await coordinator.activateCallSession(intent: AudioIntent())
        XCTAssertTrue(coordinator.audioSessionActiveForTest)
        XCTAssertTrue(registry.isCurrent(lease))

        // No newer owner: this deactivate is current and must proceed.
        await coordinator.deactivateCallSession()
        await yieldToMainActor()

        XCTAssertFalse(coordinator.audioSessionActiveForTest,
                       "The live owner's deactivate must tear down the audio session")
        XCTAssertNil(registry.activeLease,
                     "A current deactivate clears the process-global lease")
    }

    /// Single-call / direct path: a coordinator that was never handed a registry
    /// lease (nil owner token) is unfenced — its deactivate always applies and
    /// nothing is dropped. This is the "single-call behavior identical" guarantee.
    func testUnleasedCoordinatorDeactivatesUnfenced() async {
        let registry = AudioSessionLeaseRegistry()
        let coordinator = makeCoordinator(registry)

        // No setForegroundLease call (direct single-call path).
        try? await coordinator.activateCallSession(intent: AudioIntent())
        XCTAssertTrue(coordinator.audioSessionActiveForTest)
        XCTAssertNil(coordinator.installedLeaseForTest)

        await coordinator.deactivateCallSession()
        await yieldToMainActor()
        XCTAssertFalse(coordinator.audioSessionActiveForTest,
                       "An unleased (single-call) coordinator deactivates normally")
    }

    // MARK: - OS observers fenced to the current lease owner

    /// A route-change observer callback from a SUPERSEDED owner must be a no-op: it
    /// must not re-drive routing for a session that no longer holds the lease.
    /// `onAudioEnvironmentChanged` is invoked only when the observer applies, so a
    /// fired-but-fenced callback leaves the counter untouched.
    func testRouteChangeObserverForSupersededOwnerIsNoOp() async {
        let registry = AudioSessionLeaseRegistry()
        var oldEnvChanges = 0
        let old = DefaultAudioCoordinator(
            proximityMonitoringEnabled: false,
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: { oldEnvChanges += 1 },
            logger: nil,
            leaseRegistry: registry
        )

        let oldLease = AudioSessionLease(ownerTokenId: 1, generation: 1)
        old.setForegroundLease(oldLease)
        try? await old.activateCallSession(intent: AudioIntent())
        await yieldToMainActor()
        let baseline = oldEnvChanges

        // A NEW owner takes the lease (supersedes OLD). OLD's route observer is
        // still registered on NotificationCenter but must fence itself.
        let newLease = AudioSessionLease(ownerTokenId: 2, generation: 2)
        registry.install(newLease)

        // Fire a route-change notification: OLD's observer runs, sees its lease is
        // stale, and bails before re-driving routing or notifying the environment.
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification,
            object: nil,
            userInfo: [:]
        )
        await yieldToMainActor()

        XCTAssertEqual(oldEnvChanges, baseline,
                       "A route-change for a superseded owner must not re-drive audio routing")
    }

    /// The same route-change observer DOES apply for the live owner — proving the
    /// fence is selective, not a blanket disable.
    func testRouteChangeObserverForCurrentOwnerApplies() async {
        let registry = AudioSessionLeaseRegistry()
        var envChanges = 0
        let coordinator = DefaultAudioCoordinator(
            proximityMonitoringEnabled: false,
            onProximityChanged: { _ in },
            onAudioEnvironmentChanged: { envChanges += 1 },
            logger: nil,
            leaseRegistry: registry
        )

        let lease = AudioSessionLease(ownerTokenId: 1, generation: 1)
        coordinator.setForegroundLease(lease)
        try? await coordinator.activateCallSession(intent: AudioIntent())
        await yieldToMainActor()
        let baseline = envChanges

        // Still the current owner: a route change must apply.
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification,
            object: nil,
            userInfo: [:]
        )
        await yieldToMainActor()

        XCTAssertGreaterThan(envChanges, baseline,
                             "A route-change for the live owner must re-drive audio routing")
    }

    /// An interruption-ended observer from a superseded owner must NOT re-activate
    /// the audio session (it would steal the route from the new owner). The fence
    /// drops it before any `AVAudioSession.setActive` attempt.
    func testInterruptionObserverForSupersededOwnerIsNoOp() async {
        let registry = AudioSessionLeaseRegistry()
        let old = makeCoordinator(registry)
        let oldLease = AudioSessionLease(ownerTokenId: 1, generation: 1)
        old.setForegroundLease(oldLease)
        try? await old.activateCallSession(intent: AudioIntent())
        await yieldToMainActor()

        // NEW owner supersedes.
        let newLease = AudioSessionLease(ownerTokenId: 2, generation: 2)
        registry.install(newLease)

        // Fire interruption-ended on OLD's observer. It must bail (stale lease) and
        // must not disturb the process-global lease the new owner holds.
        NotificationCenter.default.post(
            name: AVAudioSession.interruptionNotification,
            object: nil,
            userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue]
        )
        await yieldToMainActor()

        XCTAssertEqual(registry.activeLease, newLease,
                       "A superseded owner's interruption recovery must not touch the live lease")
    }
}
