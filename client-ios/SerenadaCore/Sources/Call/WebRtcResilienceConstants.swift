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

    // MARK: - Snapshot

    public static let snapshotPrepareTimeoutMs = 2_000
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
