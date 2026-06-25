@testable import SerenadaCallUI
import SerenadaCore
import XCTest

/// Pure unit tests for the content-vs-camera resolution helper
/// (``ContentRendering``). Mirrors the Android suite
/// `client-android/serenada-call-ui/.../ContentRenderingTest.kt` and the web
/// suite `client/packages/react-ui/test/utils/contentRendering.test.ts`.
final class ContentRenderingTests: XCTestCase {

    private func activeContent(revision: Int64 = 1, type: String = ContentTypeWire.screenShare) -> ParticipantContent {
        ParticipantContent(active: true, type: type, revision: revision)
    }

    /// A remote peer that advertised independent-content capability and is
    /// sharing content. INDEPENDENT mode is gated per peer, so independent-mode
    /// tests must build capable peers (a peer that did not advertise the
    /// capability is LEGACY even with the flag on; that is the subject of the
    /// per-peer tests).
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

    // MARK: - Independent content tile from content state + active

    func testIndependentRemoteContentActiveWithMediaResolvesIndependentTile() {
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        XCTAssertEqual(scene.remotes.count, 1)
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .independent)
        XCTAssertEqual(resolved?.type, .screenShare)
        XCTAssertEqual(resolved?.hasMedia, true)
        XCTAssertEqual(resolved?.loading, false)
        XCTAssertEqual(scene.primary?.ownerCid, "peer")
    }

    // MARK: - Per-peer capability gate (mixed mesh, flag on)

    func testMixedMeshFlagOnCapablePeerIndependentLegacyPeerLegacy() {
        // Flag on, both peers actively sharing content. Only the peer that
        // advertised independent-content capability resolves INDEPENDENT (it has
        // a dedicated content track). The non-capable peer routes its share
        // through the single-video path — core delivers no separate content
        // track for it — so it MUST resolve LEGACY (rendered via its camera
        // sink, no content sink).
        let capable = ContentRemoteParticipant(cid: "capable", content: activeContent(), supportsIndependentContentVideo: true)
        let legacy = ContentRemoteParticipant(cid: "legacy", content: activeContent(), supportsIndependentContentVideo: false)
        let scene = resolveContentScene(input(remotes: [capable, legacy], independentContentEnabled: true))

        XCTAssertEqual(scene.remotes.count, 2)
        let capableResolved = scene.remotes.first { $0.ownerCid == "capable" }
        let legacyResolved = scene.remotes.first { $0.ownerCid == "legacy" }
        XCTAssertEqual(capableResolved?.mode, .independent)
        XCTAssertEqual(legacyResolved?.mode, .legacy)
        // The legacy peer renders via its camera sink (mode legacy): no content
        // sink is attached, so media is treated as present (the single video).
        XCTAssertEqual(legacyResolved?.hasMedia, true)
        XCTAssertEqual(legacyResolved?.loading, false)
    }

    func testNonCapablePeerFlagOnActiveContentResolvesLegacyNotIndependent() {
        // A single non-capable remote peer sharing content with the flag on must
        // NOT be marked INDEPENDENT (the old bug attached a content sink for a
        // track that never exists, blanking the share). It is LEGACY.
        let legacy = ContentRemoteParticipant(cid: "legacy", content: activeContent(), supportsIndependentContentVideo: false)
        let scene = resolveContentScene(input(remotes: [legacy], independentContentEnabled: true))
        XCTAssertEqual(scene.remotes.first?.mode, .legacy)
        XCTAssertEqual(scene.remotes.first?.hasMedia, true)
    }

    func testNonCapablePeerFlagOnLoadingPredicateIgnoredLegacyHasMedia() {
        // Even if the media-liveness predicate says "no content track yet", a
        // non-capable peer is LEGACY and renders its single video immediately
        // (no INDEPENDENT loading hold, which would blank a legacy share).
        let legacy = ContentRemoteParticipant(cid: "legacy", content: activeContent(), supportsIndependentContentVideo: false)
        let scene = resolveContentScene(input(remotes: [legacy], independentContentEnabled: true, remoteContentHasMedia: { _ in false }))
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .legacy)
        XCTAssertEqual(resolved?.hasMedia, true)
        XCTAssertEqual(resolved?.loading, false)
    }

    func testCapablePeerFlagOnActiveNoMediaStaysIndependentWithLoading() {
        // A capable peer whose content track has not arrived yet stays
        // INDEPENDENT-with-loading (the per-peer gate does not collapse it to
        // LEGACY just because media is pending).
        let capable = ContentRemoteParticipant(cid: "capable", content: activeContent(), supportsIndependentContentVideo: true)
        let scene = resolveContentScene(input(remotes: [capable], independentContentEnabled: true, remoteContentHasMedia: { _ in false }))
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .independent)
        XCTAssertEqual(resolved?.hasMedia, false)
        XCTAssertEqual(resolved?.loading, true)
    }

    func testFlagOffCapablePeerStillResolvesLegacyUnchanged() {
        // Defense: with the flag off, even a capability-advertising peer is
        // LEGACY (byte-identical to today; no content track can exist locally).
        let capable = ContentRemoteParticipant(cid: "capable", content: activeContent(), supportsIndependentContentVideo: true)
        let scene = resolveContentScene(input(remotes: [capable], independentContentEnabled: false))
        XCTAssertEqual(scene.remotes.first?.mode, .legacy)
    }

    // MARK: - BUG 3: independent gated on screenShare content type

    func testCapablePeerWorldCameraContentResolvesLegacyNotIndependent() {
        // A capable peer (flag on) whose content_state type is worldCamera is a
        // CAMERA framing, not a screen share. The iOS engine creates the
        // dedicated content track for SCREEN SHARE only, so a worldCamera content
        // has no content track — it must resolve LEGACY and render via the
        // camera path. Resolving it INDEPENDENT would route it to the (empty)
        // content sink and blank the tile.
        let remote = capableRemote(content: activeContent(type: ContentTypeWire.worldCamera))
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .legacy)
        XCTAssertEqual(resolved?.type, .worldCamera)
        XCTAssertEqual(resolved?.hasMedia, true)
        XCTAssertEqual(resolved?.loading, false)
    }

    func testCapablePeerCompositeCameraContentResolvesLegacyNotIndependent() {
        let remote = capableRemote(content: activeContent(type: ContentTypeWire.compositeCamera))
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .legacy)
        XCTAssertEqual(resolved?.type, .compositeCamera)
    }

    func testCapablePeerScreenShareContentResolvesIndependent() {
        // Counterpart to the world/composite cases: an explicit screenShare
        // content type from a capable peer DOES resolve INDEPENDENT.
        let remote = capableRemote(content: activeContent(type: ContentTypeWire.screenShare))
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        XCTAssertEqual(scene.remotes.first?.mode, .independent)
        XCTAssertEqual(scene.remotes.first?.type, .screenShare)
    }

    func testLocalWorldCameraContentResolvesLegacyNotIndependent() {
        // Same gate on the LOCAL branch: a local world-camera content framing is
        // not a screen share, so it stays LEGACY (camera path) even with the flag
        // on and precise content state present.
        let local = ContentLocalParticipant(
            cid: "me",
            content: activeContent(type: ContentTypeWire.worldCamera)
        )
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        XCTAssertEqual(scene.local?.mode, .legacy)
        XCTAssertEqual(scene.local?.type, .worldCamera)
    }

    func testLocalScreenShareContentResolvesIndependent() {
        let local = ContentLocalParticipant(
            cid: "me",
            content: activeContent(type: ContentTypeWire.screenShare)
        )
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        XCTAssertEqual(scene.local?.mode, .independent)
        XCTAssertEqual(scene.local?.type, .screenShare)
    }

    func testIndependentLocalContentActiveResolvesIndependentTile() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        XCTAssertNotNil(scene.local)
        XCTAssertEqual(scene.local?.mode, .independent)
        XCTAssertEqual(scene.local?.hasMedia, true)
    }

    // MARK: - Camera + content together (content active does not drop the camera)

    func testCameraAndContentTogetherContentResolvedSeparatelyFromCamera() {
        // Owner has camera on AND is sharing content in independent mode. The
        // content tile resolves independently; the camera is rendered separately
        // by the UI as a PIP. Here we assert the content tile is independent and
        // that cameraMode is NOT used to derive the content type (selfie camera
        // + screenShare content ⇒ content type stays screenShare).
        let local = ContentLocalParticipant(
            cid: "me",
            cameraMode: .selfie,
            content: activeContent(type: ContentTypeWire.screenShare)
        )
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        XCTAssertEqual(scene.local?.mode, .independent)
        XCTAssertEqual(scene.local?.type, .screenShare)
    }

    // MARK: - Flag-off legacy: single video presented as content (byte-identical)

    func testFlagOffLocalSharingResolvesLegacyContent() {
        // Flag off, but content.active is populated (Phase 1 mirrors it while
        // sharing). The single video must be presented as content (LEGACY).
        let local = ContentLocalParticipant(cid: "me", isScreenSharing: true, content: activeContent())
        let scene = resolveContentScene(input(local: local, independentContentEnabled: false))
        XCTAssertEqual(scene.local?.mode, .legacy)
        XCTAssertEqual(scene.local?.hasMedia, true)
        XCTAssertEqual(scene.local?.loading, false)
    }

    func testFlagOffLegacyCameraModeOnlyResolvesLegacyContent() {
        // No precise content state at all; world camera framing is content.
        let local = ContentLocalParticipant(cid: "me", cameraMode: .world, content: nil)
        let scene = resolveContentScene(input(local: local, independentContentEnabled: false))
        XCTAssertEqual(scene.local?.mode, .legacy)
        XCTAssertEqual(scene.local?.type, .worldCamera)
    }

    func testFlagOffRemoteSharingResolvesLegacyContent() {
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: false))
        XCTAssertEqual(scene.remotes.first?.mode, .legacy)
        XCTAssertEqual(scene.remotes.first?.hasMedia, true)
    }

    func testNotSharingResolvesNoContent() {
        let local = ContentLocalParticipant(cid: "me", isScreenSharing: false, content: nil)
        let remote = ContentRemoteParticipant(cid: "peer", content: nil)
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true))
        XCTAssertNil(scene.local)
        XCTAssertTrue(scene.remotes.isEmpty)
        XCTAssertNil(scene.primary)
    }

    // MARK: - Receiver-side hold: loading when active but media not arrived

    func testIndependentRemoteContentActiveNoMediaResolvesLoading() {
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true, remoteContentHasMedia: { _ in false }))
        let resolved = scene.remotes.first
        XCTAssertEqual(resolved?.mode, .independent)
        XCTAssertEqual(resolved?.hasMedia, false)
        XCTAssertEqual(resolved?.loading, true)
    }

    func testRemoteContentInactiveIsNeverResolvedEvenWithMedia() {
        // Receiver-side hold layer 1: an inactive content state is never
        // promoted, even if a (stale) content track exists.
        let remote = ContentRemoteParticipant(
            cid: "peer",
            content: ParticipantContent(active: false, type: ContentTypeWire.screenShare, revision: 2),
            supportsIndependentContentVideo: true
        )
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true, remoteContentHasMedia: { _ in true }))
        XCTAssertTrue(scene.remotes.isEmpty)
    }

    // MARK: - Local "waiting for participants"

    func testIndependentLocalContentNoRemotesWaitingForParticipants() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let scene = resolveContentScene(input(local: local, remotes: [], independentContentEnabled: true))
        XCTAssertEqual(scene.local?.waitingForParticipants, true)
    }

    func testIndependentLocalContentWithRemoteNotWaiting() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = ContentRemoteParticipant(cid: "peer", content: nil)
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true))
        XCTAssertEqual(scene.local?.waitingForParticipants, false)
    }

    // MARK: - Audio-only suppression

    func testAudioOnlyReceiverSuppressesAllContent() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent(), supportsIndependentContentVideo: true)
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true, localVideoMediaEnabled: false))
        XCTAssertNil(scene.local)
        XCTAssertTrue(scene.remotes.isEmpty)
        XCTAssertNil(scene.primary)
    }

    func testAudioOnlyReceiverSuppressesLegacyContentToo() {
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: false, localVideoMediaEnabled: false))
        XCTAssertTrue(scene.remotes.isEmpty)
    }

    // MARK: - Multiple simultaneous sharers + primary order

    func testMultipleRemoteSharersEachGetsContent() {
        let a = capableRemote(cid: "a")
        let b = capableRemote(cid: "b")
        let scene = resolveContentScene(input(remotes: [a, b], independentContentEnabled: true))
        XCTAssertEqual(scene.remotes.count, 2)
    }

    func testMultipleRemoteSharersPrimaryIsMostRecentlyActive() {
        let a = capableRemote(cid: "a")
        let b = capableRemote(cid: "b")
        // "b" became active most recently (last in order).
        let scene = resolveContentScene(input(remotes: [a, b], independentContentEnabled: true, remoteContentOrder: ["a", "b"]))
        XCTAssertEqual(scene.primary?.ownerCid, "b")
    }

    func testRemoteSharerPreferredOverLocalSharerAsPrimary() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = capableRemote()
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true))
        XCTAssertEqual(scene.primary?.ownerCid, "peer")
    }

    func testLocalSharerPrimaryWhenNoRemoteSharing() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = ContentRemoteParticipant(cid: "peer", content: nil)
        let scene = resolveContentScene(input(local: local, remotes: [remote], independentContentEnabled: true))
        XCTAssertEqual(scene.primary?.ownerCid, "me")
    }

    // MARK: - BUG 4: flag-off/legacy primary order is LOCAL-first (byte-identical)

    func testFlagOffLocalSharingAndRemoteActiveLocalIsPrimary() {
        // Multi-party LEGACY call: local is sharing AND a remote content_state is
        // also active. The old CallScreen legacy path chose LOCAL content first
        // (`hasLocalContent` before `remoteContentCid`). The flag-off resolver
        // must preserve that local-first order; the most-recently-active
        // remote-first heuristic applies only in independent mode.
        let local = ContentLocalParticipant(cid: "me", isScreenSharing: true, content: activeContent())
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let scene = resolveContentScene(input(
            local: local,
            remotes: [remote],
            independentContentEnabled: false,
            remoteContentOrder: ["peer"]
        ))
        XCTAssertEqual(scene.primary?.ownerCid, "me")
        XCTAssertEqual(scene.primary?.mode, .legacy)
    }

    func testFlagOffRemotePrimaryWhenLocalNotSharing() {
        // Flag off, only the remote is sharing: the remote is primary (local is
        // nil), matching the legacy `else if remoteContentCid` branch.
        let local = ContentLocalParticipant(cid: "me", isScreenSharing: false, content: nil)
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let scene = resolveContentScene(input(
            local: local,
            remotes: [remote],
            independentContentEnabled: false
        ))
        XCTAssertEqual(scene.primary?.ownerCid, "peer")
    }

    func testIndependentModeRemoteStillPreferredOverLocal() {
        // Counterpart: in INDEPENDENT mode the remote-first "Multiple Sharers"
        // heuristic still applies (local sharing + remote active ⇒ remote
        // primary), so the BUG 4 fix did not regress independent behavior.
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let remote = capableRemote(cid: "peer")
        let scene = resolveContentScene(input(
            local: local,
            remotes: [remote],
            independentContentEnabled: true,
            remoteContentOrder: ["peer"]
        ))
        XCTAssertEqual(scene.primary?.ownerCid, "peer")
    }

    // MARK: - resolveContentSource gating (1:1 independent renders; legacy 1:1 null)

    func testResolveContentSourceNullWhenNoPrimary() {
        XCTAssertNil(resolveContentSource(nil, isMultiParty: false))
        XCTAssertNil(resolveContentSource(nil, isMultiParty: true))
    }

    func testResolveContentSourceOneToOneIndependentRemoteSurfaces() {
        let remote = capableRemote()
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        let src = resolveContentSource(scene.primary, isMultiParty: false)
        XCTAssertNotNil(src)
        XCTAssertEqual(src?.ownerCid, "peer")
        XCTAssertEqual(src?.mode, .independent)
    }

    func testResolveContentSourceOneToOneIndependentLocalSurfaces() {
        let local = ContentLocalParticipant(cid: "me", content: activeContent())
        let scene = resolveContentScene(input(local: local, independentContentEnabled: true))
        let src = resolveContentSource(scene.primary, isMultiParty: false)
        XCTAssertNotNil(src)
        XCTAssertEqual(src?.ownerCid, "me")
    }

    func testResolveContentSourceOneToOneLegacyReturnsNullByteIdentical() {
        // Legacy 1:1 content: the single video is swapped to the screen by the
        // SDK and presented in the normal one-tile layout. Surfacing a content
        // tile would double-render, so the source must be null in 1:1 legacy.
        let remote = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: false))
        XCTAssertNil(resolveContentSource(scene.primary, isMultiParty: false))
    }

    func testResolveContentSourceMultiPartyIndependentAndLegacyBothSurface() {
        let remoteIndependent = capableRemote()
        let sceneIndependent = resolveContentScene(input(remotes: [remoteIndependent], independentContentEnabled: true))
        XCTAssertNotNil(resolveContentSource(sceneIndependent.primary, isMultiParty: true))

        let remoteLegacy = ContentRemoteParticipant(cid: "peer", content: activeContent())
        let sceneLegacy = resolveContentScene(input(remotes: [remoteLegacy], independentContentEnabled: false))
        XCTAssertNotNil(resolveContentSource(sceneLegacy.primary, isMultiParty: true))
    }

    func testResolveContentSourceCarriesContentType() {
        let remote = capableRemote(content: activeContent(type: ContentTypeWire.worldCamera))
        let scene = resolveContentScene(input(remotes: [remote], independentContentEnabled: true))
        let src = resolveContentSource(scene.primary, isMultiParty: true)
        XCTAssertEqual(src?.type, .worldCamera)
    }

    // MARK: - shouldRenderContentStage (phase gating)
    // Mirrors web's contentRendering.test.ts "shouldRenderContentStage" suite.

    func testShouldRenderContentStageInCallMultiPartyRenders() {
        XCTAssertTrue(shouldRenderContentStage(phase: .inCall, isMultiParty: true, hasContentStageLayout: false))
    }

    func testShouldRenderContentStageInCallOneToOneWithLayoutRenders() {
        XCTAssertTrue(shouldRenderContentStage(phase: .inCall, isMultiParty: false, hasContentStageLayout: true))
    }

    func testShouldRenderContentStageInCallOneToOneNoLayoutDoesNotRender() {
        XCTAssertFalse(shouldRenderContentStage(phase: .inCall, isMultiParty: false, hasContentStageLayout: false))
    }

    func testShouldRenderContentStageWaitingOneToOneWithLayoutRenders() {
        // Local user started an independent screen share before anyone joined:
        // a content-stage layout has resolved while phase is still Waiting. The
        // content stage + "sharing, waiting for participants" badge must surface.
        XCTAssertTrue(shouldRenderContentStage(phase: .waiting, isMultiParty: false, hasContentStageLayout: true))
    }

    func testShouldRenderContentStageWaitingNotSharingDoesNotRender() {
        XCTAssertFalse(shouldRenderContentStage(phase: .waiting, isMultiParty: false, hasContentStageLayout: false))
    }

    func testShouldRenderContentStageWaitingMultiPartyAloneDoesNotForceStage() {
        // In Waiting the gate keys on the resolved content-stage layout, not on
        // isMultiParty (which is false with no remotes).
        XCTAssertFalse(shouldRenderContentStage(phase: .waiting, isMultiParty: true, hasContentStageLayout: false))
    }

    func testShouldRenderContentStageOtherPhasesNeverRender() {
        XCTAssertFalse(shouldRenderContentStage(phase: .other, isMultiParty: true, hasContentStageLayout: true))
    }

    // MARK: - contentStagePhase mapping

    func testContentStagePhaseMapping() {
        XCTAssertEqual(contentStagePhase(.inCall), .inCall)
        XCTAssertEqual(contentStagePhase(.waiting), .waiting)
        XCTAssertEqual(contentStagePhase(.idle), .other)
        XCTAssertEqual(contentStagePhase(.joining), .other)
        XCTAssertEqual(contentStagePhase(.error), .other)
    }

    // MARK: - Stream-keyed stage tiles (stageTileId / parseStageTileId)
    // Mirrors web's contentRendering.test.ts "stage tiles" suite.

    func testStageTileIdRoundTrip() {
        let key = StageTileKey(cid: "peer-1", kind: .camera)
        XCTAssertEqual(stageTileId(key), "peer-1::camera")
        let parsed = parseStageTileId("peer-1::camera")
        XCTAssertEqual(parsed?.cid, "peer-1")
        XCTAssertEqual(parsed?.kind, .camera)
    }

    func testStageTileIdContentKind() {
        XCTAssertEqual(stageTileId(StageTileKey(cid: "me", kind: .content)), "me::content")
        let parsed = parseStageTileId("me::content")
        XCTAssertEqual(parsed?.kind, .content)
    }

    func testParseStageTileIdUsesLastSeparatorSoCidsRoundTrip() {
        // A cid containing "::" still round-trips because parsing splits on the
        // LAST separator (the kind suffix is unambiguous).
        let key = StageTileKey(cid: "weird::cid", kind: .content)
        let id = stageTileId(key)
        XCTAssertEqual(id, "weird::cid::content")
        let parsed = parseStageTileId(id)
        XCTAssertEqual(parsed?.cid, "weird::cid")
        XCTAssertEqual(parsed?.kind, .content)
    }

    func testParseStageTileIdRejectsMalformed() {
        XCTAssertNil(parseStageTileId("no-separator"))
        XCTAssertNil(parseStageTileId("::camera")) // empty cid
        XCTAssertNil(parseStageTileId("cid::unknown")) // unknown kind
    }

    func testStageTileKeyEquals() {
        let a = StageTileKey(cid: "x", kind: .camera)
        XCTAssertTrue(stageTileKeyEquals(a, StageTileKey(cid: "x", kind: .camera)))
        XCTAssertFalse(stageTileKeyEquals(a, StageTileKey(cid: "x", kind: .content)))
        XCTAssertFalse(stageTileKeyEquals(a, StageTileKey(cid: "y", kind: .camera)))
        XCTAssertFalse(stageTileKeyEquals(a, nil))
        XCTAssertFalse(stageTileKeyEquals(nil, a))
    }

    // MARK: - deriveStageTiles

    private func independentResolved(cid: String, isLocal: Bool, loading: Bool = false) -> ResolvedContent {
        ResolvedContent(
            ownerCid: cid,
            isLocal: isLocal,
            type: .screenShare,
            mode: .independent,
            hasMedia: !loading,
            loading: loading,
            waitingForParticipants: false
        )
    }

    private func legacyResolved(cid: String, isLocal: Bool) -> ResolvedContent {
        ResolvedContent(
            ownerCid: cid,
            isLocal: isLocal,
            type: .screenShare,
            mode: .legacy,
            hasMedia: true,
            loading: false,
            waitingForParticipants: false
        )
    }

    func testDeriveStageTilesRemoteCamerasFirstThenLocalThenContent() {
        // Sharer "me" has both camera + content; remote "peer" camera on.
        let cameras = [
            StageCameraParticipant(cid: "peer", isLocal: false),
            StageCameraParticipant(cid: "me", isLocal: true)
        ]
        let content = [independentResolved(cid: "me", isLocal: true)]
        let tiles = deriveStageTiles(cameras: cameras, content: content)
        XCTAssertEqual(tiles.map(\.id), ["peer::camera", "me::camera", "me::content"])
    }

    func testDeriveStageTilesSharerCameraAndScreenAreTwoEqualTiles() {
        // The whole point of the pivot: a sharer's camera + screen are two peer
        // tiles, not a camera-over-content PIP.
        let cameras = [StageCameraParticipant(cid: "me", isLocal: true)]
        let content = [independentResolved(cid: "me", isLocal: true)]
        let tiles = deriveStageTiles(cameras: cameras, content: content)
        XCTAssertEqual(tiles.count, 2)
        XCTAssertTrue(tiles.contains { $0.cid == "me" && $0.kind == .camera })
        XCTAssertTrue(tiles.contains { $0.cid == "me" && $0.kind == .content })
    }

    func testDeriveStageTilesCameraOffStillEmitsAvatarTilePlusContent() {
        // Video-off participants keep an avatar/placeholder camera tile (identity +
        // audio status) so the filmstrip never collapses to a single stretched tile.
        let cameras = [StageCameraParticipant(cid: "me", isLocal: true)]
        let content = [independentResolved(cid: "me", isLocal: true)]
        let tiles = deriveStageTiles(cameras: cameras, content: content)
        XCTAssertEqual(tiles.map(\.id), ["me::camera", "me::content"])
    }

    func testDeriveStageTilesLegacyContentIsNotADuplicateTile() {
        // A legacy sharer shows as their camera tile (the screen replaced the
        // camera); no separate content tile.
        let cameras = [StageCameraParticipant(cid: "peer", isLocal: false)]
        let content = [legacyResolved(cid: "peer", isLocal: false)]
        let tiles = deriveStageTiles(cameras: cameras, content: content)
        XCTAssertEqual(tiles.map(\.id), ["peer::camera"])
    }

    func testDeriveStageTilesMultipleSharersEachGetContentTile() {
        let cameras = [
            StageCameraParticipant(cid: "a", isLocal: false),
            StageCameraParticipant(cid: "b", isLocal: false),
            StageCameraParticipant(cid: "me", isLocal: true)
        ]
        let content = [independentResolved(cid: "a", isLocal: false), independentResolved(cid: "b", isLocal: false)]
        let tiles = deriveStageTiles(cameras: cameras, content: content)
        // Camera-off participants still get avatar tiles (remotes first, local last),
        // then each sharer's content tile.
        XCTAssertEqual(tiles.map(\.id), ["a::camera", "b::camera", "me::camera", "a::content", "b::content"])
    }

    func testStageContentPutsLocalLast() {
        let scene = ContentScene(
            primary: nil,
            local: independentResolved(cid: "me", isLocal: true),
            remotes: [independentResolved(cid: "a", isLocal: false), independentResolved(cid: "b", isLocal: false)]
        )
        XCTAssertEqual(stageContent(for: scene).map(\.ownerCid), ["a", "b", "me"])
    }

    // MARK: - pickStageSpotlightTileId

    func testPickStageSpotlightDefaultsToContentPrimaryTile() {
        let cameras = [
            StageCameraParticipant(cid: "peer", isLocal: false),
            StageCameraParticipant(cid: "me", isLocal: true)
        ]
        let primary = independentResolved(cid: "peer", isLocal: false)
        let tiles = deriveStageTiles(cameras: cameras, content: [primary])
        let spotlight = pickStageSpotlightTileId(tiles: tiles, pinnedTile: nil, contentPrimary: primary)
        XCTAssertEqual(spotlight, "peer::content")
    }

    func testPickStageSpotlightPinnedCameraTileWins() {
        let cameras = [
            StageCameraParticipant(cid: "peer", isLocal: false),
            StageCameraParticipant(cid: "me", isLocal: true)
        ]
        let primary = independentResolved(cid: "peer", isLocal: false)
        let tiles = deriveStageTiles(cameras: cameras, content: [primary])
        let pinned = StageTileKey(cid: "me", kind: .camera)
        let spotlight = pickStageSpotlightTileId(tiles: tiles, pinnedTile: pinned, contentPrimary: primary)
        XCTAssertEqual(spotlight, "me::camera")
    }

    func testPickStageSpotlightPinnedContentTileWins() {
        let cameras = [StageCameraParticipant(cid: "me", isLocal: true)]
        let localContent = independentResolved(cid: "me", isLocal: true)
        let remoteContent = independentResolved(cid: "peer", isLocal: false)
        let tiles = deriveStageTiles(cameras: cameras, content: [remoteContent, localContent])
        let pinned = StageTileKey(cid: "me", kind: .content)
        let spotlight = pickStageSpotlightTileId(tiles: tiles, pinnedTile: pinned, contentPrimary: remoteContent)
        XCTAssertEqual(spotlight, "me::content")
    }

    func testPickStageSpotlightStalePinFallsBackToContentPrimary() {
        // The pinned tile is no longer present (its owner stopped sharing / camera
        // off) ⇒ revert to the most-recent-share default.
        let cameras = [StageCameraParticipant(cid: "peer", isLocal: false)]
        let primary = independentResolved(cid: "peer", isLocal: false)
        let tiles = deriveStageTiles(cameras: cameras, content: [primary])
        let stalePin = StageTileKey(cid: "gone", kind: .camera)
        let spotlight = pickStageSpotlightTileId(tiles: tiles, pinnedTile: stalePin, contentPrimary: primary)
        XCTAssertEqual(spotlight, "peer::content")
    }

    func testPickStageSpotlightFallsBackToFirstTile() {
        let cameras = [StageCameraParticipant(cid: "peer", isLocal: false)]
        let tiles = deriveStageTiles(cameras: cameras, content: [])
        let spotlight = pickStageSpotlightTileId(tiles: tiles, pinnedTile: nil, contentPrimary: nil)
        XCTAssertEqual(spotlight, "peer::camera")
    }

    func testPickStageSpotlightNilWhenNoTiles() {
        XCTAssertNil(pickStageSpotlightTileId(tiles: [], pinnedTile: nil, contentPrimary: nil))
    }
}
