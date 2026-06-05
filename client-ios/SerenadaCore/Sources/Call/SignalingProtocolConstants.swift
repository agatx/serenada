import Foundation

/// Canonical signaling protocol wire constants shared across Serenada clients.
/// Run `node scripts/check-signaling-protocol-constants.mjs` to verify cross-platform parity.
enum SignalingProtocolConstants {
    static let mediaRestartReasonLocalTrackNegotiation = "local track negotiation"
}
