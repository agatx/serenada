import Foundation
@testable import SerenadaCore

@MainActor
final class SessionTestHarness {
    let session: SerenadaSession
    let fakeProvider: FakeSignalingProvider
    let fakeAPI: FakeAPIClient
    let fakeAudio: FakeAudioController
    let fakeAudioCoordinator: FakeAudioCoordinator
    let fakeMedia: FakeMediaEngine
    let fakeClock: FakeSessionClock

    /// The arbiter this harness's session was wired with — `.shared` by default
    /// (reset in init), or a caller-supplied dedicated arbiter for multi-session
    /// tests that must NOT share the singleton.
    let arbiter: ForegroundMediaArbiter

    init(
        roomId: String = "test-room-id",
        handlesReconnection: Bool = false,
        deferInitialAnswer: Bool = false,
        config: SerenadaConfig? = nil,
        delegate: SerenadaCoreDelegate? = nil,
        initialMediaRole: CallMediaRole = .foreground,
        // Default mirrors the public single-call `SerenadaCore.join()` path: a
        // FOREGROUND harness session self-acquires the direct lease, a HELD one
        // never does. A test that wants a registry-style FOREGROUND session (one
        // that does NOT self-acquire/self-release the lease) passes `false`.
        acquireForegroundLease: Bool? = nil,
        isCapabilityGranted: ((MediaCapability) -> Bool)? = nil,
        arbiter: ForegroundMediaArbiter? = nil
    ) {
        // The foreground arbiter is a PROCESS singleton: a prior test case may
        // have left a live lease/mode, which would make this session's direct
        // join fail to acquire. Reset it so each harness starts from a clean
        // arbiter. (Mirrors web `arbiter.__resetForTests`.) A caller-supplied
        // dedicated arbiter is used as-is (it is not the shared singleton).
        ForegroundMediaArbiter.shared.resetForTests()
        let resolvedArbiter = arbiter ?? .shared
        self.arbiter = resolvedArbiter
        self.fakeProvider = FakeSignalingProvider(handlesReconnection: handlesReconnection)
        var resolvedConfig = config ?? SerenadaConfig(signalingProvider: fakeProvider)
        if deferInitialAnswer {
            resolvedConfig.deferInitialAnswer = true
        }
        self.fakeAPI = FakeAPIClient()
        self.fakeAudio = FakeAudioController()
        self.fakeAudioCoordinator = FakeAudioCoordinator()
        self.fakeMedia = FakeMediaEngine()
        self.fakeClock = FakeSessionClock()
        if resolvedConfig.audioCoordinator == nil {
            resolvedConfig.audioCoordinator = fakeAudioCoordinator
        }

        // A FOREGROUND session self-acquires the direct lease (single-call default)
        // unless the test overrides; a HELD session never self-acquires.
        let resolvedAcquireLease = acquireForegroundLease ?? (initialMediaRole == .foreground)
        self.session = SerenadaSession(
            roomId: roomId,
            config: resolvedConfig,
            delegateProvider: delegate.map { d in { d } },
            initialSignalingProvider: fakeProvider,
            apiClient: fakeAPI,
            audioController: fakeAudio,
            mediaEngine: fakeMedia,
            clock: fakeClock,
            initialMediaRole: initialMediaRole,
            acquireForegroundLease: resolvedAcquireLease,
            isCapabilityGranted: isCapabilityGranted,
            foregroundArbiter: resolvedArbiter
        )
    }

    /// Yield to main actor to let init's async Task run (which calls beginJoinIfNeeded).
    /// If permissions are not yet granted, calls resumeJoin() to advance past the gate.
    func advancePastPermissions() async {
        await yieldToMainActor()
        if session.state.phase == .awaitingPermissions {
            session.resumeJoin()
            await yieldToMainActor()
        }
        await waitForJoinStartup()
    }

    func openSignaling(transport: String = "ws") {
        fakeProvider.simulateConnected(transport: transport)
    }

    func simulateJoinedResponse(
        cid: String = "local-cid-1",
        participants: [(cid: String, joinedAt: Int)] = [],
        hostCid: String? = nil
    ) {
        let resolvedHost = hostCid ?? cid
        let participantList = participants.isEmpty
            ? [SignalingProviderParticipant(peerId: cid, joinedAt: 1)]
            : participants.map { SignalingProviderParticipant(peerId: $0.cid, joinedAt: Int64($0.joinedAt)) }
        fakeProvider.simulateJoined(
            peerId: cid,
            participants: participantList,
            hostPeerId: resolvedHost
        )
    }

    func simulateRoomState(
        participants: [(cid: String, joinedAt: Int)],
        hostCid: String
    ) {
        fakeProvider.simulateRoomState(
            participants: participants.map { SignalingProviderParticipant(peerId: $0.cid, joinedAt: Int64($0.joinedAt)) },
            hostPeerId: hostCid
        )
    }

    /// Variant that lets a test pass full ``SignalingProviderParticipant``
    /// records — needed when the test cares about per-participant
    /// `connectionStatus` (active/suspended), `displayName`, etc.
    func simulateRoomStateWith(
        participants: [SignalingProviderParticipant],
        hostCid: String
    ) {
        fakeProvider.simulateRoomState(participants: participants, hostPeerId: hostCid)
    }

    func simulateError(code: String, message: String) {
        fakeProvider.simulateError(code: code, message: message)
    }

    func yieldToMainActor() async {
        await Task.yield()
        await Task.yield()
        await Task.yield()
        await Task.yield()
    }

    func waitForJoinStartup(attempts: Int = 32) async {
        for _ in 0..<attempts {
            if !fakeMedia.startLocalMediaCalls.isEmpty || session.state.phase != .joining {
                return
            }
            await yieldToMainActor()
        }
    }

    func waitForLocalMedia(attempts: Int = 32) async {
        for _ in 0..<attempts {
            if !fakeMedia.startLocalMediaCalls.isEmpty {
                return
            }
            await yieldToMainActor()
        }
    }

    func waitForIceServers(attempts: Int = 32) async {
        for _ in 0..<attempts {
            if fakeMedia.hasIceServers() {
                return
            }
            await yieldToMainActor()
        }
    }

    func waitForInitialOfferIfNeeded(localCid: String, remoteCid: String, attempts: Int = 32) async {
        guard localCid < remoteCid else { return }
        for _ in 0..<attempts {
            if !fakeProvider.sentPeerMessages(ofType: "offer").isEmpty {
                return
            }
            await yieldToMainActor()
        }
    }

    // MARK: - Negotiation Test Helpers

    /// Advance to inCall state with TURN credentials ready and ICE servers set.
    func advanceToInCallWithTurn(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        localJoinedAt: Int = 1,
        remoteJoinedAt: Int = 2,
        hostCid: String? = nil,
        iceServers: [IceServerConfig] = [IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")]
    ) async {
        fakeProvider.iceServerResults = [.success(iceServers)]
        await advancePastPermissions()
        await waitForLocalMedia()
        openSignaling()
        simulateJoinedResponse(
            cid: localCid,
            participants: [
                (cid: localCid, joinedAt: localJoinedAt),
                (cid: remoteCid, joinedAt: remoteJoinedAt)
            ],
            hostCid: hostCid ?? localCid
        )
        await yieldToMainActor()
        await fakeClock.advance(byMs: 100)
        await yieldToMainActor()
        await waitForLocalMedia()
        await waitForIceServers()
        await waitForInitialOfferIfNeeded(localCid: localCid, remoteCid: remoteCid)
    }

    /// Advance a HELD-initial session to in-call: a held join never calls
    /// `startLocalMedia` (no capture) so the foreground `waitForLocalMedia` path
    /// would hang. This drives signaling open + joined + ICE so the peer slot is
    /// created (stable senders, nil tracks) without ever foregrounding.
    func advanceToInCallHeld(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        localJoinedAt: Int = 1,
        remoteJoinedAt: Int = 2,
        hostCid: String? = nil,
        iceServers: [IceServerConfig] = [IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")]
    ) async {
        fakeProvider.iceServerResults = [.success(iceServers)]
        await yieldToMainActor()
        openSignaling()
        simulateJoinedResponse(
            cid: localCid,
            participants: [
                (cid: localCid, joinedAt: localJoinedAt),
                (cid: remoteCid, joinedAt: remoteJoinedAt)
            ],
            hostCid: hostCid ?? localCid
        )
        await yieldToMainActor()
        await fakeClock.advance(byMs: 100)
        await yieldToMainActor()
        await waitForIceServers()
    }

    /// Advance to inCall with a remote whose join capabilities/media policy are
    /// supplied verbatim — needed by independent-content per-peer routing tests.
    /// Mirrors ``advanceToInCallWithTurn`` but seeds the `joined` snapshot with
    /// full ``SignalingProviderParticipant`` records.
    func advanceToInCallWithCapabilities(
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        remoteIndependentContentVideo: Bool? = nil,
        remoteVideoMediaEnabled: Bool? = nil,
        hostCid: String? = nil,
        iceServers: [IceServerConfig] = [IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "user", credential: "pass")]
    ) async {
        fakeProvider.iceServerResults = [.success(iceServers)]
        await advancePastPermissions()
        await waitForLocalMedia()
        openSignaling()
        let remoteCaps = remoteIndependentContentVideo.map {
            SignalingProviderParticipantCapabilities(independentContentVideo: $0)
        }
        let remotePolicy = remoteVideoMediaEnabled.map {
            SignalingProviderParticipantMediaPolicy(videoMediaEnabled: $0)
        }
        fakeProvider.simulateJoined(
            peerId: localCid,
            participants: [
                SignalingProviderParticipant(peerId: localCid, joinedAt: 1),
                SignalingProviderParticipant(
                    peerId: remoteCid,
                    joinedAt: 2,
                    capabilities: remoteCaps,
                    mediaPolicy: remotePolicy
                )
            ],
            hostPeerId: hostCid ?? localCid
        )
        await yieldToMainActor()
        await fakeClock.advance(byMs: 100)
        await yieldToMainActor()
        await waitForLocalMedia()
        await waitForIceServers()
        await waitForInitialOfferIfNeeded(localCid: localCid, remoteCid: remoteCid)
    }

    func simulateOfferFromRemote(fromCid: String, sdp: String = "remote-offer-sdp", offerId: String? = nil) {
        var payload: [String: JSONValue] = [
            "from": .string(fromCid),
            "sdp": .string(sdp)
        ]
        if let offerId {
            payload["offerId"] = .string(offerId)
        }
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "offer",
            payload: payload
        )
    }

    func simulateAnswerFromRemote(fromCid: String, sdp: String = "remote-answer-sdp", offerId: String? = nil) {
        var payload: [String: JSONValue] = [
            "from": .string(fromCid),
            "sdp": .string(sdp)
        ]
        if let offerId {
            payload["offerId"] = .string(offerId)
        }
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "answer",
            payload: payload
        )
    }

    func simulateIceCandidateFromRemote(
        fromCid: String,
        candidate: String = "candidate:1 1 udp 2130706431 192.168.1.1 12345 typ host",
        sdpMid: String? = "0",
        sdpMLineIndex: Int = 0,
        offerId: String? = nil
    ) {
        var candidateObject: [String: JSONValue] = [
            "candidate": .string(candidate),
            "sdpMLineIndex": .number(Double(sdpMLineIndex))
        ]
        if let sdpMid {
            candidateObject["sdpMid"] = .string(sdpMid)
        }
        var payload: [String: JSONValue] = [
            "from": .string(fromCid),
            "candidate": .object(candidateObject)
        ]
        if let offerId {
            payload["offerId"] = .string(offerId)
        }
        fakeProvider.simulateMessage(
            from: fromCid,
            type: "ice",
            payload: payload
        )
    }

    func tearDown() {
        session.cancelJoin()
        // Release any lease this session acquired so the next test case starts
        // clean even if it does not build a harness (and so a leaked lease never
        // masks a real acquire failure).
        ForegroundMediaArbiter.shared.resetForTests()
    }
}
