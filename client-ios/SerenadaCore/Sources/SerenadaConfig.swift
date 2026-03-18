import Foundation

public enum SerenadaTransport: String, Equatable, Sendable {
    case ws
    case sse
}

public struct SerenadaConfig: Equatable, Sendable {
    public let serverHost: String
    public var defaultAudioEnabled: Bool
    public var defaultVideoEnabled: Bool
    public var transports: [SerenadaTransport]

    public init(
        serverHost: String,
        defaultAudioEnabled: Bool = true,
        defaultVideoEnabled: Bool = true,
        transports: [SerenadaTransport] = [.ws, .sse]
    ) {
        self.serverHost = serverHost
        self.defaultAudioEnabled = defaultAudioEnabled
        self.defaultVideoEnabled = defaultVideoEnabled
        self.transports = transports
    }
}
