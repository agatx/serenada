@testable import SerenadaCallUI
import SerenadaCore
import XCTest

/// Pure unit tests for the Frontline content-decision seam
/// (``resolveFrontlineIndependentContent``). Mirrors the Android suite's
/// `resolveFrontlineIndependentContent` block in
/// `client-android/serenada-call-ui/.../ContentRenderingTest.kt`.
///
/// The Frontline screen keeps a self-contained LEGACY content path (an
/// `activeContentOwnerId` inferred from camera mode / screen share / remote
/// content, rendered as a single swapped video). This helper layers the
/// INDEPENDENT (dedicated content track) path on top and engages ONLY for an
/// INDEPENDENT screen-share primary (flag on + a real content track). Every other
/// case returns nil and keeps the legacy path byte-identical.
final class FrontlineContentRenderingTests: XCTestCase {

    private func activeContent(revision: Int64 = 1, type: String = ContentTypeWire.screenShare) -> ParticipantContent {
        ParticipantContent(active: true, type: type, revision: revision)
    }

    /// A remote peer that advertised independent-content capability and is sharing.
    private func capableRemote(
        cid: String = "peer",
        content: ParticipantContent? = nil
    ) -> ContentRemoteParticipant {
        ContentRemoteParticipant(
            cid: cid,
            content: content ?? activeContent(),
            supportsIndependentContentVideo: true
        )
    }

    private func input(
        local: ContentLocalParticipant? = nil,
        remotes: [ContentRemoteParticipant] = [],
        independentContentEnabled: Bool = false,
        localVideoMediaEnabled: Bool = true,
        remoteContentHasMedia: @escaping (String) -> Bool = { _ in true },
        localContentHasMedia: @escaping () -> Bool = { true },
        remoteContentOrder: [String] = []
    ) -> ResolveContentInput {
        ResolveContentInput(
            local: local,
            remotes: remotes,
            independentContentEnabled: independentContentEnabled,
            localVideoMediaEnabled: localVideoMediaEnabled,
            remoteContentHasMedia: remoteContentHasMedia,
            localContentHasMedia: localContentHasMedia,
            remoteContentOrder: remoteContentOrder
        )
    }

    // MARK: - Engages for an INDEPENDENT screen-share primary (flag on + track)

    func testFrontlineIndependentRemoteScreenShareFlagOnEngages() {
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        let decision = resolveFrontlineIndependentContent(scene)
        XCTAssertNotNil(decision)
        XCTAssertEqual(decision?.ownerCid, "peer")
        XCTAssertEqual(decision?.isLocal, false)
        XCTAssertEqual(decision?.type, .screenShare)
        XCTAssertEqual(decision?.loading, false)
        XCTAssertEqual(decision?.waitingForParticipants, false)
    }

    func testFrontlineIndependentLocalScreenShareFlagOnEngages() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        let decision = resolveFrontlineIndependentContent(scene)
        XCTAssertNotNil(decision)
        XCTAssertEqual(decision?.ownerCid, "me")
        XCTAssertEqual(decision?.isLocal, true)
        // Sharing as the first/only participant ⇒ waiting for participants.
        XCTAssertEqual(decision?.waitingForParticipants, true)
    }

    func testFrontlineIndependentRemoteActiveNoMediaEngagesLoading() {
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true, remoteContentHasMedia: { _ in false }))
        let decision = resolveFrontlineIndependentContent(scene)
        XCTAssertNotNil(decision)
        XCTAssertEqual(decision?.loading, true)
    }

    // MARK: - Does NOT engage (legacy path stays in control, byte-identical)

    func testFrontlineIndependentFlagOffDoesNotEngageByteIdentical() {
        // Flag off: even an actively-sharing capable peer resolves LEGACY, so the
        // Frontline legacy single-video path must stay in control (nil decision).
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: false))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    func testFrontlineIndependentNonCapablePeerFlagOnDoesNotEngage() {
        // Non-capable peer routes its share through the single-video path (LEGACY),
        // so the dedicated content path must not engage.
        let legacy = ContentRemoteParticipant(cid: "legacy", content: activeContent(), supportsIndependentContentVideo: false)
        let scene = resolveContentScene(input(remotes: [legacy], independentContentEnabled: true))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    func testFrontlineIndependentWorldCameraContentFlagOnDoesNotEngage() {
        // World/composite camera-as-content rides the camera track ⇒ LEGACY ⇒ the
        // legacy path renders it; the dedicated content path must not engage.
        let capable = capableRemote(content: activeContent(type: ContentTypeWire.worldCamera))
        let scene = resolveContentScene(input(remotes: [capable], independentContentEnabled: true))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    func testFrontlineIndependentLocalWorldCameraFlagOnDoesNotEngage() {
        // Local world-camera framing (a legacy camera-as-content case) must keep the
        // legacy Frontline path even with the flag on.
        let local = ContentLocalParticipant(cid: "me", content: activeContent(type: ContentTypeWire.worldCamera))
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    func testFrontlineIndependentNotSharingDoesNotEngage() {
        let scene = resolveContentScene(input(independentContentEnabled: true))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    func testFrontlineIndependentAudioOnlyReceiverDoesNotEngage() {
        // Audio-only receiver suppresses ALL content ⇒ no primary ⇒ no decision.
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true, localVideoMediaEnabled: false))
        XCTAssertNil(resolveFrontlineIndependentContent(scene))
    }

    // MARK: - Multiple sharers: primary follows local receive order

    func testFrontlineIndependentMultipleRemoteSharersPrimaryIsMostRecent() {
        let a = capableRemote(cid: "a")
        let b = capableRemote(cid: "b")
        // "b" became active most recently (last in order) ⇒ primary.
        let scene = resolveContentScene(input(remotes: [a, b], independentContentEnabled: true, remoteContentOrder: ["a", "b"]))
        let decision = resolveFrontlineIndependentContent(scene)
        XCTAssertEqual(decision?.ownerCid, "b")
    }

    func testFrontlineIndependentRemoteSharerPreferredOverLocal() {
        // A remote sharer outranks a local sharer for the primary content owner
        // (design "Multiple Sharers"), so the Frontline decision targets the remote.
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = capableRemote(cid: "peer")
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true, remoteContentOrder: ["peer"]))
        let decision = resolveFrontlineIndependentContent(scene)
        XCTAssertEqual(decision?.ownerCid, "peer")
        XCTAssertEqual(decision?.isLocal, false)
    }

    func testFrontlineRemoteScreenShareAlwaysUsesFit() {
        XCTAssertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare: true, remoteVideoFitCover: true))
        XCTAssertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare: true, remoteVideoFitCover: false))
        XCTAssertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare: false, remoteVideoFitCover: false))
        XCTAssertFalse(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare: false, remoteVideoFitCover: true))
    }

    func testFrontlineRemoteScreenShareFullscreenRequiresCurrentSource() {
        XCTAssertTrue(frontlineRemoteScreenShareFullscreenActive(requestedSourceId: "independent:peer", currentSourceId: "independent:peer"))
        XCTAssertFalse(frontlineRemoteScreenShareFullscreenActive(requestedSourceId: "independent:peer", currentSourceId: "legacy:peer"))
        XCTAssertFalse(frontlineRemoteScreenShareFullscreenActive(requestedSourceId: "independent:peer", currentSourceId: nil))
        XCTAssertFalse(frontlineRemoteScreenShareFullscreenActive(requestedSourceId: nil, currentSourceId: "independent:peer"))
    }

    func testFrontlineRemoteScreenShareZoomScaleClamps() {
        XCTAssertEqual(frontlineRemoteScreenShareZoomScale(currentScale: 1, change: 2), 2, accuracy: 0.001)
        XCTAssertEqual(frontlineRemoteScreenShareZoomScale(currentScale: 3, change: 2), 4, accuracy: 0.001)
        XCTAssertEqual(frontlineRemoteScreenShareZoomScale(currentScale: 2, change: 0.1), 1, accuracy: 0.001)
        XCTAssertEqual(frontlineRemoteScreenShareZoomScale(currentScale: 2, change: -1), 1, accuracy: 0.001)
    }

    func testFrontlineRemoteScreenSharePanOffsetClampsToScaledViewport() {
        let clamped = frontlineRemoteScreenSharePanOffset(
            currentX: 0,
            currentY: 0,
            deltaX: 500,
            deltaY: -500,
            scale: 2,
            viewportWidth: 320,
            viewportHeight: 240
        )
        XCTAssertEqual(clamped.x, 160, accuracy: 0.001)
        XCTAssertEqual(clamped.y, -120, accuracy: 0.001)

        let reset = frontlineRemoteScreenSharePanOffset(
            currentX: 80,
            currentY: 40,
            deltaX: 10,
            deltaY: 10,
            scale: 1,
            viewportWidth: 320,
            viewportHeight: 240
        )
        XCTAssertEqual(reset.x, 0, accuracy: 0.001)
        XCTAssertEqual(reset.y, 0, accuracy: 0.001)
    }
}
