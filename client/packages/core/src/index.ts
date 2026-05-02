/**
 * @serenada/core — headless call engine.
 * Vanilla TypeScript — no React dependency.
 */
export const SERENADA_CORE_VERSION = '0.6.8';

// Public API
export { SerenadaCore } from './SerenadaCore.js';
export { SerenadaSession } from './SerenadaSession.js';
export { SerenadaDiagnostics } from './SerenadaDiagnostics.js';
export { RoomWatcher } from './RoomWatcher.js';
export { SerenadaServerProvider } from './SerenadaServerProvider.js';
export { SignalingProviderEmitter } from './SignalingProvider.js';

// Factory functions (match documented API)
import { SerenadaCore as _SerenadaCore } from './SerenadaCore.js';
import { SerenadaDiagnostics as _SerenadaDiagnostics } from './SerenadaDiagnostics.js';
import type { SerenadaConfig } from './types.js';
export function createSerenadaCore(config: SerenadaConfig): _SerenadaCore { return new _SerenadaCore(config); }
export function createSerenadaDiagnostics(config: SerenadaConfig): _SerenadaDiagnostics { return new _SerenadaDiagnostics(config); }

// Public types
export { DEFAULT_CAMERA_MODES } from './types.js';
export type {
    CallPhase,
    ConnectionStatus,
    ActiveTransport,
    CameraMode,
    ConfigurableCameraMode,
    MediaCapability,
    PeerConnectionState,
    Participant,
    LocalParticipant,
    CallError,
    CallErrorCode,
    CallState,
    SerenadaConfig,
    CreateRoomResult,
    SerenadaSessionHandle,
    CallStats,
    DiagnosticCheckResult,
    DiagnosticsReport,
    CheckOutcome,
    ConnectivityReport,
    IceProbeReport,
    RoomOccupancy,
    RoomWatcherState,
    SerenadaLogLevel,
    SerenadaLogger,
} from './types.js';
export type { RecoveryRecord } from './recoveryStorage.js';
export type {
    ProviderCapabilities,
    ConnectionInfo,
    JoinOptions,
    SignalingProviderParticipant,
    JoinedEvent,
    RoomStateEvent,
    PeerEvent,
    PeerMessage,
    RoomEndedEvent,
    SignalingErrorEvent,
    SignalingProviderEventMap,
    SignalingProviderEventName,
    SignalingProvider,
} from './SignalingProvider.js';

export { ConsoleSerenadaLogger } from './ConsoleLogger.js';

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

/** @internal Signaling types re-exported for advanced usage */
export type { TransportKind } from './signaling/transports/types.js';
/** @internal */
export type { RoomState, SignalingMessage } from './signaling/types.js';
/** @internal */
export type {
    JoinedPayload,
    ErrorPayload,
    TurnRefreshedPayload,
    OfferPayload,
    AnswerPayload,
    IceCandidatePayload,
} from './signaling/payloads.js';
/** @internal */
export {
    parseJoinedPayload,
    parseRoomStatePayload,
    parseErrorPayload,
    parseTurnRefreshedPayload,
    parseOfferPayload,
    parseAnswerPayload,
    parseIceCandidatePayload,
} from './signaling/payloads.js';
/** @internal */
export type { RoomStatus, RoomStatuses } from './signaling/roomStatuses.js';
/** @internal */
export { getRoomStatusState, mergeRoomStatusesPayload, mergeRoomStatusUpdatePayload } from './signaling/roomStatuses.js';
/** @internal */
export { parseTransportOrder } from './signaling/transportConfig.js';

/**
 * @internal
 * Resilience constants
 */
export {
    RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS,
    CONNECT_TIMEOUT_MS, PING_INTERVAL_MS, PONG_MISS_THRESHOLD,
    WS_FALLBACK_CONSECUTIVE_FAILURES,
    JOIN_CONNECT_KICKSTART_MS, JOIN_RECOVERY_MS, JOIN_HARD_TIMEOUT_MS,
    OFFER_TIMEOUT_MS, ICE_RESTART_COOLDOWN_MS,
    NON_HOST_FALLBACK_DELAY_MS, NON_HOST_FALLBACK_MAX_ATTEMPTS,
    ICE_CANDIDATE_BUFFER_MAX,
    TURN_FETCH_TIMEOUT_MS, TURN_REFRESH_TRIGGER_RATIO, ICE_FETCH_RETRY_DELAYS_MS,
    SNAPSHOT_PREPARE_TIMEOUT_MS,
    CONNECTION_RETRYING_DELAY_MS,
    LOCAL_VIDEO_RESUME_GAP_MS, LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS,
} from './constants.js';

/** @internal Local video recovery utilities */
export { shouldForceLocalVideoRefresh, shouldRecoverLocalVideo } from './media/localVideoRecovery.js';

// Audio level monitoring (used by call UI for activity indicators)
export { AudioLevelMonitor } from './media/AudioLevelMonitor.js';
export type { AudioLevelMonitorOptions } from './media/AudioLevelMonitor.js';

/** @internal */
export { createRoomId } from './api/roomApi.js';
/** @internal */
export { buildApiUrl, buildRoomUrl, resolveServerBaseUrl, resolveServerUrls } from './serverUrls.js';

/** @internal Advanced host-app usage */
export { SignalingEngine } from './signaling/SignalingEngine.js';
/** @internal */
export type { SignalingEngineConfig } from './signaling/SignalingEngine.js';
