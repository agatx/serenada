/**
 * @serenada/core — headless call engine.
 * Vanilla TypeScript — no React dependency.
 */
export const SERENADA_CORE_VERSION = '0.1.0';

// Public API
export { SerenadaCore } from './SerenadaCore.js';
export { SerenadaSession } from './SerenadaSession.js';
export { SerenadaDiagnostics } from './SerenadaDiagnostics.js';

// Factory functions (match documented API)
import { SerenadaCore as _SerenadaCore } from './SerenadaCore.js';
import { SerenadaDiagnostics as _SerenadaDiagnostics } from './SerenadaDiagnostics.js';
import type { SerenadaConfig } from './types.js';
export function createSerenadaCore(config: SerenadaConfig): _SerenadaCore { return new _SerenadaCore(config); }
export function createSerenadaDiagnostics(config: SerenadaConfig): _SerenadaDiagnostics { return new _SerenadaDiagnostics(config); }

// Public types
export type {
    CallPhase,
    ConnectionStatus,
    CameraMode,
    MediaCapability,
    Participant,
    LocalParticipant,
    CallError,
    CallState,
    SerenadaConfig,
    CreateRoomResult,
    SerenadaSessionHandle,
    CallStats,
    DiagnosticCheckResult,
    DiagnosticsReport,
} from './types.js';

// Public utilities
export {
    computeLayout, computeStageLayout, clampStageTileAspectRatio,
    MIN_STAGE_TILE_ASPECT, MAX_STAGE_TILE_ASPECT, DEFAULT_STAGE_TILE_ASPECT, STAGE_TILE_GAP_PX,
} from './layout/computeLayout.js';
export type {
    CallScene,
    SceneParticipant,
    ContentSource,
    UserLayoutPrefs,
    Insets,
    LayoutMode,
    FitMode,
    LayoutResult,
    TileLayout,
    PipLayout,
    Rect,
    StageTileSpec,
    StageTileLayout,
    StageRowLayout,
} from './layout/computeLayout.js';

// Signaling types re-exported for advanced usage
export type { TransportKind } from './signaling/transports/types.js';
export type { RoomState, SignalingMessage } from './signaling/types.js';
export type { RoomStatus, RoomStatuses } from './signaling/roomStatuses.js';
export { getRoomStatusState, mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './signaling/roomStatuses.js';
export { parseTransportOrder } from './signaling/transportConfig.js';

// Resilience constants
export {
    RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS,
    CONNECT_TIMEOUT_MS, PING_INTERVAL_MS, PONG_MISS_THRESHOLD,
    WS_FALLBACK_CONSECUTIVE_FAILURES,
    JOIN_CONNECT_KICKSTART_MS, JOIN_RECOVERY_MS, JOIN_HARD_TIMEOUT_MS,
    OFFER_TIMEOUT_MS, ICE_RESTART_COOLDOWN_MS,
    NON_HOST_FALLBACK_DELAY_MS, NON_HOST_FALLBACK_MAX_ATTEMPTS,
    ICE_CANDIDATE_BUFFER_MAX,
    TURN_FETCH_TIMEOUT_MS, TURN_REFRESH_TRIGGER_RATIO,
    SNAPSHOT_PREPARE_TIMEOUT_MS,
    CONNECTION_RETRYING_DELAY_MS,
    LOCAL_VIDEO_RESUME_GAP_MS, LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS,
} from './constants.js';

// Local video recovery utilities
export { shouldForceLocalVideoRefresh, shouldRecoverLocalVideo } from './media/localVideoRecovery.js';

// Room API
export { createRoomId } from './api/roomApi.js';
export { buildApiUrl, buildRoomUrl, resolveServerBaseUrl, resolveServerUrls } from './serverUrls.js';

// Advanced host-app usage
export { SignalingEngine } from './signaling/SignalingEngine.js';
export type { SignalingEngineConfig } from './signaling/SignalingEngine.js';
