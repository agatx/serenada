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
