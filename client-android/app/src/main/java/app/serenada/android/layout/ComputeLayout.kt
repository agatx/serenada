package app.serenada.android.layout

import app.serenada.android.call.ContentTypeWire
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ===== Input types ===========================================================

data class CallScene(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val safeAreaInsets: Insets,
    val participants: List<SceneParticipant>,
    val localParticipantId: String,
    val activeSpeakerId: String?,
    val pinnedParticipantId: String?,
    val contentSource: ContentSource?,
    val userPrefs: UserLayoutPrefs,
)

data class SceneParticipant(
    val id: String,
    val role: ParticipantRole,
    val videoEnabled: Boolean,
    val videoAspectRatio: Float?,
)

enum class ParticipantRole { LOCAL, REMOTE }

data class ContentSource(
    val type: ContentType,
    val ownerParticipantId: String,
    val aspectRatio: Float?,
)

enum class ContentType {
    SCREEN_SHARE, WORLD_CAMERA, COMPOSITE_CAMERA;

    companion object {
        fun fromWire(wire: String?): ContentType = when (wire) {
            ContentTypeWire.WORLD_CAMERA -> WORLD_CAMERA
            ContentTypeWire.COMPOSITE_CAMERA -> COMPOSITE_CAMERA
            else -> SCREEN_SHARE
        }
    }
}

data class UserLayoutPrefs(
    val swappedLocalAndRemote: Boolean = false,
    val dominantFit: FitMode = FitMode.COVER,
)

data class Insets(
    val top: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
)

// ===== Output types ==========================================================

enum class LayoutMode { SOLO, PAIR, GRID, FOCUS, CONTENT }
enum class FitMode { COVER, CONTAIN }

data class LayoutResult(
    val mode: LayoutMode,
    val tiles: List<TileLayout>,
    val localPip: PipLayout?,
)

data class TileLayout(
    val id: String,
    val type: OccupantType,
    val frame: LayoutRect,
    val fit: FitMode,
    val cornerRadius: Float,
    val zOrder: Int,
)

enum class OccupantType { PARTICIPANT, CONTENT_SOURCE }

data class PipLayout(
    val participantId: String,
    val frame: LayoutRect,
    val fit: FitMode,
    val cornerRadius: Float,
    val anchor: Anchor,
    val zOrder: Int,
)

enum class Anchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class LayoutRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

// ===== Device-group-based layout constants ===================================

private enum class DeviceGroup { PHONE, TABLET, DESKTOP }

private object LayoutConstants {
    val gap = mapOf(DeviceGroup.PHONE to 8f, DeviceGroup.TABLET to 12f, DeviceGroup.DESKTOP to 12f)
    val outerPadding = mapOf(DeviceGroup.PHONE to 8f, DeviceGroup.TABLET to 16f, DeviceGroup.DESKTOP to 16f)
    val cornerRadius = mapOf(DeviceGroup.PHONE to 12f, DeviceGroup.TABLET to 14f, DeviceGroup.DESKTOP to 16f)
    const val minTileAspect = 9f / 16f
    const val maxTileAspect = 16f / 9f
    const val defaultTileAspect = 16f / 9f
    val pipWidthFraction = mapOf(DeviceGroup.PHONE to 0.24f, DeviceGroup.TABLET to 0.20f, DeviceGroup.DESKTOP to 0.16f)
    const val pipAspectRatio = 3f / 4f
    val pipInset = mapOf(DeviceGroup.PHONE to 12f, DeviceGroup.TABLET to 16f, DeviceGroup.DESKTOP to 20f)
    const val pipCornerRadius = 12f
    const val primaryRatio = 0.75f
    val thumbnailMinSize = mapOf(DeviceGroup.PHONE to 72f, DeviceGroup.TABLET to 96f, DeviceGroup.DESKTOP to 120f)
}

// ===== Stage layout types (used by UI for row-based grid rendering) ==========

data class StageTileSpec(
    val cid: String,
    val aspectRatio: Float,
)

data class StageTileLayout(
    val cid: String,
    val widthPx: Int,
    val heightPx: Int,
)

data class StageRowLayout(
    val items: List<StageTileLayout>,
)

// ===== Device group classification ===========================================

private fun deviceGroup(viewportWidth: Float, viewportHeight: Float): DeviceGroup {
    val shortSide = min(viewportWidth, viewportHeight)
    if (shortSide < 600f) return DeviceGroup.PHONE
    if (shortSide < 1024f) return DeviceGroup.TABLET
    return DeviceGroup.DESKTOP
}

// ===== Mode derivation =======================================================

private fun deriveMode(scene: CallScene): LayoutMode {
    if (scene.participants.size == 1) return LayoutMode.SOLO
    if (scene.contentSource != null) return LayoutMode.CONTENT
    if (scene.pinnedParticipantId != null) return LayoutMode.FOCUS
    if (scene.participants.size == 2) return LayoutMode.PAIR
    return LayoutMode.GRID
}

// ===== Aspect ratio clamping =================================================

fun clampStageTileAspectRatio(ratio: Float?): Float {
    val safeRatio = ratio ?: return LayoutConstants.defaultTileAspect
    if (!safeRatio.isFinite() || safeRatio <= 0f) return LayoutConstants.defaultTileAspect
    return safeRatio.coerceIn(LayoutConstants.minTileAspect, LayoutConstants.maxTileAspect)
}

// ===== Harmonic-mean grid algorithm (exact port from CallScreen.kt) ==========

fun computeStageLayout(
    tiles: List<StageTileSpec>,
    availableWidthPx: Float,
    availableHeightPx: Float,
    gapPx: Float,
): List<StageRowLayout> {
    if (tiles.isEmpty() || availableWidthPx <= 0f || availableHeightPx <= 0f) return emptyList()

    val candidateRows =
        when (tiles.size) {
            1 -> listOf(listOf(listOf(0)))
            2 -> listOf(listOf(listOf(0, 1)), listOf(listOf(0), listOf(1)))
            else ->
                listOf(
                    listOf(listOf(0, 1, 2)),
                    listOf(listOf(0, 1), listOf(2)),
                    listOf(listOf(0), listOf(1, 2)),
                    listOf(listOf(0), listOf(1), listOf(2)),
                )
        }

    var bestLayout = emptyList<StageRowLayout>()
    var bestHarmonicShortEdge = -1f
    var bestMinShortEdge = -1f
    var bestArea = -1f
    var bestRowCount = Int.MAX_VALUE

    candidateRows.forEach { rows ->
        val baseHeights =
            rows.map { row ->
                val totalAspect = row.sumOf { index -> tiles[index].aspectRatio.toDouble() }.toFloat()
                val rowWidth = availableWidthPx - gapPx * (row.size - 1).coerceAtLeast(0)
                if (rowWidth > 0f && totalAspect > 0f) rowWidth / totalAspect else 0f
            }
        val verticalGap = gapPx * (rows.size - 1).coerceAtLeast(0)
        val totalBaseHeight = baseHeights.sum()
        if (totalBaseHeight <= 0f || availableHeightPx <= verticalGap) return@forEach

        val scale = minOf(1f, (availableHeightPx - verticalGap) / totalBaseHeight)
        if (scale <= 0f) return@forEach

        val layout =
            rows.mapIndexed { rowIndex, row ->
                val rowHeight = maxOf(1, (baseHeights[rowIndex] * scale).toInt())
                StageRowLayout(
                    items =
                        row.map { index ->
                            val tile = tiles[index]
                            StageTileLayout(
                                cid = tile.cid,
                                widthPx = maxOf(1, (tile.aspectRatio * rowHeight).toInt()),
                                heightPx = rowHeight,
                            )
                        }
                )
            }

        val shortEdges = layout.flatMap { row -> row.items.map { minOf(it.widthPx, it.heightPx).toFloat() } }
        val minShortEdge = shortEdges.minOrNull() ?: return@forEach
        val harmonicShortEdge = shortEdges.size / shortEdges.sumOf { shortEdge -> (1f / shortEdge).toDouble() }.toFloat()
        val area = layout.sumOf { row -> row.items.sumOf { it.widthPx * it.heightPx } }.toFloat()
        val rowCount = layout.size

        val shortEdgeGainIsMeaningful = harmonicShortEdge > bestHarmonicShortEdge + 6f
        val shortEdgeIsComparable = abs(harmonicShortEdge - bestHarmonicShortEdge) <= 6f
        val minShortEdgeImproved = minShortEdge > bestMinShortEdge + 1f
        val minShortEdgeComparable = abs(minShortEdge - bestMinShortEdge) <= 1f

        if (
            shortEdgeGainIsMeaningful ||
            (
                shortEdgeIsComparable && (
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
        ) {
            bestLayout = layout
            bestHarmonicShortEdge = harmonicShortEdge
            bestMinShortEdge = minShortEdge
            bestArea = area
            bestRowCount = rowCount
        }
    }

    return bestLayout
}

// ===== Grid tile absolute positioning ========================================

private data class AbsoluteTile(
    val cid: String,
    val frame: LayoutRect,
)

private fun gridTilesToAbsolute(
    rows: List<StageRowLayout>,
    availableX: Float,
    availableY: Float,
    availableWidth: Float,
    availableHeight: Float,
    gap: Float,
): List<AbsoluteTile> {
    if (rows.isEmpty()) return emptyList()

    val totalRowsHeight =
        rows.sumOf { row -> row.items[0].heightPx.toDouble() }.toFloat() +
            gap * max(0, rows.size - 1)
    var rowY = availableY + (availableHeight - totalRowsHeight) / 2f

    val result = mutableListOf<AbsoluteTile>()
    for (row in rows) {
        val rowWidth =
            row.items.sumOf { tile -> tile.widthPx.toDouble() }.toFloat() +
                gap * max(0, row.items.size - 1)
        var tileX = availableX + (availableWidth - rowWidth) / 2f
        val rowHeight = row.items[0].heightPx.toFloat()

        for (tile in row.items) {
            result.add(
                AbsoluteTile(
                    cid = tile.cid,
                    frame = LayoutRect(
                        x = tileX,
                        y = rowY,
                        width = tile.widthPx.toFloat(),
                        height = rowHeight,
                    ),
                )
            )
            tileX += tile.widthPx.toFloat() + gap
        }
        rowY += rowHeight + gap
    }
    return result
}

// ===== PIP computation =======================================================

private fun computePip(
    participantId: String,
    viewportWidth: Float,
    viewportHeight: Float,
    group: DeviceGroup,
    videoAspectRatio: Float?,
): PipLayout {
    val widthFraction = LayoutConstants.pipWidthFraction.getValue(group)
    val inset = LayoutConstants.pipInset.getValue(group)
    val width = viewportWidth * widthFraction
    val ar = clampStageTileAspectRatio(videoAspectRatio ?: LayoutConstants.pipAspectRatio)
    val height = width / ar

    return PipLayout(
        participantId = participantId,
        frame = LayoutRect(
            x = viewportWidth - inset - width,
            y = viewportHeight - inset - height,
            width = width,
            height = height,
        ),
        fit = FitMode.COVER,
        cornerRadius = LayoutConstants.pipCornerRadius,
        anchor = Anchor.BOTTOM_RIGHT,
        zOrder = 1,
    )
}

// ===== Focus / Content: primary + filmstrip layout ==========================

private fun computePrimaryWithFilmstrip(
    primaryId: String,
    primaryType: OccupantType,
    primaryFit: FitMode,
    filmstripParticipants: List<SceneParticipant>,
    areaX: Float,
    areaY: Float,
    areaWidth: Float,
    areaHeight: Float,
    gap: Float,
    cornerRadius: Float,
    group: DeviceGroup,
): List<TileLayout> {
    val isLandscape = areaWidth > areaHeight
    val thumbnailMin = LayoutConstants.thumbnailMinSize.getValue(group)
    val filmstripCount = filmstripParticipants.size

    var primaryRatio = LayoutConstants.primaryRatio
    if (filmstripCount > 0) {
        if (isLandscape) {
            val secondaryWidth = areaWidth * (1f - primaryRatio)
            val tileHeight = (areaHeight - gap * max(0, filmstripCount - 1)) / filmstripCount
            if (tileHeight < thumbnailMin || secondaryWidth < thumbnailMin) {
                val ratioFromHeight = if (thumbnailMin * filmstripCount + gap * max(0, filmstripCount - 1) > areaHeight) 0.5f else primaryRatio
                val ratioFromWidth = 1f - thumbnailMin / areaWidth
                primaryRatio = max(0.5f, min(primaryRatio, min(ratioFromHeight, ratioFromWidth)))
            }
        } else {
            val secondaryHeight = areaHeight * (1f - primaryRatio)
            val tileWidth = (areaWidth - gap * max(0, filmstripCount - 1)) / filmstripCount
            if (tileWidth < thumbnailMin || secondaryHeight < thumbnailMin) {
                val ratioFromWidth = if (thumbnailMin * filmstripCount + gap * max(0, filmstripCount - 1) > areaWidth) 0.5f else primaryRatio
                val ratioFromHeight = 1f - thumbnailMin / areaHeight
                primaryRatio = max(0.5f, min(primaryRatio, min(ratioFromWidth, ratioFromHeight)))
            }
        }
    }

    val tiles = mutableListOf<TileLayout>()

    if (isLandscape) {
        val primaryWidth = areaWidth * primaryRatio - gap / 2f
        val secondaryWidth = areaWidth - primaryWidth - gap

        tiles += TileLayout(
            id = primaryId,
            type = primaryType,
            frame = LayoutRect(x = areaX, y = areaY, width = primaryWidth, height = areaHeight),
            fit = primaryFit,
            cornerRadius = cornerRadius,
            zOrder = 0,
        )

        if (filmstripCount > 0) {
            val stripX = areaX + primaryWidth + gap
            val tileHeight = (areaHeight - gap * max(0, filmstripCount - 1)) / filmstripCount
            filmstripParticipants.forEachIndexed { i, p ->
                tiles += TileLayout(
                    id = p.id,
                    type = OccupantType.PARTICIPANT,
                    frame = LayoutRect(
                        x = stripX,
                        y = areaY + i * (tileHeight + gap),
                        width = secondaryWidth,
                        height = tileHeight,
                    ),
                    fit = FitMode.COVER,
                    cornerRadius = cornerRadius,
                    zOrder = i + 1,
                )
            }
        }
    } else {
        val primaryHeight = areaHeight * primaryRatio - gap / 2f
        val secondaryHeight = areaHeight - primaryHeight - gap

        tiles += TileLayout(
            id = primaryId,
            type = primaryType,
            frame = LayoutRect(x = areaX, y = areaY, width = areaWidth, height = primaryHeight),
            fit = primaryFit,
            cornerRadius = cornerRadius,
            zOrder = 0,
        )

        if (filmstripCount > 0) {
            val stripY = areaY + primaryHeight + gap
            val tileWidth = (areaWidth - gap * max(0, filmstripCount - 1)) / filmstripCount
            filmstripParticipants.forEachIndexed { i, p ->
                tiles += TileLayout(
                    id = p.id,
                    type = OccupantType.PARTICIPANT,
                    frame = LayoutRect(
                        x = areaX + i * (tileWidth + gap),
                        y = stripY,
                        width = tileWidth,
                        height = secondaryHeight,
                    ),
                    fit = FitMode.COVER,
                    cornerRadius = cornerRadius,
                    zOrder = i + 1,
                )
            }
        }
    }

    return tiles
}

// ===== Stable participant ordering ==========================================

private fun participantsStableOrder(
    participants: List<SceneParticipant>,
    localParticipantId: String,
): List<SceneParticipant> {
    val remotes = participants.filter { it.id != localParticipantId }
    val local = participants.filter { it.id == localParticipantId }
    return remotes + local
}

// ===== Main entry point ======================================================

fun computeLayout(scene: CallScene): LayoutResult {
    val mode = deriveMode(scene)
    val group = deviceGroup(scene.viewportWidth, scene.viewportHeight)
    val gap = LayoutConstants.gap.getValue(group)
    val outerPadding = LayoutConstants.outerPadding.getValue(group)
    val cornerRadius = LayoutConstants.cornerRadius.getValue(group)

    // Available area after safe-area insets
    val availableX = scene.safeAreaInsets.left
    val availableY = scene.safeAreaInsets.top
    val availableWidth =
        scene.viewportWidth - scene.safeAreaInsets.left - scene.safeAreaInsets.right
    val availableHeight =
        scene.viewportHeight - scene.safeAreaInsets.top - scene.safeAreaInsets.bottom

    // Padded area
    val paddedX = availableX + outerPadding
    val paddedY = availableY + outerPadding
    val paddedWidth = availableWidth - outerPadding * 2f
    val paddedHeight = availableHeight - outerPadding * 2f

    val localParticipant = scene.participants.find { it.id == scene.localParticipantId }

    return when (mode) {
        // ----- solo ---------------------------------------------------------------
        LayoutMode.SOLO -> {
            LayoutResult(
                mode = mode,
                tiles = emptyList(),
                localPip = localParticipant?.let {
                    computePip(it.id, scene.viewportWidth, scene.viewportHeight, group, it.videoAspectRatio)
                },
            )
        }

        // ----- pair ---------------------------------------------------------------
        LayoutMode.PAIR -> {
            val remoteParticipant = scene.participants.find { it.role == ParticipantRole.REMOTE }
            val dominant = if (scene.userPrefs.swappedLocalAndRemote) localParticipant else remoteParticipant
            val pipParticipant = if (scene.userPrefs.swappedLocalAndRemote) remoteParticipant else localParticipant

            val tiles = if (dominant != null) {
                listOf(TileLayout(
                    id = dominant.id, type = OccupantType.PARTICIPANT,
                    frame = LayoutRect(availableX, availableY, availableWidth, availableHeight),
                    fit = scene.userPrefs.dominantFit, cornerRadius = 0f, zOrder = 0,
                ))
            } else emptyList()

            LayoutResult(
                mode = mode, tiles = tiles,
                localPip = pipParticipant?.let {
                    computePip(it.id, scene.viewportWidth, scene.viewportHeight, group, it.videoAspectRatio)
                },
            )
        }

        // ----- grid ---------------------------------------------------------------
        LayoutMode.GRID -> {
            val remoteParticipants = scene.participants.filter { it.role == ParticipantRole.REMOTE }
            val stageTiles = remoteParticipants.map { StageTileSpec(it.id, clampStageTileAspectRatio(it.videoAspectRatio)) }
            val rows = computeStageLayout(stageTiles, paddedWidth, paddedHeight, gap)
            val absoluteTiles = gridTilesToAbsolute(rows, paddedX, paddedY, paddedWidth, paddedHeight, gap)

            LayoutResult(
                mode = mode,
                tiles = absoluteTiles.mapIndexed { index, t ->
                    TileLayout(t.cid, OccupantType.PARTICIPANT, t.frame, FitMode.COVER, cornerRadius, index)
                },
                localPip = localParticipant?.let {
                    computePip(it.id, scene.viewportWidth, scene.viewportHeight, group, it.videoAspectRatio)
                },
            )
        }

        // ----- focus --------------------------------------------------------------
        LayoutMode.FOCUS -> {
            val pinnedParticipant = scene.participants.find { it.id == scene.pinnedParticipantId }
            if (pinnedParticipant == null) {
                return computeLayout(scene.copy(pinnedParticipantId = null))
            }

            val secondaryParticipants = participantsStableOrder(
                scene.participants.filter { it.id != pinnedParticipant.id },
                scene.localParticipantId,
            )

            LayoutResult(
                mode = mode,
                tiles = computePrimaryWithFilmstrip(
                    pinnedParticipant.id, OccupantType.PARTICIPANT, scene.userPrefs.dominantFit,
                    secondaryParticipants, paddedX, paddedY, paddedWidth, paddedHeight,
                    gap, cornerRadius, group,
                ),
                localPip = null,
            )
        }

        // ----- content ------------------------------------------------------------
        LayoutMode.CONTENT -> {
            val cs = scene.contentSource
            if (cs == null) {
                return computeLayout(scene.copy(contentSource = null))
            }

            val secondaryParticipants = participantsStableOrder(
                scene.participants.filter { it.id != cs.ownerParticipantId },
                scene.localParticipantId,
            )

            LayoutResult(
                mode = mode,
                tiles = computePrimaryWithFilmstrip(
                    cs.ownerParticipantId + "_content", OccupantType.CONTENT_SOURCE, scene.userPrefs.dominantFit,
                    secondaryParticipants, paddedX, paddedY, paddedWidth, paddedHeight,
                    gap, cornerRadius, group,
                ),
                localPip = null,
            )
        }
    }
}
