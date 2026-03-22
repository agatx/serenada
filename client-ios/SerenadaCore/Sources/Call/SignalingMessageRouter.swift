import Foundation

@MainActor
final class SignalingMessageRouter {
    // State readers
    private let getClientId: () -> String?

    // Callbacks for mutations
    private let onJoined: (_ cid: String?, _ payload: JoinedPayload, _ rawPayload: JSONValue?) -> Void
    private let onRoomState: (_ payload: JSONValue?) -> Void
    private let onRoomEnded: () -> Void
    private let onPong: () -> Void
    private let onTurnRefreshed: (_ payload: JSONValue?) -> Void
    private let onSignalingPayload: (_ message: SignalingMessage) -> Void
    private let onContentState: (_ payload: ContentStatePayload) -> Void
    private let onError: (_ error: CallError) -> Void
    private let sendMessage: (_ type: String, _ payload: JSONValue?, _ to: String?) -> Void

    init(
        getClientId: @escaping () -> String?,
        onJoined: @escaping (_ cid: String?, _ payload: JoinedPayload, _ rawPayload: JSONValue?) -> Void,
        onRoomState: @escaping (_ payload: JSONValue?) -> Void,
        onRoomEnded: @escaping () -> Void,
        onPong: @escaping () -> Void,
        onTurnRefreshed: @escaping (_ payload: JSONValue?) -> Void,
        onSignalingPayload: @escaping (_ message: SignalingMessage) -> Void,
        onContentState: @escaping (_ payload: ContentStatePayload) -> Void,
        onError: @escaping (_ error: CallError) -> Void,
        sendMessage: @escaping (_ type: String, _ payload: JSONValue?, _ to: String?) -> Void
    ) {
        self.getClientId = getClientId
        self.onJoined = onJoined
        self.onRoomState = onRoomState
        self.onRoomEnded = onRoomEnded
        self.onPong = onPong
        self.onTurnRefreshed = onTurnRefreshed
        self.onSignalingPayload = onSignalingPayload
        self.onContentState = onContentState
        self.onError = onError
        self.sendMessage = sendMessage
    }

    // MARK: - Public API

    func processMessage(_ message: SignalingMessage) {
        switch message.type {
        case "joined":
            let payload = JoinedPayload(from: message.payload)
            onJoined(message.cid, payload, message.payload)
        case "room_state":
            onRoomState(message.payload)
        case "room_ended":
            onRoomEnded()
        case "pong":
            onPong()
        case "turn-refreshed":
            onTurnRefreshed(message.payload)
        case "offer", "answer", "ice":
            onSignalingPayload(message)
        case "content_state":
            let payload = ContentStatePayload(from: message.payload)
            onContentState(payload)
        case "error":
            let payload = ErrorPayload(from: message.payload)
            onError(payload.toCallError())
        default:
            break
        }
    }

    // MARK: - Outbound Helpers

    func broadcastContentState(active: Bool, contentType: String? = nil) {
        var payload: [String: JSONValue] = ["active": .bool(active)]
        if active, let contentType {
            payload["contentType"] = .string(contentType)
        }
        sendMessage("content_state", .object(payload), nil)
    }

    // MARK: - Parsing Helpers

    func parseRoomState(payload: JSONValue?, fallbackHostCid: String?) -> RoomState? {
        guard let obj = payload?.objectValue else { return nil }
        let parsedHostCid = obj["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        let participants = parseParticipants(from: obj["participants"]?.arrayValue) ?? []

        var resolvedHostCid = (parsedHostCid?.isEmpty == false ? parsedHostCid : nil) ?? fallbackHostCid ?? getClientId()
        if let currentHostCid = resolvedHostCid, !participants.isEmpty {
            let participantCids = Set(participants.map(\.cid))
            if !participantCids.contains(currentHostCid) {
                resolvedHostCid = participants.first?.cid
            }
        }

        guard let resolvedHostCid, !resolvedHostCid.isEmpty else { return nil }
        let maxParticipants = obj["maxParticipants"]?.intValue
        return RoomState(hostCid: resolvedHostCid, participants: participants, maxParticipants: maxParticipants)
    }

    static func turnToken(from payload: JSONValue?) -> String? {
        payload?.objectValue?["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func participantCountHint(payload: JSONValue?) -> Int? {
        guard let participants = payload?.objectValue?["participants"]?.arrayValue else { return nil }
        return max(1, participants.count)
    }
}
