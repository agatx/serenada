import ReplayKit
import SerenadaCore
import SwiftUI
import UIKit

private let frontlineBlack = Color.black
private let frontlinePanel = Color.black
private let frontlineSurface = Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x1A / 255)
private let frontlineBorder = Color(red: 0x2A / 255, green: 0x2A / 255, blue: 0x2A / 255)
private let frontlineAccent = Color(red: 0x15 / 255, green: 0xBF / 255, blue: 0x54 / 255)
private let frontlineDanger = Color(red: 0xF5 / 255, green: 0x56 / 255, blue: 0x4B / 255)
private let frontlineDim = Color(red: 0xA1 / 255, green: 0xA1 / 255, blue: 0xAA / 255)
private let frontlineSheet = Color(red: 0x15 / 255, green: 0x16 / 255, blue: 0x1A / 255)
private let frontlineSheetRow = Color.white.opacity(0.09)
private let frontlineContentSpotlightPrefix = "content:"
private let frontlineSheetAnimation = Animation.interactiveSpring(response: 0.32, dampingFraction: 0.9, blendDuration: 0.05)
private let frontlineMoreButtonHeightToWidthRatio: CGFloat = 1.62
private let frontlineLargeVideoAccentLineWidth: CGFloat = 3
private let frontlinePipVideoAccentLineWidth: CGFloat = 2.5
private let frontlinePipBorderLineWidth: CGFloat = 1.5
private let frontlineReconnectingBadgeDelayNanoseconds: UInt64 = 800_000_000

private enum FrontlineFeed {
    case local
    case remote
}

private enum FrontlineRemoteScreenShareSource: Equatable {
    case independent(ownerCid: String)
    case legacy(ownerCid: String)

    var id: String {
        switch self {
        case .independent(let ownerCid): return "independent:\(ownerCid)"
        case .legacy(let ownerCid): return "legacy:\(ownerCid)"
        }
    }

    var ownerCid: String {
        switch self {
        case .independent(let ownerCid), .legacy(let ownerCid): return ownerCid
        }
    }

    var videoKind: WebRTCVideoView.Kind {
        switch self {
        case .independent(let ownerCid): return .remoteContentForCid(ownerCid)
        case .legacy(let ownerCid): return .remoteForCid(ownerCid)
        }
    }
}

func frontlineIsWaitingForRemote(_ uiState: CallUiState) -> Bool {
    (uiState.phase == .waiting || uiState.phase == .inCall) && uiState.remoteParticipants.isEmpty
}

func frontlineUsesLargeLocalPreview(
    localVideoEnabled: Bool,
    localCameraMode: LocalCameraMode,
    isScreenSharing: Bool,
    pipSwapped: Bool
) -> Bool {
    guard localVideoEnabled else { return false }
    let localContentMode = localCameraMode == .world || localCameraMode == .composite || isScreenSharing
    return localContentMode ? !pipSwapped : pipSwapped
}

func frontlineSnapshotSource(
    snapshotEnabled: Bool,
    localVideoEnabled: Bool,
    remoteParticipants: [RemoteParticipant]
) -> SnapshotSource? {
    guard snapshotEnabled else { return nil }
    if localVideoEnabled { return .local }
    return remoteParticipants.first(where: { $0.videoEnabled }).map { .remote(cid: $0.cid) }
}

func frontlineAllowsLocalCameraZoom(
    phase: CallPhase,
    localVideoEnabled: Bool,
    localCameraMode: LocalCameraMode,
    isScreenSharing: Bool
) -> Bool {
    (phase == .waiting || phase == .inCall)
        && localVideoEnabled
        && localCameraMode.isContentMode
        && !isScreenSharing
}

func frontlineIncludesNormalLocalStageTile(
    localSpotlightId: String,
    activeContentOwnerId: String?,
    contentTileIsSpotlight: Bool
) -> Bool {
    activeContentOwnerId != localSpotlightId || contentTileIsSpotlight
}

func frontlineMoreMenuOpensAudioRouteDirectly(
    showsAudioRoute: Bool,
    screenSharingEnabled: Bool,
    inviteEnabled: Bool
) -> Bool {
    showsAudioRoute && !screenSharingEnabled && !inviteEnabled
}

func frontlineShowsRemoteFitButton(
    isCallSurfacePhase: Bool,
    waitingForRemote: Bool,
    remoteParticipantCount: Int,
    largeFeedIsRemote: Bool,
    remoteVideoEnabled: Bool
) -> Bool {
    isCallSurfacePhase &&
        !waitingForRemote &&
        remoteParticipantCount <= 1 &&
        largeFeedIsRemote &&
        remoteVideoEnabled
}

struct FrontlineCallScreenView: View {
    let roomId: String
    let uiState: CallUiState
    let sessionPhase: SerenadaCallPhase
    let roomShareURL: URL?
    let screenShareExtensionBundleId: String?
    let screenShareAvailable: Bool
    let roomName: String?
    let config: SerenadaCallFlowConfig
    let strings: [SerenadaString: String]?
    /// Resolved independent-content (screen share) scene for this render. With the
    /// independent flag off (default) every owner is LEGACY,
    /// ``resolveFrontlineIndependentContent`` returns nil, and the existing
    /// single-video-as-content presentation is preserved byte-identically.
    let contentScene: ContentScene
    let availableAudioDevices: [AudioDevice]
    let currentAudioDevice: AudioDevice?
    let onToggleAudio: () -> Void
    let onSelectAudioDevice: (AudioDevice) -> Void
    let onToggleVideo: () -> Void
    let onFlipCamera: () -> Void
    let onToggleScreenShare: () -> Void
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onResetCameraZoom: () -> Void
    let onToggleFlashlight: () -> Void
    let onEndCall: () -> Void
    let onInviteToRoom: () async -> Result<Void, Error>
    let onRequestPermissions: () -> Void
    let onDismiss: (() -> Void)?
    let onSnapshotRequested: ((SnapshotSource) -> Void)?
    let rendererProvider: CallRendererProvider
    let onRemoteVideoFitChanged: ((Bool) -> Void)?
    let onSystemPictureInPictureSourceChanged: ((SystemPictureInPictureSource) -> Void)?
    let onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)?

    @State private var pipSwapped = false
    @State private var isMoreSheetVisible = false
    @State private var isAudioRouteSheetVisible = false
    @State private var remoteVideoFitCover: Bool
    @State private var showShareSheet = false
    @State private var showSnapshotFlash = false
    @State private var showDebugPanel = false
    @State private var lastDebugTapAt: Date?
    @State private var lastMagnificationValue: CGFloat = 1
    @State private var remoteTileAspectRatios: [String: CGFloat] = [:]
    @State private var pinnedSpotlightId: String?
    @State private var selectedSpotlightId: String?
    @State private var lastVideoStartedParticipantId: String?
    @State private var previousRemoteVideoEnabled: [String: Bool] = [:]
    @State private var broadcastTriggerCount = 0
    @State private var showConnectionStatusBadge = false
    @State private var remoteScreenShareFullscreenSourceId: String?

    init(
        roomId: String,
        uiState: CallUiState,
        sessionPhase: SerenadaCallPhase,
        roomShareURL: URL?,
        screenShareExtensionBundleId: String?,
        screenShareAvailable: Bool = true,
        roomName: String?,
        config: SerenadaCallFlowConfig,
        strings: [SerenadaString: String]?,
        contentScene: ContentScene = ContentScene(primary: nil, local: nil, remotes: []),
        availableAudioDevices: [AudioDevice],
        currentAudioDevice: AudioDevice?,
        onToggleAudio: @escaping () -> Void,
        onSelectAudioDevice: @escaping (AudioDevice) -> Void,
        onToggleVideo: @escaping () -> Void,
        onFlipCamera: @escaping () -> Void,
        onToggleScreenShare: @escaping () -> Void,
        onAdjustCameraZoom: @escaping (CGFloat) -> Void,
        onResetCameraZoom: @escaping () -> Void,
        onToggleFlashlight: @escaping () -> Void,
        onEndCall: @escaping () -> Void,
        onInviteToRoom: @escaping () async -> Result<Void, Error>,
        onRequestPermissions: @escaping () -> Void,
        onDismiss: (() -> Void)?,
        onSnapshotRequested: ((SnapshotSource) -> Void)?,
        rendererProvider: CallRendererProvider,
        initialRemoteVideoFitCover: Bool = true,
        onRemoteVideoFitChanged: ((Bool) -> Void)? = nil,
        onSystemPictureInPictureSourceChanged: ((SystemPictureInPictureSource) -> Void)? = nil,
        onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)? = nil
    ) {
        self.roomId = roomId
        self.uiState = uiState
        self.sessionPhase = sessionPhase
        self.roomShareURL = roomShareURL
        self.screenShareExtensionBundleId = screenShareExtensionBundleId
        self.screenShareAvailable = screenShareAvailable
        self.roomName = roomName
        self.config = config
        self.strings = strings
        self.contentScene = contentScene
        self.availableAudioDevices = availableAudioDevices
        self.currentAudioDevice = currentAudioDevice
        self.onToggleAudio = onToggleAudio
        self.onSelectAudioDevice = onSelectAudioDevice
        self.onToggleVideo = onToggleVideo
        self.onFlipCamera = onFlipCamera
        self.onToggleScreenShare = onToggleScreenShare
        self.onAdjustCameraZoom = onAdjustCameraZoom
        self.onResetCameraZoom = onResetCameraZoom
        self.onToggleFlashlight = onToggleFlashlight
        self.onEndCall = onEndCall
        self.onInviteToRoom = onInviteToRoom
        self.onRequestPermissions = onRequestPermissions
        self.onDismiss = onDismiss
        self.onSnapshotRequested = onSnapshotRequested
        self.rendererProvider = rendererProvider
        self.onRemoteVideoFitChanged = onRemoteVideoFitChanged
        self.onSystemPictureInPictureSourceChanged = onSystemPictureInPictureSourceChanged
        self.onSystemPictureInPictureSourceFrameChanged = onSystemPictureInPictureSourceFrameChanged
        _remoteVideoFitCover = State(initialValue: initialRemoteVideoFitCover)
    }

    private func str(_ key: SerenadaString) -> String {
        resolveString(key, overrides: strings)
    }

    private var isCallSurfacePhase: Bool {
        uiState.phase == .waiting || uiState.phase == .inCall
    }

    /// Non-nil ONLY for an INDEPENDENT screen-share primary (flag on + a real
    /// content track). Drives the dedicated content track + simultaneous owner
    /// camera. Nil keeps the Frontline legacy single-video-as-content path
    /// byte-identical. Reuses the shared pure resolver, same as ``CallScreenView``.
    private var frontlineIndependentContent: FrontlineIndependentContent? {
        resolveFrontlineIndependentContent(contentScene)
    }

    /// True when an INDEPENDENT content stage must render: the stream-keyed
    /// filmstrip+spotlight where the sharer's camera and screen are TWO EQUAL peer
    /// tiles (NOT a camera-PIP-on-content), with a content tile per simultaneous
    /// sharer. Never reachable flag-off, so the legacy branches stay byte-identical.
    private var useIndependentContentStage: Bool {
        isCallSurfacePhase && frontlineIndependentContent != nil
    }

    /// The resolver's per-owner content with local LAST (mirrors web's
    /// `ContentScene.all`), the input order ``deriveStageTiles`` expects.
    private var stageContentAll: [ResolvedContent] {
        stageContent(for: contentScene)
    }

    /// The full stream-keyed tile list for the INDEPENDENT content stage (one tile
    /// per camera-on participant + one per independent sharer, keyed {cid, kind}).
    /// Empty unless an independent content stream is present, so the legacy
    /// synthetic-content stage is never affected.
    private var frontlineStageTiles: [StageTile] {
        guard useIndependentContentStage else { return [] }
        let cameras: [StageCameraParticipant] =
            uiState.remoteParticipants.map {
                StageCameraParticipant(cid: $0.cid, isLocal: false)
            }
            + [StageCameraParticipant(cid: localSpotlightId, isLocal: true)]
        return deriveStageTiles(cameras: cameras, content: stageContentAll)
    }

    /// The Frontline spotlight tile id for the INDEPENDENT stage, honoring the
    /// existing pin → select → recency precedence, expressed through the shared
    /// {cid,kind} stage-tile id namespace. Pin/select hold the stage-tile id; the
    /// recency default maps the most-recently-started camera's participant to its
    /// CAMERA tile, falling back to the resolver's content primary.
    private var frontlineStageSpotlightId: String? {
        let tiles = frontlineStageTiles
        guard !tiles.isEmpty else { return nil }
        let presentIds = Set(tiles.map(\.id))
        if let pinnedSpotlightId, presentIds.contains(pinnedSpotlightId) { return pinnedSpotlightId }
        if let selectedSpotlightId, presentIds.contains(selectedSpotlightId) { return selectedSpotlightId }
        // Default = the resolver's most-recent share (its content tile). An active
        // share ALWAYS wins the default spotlight over camera recency: a later
        // camera-start must not steal the spotlight from an active screen share
        // (matches Android/web; codex P1).
        if contentScene.primary != nil {
            return pickStageSpotlightTileId(tiles: tiles, pinnedTile: nil, contentPrimary: contentScene.primary)
        }
        // No active content primary: fall back to the most-recently-started camera,
        // then the first tile.
        if let recentCamera = lastVideoStartedParticipantId {
            let cameraId = stageTileId(StageTileKey(cid: recentCamera, kind: .camera))
            if presentIds.contains(cameraId) { return cameraId }
        }
        return pickStageSpotlightTileId(tiles: tiles, pinnedTile: nil, contentPrimary: nil)
    }

    /// Legacy content owner collapse (single swapped video). Unchanged from today,
    /// but gated OFF when the dedicated independent path is active so the legacy
    /// camera-as-content collapse does not also fire (the owner camera must stay a
    /// normal tile alongside the content).
    private var localContentMode: Bool {
        guard frontlineIndependentContent == nil else { return false }
        return uiState.localCameraMode == .world || uiState.localCameraMode == .composite || uiState.isScreenSharing
    }

    private var isLocalCameraZoomEnabled: Bool {
        frontlineAllowsLocalCameraZoom(
            phase: uiState.phase,
            localVideoEnabled: uiState.localVideoEnabled,
            localCameraMode: uiState.localCameraMode,
            isScreenSharing: uiState.isScreenSharing
        )
    }

    private var remote: RemoteParticipant? {
        uiState.remoteParticipants.first
    }

    private var snapshotSource: SnapshotSource? {
        guard isCallSurfacePhase else { return nil }
        return frontlineSnapshotSource(
            snapshotEnabled: config.snapshotEnabled && onSnapshotRequested != nil,
            localVideoEnabled: uiState.localVideoEnabled,
            remoteParticipants: uiState.remoteParticipants
        )
    }

    private var currentSystemPictureInPictureSource: SystemPictureInPictureSource {
        // INDEPENDENT stage: prefer the spotlight's CAMERA owner. A stream-keyed id
        // ("cid::kind") maps to its cid for a camera tile, or to "" (ignored) for a
        // content tile — content has no PiP-able camera target.
        if useIndependentContentStage {
            return selectSystemPictureInPictureSource(
                localSourceId: localSpotlightId,
                localIsPrimary: false,
                localVideoEnabled: uiState.localVideoEnabled,
                remoteParticipants: uiState.remoteParticipants,
                preferredSourceIds: [frontlineStageSpotlightId],
                sourceIdForPreferredSourceId: { sourceId in
                    guard let key = parseStageTileId(sourceId) else { return sourceId }
                    return key.kind == .camera ? key.cid : ""
                }
            )
        }
        return selectSystemPictureInPictureSource(
            localSourceId: localSpotlightId,
            localIsPrimary: uiState.remoteParticipants.count <= 1 && largeFeed == .local,
            localVideoEnabled: uiState.localVideoEnabled,
            remoteParticipants: uiState.remoteParticipants,
            preferredSourceIds: uiState.remoteParticipants.count > 1
                ? [pinnedSpotlightId, selectedSpotlightId, lastVideoStartedParticipantId]
                : [],
            sourceIdForPreferredSourceId: { sourceId in
                sourceId.isFrontlineContentSpotlightId
                    ? sourceId.removingFrontlineContentSpotlightPrefix
                    : sourceId
            }
        )
    }

    var body: some View {
        GeometryReader { geometry in
            let isLandscape = geometry.size.width > geometry.size.height
            let isTabletLandscape = isLandscape && geometry.size.width >= 1100 && geometry.size.height >= 720
            let panelWidth = isLandscape ? (geometry.size.width >= 720 ? 320.0 : 260.0) : geometry.size.width
            // INDEPENDENT content stage owns the sharer's camera as its own tile +
            // PIP, so the bespoke 1:1 swap-PIP (floating and in-panel) is suppressed.
            // `useIndependentContentStage` is only ever true flag-on with a real
            // content track, so the flag-off path is byte-identical.
            let pipFeed = useIndependentContentStage ? nil : pipFeed
            let pipInPanel = isTabletLandscape && pipFeed != nil
            let pipSize = frontlinePipSize(containerSize: geometry.size, inPanel: pipInPanel)
            let fullscreenSource = remoteScreenShareFullscreenSource

            ZStack {
                frontlineBlack.ignoresSafeArea()

                if let fullscreenSource {
                    FrontlineRemoteScreenShareFullscreenSurface(
                        source: fullscreenSource,
                        rendererProvider: rendererProvider,
                        strings: strings,
                        onExit: { remoteScreenShareFullscreenSourceId = nil }
                    )
                } else {
                    if isLandscape {
                        HStack(spacing: 0) {
                            contentArea(
                                pipFeed: pipFeed,
                                pipInPanel: pipInPanel,
                                pipSize: pipSize
                            )
                            .frame(width: max(0, geometry.size.width - panelWidth), height: geometry.size.height)

                            controlsPanel(
                                isLandscape: true,
                                isTabletLandscape: isTabletLandscape,
                                panelWidth: panelWidth,
                                pipFeed: pipFeed,
                                pipInPanel: pipInPanel,
                                pipSize: pipSize
                            )
                            .frame(width: panelWidth, height: geometry.size.height)
                        }
                        .ignoresSafeArea()
                    } else {
                        VStack(spacing: 0) {
                            contentArea(
                                pipFeed: pipFeed,
                                pipInPanel: false,
                                pipSize: pipSize
                            )
                            .frame(width: geometry.size.width)
                            .frame(maxHeight: .infinity)

                            controlsPanel(
                                isLandscape: false,
                                isTabletLandscape: false,
                                panelWidth: panelWidth,
                                pipFeed: pipFeed,
                                pipInPanel: false,
                                pipSize: pipSize
                            )
                        }
                        .ignoresSafeArea(edges: .bottom)
                    }

                    reconnectingBadge
                    debugOverlay
                    snapshotFlashOverlay
                    moreSheet
                    audioRouteSheet
                }
            }
        }
        .background(frontlineBlack)
        .overlay(alignment: .topLeading) {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityIdentifier("call.frontline.screen")
        }
        .onChange(of: uiState.localVideoEnabled) { _ in pipSwapped = false }
        .onChange(of: uiState.localCameraMode) { _ in pipSwapped = false }
        .task(id: currentSystemPictureInPictureSource) {
            onSystemPictureInPictureSourceChanged?(currentSystemPictureInPictureSource)
        }
        .task(id: "\(uiState.phase.rawValue):\(uiState.connectionStatus.rawValue)") {
            guard uiState.phase == .inCall, uiState.connectionStatus != .connected else {
                showConnectionStatusBadge = false
                return
            }
            showConnectionStatusBadge = false
            try? await Task.sleep(nanoseconds: frontlineReconnectingBadgeDelayNanoseconds)
            guard !Task.isCancelled else { return }
            guard uiState.phase == .inCall, uiState.connectionStatus != .connected else { return }
            showConnectionStatusBadge = true
        }
        .onChange(of: uiState.remoteParticipants.map { $0.cid }) { activeCids in
            let active = Set(activeCids)
            remoteTileAspectRatios = remoteTileAspectRatios.filter { active.contains($0.key) }
            if let pinnedSpotlightId, !frontlineActiveSpotlightIds.contains(pinnedSpotlightId) {
                self.pinnedSpotlightId = nil
            }
            if let selectedSpotlightId, !frontlineActiveSpotlightIds.contains(selectedSpotlightId) {
                self.selectedSpotlightId = nil
            }
        }
        .onChange(of: uiState.remoteParticipants.map { "\($0.cid):\($0.videoEnabled)" }) { _ in
            updateLastVideoStartedParticipant()
        }
        .onChange(of: frontlineStageTiles.map(\.id)) { ids in
            // INDEPENDENT stage: a stream-keyed pin/select clears when its exact
            // tile disappears (sharer stopped, camera off, peer left) even if the
            // participant list is unchanged — so the spotlight reverts to default.
            guard useIndependentContentStage else { return }
            let present = Set(ids)
            if let pinnedSpotlightId, !present.contains(pinnedSpotlightId) { self.pinnedSpotlightId = nil }
            if let selectedSpotlightId, !present.contains(selectedSpotlightId) { self.selectedSpotlightId = nil }
        }
        .onChange(of: activeContentSpotlightId) { id in
            // Legacy synthetic-content auto-select only. The INDEPENDENT stage
            // resolves its spotlight through the {cid,kind} model (default =
            // resolver primary), so do NOT auto-select the legacy `content:` id
            // there (it would not match a stream-keyed tile anyway).
            guard !useIndependentContentStage else { return }
            if let id {
                selectedSpotlightId = id
            } else {
                if selectedSpotlightId?.isFrontlineContentSpotlightId == true { selectedSpotlightId = nil }
                if pinnedSpotlightId?.isFrontlineContentSpotlightId == true { pinnedSpotlightId = nil }
            }
        }
        .onChange(of: spotlightedRemoteScreenShareSource?.id) { currentSourceId in
            guard let requested = remoteScreenShareFullscreenSourceId else { return }
            if requested != currentSourceId {
                remoteScreenShareFullscreenSourceId = nil
            }
        }
        .onChange(of: localContentMode) { enabled in
            if !enabled {
                lastMagnificationValue = 1
                onResetCameraZoom()
            }
        }
        .sheet(isPresented: $showShareSheet) {
            if let roomShareURL {
                ActivityView(items: [roomShareURL])
            }
        }
    }

    private var localSpotlightId: String {
        uiState.localCid ?? "local"
    }

    private var activeContentOwnerId: String? {
        // INDEPENDENT: the resolver's primary owns the content; its camera stays a
        // normal tile alongside the dedicated content track.
        if let independent = frontlineIndependentContent {
            return independent.isLocal ? localSpotlightId : independent.ownerCid
        }
        // LEGACY (flag off / non-capable / world|composite camera-as-content):
        // unchanged from today.
        if uiState.isScreenSharing { return localSpotlightId }
        if uiState.localVideoEnabled && (uiState.localCameraMode == .world || uiState.localCameraMode == .composite) {
            return localSpotlightId
        }
        return uiState.remoteContentCid
    }

    private var activeContentSpotlightId: String? {
        activeContentOwnerId.map { $0.frontlineContentSpotlightId }
    }

    private var spotlightedRemoteScreenShareSource: FrontlineRemoteScreenShareSource? {
        guard isCallSurfacePhase else { return nil }
        if useIndependentContentStage {
            guard let spotlightId = frontlineStageSpotlightId,
                  let key = parseStageTileId(spotlightId),
                  key.kind == .content,
                  key.cid != localSpotlightId,
                  contentScene.remotes.contains(where: { $0.ownerCid == key.cid && $0.type == .screenShare }) else {
                return nil
            }
            return .independent(ownerCid: key.cid)
        }

        guard let ownerCid = activeContentOwnerId,
              ownerCid != localSpotlightId,
              ownerCid == uiState.remoteContentCid,
              ContentType.fromWire(uiState.remoteContentType) == .screenShare else {
            return nil
        }

        if uiState.remoteParticipants.count <= 1 {
            return largeFeed == .remote ? .legacy(ownerCid: ownerCid) : nil
        }

        guard let activeContentSpotlightId else { return nil }
        var availableIds = Set(uiState.remoteParticipants.map(\.cid))
        availableIds.insert(localSpotlightId)
        availableIds.insert(activeContentSpotlightId)
        let defaultPrimary = lastVideoStartedParticipantId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? uiState.remoteParticipants.first?.cid
            ?? localSpotlightId
        let effectiveSpotlight = pinnedSpotlightId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? selectedSpotlightId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? defaultPrimary
        return effectiveSpotlight == activeContentSpotlightId ? .legacy(ownerCid: ownerCid) : nil
    }

    private var remoteScreenShareFullscreenSource: FrontlineRemoteScreenShareSource? {
        guard frontlineRemoteScreenShareFullscreenActive(
            requestedSourceId: remoteScreenShareFullscreenSourceId,
            currentSourceId: spotlightedRemoteScreenShareSource?.id
        ) else {
            return nil
        }
        return spotlightedRemoteScreenShareSource
    }

    private var frontlineActiveSpotlightIds: Set<String> {
        // INDEPENDENT stage: the valid spotlight ids are the stream-keyed tile ids
        // ({cid, kind}). A pin/select on a camera or content tile clears when that
        // exact tile disappears (sharer stopped, camera off, peer left).
        if useIndependentContentStage {
            return Set(frontlineStageTiles.map(\.id))
        }
        var ids = Set(uiState.remoteParticipants.map(\.cid))
        ids.insert(localSpotlightId)
        if let activeContentSpotlightId {
            ids.insert(activeContentSpotlightId)
        }
        return ids
    }

    private var largeFeed: FrontlineFeed {
        frontlineUsesLargeLocalPreview(
            localVideoEnabled: uiState.localVideoEnabled,
            localCameraMode: uiState.localCameraMode,
            isScreenSharing: uiState.isScreenSharing,
            pipSwapped: pipSwapped
        ) ? .local : .remote
    }

    private var pipFeed: FrontlineFeed? {
        guard uiState.localVideoEnabled || remote?.videoEnabled == true else { return nil }
        return largeFeed == .local ? .remote : .local
    }

    private var canSwapPip: Bool {
        pipFeed != nil && uiState.localVideoEnabled && remote != nil
    }

    private var currentAudioRoute: AudioDevice? {
        currentCallAudioRoute(currentAudioDevice: currentAudioDevice, availableAudioDevices: availableAudioDevices)
    }

    private var audioRouteOptions: [AudioDevice] {
        callAudioRouteOptions(currentAudioDevice: currentAudioDevice, availableAudioDevices: availableAudioDevices)
    }

    private var showsAudioRoute: Bool {
        currentAudioRoute != nil || !audioRouteOptions.isEmpty
    }

    private var shouldShowMoreButton: Bool {
        isCallSurfacePhase && (showsAudioRoute || config.screenSharingEnabled || config.inviteControlsEnabled)
    }

    private var moreOpensAudioRouteDirectly: Bool {
        frontlineMoreMenuOpensAudioRouteDirectly(
            showsAudioRoute: showsAudioRoute,
            screenSharingEnabled: config.screenSharingEnabled,
            inviteEnabled: config.inviteControlsEnabled
        )
    }

    @ViewBuilder
    private func contentArea(
        pipFeed: FrontlineFeed?,
        pipInPanel: Bool,
        pipSize: CGSize
    ) -> some View {
        let useIndependentContentStage = useIndependentContentStage
        let remoteScreenShareSource = spotlightedRemoteScreenShareSource
        let showRemoteFitButton = !useIndependentContentStage && frontlineShowsRemoteFitButton(
            isCallSurfacePhase: isCallSurfacePhase,
            waitingForRemote: frontlineIsWaitingForRemote(uiState),
            remoteParticipantCount: uiState.remoteParticipants.count,
            largeFeedIsRemote: largeFeed == .remote,
            remoteVideoEnabled: remote?.videoEnabled == true
        )
        ZStack {
            if !isCallSurfacePhase {
                FrontlinePhaseSurface(
                    sessionPhase: sessionPhase,
                    localDisplayName: uiState.localDisplayName,
                    strings: strings,
                    onRequestPermissions: onRequestPermissions,
                    onDismiss: onDismiss
                )
            } else if useIndependentContentStage || uiState.remoteParticipants.count > 1 {
                // INDEPENDENT content (flag on + real screen-share track) routes
                // through the content stage like ``CallScreenView``'s
                // `oneToOneIndependentContent`: a dedicated content tile renders the
                // content track while the sharer's camera stays a normal participant
                // tile (simultaneous camera + content), including the "sharing,
                // waiting for participants" hold while alone. Never reachable
                // flag-off, so the legacy branches below stay byte-identical.
                multiPartyStage
                    .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
            } else if frontlineIsWaitingForRemote(uiState) {
                waitingSurface
                    .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
            } else {
                largeSurface(feed: largeFeed)
            }

            if isCallSurfacePhase,
               !useIndependentContentStage,
               !frontlineIsWaitingForRemote(uiState),
               uiState.remoteParticipants.count <= 1,
               uiState.localVideoEnabled,
               largeFeed == .local {
                Rectangle()
                    .strokeBorder(frontlineAccent, lineWidth: frontlineLargeVideoAccentLineWidth)
                    .allowsHitTesting(false)
            }

            if shouldShowLargeNameChip {
                FrontlineNameChip(
                    label: largeFeed == .local ? localDisplayName : remoteDisplayName(remote),
                    muted: largeFeed == .local ? !uiState.localAudioEnabled : remote?.audioEnabled == false,
                    audioLevel: largeFeed == .local ? uiState.localAudioLevel : remote?.audioLevel ?? 0
                )
                .padding(.leading, 16)
                .padding(.bottom, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }

            if showRemoteFitButton {
                FrontlineRemoteFitButton(
                    remoteVideoFitCover: remoteScreenShareSource == nil ? remoteVideoFitCover : false,
                    strings: strings,
                    onClick: {
                        if let remoteScreenShareSource {
                            enterRemoteScreenShareFullscreen(remoteScreenShareSource)
                        } else {
                            toggleRemoteVideoFit()
                        }
                    }
                )
                .padding(.trailing, 16)
                .padding(.bottom, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }

            if isCallSurfacePhase,
               !useIndependentContentStage,
               !frontlineIsWaitingForRemote(uiState),
               uiState.remoteParticipants.count <= 1,
               let pipFeed,
               !pipInPanel {
                FrontlinePipView(
                    feed: pipFeed,
                    uiState: uiState,
                    remote: remote,
                    rendererProvider: rendererProvider,
                    size: pipSize,
                    showSwapHint: canSwapPip,
                    strings: strings,
                    onClick: {
                        guard canSwapPip else { return }
                        withAnimation(.easeInOut(duration: 0.18)) {
                            pipSwapped.toggle()
                        }
                    }
                )
                .padding(.top, 12)
                .padding(.trailing, 14)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .background(frontlineBlack)
        .clipped()
    }

    private var shouldShowLargeNameChip: Bool {
        guard isCallSurfacePhase,
              !useIndependentContentStage,
              !frontlineIsWaitingForRemote(uiState),
              uiState.remoteParticipants.count <= 1 else {
            return false
        }
        if largeFeed == .local {
            return uiState.localVideoEnabled
        }
        return remote?.videoEnabled == true
    }

    private var waitingSurface: some View {
        ZStack {
            frontlineBlack
            if uiState.localVideoEnabled {
                localVideoView(contentMode: uiState.isScreenSharing ? .scaleAspectFit : .scaleAspectFill)
                Color.black.opacity(0.18)
                    .allowsHitTesting(false)
                localZoomInteractionLayer(enabled: isLocalCameraZoomEnabled)
            }
            Text(str(.frontlineWaiting))
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .ignoresSafeArea()
    }

    @ViewBuilder
    private func largeSurface(feed: FrontlineFeed) -> some View {
        switch feed {
        case .local where uiState.localVideoEnabled:
            ZStack {
                localVideoView(contentMode: uiState.isScreenSharing ? .scaleAspectFit : .scaleAspectFill)
                localZoomInteractionLayer(enabled: isLocalCameraZoomEnabled)
            }
            .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
        case .remote where remote?.videoEnabled == true:
            WebRTCVideoView(
                kind: remote.map { .remoteForCid($0.cid) } ?? .remote,
                rendererProvider: rendererProvider,
                videoContentMode: frontlineRemoteScreenShareUsesFit(
                    isRemoteScreenShare: spotlightedRemoteScreenShareSource != nil,
                    remoteVideoFitCover: remoteVideoFitCover
                ) ? .scaleAspectFit : .scaleAspectFill
            )
            .ignoresSafeArea()
            .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
        default:
            FrontlineAudioLarge(
                remote: remote,
                startedAtMs: uiState.callStartedAtMs,
                strings: strings,
                onSystemPictureInPictureSourceFrameChanged: onSystemPictureInPictureSourceFrameChanged
            )
        }
    }

    @ViewBuilder
    private var multiPartyStage: some View {
        if useIndependentContentStage {
            // INDEPENDENT: stream-keyed filmstrip+spotlight where the sharer's
            // camera and screen are TWO EQUAL peer tiles and each simultaneous
            // sharer gets its own content tile. Reuses the shared {cid,kind} model;
            // keeps Frontline's pin/select/recency via `frontlineStageSpotlightId`.
            FrontlineStreamKeyedStage(
                uiState: uiState,
                localSpotlightId: localSpotlightId,
                tiles: frontlineStageTiles,
                spotlightId: frontlineStageSpotlightId,
                contentScene: contentScene,
                remoteTileAspectRatios: $remoteTileAspectRatios,
                pinnedSpotlightId: $pinnedSpotlightId,
                selectedSpotlightId: $selectedSpotlightId,
                remoteVideoFitCover: $remoteVideoFitCover,
                rendererProvider: rendererProvider,
                strings: strings,
                onAdjustCameraZoom: onAdjustCameraZoom,
                onRemoteVideoFitChanged: onRemoteVideoFitChanged,
                onRemoteScreenShareFullscreen: enterRemoteScreenShareFullscreen
            )
        } else {
            FrontlineMultiPartyStage(
                uiState: uiState,
                localSpotlightId: localSpotlightId,
                activeContentSpotlightId: activeContentSpotlightId,
                localContentMode: localContentMode,
                remoteTileAspectRatios: $remoteTileAspectRatios,
                pinnedSpotlightId: $pinnedSpotlightId,
                selectedSpotlightId: $selectedSpotlightId,
                lastVideoStartedParticipantId: lastVideoStartedParticipantId,
                remoteVideoFitCover: $remoteVideoFitCover,
                rendererProvider: rendererProvider,
                strings: strings,
                onAdjustCameraZoom: onAdjustCameraZoom,
                onRemoteVideoFitChanged: onRemoteVideoFitChanged,
                onRemoteScreenShareFullscreen: enterRemoteScreenShareFullscreen
            )
        }
    }

    private func localVideoView(contentMode: UIView.ContentMode) -> some View {
        WebRTCVideoView(
            kind: .local,
            rendererProvider: rendererProvider,
            videoContentMode: contentMode,
            isMirrored: uiState.isFrontCamera && !uiState.isScreenSharing
        )
        .ignoresSafeArea()
    }

    private func localZoomInteractionLayer(enabled: Bool) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .accessibilityHidden(true)
            .simultaneousGesture(localZoomGesture(enabled: enabled))
    }

    private func localZoomGesture(enabled: Bool) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard enabled else { return }
                let delta = value / max(lastMagnificationValue, 0.001)
                lastMagnificationValue = value
                guard abs(delta - 1) > 0.01 else { return }
                onAdjustCameraZoom(delta)
            }
            .onEnded { _ in
                lastMagnificationValue = 1
            }
    }

    private func controlsPanel(
        isLandscape: Bool,
        isTabletLandscape: Bool,
        panelWidth: CGFloat,
        pipFeed: FrontlineFeed?,
        pipInPanel: Bool,
        pipSize: CGSize
    ) -> some View {
        FrontlineControlsPanel(
            uiState: uiState,
            isLandscape: isLandscape,
            isTabletLandscape: isTabletLandscape,
            panelWidth: panelWidth,
            callControlsEnabled: isCallSurfacePhase,
            videoControlsEnabled: isCallSurfacePhase && config.videoEnabled && !uiState.availableCameraModes.isEmpty,
            showMoreButton: shouldShowMoreButton,
            snapshotSource: snapshotSource,
            snapshotHandler: onSnapshotRequested,
            reservePreviewActions: isLandscape,
            pipInPanel: pipInPanel,
            pip: {
                if let pipFeed {
                    FrontlinePipView(
                        feed: pipFeed,
                        uiState: uiState,
                        remote: remote,
                        rendererProvider: rendererProvider,
                        size: pipSize,
                        showSwapHint: canSwapPip,
                        strings: strings,
                        onClick: {
                            guard canSwapPip else { return }
                            withAnimation(.easeInOut(duration: 0.18)) {
                                pipSwapped.toggle()
                            }
                        }
                    )
                }
            },
            strings: strings,
            onVideoTap: {
                if uiState.localVideoEnabled {
                    pipSwapped = false
                }
                onToggleVideo()
            },
            onToggleAudio: onToggleAudio,
            onFlipCamera: onFlipCamera,
            onToggleFlashlight: onToggleFlashlight,
            onSnapshotFlash: { showSnapshotFlash = true },
            onMore: openMoreOrAudioRoute,
            onEndCall: onEndCall
        )
    }

    private var reconnectingBadge: some View {
        Group {
            if uiState.phase == .inCall && showConnectionStatusBadge {
                VStack(spacing: 4) {
                    Text(str(.callReconnecting))
                        .font(.system(size: 14, weight: .medium))
                    if uiState.connectionStatus == .retrying {
                        Text(str(.callTakingLongerThanUsual))
                            .font(.system(size: 12, weight: .regular))
                    }
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.72))
                .clipShape(Capsule())
                .padding(.top, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
    }

    private var debugOverlay: some View {
        Group {
            if config.debugOverlayEnabled {
                Color.clear
                    .frame(width: 72, height: 72)
                    .contentShape(Rectangle())
                    .onTapGesture { handleDebugTap() }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

                if showDebugPanel {
                    FrontlineDebugPanel(uiState: uiState)
                        .padding(.leading, 16)
                        .padding(.top, 16)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
        }
    }

    private var snapshotFlashOverlay: some View {
        Group {
            if showSnapshotFlash {
                Color.white.opacity(0.86)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .task {
                        try? await Task.sleep(nanoseconds: 220_000_000)
                        showSnapshotFlash = false
                    }
            }
        }
    }

    private var moreSheet: some View {
        FrontlineMoreSheet(
            visible: isMoreSheetVisible && shouldShowMoreButton && !moreOpensAudioRouteDirectly,
            showsAudioRoute: showsAudioRoute,
            audioRouteDevice: currentAudioRoute,
            screenSharingEnabled: config.screenSharingEnabled && screenShareAvailable,
            inviteEnabled: config.inviteControlsEnabled,
            shareEnabled: config.inviteControlsEnabled && roomShareURL != nil,
            isScreenSharing: uiState.isScreenSharing,
            screenShareExtensionBundleId: screenShareExtensionBundleId,
            broadcastTriggerCount: $broadcastTriggerCount,
            strings: strings,
            onDismiss: { setMoreSheetVisible(false) },
            onAudioRoute: {
                withAnimation(frontlineSheetAnimation) {
                    isMoreSheetVisible = false
                    isAudioRouteSheetVisible = true
                }
            },
            onToggleScreenShare: {
                setMoreSheetVisible(false)
                onToggleScreenShare()
            },
            onStartBroadcastPicker: {
                broadcastTriggerCount += 1
            },
            onBroadcastPickerTriggered: {
                onToggleScreenShare()
                setMoreSheetVisible(false)
            },
            onInvite: {
                setMoreSheetVisible(false)
                Task { _ = await onInviteToRoom() }
            },
            onShare: {
                setMoreSheetVisible(false)
                showShareSheet = true
            }
        )
    }

    private var audioRouteSheet: some View {
        FrontlineAudioRouteSheet(
            visible: isAudioRouteSheetVisible,
            devices: audioRouteOptions,
            currentDevice: currentAudioRoute,
            strings: strings,
            onDismiss: { setAudioRouteSheetVisible(false) },
            onSelect: { device in
                setAudioRouteSheetVisible(false)
                onSelectAudioDevice(device)
            }
        )
    }

    private var localDisplayName: String {
        uiState.localDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? uiState.localDisplayName!
            : str(.frontlineYou)
    }

    private func handleDebugTap() {
        let now = Date()
        let didDoubleTap = lastDebugTapAt.map { now.timeIntervalSince($0) <= 0.45 } ?? false
        lastDebugTapAt = now
        guard didDoubleTap else { return }
        withAnimation(.easeInOut(duration: 0.18)) {
            showDebugPanel.toggle()
        }
    }

    private func openMoreOrAudioRoute() {
        withAnimation(frontlineSheetAnimation) {
            if moreOpensAudioRouteDirectly {
                isAudioRouteSheetVisible = true
            } else {
                isMoreSheetVisible = true
            }
        }
    }

    private func setMoreSheetVisible(_ visible: Bool) {
        withAnimation(frontlineSheetAnimation) {
            isMoreSheetVisible = visible
        }
    }

    private func setAudioRouteSheetVisible(_ visible: Bool) {
        withAnimation(frontlineSheetAnimation) {
            isAudioRouteSheetVisible = visible
        }
    }

    private func toggleRemoteVideoFit() {
        withAnimation(.easeInOut(duration: 0.2)) {
            remoteVideoFitCover.toggle()
        }
        onRemoteVideoFitChanged?(remoteVideoFitCover)
    }

    private func enterRemoteScreenShareFullscreen(_ source: FrontlineRemoteScreenShareSource) {
        withAnimation(.easeInOut(duration: 0.2)) {
            isMoreSheetVisible = false
            isAudioRouteSheetVisible = false
            showDebugPanel = false
            remoteScreenShareFullscreenSourceId = source.id
        }
    }

    private func updateLastVideoStartedParticipant() {
        let next = Dictionary(uniqueKeysWithValues: uiState.remoteParticipants.map { ($0.cid, $0.videoEnabled) })
        if !previousRemoteVideoEnabled.isEmpty {
            if let started = uiState.remoteParticipants.last(where: { $0.videoEnabled && previousRemoteVideoEnabled[$0.cid] != true }) {
                lastVideoStartedParticipantId = started.cid
            }
        }
        previousRemoteVideoEnabled = next
    }
}

func frontlinePipSize(containerSize: CGSize, inPanel: Bool) -> CGSize {
    if inPanel { return CGSize(width: 220, height: 280) }
    let referenceWidth = containerSize.width > containerSize.height ? containerSize.height : containerSize.width
    if referenceWidth >= 1100 { return CGSize(width: 172, height: 220) }
    if referenceWidth >= 720 { return CGSize(width: 152, height: 196) }
    if referenceWidth >= 480 { return CGSize(width: 120, height: 154) }
    return CGSize(width: 100, height: 128)
}

private func remoteDisplayName(_ remote: RemoteParticipant?) -> String {
    remote?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
}

private extension String {
    var frontlineContentSpotlightId: String { "\(frontlineContentSpotlightPrefix)\(self)" }
    var isFrontlineContentSpotlightId: Bool { hasPrefix(frontlineContentSpotlightPrefix) }
    var removingFrontlineContentSpotlightPrefix: String {
        hasPrefix(frontlineContentSpotlightPrefix)
            ? String(dropFirst(frontlineContentSpotlightPrefix.count))
            : self
    }
}

private struct FrontlineRemoteScreenShareFullscreenSurface: View {
    let source: FrontlineRemoteScreenShareSource
    let rendererProvider: CallRendererProvider
    let strings: [SerenadaString: String]?
    let onExit: () -> Void

    @State private var zoomScale: CGFloat = CGFloat(frontlineRemoteScreenShareMinZoomScale)
    @State private var panOffset: CGSize = .zero
    @State private var lastMagnificationValue: CGFloat = 1
    @State private var dragStartOffset: CGSize?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                frontlineBlack.ignoresSafeArea()
                WebRTCVideoView(
                    kind: source.videoKind,
                    rendererProvider: rendererProvider,
                    videoContentMode: .scaleAspectFit
                )
                .frame(width: geometry.size.width, height: geometry.size.height)
                .scaleEffect(zoomScale)
                .offset(panOffset)
                .contentShape(Rectangle())
                .simultaneousGesture(magnificationGesture(viewportSize: geometry.size))
                .simultaneousGesture(dragGesture(viewportSize: geometry.size))
                .ignoresSafeArea()

                FrontlineRemoteFitButton(
                    remoteVideoFitCover: true,
                    strings: strings,
                    onClick: onExit
                )
                .padding(.trailing, 16)
                .padding(.bottom, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .clipped()
        }
        .background(frontlineBlack)
        .ignoresSafeArea()
        .onChange(of: source.id) { _ in resetTransform() }
    }

    private func magnificationGesture(viewportSize: CGSize) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                let delta = value / max(lastMagnificationValue, 0.001)
                lastMagnificationValue = value
                setZoomScale(
                    CGFloat(frontlineRemoteScreenShareZoomScale(currentScale: Double(zoomScale), change: Double(delta))),
                    viewportSize: viewportSize
                )
            }
            .onEnded { _ in
                lastMagnificationValue = 1
                clampPanOffset(viewportSize: viewportSize)
            }
    }

    private func dragGesture(viewportSize: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if dragStartOffset == nil {
                    dragStartOffset = panOffset
                }
                let startOffset = dragStartOffset ?? .zero
                let next = frontlineRemoteScreenSharePanOffset(
                    currentX: Double(startOffset.width),
                    currentY: Double(startOffset.height),
                    deltaX: Double(value.translation.width),
                    deltaY: Double(value.translation.height),
                    scale: Double(zoomScale),
                    viewportWidth: Double(viewportSize.width),
                    viewportHeight: Double(viewportSize.height)
                )
                panOffset = CGSize(width: CGFloat(next.x), height: CGFloat(next.y))
            }
            .onEnded { _ in
                dragStartOffset = nil
                clampPanOffset(viewportSize: viewportSize)
            }
    }

    private func setZoomScale(_ nextScale: CGFloat, viewportSize: CGSize) {
        zoomScale = nextScale
        clampPanOffset(viewportSize: viewportSize)
    }

    private func clampPanOffset(viewportSize: CGSize) {
        let clamped = frontlineRemoteScreenSharePanOffset(
            currentX: Double(panOffset.width),
            currentY: Double(panOffset.height),
            deltaX: 0,
            deltaY: 0,
            scale: Double(zoomScale),
            viewportWidth: Double(viewportSize.width),
            viewportHeight: Double(viewportSize.height)
        )
        panOffset = CGSize(width: CGFloat(clamped.x), height: CGFloat(clamped.y))
    }

    private func resetTransform() {
        zoomScale = CGFloat(frontlineRemoteScreenShareMinZoomScale)
        panOffset = .zero
        lastMagnificationValue = 1
        dragStartOffset = nil
    }
}

private struct FrontlinePhaseSurface: View {
    let sessionPhase: SerenadaCallPhase
    let localDisplayName: String?
    let strings: [SerenadaString: String]?
    let onRequestPermissions: () -> Void
    let onDismiss: (() -> Void)?

    var body: some View {
        VStack(spacing: 22) {
            FrontlineLocalAvatar(size: 124, fontSize: 48, displayName: localDisplayName, strings: strings)

            Text(title)
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)

            if sessionPhase == .awaitingPermissions {
                Button(resolveString(.callGrantAccess, overrides: strings), action: onRequestPermissions)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(frontlineAccent)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            } else if sessionPhase == .error, let onDismiss {
                Button(resolveString(.callDismiss, overrides: strings), action: onDismiss)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(frontlineBlack)
    }

    private var title: String {
        switch sessionPhase {
        case .error:
            return resolveString(.callErrorGeneric, overrides: strings)
        case .waiting:
            return resolveString(.frontlineWaiting, overrides: strings)
        default:
            return resolveString(.frontlineWaiting, overrides: strings)
        }
    }
}

private struct FrontlineAudioLarge: View {
    let remote: RemoteParticipant?
    let startedAtMs: Int64?
    let strings: [SerenadaString: String]?
    let onSystemPictureInPictureSourceFrameChanged: ((CGRect?) -> Void)?

    var body: some View {
        let name = remoteDisplayName(remote)
        VStack(spacing: 0) {
            FrontlineAvatar(peerId: remote?.peerId, displayName: name, size: 140, fontSize: 58)
            Spacer().frame(height: 18)
            HStack(spacing: 10) {
                FrontlineAudioIndicator(
                    muted: remote?.audioEnabled == false,
                    audioLevel: remote?.audioLevel ?? 0,
                    size: 22
                )
                if !name.isEmpty {
                    Text(name)
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            Spacer().frame(height: 10)
            FrontlineTimerLabel(startedAtMs: startedAtMs)
        }
        .systemPictureInPictureSourceFrame(onChange: onSystemPictureInPictureSourceFrameChanged)
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(frontlineBlack)
    }
}

func formatCallElapsed(startedAtMs: Int64?, fallbackStartedAtMs: Int64, nowMs: Int64) -> String {
    let startedAt = startedAtMs ?? fallbackStartedAtMs
    let elapsedSeconds = max(0, (nowMs - startedAt) / 1000)
    return String(format: "%02lld:%02lld", elapsedSeconds / 60, elapsedSeconds % 60)
}

private struct FrontlineTimerLabel: View {
    let startedAtMs: Int64?
    @State private var nowMs = Int64(Date().timeIntervalSince1970 * 1000)
    @State private var fallbackStartedAtMs = Int64(Date().timeIntervalSince1970 * 1000)

    var body: some View {
        Text(elapsedLabel)
            .font(.system(size: 28, weight: .medium))
            .foregroundStyle(frontlineDim)
            .monospacedDigit()
            .task(id: startedAtMs) {
                fallbackStartedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
                while !Task.isCancelled {
                    nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
    }

    private var elapsedLabel: String {
        formatCallElapsed(
            startedAtMs: startedAtMs,
            fallbackStartedAtMs: fallbackStartedAtMs,
            nowMs: nowMs
        )
    }
}

private struct FrontlinePipView: View {
    let feed: FrontlineFeed
    let uiState: CallUiState
    let remote: RemoteParticipant?
    let rendererProvider: CallRendererProvider
    let size: CGSize
    let showSwapHint: Bool
    let strings: [SerenadaString: String]?
    let onClick: () -> Void

    var body: some View {
        let showsLocal = feed == .local
        ZStack(alignment: .bottomLeading) {
            frontlineSurface

            if showsLocal && uiState.localVideoEnabled {
                WebRTCVideoView(
                    kind: .local,
                    rendererProvider: rendererProvider,
                    videoContentMode: uiState.isScreenSharing ? .scaleAspectFit : .scaleAspectFill,
                    isMirrored: uiState.isFrontCamera && !uiState.isScreenSharing
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            } else if !showsLocal, let remote, remote.videoEnabled {
                WebRTCVideoView(
                    kind: .remoteForCid(remote.cid),
                    rendererProvider: rendererProvider,
                    videoContentMode: .scaleAspectFill
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            } else {
                if showsLocal {
                    FrontlineLocalAvatar(size: 74, fontSize: 34, displayName: uiState.localDisplayName, strings: strings)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    FrontlineAvatar(
                        peerId: remote?.peerId,
                        displayName: remoteDisplayName(remote),
                        size: 74,
                        fontSize: 30
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }

            FrontlineNameChip(
                label: showsLocal
                    ? (uiState.localDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
                        ? uiState.localDisplayName!
                        : resolveString(.frontlineYou, overrides: strings))
                    : remoteDisplayName(remote),
                muted: showsLocal ? !uiState.localAudioEnabled : remote?.audioEnabled == false,
                audioLevel: showsLocal ? uiState.localAudioLevel : remote?.audioLevel ?? 0,
                compact: true
            )
            .padding(6)

            if showSwapHint {
                Image(systemName: "camera.rotate.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(Color.black.opacity(0.62))
                    .clipShape(Circle())
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(
                    showsLocal && uiState.localVideoEnabled ? frontlineAccent : Color.white.opacity(0.4),
                    lineWidth: showsLocal && uiState.localVideoEnabled ? frontlinePipVideoAccentLineWidth : frontlinePipBorderLineWidth
                )
        )
        .shadow(color: .black.opacity(0.32), radius: 8, y: 4)
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture(perform: onClick)
    }
}

private struct FrontlineControlsPanel<Pip: View>: View {
    let uiState: CallUiState
    let isLandscape: Bool
    let isTabletLandscape: Bool
    let panelWidth: CGFloat
    let callControlsEnabled: Bool
    let videoControlsEnabled: Bool
    let showMoreButton: Bool
    let snapshotSource: SnapshotSource?
    let snapshotHandler: ((SnapshotSource) -> Void)?
    let reservePreviewActions: Bool
    let pipInPanel: Bool
    @ViewBuilder let pip: () -> Pip
    let strings: [SerenadaString: String]?
    let onVideoTap: () -> Void
    let onToggleAudio: () -> Void
    let onFlipCamera: () -> Void
    let onToggleFlashlight: () -> Void
    let onSnapshotFlash: () -> Void
    let onMore: () -> Void
    let onEndCall: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            if pipInPanel {
                VStack {
                    pip()
                    Spacer()
                }
                .frame(height: 300)
            } else if isLandscape {
                Spacer(minLength: 0)
            }

            if callControlsEnabled {
                previewActions
                controlGrid
            } else {
                Spacer().frame(height: isLandscape ? 24 : 12)
            }

            Spacer().frame(height: isLandscape ? 20 : 12)
            FrontlineEndButton(strings: strings, onClick: onEndCall)

            if isLandscape && !pipInPanel {
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, isLandscape ? 12 : 16)
        .padding(.top, isLandscape ? 8 : 14)
        .padding(.bottom, isLandscape ? 16 : 24)
        .background(frontlinePanel)
    }

    private var previewActions: some View {
        let visible = uiState.localVideoEnabled
        let rowHeight: CGFloat = isLandscape ? 84 : 92
        return Group {
            if visible || reservePreviewActions {
                HStack(spacing: 22) {
                    FrontlineRoundActionButton(
                        visible: visible,
                        size: 56,
                        systemImage: uiState.isFlashEnabled ? "flashlight.on.fill" : "flashlight.off.fill",
                        active: uiState.isFlashEnabled && uiState.isFlashAvailable,
                        enabled: uiState.isFlashAvailable,
                        accessibilityLabel: uiState.isFlashEnabled ? resolveString(.callA11yFlashlightOn, overrides: strings) : resolveString(.callA11yFlashlightOff, overrides: strings),
                        onClick: onToggleFlashlight
                    )

                    FrontlineRoundActionButton(
                        visible: visible && snapshotSource != nil && snapshotHandler != nil,
                        size: 72,
                        systemImage: "camera.fill",
                        primary: true,
                        accessibilityLabel: resolveString(.callA11yTakeSnapshot, overrides: strings),
                        onClick: {
                            guard let snapshotSource, let snapshotHandler else { return }
                            onSnapshotFlash()
                            snapshotHandler(snapshotSource)
                        }
                    )
                    .accessibilityIdentifier("call.frontline.takeSnapshot")

                    FrontlineRoundActionButton(
                        visible: visible && uiState.availableCameraModes.count > 1,
                        size: 56,
                        systemImage: "camera.rotate.fill",
                        accessibilityLabel: resolveString(.frontlineFlipCamera, overrides: strings),
                        onClick: onFlipCamera
                    )
                }
                .frame(maxWidth: .infinity)
                .frame(height: rowHeight)
                .padding(.bottom, isLandscape ? 12 : 14)
                .opacity(visible ? 1 : 0)
            }
        }
    }

    private var controlGrid: some View {
        let buttonHeight: CGFloat = {
            if isTabletLandscape || (!isLandscape && panelWidth >= 320) { return 86 }
            return isLandscape ? 68 : 74
        }()
        let moreWidth = buttonHeight / frontlineMoreButtonHeightToWidthRatio
        return HStack(spacing: 8) {
            if videoControlsEnabled {
                FrontlineGridButton(
                    label: uiState.localVideoEnabled
                        ? resolveString(.frontlineVideoOn, overrides: strings)
                        : resolveString(.frontlineVideo, overrides: strings),
                    systemImage: uiState.localVideoEnabled ? "video.fill" : "video.slash.fill",
                    active: uiState.localVideoEnabled,
                    onClick: onVideoTap
                )
                .frame(height: buttonHeight)
            }

            FrontlineGridButton(
                label: resolveString(.frontlineMute, overrides: strings),
                systemImage: uiState.localAudioEnabled ? "mic.fill" : "mic.slash.fill",
                danger: !uiState.localAudioEnabled,
                onClick: onToggleAudio
            )
            .frame(height: buttonHeight)

            if showMoreButton {
                FrontlineGridButton(
                    label: resolveString(.frontlineMore, overrides: strings),
                    systemImage: "ellipsis",
                    showLabel: false,
                    onClick: onMore
                )
                .frame(width: moreWidth, height: buttonHeight)
                .accessibilityIdentifier("call.frontline.more")
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct FrontlineGridButton: View {
    let label: String
    let systemImage: String
    var active = false
    var danger = false
    var showLabel = true
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: showLabel ? 4 : 0) {
                Image(systemName: systemImage)
                    .font(.system(size: 24, weight: .bold))
                if showLabel {
                    Text(label.uppercased())
                        .font(.system(size: 13, weight: .heavy))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
            }
            .foregroundStyle(active ? Color.black : Color.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 8)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(frontlineBorder, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var background: Color {
        if active { return frontlineAccent }
        if danger { return frontlineDanger }
        return frontlineSurface
    }
}

private struct FrontlineRoundActionButton: View {
    let visible: Bool
    let size: CGFloat
    let systemImage: String
    var active = false
    var primary = false
    var enabled = true
    let accessibilityLabel: String
    let onClick: () -> Void

    var body: some View {
        Group {
            if visible {
                Button(action: onClick) {
                    Image(systemName: systemImage)
                        .font(.system(size: primary ? 30 : 24, weight: .bold))
                        .foregroundStyle(tint)
                        .frame(width: size, height: size)
                        .background(background)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white.opacity(primary ? 0.45 : (enabled ? 0.28 : 0.12)), lineWidth: primary ? 4 : 1))
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel(accessibilityLabel)
            } else {
                Color.clear.frame(width: size, height: size)
            }
        }
    }

    private var background: Color {
        if primary { return Color.white.opacity(0.95) }
        if active { return .white }
        if !enabled { return Color.black.opacity(0.28) }
        return Color.black.opacity(0.58)
    }

    private var tint: Color {
        if primary || active { return .black }
        if enabled { return .white }
        return .white.opacity(0.42)
    }
}

private struct FrontlineEndButton: View {
    let strings: [SerenadaString: String]?
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 8) {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 20, weight: .bold))
                Text(resolveString(.frontlineEnd, overrides: strings).uppercased())
                    .font(.system(size: 14, weight: .heavy))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background(frontlineDanger)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .shadow(color: .black.opacity(0.28), radius: 12, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("call.frontline.endCall")
        .accessibilityLabel(resolveString(.callA11yEndCall, overrides: strings))
    }
}

private struct FrontlineRemoteFitButton: View {
    let remoteVideoFitCover: Bool
    let strings: [SerenadaString: String]?
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Image(systemName: remoteVideoFitCover
                ? "arrow.down.right.and.arrow.up.left"
                : "arrow.up.left.and.arrow.down.right")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Color.black.opacity(0.4))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(resolveString(
            remoteVideoFitCover ? .callA11yVideoFit : .callA11yVideoFill,
            overrides: strings
        ))
    }
}

private struct FrontlineBottomSheetContainer<Content: View>: View {
    let visible: Bool
    let onDismiss: () -> Void
    private let content: Content

    init(visible: Bool, onDismiss: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        self.visible = visible
        self.onDismiss = onDismiss
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if visible {
                Color.black.opacity(0.5)
                    .ignoresSafeArea()
                    .onTapGesture(perform: onDismiss)
                    .transition(.opacity)

                content
                    .transition(.move(edge: .bottom))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .allowsHitTesting(visible)
    }
}

private struct FrontlineMoreSheet: View {
    let visible: Bool
    let showsAudioRoute: Bool
    let audioRouteDevice: AudioDevice?
    let screenSharingEnabled: Bool
    let inviteEnabled: Bool
    let shareEnabled: Bool
    let isScreenSharing: Bool
    let screenShareExtensionBundleId: String?
    @Binding var broadcastTriggerCount: Int
    let strings: [SerenadaString: String]?
    let onDismiss: () -> Void
    let onAudioRoute: () -> Void
    let onToggleScreenShare: () -> Void
    let onStartBroadcastPicker: () -> Void
    let onBroadcastPickerTriggered: () -> Void
    let onInvite: () -> Void
    let onShare: () -> Void

    var body: some View {
        FrontlineBottomSheetContainer(visible: visible, onDismiss: onDismiss) {
            VStack(spacing: 0) {
                Capsule()
                    .fill(Color.white.opacity(0.24))
                    .frame(width: 36, height: 4)
                    .padding(.top, 18)
                    .padding(.bottom, 18)

                if showsAudioRoute {
                    FrontlineSheetItem(
                        systemImage: callAudioRouteSystemImage(audioRouteDevice?.kind),
                        title: audioRouteDevice.map { callAudioRouteLabel($0, strings: strings) }
                            ?? resolveString(.callAudioRoute, overrides: strings),
                        onClick: onAudioRoute
                    )
                    .accessibilityIdentifier("call.frontline.more.audio")
                }

                if screenSharingEnabled {
                    FrontlineSheetItem(
                        systemImage: isScreenSharing ? "rectangle.on.rectangle.slash" : "rectangle.on.rectangle",
                        title: isScreenSharing
                            ? resolveString(.frontlineStopScreenShare, overrides: strings)
                            : resolveString(.frontlineShareScreen, overrides: strings),
                        danger: isScreenSharing,
                        onClick: screenShareAction
                    )
                    .overlay {
                        if shouldUseBroadcastPicker(isScreenSharing: isScreenSharing, screenShareExtensionBundleId: screenShareExtensionBundleId),
                           let screenShareExtensionBundleId {
                            FrontlineBroadcastTrigger(
                                preferredExtension: screenShareExtensionBundleId,
                                triggerCount: broadcastTriggerCount,
                                onTriggered: onBroadcastPickerTriggered
                            )
                            .frame(width: 1, height: 1)
                            .allowsHitTesting(false)
                        }
                    }
                }

                if inviteEnabled {
                    FrontlineSheetItem(
                        systemImage: "bell.badge.fill",
                        title: resolveString(.callInviteToRoom, overrides: strings),
                        onClick: onInvite
                    )
                }

                if shareEnabled {
                    FrontlineSheetItem(
                        systemImage: "square.and.arrow.up",
                        title: resolveString(.callShareInvitation, overrides: strings),
                        onClick: onShare
                    )
                }

                Button(action: onDismiss) {
                    HStack(spacing: 8) {
                        Image(systemName: "xmark")
                        Text(resolveString(.frontlineClose, overrides: strings))
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .padding(.top, 12)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 18)
            .background(frontlineSheet)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .ignoresSafeArea(edges: .bottom)
        }
    }

    private func screenShareAction() {
        if shouldUseBroadcastPicker(isScreenSharing: isScreenSharing, screenShareExtensionBundleId: screenShareExtensionBundleId) {
            onStartBroadcastPicker()
        } else {
            onToggleScreenShare()
        }
    }
}

private struct FrontlineAudioRouteSheet: View {
    let visible: Bool
    let devices: [AudioDevice]
    let currentDevice: AudioDevice?
    let strings: [SerenadaString: String]?
    let onDismiss: () -> Void
    let onSelect: (AudioDevice) -> Void

    var body: some View {
        FrontlineBottomSheetContainer(visible: visible, onDismiss: onDismiss) {
            VStack(spacing: 0) {
                Capsule()
                    .fill(Color.white.opacity(0.24))
                    .frame(width: 36, height: 4)
                    .padding(.top, 18)
                    .padding(.bottom, 18)

                Text(resolveString(.callAudioRoute, overrides: strings))
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.bottom, 10)

                ForEach(devices, id: \.self) { device in
                    FrontlineAudioRouteItem(
                        device: device,
                        selected: isSelected(device),
                        strings: strings,
                        onClick: { onSelect(device) }
                    )
                }

                Button(action: onDismiss) {
                    HStack(spacing: 8) {
                        Image(systemName: "xmark")
                        Text(resolveString(.frontlineClose, overrides: strings))
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .padding(.top, 12)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 18)
            .background(frontlineSheet)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .ignoresSafeArea(edges: .bottom)
        }
    }

    private func isSelected(_ device: AudioDevice) -> Bool {
        if let currentDevice {
            return callAudioRouteKey(device) == callAudioRouteKey(currentDevice)
        }
        return device.status == .active
    }
}

private struct FrontlineAudioRouteItem: View {
    let device: AudioDevice
    let selected: Bool
    let strings: [SerenadaString: String]?
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                Image(systemName: callAudioRouteSystemImage(device.kind))
                    .font(.system(size: 22, weight: .semibold))
                    .frame(width: 38, height: 38)

                Text(callAudioRouteLabel(device, strings: strings))
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "checkmark")
                    .font(.system(size: 20, weight: .bold))
                    .opacity(selected ? 1 : 0)
                    .frame(width: 24, height: 24)
            }
            .foregroundStyle(selected ? Color.black : Color.white)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(selected ? frontlineAccent : frontlineSheetRow)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.vertical, 5)
        .accessibilityLabel(callAudioRouteLabel(device, strings: strings))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct FrontlineSheetItem: View {
    let systemImage: String
    let title: String
    var danger = false
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)

                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
            .background(danger ? frontlineDanger : frontlineSheetRow)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.vertical, 5)
    }
}

private struct FrontlineBroadcastTrigger: UIViewRepresentable {
    let preferredExtension: String
    let triggerCount: Int
    let onTriggered: () -> Void

    func makeUIView(context: Context) -> FrontlineBroadcastPickerContainerView {
        FrontlineBroadcastPickerContainerView(preferredExtension: preferredExtension)
    }

    func updateUIView(_ uiView: FrontlineBroadcastPickerContainerView, context: Context) {
        uiView.preferredExtension = preferredExtension
        uiView.triggerIfNeeded(triggerCount, onTriggered: onTriggered)
    }
}

private final class FrontlineBroadcastPickerContainerView: UIView {
    private let pickerView = RPSystemBroadcastPickerView(frame: .zero)
    private var lastTriggerCount = 0

    var preferredExtension: String {
        get { pickerView.preferredExtension ?? "" }
        set { pickerView.preferredExtension = newValue }
    }

    init(preferredExtension: String) {
        super.init(frame: .zero)
        pickerView.preferredExtension = preferredExtension
        pickerView.showsMicrophoneButton = false
        pickerView.alpha = 0.02
        addSubview(pickerView)
        pickerView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            pickerView.leadingAnchor.constraint(equalTo: leadingAnchor),
            pickerView.trailingAnchor.constraint(equalTo: trailingAnchor),
            pickerView.topAnchor.constraint(equalTo: topAnchor),
            pickerView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func triggerIfNeeded(_ triggerCount: Int, onTriggered: @escaping () -> Void) {
        guard triggerCount != lastTriggerCount else { return }
        lastTriggerCount = triggerCount
        DispatchQueue.main.async { [weak self] in
            guard let button = self?.pickerView.subviews.compactMap({ $0 as? UIButton }).first else { return }
            button.sendActions(for: .touchUpInside)
            // Starting the SDK path can flip SwiftUI state and remove this picker host.
            // Do it only after ReplayKit has received the tap.
            onTriggered()
        }
    }
}

/// Name label for a content (screen share) tile: identifies whose screen it is.
/// Content carries no audio, so this shows a screen-share glyph instead of the
/// mic/audio indicator used by `FrontlineNameChip`.
private struct FrontlineContentNameChip: View {
    let label: String
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 5 : 7) {
            Image(systemName: "rectangle.on.rectangle")
                .font(.system(size: compact ? 10 : 11, weight: .semibold))
                .foregroundStyle(.white)
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: compact ? 11 : 12, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, compact ? 7 : 9)
        .padding(.vertical, compact ? 4 : 5)
        .background(Color.black.opacity(0.62))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Color.white.opacity(0.16), lineWidth: 1))
    }
}

private struct FrontlineNameChip: View {
    let label: String
    let muted: Bool
    let audioLevel: Float
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 5 : 7) {
            FrontlineAudioIndicator(muted: muted, audioLevel: audioLevel, size: 14)
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: compact ? 11 : 12, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, compact ? 7 : 9)
        .padding(.vertical, compact ? 4 : 5)
        .background(Color.black.opacity(0.62))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Color.white.opacity(0.16), lineWidth: 1))
    }
}

private struct FrontlineAudioIndicator: View {
    let muted: Bool
    let audioLevel: Float
    let size: CGFloat

    var body: some View {
        if muted {
            Image(systemName: "mic.slash.fill")
                .font(.system(size: size * 0.8, weight: .bold))
                .foregroundStyle(frontlineDanger)
                .frame(width: size, height: size)
        } else {
            AudioActivityIndicator(level: audioLevel, size: size)
        }
    }
}

private struct FrontlineAvatar: View {
    let peerId: String?
    let displayName: String?
    let size: CGFloat
    let fontSize: CGFloat

    var body: some View {
        RemoteAvatarView(peerId: peerId, displayName: displayName, size: size)
            .frame(width: size, height: size)
    }
}

private struct FrontlineLocalAvatar: View {
    let size: CGFloat
    let fontSize: CGFloat
    let displayName: String?
    let strings: [SerenadaString: String]?

    var body: some View {
        ZStack {
            Circle().fill(Color(red: 0x2A / 255, green: 0x35 / 255, blue: 0x40 / 255))
            Text(label)
                .font(.system(size: fontSize, weight: .heavy))
                .foregroundStyle(.white)
                .minimumScaleFactor(0.4)
        }
        .frame(width: size, height: size)
    }

    private var label: String {
        let initials = initialsFor(displayName: displayName)
        return initials.isEmpty ? resolveString(.frontlineYou, overrides: strings) : initials
    }
}

/// Stream-keyed filmstrip + spotlight stage for active INDEPENDENT content on the
/// Frontline screen.
///
/// Every stream is its own tile keyed `{cid, kind}`: a camera tile per camera-on
/// participant (local + remote) and a content tile per independent sharer (local +
/// remote, including the local user's own screen). The sharer's camera and screen
/// are TWO EQUAL peer tiles — NOT a camera-PIP-on-content. A single spotlight is
/// chosen by ``FrontlineCallScreenView/frontlineStageSpotlightId`` (pin → select →
/// recency → resolver primary). Tap any tile to SELECT it as the spotlight;
/// long-press to PIN/unpin (Frontline's existing pin/select model, now expressed
/// through the shared {cid,kind} ids). Geometry reuses the conformance-locked
/// ``computeLayout`` via a composite-id FOCUS scene; the lone-tile edge is emitted
/// directly (the engine would otherwise derive `.solo`). Only ever instantiated
/// flag-on with a real independent content track.
private struct FrontlineStreamKeyedStage: View {
    let uiState: CallUiState
    let localSpotlightId: String
    let tiles: [StageTile]
    let spotlightId: String?
    let contentScene: ContentScene
    @Binding var remoteTileAspectRatios: [String: CGFloat]
    @Binding var pinnedSpotlightId: String?
    @Binding var selectedSpotlightId: String?
    @Binding var remoteVideoFitCover: Bool
    let rendererProvider: CallRendererProvider
    let strings: [SerenadaString: String]?
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onRemoteVideoFitChanged: ((Bool) -> Void)?
    let onRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Void

    @State private var lastMagnificationValue: CGFloat = 1

    private func str(_ key: SerenadaString) -> String {
        resolveString(key, overrides: strings)
    }

    private func resolvedContent(for cid: String) -> ResolvedContent? {
        if let local = contentScene.local, local.ownerCid == cid { return local }
        return contentScene.remotes.first(where: { $0.ownerCid == cid })
    }

    private func remote(for cid: String) -> RemoteParticipant? {
        uiState.remoteParticipants.first(where: { $0.cid == cid })
    }

    private func isRemoteScreenShareContentTile(_ id: String) -> Bool {
        guard let key = parseStageTileId(id), key.kind == .content, key.cid != localSpotlightId else { return false }
        return resolvedContent(for: key.cid)?.type == .screenShare
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                frontlineBlack
                if let spotlightId, !tiles.isEmpty, let layoutTiles = computedTiles(spotlightId: spotlightId, geometry: geometry) {
                    ForEach(layoutTiles.sorted(by: { $0.zOrder < $1.zOrder }), id: \.id) { tile in
                        tileView(tile, spotlightId: spotlightId)
                            .frame(width: tile.frame.width, height: tile.frame.height)
                            .clipShape(RoundedRectangle(cornerRadius: tile.cornerRadius))
                            .position(x: tile.frame.x + tile.frame.width / 2, y: tile.frame.y + tile.frame.height / 2)
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .background(frontlineBlack)
        }
    }

    private func computedTiles(spotlightId: String, geometry: GeometryProxy) -> [TileLayout]? {
        let spotlightIsRemoteScreenShare = isRemoteScreenShareContentTile(spotlightId)
        let spotlightFit: FitMode = frontlineRemoteScreenShareUsesFit(
            isRemoteScreenShare: spotlightIsRemoteScreenShare,
            remoteVideoFitCover: remoteVideoFitCover
        ) ? .contain : .cover
        if tiles.count == 1 {
            return [TileLayout(
                id: spotlightId,
                type: .participant,
                frame: LayoutRect(x: 0, y: 0, width: geometry.size.width, height: geometry.size.height),
                fit: spotlightFit,
                cornerRadius: 0,
                zOrder: 0
            )]
        }
        let participants: [SceneParticipant] = tiles.map { tile in
            SceneParticipant(
                id: tile.id,
                role: tile.id == spotlightId ? .local : .remote,
                videoEnabled: true,
                videoAspectRatio: tile.kind == .camera ? remoteTileAspectRatios[tile.cid] : nil
            )
        }
        let scene = CallScene(
            viewportWidth: geometry.size.width,
            viewportHeight: geometry.size.height,
            safeAreaInsets: LayoutInsets(),
            participants: participants,
            localParticipantId: spotlightId,
            activeSpeakerId: nil,
            pinnedParticipantId: spotlightId,
            contentSource: nil,
            userPrefs: UserLayoutPrefs(dominantFit: spotlightFit)
        )
        return computeLayout(scene: scene).tiles
    }

    @ViewBuilder
    private func tileView(_ tile: TileLayout, spotlightId: String) -> some View {
        if let key = parseStageTileId(tile.id) {
            let isLocal = key.cid == localSpotlightId
            let isContent = key.kind == .content
            let isSpotlight = tile.zOrder == 0
            let pinned = tile.id == pinnedSpotlightId
            let localZoomEnabled = isContent && isLocal && frontlineAllowsLocalCameraZoom(
                phase: uiState.phase,
                localVideoEnabled: uiState.localVideoEnabled,
                localCameraMode: uiState.localCameraMode,
                isScreenSharing: uiState.isScreenSharing
            )
            let tileShape = RoundedRectangle(cornerRadius: tile.cornerRadius)

            ZStack(alignment: .bottomLeading) {
                frontlineSurface

                if isContent {
                    contentTileBody(key: key)
                    if localZoomEnabled {
                        Color.clear
                            .contentShape(tileShape)
                            .accessibilityHidden(true)
                            .simultaneousGesture(localZoomGesture(enabled: true))
                    }
                } else {
                    cameraTileBody(isLocal: isLocal, cid: key.cid, fit: tile.fit)
                }

                if pinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 28, height: 28)
                        .background(Color.black.opacity(0.62))
                        .clipShape(Circle())
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }

                if !isContent {
                    FrontlineNameChip(
                        label: cameraName(isLocal: isLocal, cid: key.cid),
                        muted: isLocal ? !uiState.localAudioEnabled : remote(for: key.cid)?.audioEnabled == false,
                        audioLevel: isLocal ? uiState.localAudioLevel : remote(for: key.cid)?.audioLevel ?? 0,
                        compact: true
                    )
                    .padding(6)
                } else {
                    // Content (screen share) tile: label whose screen this is.
                    // Content carries no audio, so no mic indicator.
                    FrontlineContentNameChip(
                        label: cameraName(isLocal: isLocal, cid: key.cid),
                        compact: true
                    )
                    .padding(6)
                }

                if isSpotlight && !isLocal && isContent && resolvedContent(for: key.cid)?.type == .screenShare {
                    FrontlineRemoteFitButton(
                        remoteVideoFitCover: false,
                        strings: strings,
                        onClick: { onRemoteScreenShareFullscreen(.independent(ownerCid: key.cid)) }
                    )
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                } else if isSpotlight && !isLocal && remote(for: key.cid)?.videoEnabled == true {
                    FrontlineRemoteFitButton(
                        remoteVideoFitCover: remoteVideoFitCover,
                        strings: strings,
                        onClick: toggleRemoteVideoFit
                    )
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
            }
            .clipShape(tileShape)
            .overlay {
                if !isContent && isLocal && uiState.localVideoEnabled {
                    tileShape
                        .strokeBorder(frontlineAccent, lineWidth: frontlinePipVideoAccentLineWidth)
                        .allowsHitTesting(false)
                }
            }
            .contentShape(tileShape)
            .onTapGesture { selectedSpotlightId = tile.id }
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5)
                    .onEnded { _ in
                        pinnedSpotlightId = tile.id == pinnedSpotlightId ? nil : tile.id
                    }
            )
        }
    }

    @ViewBuilder
    private func contentTileBody(key: StageTileKey) -> some View {
        let owner = resolvedContent(for: key.cid)
        let isLocal = key.cid == localSpotlightId
        let isRemoteScreenShare = !isLocal && owner?.type == .screenShare
        WebRTCVideoView(
            kind: isLocal ? .localContent : .remoteContentForCid(key.cid),
            rendererProvider: rendererProvider,
            videoContentMode: frontlineRemoteScreenShareUsesFit(
                isRemoteScreenShare: isRemoteScreenShare,
                remoteVideoFitCover: remoteVideoFitCover
            ) ? .scaleAspectFit : .scaleAspectFill
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        if let owner, owner.loading || owner.waitingForParticipants {
            FrontlineContentStatusOverlay(
                text: owner.waitingForParticipants
                    ? str(.callContentWaitingForParticipants)
                    : str(.callContentConnecting)
            )
        }
    }

    @ViewBuilder
    private func cameraTileBody(isLocal: Bool, cid: String, fit: FitMode) -> some View {
        if isLocal {
            if uiState.localVideoEnabled {
                WebRTCVideoView(
                    kind: .local,
                    rendererProvider: rendererProvider,
                    videoContentMode: fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                    isMirrored: uiState.isFrontCamera && !uiState.isScreenSharing,
                    onVideoSizeChanged: { remoteTileAspectRatios[localSpotlightId] = quantizedFrontlineAspectRatio($0) }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                FrontlineLocalAvatar(size: 86, fontSize: 34, displayName: uiState.localDisplayName, strings: strings)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else if let remote = remote(for: cid) {
            if remote.videoEnabled {
                WebRTCVideoView(
                    kind: .remoteForCid(cid),
                    rendererProvider: rendererProvider,
                    videoContentMode: fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                    onVideoSizeChanged: { remoteTileAspectRatios[cid] = quantizedFrontlineAspectRatio($0) }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                FrontlineAvatar(peerId: remote.peerId, displayName: remoteDisplayName(remote), size: 86, fontSize: 32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func cameraName(isLocal: Bool, cid: String) -> String {
        if isLocal {
            return uiState.localDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
                ? uiState.localDisplayName!
                : str(.frontlineYou)
        }
        return remoteDisplayName(remote(for: cid))
    }

    private func toggleRemoteVideoFit() {
        withAnimation(.easeInOut(duration: 0.2)) {
            remoteVideoFitCover.toggle()
        }
        onRemoteVideoFitChanged?(remoteVideoFitCover)
    }

    private func localZoomGesture(enabled: Bool) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard enabled else { return }
                let delta = value / max(lastMagnificationValue, 0.001)
                lastMagnificationValue = value
                guard abs(delta - 1) > 0.01 else { return }
                onAdjustCameraZoom(delta)
            }
            .onEnded { _ in lastMagnificationValue = 1 }
    }
}

private struct FrontlineMultiPartyStage: View {
    let uiState: CallUiState
    let localSpotlightId: String
    let activeContentSpotlightId: String?
    let localContentMode: Bool
    @Binding var remoteTileAspectRatios: [String: CGFloat]
    @Binding var pinnedSpotlightId: String?
    @Binding var selectedSpotlightId: String?
    let lastVideoStartedParticipantId: String?
    @Binding var remoteVideoFitCover: Bool
    let rendererProvider: CallRendererProvider
    let strings: [SerenadaString: String]?
    let onAdjustCameraZoom: (CGFloat) -> Void
    let onRemoteVideoFitChanged: ((Bool) -> Void)?
    let onRemoteScreenShareFullscreen: (FrontlineRemoteScreenShareSource) -> Void

    @State private var lastMagnificationValue: CGFloat = 1
    @State private var localAspectRatio: CGFloat?

    var body: some View {
        GeometryReader { geometry in
            let layout = computeFrontlineLayout(geometry: geometry)
            ZStack {
                ForEach(layout.tiles.sorted(by: { $0.zOrder < $1.zOrder }), id: \.id) { tile in
                    tileView(tile)
                        .frame(width: tile.frame.width, height: tile.frame.height)
                        .clipShape(RoundedRectangle(cornerRadius: tile.cornerRadius))
                        .position(x: tile.frame.x + tile.frame.width / 2, y: tile.frame.y + tile.frame.height / 2)
                }

                if let pip = layout.localPip {
                    FrontlineLayoutTile(
                        tileId: pip.participantId,
                        isLocal: true,
                        isContentTile: false,
                        remote: nil,
                        uiState: uiState,
                        rendererProvider: rendererProvider,
                        videoContentMode: pip.fit == .contain ? .scaleAspectFit : .scaleAspectFill,
                        cornerRadius: pip.cornerRadius,
                        pinned: false,
                        strings: strings,
                        onSelect: { selectedSpotlightId = pip.participantId },
                        onTogglePinned: {
                            pinnedSpotlightId = pip.participantId == pinnedSpotlightId ? nil : pip.participantId
                        },
                        onLocalVideoSizeChanged: { localAspectRatio = quantizedFrontlineAspectRatio($0) },
                        onRemoteVideoSizeChanged: { _, _ in },
                        localZoomEnabled: false,
                        localZoomGesture: localZoomGesture(enabled: false),
                        showRemoteFitButton: false,
                        remoteVideoFitCover: remoteVideoFitCover,
                        onToggleRemoteVideoFit: toggleRemoteVideoFit
                    )
                    .frame(width: pip.frame.width, height: pip.frame.height)
                    .clipShape(RoundedRectangle(cornerRadius: pip.cornerRadius))
                    .position(x: pip.frame.x + pip.frame.width / 2, y: pip.frame.y + pip.frame.height / 2)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .background(frontlineBlack)
        }
    }

    private func computeFrontlineLayout(geometry: GeometryProxy) -> LayoutResult {
        let contentSource = activeContentSource
        var availableIds = Set(uiState.remoteParticipants.map(\.cid))
        availableIds.insert(localSpotlightId)
        if let activeContentSpotlightId {
            availableIds.insert(activeContentSpotlightId)
        }
        let defaultPrimary = lastVideoStartedParticipantId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? uiState.remoteParticipants.first?.cid
            ?? localSpotlightId
        let effectiveSpotlight = pinnedSpotlightId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? selectedSpotlightId.flatMap { availableIds.contains($0) ? $0 : nil }
            ?? defaultPrimary
        let spotlightIsContent = contentSource != nil && activeContentSpotlightId != nil && effectiveSpotlight == activeContentSpotlightId
        let spotlightIsRemote =
            uiState.remoteParticipants.contains { $0.cid == effectiveSpotlight } ||
            (spotlightIsContent && contentSource?.ownerParticipantId != localSpotlightId)
        let spotlightIsRemoteScreenShare =
            spotlightIsContent &&
            contentSource?.ownerParticipantId != localSpotlightId &&
            contentSource?.type == .screenShare

        var participants = uiState.remoteParticipants.map {
            SceneParticipant(
                id: $0.cid,
                role: .remote,
                videoEnabled: $0.videoEnabled,
                videoAspectRatio: remoteTileAspectRatios[$0.cid]
            )
        }
        // LEGACY: keep today's collapse rule (camera swapped into the single
        // content video).
        if frontlineIncludesNormalLocalStageTile(
            localSpotlightId: localSpotlightId,
            activeContentOwnerId: contentSource?.ownerParticipantId,
            contentTileIsSpotlight: spotlightIsContent
        ) {
            participants.append(
                SceneParticipant(
                    id: localSpotlightId,
                    role: .local,
                    videoEnabled: uiState.localVideoEnabled,
                    videoAspectRatio: localAspectRatio
                )
            )
        }
        if let contentSource, let activeContentSpotlightId, !spotlightIsContent {
            participants.append(
                SceneParticipant(
                    id: activeContentSpotlightId,
                    role: .remote,
                    videoEnabled: true,
                    videoAspectRatio: contentSource.aspectRatio
                )
            )
        }

        return computeLayout(scene: CallScene(
            viewportWidth: geometry.size.width,
            viewportHeight: geometry.size.height,
            safeAreaInsets: LayoutInsets(),
            participants: participants,
            localParticipantId: localSpotlightId,
            activeSpeakerId: nil,
            pinnedParticipantId: spotlightIsContent ? nil : effectiveSpotlight,
            contentSource: spotlightIsContent ? contentSource : nil,
            userPrefs: UserLayoutPrefs(
                dominantFit: frontlineRemoteScreenShareUsesFit(
                    isRemoteScreenShare: spotlightIsRemoteScreenShare,
                    remoteVideoFitCover: spotlightIsRemote ? remoteVideoFitCover : true
                ) ? .contain : .cover
            )
        ))
    }

    @ViewBuilder
    private func tileView(_ tile: TileLayout) -> some View {
        let contentSource = activeContentSource
        let isSyntheticContentTile = activeContentSpotlightId != nil && tile.id == activeContentSpotlightId
        let isContentTile = tile.type == .contentSource || isSyntheticContentTile
        let contentOwnerCid = isContentTile ? contentSource?.ownerParticipantId : nil
        let tileSpotlightId = isContentTile ? (activeContentSpotlightId ?? tile.id) : tile.id
        let isLocal = tile.id == localSpotlightId && !isContentTile
        let isLocalContent = isContentTile && contentOwnerCid == localSpotlightId
        let isRemoteScreenShare = isContentTile && contentOwnerCid != localSpotlightId && contentSource?.type == .screenShare
        let localZoomEnabled = isLocalContent && frontlineAllowsLocalCameraZoom(
            phase: uiState.phase,
            localVideoEnabled: uiState.localVideoEnabled,
            localCameraMode: uiState.localCameraMode,
            isScreenSharing: uiState.isScreenSharing
        )
        let remote = {
            if let contentOwnerCid, contentOwnerCid != localSpotlightId {
                return uiState.remoteParticipants.first(where: { $0.cid == contentOwnerCid })
            }
            if !isLocal {
                return uiState.remoteParticipants.first(where: { $0.cid == tile.id })
            }
            return nil
        }()
        let showRemoteFitButton =
            tile.zOrder == 0 &&
            remote != nil &&
            !isLocal &&
            (!isContentTile || contentOwnerCid != localSpotlightId)

        FrontlineLayoutTile(
            tileId: tile.id,
            isLocal: isLocal || isLocalContent,
            isContentTile: isContentTile,
            remote: remote,
            uiState: uiState,
            rendererProvider: rendererProvider,
            videoContentMode: isLocalContent && uiState.isScreenSharing
                ? .scaleAspectFit
                : (frontlineRemoteScreenShareUsesFit(
                    isRemoteScreenShare: isRemoteScreenShare,
                    remoteVideoFitCover: remoteVideoFitCover
                ) ? .scaleAspectFit : .scaleAspectFill),
            cornerRadius: tile.cornerRadius,
            pinned: tileSpotlightId == pinnedSpotlightId,
            strings: strings,
            onSelect: { selectedSpotlightId = tileSpotlightId },
            onTogglePinned: {
                pinnedSpotlightId = tileSpotlightId == pinnedSpotlightId ? nil : tileSpotlightId
            },
            onLocalVideoSizeChanged: { localAspectRatio = quantizedFrontlineAspectRatio($0) },
            onRemoteVideoSizeChanged: { cid, size in remoteTileAspectRatios[cid] = quantizedFrontlineAspectRatio(size) },
            localZoomEnabled: localZoomEnabled,
            localZoomGesture: localZoomGesture(enabled: localZoomEnabled),
            showRemoteFitButton: showRemoteFitButton,
            remoteVideoFitCover: isRemoteScreenShare ? false : remoteVideoFitCover,
            onToggleRemoteVideoFit: {
                if isRemoteScreenShare, let contentOwnerCid {
                    onRemoteScreenShareFullscreen(.legacy(ownerCid: contentOwnerCid))
                } else {
                    toggleRemoteVideoFit()
                }
            }
        )
    }

    private func toggleRemoteVideoFit() {
        withAnimation(.easeInOut(duration: 0.2)) {
            remoteVideoFitCover.toggle()
        }
        onRemoteVideoFitChanged?(remoteVideoFitCover)
    }

    private var activeContentSource: ContentSource? {
        guard let activeContentSpotlightId else { return nil }
        let owner = activeContentSpotlightId.removingFrontlineContentSpotlightPrefix
        if owner == localSpotlightId {
            let type: ContentType = {
                if uiState.isScreenSharing { return .screenShare }
                if uiState.localCameraMode == .world { return .worldCamera }
                return .compositeCamera
            }()
            return ContentSource(type: type, ownerParticipantId: owner, aspectRatio: localAspectRatio)
        }
        if owner == uiState.remoteContentCid {
            let type = ContentType.fromWire(uiState.remoteContentType)
            return ContentSource(type: type, ownerParticipantId: owner, aspectRatio: remoteTileAspectRatios[owner])
        }
        return nil
    }

    private func localZoomGesture(enabled: Bool) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard enabled else { return }
                let delta = value / max(lastMagnificationValue, 0.001)
                lastMagnificationValue = value
                guard abs(delta - 1) > 0.01 else { return }
                onAdjustCameraZoom(delta)
            }
            .onEnded { _ in lastMagnificationValue = 1 }
    }
}

/// Status overlay drawn ON TOP of a Frontline independent content tile while the
/// content is connecting (media not arrived yet — receiver-side hold) or the local
/// user is sharing with no participants receiving yet ("waiting for participants").
/// Never replaces the content sink with a stale frame.
private struct FrontlineContentStatusOverlay: View {
    let text: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.55)
            VStack(spacing: 12) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                Text(text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white.opacity(0.9))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
        .allowsHitTesting(false)
    }
}

private struct FrontlineLayoutTile<ZoomGesture: Gesture>: View {
    let tileId: String
    let isLocal: Bool
    let isContentTile: Bool
    let remote: RemoteParticipant?
    let uiState: CallUiState
    let rendererProvider: CallRendererProvider
    let videoContentMode: UIView.ContentMode
    let cornerRadius: CGFloat
    let pinned: Bool
    let strings: [SerenadaString: String]?
    let onSelect: () -> Void
    let onTogglePinned: () -> Void
    let onLocalVideoSizeChanged: (CGSize) -> Void
    let onRemoteVideoSizeChanged: (String, CGSize) -> Void
    let localZoomEnabled: Bool
    let localZoomGesture: ZoomGesture
    let showRemoteFitButton: Bool
    let remoteVideoFitCover: Bool
    let onToggleRemoteVideoFit: () -> Void

    var body: some View {
        let displayName = isLocal
            ? (uiState.localDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? uiState.localDisplayName! : resolveString(.frontlineYou, overrides: strings))
            : remoteDisplayName(remote)
        let muted = isLocal ? !uiState.localAudioEnabled : remote?.audioEnabled == false
        let audioLevel = isLocal ? uiState.localAudioLevel : remote?.audioLevel ?? 0
        let videoEnabled = isLocal ? (uiState.localVideoEnabled || uiState.isScreenSharing) : remote?.videoEnabled == true
        let showLocalVideoAccent = isLocal && uiState.localVideoEnabled
        let tileShape = RoundedRectangle(cornerRadius: cornerRadius)

        ZStack(alignment: .bottomLeading) {
            frontlineSurface

            if isLocal {
                if videoEnabled {
                    WebRTCVideoView(
                        kind: .local,
                        rendererProvider: rendererProvider,
                        videoContentMode: videoContentMode,
                        isMirrored: !isContentTile && uiState.isFrontCamera && !uiState.isScreenSharing,
                        onVideoSizeChanged: onLocalVideoSizeChanged
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipShape(tileShape)
                    if localZoomEnabled {
                        Color.clear
                            .contentShape(tileShape)
                            .accessibilityHidden(true)
                            .simultaneousGesture(localZoomGesture)
                    }
                } else {
                    FrontlineLocalAvatar(size: 86, fontSize: 34, displayName: displayName, strings: strings)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else if let remote, remote.videoEnabled {
                WebRTCVideoView(
                    kind: .remoteForCid(remote.cid),
                    rendererProvider: rendererProvider,
                    videoContentMode: videoContentMode,
                    onVideoSizeChanged: { onRemoteVideoSizeChanged(remote.cid, $0) }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipShape(tileShape)
            } else {
                FrontlineAvatar(peerId: remote?.peerId, displayName: displayName, size: 86, fontSize: 32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if pinned {
                Image(systemName: "pin.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                    .background(Color.black.opacity(0.62))
                    .clipShape(Circle())
                    .padding(8)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }

            if !isContentTile {
                FrontlineNameChip(label: displayName, muted: muted, audioLevel: audioLevel, compact: true)
                    .padding(6)
            }

            if showRemoteFitButton {
                FrontlineRemoteFitButton(
                    remoteVideoFitCover: remoteVideoFitCover,
                    strings: strings,
                    onClick: onToggleRemoteVideoFit
                )
                .padding(8)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
        .clipShape(tileShape)
        .overlay {
            if showLocalVideoAccent {
                tileShape
                    .strokeBorder(frontlineAccent, lineWidth: frontlinePipVideoAccentLineWidth)
                    .allowsHitTesting(false)
            }
        }
        .contentShape(tileShape)
        .onTapGesture(perform: onSelect)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5)
                .onEnded { _ in onTogglePinned() }
        )
    }
}

private func quantizedFrontlineAspectRatio(_ size: CGSize) -> CGFloat {
    guard size.width > 0, size.height > 0 else {
        return clampStageTileAspectRatio(nil)
    }
    let rawRatio = size.width / size.height
    let quantized = (rawRatio / 0.05).rounded() * 0.05
    return clampStageTileAspectRatio(max(0.1, quantized))
}

private struct FrontlineDebugPanel: View {
    let uiState: CallUiState

    var body: some View {
        let sections = buildDebugPanelSections(uiState: uiState)
        VStack(alignment: .leading, spacing: 8) {
            ForEach(sections) { section in
                VStack(alignment: .leading, spacing: 6) {
                    Text(section.title)
                        .font(.caption.weight(.semibold))
                    ForEach(section.metrics) { metric in
                        HStack(spacing: 8) {
                            Circle()
                                .fill(debugDotColor(metric.status))
                                .frame(width: 8, height: 8)
                            if !metric.label.isEmpty {
                                Text(metric.label)
                                    .font(.caption2)
                            }
                            Spacer(minLength: 8)
                            Text(metric.value)
                                .font(.caption2)
                        }
                    }
                }
            }
        }
        .foregroundStyle(Color.white.opacity(0.95))
        .padding(10)
        .frame(maxWidth: 430, maxHeight: 520)
        .background(Color.black.opacity(0.62))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func debugDotColor(_ status: DebugStatus) -> Color {
        switch status {
        case .good:
            return Color(red: 0.18, green: 0.80, blue: 0.44)
        case .warn:
            return Color(red: 0.94, green: 0.77, blue: 0.06)
        case .bad:
            return Color(red: 0.91, green: 0.30, blue: 0.24)
        case .na:
            return Color(red: 0.58, green: 0.65, blue: 0.65)
        }
    }
}
