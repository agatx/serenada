package app.serenada.core.call

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
    const val AUDIO_COORDINATOR_TIMEOUT_MS = 10_000L
    const val JOIN_CONNECT_KICKSTART_MS = 1_200L
    const val JOIN_RECOVERY_MS = 4_000L
    const val JOIN_HARD_TIMEOUT_MS = 15_000L

    // ── Peer Connection ──────────────────────────────────────────────
    const val OFFER_TIMEOUT_MS = 8_000L
    const val ICE_RESTART_COOLDOWN_MS = 10_000L
    const val ICE_CANDIDATE_BUFFER_MAX = 50
    const val OUTBOUND_MEDIA_WATCHDOG_INTERVAL_MS = 5_000L
    const val OUTBOUND_MEDIA_STALL_SAMPLES = 2
    const val OUTBOUND_MEDIA_RECOVERY_COOLDOWN_MS = 30_000L

    // ── TURN ─────────────────────────────────────────────────────────
    const val TURN_FETCH_TIMEOUT_MS = 2_000L
    const val TURN_REFRESH_TRIGGER_RATIO = 0.8
    val ICE_FETCH_RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L)

    // ── Reconnect token ──────────────────────────────────────────────
    const val RECONNECT_TOKEN_TTL_FALLBACK_MS = 1_200_000L
    const val RECONNECT_TOKEN_REFRESH_LEEWAY_MS = 600_000L

    // ── Snapshot ─────────────────────────────────────────────────────
    const val SNAPSHOT_PREPARE_TIMEOUT_MS = 2_000L

    // ── Foreground / Doze recovery ───────────────────────────────────
    // After the app returns to foreground from background, the SDK issues a
    // synthetic ping and waits this long for a pong before force-closing the
    // transport and triggering the normal reconnect path.
    const val FOREGROUND_FORCE_PING_TIMEOUT_MS = 2_000L

    // ── Post-reconnect snapshot resync ───────────────────────────────
    // After signaling reconnects, the SDK waits this long for an authoritative
    // `room_state` snapshot before falling back to firing ICE restart against
    // the last-known peer map.
    const val EPOCH_RESYNC_TIMEOUT_MS = 5_000L

    // ── Suspended-peer presentation ──────────────────────────────────
    // After a remote peer transitions to `signalingStatus=SUSPENDED`, the SDK
    // starts a per-CID UI presentation timer. When this timer expires the
    // participant is flagged `presumedLost=true` so call UIs can move them
    // out of the active grid. The peer connection itself stays open so media
    // can resume immediately if the peer reattaches.
    const val PEER_SUSPENDED_UI_TIMEOUT_MS = 30_000L

    // ── Server hard-eviction window ──────────────────────────────────
    // Mirrors `suspendHardEvictionTimeout` on the Go server. Used SDK-side to
    // compute `estimatedHardEvictionAtMs` for the `SignalingState.Suspended`
    // surface so apps can render a countdown.
    const val SUSPEND_HARD_EVICTION_TIMEOUT_MS = 600_000L

    // ── Media-liveness emission cadence ──────────────────────────────
    // Active SDKs broadcast `media_liveness{cids:[..]}` every interval for
    // remote CIDs whose inbound media is currently flowing. The server uses
    // this hint to defer hard-eviction of suspended peers whose media is
    // still being received. 10s leaves headroom under the server's 30s
    // freshness window (`mediaLivenessFreshnessWindow`) for missed
    // emissions.
    const val MEDIA_LIVENESS_INTERVAL_MS = 10_000L
}
