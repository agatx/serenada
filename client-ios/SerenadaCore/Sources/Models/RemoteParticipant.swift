import Foundation

public struct RemoteParticipant: Identifiable, Equatable {
    public let cid: String
    public var displayName: String?
    /// Host-supplied stable identity passed via the remote peer's
    /// ``SerenadaCore/join(url:displayName:peerId:)``.
    public var peerId: String?
    public var audioEnabled: Bool
    public var videoEnabled: Bool
    public var connectionState: SerenadaPeerConnectionState
    /// Smoothed voice activity level (0..1) for this peer's inbound audio.
    /// Updated at ~10 Hz while the call is active. Always 0 when
    /// ``audioEnabled`` is false.
    public var audioLevel: Float

    public var id: String { cid }

    public init(cid: String, displayName: String? = nil, peerId: String? = nil, audioEnabled: Bool = true, videoEnabled: Bool, connectionState: SerenadaPeerConnectionState, audioLevel: Float = 0) {
        self.cid = cid
        self.displayName = displayName
        self.peerId = peerId
        self.audioEnabled = audioEnabled
        self.videoEnabled = videoEnabled
        self.connectionState = connectionState
        self.audioLevel = audioLevel
    }
}
