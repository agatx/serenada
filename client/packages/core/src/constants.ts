/**
 * Canonical WebRTC resilience constants shared across all Serenada clients.
 */

// Signaling
export const RECONNECT_BACKOFF_BASE_MS = 500;
export const RECONNECT_BACKOFF_CAP_MS = 5000;
export const CONNECT_TIMEOUT_MS = 2000;
export const PING_INTERVAL_MS = 12000;
export const PONG_MISS_THRESHOLD = 2;
export const WS_FALLBACK_CONSECUTIVE_FAILURES = 3;

// Join
export const JOIN_CONNECT_KICKSTART_MS = 1200;
export const JOIN_RECOVERY_MS = 4000;
export const JOIN_HARD_TIMEOUT_MS = 15000;

// Peer Connection
export const OFFER_TIMEOUT_MS = 8000;
export const ICE_RESTART_COOLDOWN_MS = 10000;
export const NON_HOST_FALLBACK_DELAY_MS = 4000;
export const NON_HOST_FALLBACK_MAX_ATTEMPTS = 2;
export const ICE_CANDIDATE_BUFFER_MAX = 50;

// TURN
export const TURN_FETCH_TIMEOUT_MS = 2000;
export const TURN_REFRESH_TRIGGER_RATIO = 0.8;

// Snapshot
export const SNAPSHOT_PREPARE_TIMEOUT_MS = 2000;

// Connection Status
export const CONNECTION_RETRYING_DELAY_MS = 10_000;

// Local Video Recovery
export const LOCAL_VIDEO_RESUME_GAP_MS = 15_000;
export const LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS = 5_000;
