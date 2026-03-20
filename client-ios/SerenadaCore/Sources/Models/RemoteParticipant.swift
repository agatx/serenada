import Foundation

public struct RemoteParticipant: Identifiable, Equatable {
    public let cid: String
    public var videoEnabled: Bool
    public var connectionState: String

    public var id: String { cid }

    public init(cid: String, videoEnabled: Bool, connectionState: String) {
        self.cid = cid
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
    }
}
