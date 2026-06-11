@testable import SerenadaCore
import XCTest

private final class BlockingAudioCoordinator: @unchecked Sendable, SerenadaAudioCoordinator {
    private(set) var activateCalls = 0
    private var activationContinuation: CheckedContinuation<Void, Error>?

    func activateCallSession(intent: AudioIntent) async throws {
        activateCalls += 1
        return try await withCheckedThrowingContinuation { continuation in
            activationContinuation = continuation
        }
    }

    func finishActivation() {
        activationContinuation?.resume()
        activationContinuation = nil
    }

    func deactivateCallSession() async {}
    func applyRouting(_ device: AudioDevice) async throws {}
    func setMicMuted(_ muted: Bool) async throws {}

    var availableDevices: AsyncStream<[AudioDevice]> { AsyncStream { _ in } }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { AsyncStream { _ in } }
    var events: AsyncStream<AudioCoordinatorEvent> { AsyncStream { _ in } }
}

private final class RetainedStreamAudioCoordinator: @unchecked Sendable, SerenadaAudioCoordinator {
    private var availableDevicesContinuation: AsyncStream<[AudioDevice]>.Continuation?
    private var effectiveInputContinuation: AsyncStream<AudioDevice?>.Continuation?
    private var effectiveOutputContinuation: AsyncStream<AudioDevice?>.Continuation?
    private var eventsContinuation: AsyncStream<AudioCoordinatorEvent>.Continuation?

    lazy var availableDevices: AsyncStream<[AudioDevice]> = AsyncStream { [weak self] continuation in
        self?.availableDevicesContinuation = continuation
    }

    lazy var effectiveInputDevice: AsyncStream<AudioDevice?> = AsyncStream { [weak self] continuation in
        self?.effectiveInputContinuation = continuation
    }

    lazy var effectiveOutputDevice: AsyncStream<AudioDevice?> = AsyncStream { [weak self] continuation in
        self?.effectiveOutputContinuation = continuation
    }

    lazy var events: AsyncStream<AudioCoordinatorEvent> = AsyncStream { [weak self] continuation in
        self?.eventsContinuation = continuation
    }

    func activateCallSession(intent: AudioIntent) async throws {}
    func deactivateCallSession() async {}
    func applyRouting(_ device: AudioDevice) async throws {}
    func setMicMuted(_ muted: Bool) async throws {}

    func emit(_ event: AudioCoordinatorEvent) {
        eventsContinuation?.yield(event)
    }
}

@MainActor
final class SerenadaSessionTests: XCTestCase {
    func testJoinUrlUsesDeepLinkHostInsteadOfDefaultConfigHost() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let url = URL(string: "https://serenada-app.ru/call/\(roomId)")!
        let core = SerenadaCore(config: SerenadaConfig(serverHost: "serenada.app"))

        let session = core.join(url: url)

        XCTAssertEqual(session.serverHost, "serenada-app.ru")
        XCTAssertEqual(session.roomUrl, url)

        session.cancelJoin()
    }

    func testJoinRoomBuildsRoomUrlWithLocalPort() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let core = SerenadaCore(config: SerenadaConfig(serverHost: "localhost:8080"))

        let session = core.join(roomId: roomId)

        XCTAssertEqual(session.roomUrl?.absoluteString, "http://localhost:8080/call/\(roomId)")
        XCTAssertEqual(session.serverHost, "localhost:8080")

        session.cancelJoin()
    }

    func testSessionStartsInJoiningPhaseBeforeAsyncJoinBegins() {
        let roomId = "YovflsGamCygX912gb26Jeaq8Es"
        let session = SerenadaSession(
            roomId: roomId,
            config: SerenadaConfig(serverHost: "serenada.app")
        )

        XCTAssertEqual(session.state.phase, .joining)
        XCTAssertEqual(session.state.roomId, roomId)

        session.cancelJoin()
    }

    func testProviderModeJoinByRoomIdHasNoRoomUrl() {
        let provider = FakeSignalingProvider()
        let core = SerenadaCore(config: SerenadaConfig(signalingProvider: provider))

        let session = core.join(roomId: "provider-room")

        XCTAssertNil(session.roomUrl)
        session.cancelJoin()
    }

    func testDefaultVideoDisabledDoesNotRequireCameraBeforeJoin() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let coordinator = FakeAudioCoordinator()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                defaultVideoEnabled: false,
                cameraModes: [.selfie, .world],
                audioCoordinator: coordinator
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await Task.yield()
        await Task.yield()
        await Task.yield()

        if session.state.phase == .awaitingPermissions {
            XCTAssertFalse(session.state.requiredPermissions?.contains(.camera) ?? false)
            session.resumeJoin()
            await Task.yield()
            await Task.yield()
        }

        await waitUntil { !media.startLocalMediaCalls.isEmpty }
        XCTAssertEqual(media.startLocalMediaCalls.first, false)
        session.cancelJoin()
    }

    func testAudioSessionRestartedRestartsAudioUnitAndClearsExternalMute() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let coordinator = RetainedStreamAudioCoordinator()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                audioCoordinator: coordinator
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
        await waitUntil { !media.startLocalMediaCalls.isEmpty }

        coordinator.emit(.externalAudioStarted)
        await waitUntil { session.isMicMutedByExternalAudio }
        XCTAssertTrue(session.isMicMutedByExternalAudio, "externalAudioStarted should mute the WebRTC mic")

        coordinator.emit(.audioSessionRestarted)
        await waitUntil { media.restartAudioUnitCalls == 1 }
        XCTAssertEqual(media.restartAudioUnitCalls, 1, "audioSessionRestarted must restart the audio unit (no interruption notification fires for a same-app takeover)")
        XCTAssertFalse(session.isMicMutedByExternalAudio, "audioSessionRestarted should clear the external-audio mute")

        session.cancelJoin()
    }

    func testAdjustCameraZoomAllowedWhileWaitingWithContentCamera() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                cameraModes: [.world]
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
        provider.simulateConnected()
        provider.simulateJoined(
            peerId: "local-cid-1",
            participants: [SignalingProviderParticipant(peerId: "local-cid-1", joinedAt: 1)],
            hostPeerId: "local-cid-1"
        )
        await yieldToMainActor()

        XCTAssertEqual(session.state.phase, .waiting)
        XCTAssertEqual(session.state.localParticipant.cameraMode, .world)
        XCTAssertEqual(session.adjustCameraZoom(by: 1.2), 1.25)
        XCTAssertEqual(media.adjustCaptureZoomCalls, [1.2])
        session.cancelJoin()
    }

    func testCancelJoinPreventsPendingAudioActivationFromStartingMedia() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let coordinator = BlockingAudioCoordinator()
        let session = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                defaultAudioEnabled: false,
                defaultVideoEnabled: false,
                audioCoordinator: coordinator
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )

        await waitUntil { coordinator.activateCalls == 1 }
        session.cancelJoin()
        coordinator.finishActivation()
        await yieldToMainActor()

        XCTAssertEqual(session.state.phase, .idle)
        XCTAssertTrue(media.startLocalMediaCalls.isEmpty)
        XCTAssertEqual(provider.connectCalls, 0)
    }

    func testSessionDeinitializesWhileCoordinatorStreamsRemainOpen() async {
        let provider = FakeSignalingProvider()
        let media = FakeMediaEngine()
        let coordinator = RetainedStreamAudioCoordinator()
        weak var weakSession: SerenadaSession?
        var session: SerenadaSession? = SerenadaSession(
            roomId: "provider-room",
            config: SerenadaConfig(
                signalingProvider: provider,
                defaultAudioEnabled: false,
                defaultVideoEnabled: false,
                audioCoordinator: coordinator
            ),
            initialSignalingProvider: provider,
            mediaEngine: media
        )
        weakSession = session

        await yieldToMainActor()
        session?.cancelJoin()
        session = nil
        await yieldToMainActor()

        XCTAssertNil(weakSession)
        withExtendedLifetime(coordinator) {}
    }

    private func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
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
}
