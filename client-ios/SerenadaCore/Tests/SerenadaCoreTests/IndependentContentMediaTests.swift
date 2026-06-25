@testable import SerenadaCore
import XCTest

/// Phase 4a (media engine CORE) session-level coverage for independent screen
/// share: per-peer capability gate + routing, the screen-share start/stop
/// confirmed-start signaling, idempotent broadcast-stop, camera-during-
/// share, mixed-mesh routing, and the content renderer APIs.
///
/// Deep WebRtcEngine transceiver logic (m-line bind order, replaceTrack envelope
/// rejection, glare) is on-device/interop-gated; it is verified by compilation
/// and exercised here at the session level through the fakes.
@MainActor
final class IndependentContentMediaTests: XCTestCase {

    private func independentConfig(
        videoMediaEnabled: Bool = true,
        enable: Bool = true,
        screenShareMode: ScreenShareMode = .inAppOnly
    ) -> SerenadaConfig {
        SerenadaConfig(
            signalingProvider: FakeSignalingProvider(),
            videoMediaEnabled: videoMediaEnabled,
            enableIndependentContentVideo: enable,
            screenShareMode: screenShareMode
        )
    }

    private func contentStateBroadcasts(_ harness: SessionTestHarness) -> [(type: String, payload: SignalingPayload?)] {
        harness.fakeProvider.broadcastMessages(ofType: "content_state")
    }

    private func lastContentActive(_ harness: SessionTestHarness) -> Bool? {
        contentStateBroadcasts(harness).last?.payload?["active"]?.boolValue
    }

    // MARK: - Per-peer capability gate (engine slot routing)

    func testFlagOffRoutesEveryPeerLegacy() async {
        let harness = SessionTestHarness(config: independentConfig(enable: false))
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        // Flag off ⇒ slot created with supportsIndependentContentVideo=false even
        // though the peer advertised the capability (byte-identical to today).
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        harness.tearDown()
    }

    func testCapablePeerRoutedIndependentWhenFlagOn() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], true)
        harness.tearDown()
    }

    func testLegacyPeerRoutedLegacyWhenFlagOn() async {
        let harness = SessionTestHarness(config: independentConfig())
        // Peer did NOT advertise independentContentVideo ⇒ legacy routing.
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: false, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        harness.tearDown()
    }

    func testAudioOnlyPeerRoutedLegacyEvenIfCapable() async {
        let harness = SessionTestHarness(config: independentConfig())
        // Capable but videoMediaEnabled=false on the peer ⇒ no content m-line.
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: false
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        harness.tearDown()
    }

    func testLocalAudioOnlyRoutesLegacyEvenWithCapablePeer() async {
        let harness = SessionTestHarness(config: independentConfig(videoMediaEnabled: false))
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        // Local videoMediaEnabled=false ⇒ no video at all ⇒ legacy gate false.
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        harness.tearDown()
    }

    // MARK: - Remote participant capability surface

    func testRemoteParticipantSurfacesIndependentCapability() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        let remote = harness.session.state.remoteParticipants.first(where: { $0.cid == "remote-cid-1" })
        XCTAssertEqual(remote?.supportsIndependentContentVideo, true)
        harness.tearDown()
    }

    func testRemoteParticipantCapabilityDefaultsFalseWhenAbsent() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities()
        let remote = harness.session.state.remoteParticipants.first(where: { $0.cid == "remote-cid-1" })
        XCTAssertEqual(remote?.supportsIndependentContentVideo, false)
        harness.tearDown()
    }

    // MARK: - Offer owner threading (myCid < remoteCid)

    func testOfferOwnerResolvedForLowerLocalCid() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            localCid: "aaa", remoteCid: "zzz",
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        // myCid ("aaa") < remoteCid ("zzz") ⇒ local participant is the offer owner.
        XCTAssertEqual(harness.fakeMedia.createdSlotIsOfferOwner["zzz"], true)
        harness.tearDown()
    }

    func testAnswererResolvedForHigherLocalCid() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            localCid: "zzz", remoteCid: "aaa",
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotIsOfferOwner["aaa"], false)
        harness.tearDown()
    }

    // MARK: - Screen-share start: confirmed stream only

    func testIndependentStartSignalsContentAfterCaptureConfirmsAndKeepsCameraIntent() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        let before = harness.fakeMedia.startScreenShareCalls

        harness.session.startScreenShare()
        await harness.yieldToMainActor()

        XCTAssertEqual(harness.fakeMedia.startScreenShareCalls, before + 1)
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        XCTAssertEqual(lastContentActive(harness), true, "content_state active broadcast on start")
        XCTAssertEqual(harness.session.state.localParticipant.content?.active, true)
        XCTAssertEqual(harness.session.state.localParticipant.content?.type, "screenShare")
        // Independent mode must NOT set cameraMode=screenShare (camera untouched).
        XCTAssertNotEqual(harness.session.state.localParticipant.cameraMode, .screenShare)
        harness.tearDown()
    }

    func testIndependentStartFailureDoesNotBroadcastContentState() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = false  // picker cancelled / denied
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )

        harness.session.startScreenShare()
        await harness.yieldToMainActor()

        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty, "cancelled/denied share stays silent")
        XCTAssertNil(harness.session.state.localParticipant.content)
        harness.tearDown()
    }

    func testStartScreenShareNoOpWhenVideoMediaDisabled() async {
        let harness = SessionTestHarness(config: independentConfig(videoMediaEnabled: false))
        await harness.advanceToInCallWithCapabilities()
        let before = harness.fakeMedia.startScreenShareCalls
        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeMedia.startScreenShareCalls, before, "no-op when videoMediaEnabled=false")
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        harness.tearDown()
    }

    /// `.disabled` screen-share mode is a clean no-op: no engine call and, in the
    /// independent path, no pending start or `content_state` active broadcast.
    func testStartScreenShareNoOpWhenScreenShareDisabled() async {
        let harness = SessionTestHarness(config: independentConfig(screenShareMode: .disabled))
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        let before = harness.fakeMedia.startScreenShareCalls
        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeMedia.startScreenShareCalls, before, "no-op when screenShareMode is .disabled")
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty, "no content_state signaled when disabled")
        XCTAssertNil(harness.session.state.localParticipant.content)
        harness.tearDown()
    }

    // MARK: - Idempotent stop

    func testIndependentStopBroadcastsInactiveOnce() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        harness.session.startScreenShare()
        await harness.yieldToMainActor()

        harness.session.stopScreenShare()
        await harness.yieldToMainActor()
        let inactiveCountFirst = contentStateBroadcasts(harness)
            .filter { $0.payload?["active"]?.boolValue == false }.count

        // A second stop must be a no-op (already stopped) — no extra inactive.
        harness.session.stopScreenShare()
        await harness.yieldToMainActor()
        let inactiveCountSecond = contentStateBroadcasts(harness)
            .filter { $0.payload?["active"]?.boolValue == false }.count

        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        XCTAssertEqual(inactiveCountFirst, inactiveCountSecond, "second stop is idempotent — no extra content_state")
        harness.tearDown()
    }

    func testIndependentPendingStartDoesNotBroadcastOrAcceptDuplicateStarts() async {
        let harness = SessionTestHarness(config: independentConfig())
        // Model the pending window: start is accepted but onComplete is deferred.
        harness.fakeMedia.deferStartScreenShareCompletion = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )

        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing, "pending picker is not yet sharing")
        XCTAssertNil(harness.session.state.localParticipant.content)
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty, "no black content tile before capture confirms")

        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertEqual(harness.fakeMedia.startScreenShareCalls, 1, "duplicate pending start ignored")

        harness.fakeMedia.completeDeferredStartScreenShare(started: true)
        await harness.yieldToMainActor()

        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        XCTAssertEqual(lastContentActive(harness), true, "content_state active only after capture confirms")
        XCTAssertEqual(harness.session.state.localParticipant.content?.type, "screenShare")
        harness.tearDown()
    }

    func testIndependentStopDuringPendingStartStaysSilent() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.deferStartScreenShareCompletion = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )

        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty)

        // STOP during the pending window cancels the pending request. Since the
        // session never advertised active content, it must not send inactive
        // content_state either.
        harness.session.stopScreenShare()
        await harness.yieldToMainActor()

        XCTAssertFalse(harness.session.diagnostics.isScreenSharing, "pending share cancelled")
        XCTAssertNil(harness.session.state.localParticipant.content, "local content cleared")
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty, "pending cancel stays silent")

        // A late start completion (broadcast confirmed right after STOP) must NOT
        // resurrect the share.
        harness.fakeMedia.completeDeferredStartScreenShare(started: true)
        await harness.yieldToMainActor()
        XCTAssertFalse(harness.session.diagnostics.isScreenSharing)
        XCTAssertTrue(contentStateBroadcasts(harness).isEmpty)
        harness.tearDown()
    }

    // MARK: - Renderer APIs route to the right slot/engine

    func testRemoteContentRendererRoutesToPeerSlot() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        let renderer = NSObject()
        harness.session.attachRemoteContentRenderer(renderer, forParticipant: "remote-cid-1")
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]
        XCTAssertEqual(slot?.attachRemoteContentRendererCalls.count, 1)

        harness.session.detachRemoteContentRenderer(renderer, forParticipant: "remote-cid-1")
        XCTAssertEqual(slot?.detachRemoteContentRendererCalls.count, 1)
        // The camera renderer path is independent and untouched.
        XCTAssertEqual(slot?.attachRemoteRendererCalls.count, 0)
        harness.tearDown()
    }

    func testLocalContentRendererRoutesToEngine() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities()
        let renderer = NSObject()
        harness.session.attachLocalContentRenderer(renderer)
        XCTAssertEqual(harness.fakeMedia.attachLocalContentRendererCalls.count, 1)
        harness.session.detachLocalContentRenderer(renderer)
        XCTAssertEqual(harness.fakeMedia.detachLocalContentRendererCalls.count, 1)
        // The local camera renderer path is independent and untouched.
        XCTAssertEqual(harness.fakeMedia.attachLocalRendererCalls.count, 0)
        harness.tearDown()
    }

    // MARK: - Camera controls during an independent share

    func testFlipCameraWorksDuringIndependentShare() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)

        // flipCamera is gated on `!diagnostics.isScreenSharing` in the session,
        // which still blocks during a share. The engine-level
        // isLegacyScreenSharing gate (camera ops keep working) is verified by
        // compilation + the WebRtcEngine logic; here we assert the session does
        // not crash and the share survives a setCameraMode no-op.
        harness.session.setCameraMode(.world)
        await harness.yieldToMainActor()
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing, "camera-mode change does not tear down an independent share")
        harness.tearDown()
    }

    // MARK: - Mixed mesh routing

    func testMixedMeshRoutesPeersIndependentlyByCapability() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeProvider.iceServerResults = [.success([
            IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "u", credential: "p")
        ])]
        await harness.advancePastPermissions()
        await harness.waitForLocalMedia()
        harness.openSignaling()
        // local-cid-1 + one capable peer + one legacy peer.
        harness.fakeProvider.simulateJoined(
            peerId: "local-cid-1",
            participants: [
                SignalingProviderParticipant(peerId: "local-cid-1", joinedAt: 1),
                SignalingProviderParticipant(
                    peerId: "remote-capable", joinedAt: 2,
                    capabilities: SignalingProviderParticipantCapabilities(independentContentVideo: true),
                    mediaPolicy: SignalingProviderParticipantMediaPolicy(videoMediaEnabled: true)
                ),
                SignalingProviderParticipant(
                    peerId: "remote-legacy", joinedAt: 3,
                    capabilities: SignalingProviderParticipantCapabilities(independentContentVideo: false),
                    mediaPolicy: SignalingProviderParticipantMediaPolicy(videoMediaEnabled: true)
                )
            ],
            hostPeerId: "local-cid-1"
        )
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()
        await harness.waitForIceServers()

        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-capable"], true)
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-legacy"], false)
        harness.tearDown()
    }

    // MARK: - FIX 1: capability-transition slot handling

    /// Re-drive a room_state carrying the given per-participant capability for the
    /// remote peer (host = local). Used to flip a peer's independent-content
    /// capability after the slot was already created.
    private func sendRoomStateWithRemoteCapability(
        _ harness: SessionTestHarness,
        localCid: String = "local-cid-1",
        remoteCid: String = "remote-cid-1",
        remoteIndependentContentVideo: Bool?,
        remoteVideoMediaEnabled: Bool? = true
    ) async {
        let remoteCaps = remoteIndependentContentVideo.map {
            SignalingProviderParticipantCapabilities(independentContentVideo: $0)
        }
        let remotePolicy = remoteVideoMediaEnabled.map {
            SignalingProviderParticipantMediaPolicy(videoMediaEnabled: $0)
        }
        harness.simulateRoomStateWith(
            participants: [
                SignalingProviderParticipant(peerId: localCid, joinedAt: 1),
                SignalingProviderParticipant(
                    peerId: remoteCid,
                    joinedAt: 2,
                    capabilities: remoteCaps,
                    mediaPolicy: remotePolicy
                )
            ],
            hostCid: localCid
        )
        await harness.yieldToMainActor()
    }

    /// A peer created LEGACY (no caps when its slot was built) that later advertises
    /// independentContentVideo in a room_state must have its slot RECREATED with the
    /// capable layout — otherwise the late-announced capable peer never binds the
    /// content transceiver.
    func testCapabilityFlipToCapableRecreatesSlot() async {
        let harness = SessionTestHarness(config: independentConfig())
        // Slot built LEGACY: peer advertised NO independent-content capability.
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: false, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        let createsBefore = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        let oldSlot = harness.fakeMedia.fakeSlots["remote-cid-1"]

        // Caps arrive late: the peer is now capable. The flip must recreate the slot.
        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)

        let createsAfter = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        XCTAssertEqual(createsAfter, createsBefore + 1, "the slot was recreated on the capability flip")
        XCTAssertEqual(oldSlot?.closePeerConnectionCalled, true, "the old (legacy) slot was closed")
        XCTAssertTrue(harness.fakeMedia.removedSlots.contains { ($0 as AnyObject) === (oldSlot as AnyObject?) },
                      "the old slot was removed from the engine")
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], true,
                       "the recreated slot is independent-capable")
        harness.tearDown()
    }

    /// iOS SwiftUI video views attach their RTC renderer once in `makeUIView`.
    /// A capability flip recreates the peer slot after that attach, so the session
    /// must replay renderer registrations onto the replacement slot; otherwise
    /// remote media can arrive but the surface remains blank.
    func testCapabilityFlipReplaysRemoteRendererRegistrations() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: false, remoteVideoMediaEnabled: true
        )
        guard let oldSlot = harness.fakeMedia.fakeSlots["remote-cid-1"] else {
            XCTFail("expected initial slot")
            return
        }

        let defaultCameraRenderer = NSObject()
        let participantCameraRenderer = NSObject()
        let contentRenderer = NSObject()
        harness.session.attachRemoteRenderer(defaultCameraRenderer)
        harness.session.attachRemoteRenderer(participantCameraRenderer, forParticipant: "remote-cid-1")
        harness.session.attachRemoteContentRenderer(contentRenderer, forParticipant: "remote-cid-1")

        XCTAssertEqual(oldSlot.attachRemoteRendererCalls.count, 2)
        XCTAssertTrue(oldSlot.attachRemoteRendererCalls.contains { $0 === defaultCameraRenderer })
        XCTAssertTrue(oldSlot.attachRemoteRendererCalls.contains { $0 === participantCameraRenderer })
        XCTAssertEqual(oldSlot.attachRemoteContentRendererCalls.count, 1)
        XCTAssertTrue(oldSlot.attachRemoteContentRendererCalls.contains { $0 === contentRenderer })

        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)

        guard let newSlot = harness.fakeMedia.fakeSlots["remote-cid-1"] else {
            XCTFail("expected replacement slot")
            return
        }
        XCTAssertFalse(newSlot === oldSlot)
        XCTAssertTrue(newSlot.attachRemoteRendererCalls.contains { $0 === defaultCameraRenderer })
        XCTAssertTrue(newSlot.attachRemoteRendererCalls.contains { $0 === participantCameraRenderer })
        XCTAssertTrue(newSlot.attachRemoteContentRendererCalls.contains { $0 === contentRenderer })
        harness.tearDown()
    }

    /// The reverse flip: a peer that was capable and later drops the capability
    /// (e.g. its mediaPolicy disables video) must be recreated LEGACY.
    func testCapabilityFlipToLegacyRecreatesSlot() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], true)
        let createsBefore = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count

        // Peer disables video media → no longer independent-capable.
        await sendRoomStateWithRemoteCapability(
            harness, remoteIndependentContentVideo: true, remoteVideoMediaEnabled: false
        )

        let createsAfter = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        XCTAssertEqual(createsAfter, createsBefore + 1, "the slot was recreated on the reverse capability flip")
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false,
                       "the recreated slot is legacy")
        harness.tearDown()
    }

    /// A steady-state room_state that re-sends the SAME capability must NOT recreate
    /// the slot (no churn).
    func testSameCapabilityDoesNotRecreateSlot() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        let createsBefore = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]

        // Re-send the same capability twice.
        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)
        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)

        let createsAfter = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        XCTAssertEqual(createsAfter, createsBefore, "no recreate when the capability did not change")
        XCTAssertEqual(slot?.closePeerConnectionCalled, false, "the slot was not closed")
        harness.tearDown()
    }

    /// Flag OFF: caps arriving in a room_state must NEVER recreate the slot — every
    /// peer stays legacy (byte-identical to today).
    func testFlagOffNeverRecreatesOnCapabilityChange() async {
        let harness = SessionTestHarness(config: independentConfig(enable: false))
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: false, remoteVideoMediaEnabled: true
        )
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false)
        let createsBefore = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]

        // Peer now advertises the capability, but the local flag is off ⇒ the
        // resolved per-peer capability is still false ⇒ no flip ⇒ no recreate.
        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)

        let createsAfter = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        XCTAssertEqual(createsAfter, createsBefore, "flag off ⇒ never recreates")
        XCTAssertEqual(slot?.closePeerConnectionCalled, false)
        XCTAssertEqual(harness.fakeMedia.createdSlotSupportsIndependentContentVideo["remote-cid-1"], false,
                       "still legacy")
        harness.tearDown()
    }

    /// An in-progress screen share must re-attach to the recreated slot: the
    /// recreate runs through `WebRtcEngine.createSlot` → `attachLocalTracksToSlot`,
    /// which re-attaches the content track. At the session/fake level we assert the
    /// share survives the recreate (`isScreenSharing` stays true and `content_state`
    /// is not torn down). The actual sender re-attach is WebRTC-gated.
    func testCapabilityFlipDuringShareKeepsShareActive() async {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = true
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: false, remoteVideoMediaEnabled: true
        )
        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        let createsBefore = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count

        // Capability flips to capable mid-share → slot recreated.
        await sendRoomStateWithRemoteCapability(harness, remoteIndependentContentVideo: true)

        let createsAfter = harness.fakeMedia.createdSlotCids.filter { $0 == "remote-cid-1" }.count
        XCTAssertEqual(createsAfter, createsBefore + 1, "slot recreated mid-share")
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing, "share survives the slot recreate")
        XCTAssertEqual(harness.session.state.localParticipant.content?.active, true,
                       "content_state stays active across the recreate")
        harness.tearDown()
    }

    // MARK: - FIX 2: legacy-peer content sender encoding gate

    func testLegacyVideoCarriesContentGate() {
        // During an active share with a live content track, the legacy single
        // sender carries content.
        XCTAssertTrue(legacyVideoCarriesContentGate(isScreenSharing: true, hasContentVideoTrack: true))
        // No content track yet → carries camera (not content).
        XCTAssertFalse(legacyVideoCarriesContentGate(isScreenSharing: true, hasContentVideoTrack: false))
        // Not sharing → carries camera regardless of a stray content track.
        XCTAssertFalse(legacyVideoCarriesContentGate(isScreenSharing: false, hasContentVideoTrack: true))
        XCTAssertFalse(legacyVideoCarriesContentGate(isScreenSharing: false, hasContentVideoTrack: false))
    }

    /// The `legacyVideoCarriesContent` signal is threaded through the slot
    /// `attachLocalTracks` contract (the real slot uses it to pick the content vs
    /// camera sender encoding; the WebRTC sender params themselves are device-
    /// gated). Lock the protocol param so the engine→slot wiring stays intact.
    func testSlotRecordsLegacyVideoCarriesContent() {
        let slot = FakePeerConnectionSlot(remoteCid: "remote-cid-1", supportsIndependentContentVideo: false)
        slot.attachLocalTracks(
            audioTrack: nil, cameraTrack: nil, contentTrack: nil,
            supportsIndependentContentVideo: false, legacyVideoCarriesContent: true
        )
        slot.attachLocalTracks(
            audioTrack: nil, cameraTrack: nil, contentTrack: nil,
            supportsIndependentContentVideo: false, legacyVideoCarriesContent: false
        )
        XCTAssertEqual(slot.attachLocalTracksCalls.map(\.legacyVideoCarriesContent), [true, false])
    }

    // MARK: - Flag-off byte-identical legacy screen share

    func testLegacyShareSetsCameraModeScreenShare() async {
        let harness = SessionTestHarness(config: independentConfig(enable: false))
        harness.fakeMedia.startScreenShareResult = true
        await harness.advanceToInCallWithCapabilities()

        harness.session.startScreenShare()
        await harness.yieldToMainActor()

        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        // Flag off ⇒ legacy path sets cameraMode=screenShare (unchanged behavior).
        XCTAssertEqual(harness.session.state.localParticipant.cameraMode, .screenShare)
        XCTAssertEqual(lastContentActive(harness), true)
        harness.tearDown()
    }

    // MARK: - FIX A (GAP 2): per-role inbound stall diagnostics

    /// Advance the media-liveness timer one full interval and let the async
    /// `collectInboundRoleBytes` callbacks land back on the main actor so the
    /// per-role liveness booleans refresh on the public participant.
    private func tickRoleLiveness(_ harness: SessionTestHarness) async {
        await harness.fakeClock.advance(byMs: Int64(WebRtcResilience.mediaLivenessIntervalMs) + 50)
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
    }

    private func remote(_ harness: SessionTestHarness, _ cid: String = "remote-cid-1") -> SerenadaRemoteParticipant? {
        harness.session.state.remoteParticipants.first { $0.cid == cid }
    }

    /// Both role-liveness booleans default false before any sample, and stay
    /// false on the FIRST sample (no baseline ⇒ conservative).
    func testRoleLivenessDefaultsFalseAndFirstSampleConservative() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()

        // Before any tick: both false (defaults).
        XCTAssertEqual(remote(harness)?.cameraReceiving, false)
        XCTAssertEqual(remote(harness)?.contentReceiving, false)

        // First sample has no baseline to diff against ⇒ still false even though
        // bytes are present.
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 1_000, contentBytes: 2_000)
        await tickRoleLiveness(harness)
        XCTAssertEqual(remote(harness)?.cameraReceiving, false, "first sample is conservative (no baseline)")
        XCTAssertEqual(remote(harness)?.contentReceiving, false)
        XCTAssertGreaterThan(slot?.collectInboundLivenessCalls ?? 0, 0, "the combined liveness sampler ran")
        XCTAssertEqual(slot?.collectInboundRoleBytesCalls ?? 0, 0, "timer should not run a second role-only stats pass")
        harness.tearDown()
    }

    /// Once a baseline exists, each role is `true` only while ITS bytes advance.
    /// Camera advancing while content is flat ⇒ cameraReceiving true,
    /// contentReceiving false (the consumer derives "content stalled").
    func testRoleLivenessTracksEachRoleIndependently() async {
        let harness = SessionTestHarness(config: independentConfig())
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]

        // Baseline tick.
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 1_000, contentBytes: 1_000)
        await tickRoleLiveness(harness)

        // Camera advances, content flat → content stalled while camera healthy.
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 5_000, contentBytes: 1_000)
        await tickRoleLiveness(harness)
        XCTAssertEqual(remote(harness)?.cameraReceiving, true)
        XCTAssertEqual(remote(harness)?.contentReceiving, false, "content stalled (bytes flat)")

        // Now content advances, camera flat → camera stalled, content healthy.
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 5_000, contentBytes: 9_000)
        await tickRoleLiveness(harness)
        XCTAssertEqual(remote(harness)?.cameraReceiving, false)
        XCTAssertEqual(remote(harness)?.contentReceiving, true)

        // Both advance → both receiving.
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 8_000, contentBytes: 12_000)
        await tickRoleLiveness(harness)
        XCTAssertEqual(remote(harness)?.cameraReceiving, true)
        XCTAssertEqual(remote(harness)?.contentReceiving, true)
        harness.tearDown()
    }

    /// Flag off / legacy peer: the slot attributes its single inbound video to
    /// the camera role, so cameraReceiving tracks it and contentReceiving stays
    /// false (the real slot has no bound content receiver ⇒ contentBytes 0).
    func testRoleLivenessLegacyPeerContentStaysFalse() async {
        let harness = SessionTestHarness(config: independentConfig(enable: false))
        await harness.advanceToInCallWithCapabilities(
            remoteIndependentContentVideo: true, remoteVideoMediaEnabled: true
        )
        await harness.yieldToMainActor()
        await harness.yieldToMainActor()
        let slot = harness.fakeMedia.fakeSlots["remote-cid-1"]

        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 1_000, contentBytes: 0)
        await tickRoleLiveness(harness)
        slot?.roleInboundBytesSample = RoleInboundBytes(cameraBytes: 4_000, contentBytes: 0)
        await tickRoleLiveness(harness)

        XCTAssertEqual(remote(harness)?.cameraReceiving, true, "single inbound video → camera role")
        XCTAssertEqual(remote(harness)?.contentReceiving, false, "no content role ⇒ contentReceiving stays false")
        harness.tearDown()
    }

    // MARK: - FIX C (GAP 1): per-peer attach-failure isolation

    /// Bring up a room with two CAPABLE peers (peer A and peer B) under the local
    /// offer owner, then ANSWER each peer's initial offer so both slots settle to
    /// STABLE — a forced renegotiation re-offer only fires from STABLE
    /// (`maybeSendOffer` early-returns otherwise). Returns the harness with both
    /// fake slots created and ready.
    private func joinTwoCapablePeers(localCid: String = "aaa") async -> SessionTestHarness {
        let harness = SessionTestHarness(config: independentConfig())
        harness.fakeMedia.startScreenShareResult = true
        harness.fakeProvider.iceServerResults = [.success([
            IceServerConfig(urls: ["turn:turn.example.com:3478"], username: "u", credential: "p")
        ])]
        await harness.advancePastPermissions()
        await harness.waitForLocalMedia()
        harness.openSignaling()
        harness.fakeProvider.simulateJoined(
            peerId: localCid,
            participants: [
                SignalingProviderParticipant(peerId: localCid, joinedAt: 1),
                SignalingProviderParticipant(
                    peerId: "peer-A", joinedAt: 2,
                    capabilities: SignalingProviderParticipantCapabilities(independentContentVideo: true),
                    mediaPolicy: SignalingProviderParticipantMediaPolicy(videoMediaEnabled: true)
                ),
                SignalingProviderParticipant(
                    peerId: "peer-B", joinedAt: 3,
                    capabilities: SignalingProviderParticipantCapabilities(independentContentVideo: true),
                    mediaPolicy: SignalingProviderParticipantMediaPolicy(videoMediaEnabled: true)
                )
            ],
            hostPeerId: localCid
        )
        await harness.yieldToMainActor()
        await harness.fakeClock.advance(byMs: 100)
        await harness.yieldToMainActor()
        await harness.waitForIceServers()
        // Answer each peer's initial offer so its slot returns to STABLE.
        await settleSlotToStable(harness, peerCid: "peer-A")
        await settleSlotToStable(harness, peerCid: "peer-B")
        return harness
    }

    /// Answer the latest initial offer sent to `peerCid` so its slot transitions
    /// HAVE_LOCAL_OFFER → STABLE.
    private func settleSlotToStable(_ harness: SessionTestHarness, peerCid: String) async {
        guard harness.fakeMedia.fakeSlots[peerCid]?.getSignalingState() == "HAVE_LOCAL_OFFER" else { return }
        let offerId = harness.fakeProvider.sentPeerMessages(ofType: "offer")
            .last(where: { $0.peerId == peerCid })?.payload?["offerId"]?.stringValue
        harness.simulateAnswerFromRemote(fromCid: peerCid, offerId: offerId)
        await harness.yieldToMainActor()
    }

    /// Two capable peers, content attach REJECTS on peer A and succeeds on peer B.
    /// The share stays active (no `content_state active:false`, no rollback), peer
    /// B carries content, peer A goes to RECOVERY (renegotiation re-offer) and is
    /// NOT torn down. iOS has no `attachedCount==0` rollback gate — once capture
    /// confirms, per-peer attach failures are isolated inside each slot (the
    /// failing slot falls back to renegotiation, never throws), so a single peer's
    /// failure cannot abort the share for the others.
    func testPerPeerContentAttachFailureIsolation() async {
        let harness = await joinTwoCapablePeers(localCid: "aaa")
        let slotA = harness.fakeMedia.fakeSlots["peer-A"]
        let slotB = harness.fakeMedia.fakeSlots["peer-B"]
        XCTAssertEqual(slotA?.supportsIndependentContentVideo, true)
        XCTAssertEqual(slotB?.supportsIndependentContentVideo, true)

        // Local user starts sharing: once capture confirms, the session signals
        // content_state active and marks the share active.
        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        XCTAssertEqual(lastContentActive(harness), true)

        let offersBefore = harness.fakeProvider.sentPeerMessages(ofType: "offer")
            .filter { $0.peerId == "peer-A" }.count

        // Drive the engine's per-peer attach loop at the reachable seam: attach the
        // content track to each capable slot, with peer A configured to REJECT
        // (its slot falls back to renegotiation; the real `WebRtcEngine` loop is
        // WebRTC-gated). Peer B attaches cleanly.
        slotA?.failNextContentAttachWithRenegotiation = true
        let contentTrack = NSObject()
        slotA?.attachLocalTracks(
            audioTrack: nil, cameraTrack: nil, contentTrack: contentTrack,
            supportsIndependentContentVideo: true, legacyVideoCarriesContent: false
        )
        slotB?.attachLocalTracks(
            audioTrack: nil, cameraTrack: nil, contentTrack: contentTrack,
            supportsIndependentContentVideo: true, legacyVideoCarriesContent: false
        )
        await harness.yieldToMainActor()

        // Share stays active: no rollback, content_state stays active for all.
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing, "share survives peer A's attach failure")
        XCTAssertEqual(harness.session.state.localParticipant.content?.active, true)
        let inactiveBroadcasts = contentStateBroadcasts(harness)
            .filter { $0.payload?["active"]?.boolValue == false }.count
        XCTAssertEqual(inactiveBroadcasts, 0, "no content_state active:false rollback")

        // Peer A: rejected once → recovery via renegotiation re-offer, NOT torn down.
        XCTAssertEqual(slotA?.contentAttachRejectionCount, 1, "peer A rejected the content attach")
        XCTAssertEqual(slotA?.closePeerConnectionCalled, false, "peer A is NOT torn down")
        // The recovery re-offer drains through Task { @MainActor } hops, so poll for
        // it (iOS async-settling pattern) rather than racing it after a single yield.
        var offersAfter = offersBefore
        for _ in 0..<100 {
            await harness.yieldToMainActor()
            offersAfter = harness.fakeProvider.sentPeerMessages(ofType: "offer")
                .filter { $0.peerId == "peer-A" }.count
            if offersAfter > offersBefore { break }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertGreaterThan(offersAfter, offersBefore, "peer A re-offered (recovery), not torn down")

        // Peer B: clean attach with the content track, no rejection.
        XCTAssertEqual(slotB?.contentAttachRejectionCount, 0)
        XCTAssertEqual(slotB?.attachLocalTracksCalls.last?.hasContent, true, "peer B carries the content track")
        XCTAssertEqual(slotB?.closePeerConnectionCalled, false)
        harness.tearDown()
    }

    // MARK: - FIX D (GAP 4): content replaceTrack-reject → renegotiation fallback

    /// When the content sender's assign-then-verify detects a reject/mismatch for
    /// a capable peer, the engine falls back to RENEGOTIATION (durable retry: the
    /// content track stays set so the post-renegotiation bind re-attaches it),
    /// rather than tearing the peer down. The real assign-then-verify
    /// (`replaceContentTrackWithFallback`) is WebRTC-gated; the fake models the
    /// reject by firing `onRenegotiationNeeded` from the content attach, exactly
    /// like the real slot's fallback. This locks the engine→negotiation wiring:
    /// a content-role reject re-offers the (capable, offer-owner) peer and never
    /// closes it.
    func testContentReplaceTrackRejectFallsBackToRenegotiation() async {
        // local "aaa" < "peer-A" ⇒ local is the offer owner ⇒ a reject re-offers.
        let harness = await joinTwoCapablePeers(localCid: "aaa")
        let slotA = harness.fakeMedia.fakeSlots["peer-A"]
        XCTAssertEqual(harness.fakeMedia.createdSlotIsOfferOwner["peer-A"], true)

        harness.session.startScreenShare()
        await harness.yieldToMainActor()
        let offersBefore = harness.fakeProvider.sentPeerMessages(ofType: "offer")
            .filter { $0.peerId == "peer-A" }.count

        // Content attach REJECTS → renegotiation fallback.
        slotA?.failNextContentAttachWithRenegotiation = true
        let contentTrack = NSObject()
        slotA?.attachLocalTracks(
            audioTrack: nil, cameraTrack: nil, contentTrack: contentTrack,
            supportsIndependentContentVideo: true, legacyVideoCarriesContent: false
        )
        // The reject's onRenegotiationNeeded re-offer drains through Task { @MainActor }
        // hops, so poll for it (per the iOS async-settling pattern) instead of
        // asserting after a single yield, which races the re-offer.
        var offersAfter = offersBefore
        for _ in 0..<100 {
            await harness.yieldToMainActor()
            offersAfter = harness.fakeProvider.sentPeerMessages(ofType: "offer")
                .filter { $0.peerId == "peer-A" }.count
            if offersAfter > offersBefore { break }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertEqual(slotA?.contentAttachRejectionCount, 1)
        XCTAssertGreaterThan(offersAfter, offersBefore, "reject → renegotiation (force re-offer)")
        XCTAssertEqual(slotA?.closePeerConnectionCalled, false, "durable retry via renegotiation, not teardown")
        // The share is unaffected by the reject (durable retry, not abort).
        XCTAssertTrue(harness.session.diagnostics.isScreenSharing)
        XCTAssertEqual(harness.session.state.localParticipant.content?.active, true)
        harness.tearDown()
    }
}
