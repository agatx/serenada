// ---------------------------------------------------------------------------
// Layout module – ported from client/src/layout/computeLayout.ts
//
// Contains the harmonic-mean grid algorithm (computeStageLayout) and a
// computeLayout() wrapper that accepts a CallScene and returns a LayoutResult
// with absolute tile positions.
//
// Uses only Foundation so the module is testable without a UI host.
// ---------------------------------------------------------------------------

import Foundation

// MARK: - Input types

struct CallScene: Decodable {
    let viewportWidth: CGFloat
    let viewportHeight: CGFloat
    let safeAreaInsets: LayoutInsets
    let participants: [SceneParticipant]
    let localParticipantId: String
    let activeSpeakerId: String?
    let pinnedParticipantId: String?
    let contentSource: ContentSource?
    let userPrefs: UserLayoutPrefs

    init(viewportWidth: CGFloat, viewportHeight: CGFloat, safeAreaInsets: LayoutInsets,
         participants: [SceneParticipant], localParticipantId: String,
         activeSpeakerId: String?, pinnedParticipantId: String?,
         contentSource: ContentSource?, userPrefs: UserLayoutPrefs) {
        self.viewportWidth = viewportWidth
        self.viewportHeight = viewportHeight
        self.safeAreaInsets = safeAreaInsets
        self.participants = participants
        self.localParticipantId = localParticipantId
        self.activeSpeakerId = activeSpeakerId
        self.pinnedParticipantId = pinnedParticipantId
        self.contentSource = contentSource
        self.userPrefs = userPrefs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        viewportWidth = try c.decode(CGFloat.self, forKey: .viewportWidth)
        viewportHeight = try c.decode(CGFloat.self, forKey: .viewportHeight)
        safeAreaInsets = try c.decode(LayoutInsets.self, forKey: .safeAreaInsets)
        participants = try c.decode([SceneParticipant].self, forKey: .participants)
        localParticipantId = try c.decode(String.self, forKey: .localParticipantId)
        activeSpeakerId = try c.decodeIfPresent(String.self, forKey: .activeSpeakerId)
        pinnedParticipantId = try c.decodeIfPresent(String.self, forKey: .pinnedParticipantId)
        contentSource = try c.decodeIfPresent(ContentSource.self, forKey: .contentSource)
        userPrefs = (try? c.decode(UserLayoutPrefs.self, forKey: .userPrefs)) ?? UserLayoutPrefs()
    }

    private enum CodingKeys: String, CodingKey {
        case viewportWidth, viewportHeight, safeAreaInsets, participants
        case localParticipantId, activeSpeakerId, pinnedParticipantId, contentSource, userPrefs
    }
}

struct SceneParticipant: Decodable {
    let id: String
    let role: ParticipantRole
    let videoEnabled: Bool
    let videoAspectRatio: CGFloat?
}

enum ParticipantRole: String, Codable {
    case local
    case remote
}

struct ContentSource: Decodable {
    let type: ContentType
    let ownerParticipantId: String
    let aspectRatio: CGFloat?
}

enum ContentType: String, Codable {
    case screenShare
    case worldCamera
    case compositeCamera
}

struct UserLayoutPrefs: Decodable {
    var swappedLocalAndRemote: Bool = false
    var dominantFit: FitMode = .cover
}

struct LayoutInsets: Decodable {
    var top: CGFloat = 0
    var bottom: CGFloat = 0
    var left: CGFloat = 0
    var right: CGFloat = 0
}

// MARK: - Output types

enum LayoutMode: String, Codable {
    case solo, pair, grid, focus, content
}

enum FitMode: String, Codable {
    case cover, contain
}

struct LayoutResult {
    let mode: LayoutMode
    let tiles: [TileLayout]
    let localPip: PipLayout?
}

struct TileLayout {
    let id: String
    let type: OccupantType
    let frame: LayoutRect
    let fit: FitMode
    let cornerRadius: CGFloat
    let zOrder: Int
}

enum OccupantType: String, Codable {
    case participant
    case contentSource
}

struct PipLayout {
    let participantId: String
    let frame: LayoutRect
    let fit: FitMode
    let cornerRadius: CGFloat
    let anchor: Anchor
    let zOrder: Int
}

enum Anchor: String, Codable {
    case topLeft, topRight, bottomLeft, bottomRight
}

struct LayoutRect {
    let x: CGFloat
    let y: CGFloat
    let width: CGFloat
    let height: CGFloat
}

// MARK: - Layout constants

private enum DeviceGroup {
    case phone, tablet, desktop
}

private enum LayoutConstants {
    static let gap: [DeviceGroup: CGFloat] = [.phone: 8, .tablet: 12, .desktop: 12]
    static let outerPadding: [DeviceGroup: CGFloat] = [.phone: 8, .tablet: 16, .desktop: 16]
    static let cornerRadius: [DeviceGroup: CGFloat] = [.phone: 12, .tablet: 14, .desktop: 16]
    static let minTileAspect: CGFloat = 9.0 / 16.0
    static let maxTileAspect: CGFloat = 16.0 / 9.0
    static let defaultTileAspect: CGFloat = 16.0 / 9.0
    static let pipWidthFraction: [DeviceGroup: CGFloat] = [.phone: 0.24, .tablet: 0.20, .desktop: 0.16]
    static let pipAspectRatio: CGFloat = 3.0 / 4.0
    static let pipInset: [DeviceGroup: CGFloat] = [.phone: 12, .tablet: 16, .desktop: 20]
    static let pipCornerRadius: CGFloat = 12
    static let primaryRatio: CGFloat = 0.75
    static let thumbnailMinSize: [DeviceGroup: CGFloat] = [.phone: 72, .tablet: 96, .desktop: 120]
}

// MARK: - Stage layout types (used by UI for row-based grid rendering)

struct StageTileSpec {
    let cid: String
    let aspectRatio: CGFloat
}

struct StageTileLayout: Identifiable {
    let cid: String
    let width: CGFloat
    let height: CGFloat

    var id: String { cid }
}

struct StageRowLayout: Identifiable {
    let items: [StageTileLayout]
    let index: Int

    var id: Int { index }
}

// MARK: - Private helpers

private func deviceGroup(_ viewportWidth: CGFloat, _ viewportHeight: CGFloat) -> DeviceGroup {
    let shortSide = min(viewportWidth, viewportHeight)
    if shortSide < 600 { return .phone }
    if shortSide < 1024 { return .tablet }
    return .desktop
}

private func deriveMode(_ scene: CallScene) -> LayoutMode {
    if scene.participants.count == 1 { return .solo }
    if scene.contentSource != nil { return .content }
    if scene.pinnedParticipantId != nil { return .focus }
    if scene.participants.count == 2 { return .pair }
    return .grid
}

func clampStageTileAspectRatio(_ ratio: CGFloat?) -> CGFloat {
    guard let ratio, ratio.isFinite, ratio > 0 else {
        return LayoutConstants.defaultTileAspect
    }
    return min(max(ratio, LayoutConstants.minTileAspect), LayoutConstants.maxTileAspect)
}

// MARK: - Grid algorithm (harmonic-mean best-fit)

func computeStageLayout(
    tiles: [StageTileSpec],
    availableWidth: CGFloat,
    availableHeight: CGFloat,
    gap: CGFloat
) -> [StageRowLayout] {
    guard !tiles.isEmpty, availableWidth > 0, availableHeight > 0 else { return [] }

    let candidateRows: [[[Int]]]
    switch tiles.count {
    case 1:
        candidateRows = [[[0]]]
    case 2:
        candidateRows = [[[0, 1]], [[0], [1]]]
    default:
        candidateRows = [
            [[0, 1, 2]],
            [[0, 1], [2]],
            [[0], [1, 2]],
            [[0], [1], [2]],
        ]
    }

    var bestLayout: [StageRowLayout] = []
    var bestHarmonicShortEdge: CGFloat = -1
    var bestMinShortEdge: CGFloat = -1
    var bestArea: CGFloat = -1
    var bestRowCount = Int.max

    for rows in candidateRows {
        let baseHeights = rows.map { row -> CGFloat in
            let totalAspect = row.reduce(CGFloat.zero) { partial, index in
                partial + tiles[index].aspectRatio
            }
            let rowWidth = availableWidth - gap * CGFloat(max(0, row.count - 1))
            guard rowWidth > 0, totalAspect > 0 else { return 0 }
            return rowWidth / totalAspect
        }
        let verticalGap = gap * CGFloat(max(0, rows.count - 1))
        let totalBaseHeight = baseHeights.reduce(0, +)
        guard totalBaseHeight > 0, availableHeight > verticalGap else { continue }

        let scale = min(1, (availableHeight - verticalGap) / totalBaseHeight)
        guard scale > 0 else { continue }

        let layout = rows.enumerated().map { rowIndex, row in
            let rowHeight = max(1, floor(baseHeights[rowIndex] * scale))
            return StageRowLayout(
                items: row.map { index in
                    let tile = tiles[index]
                    return StageTileLayout(
                        cid: tile.cid,
                        width: max(1, floor(tile.aspectRatio * rowHeight)),
                        height: rowHeight
                    )
                },
                index: rowIndex
            )
        }

        let shortEdges = layout.flatMap { $0.items.map { min($0.width, $0.height) } }
        guard let minShortEdge = shortEdges.min(), !shortEdges.isEmpty else { continue }
        let harmonicShortEdge = CGFloat(shortEdges.count) / shortEdges.reduce(CGFloat.zero) { partial, shortEdge in
            partial + (1 / shortEdge)
        }
        let area = layout.reduce(CGFloat.zero) { partial, row in
            partial + row.items.reduce(CGFloat.zero) { rowPartial, item in
                rowPartial + item.width * item.height
            }
        }
        let rowCount = layout.count

        let shortEdgeGainIsMeaningful = harmonicShortEdge > bestHarmonicShortEdge + 6
        let shortEdgeIsComparable = abs(harmonicShortEdge - bestHarmonicShortEdge) <= 6
        let minShortEdgeImproved = minShortEdge > bestMinShortEdge + 1
        let minShortEdgeComparable = abs(minShortEdge - bestMinShortEdge) <= 1

        let shouldSelectLayout =
            shortEdgeGainIsMeaningful ||
            (
                shortEdgeIsComparable &&
                (
                    rowCount < bestRowCount ||
                    (rowCount == bestRowCount && (
                        minShortEdgeImproved ||
                        (minShortEdgeComparable && area > bestArea)
                    ))
                )
            ) ||
            (
                !shortEdgeIsComparable &&
                harmonicShortEdge > bestHarmonicShortEdge &&
                minShortEdgeImproved
            )

        if shouldSelectLayout {
            bestLayout = layout
            bestHarmonicShortEdge = harmonicShortEdge
            bestMinShortEdge = minShortEdge
            bestArea = area
            bestRowCount = rowCount
        }
    }

    return bestLayout
}

// MARK: - Grid tile absolute positioning

private func gridTilesToAbsolute(
    _ rows: [StageRowLayout],
    availableX: CGFloat,
    availableY: CGFloat,
    availableWidth: CGFloat,
    availableHeight: CGFloat,
    gap: CGFloat
) -> [(cid: String, frame: LayoutRect)] {
    let totalRowsHeight =
        rows.reduce(CGFloat.zero) { sum, row in sum + row.items[0].height } +
        gap * CGFloat(max(0, rows.count - 1))
    var rowY = availableY + (availableHeight - totalRowsHeight) / 2

    var result: [(cid: String, frame: LayoutRect)] = []
    for row in rows {
        let rowWidth =
            row.items.reduce(CGFloat.zero) { sum, tile in sum + tile.width } +
            gap * CGFloat(max(0, row.items.count - 1))
        var tileX = availableX + (availableWidth - rowWidth) / 2
        let rowHeight = row.items[0].height

        for tile in row.items {
            result.append((
                cid: tile.cid,
                frame: LayoutRect(x: tileX, y: rowY, width: tile.width, height: rowHeight)
            ))
            tileX += tile.width + gap
        }
        rowY += rowHeight + gap
    }
    return result
}

// MARK: - PIP computation

private func computePip(
    participantId: String,
    viewportWidth: CGFloat,
    viewportHeight: CGFloat,
    group: DeviceGroup,
    videoAspectRatio: CGFloat?
) -> PipLayout {
    let widthFraction = LayoutConstants.pipWidthFraction[group]!
    let inset = LayoutConstants.pipInset[group]!
    let width = viewportWidth * widthFraction
    let ar = clampStageTileAspectRatio(videoAspectRatio ?? LayoutConstants.pipAspectRatio)
    let height = width / ar

    return PipLayout(
        participantId: participantId,
        frame: LayoutRect(
            x: viewportWidth - inset - width,
            y: viewportHeight - inset - height,
            width: width,
            height: height
        ),
        fit: .cover,
        cornerRadius: LayoutConstants.pipCornerRadius,
        anchor: .bottomRight,
        zOrder: 1
    )
}

// MARK: - Focus / Content: primary + filmstrip layout

private func computePrimaryWithFilmstrip(
    primaryId: String,
    primaryType: OccupantType,
    primaryFit: FitMode,
    filmstripParticipants: [SceneParticipant],
    areaX: CGFloat,
    areaY: CGFloat,
    areaWidth: CGFloat,
    areaHeight: CGFloat,
    gap: CGFloat,
    cornerRadius: CGFloat,
    group: DeviceGroup
) -> [TileLayout] {
    let isLandscape = areaWidth > areaHeight
    let thumbnailMin = LayoutConstants.thumbnailMinSize[group]!
    let filmstripCount = filmstripParticipants.count

    var primaryRatio = LayoutConstants.primaryRatio
    if filmstripCount > 0 {
        if isLandscape {
            let secondaryWidth = areaWidth * (1 - primaryRatio)
            let tileHeight = (areaHeight - gap * CGFloat(max(0, filmstripCount - 1))) / CGFloat(filmstripCount)
            if tileHeight < thumbnailMin || secondaryWidth < thumbnailMin {
                let ratioFromHeight = (thumbnailMin * CGFloat(filmstripCount) + gap * CGFloat(max(0, filmstripCount - 1))) > areaHeight ? 0.5 : primaryRatio
                let ratioFromWidth = 1 - thumbnailMin / areaWidth
                primaryRatio = max(0.5, min(primaryRatio, min(ratioFromHeight, ratioFromWidth)))
            }
        } else {
            let secondaryHeight = areaHeight * (1 - primaryRatio)
            let tileWidth = (areaWidth - gap * CGFloat(max(0, filmstripCount - 1))) / CGFloat(filmstripCount)
            if tileWidth < thumbnailMin || secondaryHeight < thumbnailMin {
                let ratioFromWidth = (thumbnailMin * CGFloat(filmstripCount) + gap * CGFloat(max(0, filmstripCount - 1))) > areaWidth ? 0.5 : primaryRatio
                let ratioFromHeight = 1 - thumbnailMin / areaHeight
                primaryRatio = max(0.5, min(primaryRatio, min(ratioFromWidth, ratioFromHeight)))
            }
        }
    }

    var tiles: [TileLayout] = []

    if isLandscape {
        let primaryWidth = areaWidth * primaryRatio - gap / 2
        let secondaryWidth = areaWidth - primaryWidth - gap

        tiles.append(TileLayout(
            id: primaryId, type: primaryType,
            frame: LayoutRect(x: areaX, y: areaY, width: primaryWidth, height: areaHeight),
            fit: primaryFit, cornerRadius: cornerRadius, zOrder: 0
        ))

        if filmstripCount > 0 {
            let stripX = areaX + primaryWidth + gap
            let tileHeight = (areaHeight - gap * CGFloat(max(0, filmstripCount - 1))) / CGFloat(filmstripCount)
            for (i, p) in filmstripParticipants.enumerated() {
                tiles.append(TileLayout(
                    id: p.id, type: .participant,
                    frame: LayoutRect(
                        x: stripX,
                        y: areaY + CGFloat(i) * (tileHeight + gap),
                        width: secondaryWidth,
                        height: tileHeight
                    ),
                    fit: .cover, cornerRadius: cornerRadius, zOrder: i + 1
                ))
            }
        }
    } else {
        let primaryHeight = areaHeight * primaryRatio - gap / 2
        let secondaryHeight = areaHeight - primaryHeight - gap

        tiles.append(TileLayout(
            id: primaryId, type: primaryType,
            frame: LayoutRect(x: areaX, y: areaY, width: areaWidth, height: primaryHeight),
            fit: primaryFit, cornerRadius: cornerRadius, zOrder: 0
        ))

        if filmstripCount > 0 {
            let stripY = areaY + primaryHeight + gap
            let tileWidth = (areaWidth - gap * CGFloat(max(0, filmstripCount - 1))) / CGFloat(filmstripCount)
            for (i, p) in filmstripParticipants.enumerated() {
                tiles.append(TileLayout(
                    id: p.id, type: .participant,
                    frame: LayoutRect(
                        x: areaX + CGFloat(i) * (tileWidth + gap),
                        y: stripY,
                        width: tileWidth,
                        height: secondaryHeight
                    ),
                    fit: .cover, cornerRadius: cornerRadius, zOrder: i + 1
                ))
            }
        }
    }

    return tiles
}

// MARK: - Stable participant ordering

private func participantsStableOrder(
    _ participants: [SceneParticipant],
    localParticipantId: String
) -> [SceneParticipant] {
    let remotes = participants.filter { $0.id != localParticipantId }
    let local = participants.filter { $0.id == localParticipantId }
    return remotes + local
}

// MARK: - Main entry point

func computeLayout(scene: CallScene) -> LayoutResult {
    let mode = deriveMode(scene)
    let group = deviceGroup(scene.viewportWidth, scene.viewportHeight)
    let gap = LayoutConstants.gap[group]!
    let outerPadding = LayoutConstants.outerPadding[group]!
    let cornerRadius = LayoutConstants.cornerRadius[group]!

    let availableX = scene.safeAreaInsets.left
    let availableY = scene.safeAreaInsets.top
    let availableWidth = scene.viewportWidth - scene.safeAreaInsets.left - scene.safeAreaInsets.right
    let availableHeight = scene.viewportHeight - scene.safeAreaInsets.top - scene.safeAreaInsets.bottom

    let paddedX = availableX + outerPadding
    let paddedY = availableY + outerPadding
    let paddedWidth = availableWidth - outerPadding * 2
    let paddedHeight = availableHeight - outerPadding * 2

    let localParticipant = scene.participants.first { $0.id == scene.localParticipantId }

    switch mode {

    case .solo:
        return LayoutResult(
            mode: mode, tiles: [],
            localPip: localParticipant.map {
                computePip(participantId: $0.id, viewportWidth: scene.viewportWidth,
                           viewportHeight: scene.viewportHeight, group: group,
                           videoAspectRatio: $0.videoAspectRatio)
            }
        )

    case .pair:
        let remoteParticipant = scene.participants.first { $0.role == .remote }
        let dominant = scene.userPrefs.swappedLocalAndRemote ? localParticipant : remoteParticipant
        let pipParticipant = scene.userPrefs.swappedLocalAndRemote ? remoteParticipant : localParticipant

        let tiles: [TileLayout] = dominant.map { d in
            [TileLayout(
                id: d.id, type: .participant,
                frame: LayoutRect(x: availableX, y: availableY, width: availableWidth, height: availableHeight),
                fit: scene.userPrefs.dominantFit, cornerRadius: 0, zOrder: 0
            )]
        } ?? []

        return LayoutResult(
            mode: mode, tiles: tiles,
            localPip: pipParticipant.map {
                computePip(participantId: $0.id, viewportWidth: scene.viewportWidth,
                           viewportHeight: scene.viewportHeight, group: group,
                           videoAspectRatio: $0.videoAspectRatio)
            }
        )

    case .grid:
        let remoteParticipants = scene.participants.filter { $0.role == .remote }
        let stageTiles = remoteParticipants.map {
            StageTileSpec(cid: $0.id, aspectRatio: clampStageTileAspectRatio($0.videoAspectRatio))
        }
        let rows = computeStageLayout(tiles: stageTiles, availableWidth: paddedWidth, availableHeight: paddedHeight, gap: gap)
        let absoluteTiles = gridTilesToAbsolute(rows, availableX: paddedX, availableY: paddedY, availableWidth: paddedWidth, availableHeight: paddedHeight, gap: gap)

        return LayoutResult(
            mode: mode,
            tiles: absoluteTiles.enumerated().map { index, t in
                TileLayout(id: t.cid, type: .participant, frame: t.frame, fit: .cover, cornerRadius: cornerRadius, zOrder: index)
            },
            localPip: localParticipant.map {
                computePip(participantId: $0.id, viewportWidth: scene.viewportWidth,
                           viewportHeight: scene.viewportHeight, group: group,
                           videoAspectRatio: $0.videoAspectRatio)
            }
        )

    case .focus:
        guard let pinnedParticipant = scene.participants.first(where: { $0.id == scene.pinnedParticipantId }) else {
            return computeLayout(scene: CallScene(
                viewportWidth: scene.viewportWidth, viewportHeight: scene.viewportHeight,
                safeAreaInsets: scene.safeAreaInsets, participants: scene.participants,
                localParticipantId: scene.localParticipantId, activeSpeakerId: scene.activeSpeakerId,
                pinnedParticipantId: nil, contentSource: scene.contentSource, userPrefs: scene.userPrefs
            ))
        }

        let secondaryParticipants = participantsStableOrder(
            scene.participants.filter { $0.id != pinnedParticipant.id },
            localParticipantId: scene.localParticipantId
        )

        return LayoutResult(
            mode: mode,
            tiles: computePrimaryWithFilmstrip(
                primaryId: pinnedParticipant.id, primaryType: .participant,
                primaryFit: scene.userPrefs.dominantFit,
                filmstripParticipants: secondaryParticipants,
                areaX: paddedX, areaY: paddedY, areaWidth: paddedWidth, areaHeight: paddedHeight,
                gap: gap, cornerRadius: cornerRadius, group: group
            ),
            localPip: nil
        )

    case .content:
        guard let cs = scene.contentSource else {
            return computeLayout(scene: CallScene(
                viewportWidth: scene.viewportWidth, viewportHeight: scene.viewportHeight,
                safeAreaInsets: scene.safeAreaInsets, participants: scene.participants,
                localParticipantId: scene.localParticipantId, activeSpeakerId: scene.activeSpeakerId,
                pinnedParticipantId: scene.pinnedParticipantId, contentSource: nil, userPrefs: scene.userPrefs
            ))
        }

        let secondaryParticipants = participantsStableOrder(
            scene.participants.filter { $0.id != cs.ownerParticipantId },
            localParticipantId: scene.localParticipantId
        )

        return LayoutResult(
            mode: mode,
            tiles: computePrimaryWithFilmstrip(
                primaryId: cs.ownerParticipantId + "_content", primaryType: .contentSource,
                primaryFit: scene.userPrefs.dominantFit,
                filmstripParticipants: secondaryParticipants,
                areaX: paddedX, areaY: paddedY, areaWidth: paddedWidth, areaHeight: paddedHeight,
                gap: gap, cornerRadius: cornerRadius, group: group
            ),
            localPip: nil
        )
    }
}
