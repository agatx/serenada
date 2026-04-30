import Foundation

/// Canonical WebRTC resilience constants shared across all Serenada clients.
/// Run `node scripts/check-resilience-constants.mjs` to verify cross-platform parity.
public enum WebRtcResilience {

    // MARK: - Signaling

    public static let reconnectBackoffBaseMs = 500
    public static let reconnectBackoffCapMs = 5_000
    public static let connectTimeoutMs = 2_000
    public static let pingIntervalMs = 12_000
    public static let pongMissThreshold = 2
    public static let wsFallbackConsecutiveFailures = 3

    // MARK: - Join

    public static let joinConnectKickstartMs = 1_200
    public static let joinRecoveryMs = 4_000
    public static let joinHardTimeoutMs = 15_000

    // MARK: - Peer Connection

    public static let offerTimeoutMs = 8_000
    public static let iceRestartCooldownMs = 10_000
    public static let nonHostFallbackDelayMs = 4_000
    public static let nonHostFallbackMaxAttempts = 2
    public static let iceCandidateBufferMax = 50

    // MARK: - TURN

    public static let turnFetchTimeoutMs = 2_000
    public static let turnRefreshTriggerRatio = 0.8
    public static let iceFetchRetryDelaysMs = [0, 1_000, 2_000, 4_000]

    // MARK: - Snapshot

    public static let snapshotPrepareTimeoutMs = 2_000

    // MARK: - Foreground / Doze recovery

    /// After the app returns to foreground from background, the SDK issues a
    /// synthetic ping and waits this long for a pong before force-closing the
    /// transport and triggering the normal reconnect path.
    public static let foregroundForcePingTimeoutMs = 2_000

    // MARK: - Post-reconnect snapshot resync

    /// After signaling reconnects, the SDK waits this long for an authoritative
    /// `room_state` snapshot before falling back to firing ICE restart against
    /// the last-known peer map.
    public static let epochResyncTimeoutMs = 5_000

    // MARK: - Suspended-peer presentation

    /// After a remote peer transitions to `signalingStatus=.suspended`, the
    /// SDK starts a per-CID UI presentation timer. When this timer expires
    /// the participant is flagged `presumedLost=true` so call UIs can move
    /// them out of the active grid. The peer connection itself stays open
    /// so media can resume immediately if the peer reattaches.
    public static let peerSuspendedUiTimeoutMs = 30_000

    // MARK: - Server hard-eviction window

    /// Mirrors `suspendHardEvictionTimeout` on the Go server. Used SDK-side
    /// to compute `estimatedHardEvictionAtMs` for the
    /// `SignalingState.suspended` surface so apps can render a countdown.
    public static let suspendHardEvictionTimeoutMs = 600_000

    // MARK: - Media-liveness emission cadence

    /// Active SDKs broadcast `media_liveness{cids:[..]}` every interval
    /// for remote CIDs whose inbound media is currently flowing. The
    /// server uses this hint to defer hard-eviction of suspended peers
    /// whose media is still being received. 10s leaves headroom under the
    /// server's 30s freshness window (`mediaLivenessFreshnessWindow`) for
    /// missed emissions.
    public static let mediaLivenessIntervalMs = 10_000
}

// MARK: - Nanosecond convenience accessors

extension WebRtcResilience {
    public static var reconnectBackoffBaseNs: UInt64 { UInt64(reconnectBackoffBaseMs) * 1_000_000 }
    public static var reconnectBackoffCapNs: UInt64 { UInt64(reconnectBackoffCapMs) * 1_000_000 }
    public static var connectTimeoutNs: UInt64 { UInt64(connectTimeoutMs) * 1_000_000 }
    public static var pingIntervalNs: UInt64 { UInt64(pingIntervalMs) * 1_000_000 }
    public static var joinConnectKickstartNs: UInt64 { UInt64(joinConnectKickstartMs) * 1_000_000 }
    public static var joinRecoveryNs: UInt64 { UInt64(joinRecoveryMs) * 1_000_000 }
    public static var joinHardTimeoutNs: UInt64 { UInt64(joinHardTimeoutMs) * 1_000_000 }
    public static var offerTimeoutNs: UInt64 { UInt64(offerTimeoutMs) * 1_000_000 }
    public static var iceRestartCooldownNs: UInt64 { UInt64(iceRestartCooldownMs) * 1_000_000 }
    public static var nonHostFallbackDelayNs: UInt64 { UInt64(nonHostFallbackDelayMs) * 1_000_000 }
    public static var turnFetchTimeoutNs: UInt64 { UInt64(turnFetchTimeoutMs) * 1_000_000 }
    public static var snapshotPrepareTimeoutNs: UInt64 { UInt64(snapshotPrepareTimeoutMs) * 1_000_000 }
}
