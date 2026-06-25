package app.serenada.callui

import app.serenada.core.SnapshotSource
import app.serenada.core.call.ContentTypeWire
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.ParticipantContent
import app.serenada.core.layout.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the content-vs-camera resolution helper
 * ([app.serenada.callui.ContentRendering]). Mirrors the web suite
 * `client/packages/react-ui/test/utils/contentRendering.test.ts`.
 */
class ContentRenderingTest {

    private fun activeContent(revision: Long = 1L, type: String = ContentTypeWire.SCREEN_SHARE) =
        ParticipantContent(active = true, type = type, revision = revision)

    /**
     * A remote peer that advertised independent-content capability and is sharing
     * content. INDEPENDENT mode is now gated per peer, so independent-mode tests
     * must build capable peers (a peer that did not advertise the capability is
     * LEGACY even with the flag on; that is the subject of the per-peer tests).
     */
    private fun capableRemote(
        cid: String = "peer",
        content: ParticipantContent? = activeContent(),
    ) = ContentRemoteParticipant(
        cid = cid,
        content = content,
        supportsIndependentContentVideo = true,
    )

    private fun input(
        local: ContentLocalParticipant? = null,
        remotes: List<ContentRemoteParticipant> = emptyList(),
        independentContentEnabled: Boolean = false,
        localVideoMediaEnabled: Boolean = true,
        remoteContentHasMedia: (String) -> Boolean = { true },
        localContentHasMedia: () -> Boolean = { true },
        remoteContentOrder: List<String> = emptyList(),
    ) = ResolveContentInput(
        local = local,
        remotes = remotes,
        independentContentEnabled = independentContentEnabled,
        localVideoMediaEnabled = localVideoMediaEnabled,
        remoteContentHasMedia = remoteContentHasMedia,
        localContentHasMedia = localContentHasMedia,
        remoteContentOrder = remoteContentOrder,
    )

    // ---- Independent content tile from content state + active --------------------

    @Test
    fun independentRemoteContent_activeWithMedia_resolvesIndependentTile() {
        val remote = capableRemote()
        val scene = resolveContentScene(
            input(remotes = listOf(remote), independentContentEnabled = true),
        )
        assertEquals(1, scene.remotes.size)
        val resolved = scene.remotes.first()
        assertEquals(ContentMode.INDEPENDENT, resolved.mode)
        assertEquals(ContentType.SCREEN_SHARE, resolved.type)
        assertTrue(resolved.hasMedia)
        assertFalse(resolved.loading)
        assertEquals("peer", scene.primary?.ownerCid)
    }

    // ---- Per-peer capability gate (mixed mesh, flag on) -------------------------

    @Test
    fun mixedMesh_flagOn_capablePeerResolvesIndependent_legacyPeerResolvesLegacy() {
        // Flag on, both peers actively sharing content. Only the peer that
        // advertised independent-content capability resolves INDEPENDENT (it has a
        // dedicated content track). The non-capable peer routes its share through
        // the single-video path — core delivers no separate content track for it —
        // so it MUST resolve LEGACY (rendered via its camera sink, no content sink).
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(),
            supportsIndependentContentVideo = true,
        )
        val legacy = ContentRemoteParticipant(
            cid = "legacy",
            content = activeContent(),
            supportsIndependentContentVideo = false,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(capable, legacy), independentContentEnabled = true),
        )

        assertEquals(2, scene.remotes.size)
        val capableResolved = scene.remotes.first { it.ownerCid == "capable" }
        val legacyResolved = scene.remotes.first { it.ownerCid == "legacy" }
        assertEquals(ContentMode.INDEPENDENT, capableResolved.mode)
        assertEquals(ContentMode.LEGACY, legacyResolved.mode)
        // The legacy peer renders via its camera sink (mode LEGACY): no content
        // sink is attached, so media is treated as present (the single video).
        assertTrue(legacyResolved.hasMedia)
        assertFalse(legacyResolved.loading)
    }

    @Test
    fun nonCapablePeer_flagOn_activeContent_resolvesLegacyNotIndependent() {
        // A single non-capable remote peer sharing content with the flag on must
        // NOT be marked INDEPENDENT (the old bug attached a content sink for a
        // track that never exists, blanking the share). It is LEGACY.
        val legacy = ContentRemoteParticipant(
            cid = "legacy",
            content = activeContent(),
            supportsIndependentContentVideo = false,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(legacy), independentContentEnabled = true),
        )
        assertEquals(ContentMode.LEGACY, scene.remotes.first().mode)
        assertTrue(scene.remotes.first().hasMedia)
    }

    @Test
    fun nonCapablePeer_flagOn_loadingPredicateIgnored_legacyHasMedia() {
        // Even if the media-liveness predicate says "no content track yet", a
        // non-capable peer is LEGACY and renders its single video immediately
        // (no INDEPENDENT loading hold, which would blank a legacy share).
        val legacy = ContentRemoteParticipant(
            cid = "legacy",
            content = activeContent(),
            supportsIndependentContentVideo = false,
        )
        val scene = resolveContentScene(
            input(
                remotes = listOf(legacy),
                independentContentEnabled = true,
                remoteContentHasMedia = { false },
            ),
        )
        val resolved = scene.remotes.first()
        assertEquals(ContentMode.LEGACY, resolved.mode)
        assertTrue(resolved.hasMedia)
        assertFalse(resolved.loading)
    }

    @Test
    fun capablePeer_flagOn_activeNoMedia_staysIndependentWithLoading() {
        // A capable peer whose content track has not arrived yet stays
        // INDEPENDENT-with-loading (the per-peer gate does not collapse it to
        // LEGACY just because media is pending).
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(),
            supportsIndependentContentVideo = true,
        )
        val scene = resolveContentScene(
            input(
                remotes = listOf(capable),
                independentContentEnabled = true,
                remoteContentHasMedia = { false },
            ),
        )
        val resolved = scene.remotes.first()
        assertEquals(ContentMode.INDEPENDENT, resolved.mode)
        assertFalse(resolved.hasMedia)
        assertTrue(resolved.loading)
    }

    // ---- Screen-share-type gate on INDEPENDENT (world/composite ⇒ LEGACY) -------

    @Test
    fun capablePeer_worldCameraContent_resolvesLegacyNotBlankIndependent() {
        // A capable peer switched to WORLD camera: it emits content_state with type
        // worldCamera and NO content track (the independent content transceiver
        // carries SCREEN SHARE only). Marking it INDEPENDENT would route to an empty
        // content sink → blank tile. It MUST resolve LEGACY (camera-path rendering)
        // and keep its worldCamera type for the existing ComputeLayout handling.
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(type = ContentTypeWire.WORLD_CAMERA),
            supportsIndependentContentVideo = true,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(capable), independentContentEnabled = true),
        )
        val resolved = scene.remotes.first()
        assertEquals(ContentMode.LEGACY, resolved.mode)
        assertEquals(ContentType.WORLD_CAMERA, resolved.type)
        assertTrue(resolved.hasMedia)
        assertFalse(resolved.loading)
    }

    @Test
    fun capablePeer_compositeCameraContent_resolvesLegacy() {
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(type = ContentTypeWire.COMPOSITE_CAMERA),
            supportsIndependentContentVideo = true,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(capable), independentContentEnabled = true),
        )
        assertEquals(ContentMode.LEGACY, scene.remotes.first().mode)
        assertEquals(ContentType.COMPOSITE_CAMERA, scene.remotes.first().type)
    }

    @Test
    fun capablePeer_screenShareContent_resolvesIndependent() {
        // The screenShare type is the only content the independent transceiver
        // carries, so it (and only it) resolves INDEPENDENT for a capable peer.
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(type = ContentTypeWire.SCREEN_SHARE),
            supportsIndependentContentVideo = true,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(capable), independentContentEnabled = true),
        )
        assertEquals(ContentMode.INDEPENDENT, scene.remotes.first().mode)
    }

    @Test
    fun localWorldCameraContent_flagOn_resolvesLegacyNotIndependent() {
        // Local user sharing world camera as content (precise content state present)
        // with the flag on still resolves LEGACY: only screen share rides the
        // independent content track.
        val local = ContentLocalParticipant(
            cid = "me",
            content = activeContent(type = ContentTypeWire.WORLD_CAMERA),
        )
        val scene = resolveContentScene(input(local = local, independentContentEnabled = true))
        assertEquals(ContentMode.LEGACY, scene.local?.mode)
        assertEquals(ContentType.WORLD_CAMERA, scene.local?.type)
    }

    @Test
    fun localScreenShareContent_flagOn_resolvesIndependent() {
        val local = ContentLocalParticipant(
            cid = "me",
            content = activeContent(type = ContentTypeWire.SCREEN_SHARE),
        )
        val scene = resolveContentScene(input(local = local, independentContentEnabled = true))
        assertEquals(ContentMode.INDEPENDENT, scene.local?.mode)
    }

    @Test
    fun flagOff_capablePeer_stillResolvesLegacy_unchanged() {
        // Defense: with the flag off, even a capability-advertising peer is LEGACY
        // (byte-identical to today; no content track can exist locally).
        val capable = ContentRemoteParticipant(
            cid = "capable",
            content = activeContent(),
            supportsIndependentContentVideo = true,
        )
        val scene = resolveContentScene(
            input(remotes = listOf(capable), independentContentEnabled = false),
        )
        assertEquals(ContentMode.LEGACY, scene.remotes.first().mode)
    }

    @Test
    fun independentLocalContent_active_resolvesIndependentTile() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val scene = resolveContentScene(
            input(local = local, independentContentEnabled = true),
        )
        assertNotNull(scene.local)
        assertEquals(ContentMode.INDEPENDENT, scene.local?.mode)
        assertTrue(scene.local?.hasMedia == true)
    }

    // ---- Camera + content together (content active does not drop the camera) -----

    @Test
    fun cameraAndContentTogether_contentResolvedSeparatelyFromCamera() {
        // Owner has camera on AND is sharing content in independent mode. The
        // content tile resolves independently; the camera is rendered separately
        // by the UI as a PIP. Here we just assert the content tile is independent
        // and primary, and that cameraMode is NOT used to derive content type.
        val local = ContentLocalParticipant(
            cid = "me",
            cameraMode = LocalCameraMode.SELFIE,
            content = activeContent(type = ContentTypeWire.SCREEN_SHARE),
        )
        val scene = resolveContentScene(input(local = local, independentContentEnabled = true))
        assertEquals(ContentMode.INDEPENDENT, scene.local?.mode)
        assertEquals(ContentType.SCREEN_SHARE, scene.local?.type)
    }

    // ---- Flag-off legacy: single video presented as content (byte-identical) -----

    @Test
    fun flagOff_localSharing_resolvesLegacyContent() {
        // Flag off, but content.active is populated (Phase 1 mirrors it while
        // sharing). The single video must be presented as content (LEGACY).
        val local = ContentLocalParticipant(cid = "me", isScreenSharing = true, content = activeContent())
        val scene = resolveContentScene(input(local = local, independentContentEnabled = false))
        assertEquals(ContentMode.LEGACY, scene.local?.mode)
        assertTrue(scene.local?.hasMedia == true)
        assertFalse(scene.local?.loading == true)
    }

    @Test
    fun flagOff_legacyCameraModeOnly_resolvesLegacyContent() {
        // No precise content state at all; world camera framing is content.
        val local = ContentLocalParticipant(cid = "me", cameraMode = LocalCameraMode.WORLD, content = null)
        val scene = resolveContentScene(input(local = local, independentContentEnabled = false))
        assertEquals(ContentMode.LEGACY, scene.local?.mode)
        assertEquals(ContentType.WORLD_CAMERA, scene.local?.type)
    }

    @Test
    fun flagOff_remoteSharing_resolvesLegacyContent() {
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = false))
        assertEquals(ContentMode.LEGACY, scene.remotes.first().mode)
        assertTrue(scene.remotes.first().hasMedia)
    }

    @Test
    fun notSharing_resolvesNoContent() {
        val local = ContentLocalParticipant(cid = "me", isScreenSharing = false, content = null)
        val remote = ContentRemoteParticipant(cid = "peer", content = null)
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertNull(scene.local)
        assertTrue(scene.remotes.isEmpty())
        assertNull(scene.primary)
    }

    // ---- Receiver-side hold: loading when active but media not arrived -----------

    @Test
    fun independentRemoteContent_activeNoMedia_resolvesLoading() {
        val remote = capableRemote()
        val scene = resolveContentScene(
            input(
                remotes = listOf(remote),
                independentContentEnabled = true,
                remoteContentHasMedia = { false },
            ),
        )
        val resolved = scene.remotes.first()
        assertEquals(ContentMode.INDEPENDENT, resolved.mode)
        assertFalse(resolved.hasMedia)
        assertTrue(resolved.loading)
    }

    @Test
    fun remoteContentInactive_isNeverResolved_evenWithMedia() {
        // Receiver-side hold layer 1: an inactive content state is never promoted,
        // even if a (stale) content track exists.
        val remote = ContentRemoteParticipant(
            cid = "peer",
            content = ParticipantContent(active = false, type = ContentTypeWire.SCREEN_SHARE, revision = 2L),
        )
        val scene = resolveContentScene(
            input(remotes = listOf(remote), independentContentEnabled = true, remoteContentHasMedia = { true }),
        )
        assertTrue(scene.remotes.isEmpty())
    }

    // ---- Local "waiting for participants" ---------------------------------------

    @Test
    fun independentLocalContent_noRemotes_waitingForParticipants() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val scene = resolveContentScene(
            input(local = local, remotes = emptyList(), independentContentEnabled = true),
        )
        assertTrue(scene.local?.waitingForParticipants == true)
    }

    @Test
    fun independentLocalContent_withRemote_notWaiting() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = ContentRemoteParticipant(cid = "peer", content = null)
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertFalse(scene.local?.waitingForParticipants == true)
    }

    // ---- Audio-only suppression -------------------------------------------------

    @Test
    fun audioOnlyReceiver_suppressesAllContent() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(
            input(
                local = local,
                remotes = listOf(remote),
                independentContentEnabled = true,
                localVideoMediaEnabled = false,
            ),
        )
        assertNull(scene.local)
        assertTrue(scene.remotes.isEmpty())
        assertNull(scene.primary)
    }

    @Test
    fun audioOnlyReceiver_suppressesLegacyContentToo() {
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(
            input(remotes = listOf(remote), independentContentEnabled = false, localVideoMediaEnabled = false),
        )
        assertTrue(scene.remotes.isEmpty())
    }

    // ---- Multiple simultaneous sharers + primary order --------------------------

    @Test
    fun multipleRemoteSharers_eachGetsContent() {
        val a = capableRemote(cid = "a")
        val b = capableRemote(cid = "b")
        val scene = resolveContentScene(
            input(remotes = listOf(a, b), independentContentEnabled = true),
        )
        assertEquals(2, scene.remotes.size)
    }

    @Test
    fun multipleRemoteSharers_primaryIsMostRecentlyActive() {
        val a = capableRemote(cid = "a")
        val b = capableRemote(cid = "b")
        // "b" became active most recently (last in order).
        val scene = resolveContentScene(
            input(remotes = listOf(a, b), independentContentEnabled = true, remoteContentOrder = listOf("a", "b")),
        )
        assertEquals("b", scene.primary?.ownerCid)
    }

    @Test
    fun remoteSharer_preferredOverLocalSharer_asPrimary() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = capableRemote()
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertEquals("peer", scene.primary?.ownerCid)
    }

    @Test
    fun localSharer_primaryWhenNoRemoteSharing() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = ContentRemoteParticipant(cid = "peer", content = null)
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertEquals("me", scene.primary?.ownerCid)
    }

    // ---- Primary order is local-first in flag-off/legacy mode -------------------

    @Test
    fun flagOff_localSharing_plusRemoteActive_localIsPrimary_byteIdentical() {
        // Legacy multi-party with local sharing AND a remote content active: the
        // pre-Phase-3 CallScreen chose LOCAL content first. The remote-first
        // heuristic is an independent-mode feature and must NOT change the legacy
        // layout when the flag is off.
        val local = ContentLocalParticipant(cid = "me", isScreenSharing = true, content = activeContent())
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = false),
        )
        assertEquals("me", scene.primary?.ownerCid)
        assertEquals(ContentMode.LEGACY, scene.primary?.mode)
    }

    @Test
    fun flagOn_localSharing_plusRemoteActive_recentRemoteIsPrimary() {
        // Same shape with the flag ON: the most-recently-active remote wins
        // (design "Multiple Sharers"). Contrast with the flag-off local-first case.
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = capableRemote(cid = "peer")
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertEquals("peer", scene.primary?.ownerCid)
    }

    @Test
    fun flagOff_localNotSharing_remoteActive_remoteIsPrimary() {
        // Flag off, only the remote is sharing: local-first falls through to the
        // remote (local content is null), so the remote is primary.
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(
            input(remotes = listOf(remote), independentContentEnabled = false),
        )
        assertEquals("peer", scene.primary?.ownerCid)
    }

    // ---- resolveContentSource gating (1:1 independent renders; legacy 1:1 null) --

    @Test
    fun resolveContentSource_nullWhenNoPrimary() {
        assertNull(resolveContentSource(null, isMultiParty = false))
        assertNull(resolveContentSource(null, isMultiParty = true))
    }

    @Test
    fun resolveContentSource_oneToOne_independentRemote_surfaces() {
        val remote = capableRemote()
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = true))
        val src = resolveContentSource(scene.primary, isMultiParty = false)
        assertNotNull(src)
        assertEquals("peer", src?.ownerCid)
        assertEquals(ContentMode.INDEPENDENT, src?.mode)
    }

    @Test
    fun resolveContentSource_oneToOne_independentLocal_surfaces() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val scene = resolveContentScene(input(local = local, independentContentEnabled = true))
        val src = resolveContentSource(scene.primary, isMultiParty = false)
        assertNotNull(src)
        assertEquals("me", src?.ownerCid)
    }

    @Test
    fun resolveContentSource_oneToOne_legacy_returnsNull_byteIdentical() {
        // Legacy 1:1 content: the single video is swapped to the screen by the SDK
        // and presented in the normal one-tile layout. Surfacing a content tile
        // would double-render, so the source must be null in 1:1 legacy.
        val remote = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = false))
        assertNull(resolveContentSource(scene.primary, isMultiParty = false))
    }

    @Test
    fun resolveContentSource_multiParty_independentAndLegacyBothSurface() {
        val remoteIndependent = capableRemote()
        val sceneIndependent =
            resolveContentScene(input(remotes = listOf(remoteIndependent), independentContentEnabled = true))
        assertNotNull(resolveContentSource(sceneIndependent.primary, isMultiParty = true))

        val remoteLegacy = ContentRemoteParticipant(cid = "peer", content = activeContent())
        val sceneLegacy =
            resolveContentScene(input(remotes = listOf(remoteLegacy), independentContentEnabled = false))
        assertNotNull(resolveContentSource(sceneLegacy.primary, isMultiParty = true))
    }

    @Test
    fun resolveContentSource_carriesContentType() {
        val remote = capableRemote(content = activeContent(type = ContentTypeWire.WORLD_CAMERA))
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = true))
        val src = resolveContentSource(scene.primary, isMultiParty = true)
        assertEquals(ContentType.WORLD_CAMERA, src?.type)
    }

    // ── shouldRenderContentStage (phase gating) ─────────────────────────────
    // Mirrors web's contentRendering.test.ts "shouldRenderContentStage" suite.

    @Test
    fun shouldRenderContentStage_inCall_multiParty_renders() {
        assertTrue(
            shouldRenderContentStage(
                phase = ContentStagePhase.InCall,
                isMultiParty = true,
                hasContentStageLayout = false,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_inCall_oneToOne_withLayout_renders() {
        assertTrue(
            shouldRenderContentStage(
                phase = ContentStagePhase.InCall,
                isMultiParty = false,
                hasContentStageLayout = true,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_inCall_oneToOne_noLayout_doesNotRender() {
        assertFalse(
            shouldRenderContentStage(
                phase = ContentStagePhase.InCall,
                isMultiParty = false,
                hasContentStageLayout = false,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_waiting_oneToOne_withLayout_renders() {
        // Local user started an independent screen share before anyone joined:
        // a content-stage layout has resolved while phase is still Waiting. The
        // content stage + "sharing, waiting for participants" badge must surface.
        assertTrue(
            shouldRenderContentStage(
                phase = ContentStagePhase.Waiting,
                isMultiParty = false,
                hasContentStageLayout = true,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_waiting_notSharing_doesNotRender() {
        assertFalse(
            shouldRenderContentStage(
                phase = ContentStagePhase.Waiting,
                isMultiParty = false,
                hasContentStageLayout = false,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_waiting_multiPartyAlone_doesNotForceStage() {
        // In Waiting the gate keys on the resolved content-stage layout, not on
        // isMultiParty (which is false with no remotes).
        assertFalse(
            shouldRenderContentStage(
                phase = ContentStagePhase.Waiting,
                isMultiParty = true,
                hasContentStageLayout = false,
            )
        )
    }

    @Test
    fun shouldRenderContentStage_otherPhases_neverRender() {
        assertFalse(
            shouldRenderContentStage(
                phase = ContentStagePhase.Other,
                isMultiParty = true,
                hasContentStageLayout = true,
            )
        )
    }

    // ── resolveFrontlineIndependentContent (Frontline content decision) ─────────
    // The Frontline screen keeps a self-contained LEGACY content path; this helper
    // engages ONLY for an INDEPENDENT screen-share primary (flag on + real track).

    @Test
    fun frontlineIndependent_remoteScreenShare_flagOn_engages() {
        val remote = capableRemote()
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = true))
        val decision = resolveFrontlineIndependentContent(scene)
        assertNotNull(decision)
        assertEquals("peer", decision?.ownerCid)
        assertFalse(decision?.isLocal == true)
        assertEquals(ContentType.SCREEN_SHARE, decision?.type)
        assertFalse(decision?.loading == true)
        assertFalse(decision?.waitingForParticipants == true)
    }

    @Test
    fun frontlineIndependent_localScreenShare_flagOn_engages() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val scene = resolveContentScene(input(local = local, independentContentEnabled = true))
        val decision = resolveFrontlineIndependentContent(scene)
        assertNotNull(decision)
        assertEquals("me", decision?.ownerCid)
        assertTrue(decision?.isLocal == true)
        // Sharing as the first/only participant ⇒ waiting for participants.
        assertTrue(decision?.waitingForParticipants == true)
    }

    @Test
    fun frontlineIndependent_remoteActiveNoMedia_engagesLoading() {
        val remote = capableRemote()
        val scene = resolveContentScene(
            input(
                remotes = listOf(remote),
                independentContentEnabled = true,
                remoteContentHasMedia = { false },
            ),
        )
        val decision = resolveFrontlineIndependentContent(scene)
        assertNotNull(decision)
        assertTrue(decision?.loading == true)
    }

    @Test
    fun frontlineIndependent_flagOff_doesNotEngage_byteIdentical() {
        // Flag off: even an actively-sharing capable peer resolves LEGACY, so the
        // Frontline legacy single-video path must stay in control (null decision).
        val remote = capableRemote()
        val scene = resolveContentScene(input(remotes = listOf(remote), independentContentEnabled = false))
        assertNull(resolveFrontlineIndependentContent(scene))
    }

    @Test
    fun frontlineIndependent_nonCapablePeer_flagOn_doesNotEngage() {
        // Non-capable peer routes its share through the single-video path (LEGACY),
        // so the dedicated content path must not engage.
        val legacy = ContentRemoteParticipant(
            cid = "legacy",
            content = activeContent(),
            supportsIndependentContentVideo = false,
        )
        val scene = resolveContentScene(input(remotes = listOf(legacy), independentContentEnabled = true))
        assertNull(resolveFrontlineIndependentContent(scene))
    }

    @Test
    fun frontlineIndependent_worldCameraContent_flagOn_doesNotEngage() {
        // World/composite camera-as-content rides the camera track ⇒ LEGACY ⇒ the
        // legacy path renders it; the dedicated content path must not engage.
        val capable = capableRemote(content = activeContent(type = ContentTypeWire.WORLD_CAMERA))
        val scene = resolveContentScene(input(remotes = listOf(capable), independentContentEnabled = true))
        assertNull(resolveFrontlineIndependentContent(scene))
    }

    @Test
    fun frontlineIndependent_notSharing_doesNotEngage() {
        val scene = resolveContentScene(input(independentContentEnabled = true))
        assertNull(resolveFrontlineIndependentContent(scene))
    }

    @Test
    fun frontlineIndependent_audioOnlyReceiver_doesNotEngage() {
        val remote = capableRemote()
        val scene = resolveContentScene(
            input(remotes = listOf(remote), independentContentEnabled = true, localVideoMediaEnabled = false),
        )
        assertNull(resolveFrontlineIndependentContent(scene))
    }

    @Test
    fun frontlineIndependent_multipleRemoteSharers_primaryIsMostRecent() {
        val a = capableRemote(cid = "a")
        val b = capableRemote(cid = "b")
        val scene = resolveContentScene(
            input(remotes = listOf(a, b), independentContentEnabled = true, remoteContentOrder = listOf("a", "b")),
        )
        val decision = resolveFrontlineIndependentContent(scene)
        assertEquals("b", decision?.ownerCid)
    }

    // =======================================================================
    // Stream-keyed stage tiles. Mirrors the web suite's "stage tile id
    // encoding", "deriveStageTiles", "pickStageSpotlightTileId" and
    // "1:1 + share engages the filmstrip stage" describe blocks.
    // =======================================================================

    private fun resolvedContent(
        ownerCid: String = "r1",
        isLocal: Boolean = false,
        mode: ContentMode = ContentMode.INDEPENDENT,
        loading: Boolean = false,
    ) = ResolvedContent(
        ownerCid = ownerCid,
        isLocal = isLocal,
        type = ContentType.SCREEN_SHARE,
        mode = mode,
        hasMedia = !loading,
        loading = loading,
        waitingForParticipants = false,
    )

    private fun cam(cid: String, isLocal: Boolean) =
        StageCameraParticipant(cid = cid, isLocal = isLocal)

    // ---- pickPrimaryContent ----------------------------------------------------

    @Test
    fun pickPrimaryContent_realShareWinsOverRemoteCameraFramingLegacy() {
        // A remote camera-framing content (LEGACY, no content tile) must not steal
        // the spotlight from the local user's real INDEPENDENT screen share.
        val local = resolvedContent(ownerCid = "me", isLocal = true, mode = ContentMode.INDEPENDENT)
        val remotes = listOf(resolvedContent(ownerCid = "r1", isLocal = false, mode = ContentMode.LEGACY))
        val primary = pickPrimaryContent(local, remotes, listOf("r1"), independentContentEnabled = true)
        assertEquals("me", primary?.ownerCid)
    }

    // ---- stage tile id encoding ------------------------------------------------

    @Test
    fun stageTileId_encodesAndRoundTrips() {
        assertEquals("abc::camera", stageTileId(StageTileKey("abc", StageTileKind.CAMERA)))
        assertEquals("abc::content", stageTileId(StageTileKey("abc", StageTileKind.CONTENT)))
        assertEquals(StageTileKey("abc", StageTileKind.CAMERA), parseStageTileId("abc::camera"))
        assertEquals(StageTileKey("abc", StageTileKind.CONTENT), parseStageTileId("abc::content"))
    }

    @Test
    fun stageTileId_roundTripsCidContainingSeparator() {
        // Server CIDs are opaque; lastIndexOf("::") keeps the kind unambiguous.
        val id = stageTileId(StageTileKey("a::b", StageTileKind.CONTENT))
        assertEquals("a::b::content", id)
        assertEquals(StageTileKey("a::b", StageTileKind.CONTENT), parseStageTileId(id))
    }

    @Test
    fun parseStageTileId_returnsNullForMalformedOrUnknownKind() {
        assertNull(parseStageTileId("nokind"))
        assertNull(parseStageTileId("::camera"))
        assertNull(parseStageTileId("abc::audio"))
    }

    @Test
    fun stageTileKeyEquals_comparesStructurallyNullSafe() {
        assertTrue(stageTileKeyEquals(StageTileKey("a", StageTileKind.CAMERA), StageTileKey("a", StageTileKind.CAMERA)))
        assertFalse(stageTileKeyEquals(StageTileKey("a", StageTileKind.CAMERA), StageTileKey("a", StageTileKind.CONTENT)))
        assertFalse(stageTileKeyEquals(StageTileKey("a", StageTileKind.CAMERA), StageTileKey("b", StageTileKind.CAMERA)))
        assertFalse(stageTileKeyEquals(null, StageTileKey("a", StageTileKind.CAMERA)))
        assertFalse(stageTileKeyEquals(StageTileKey("a", StageTileKind.CAMERA), null))
    }

    @Test
    fun streamKeyedSnapshot_usesPinnedCameraTile() {
        val source = resolveStreamKeyedSnapshotSource(
            pinnedTile = StageTileKey("peer", StageTileKind.CAMERA),
            localCid = "me",
            localVideoEnabled = true,
            remotes = listOf(SnapshotVideoParticipant(cid = "peer", videoEnabled = true)),
        )

        assertEquals(SnapshotSource.Remote("peer"), source)
    }

    @Test
    fun streamKeyedSnapshot_ignoresPinnedContentTile() {
        val source = resolveStreamKeyedSnapshotSource(
            pinnedTile = StageTileKey("peer", StageTileKind.CONTENT),
            localCid = "me",
            localVideoEnabled = true,
            remotes = listOf(SnapshotVideoParticipant(cid = "peer", videoEnabled = true)),
        )

        assertNull(source)
    }

    @Test
    fun streamKeyedSnapshot_hidesVideoOffCameraTile() {
        val source = resolveStreamKeyedSnapshotSource(
            pinnedTile = StageTileKey("peer", StageTileKind.CAMERA),
            localCid = "me",
            localVideoEnabled = true,
            remotes = listOf(SnapshotVideoParticipant(cid = "peer", videoEnabled = false)),
        )

        assertNull(source)
    }

    // ---- deriveStageTiles ------------------------------------------------------

    @Test
    fun deriveStageTiles_oneContentTilePerActiveSharer() {
        val tiles = deriveStageTiles(
            cameras = emptyList(),
            content = listOf(resolvedContent(ownerCid = "r1"), resolvedContent(ownerCid = "r2")),
        )
        assertEquals(listOf("r1", "r2"), tiles.filter { it.kind == StageTileKind.CONTENT }.map { it.cid })
        assertTrue(tiles.all { it.kind == StageTileKind.CONTENT })
    }

    @Test
    fun deriveStageTiles_includesSharersOwnCameraAsRealTile() {
        // The sharer r1 has BOTH a camera tile and a content tile.
        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("me", true)),
            content = listOf(resolvedContent(ownerCid = "r1")),
        )
        val ids = tiles.map { it.id }
        assertTrue(ids.contains("r1::camera"))
        assertTrue(ids.contains("r1::content"))
        assertTrue(ids.contains("me::camera"))
    }

    @Test
    fun deriveStageTiles_showsLocalOwnScreenAsContentTile() {
        val tiles = deriveStageTiles(
            cameras = listOf(cam("me", true)),
            content = listOf(resolvedContent(ownerCid = "me", isLocal = true)),
        )
        val selfScreen = tiles.first { it.id == "me::content" }
        assertTrue(selfScreen.isLocal)
    }

    @Test
    fun deriveStageTiles_ordersRemoteCamerasThenLocalCameraThenContent() {
        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("me", true)),
            content = listOf(resolvedContent(ownerCid = "r1")),
        )
        assertEquals(listOf("r1::camera", "me::camera", "r1::content"), tiles.map { it.id })
    }

    @Test
    fun deriveStageTiles_videoOffPeerKeepsAvatarTile() {
        // Video-off participants keep an avatar/placeholder camera tile (identity +
        // audio status) so the filmstrip never collapses to a single stretched tile.
        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("me", true)),
            content = listOf(resolvedContent(ownerCid = "r1")),
        )
        val ids = tiles.map { it.id }
        assertTrue(ids.contains("r1::camera"))
        assertTrue(ids.contains("r1::content"))
        assertTrue(ids.contains("me::camera"))
    }

    @Test
    fun deriveStageTiles_videoOffPeerGetsAvatarTile() {
        // Every participant shows in the filmstrip; a camera-off peer gets an avatar
        // camera tile rather than being dropped.
        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("r2", false), cam("me", true)),
            content = listOf(resolvedContent(ownerCid = "r1")),
        )
        assertTrue(tiles.any { it.id == "r2::camera" })
    }

    @Test
    fun deriveStageTiles_heldContentTileIsStillATile() {
        val tiles = deriveStageTiles(
            cameras = emptyList(),
            content = listOf(resolvedContent(ownerCid = "r1", loading = true)),
        )
        assertEquals(1, tiles.size)
        assertEquals("r1::content", tiles.first().id)
    }

    @Test
    fun deriveStageTiles_skipsLegacyModeContentNoDuplicateTile() {
        // Mixed room: r1 shares independently, r2 is a legacy peer whose single
        // video IS the content. r2's screen already renders as its camera tile, so
        // it must NOT also get a content tile (that would show one stream twice).
        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("r2", false)),
            content = listOf(
                resolvedContent(ownerCid = "r1", mode = ContentMode.INDEPENDENT),
                resolvedContent(ownerCid = "r2", mode = ContentMode.LEGACY),
            ),
        )
        val ids = tiles.map { it.id }
        assertTrue(ids.contains("r1::content"))
        assertFalse(ids.contains("r2::content"))
        assertTrue(ids.contains("r2::camera"))
    }

    // ---- pickStageSpotlightTileId ----------------------------------------------

    private fun spotlightTiles() = deriveStageTiles(
        cameras = listOf(cam("r1", false), cam("me", true)),
        content = listOf(resolvedContent(ownerCid = "r1"), resolvedContent(ownerCid = "r2")),
    )

    @Test
    fun pickStageSpotlight_defaultIsMostRecentSharePrimary() {
        val primary = resolvedContent(ownerCid = "r2")
        assertEquals("r2::content", pickStageSpotlightTileId(spotlightTiles(), null, primary))
    }

    @Test
    fun pickStageSpotlight_pinOverridesAndCanSelectAnyTile() {
        val primary = resolvedContent(ownerCid = "r2")
        assertEquals(
            "r1::camera",
            pickStageSpotlightTileId(spotlightTiles(), StageTileKey("r1", StageTileKind.CAMERA), primary),
        )
    }

    @Test
    fun pickStageSpotlight_pinCanSelectContentTileOtherThanDefault() {
        val primary = resolvedContent(ownerCid = "r2")
        assertEquals(
            "r1::content",
            pickStageSpotlightTileId(spotlightTiles(), StageTileKey("r1", StageTileKind.CONTENT), primary),
        )
    }

    @Test
    fun pickStageSpotlight_unpinRevertsToDefault() {
        val primary = resolvedContent(ownerCid = "r2")
        assertEquals("r2::content", pickStageSpotlightTileId(spotlightTiles(), null, primary))
    }

    @Test
    fun pickStageSpotlight_stalePinFallsBackToDefault() {
        val primary = resolvedContent(ownerCid = "r2")
        assertEquals(
            "r2::content",
            pickStageSpotlightTileId(spotlightTiles(), StageTileKey("gone", StageTileKind.CAMERA), primary),
        )
    }

    @Test
    fun pickStageSpotlight_fallsBackToFirstTileWhenNoContentPrimary() {
        assertEquals("r1::camera", pickStageSpotlightTileId(spotlightTiles(), null, null))
    }

    @Test
    fun pickStageSpotlight_returnsNullWhenNoTiles() {
        assertNull(pickStageSpotlightTileId(emptyList(), StageTileKey("r1", StageTileKind.CAMERA), null))
    }

    // ---- 1:1 + share engages the stream-keyed stage ----------------------------

    @Test
    fun oneToOne_independentRemoteShare_producesStageTilesCameraPlusContent() {
        // One remote (r1) sharing an independent screen; both cameras on.
        val remote = capableRemote(cid = "r1")
        val scene = resolveContentScene(
            input(
                local = ContentLocalParticipant(cid = "me"),
                remotes = listOf(remote),
                independentContentEnabled = true,
            ),
        )
        assertTrue(scene.all.any { it.mode == ContentMode.INDEPENDENT })

        val tiles = deriveStageTiles(
            cameras = listOf(cam("r1", false), cam("me", true)),
            content = scene.all,
        )
        // 1:1 (2 participants) + a share → 3 tiles: both cameras + r1's screen.
        assertEquals(listOf("r1::camera", "me::camera", "r1::content"), tiles.map { it.id })
        assertEquals("r1::content", pickStageSpotlightTileId(tiles, null, scene.primary))
    }

    @Test
    fun oneToOne_legacyShare_resolvesNoIndependentContent_staysOffStreamKeyedStage() {
        val scene = resolveContentScene(
            input(
                local = ContentLocalParticipant(cid = "me"),
                remotes = listOf(ContentRemoteParticipant(cid = "r1", content = activeContent())),
                independentContentEnabled = false,
            ),
        )
        assertTrue(scene.all.isNotEmpty())
        assertFalse(scene.all.any { it.mode == ContentMode.INDEPENDENT })
    }

    @Test
    fun contentScene_all_ordersRemotesThenLocal() {
        val local = ContentLocalParticipant(cid = "me", content = activeContent())
        val remote = capableRemote(cid = "r1")
        val scene = resolveContentScene(
            input(local = local, remotes = listOf(remote), independentContentEnabled = true),
        )
        assertEquals(listOf("r1", "me"), scene.all.map { it.ownerCid })
    }

    @Test
    fun frontlineRemoteScreenShareAlwaysUsesFit() {
        assertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare = true, remoteVideoFitCover = true))
        assertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare = true, remoteVideoFitCover = false))
        assertTrue(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare = false, remoteVideoFitCover = false))
        assertFalse(frontlineRemoteScreenShareUsesFit(isRemoteScreenShare = false, remoteVideoFitCover = true))
    }

    @Test
    fun frontlineRemoteScreenShareFullscreenRequiresCurrentSource() {
        assertTrue(
            frontlineRemoteScreenShareFullscreenActive(
                requestedSourceId = "independent:r1",
                currentSourceId = "independent:r1",
            ),
        )
        assertFalse(
            frontlineRemoteScreenShareFullscreenActive(
                requestedSourceId = "independent:r1",
                currentSourceId = "legacy:r1",
            ),
        )
        assertFalse(
            frontlineRemoteScreenShareFullscreenActive(
                requestedSourceId = "independent:r1",
                currentSourceId = null,
            ),
        )
        assertFalse(
            frontlineRemoteScreenShareFullscreenActive(
                requestedSourceId = null,
                currentSourceId = "independent:r1",
            ),
        )
    }

    @Test
    fun frontlineRemoteScreenShareZoomScaleClamps() {
        assertEquals(2f, frontlineRemoteScreenShareZoomScale(currentScale = 1f, change = 2f))
        assertEquals(4f, frontlineRemoteScreenShareZoomScale(currentScale = 3f, change = 2f))
        assertEquals(1f, frontlineRemoteScreenShareZoomScale(currentScale = 2f, change = 0.1f))
        assertEquals(1f, frontlineRemoteScreenShareZoomScale(currentScale = 2f, change = -1f))
    }

    @Test
    fun frontlineRemoteScreenShareViewportPanChangeScalesLayerLocalDelta() {
        assertEquals(120f, frontlineRemoteScreenShareViewportPanChange(reportedPanChange = 40f, scale = 3f))
        assertEquals(-80f, frontlineRemoteScreenShareViewportPanChange(reportedPanChange = -40f, scale = 2f))
        assertEquals(40f, frontlineRemoteScreenShareViewportPanChange(reportedPanChange = 40f, scale = 1f))
    }

    @Test
    fun frontlineRemoteScreenSharePanOffsetClampsToScaledViewport() {
        val clamped = frontlineRemoteScreenSharePanOffset(
            currentOffset = FrontlineScreenSharePanOffset(),
            panChangeX = 500f,
            panChangeY = -500f,
            scale = 2f,
            viewportWidth = 320f,
            viewportHeight = 240f,
        )
        assertEquals(160f, clamped.x)
        assertEquals(-120f, clamped.y)

        val reset = frontlineRemoteScreenSharePanOffset(
            currentOffset = FrontlineScreenSharePanOffset(x = 80f, y = 40f),
            panChangeX = 10f,
            panChangeY = 10f,
            scale = 1f,
            viewportWidth = 320f,
            viewportHeight = 240f,
        )
        assertEquals(FrontlineScreenSharePanOffset(), reset)
    }
}
