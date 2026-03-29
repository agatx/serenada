import Foundation

protocol PeerIdentifiable {
    var peerIdentifier: String { get }
}

extension Participant: PeerIdentifiable {
    var peerIdentifier: String { cid }
}

extension SignalingProviderParticipant: PeerIdentifiable {
    var peerIdentifier: String { peerId }
}

func dedupeParticipants<T: PeerIdentifiable>(
    participants: [T],
    localPeerId: String?,
    makeLocalParticipant: (String) -> T
) -> [T] {
    var deduped: [String: T] = [:]
    var order: [String] = []
    for participant in participants where !participant.peerIdentifier.isEmpty {
        if deduped[participant.peerIdentifier] == nil {
            order.append(participant.peerIdentifier)
        }
        deduped[participant.peerIdentifier] = participant
    }
    if let localPeerId, !localPeerId.isEmpty, deduped[localPeerId] == nil {
        deduped[localPeerId] = makeLocalParticipant(localPeerId)
        order.append(localPeerId)
    }
    return order.compactMap { deduped[$0] }
}

/// Resolves the host peer ID from a priority chain:
/// explicit provider value → current session host → local peer → first participant.
/// Returns `nil` only when `participants` is empty and all candidates are nil.
func resolveHostPeerId(
    explicitHostPeerId: String?,
    participants: [Participant],
    currentHostPeerId: String?,
    localPeerId: String?
) -> String? {
    let participantIds = Set(participants.map(\.cid))
    for candidate in [explicitHostPeerId, currentHostPeerId, localPeerId] {
        if let candidate, participantIds.contains(candidate) {
            return candidate
        }
    }
    return participants.first?.cid
}
