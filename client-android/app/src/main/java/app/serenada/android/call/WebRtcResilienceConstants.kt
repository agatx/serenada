package app.serenada.android.call

/**
 * Canonical WebRTC resilience constants shared across all Serenada clients.
 * Run `node scripts/check-resilience-constants.mjs` to verify cross-platform parity.
 */
object WebRtcResilienceConstants {

    // ── Signaling ────────────────────────────────────────────────────
    const val RECONNECT_BACKOFF_BASE_MS = 500L
    const val RECONNECT_BACKOFF_CAP_MS = 5_000L
    const val CONNECT_TIMEOUT_MS = 2_000L
    const val PING_INTERVAL_MS = 12_000L
    const val PONG_MISS_THRESHOLD = 2
    const val WS_FALLBACK_CONSECUTIVE_FAILURES = 3

    // ── Join ─────────────────────────────────────────────────────────
    const val JOIN_PUSH_ENDPOINT_WAIT_MS = 250L
    const val JOIN_CONNECT_KICKSTART_MS = 1_200L
    const val JOIN_RECOVERY_MS = 4_000L
    const val JOIN_HARD_TIMEOUT_MS = 15_000L

    // ── Peer Connection ──────────────────────────────────────────────
    const val OFFER_TIMEOUT_MS = 8_000L
    const val ICE_RESTART_COOLDOWN_MS = 10_000L
    const val NON_HOST_FALLBACK_DELAY_MS = 4_000L
    const val NON_HOST_FALLBACK_MAX_ATTEMPTS = 2
    const val ICE_CANDIDATE_BUFFER_MAX = 50

    // ── TURN ─────────────────────────────────────────────────────────
    const val TURN_FETCH_TIMEOUT_MS = 2_000L
    const val TURN_REFRESH_TRIGGER_RATIO = 0.8

    // ── Snapshot ─────────────────────────────────────────────────────
    const val SNAPSHOT_PREPARE_TIMEOUT_MS = 2_000L
}
