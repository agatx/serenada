// ---------------------------------------------------------------------------
// Layout module – extracted from CallRoom.tsx
//
// Exports the original harmonic-mean grid algorithm (computeStageLayout) plus
// a new computeLayout() wrapper that accepts a CallScene and returns a
// LayoutResult with absolute tile positions.
// ---------------------------------------------------------------------------

// ===== Legacy constants (exported for backward compat during migration) =====

export const MIN_STAGE_TILE_ASPECT = 9 / 16;
export const MAX_STAGE_TILE_ASPECT = 16 / 9;
export const DEFAULT_STAGE_TILE_ASPECT = 16 / 9;
export const STAGE_TILE_GAP_PX = 12;

// ===== Legacy types (exported for backward compat during migration) =========

export type StageTileSpec = {
    cid: string;
    aspectRatio: number;
};

export type StageTileLayout = {
    cid: string;
    width: number;
    height: number;
};

export type StageRowLayout = {
    items: StageTileLayout[];
};

// ===== Legacy functions (exact copies from CallRoom.tsx) ====================

export function clampStageTileAspectRatio(ratio?: number | null): number {
    if (!ratio || !Number.isFinite(ratio) || ratio <= 0) {
        return DEFAULT_STAGE_TILE_ASPECT;
    }
    return Math.min(MAX_STAGE_TILE_ASPECT, Math.max(MIN_STAGE_TILE_ASPECT, ratio));
}

export function computeStageLayout(tiles: StageTileSpec[], availableWidth: number, availableHeight: number, gap: number): StageRowLayout[] {
    if (tiles.length === 0 || availableWidth <= 0 || availableHeight <= 0) {
        return [];
    }

    const candidateRows: number[][][] = tiles.length === 1
        ? [[[0]]]
        : tiles.length === 2
            ? [[[0, 1]], [[0], [1]]]
            : [[[0, 1, 2]], [[0, 1], [2]], [[0], [1, 2]], [[0], [1], [2]]];

    let bestLayout: StageRowLayout[] = [];
    let bestHarmonicShortEdge = -1;
    let bestMinShortEdge = -1;
    let bestArea = -1;
    let bestRowCount = Number.POSITIVE_INFINITY;

    for (const rows of candidateRows) {
        const baseHeights = rows.map((row) => {
            const totalAspect = row.reduce((sum, index) => sum + tiles[index].aspectRatio, 0);
            const rowWidth = availableWidth - gap * Math.max(0, row.length - 1);
            return rowWidth > 0 && totalAspect > 0 ? rowWidth / totalAspect : 0;
        });

        const verticalGap = gap * Math.max(0, rows.length - 1);
        const totalBaseHeight = baseHeights.reduce((sum, value) => sum + value, 0);
        if (totalBaseHeight <= 0 || availableHeight <= verticalGap) {
            continue;
        }

        const scale = Math.min(1, (availableHeight - verticalGap) / totalBaseHeight);
        if (scale <= 0) {
            continue;
        }

        const layout = rows.map((row, rowIndex) => {
            const rowHeight = Math.max(1, Math.floor(baseHeights[rowIndex] * scale));
            const items = row.map((index) => {
                const tile = tiles[index];
                return {
                    cid: tile.cid,
                    width: Math.max(1, Math.floor(tile.aspectRatio * rowHeight)),
                    height: rowHeight
                };
            });
            return { items };
        });

        const area = layout.reduce((sum, row) => (
            sum + row.items.reduce((rowArea, tile) => rowArea + tile.width * tile.height, 0)
        ), 0);
        const shortEdges = layout.flatMap((row) => row.items.map((tile) => Math.min(tile.width, tile.height)));
        const minShortEdge = shortEdges.reduce((currentMin, shortEdge) => Math.min(currentMin, shortEdge), Number.POSITIVE_INFINITY);
        const harmonicShortEdge = shortEdges.length / shortEdges.reduce((sum, shortEdge) => sum + (1 / shortEdge), 0);
        const rowCount = layout.length;

        const shortEdgeGainIsMeaningful = harmonicShortEdge > bestHarmonicShortEdge + 6;
        const shortEdgeIsComparable = Math.abs(harmonicShortEdge - bestHarmonicShortEdge) <= 6;
        const minShortEdgeImproved = minShortEdge > bestMinShortEdge + 1;
        const minShortEdgeComparable = Math.abs(minShortEdge - bestMinShortEdge) <= 1;

        if (
            shortEdgeGainIsMeaningful ||
            (
                shortEdgeIsComparable && (
                    rowCount < bestRowCount ||
                    (rowCount === bestRowCount && (
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
            bestHarmonicShortEdge = harmonicShortEdge;
            bestMinShortEdge = minShortEdge;
            bestArea = area;
            bestRowCount = rowCount;
            bestLayout = layout;
        }
    }

    return bestLayout;
}

// ===== New input types ======================================================

export interface CallScene {
    viewportWidth: number;
    viewportHeight: number;
    safeAreaInsets: Insets;
    participants: SceneParticipant[];
    localParticipantId: string;
    activeSpeakerId: string | null;
    pinnedParticipantId: string | null;
    contentSource: ContentSource | null;
    userPrefs: UserLayoutPrefs;
}

export interface SceneParticipant {
    id: string;
    role: 'local' | 'remote';
    videoEnabled: boolean;
    videoAspectRatio: number | null;
}

export interface ContentSource {
    type: 'screenShare' | 'worldCamera' | 'compositeCamera';
    ownerParticipantId: string;
    aspectRatio: number | null;
}

export interface UserLayoutPrefs {
    swappedLocalAndRemote: boolean;
    dominantFit: 'cover' | 'contain';
}

export interface Insets {
    top: number;
    bottom: number;
    left: number;
    right: number;
}

// ===== New output types =====================================================

export type LayoutMode = 'solo' | 'pair' | 'grid' | 'focus' | 'content';
export type FitMode = 'cover' | 'contain';

export interface LayoutResult {
    mode: LayoutMode;
    tiles: TileLayout[];
    localPip: PipLayout | null;
}

export interface TileLayout {
    id: string;
    type: 'participant' | 'contentSource';
    frame: Rect;
    fit: FitMode;
    cornerRadius: number;
    zOrder: number;
}

export interface PipLayout {
    participantId: string;
    frame: Rect;
    fit: FitMode;
    cornerRadius: number;
    anchor: 'topLeft' | 'topRight' | 'bottomLeft' | 'bottomRight';
    zOrder: number;
}

export interface Rect {
    x: number;
    y: number;
    width: number;
    height: number;
}

// ===== Device-group-based layout constants ==================================

const LAYOUT_CONSTANTS = {
    gap: { phone: 8, tablet: 12, desktop: 12 },
    outerPadding: { phone: 8, tablet: 16, desktop: 16 },
    cornerRadius: { phone: 12, tablet: 14, desktop: 16 },
    minTileAspect: 9 / 16,
    maxTileAspect: 16 / 9,
    defaultTileAspect: 16 / 9,
    pipWidthFraction: { phone: 0.24, tablet: 0.20, desktop: 0.16 },
    pipAspectRatio: 3 / 4,
    pipInset: { phone: 12, tablet: 16, desktop: 20 },
    pipCornerRadius: 12,
    primaryRatio: 0.75,
    thumbnailMinSize: { phone: 72, tablet: 96, desktop: 120 },
    harmonicShortEdgeTolerance: 6,
    minShortEdgeTolerance: 1,
};

// ===== Device group classification ==========================================

type DeviceGroup = 'phone' | 'tablet' | 'desktop';

function deviceGroup(viewportWidth: number, viewportHeight: number): DeviceGroup {
    const shortSide = Math.min(viewportWidth, viewportHeight);
    if (shortSide < 600) return 'phone';
    if (shortSide < 1024) return 'tablet';
    return 'desktop';
}

// ===== Mode derivation ======================================================

function deriveMode(scene: CallScene): LayoutMode {
    if (scene.participants.length === 1) return 'solo';
    if (scene.contentSource !== null) return 'content';
    if (scene.pinnedParticipantId !== null) return 'focus';
    if (scene.participants.length === 2) return 'pair';
    return 'grid';
}

// ===== Grid tile absolute positioning =======================================

function gridTilesToAbsolute(
    rows: StageRowLayout[],
    availableX: number,
    availableY: number,
    availableWidth: number,
    availableHeight: number,
    gap: number,
): { cid: string; frame: Rect }[] {
    const totalRowsHeight =
        rows.reduce((sum, row) => sum + row.items[0].height, 0) +
        gap * Math.max(0, rows.length - 1);
    let rowY = availableY + (availableHeight - totalRowsHeight) / 2;

    const result: { cid: string; frame: Rect }[] = [];
    for (const row of rows) {
        const rowWidth =
            row.items.reduce((sum, tile) => sum + tile.width, 0) +
            gap * Math.max(0, row.items.length - 1);
        let tileX = availableX + (availableWidth - rowWidth) / 2;
        const rowHeight = row.items[0].height;

        for (const tile of row.items) {
            result.push({
                cid: tile.cid,
                frame: { x: tileX, y: rowY, width: tile.width, height: rowHeight },
            });
            tileX += tile.width + gap;
        }
        rowY += rowHeight + gap;
    }
    return result;
}

// ===== PIP computation ======================================================

function computePip(
    participantId: string,
    viewportWidth: number,
    viewportHeight: number,
    group: DeviceGroup,
    videoAspectRatio: number | null,
): PipLayout {
    const widthFraction = LAYOUT_CONSTANTS.pipWidthFraction[group];
    const inset = LAYOUT_CONSTANTS.pipInset[group];
    const width = viewportWidth * widthFraction;
    const ar = clampStageTileAspectRatio(videoAspectRatio ?? LAYOUT_CONSTANTS.pipAspectRatio);
    const height = width / ar;

    return {
        participantId,
        frame: {
            x: viewportWidth - inset - width,
            y: viewportHeight - inset - height,
            width,
            height,
        },
        fit: 'cover',
        cornerRadius: LAYOUT_CONSTANTS.pipCornerRadius,
        anchor: 'bottomRight',
        zOrder: 1,
    };
}

// ===== Main computeLayout entry point =======================================

export function computeLayout(scene: CallScene): LayoutResult {
    const mode = deriveMode(scene);
    const group = deviceGroup(scene.viewportWidth, scene.viewportHeight);
    const gap = LAYOUT_CONSTANTS.gap[group];
    const outerPadding = LAYOUT_CONSTANTS.outerPadding[group];
    const cornerRadius = LAYOUT_CONSTANTS.cornerRadius[group];

    // Available area after safe-area insets
    const availableX = scene.safeAreaInsets.left;
    const availableY = scene.safeAreaInsets.top;
    const availableWidth =
        scene.viewportWidth - scene.safeAreaInsets.left - scene.safeAreaInsets.right;
    const availableHeight =
        scene.viewportHeight - scene.safeAreaInsets.top - scene.safeAreaInsets.bottom;

    const localParticipant = scene.participants.find(
        (p) => p.id === scene.localParticipantId,
    );

    switch (mode) {
        // ----- solo: no remote tiles, local shown as PIP -----------------------
        case 'solo': {
            return {
                mode,
                tiles: [],
                localPip: localParticipant
                    ? computePip(
                          localParticipant.id,
                          scene.viewportWidth,
                          scene.viewportHeight,
                          group,
                          localParticipant.videoAspectRatio,
                      )
                    : null,
            };
        }

        // ----- pair: one tile fills area, other as PIP --------------------------
        case 'pair': {
            const remoteParticipant = scene.participants.find(
                (p) => p.role === 'remote',
            );

            const dominant = scene.userPrefs.swappedLocalAndRemote
                ? localParticipant
                : remoteParticipant;
            const pipParticipant = scene.userPrefs.swappedLocalAndRemote
                ? remoteParticipant
                : localParticipant;

            const tiles: TileLayout[] = dominant
                ? [
                      {
                          id: dominant.id,
                          type: 'participant',
                          frame: {
                              x: availableX,
                              y: availableY,
                              width: availableWidth,
                              height: availableHeight,
                          },
                          fit: scene.userPrefs.dominantFit,
                          cornerRadius: 0,
                          zOrder: 0,
                      },
                  ]
                : [];

            const localPip = pipParticipant
                ? computePip(
                      pipParticipant.id,
                      scene.viewportWidth,
                      scene.viewportHeight,
                      group,
                      pipParticipant.videoAspectRatio,
                  )
                : null;

            return { mode, tiles, localPip };
        }

        // ----- grid / focus / content: harmonic-mean grid -----------------------
        case 'grid':
        case 'focus':
        case 'content': {
            const remoteParticipants = scene.participants.filter(
                (p) => p.role === 'remote',
            );

            const stageTiles: StageTileSpec[] = remoteParticipants.map((p) => ({
                cid: p.id,
                aspectRatio: clampStageTileAspectRatio(p.videoAspectRatio),
            }));

            // Grid available area = viewport minus safe area minus outerPadding
            const gridX = availableX + outerPadding;
            const gridY = availableY + outerPadding;
            const gridWidth = availableWidth - outerPadding * 2;
            const gridHeight = availableHeight - outerPadding * 2;

            const rows = computeStageLayout(stageTiles, gridWidth, gridHeight, gap);

            const absoluteTiles = gridTilesToAbsolute(
                rows,
                gridX,
                gridY,
                gridWidth,
                gridHeight,
                gap,
            );

            const tiles: TileLayout[] = absoluteTiles.map((t, index) => ({
                id: t.cid,
                type: 'participant' as const,
                frame: t.frame,
                fit: 'cover' as FitMode,
                cornerRadius,
                zOrder: index,
            }));

            const localPip = localParticipant
                ? computePip(
                      localParticipant.id,
                      scene.viewportWidth,
                      scene.viewportHeight,
                      group,
                      localParticipant.videoAspectRatio,
                  )
                : null;

            return { mode, tiles, localPip };
        }
    }
}
