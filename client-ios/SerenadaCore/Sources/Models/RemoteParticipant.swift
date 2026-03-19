import Foundation

struct RemoteParticipant: Identifiable, Equatable {
    let cid: String
    var videoEnabled: Bool
    var connectionState: String

    var id: String { cid }

    init(cid: String, videoEnabled: Bool, connectionState: String) {
        self.cid = cid
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
    }
}
