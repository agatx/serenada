import Foundation

public struct RemoteParticipant: Identifiable, Equatable {
    public let cid: String
    public var videoEnabled: Bool
    public var connectionState: SerenadaPeerConnectionState

    public var id: String { cid }

    public init(cid: String, videoEnabled: Bool, connectionState: SerenadaPeerConnectionState) {
        self.cid = cid
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
    }
}
