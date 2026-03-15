import Foundation

struct RemoteParticipant: Identifiable, Equatable {
    let cid: String
    var videoEnabled: Bool
    var connectionState: String

    var id: String { cid }
}
