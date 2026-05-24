/**
 * Canonical WebRTC resilience constants shared across all Serenada clients.
 * @internal
 * @module
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
export const ICE_FETCH_RETRY_DELAYS_MS = [0, 1000, 2000, 4000];

// Reconnect token
export const RECONNECT_TOKEN_TTL_FALLBACK_MS = 1200000;
export const RECONNECT_TOKEN_REFRESH_LEEWAY_MS = 600000;

// Session
export const ENDING_SCREEN_MS = 3000;

// Snapshot
export const SNAPSHOT_PREPARE_TIMEOUT_MS = 2000;

// Foreground / Doze recovery
// After the app returns to foreground from background, the SDK issues a
// synthetic ping and waits this long for a pong before force-closing the
// transport and triggering the normal reconnect path.
export const FOREGROUND_FORCE_PING_TIMEOUT_MS = 2000;

// Post-reconnect snapshot resync
// After signaling reconnects, the SDK waits this long for an authoritative
// `room_state` snapshot before falling back to firing ICE restart against the
// last-known peer map.
export const EPOCH_RESYNC_TIMEOUT_MS = 5000;

// Suspended-peer presentation
// After a remote peer transitions to `signalingStatus="suspended"`, the SDK
// starts a per-CID UI presentation timer. When this timer expires the
// participant is flagged `presumedLost=true` so call UIs can move them out
// of the active grid. The peer connection itself stays open so media can
// resume immediately if the peer reattaches.
export const PEER_SUSPENDED_UI_TIMEOUT_MS = 30000;

// Server hard-eviction window
// Mirrors `suspendHardEvictionTimeout` on the Go server. Used SDK-side to
// compute `estimatedHardEvictionAtMs` for the `signalingState.suspended`
// surface so apps can render a countdown.
export const SUSPEND_HARD_EVICTION_TIMEOUT_MS = 600000;

// Media-liveness emission cadence
// Active SDKs broadcast `media_liveness{cids:[..]}` every interval for
// remote CIDs whose inbound media is currently flowing. The server uses
// this hint to defer hard-eviction of suspended peers whose media is still
// being received. 10s leaves headroom under the server's 30s freshness
// window (`mediaLivenessFreshnessWindow`) for missed emissions.
export const MEDIA_LIVENESS_INTERVAL_MS = 10000;

// Connection Status
export const CONNECTION_RETRYING_DELAY_MS = 10_000;

// Local Video Recovery
export const LOCAL_VIDEO_RESUME_GAP_MS = 15_000;
export const LOCAL_VIDEO_HEARTBEAT_INTERVAL_MS = 5_000;
