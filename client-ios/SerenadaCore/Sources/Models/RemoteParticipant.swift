import Foundation

public struct RemoteParticipant: Identifiable, Equatable {
    public let cid: String
    public var displayName: String?
    public var audioEnabled: Bool
    public var videoEnabled: Bool
    public var connectionState: SerenadaPeerConnectionState

    public var id: String { cid }

    public init(cid: String, displayName: String? = nil, audioEnabled: Bool = true, videoEnabled: Bool, connectionState: SerenadaPeerConnectionState) {
        self.cid = cid
        self.displayName = displayName
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
    }
}
