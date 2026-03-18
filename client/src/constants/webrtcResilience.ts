/**
 * Canonical WebRTC resilience constants shared across all Serenada clients.
 * Run `node scripts/check-resilience-constants.mjs` to verify cross-platform parity.
 *
 * Re-exported from @serenada/core — single source of truth.
 */
export {
    RECONNECT_BACKOFF_BASE_MS,
    RECONNECT_BACKOFF_CAP_MS,
    CONNECT_TIMEOUT_MS,
    PING_INTERVAL_MS,
    PONG_MISS_THRESHOLD,
    WS_FALLBACK_CONSECUTIVE_FAILURES,
    JOIN_CONNECT_KICKSTART_MS,
    JOIN_RECOVERY_MS,
    JOIN_HARD_TIMEOUT_MS,
    OFFER_TIMEOUT_MS,
    ICE_RESTART_COOLDOWN_MS,
    NON_HOST_FALLBACK_DELAY_MS,
    NON_HOST_FALLBACK_MAX_ATTEMPTS,
    ICE_CANDIDATE_BUFFER_MAX,
    TURN_FETCH_TIMEOUT_MS,
    TURN_REFRESH_TRIGGER_RATIO,
    SNAPSHOT_PREPARE_TIMEOUT_MS,
} from '@serenada/core';
