@testable import SerenadaCore
import XCTest

/// Phase 1 foundation coverage for the independent screen-share feature:
/// outgoing `content_state` revision, receiver-side `(cid, sid)` revision
/// ordering, public `cameraEnabled`/`content` population, config flag plumbing,
/// and the guarantee that the camera mode matrix is unaffected (flag off).
@MainActor
final class IndependentContentStateTests: XCTestCase {

    // MARK: - Config flag plumbing

    func testConfigDefaultsIndependentContentVideoFalse() {
        let config = SerenadaConfig(serverHost: "serenada.app")
        XCTAssertFalse(config.enableIndependentContentVideo)
        // videoMediaEnabled retains its existing default.
        XCTAssertTrue(config.videoMediaEnabled)
    }

    func testConfigEquatableDistinguishesIndependentContentVideoFlag() {
        let off = SerenadaConfig(serverHost: "serenada.app", enableIndependentContentVideo: false)
        let on = SerenadaConfig(serverHost: "serenada.app", enableIndependentContentVideo: true)
        XCTAssertNotEqual(off, on)
        let offAgain = SerenadaConfig(serverHost: "serenada.app", enableIndependentContentVideo: false)
        XCTAssertEqual(off, offAgain)
    }

    func testSessionThreadsConfigFlagIntoJoinOptions() async {
        // The harness substitutes its own FakeSignalingProvider as the session
        // provider; assert on `harness.fakeProvider.joinCalls`. The config's
        // provider is only used for validation.
        let config = SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            videoMediaEnabled: false,
            enableIndependentContentVideo: true
        )
        let harness = SessionTestHarness(config: config)
        // Driving through the standard join path reliably reaches sendJoin,
        // which fires signalingProvider.joinRoom after the transport connects.
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        for _ in 0..<32 where harness.fakeProvider.joinCalls.isEmpty {
            await harness.yieldToMainActor()
        }
        let join = harness.fakeProvider.joinCalls.last
        XCTAssertEqual(join?.options.independentContentVideo, true)
        XCTAssertEqual(join?.options.videoMediaEnabled, false)
        harness.tearDown()
    }

    // MARK: - Outgoing content_state revision (router)

    /// A router whose `sendMessage` closure records outgoing messages, so we
    /// can assert the monotonic per-`(cid, sid)` revision contract directly.
    private func makeRecordingRouter(sink: @escaping (_ type: String, _ payload: JSONValue?) -> Void) -> SignalingMessageRouter {
        SignalingMessageRouter(
            getClientId: { "local-cid" },
            getHostCid: { "local-cid" },
            getRoomId: { "room-1" },
            onJoined: { _, _, _ in },
            onRoomState: { _, _ in },
            onRoomEnded: {},
            onPong: {},
            onTurnRefreshed: { _ in },
            onSignalingPayload: { _ in },
            onContentState: { _ in },
            onParticipantMediaState: { _ in },
            onError: { _, _ in },
            sendMessage: { type, payload, _ in sink(type, payload) }
        )
    }

    func testOutgoingContentStateRevisionIncrementsMonotonically() {
        var revisions: [Int64] = []
        let router = makeRecordingRouter { type, payload in
            guard type == "content_state" else { return }
            if let r = payload?.objectValue?["revision"]?.intValue { revisions.append(Int64(r)) }
        }

        let r1 = router.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
        let r2 = router.broadcastContentState(active: false)
        let r3 = router.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)

        XCTAssertEqual(revisions, [1, 2, 3], "every send carries a strictly greater revision")
        XCTAssertEqual([r1, r2, r3], [1, 2, 3])
    }

    func testOutgoingContentStateRevisionSeedsFromSnapshot() {
        var revisions: [Int64] = []
        let router = makeRecordingRouter { type, payload in
            guard type == "content_state" else { return }
            if let r = payload?.objectValue?["revision"]?.intValue { revisions.append(Int64(r)) }
        }

        router.seedContentRevision(7)
        let r1 = router.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
        router.seedContentRevision(3)
        let r2 = router.broadcastContentState(active: false)

        XCTAssertEqual(revisions, [8, 9])
        XCTAssertEqual([r1, r2], [8, 9])
    }

    func testOutgoingActiveStateCarriesContentTypeAndInactiveDoesNot() {
        var lastPayload: [String: JSONValue]?
        let router = makeRecordingRouter { type, payload in
            guard type == "content_state" else { return }
            lastPayload = payload?.objectValue
        }

        router.broadcastContentState(active: true, contentType: ContentTypeWire.screenShare)
        XCTAssertEqual(lastPayload?["active"]?.boolValue, true)
        XCTAssertEqual(lastPayload?["contentType"]?.stringValue, "screenShare")
        XCTAssertNotNil(lastPayload?["revision"])

        router.broadcastContentState(active: false)
        XCTAssertEqual(lastPayload?["active"]?.boolValue, false)
        XCTAssertNil(lastPayload?["contentType"], "rollback carries no contentType")
        XCTAssertNotNil(lastPayload?["revision"])
    }

    // MARK: - Receiver revision ordering ((cid, sid) tracking)

    private func sendRemoteContentState(
        _ harness: SessionTestHarness,
        from cid: String,
        sid: String?,
        active: Bool,
        revision: Int64?,
        contentType: String? = ContentTypeWire.screenShare
    ) async {
        var payload: [String: JSONValue] = ["from": .string(cid), "active": .bool(active)]
        if active, let contentType { payload["contentType"] = .string(contentType) }
        if let revision { payload["revision"] = .number(Double(revision)) }
        harness.fakeProvider.simulateMessage(from: cid, type: "content_state", payload: payload, sid: sid)
        await harness.yieldToMainActor()
    }

    private func remoteContent(_ harness: SessionTestHarness, cid: String) -> ParticipantContent? {
        harness.session.state.remoteParticipants.first(where: { $0.cid == cid })?.content
    }

    func testRemoteContentStatePopulatesParticipantContent() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 1)

        let content = remoteContent(harness, cid: "remote-cid-1")
        XCTAssertEqual(content?.active, true)
        XCTAssertEqual(content?.type, "screenShare")
        XCTAssertEqual(content?.revision, 1)
        harness.tearDown()
    }

    func testReceiverKeepsHighestRevisionWithinSameSession() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 5)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 5)

        // A stale active:false at a LOWER revision must be discarded.
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: false, revision: 3)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true,
                       "out-of-order lower revision is discarded")
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 5)

        // A newer (higher) revision is applied.
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: false, revision: 6)
        XCTAssertNil(remoteContent(harness, cid: "remote-cid-1"),
                     "active:false at a higher revision clears content")
        harness.tearDown()
    }

    func testMalformedContentStateWithoutBooleanActiveDoesNotClearContent() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 1)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true)

        harness.fakeProvider.simulateMessage(
            from: "remote-cid-1",
            type: "content_state",
            payload: ["from": .string("remote-cid-1"), "revision": .number(2)],
            sid: "S-r"
        )
        await harness.yieldToMainActor()

        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 1)
        harness.tearDown()
    }

    func testReceiverDiscardsEqualRevisionWithinSameSession() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true,
                                     revision: 4, contentType: ContentTypeWire.screenShare)
        // Equal revision with a different type must be ignored (<=).
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true,
                                     revision: 4, contentType: ContentTypeWire.worldCamera)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.type, "screenShare",
                       "equal revision is discarded; first-write wins")
        harness.tearDown()
    }

    func testNewSessionSupersedesByIdentityEvenWhenRevisionResets() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-old", active: true, revision: 7)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 7)

        // Rejoin: a NEW sid restarting at revision:1 must supersede by identity.
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-new", active: true,
                                     revision: 1, contentType: ContentTypeWire.worldCamera)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 1,
                       "new sid resets tracked revision; revision:1 accepted")
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.type, "worldCamera")
        harness.tearDown()
    }

    // MARK: - Reconnect snapshot reconciliation (room_state contentState)

    /// Deliver a `room_state` snapshot whose remote participant carries a
    /// server-persisted `contentState` — the reconnect-restore path.
    private func sendRoomStateSnapshot(
        _ harness: SessionTestHarness,
        localCid: String,
        remoteCid: String,
        snapshotActive: Bool,
        snapshotRevision: Int64?,
        snapshotType: String? = ContentTypeWire.screenShare
    ) async {
        let content = SignalingProviderParticipantContentState(
            active: snapshotActive,
            contentType: snapshotActive ? snapshotType : nil,
            revision: snapshotRevision
        )
        harness.simulateRoomStateWith(
            participants: [
                SignalingProviderParticipant(peerId: localCid, joinedAt: 1),
                SignalingProviderParticipant(peerId: remoteCid, joinedAt: 2, contentState: content)
            ],
            hostCid: localCid
        )
        await harness.yieldToMainActor()
    }

    /// A reconnect snapshot at a strictly-higher revision supersedes a stale
    /// cached live state — including an `active:false` rollback the client
    /// missed while disconnected, which must clear a stale cached `active:true`.
    func testReconnectSnapshotWithHigherRevisionSupersedesCachedActive() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        // Cache a live active:true revision 5 for the remote cid.
        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 5)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 5)

        // Reconnect snapshot: active:false revision 6 supersedes the stale cache.
        await sendRoomStateSnapshot(
            harness, localCid: "local-cid-1", remoteCid: "remote-cid-1",
            snapshotActive: false, snapshotRevision: 6
        )
        XCTAssertNil(remoteContent(harness, cid: "remote-cid-1"),
                     "snapshot active:false at a higher revision clears the stale cached active:true")
        harness.tearDown()
    }

    /// A reconnect snapshot at a lower revision must NOT override a higher
    /// cached live state (keep-highest, keyed by cid).
    func testReconnectSnapshotWithLowerRevisionDoesNotOverrideCached() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 5)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 5)

        // Stale snapshot at a lower revision is ignored; cached active:true wins.
        await sendRoomStateSnapshot(
            harness, localCid: "local-cid-1", remoteCid: "remote-cid-1",
            snapshotActive: false, snapshotRevision: 3
        )
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true,
                       "snapshot at a lower revision does not override the higher cached state")
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 5)
        harness.tearDown()
    }

    func testRevisionlessLiveStopDoesNotLowerSnapshotGate() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: true, revision: 5)
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true)

        await sendRemoteContentState(harness, from: "remote-cid-1", sid: "S-r", active: false, revision: nil)
        XCTAssertNil(remoteContent(harness, cid: "remote-cid-1"))

        await sendRoomStateSnapshot(
            harness, localCid: "local-cid-1", remoteCid: "remote-cid-1",
            snapshotActive: true, snapshotRevision: 5
        )
        XCTAssertNil(remoteContent(harness, cid: "remote-cid-1"),
                     "revisionless live stop keeps the revision 5 high-water, so revision 5 snapshot cannot reactivate")
        harness.tearDown()
    }

    /// With no cached live state, a reconnect snapshot is adopted as-is.
    func testReconnectSnapshotAdoptedWhenNoCachedState() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")

        await sendRoomStateSnapshot(
            harness, localCid: "local-cid-1", remoteCid: "remote-cid-1",
            snapshotActive: true, snapshotRevision: 2
        )
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.active, true,
                       "snapshot adopted when there is no cached live state")
        XCTAssertEqual(remoteContent(harness, cid: "remote-cid-1")?.revision, 2)
        harness.tearDown()
    }

    // MARK: - cameraEnabled mirrors videoEnabled (mode matrix unaffected)

    func testLocalCameraEnabledMirrorsVideoEnabled() async {
        let harness = SessionTestHarness()
        await harness.advancePastPermissions()
        await harness.waitForLocalMedia()
        harness.openSignaling()
        harness.simulateJoinedResponse()
        await harness.yieldToMainActor()

        let local = harness.session.state.localParticipant
        XCTAssertEqual(local.cameraEnabled, local.videoEnabled,
                       "Phase 1: local cameraEnabled mirrors videoEnabled")
        // Local cameraMode is unaffected by Phase 1 (flag off).
        XCTAssertFalse(local.cameraMode == .screenShare)
        harness.tearDown()
    }

    func testRemoteCameraEnabledMirrorsVideoEnabled() async {
        let harness = SessionTestHarness()
        await harness.advanceToInCallWithTurn(localCid: "local-cid-1", remoteCid: "remote-cid-1")
        harness.fakeProvider.simulateMessage(
            from: "remote-cid-1",
            type: "participant_media_state",
            payload: ["from": .string("remote-cid-1"), "audioEnabled": .bool(true), "videoEnabled": .bool(true)]
        )
        await harness.yieldToMainActor()

        let remote = harness.session.state.remoteParticipants.first(where: { $0.cid == "remote-cid-1" })
        XCTAssertEqual(remote?.cameraEnabled, remote?.videoEnabled,
                       "Phase 1: remote cameraEnabled mirrors videoEnabled")
        harness.tearDown()
    }
}
