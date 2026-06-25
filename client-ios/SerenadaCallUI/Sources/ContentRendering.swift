import SerenadaCore

/// Content (screen share) vs camera tile resolution for the SwiftUI call UI.
///
/// Phase 4b of independent screen share. The core SDK exposes content as an
/// independent stream/track (`attachRemoteContentRenderer(_:forParticipant:)` /
/// `attachLocalContentRenderer`) and carries `content { active, type, revision }`
/// on each participant (local: ``LocalParticipant/content``; remote:
/// ``SerenadaRemoteParticipant/content``), separate from camera
/// (`cameraEnabled`, `cameraMode`). This module consumes that state and decides,
/// per render, what to present as content and what to present as camera —
/// WITHOUT inferring content from `cameraMode`.
///
/// This mirrors the vetted web helper
/// `client/packages/react-ui/src/utils/contentRendering.ts` and the Android
/// helper `client-android/serenada-call-ui/.../callui/ContentRendering.kt`
/// (`resolveContentScene` / `resolveContentSource` / `shouldRenderContentStage`).
///
/// Backward / flag-off compatibility is the default path and must stay
/// byte-identical to today:
///   - iOS core never produces an independent content track when
///     `enableIndependentContentVideo=false`, so the UI surfaces
///     ``ResolveContentInput/independentContentEnabled``=false from its config.
///     When false the resolver marks every owner `mode == .legacy` and the
///     single video the SDK already routes (the camera sink) is presented as
///     content, exactly as today. This is the iOS equivalent of web's "no
///     independent content stream ⇒ legacy" stream-presence detection: with the
///     flag off there is provably no content track, so mode is `.legacy`.
///   - The legacy "is the local user sharing" signal stays
///     `cameraMode == .screenShare`, and the legacy remote signal stays the
///     received `content_state` mirrored onto `content.active`.
///
/// The functions here are pure and free of SwiftUI / UIKit view types so they
/// can be unit tested on the host test target alongside the core layout tests.

// MARK: - Inputs

/// Subset of local participant state this module reads.
struct ContentLocalParticipant: Equatable {
    let cid: String
    /// Legacy single-video share signal (flag off / no precise content).
    var isScreenSharing: Bool = false
    /// Legacy world/composite camera content framings, and the legacy
    /// `.screenShare` share signal.
    var cameraMode: LocalCameraMode = .selfie
    /// Precise content presentation state. Non-nil while content is active.
    var content: ParticipantContent?
}

/// Subset of a remote participant this module reads.
struct ContentRemoteParticipant: Equatable {
    let cid: String
    /// Precise content presentation state. Non-nil while content is active.
    var content: ParticipantContent?
    /// Whether this peer advertised independent content video at join
    /// (`SerenadaRemoteParticipant.supportsIndependentContentVideo`). Defaults
    /// false.
    ///
    /// INDEPENDENT mode is resolved PER PEER and requires this to be true (in
    /// addition to the build flag and `content.active`). A legacy peer that did
    /// NOT advertise the capability routes its share through the single-video
    /// path: core delivers no separate content track for it, so it must resolve
    /// `.legacy` and be presented via the peer's normal video sink. Defaulting
    /// false keeps the flag-off path (every peer non-independent ⇒ `.legacy`)
    /// unchanged.
    var supportsIndependentContentVideo: Bool = false
}

/// `mode` distinguishes the independent content stream from the legacy
/// single-video-as-content fallback.
enum ContentMode: Equatable {
    case independent
    case legacy
}

/// Resolved content presentation for a single owner.
///
/// `hasMedia` is true when content media is expected to be flowing (and so the
/// content sink should be rendered). When content is active but media has not
/// arrived yet, `loading` is true and the UI shows a connecting tile instead of
/// a stale camera frame (receiver-side hold).
struct ResolvedContent: Equatable {
    let ownerCid: String
    let isLocal: Bool
    let type: ContentType
    /// `.independent` when a dedicated content track exists for this owner;
    /// `.legacy` for the single-video fallback.
    let mode: ContentMode
    /// True when content media is present/expected and the content sink should
    /// render.
    let hasMedia: Bool
    /// Content is active per state but content media has not arrived for this
    /// owner yet.
    let loading: Bool
    /// Local-only: capture is live but no peer is receiving content media yet
    /// (independent "start and wait"). The UI must show "sharing, waiting for
    /// participants" rather than implying media is flowing.
    let waitingForParticipants: Bool
}

/// Inputs to the content resolver for one render.
struct ResolveContentInput {
    /// Local participant state, or nil when not in a call.
    let local: ContentLocalParticipant?
    /// Remote participants in stable order.
    let remotes: [ContentRemoteParticipant]
    /// Whether the local build negotiates an independent content track
    /// (`SerenadaConfig.enableIndependentContentVideo`). When false, no content
    /// track can exist, so every owner resolves `.legacy` and the single camera
    /// video is presented as content (byte-identical to today).
    let independentContentEnabled: Bool
    /// Whether the local user can receive any video media at all
    /// (`videoMediaEnabled`). When false the local user is an audio-only
    /// receiver that never negotiated content receive and MUST suppress ALL
    /// content UI even if room state says someone is sharing.
    let localVideoMediaEnabled: Bool
    /// Predicate: has the SDK got a content track for this remote owner yet?
    /// Used only in INDEPENDENT mode for the loading hold. Defaults to "assume
    /// present once active" (matching legacy, where the single video track is
    /// the content) when a host cannot supply media liveness.
    let remoteContentHasMedia: (String) -> Bool
    /// Whether the local independent content track is live. Used only in
    /// INDEPENDENT mode.
    let localContentHasMedia: () -> Bool
    /// Order in which remote content became active, most recent LAST, as
    /// observed locally. Picks the default primary among multiple simultaneous
    /// remote sharers (design "Multiple Sharers", local receive order).
    let remoteContentOrder: [String]

    init(
        local: ContentLocalParticipant?,
        remotes: [ContentRemoteParticipant],
        independentContentEnabled: Bool,
        localVideoMediaEnabled: Bool,
        remoteContentHasMedia: @escaping (String) -> Bool = { _ in true },
        localContentHasMedia: @escaping () -> Bool = { true },
        remoteContentOrder: [String] = []
    ) {
        self.local = local
        self.remotes = remotes
        self.independentContentEnabled = independentContentEnabled
        self.localVideoMediaEnabled = localVideoMediaEnabled
        self.remoteContentHasMedia = remoteContentHasMedia
        self.localContentHasMedia = localContentHasMedia
        self.remoteContentOrder = remoteContentOrder
    }
}

/// Full content resolution for a render: per-owner resolved content plus the
/// chosen primary. Callers render a content tile per sharer (design:
/// per-peer/per-participant content) from `local` + `remotes`.
struct ContentScene: Equatable {
    let primary: ResolvedContent?
    let local: ResolvedContent?
    let remotes: [ResolvedContent]
}

/// The content-role input to the layout (``ContentSource``), or nil when no
/// content tile should render. This is the render-logic seam that decides WHEN
/// the content-stage layout fires — pure so it can be unit tested.
///
/// Gating rules (the two paths differ deliberately, mirroring web's
/// `resolveContentSource`):
///   - **Multi-party** (`isMultiParty == true`): a content tile renders
///     whenever a primary content owner is resolved — INDEPENDENT or LEGACY.
///     This is the existing behavior; legacy multi-party already presents the
///     single video as a content tile. Unchanged.
///   - **1:1** (`isMultiParty == false`): a content tile renders ONLY when the
///     primary content is INDEPENDENT. In the LEGACY 1:1 case the single video
///     is physically swapped to the screen by the SDK and presented in the
///     normal one-tile layout, so surfacing a content tile would double-render
///     it. Gating strictly on INDEPENDENT keeps the legacy 1:1 single-tile
///     experience byte-identical.
struct ResolvedContentSource: Equatable {
    let type: ContentType
    let ownerCid: String
    let mode: ContentMode
}

// MARK: - Local / remote content activity + type

private func localContentType(_ local: ContentLocalParticipant) -> ContentType {
    if let content = local.content {
        return contentTypeFromWire(content.type)
    }
    switch local.cameraMode {
    case .world: return .worldCamera
    case .composite: return .compositeCamera
    default: return .screenShare
    }
}

private func localContentActive(_ local: ContentLocalParticipant) -> Bool {
    // Precise content.active (populated in both builds while sharing) wins; the
    // legacy isScreenSharing / world|composite camera mode are the fallbacks for
    // participants with no `content` state at all.
    local.content?.active == true
        || local.isScreenSharing
        || local.cameraMode.isContentMode
}

private func remoteContentType(_ remote: ContentRemoteParticipant) -> ContentType {
    guard let content = remote.content else { return .screenShare }
    return contentTypeFromWire(content.type)
}

private func remoteContentActive(_ remote: ContentRemoteParticipant) -> Bool {
    remote.content?.active == true
}

/// Whether a content state describes a SCREEN SHARE specifically (vs a
/// world/composite camera framing). The independent content track carries
/// SCREEN SHARE only (see CONTRACT.md "independent transceiver is screenShare-
/// only"): the iOS engine creates/attaches the dedicated content sink only for
/// screen share. A capable peer switching to world/composite camera emits
/// `content_state` with type worldCamera/compositeCamera and NO content track,
/// so it must NOT resolve `.independent` (that would render a blank content
/// sink). Defaults to screen share for an absent/unknown type
/// (forward-compatible, matching `contentTypeFromWire`).
private func isScreenShareContent(_ content: ParticipantContent?) -> Bool {
    guard let content else { return false }
    return contentTypeFromWire(content.type) == .screenShare
}

/// Map a `content_state.contentType` wire string to the layout ``ContentType``.
/// Defaults to `.screenShare` for unknown values (forward-compatible).
private func contentTypeFromWire(_ wire: String) -> ContentType {
    ContentType.fromWire(wire)
}

// MARK: - Resolution

/// Resolve content for the local participant.
///
/// Receiver-side hold and audio-only suppression are honored: when the local
/// user cannot receive video, no content is presented at all.
func resolveLocalContent(_ input: ResolveContentInput) -> ResolvedContent? {
    guard let local = input.local else { return nil }
    // Audio-only receivers never present content UI. Sharing is also blocked by
    // the SDK for the local user in this mode, so there is nothing to show.
    guard input.localVideoMediaEnabled else { return nil }
    guard localContentActive(local) else { return nil }

    // INDEPENDENT only when the build flag is on AND precise content state
    // exists AND it is a SCREEN SHARE. The dedicated content track carries
    // screen share only; a world/composite camera framing rides the camera
    // track and must render via the legacy/camera path (routing it through the
    // content sink would blank the tile). With the flag off (default) there is
    // provably no independent content track, so the single camera video IS the
    // content — byte-identical to today.
    let independent = input.independentContentEnabled
        && local.content?.active == true
        && isScreenShareContent(local.content)
    if independent {
        let hasMedia = input.localContentHasMedia()
        return ResolvedContent(
            ownerCid: local.cid,
            isLocal: true,
            type: localContentType(local),
            mode: .independent,
            hasMedia: hasMedia,
            loading: !hasMedia,
            // Capture live but no peer receiving yet: independent start-and-wait.
            waitingForParticipants: input.remotes.isEmpty
        )
    }

    // Legacy / flag-off path: the single video IS the content. Byte-identical to
    // today's "screen replaces camera in the single sender".
    return ResolvedContent(
        ownerCid: local.cid,
        isLocal: true,
        type: localContentType(local),
        mode: .legacy,
        hasMedia: true,
        loading: false,
        waitingForParticipants: false
    )
}

/// Resolve content for every remote participant that is presenting.
func resolveRemoteContents(_ input: ResolveContentInput) -> [ResolvedContent] {
    // Audio-only receivers never negotiated content receive: suppress all.
    guard input.localVideoMediaEnabled else { return [] }

    var resolved: [ResolvedContent] = []
    for remote in input.remotes {
        guard remoteContentActive(remote) else { continue }

        // INDEPENDENT is resolved PER PEER: the local build flag is on AND this
        // remote peer advertised independent-content capability AND its content
        // is active AND the content is a SCREEN SHARE. The dedicated content
        // track carries screen share only; a capable peer switching to
        // world/composite CAMERA emits content_state with that type and NO
        // content track, so it must resolve `.legacy` and render via the camera
        // sink (the layout's existing ContentType handling) — routing it through
        // the content sink would blank the tile. A capable peer is INDEPENDENT
        // even before its content track arrives (loading hold below). A
        // NON-capable (legacy) peer routes its share through the single-video
        // path — core delivers NO separate content track for it — so it must
        // resolve `.legacy` and render via the camera sink. With the flag off,
        // no peer is independent ⇒ every peer `.legacy` (unchanged).
        let independent = input.independentContentEnabled
            && remote.supportsIndependentContentVideo
            && remote.content?.active == true
            && isScreenShareContent(remote.content)
        if independent {
            let hasMedia = input.remoteContentHasMedia(remote.cid)
            resolved.append(
                ResolvedContent(
                    ownerCid: remote.cid,
                    isLocal: false,
                    type: remoteContentType(remote),
                    mode: .independent,
                    // Receiver-side hold: only ever resolved when content.active
                    // is true (guarded above); when media has not arrived,
                    // loading.
                    hasMedia: hasMedia,
                    loading: !hasMedia,
                    waitingForParticipants: false
                )
            )
        } else {
            // Legacy / flag-off / non-capable-peer path: the single received
            // video IS the content (rendered via the peer's normal video sink).
            resolved.append(
                ResolvedContent(
                    ownerCid: remote.cid,
                    isLocal: false,
                    type: remoteContentType(remote),
                    mode: .legacy,
                    hasMedia: true,
                    loading: false,
                    waitingForParticipants: false
                )
            )
        }
    }
    return resolved
}

/// Pick the primary content owner among local + multiple simultaneous remote
/// sharers.
///
/// - INDEPENDENT mode (`independentContentEnabled == true`): design "Multiple
///   Sharers" — the most-recently-received active REMOTE content is primary;
///   local content is primary only when no remote content is active. This is an
///   explicitly local heuristic — there is no server-stamped ordering.
/// - FLAG-OFF / LEGACY mode: preserve the legacy CallScreen order, which chose
///   LOCAL content FIRST (`hasLocalContent` before `remoteContentCid`), then the
///   remote content. The most-recently-active remote-first heuristic is an
///   independent-mode feature and must NOT change the byte-identical legacy
///   layout in multi-party calls where local is sharing and a remote
///   `content_state` is also active.
func pickPrimaryContent(
    local: ResolvedContent?,
    remotes: [ResolvedContent],
    remoteContentOrder: [String],
    independentContentEnabled: Bool
) -> ResolvedContent? {
    // Legacy / flag-off: local content wins first (byte-identical to the old
    // CallScreen `if hasLocalContent { ... } else if remoteContentCid { ... }`).
    if !independentContentEnabled {
        return local ?? remotes.last
    }
    // A real screen share (independent content — the only kind with its own content
    // tile) wins the spotlight over a camera-framing legacy content_state
    // (worldCamera/composite, no content tile), which would otherwise steal the
    // spotlight and leave it falling back to a camera. Prefer the most-recent remote
    // independent share, then the local independent share.
    let independentRemotes = remotes.filter { $0.mode == .independent }
    if !independentRemotes.isEmpty {
        for i in stride(from: remoteContentOrder.count - 1, through: 0, by: -1) {
            if let match = independentRemotes.first(where: { $0.ownerCid == remoteContentOrder[i] }) {
                return match
            }
        }
        return independentRemotes.last
    }
    if let local, local.mode == .independent {
        return local
    }
    if !remotes.isEmpty {
        // Most-recently-active is LAST in order. Walk back to front.
        for i in stride(from: remoteContentOrder.count - 1, through: 0, by: -1) {
            if let match = remotes.first(where: { $0.ownerCid == remoteContentOrder[i] }) {
                return match
            }
        }
        // No order info: last in the remotes list (stable input order).
        return remotes.last
    }
    return local
}

func resolveContentScene(_ input: ResolveContentInput) -> ContentScene {
    let local = resolveLocalContent(input)
    let remotes = resolveRemoteContents(input)
    let primary = pickPrimaryContent(
        local: local,
        remotes: remotes,
        remoteContentOrder: input.remoteContentOrder,
        independentContentEnabled: input.independentContentEnabled
    )
    return ContentScene(primary: primary, local: local, remotes: remotes)
}

/// The content-role input to the layout, or nil when no content tile should
/// render. See ``ResolvedContentSource`` for the gating rationale.
func resolveContentSource(_ primary: ResolvedContent?, isMultiParty: Bool) -> ResolvedContentSource? {
    guard let primary else { return nil }
    // 1:1 surfaces content only when it is an independent stream; legacy 1:1
    // stays the single swapped-video tile (byte-identical to today).
    if !isMultiParty && primary.mode != .independent { return nil }
    return ResolvedContentSource(type: primary.type, ownerCid: primary.ownerCid, mode: primary.mode)
}

// MARK: - Phase gating

/// The call phases during which the content-stage layout may render. Mirrors the
/// subset of ``CallPhase`` the call UI treats as an active call; kept local so
/// this helper stays free of SwiftUI types and unit-testable. Every other
/// ``CallPhase`` maps to `.other` (never renders the content stage).
enum ContentStagePhase: Equatable {
    case inCall
    case waiting
    case other
}

/// Map a ``CallPhase`` to a ``ContentStagePhase`` for the content-stage gate.
func contentStagePhase(_ phase: CallPhase) -> ContentStagePhase {
    switch phase {
    case .inCall: return .inCall
    case .waiting: return .waiting
    default: return .other
    }
}

/// The Frontline call UI's INDEPENDENT-content decision for one render.
///
/// The Frontline screen keeps a self-contained LEGACY content model (an
/// `activeContentOwnerId` inferred from `isScreenSharing` / world|composite
/// camera mode / `remoteContentCid`, rendered via the owner's camera renderer as
/// a single swapped video). That legacy path stays byte-identical to today and is
/// NOT derived from this helper.
///
/// This helper layers the INDEPENDENT (dedicated content track) path on top,
/// gated strictly on the shared resolver's ``ContentMode/independent``, which is
/// only reachable with the build flag on AND a real screen-share content track.
/// When the resolved primary content is INDEPENDENT, the content spotlight renders
/// the dedicated content track (not the owner camera), and the owner's CAMERA stays
/// as its own participant tile (simultaneous camera + content) — exactly the
/// standard ``CallScreenView`` behavior, reusing the same per-owner resolver
/// outputs.
///
/// Returns nil whenever the new path must NOT engage:
///   - the primary content is nil or LEGACY (flag off / non-capable owner /
///     world|composite camera-as-content) ⇒ the Frontline legacy path renders
///     unchanged;
///   - audio-only suppression already zeroed the scene (resolver returns no
///     primary).
///
/// Mirrors Android's `resolveFrontlineIndependentContent` /
/// `FrontlineIndependentContent`.
struct FrontlineIndependentContent: Equatable {
    let ownerCid: String
    let isLocal: Bool
    let type: ContentType
    /// True while the content track has not arrived yet (receiver-side hold).
    let loading: Bool
    /// Local-only: capture live but no peer receiving content media yet.
    let waitingForParticipants: Bool
}

/// Resolve the Frontline INDEPENDENT content decision from a ``ContentScene``.
/// Pure so it can be unit tested without SwiftUI. Returns nil unless the chosen
/// primary content is an INDEPENDENT screen-share track (the only case the new
/// dedicated content path engages); every other case keeps the legacy
/// single-video path (byte-identical).
func resolveFrontlineIndependentContent(_ scene: ContentScene) -> FrontlineIndependentContent? {
    guard let primary = scene.primary, primary.mode == .independent else { return nil }
    return FrontlineIndependentContent(
        ownerCid: primary.ownerCid,
        isLocal: primary.isLocal,
        type: primary.type,
        loading: primary.loading,
        waitingForParticipants: primary.waitingForParticipants
    )
}

/// Frontline remote screen shares always render with fit-style scaling. The
/// legacy camera fit/cover preference still applies to remote camera tiles.
func frontlineRemoteScreenShareUsesFit(
    isRemoteScreenShare: Bool,
    remoteVideoFitCover: Bool
) -> Bool {
    isRemoteScreenShare || !remoteVideoFitCover
}

/// A Frontline remote screen-share full-screen request is valid only while the
/// same source is still the current remote screen-share spotlight. If the stream
/// ends or spotlight moves, the UI falls back to the framed view automatically.
func frontlineRemoteScreenShareFullscreenActive(
    requestedSourceId: String?,
    currentSourceId: String?
) -> Bool {
    guard let requestedSourceId, let currentSourceId else { return false }
    return requestedSourceId == currentSourceId
}

let frontlineRemoteScreenShareMinZoomScale = 1.0
let frontlineRemoteScreenShareMaxZoomScale = 4.0

func frontlineRemoteScreenShareZoomScale(
    currentScale: Double,
    change: Double
) -> Double {
    guard currentScale.isFinite, change.isFinite, change > 0 else {
        return frontlineRemoteScreenShareMinZoomScale
    }
    return min(
        frontlineRemoteScreenShareMaxZoomScale,
        max(frontlineRemoteScreenShareMinZoomScale, currentScale * change)
    )
}

func frontlineRemoteScreenSharePanOffset(
    currentX: Double,
    currentY: Double,
    deltaX: Double,
    deltaY: Double,
    scale: Double,
    viewportWidth: Double,
    viewportHeight: Double
) -> (x: Double, y: Double) {
    guard scale.isFinite,
          viewportWidth.isFinite,
          viewportHeight.isFinite,
          scale > frontlineRemoteScreenShareMinZoomScale,
          viewportWidth > 0,
          viewportHeight > 0 else {
        return (0, 0)
    }
    let maxX = max(0, viewportWidth * (scale - 1) / 2)
    let maxY = max(0, viewportHeight * (scale - 1) / 2)
    let nextX = min(maxX, max(-maxX, currentX + deltaX))
    let nextY = min(maxY, max(-maxY, currentY + deltaY))
    return (nextX, nextY)
}

/// Decide WHEN the content-stage render branch fires, gated on the call phase.
/// Pure so it can be unit tested without SwiftUI. Mirrors web's
/// `shouldRenderContentStage` and Android's `shouldRenderContentStage`.
///
/// - Parameters:
///   - phase: mapped call phase (``ContentStagePhase``).
///   - isMultiParty: more than one remote participant present.
///   - hasContentStageLayout: a content-stage layout has resolved for this
///     render (a pin or an INDEPENDENT content source). In 1:1 this is only ever
///     true for INDEPENDENT content; legacy/flag-off 1:1 keeps the single
///     swapped-video tile and never resolves a content-stage layout, so this
///     stays false there (byte-identical to today).
///
/// Rules:
///   - `.inCall`: renders when multi-party OR a content-stage layout is present
///     (1:1 independent content).
///   - `.waiting`: renders ONLY when a content-stage layout is present (1:1 local
///     independent share started before any remote joined).
///   - any other phase: never renders the content stage.
func shouldRenderContentStage(
    phase: ContentStagePhase,
    isMultiParty: Bool,
    hasContentStageLayout: Bool
) -> Bool {
    switch phase {
    case .inCall:
        return isMultiParty || hasContentStageLayout
    case .waiting:
        return hasContentStageLayout
    case .other:
        return false
    }
}

// ===========================================================================
// Stream-keyed stage tiles (filmstrip + spotlight for active content)
//
// When ANY participant is presenting an INDEPENDENT content stream the call UI
// switches to a single filmstrip+spotlight stage where EVERY stream is its own
// tile, keyed by `{cid, kind}`:
//   - a CAMERA tile for every participant whose camera is on (local + remote),
//   - a CONTENT tile for every participant presenting independent content (local
//     + remote), including the LOCAL user's own screen (self-preview, pinnable).
// A sharer's camera is therefore a real filmstrip tile alongside their screen,
// not a PIP over the content. Multiple simultaneous sharers each get a content
// tile. The tile model is intentionally stream-keyed (not participant-keyed) so
// one participant can occupy two tiles at once.
//
// Mirrors web's `client/packages/react-ui/src/utils/contentRendering.ts`
// (`StageTileKind` / `StageTileKey` / `stageTileId` / `parseStageTileId` /
// `deriveStageTiles` / `pickStageSpotlightTileId`). These helpers are pure /
// SwiftUI-free so they unit-test on the host test target.
// ===========================================================================

/// Which stream of a participant a stage tile represents.
enum StageTileKind: String, Equatable {
    case camera
    case content
}

/// Identity of a single stage tile: a participant cid + which stream of theirs.
struct StageTileKey: Equatable {
    let cid: String
    let kind: StageTileKind
}

/// A derived stage tile. `id` is the stable string key fed to ``computeLayout``.
struct StageTile: Equatable {
    /// `"<cid>::<kind>"` — opaque to the layout engine, parsed back by the UI.
    let id: String
    let cid: String
    let kind: StageTileKind
    let isLocal: Bool
}

/// Stable string id for a stage tile (the ``SceneParticipant`` id fed to layout).
/// `cid::kind`; the cid round-trips because ``parseStageTileId`` splits on the
/// LAST `::` (cids never contain `::` in practice, and even if one did the last
/// separator keeps the kind suffix unambiguous).
func stageTileId(_ key: StageTileKey) -> String {
    "\(key.cid)::\(key.kind.rawValue)"
}

/// Parse a stage tile id back into its `{cid, kind}` key, or nil if malformed.
func parseStageTileId(_ id: String) -> StageTileKey? {
    guard let sepRange = id.range(of: "::", options: .backwards) else { return nil }
    let cid = String(id[..<sepRange.lowerBound])
    guard !cid.isEmpty else { return nil }
    let kindRaw = String(id[sepRange.upperBound...])
    guard let kind = StageTileKind(rawValue: kindRaw) else { return nil }
    return StageTileKey(cid: cid, kind: kind)
}

func stageTileKeyEquals(_ a: StageTileKey?, _ b: StageTileKey?) -> Bool {
    guard let a, let b else { return false }
    return a.cid == b.cid && a.kind == b.kind
}

/// A participant whose camera/avatar tile the stage needs (local or remote).
struct StageCameraParticipant: Equatable {
    let cid: String
    let isLocal: Bool
}

/// Derive the full ordered stream-keyed tile list for the stage.
///
/// Order (stable, matches the engine's "local last" filmstrip convention):
///   1. remote camera tiles (input order),
///   2. local camera tile,
///   3. content tiles (input order; local content last via the caller's `content`
///      ordering — see ``stageContent(for:)`` which appends local last).
///
/// A sharer's camera/avatar tile and content are BOTH present. Content tiles
/// are emitted only for INDEPENDENT content; a legacy single-video sharer shows up
/// as their camera tile (the screen replaced the camera), so they are not
/// duplicated as a content tile. Audio-only peers (camera off, no content)
/// contribute no tile. Audio-only-receiver suppression is already handled upstream
/// (the ``ContentScene`` is empty and camera flags false).
func deriveStageTiles(cameras: [StageCameraParticipant], content: [ResolvedContent]) -> [StageTile] {
    var tiles: [StageTile] = []

    // Every participant gets a stage tile while content is active: a live camera
    // tile when their camera is on, otherwise an avatar/placeholder tile (the
    // renderer shows identity + audio activity / mute). Without this a video-off
    // peer vanished from the filmstrip and a lone remaining tile stretched to fill
    // the whole strip.
    for cam in cameras where !cam.isLocal {
        tiles.append(StageTile(id: stageTileId(StageTileKey(cid: cam.cid, kind: .camera)), cid: cam.cid, kind: .camera, isLocal: false))
    }
    for cam in cameras where cam.isLocal {
        tiles.append(StageTile(id: stageTileId(StageTileKey(cid: cam.cid, kind: .camera)), cid: cam.cid, kind: .camera, isLocal: true))
    }
    for c in content where c.mode == .independent {
        // Only INDEPENDENT content is its own tile. A legacy sharer's single video
        // already shows as their CAMERA tile (the screen replaces the camera in
        // legacy mode), so emitting a content tile too would render the same one
        // stream twice in a mixed independent+legacy room.
        tiles.append(StageTile(id: stageTileId(StageTileKey(cid: c.ownerCid, kind: .content)), cid: c.ownerCid, kind: .content, isLocal: c.isLocal))
    }

    return tiles
}

/// The resolver's per-owner content in the order ``deriveStageTiles`` expects:
/// remote sharers first (stable input order), then local content LAST. Mirrors
/// web's `ContentScene.all` (`[...remotes, local]`).
func stageContent(for scene: ContentScene) -> [ResolvedContent] {
    if let local = scene.local {
        return scene.remotes + [local]
    }
    return scene.remotes
}

/// Resolve the spotlight (primary) tile id among the derived tiles.
///
/// - A `pinnedTile` wins whenever its tile is still present (pin ANY tile, camera
///   OR content). Tap-to-unpin reverts to the default below.
/// - Default spotlight = the MOST-RECENT active share, reusing the same primary
///   chosen by ``pickPrimaryContent`` (`contentPrimary`) — surfaced as that
///   owner's CONTENT tile.
/// - Fallbacks (no pin, no content primary tile present): the first tile, then nil
///   when there are no tiles at all.
func pickStageSpotlightTileId(
    tiles: [StageTile],
    pinnedTile: StageTileKey?,
    contentPrimary: ResolvedContent?
) -> String? {
    guard let firstTile = tiles.first else { return nil }

    if let pinnedTile {
        let pinnedId = stageTileId(pinnedTile)
        if tiles.contains(where: { $0.id == pinnedId }) { return pinnedId }
    }

    if let contentPrimary {
        let primaryId = stageTileId(StageTileKey(cid: contentPrimary.ownerCid, kind: .content))
        if tiles.contains(where: { $0.id == primaryId }) { return primaryId }
    }

    return firstTile.id
}
