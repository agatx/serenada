package app.serenada.callui

import app.serenada.core.SnapshotSource
import app.serenada.core.call.LocalCameraMode
import app.serenada.core.call.ParticipantContent
import app.serenada.core.layout.ContentType

/**
 * Content (screen share) vs camera tile resolution for the Compose call UI.
 *
 * Phase 3b of independent screen share. The core SDK exposes content as an
 * independent stream/track (`attachRemoteContentRenderer(cid)` /
 * `attachLocalContentRenderer`) and carries `content { active, type, revision }`
 * on each participant (local: [app.serenada.core.CallState.localContent]; remote:
 * [app.serenada.core.call.RemoteParticipant.content]), separate from camera
 * (`cameraEnabled`, `localCameraMode`). This module consumes that state and
 * decides, per render, what to present as content and what to present as camera —
 * WITHOUT inferring content from `cameraMode`.
 *
 * This mirrors the vetted web helper
 * `client/packages/react-ui/src/utils/contentRendering.ts`
 * (`resolveContentScene` / `resolveContentSource` / `shouldRenderContentStage`).
 *
 * Backward / flag-off compatibility is the default path and must stay
 * byte-identical to today:
 *   - Android core never produces an independent content track when
 *     `enableIndependentContentVideo=false`, so the UI surfaces
 *     [independentContentEnabled]=false from its config. When false the resolver
 *     marks every owner `mode == LEGACY` and the single video the SDK already
 *     routes (the camera sink) is presented as content, exactly as today. This is
 *     the Android equivalent of web's "no independent content stream ⇒ legacy"
 *     stream-presence detection: with the flag off there is provably no content
 *     track, so mode is LEGACY.
 *   - The legacy "is the local user sharing" signal stays `isScreenSharing`
 *     (diagnostics) / `localCameraMode == SCREEN_SHARE`, and the legacy remote
 *     signal stays the received `content_state` mirrored onto `content.active`.
 *
 * The functions here are pure and free of Compose / Android view types so they can
 * be unit tested on the plain JVM (the module ships no Robolectric in the UI
 * package — these are JVM unit tests, same as the core layout tests).
 */

/** Subset of local participant state this module reads. */
data class ContentLocalParticipant(
    val cid: String,
    /** Legacy single-video share signal (flag off / no precise content). */
    val isScreenSharing: Boolean = false,
    /** Legacy world/composite camera content framings. */
    val cameraMode: LocalCameraMode = LocalCameraMode.SELFIE,
    /** Precise content presentation state. Non-null while content is active. */
    val content: ParticipantContent? = null,
)

/** Subset of a remote participant this module reads. */
data class ContentRemoteParticipant(
    val cid: String,
    /** Precise content presentation state. Non-null while content is active. */
    val content: ParticipantContent? = null,
    /**
     * Whether this peer advertised independent content video at join
     * (`RemoteParticipant.supportsIndependentContentVideo`). Defaults false.
     *
     * INDEPENDENT mode is resolved PER PEER and requires this to be true (in
     * addition to the build flag and `content.active`). A legacy peer that did
     * NOT advertise the capability routes its share through the single-video
     * path: core delivers no separate content track for it, so it must resolve
     * LEGACY and be presented via the peer's normal video sink. Defaulting false
     * keeps the flag-off path (every peer non-independent ⇒ LEGACY) unchanged.
     */
    val supportsIndependentContentVideo: Boolean = false,
)

/** `mode` distinguishes the independent content stream from the legacy single-video-as-content fallback. */
enum class ContentMode { INDEPENDENT, LEGACY }

/**
 * Resolved content presentation for a single owner.
 *
 * `hasMedia` is true when content media is expected to be flowing (and so the
 * content sink should be rendered). When content is active but media has not
 * arrived yet, `loading` is true and the UI shows a connecting tile instead of a
 * stale camera frame (receiver-side hold).
 */
data class ResolvedContent(
    val ownerCid: String,
    val isLocal: Boolean,
    val type: ContentType,
    /** INDEPENDENT when a dedicated content track exists for this owner; LEGACY for the single-video fallback. */
    val mode: ContentMode,
    /** True when content media is present/expected and the content sink should render. */
    val hasMedia: Boolean,
    /** Content is active per state but content media has not arrived for this owner yet. */
    val loading: Boolean,
    /**
     * Local-only: capture is live but no peer is receiving content media yet
     * (independent "start and wait"). The UI must show "sharing, waiting for
     * participants" rather than implying media is flowing.
     */
    val waitingForParticipants: Boolean,
)

/**
 * Inputs to the content resolver for one render.
 *
 * @param local local participant state, or null when not in a call.
 * @param remotes remote participants in stable order.
 * @param independentContentEnabled whether the local build negotiates an
 *   independent content track (`SerenadaConfig.enableIndependentContentVideo`).
 *   When false, no content track can exist, so every owner resolves LEGACY and
 *   the single camera video is presented as content (byte-identical to today).
 * @param localVideoMediaEnabled whether the local user can receive any video
 *   media at all (`videoMediaEnabled`). When false the local user is an
 *   audio-only receiver that never negotiated content receive and MUST suppress
 *   ALL content UI even if room state says someone is sharing.
 * @param remoteContentHasMedia predicate: has the SDK got a content track for
 *   this remote owner yet? Used only in INDEPENDENT mode for the loading hold.
 *   Defaults to "assume present once active" (matching legacy, where the single
 *   video track is the content) when a host cannot supply media liveness.
 * @param localContentHasMedia whether the local independent content track is
 *   live. Used only in INDEPENDENT mode.
 * @param remoteContentOrder order in which remote content became active, most
 *   recent LAST, as observed locally. Picks the default primary among multiple
 *   simultaneous remote sharers (design "Multiple Sharers", local receive order).
 */
data class ResolveContentInput(
    val local: ContentLocalParticipant?,
    val remotes: List<ContentRemoteParticipant>,
    val independentContentEnabled: Boolean,
    val localVideoMediaEnabled: Boolean,
    val remoteContentHasMedia: (String) -> Boolean = { true },
    val localContentHasMedia: () -> Boolean = { true },
    val remoteContentOrder: List<String> = emptyList(),
)

/**
 * Full content resolution for a render: per-owner resolved content plus the
 * chosen primary. Callers render a content tile per sharer (design:
 * per-peer/per-participant content) from [local] + [remotes].
 *
 * [all] is the flat list of every active sharer (remotes then local, mirroring
 * web's `ContentScene.all` ordering) so the stream-keyed stage can emit one
 * content tile per sharer in a stable order.
 */
data class ContentScene(
    val primary: ResolvedContent?,
    val local: ResolvedContent?,
    val remotes: List<ResolvedContent>,
) {
    /** Every active sharer (remotes first, local last) — local content is its own pinnable tile. */
    val all: List<ResolvedContent>
        get() = if (local != null) remotes + local else remotes
}

/**
 * The content-role input to the layout ([app.serenada.core.layout.ContentSource]),
 * or null when no content tile should render. This is the render-logic seam that
 * decides WHEN the content-stage layout fires — pure so it can be unit tested.
 *
 * Gating rules (the two paths differ deliberately, mirroring web's
 * `resolveContentSource`):
 *   - **Multi-party** (`isMultiParty == true`): a content tile renders whenever a
 *     primary content owner is resolved — INDEPENDENT or LEGACY. This is the
 *     existing behavior; legacy multi-party already presents the single video as a
 *     content tile. Unchanged.
 *   - **1:1** (`isMultiParty == false`): a content tile renders ONLY when the
 *     primary content is INDEPENDENT. In the LEGACY 1:1 case the single video is
 *     physically swapped to the screen by the SDK and presented in the normal
 *     one-tile layout, so surfacing a content tile would double-render it. Gating
 *     strictly on INDEPENDENT keeps the legacy 1:1 single-tile experience
 *     byte-identical.
 */
data class ResolvedContentSource(
    val type: ContentType,
    val ownerCid: String,
    val mode: ContentMode,
)

private fun localContentType(local: ContentLocalParticipant): ContentType =
    when {
        local.content != null -> ContentType.fromWire(local.content.type)
        local.cameraMode == LocalCameraMode.WORLD -> ContentType.WORLD_CAMERA
        local.cameraMode == LocalCameraMode.COMPOSITE -> ContentType.COMPOSITE_CAMERA
        else -> ContentType.SCREEN_SHARE
    }

private fun localContentActive(local: ContentLocalParticipant): Boolean =
    // Precise content.active (populated in both builds while sharing) wins; the
    // legacy isScreenSharing / world|composite camera mode are the fallbacks for
    // participants with no `content` state at all.
    local.content?.active == true ||
        local.isScreenSharing ||
        local.cameraMode.isContentMode

private fun remoteContentType(remote: ContentRemoteParticipant): ContentType =
    remote.content?.let { ContentType.fromWire(it.type) } ?: ContentType.SCREEN_SHARE

private fun remoteContentActive(remote: ContentRemoteParticipant): Boolean =
    remote.content?.active == true

/**
 * Whether a content state describes a SCREEN SHARE specifically (vs a
 * world/composite camera framing). The independent content transceiver carries
 * SCREEN SHARE only (see CONTRACT.md "independent transceiver is screenShare-
 * only"): the engine creates/attaches the dedicated content sink only for screen
 * share. A capable peer switching to world/composite camera emits `content_state`
 * with type worldCamera/compositeCamera and NO content track, so it must NOT
 * resolve INDEPENDENT (that would render a blank content sink). Defaults to false
 * for an absent content state. Mirrors iOS's `isScreenShareContent`.
 */
private fun isScreenShareContent(content: ParticipantContent?): Boolean {
    if (content == null) return false
    return ContentType.fromWire(content.type) == ContentType.SCREEN_SHARE
}

/**
 * Resolve content for the local participant.
 *
 * Receiver-side hold and audio-only suppression are honored: when the local user
 * cannot receive video, no content is presented at all.
 */
fun resolveLocalContent(input: ResolveContentInput): ResolvedContent? {
    val local = input.local ?: return null
    // Audio-only receivers never present content UI. Sharing is also blocked by
    // the SDK for the local user in this mode, so there is nothing to show.
    if (!input.localVideoMediaEnabled) return null
    if (!localContentActive(local)) return null

    // INDEPENDENT only when the build flag is on AND precise content state exists
    // AND it is a SCREEN SHARE. The dedicated content track carries screen share
    // only; a world/composite camera framing rides the camera track and must
    // render via the legacy/camera path (routing it through the content sink would
    // blank the tile). With the flag off (default) there is provably no independent
    // content track, so the single camera video IS the content — byte-identical to
    // today.
    val independent = input.independentContentEnabled &&
        local.content?.active == true &&
        isScreenShareContent(local.content)
    if (independent) {
        val hasMedia = input.localContentHasMedia()
        return ResolvedContent(
            ownerCid = local.cid,
            isLocal = true,
            type = localContentType(local),
            mode = ContentMode.INDEPENDENT,
            hasMedia = hasMedia,
            loading = !hasMedia,
            // Capture live but no peer receiving yet: independent start-and-wait.
            waitingForParticipants = input.remotes.isEmpty(),
        )
    }

    // Legacy / flag-off path: the single video IS the content. Byte-identical to
    // today's "screen replaces camera in the single sender".
    return ResolvedContent(
        ownerCid = local.cid,
        isLocal = true,
        type = localContentType(local),
        mode = ContentMode.LEGACY,
        hasMedia = true,
        loading = false,
        waitingForParticipants = false,
    )
}

/** Resolve content for every remote participant that is presenting. */
fun resolveRemoteContents(input: ResolveContentInput): List<ResolvedContent> {
    // Audio-only receivers never negotiated content receive: suppress all.
    if (!input.localVideoMediaEnabled) return emptyList()

    val resolved = mutableListOf<ResolvedContent>()
    for (remote in input.remotes) {
        if (!remoteContentActive(remote)) continue

        // INDEPENDENT is resolved PER PEER: the local build flag is on AND this
        // remote peer advertised independent-content capability AND its content is
        // active AND the content is a SCREEN SHARE. The dedicated content track
        // carries screen share only; a capable peer switching to world/composite
        // CAMERA emits content_state with that type and NO content track, so it must
        // resolve LEGACY and render via the camera sink (the layout's existing
        // ContentType handling) — routing it through the content sink would blank
        // the tile. A capable peer is INDEPENDENT even before its content track
        // arrives (loading hold below). A NON-capable (legacy) peer routes its share
        // through the single-video path — core delivers NO separate content track
        // for it — so it must resolve LEGACY and render via the camera sink. With
        // the flag off, no peer is independent ⇒ every peer LEGACY (unchanged).
        val independent = input.independentContentEnabled &&
            remote.supportsIndependentContentVideo &&
            remote.content?.active == true &&
            isScreenShareContent(remote.content)
        if (independent) {
            val hasMedia = input.remoteContentHasMedia(remote.cid)
            resolved.add(
                ResolvedContent(
                    ownerCid = remote.cid,
                    isLocal = false,
                    type = remoteContentType(remote),
                    mode = ContentMode.INDEPENDENT,
                    // Receiver-side hold: only ever resolved when content.active is
                    // true (guarded above); when media has not arrived, loading.
                    hasMedia = hasMedia,
                    loading = !hasMedia,
                    waitingForParticipants = false,
                ),
            )
        } else {
            // Legacy / flag-off / non-capable-peer path: the single received video
            // IS the content (rendered via the peer's normal video sink).
            resolved.add(
                ResolvedContent(
                    ownerCid = remote.cid,
                    isLocal = false,
                    type = remoteContentType(remote),
                    mode = ContentMode.LEGACY,
                    hasMedia = true,
                    loading = false,
                    waitingForParticipants = false,
                ),
            )
        }
    }
    return resolved
}

/**
 * Pick the primary content owner among local + multiple simultaneous remote
 * sharers.
 *
 * - INDEPENDENT mode (`independentContentEnabled == true`): design "Multiple
 *   Sharers" — the most-recently-received active REMOTE content is primary; local
 *   content is primary only when no remote content is active. This is an explicitly
 *   local heuristic — there is no server-stamped ordering.
 * - FLAG-OFF / LEGACY mode: preserve the legacy CallScreen order, which chose LOCAL
 *   content FIRST (`hasLocalContent` before `remoteContentCid`), then the remote
 *   content. The most-recently-active remote-first heuristic is an independent-mode
 *   feature and must NOT change the byte-identical legacy layout in multi-party
 *   calls where local is sharing and a remote `content_state` is also active.
 */
fun pickPrimaryContent(
    local: ResolvedContent?,
    remotes: List<ResolvedContent>,
    remoteContentOrder: List<String>,
    independentContentEnabled: Boolean,
): ResolvedContent? {
    // Legacy / flag-off: local content wins first (byte-identical to the old
    // CallScreen `if (hasLocalContent) { ... } else if (remoteContentCid) { ... }`).
    if (!independentContentEnabled) {
        return local ?: remotes.lastOrNull()
    }
    // A real screen share (independent content — the only kind with its own content
    // tile) wins the spotlight over a camera-framing legacy content_state
    // (worldCamera/composite, no content tile), which would otherwise steal the
    // spotlight and leave it falling back to a camera. Prefer the most-recent remote
    // independent share, then the local independent share.
    val independentRemotes = remotes.filter { it.mode == ContentMode.INDEPENDENT }
    if (independentRemotes.isNotEmpty()) {
        for (i in remoteContentOrder.indices.reversed()) {
            val match = independentRemotes.firstOrNull { it.ownerCid == remoteContentOrder[i] }
            if (match != null) return match
        }
        return independentRemotes.last()
    }
    if (local != null && local.mode == ContentMode.INDEPENDENT) {
        return local
    }
    if (remotes.isNotEmpty()) {
        // Most-recently-active is LAST in order. Walk back to front.
        for (i in remoteContentOrder.indices.reversed()) {
            val match = remotes.firstOrNull { it.ownerCid == remoteContentOrder[i] }
            if (match != null) return match
        }
        // No order info: last in the remotes list (stable input order).
        return remotes.last()
    }
    return local
}

fun resolveContentScene(input: ResolveContentInput): ContentScene {
    val local = resolveLocalContent(input)
    val remotes = resolveRemoteContents(input)
    val primary = pickPrimaryContent(
        local = local,
        remotes = remotes,
        remoteContentOrder = input.remoteContentOrder,
        independentContentEnabled = input.independentContentEnabled,
    )
    return ContentScene(primary = primary, local = local, remotes = remotes)
}

/**
 * The content-role input to the layout, or null when no content tile should
 * render. See [ResolvedContentSource] for the gating rationale.
 */
fun resolveContentSource(primary: ResolvedContent?, isMultiParty: Boolean): ResolvedContentSource? {
    if (primary == null) return null
    // 1:1 surfaces content only when it is an independent stream; legacy 1:1 stays
    // the single swapped-video tile (byte-identical to today).
    if (!isMultiParty && primary.mode != ContentMode.INDEPENDENT) return null
    return ResolvedContentSource(type = primary.type, ownerCid = primary.ownerCid, mode = primary.mode)
}

/**
 * The call phases during which the content-stage layout may render. Mirrors the
 * subset of [app.serenada.core.call.CallPhase] the call UI treats as an active
 * call; kept local so this helper stays free of Compose/Android types and is
 * JVM-unit-testable. Every other [app.serenada.core.call.CallPhase] maps to
 * [Other] (never renders the content stage).
 */
enum class ContentStagePhase { InCall, Waiting, Other }

/**
 * Decide WHEN the content-stage render branch fires, gated on the call phase.
 * Pure so it can be unit tested without Compose. Mirrors web's
 * `shouldRenderContentStage`.
 *
 * @param phase mapped call phase ([ContentStagePhase]).
 * @param isMultiParty more than one remote participant present.
 * @param hasContentStageLayout a content-stage layout has resolved for this
 *   render (a pin or an INDEPENDENT content source). In 1:1 this is only ever
 *   true for INDEPENDENT content; legacy/flag-off 1:1 keeps the single
 *   swapped-video tile and never resolves a content-stage layout, so this stays
 *   false there (byte-identical to today).
 *
 * Rules:
 *   - [ContentStagePhase.InCall]: renders when multi-party OR a content-stage
 *     layout is present (1:1 independent content).
 *   - [ContentStagePhase.Waiting]: renders ONLY when a content-stage layout is
 *     present (1:1 local independent share started before any remote joined).
 *   - any other phase: never renders the content stage.
 */
fun shouldRenderContentStage(
    phase: ContentStagePhase,
    isMultiParty: Boolean,
    hasContentStageLayout: Boolean,
): Boolean =
    when (phase) {
        ContentStagePhase.InCall -> isMultiParty || hasContentStageLayout
        ContentStagePhase.Waiting -> hasContentStageLayout
        ContentStagePhase.Other -> false
    }

/**
 * The Frontline call UI's INDEPENDENT-content decision for one render.
 *
 * The Frontline screen keeps a self-contained LEGACY content model (an
 * `activeContentOwnerId` inferred from `isScreenSharing` / world|composite camera
 * mode / `remoteContentCid`, rendered via the owner's camera renderer as a single
 * swapped video). That legacy path stays byte-identical to today and is NOT
 * derived from this helper.
 *
 * This helper layers the INDEPENDENT (dedicated content track) path on top,
 * gated strictly on the shared resolver's [ContentMode.INDEPENDENT], which is only
 * reachable with the build flag on AND a real screen-share content track. When the
 * resolved primary content is INDEPENDENT, the content spotlight renders the
 * dedicated content track (not the owner camera), and the owner's CAMERA stays as
 * its own participant tile (simultaneous camera + content) — exactly the standard
 * `CallScreen` behavior, reusing the same per-owner resolver outputs.
 *
 * Returns null whenever the new path must NOT engage:
 *   - the primary content is null or LEGACY (flag off / non-capable owner /
 *     world|composite camera-as-content) ⇒ the Frontline legacy path renders
 *     unchanged;
 *   - audio-only suppression already zeroed the scene (resolver returns no
 *     primary).
 */
data class FrontlineIndependentContent(
    val ownerCid: String,
    val isLocal: Boolean,
    val type: ContentType,
    /** True while the content track has not arrived yet (receiver-side hold). */
    val loading: Boolean,
    /** Local-only: capture live but no peer receiving content media yet. */
    val waitingForParticipants: Boolean,
)

/**
 * Resolve the Frontline INDEPENDENT content decision from a [ContentScene]. Pure
 * so it can be unit tested without Compose. Returns null unless the chosen primary
 * content is an INDEPENDENT screen-share track (the only case the new dedicated
 * content path engages); every other case keeps the legacy single-video path.
 */
fun resolveFrontlineIndependentContent(scene: ContentScene): FrontlineIndependentContent? {
    val primary = scene.primary ?: return null
    if (primary.mode != ContentMode.INDEPENDENT) return null
    return FrontlineIndependentContent(
        ownerCid = primary.ownerCid,
        isLocal = primary.isLocal,
        type = primary.type,
        loading = primary.loading,
        waitingForParticipants = primary.waitingForParticipants,
    )
}

/**
 * Frontline remote screen shares always render with fit-style scaling. The legacy
 * camera fit/cover preference still applies to remote camera tiles.
 */
fun frontlineRemoteScreenShareUsesFit(
    isRemoteScreenShare: Boolean,
    remoteVideoFitCover: Boolean,
): Boolean = isRemoteScreenShare || !remoteVideoFitCover

/**
 * A Frontline remote screen-share full-screen request is valid only while the
 * same source is still the current remote screen-share spotlight. If the stream
 * ends or spotlight moves, the UI falls back to the framed view automatically.
 */
fun frontlineRemoteScreenShareFullscreenActive(
    requestedSourceId: String?,
    currentSourceId: String?,
): Boolean = requestedSourceId != null && requestedSourceId == currentSourceId

internal const val FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE = 1f
internal const val FRONTLINE_REMOTE_SCREEN_SHARE_MAX_ZOOM_SCALE = 4f

internal data class FrontlineScreenSharePanOffset(
    val x: Float = 0f,
    val y: Float = 0f,
)

internal fun frontlineRemoteScreenShareZoomScale(
    currentScale: Float,
    change: Float,
): Float {
    if (!currentScale.isFinite() || !change.isFinite() || change <= 0f) {
        return FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE
    }
    return (currentScale * change).coerceIn(
        FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE,
        FRONTLINE_REMOTE_SCREEN_SHARE_MAX_ZOOM_SCALE,
    )
}

internal fun frontlineRemoteScreenShareViewportPanChange(
    reportedPanChange: Float,
    scale: Float,
): Float {
    if (!reportedPanChange.isFinite() || !scale.isFinite()) {
        return 0f
    }
    return reportedPanChange * scale.coerceAtLeast(FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE)
}

internal fun frontlineRemoteScreenSharePanOffset(
    currentOffset: FrontlineScreenSharePanOffset,
    panChangeX: Float,
    panChangeY: Float,
    scale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): FrontlineScreenSharePanOffset {
    if (
        !scale.isFinite() ||
            !viewportWidth.isFinite() ||
            !viewportHeight.isFinite() ||
            scale <= FRONTLINE_REMOTE_SCREEN_SHARE_MIN_ZOOM_SCALE ||
            viewportWidth <= 0f ||
            viewportHeight <= 0f
    ) {
        return FrontlineScreenSharePanOffset()
    }
    val maxX = (viewportWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (viewportHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
    return FrontlineScreenSharePanOffset(
        x = (currentOffset.x + panChangeX).coerceIn(-maxX, maxX),
        y = (currentOffset.y + panChangeY).coerceIn(-maxY, maxY),
    )
}

// ===========================================================================
// Stream-keyed stage tiles (filmstrip + spotlight for active content)
//
// When ANY participant is presenting an INDEPENDENT content stream the call UI
// switches to a single filmstrip+spotlight stage where EVERY stream is its own
// tile, keyed by `{cid, kind}`:
//   - a CAMERA tile for every participant whose camera is on (local + remote),
//   - a CONTENT tile for every participant presenting content (local + remote),
//     including the LOCAL user's own screen (self-preview, pinnable).
// A sharer's camera is therefore a real filmstrip tile alongside their screen,
// NOT a PIP over the content. Multiple simultaneous sharers each get a content
// tile. The tile model is intentionally stream-keyed (not participant-keyed) so
// one participant can occupy two tiles at once.
//
// These helpers are pure / free of Compose / Android view types so they unit-test
// on the plain JVM. Mirrors the vetted web helper
// `client/packages/react-ui/src/utils/contentRendering.ts`.
// ===========================================================================

enum class StageTileKind { CAMERA, CONTENT }

/** Identity of a single stage tile: a participant cid + which stream of theirs. */
data class StageTileKey(
    val cid: String,
    val kind: StageTileKind,
)

/** A derived stage tile. [id] is the stable string key fed to ComputeLayout. */
data class StageTile(
    /** `"<cid>::<kind>"` — opaque to the layout engine, parsed back by the UI. */
    val id: String,
    val cid: String,
    val kind: StageTileKind,
    val isLocal: Boolean,
)

private fun StageTileKind.wire(): String = when (this) {
    StageTileKind.CAMERA -> "camera"
    StageTileKind.CONTENT -> "content"
}

/** Stable string id for a stage tile (the [SceneParticipant.id] fed to layout). */
fun stageTileId(key: StageTileKey): String = "${key.cid}::${key.kind.wire()}"

/**
 * Parse a stage tile id back into its `{cid, kind}` key, or null if malformed.
 * Uses lastIndexOf("::") so cids that themselves contain "::" round-trip.
 */
fun parseStageTileId(id: String): StageTileKey? {
    val sep = id.lastIndexOf("::")
    if (sep <= 0) return null
    val cid = id.substring(0, sep)
    val kind = when (id.substring(sep + 2)) {
        "camera" -> StageTileKind.CAMERA
        "content" -> StageTileKind.CONTENT
        else -> return null
    }
    return StageTileKey(cid = cid, kind = kind)
}

/** Structural, null-safe equality for two tile keys. */
fun stageTileKeyEquals(a: StageTileKey?, b: StageTileKey?): Boolean {
    if (a == null || b == null) return false
    return a.cid == b.cid && a.kind == b.kind
}

data class SnapshotVideoParticipant(
    val cid: String,
    val videoEnabled: Boolean,
)

fun resolveStreamKeyedSnapshotSource(
    pinnedTile: StageTileKey?,
    localCid: String?,
    localVideoEnabled: Boolean,
    remotes: List<SnapshotVideoParticipant>,
): SnapshotSource? {
    if (pinnedTile?.kind != StageTileKind.CAMERA) return null
    if (pinnedTile.cid == localCid) {
        return if (localVideoEnabled) SnapshotSource.Local else null
    }
    return remotes
        .firstOrNull { it.cid == pinnedTile.cid && it.videoEnabled }
        ?.let { SnapshotSource.Remote(it.cid) }
}

/** A participant whose camera/avatar tile the stage needs (local or remote). */
data class StageCameraParticipant(
    val cid: String,
    val isLocal: Boolean,
)

/**
 * Derive the full ordered stream-keyed tile list for the stage.
 *
 * Order (stable, matches the engine's "local last" filmstrip convention):
 *   1. remote camera tiles (input order),
 *   2. local camera tile,
 *   3. content tiles (input order; local content last via [ContentScene.all]).
 *
 * A sharer's camera/avatar tile and content are BOTH present. Content tiles
 * are emitted only for INDEPENDENT content; a legacy single-video sharer shows up
 * as their camera tile (the screen replaced the camera), so they are NOT
 * duplicated as a content tile. Audio-only peers (camera off, no content)
 * contribute no tile. Suppression for audio-only RECEIVERS is already handled
 * upstream (content is empty, camera flags false).
 */
fun deriveStageTiles(
    cameras: List<StageCameraParticipant>,
    content: List<ResolvedContent>,
): List<StageTile> {
    val tiles = mutableListOf<StageTile>()

    // Every participant gets a stage tile while content is active: a live camera
    // tile when their camera is on, otherwise an avatar/placeholder tile (the
    // renderer shows identity + audio activity / mute). Without this a video-off
    // peer vanished from the filmstrip and a lone remaining tile stretched to fill
    // the whole strip.
    for (cam in cameras) {
        if (cam.isLocal) continue
        tiles += StageTile(
            id = stageTileId(StageTileKey(cam.cid, StageTileKind.CAMERA)),
            cid = cam.cid,
            kind = StageTileKind.CAMERA,
            isLocal = false,
        )
    }
    for (cam in cameras) {
        if (!cam.isLocal) continue
        tiles += StageTile(
            id = stageTileId(StageTileKey(cam.cid, StageTileKind.CAMERA)),
            cid = cam.cid,
            kind = StageTileKind.CAMERA,
            isLocal = true,
        )
    }
    for (c in content) {
        // Only INDEPENDENT content is its own tile. A legacy sharer's single video
        // already shows as their CAMERA tile (the screen replaces the camera in
        // legacy mode), so emitting a content tile too would render the same one
        // stream twice in a mixed independent+legacy room.
        if (c.mode != ContentMode.INDEPENDENT) continue
        tiles += StageTile(
            id = stageTileId(StageTileKey(c.ownerCid, StageTileKind.CONTENT)),
            cid = c.ownerCid,
            kind = StageTileKind.CONTENT,
            isLocal = c.isLocal,
        )
    }

    return tiles
}

/**
 * Resolve the spotlight (primary) tile id among the derived tiles.
 *
 * - A [pinnedTile] wins whenever its tile is still present (pin ANY tile, camera
 *   OR content). Click-to-unpin reverts to the default below.
 * - Default spotlight = the MOST-RECENT active share, reusing the same primary
 *   chosen by [pickPrimaryContent] ([contentPrimary]) — surfaced as that owner's
 *   CONTENT tile.
 * - Fallbacks (no pin, no content primary tile present): the first tile, then
 *   null when there are no tiles at all.
 */
fun pickStageSpotlightTileId(
    tiles: List<StageTile>,
    pinnedTile: StageTileKey?,
    contentPrimary: ResolvedContent?,
): String? {
    if (tiles.isEmpty()) return null

    if (pinnedTile != null) {
        val pinnedId = stageTileId(pinnedTile)
        if (tiles.any { it.id == pinnedId }) return pinnedId
    }

    if (contentPrimary != null) {
        val primaryId = stageTileId(StageTileKey(contentPrimary.ownerCid, StageTileKind.CONTENT))
        if (tiles.any { it.id == primaryId }) return primaryId
    }

    return tiles.first().id
}
