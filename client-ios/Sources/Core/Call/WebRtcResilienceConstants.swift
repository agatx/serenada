import Foundation

/// Canonical WebRTC resilience constants shared across all Serenada clients.
/// Run `node scripts/check-resilience-constants.mjs` to verify cross-platform parity.
enum WebRtcResilience {

    // MARK: - Signaling

    static let reconnectBackoffBaseMs = 500
    static let reconnectBackoffCapMs = 5_000
    static let connectTimeoutMs = 2_000
    static let pingIntervalMs = 12_000
    static let pongMissThreshold = 2
    static let wsFallbackConsecutiveFailures = 3

    // MARK: - Join

    static let joinPushEndpointWaitMs = 250
    static let joinConnectKickstartMs = 1_200
    static let joinRecoveryMs = 4_000
    static let joinHardTimeoutMs = 15_000

    // MARK: - Peer Connection

    static let offerTimeoutMs = 8_000
    static let iceRestartCooldownMs = 10_000
    static let nonHostFallbackDelayMs = 4_000
    static let nonHostFallbackMaxAttempts = 2
    static let iceCandidateBufferMax = 50

    // MARK: - TURN

    static let turnFetchTimeoutMs = 2_000
    static let turnRefreshTriggerRatio = 0.8

    // MARK: - Snapshot

    static let snapshotPrepareTimeoutMs = 2_000
}

// MARK: - Nanosecond convenience accessors

extension WebRtcResilience {
    static var reconnectBackoffBaseNs: UInt64 { UInt64(reconnectBackoffBaseMs) * 1_000_000 }
    static var reconnectBackoffCapNs: UInt64 { UInt64(reconnectBackoffCapMs) * 1_000_000 }
    static var connectTimeoutNs: UInt64 { UInt64(connectTimeoutMs) * 1_000_000 }
    static var pingIntervalNs: UInt64 { UInt64(pingIntervalMs) * 1_000_000 }
    static var joinPushEndpointWaitNs: UInt64 { UInt64(joinPushEndpointWaitMs) * 1_000_000 }
    static var joinConnectKickstartNs: UInt64 { UInt64(joinConnectKickstartMs) * 1_000_000 }
    static var joinRecoveryNs: UInt64 { UInt64(joinRecoveryMs) * 1_000_000 }
    static var joinHardTimeoutNs: UInt64 { UInt64(joinHardTimeoutMs) * 1_000_000 }
    static var offerTimeoutNs: UInt64 { UInt64(offerTimeoutMs) * 1_000_000 }
    static var iceRestartCooldownNs: UInt64 { UInt64(iceRestartCooldownMs) * 1_000_000 }
    static var nonHostFallbackDelayNs: UInt64 { UInt64(nonHostFallbackDelayMs) * 1_000_000 }
    static var turnFetchTimeoutNs: UInt64 { UInt64(turnFetchTimeoutMs) * 1_000_000 }
    static var snapshotPrepareTimeoutNs: UInt64 { UInt64(snapshotPrepareTimeoutMs) * 1_000_000 }
}
