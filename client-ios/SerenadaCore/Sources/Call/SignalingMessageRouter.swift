import Foundation

@MainActor
final class SignalingMessageRouter {
    // State readers
    private let getClientId: () -> String?
    private let getHostCid: () -> String?
    private let getRoomId: () -> String?

    // Callbacks for mutations
    private let onJoined: (_ cid: String?, _ roomState: RoomState?, _ participantCountHint: Int?) -> Void
    private let onRoomState: (_ roomState: RoomState?, _ participantCountHint: Int?) -> Void
    private let onRoomEnded: () -> Void
    private let onPong: () -> Void
    private let onTurnRefreshed: (_ payload: JSONValue?) -> Void
    private let onSignalingPayload: (_ message: SignalingMessage) -> Void
    private let onContentState: (_ payload: ContentStatePayload) -> Void
    private let onError: (_ error: CallError) -> Void
    private let sendMessage: (_ type: String, _ payload: JSONValue?, _ to: String?) -> Void

    init(
        getClientId: @escaping () -> String?,
        getHostCid: @escaping () -> String?,
        getRoomId: @escaping () -> String?,
        onJoined: @escaping (_ cid: String?, _ roomState: RoomState?, _ participantCountHint: Int?) -> Void,
        onRoomState: @escaping (_ roomState: RoomState?, _ participantCountHint: Int?) -> Void,
        onRoomEnded: @escaping () -> Void,
        onPong: @escaping () -> Void,
        onTurnRefreshed: @escaping (_ payload: JSONValue?) -> Void,
        onSignalingPayload: @escaping (_ message: SignalingMessage) -> Void,
        onContentState: @escaping (_ payload: ContentStatePayload) -> Void,
        onError: @escaping (_ error: CallError) -> Void,
        sendMessage: @escaping (_ type: String, _ payload: JSONValue?, _ to: String?) -> Void
    ) {
        self.getClientId = getClientId
        self.getHostCid = getHostCid
        self.getRoomId = getRoomId
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
            let roomState = parseRoomState(payload: message.payload, fallbackHostCid: nil)
            onJoined(message.cid, roomState, payload.participantCount)
        case "room_state":
            let roomState = parseRoomState(payload: message.payload, fallbackHostCid: nil)
            let hint = Self.participantCountHint(payload: message.payload)
            onRoomState(roomState, hint)
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

    // MARK: - Direct-dispatch methods for provider events

    func processJoinedEvent(_ event: JoinedEvent) {
        let participants = dedupeParticipants(
            participants: event.participants.map { Participant(cid: $0.peerId, joinedAt: $0.joinedAt) },
            localPeerId: event.peerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        let host = resolveHostPeerId(
            explicitHostPeerId: event.hostPeerId,
            participants: participants,
            currentHostPeerId: getHostCid(),
            localPeerId: event.peerId
        )
        let roomState: RoomState?
        if let host, !host.isEmpty {
            roomState = RoomState(hostCid: host, participants: participants, maxParticipants: event.maxParticipants)
        } else {
            roomState = nil
        }
        let hint = participants.isEmpty ? nil : max(1, participants.count)
        onJoined(event.peerId, roomState, hint)
    }

    func processRoomStateEvent(_ event: RoomStateEvent) {
        let localPeerId = getClientId()
        let participants = dedupeParticipants(
            participants: event.participants.map { Participant(cid: $0.peerId, joinedAt: $0.joinedAt) },
            localPeerId: localPeerId,
            makeLocalParticipant: { Participant(cid: $0, joinedAt: nil) }
        )
        let host = resolveHostPeerId(
            explicitHostPeerId: event.hostPeerId,
            participants: participants,
            currentHostPeerId: getHostCid(),
            localPeerId: localPeerId
        )
        let hint = participants.isEmpty ? nil : max(1, participants.count)
        guard let host, !host.isEmpty else {
            onRoomState(nil, hint)
            return
        }
        onRoomState(
            RoomState(hostCid: host, participants: participants, maxParticipants: event.maxParticipants),
            hint
        )
    }

    func processPeerMessage(_ message: PeerMessage) {
        switch message.type {
        case "content_state":
            let fromCid = message.payload?["from"]?.stringValue ?? message.from
            let active = message.payload?["active"]?.boolValue == true
            let contentType = active ? message.payload?["contentType"]?.stringValue : nil
            onContentState(ContentStatePayload(fromCid: fromCid, active: active, contentType: contentType))
        case "offer", "answer", "ice":
            var payload = message.payload ?? [:]
            if payload["from"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
                payload["from"] = .string(message.from)
            }
            onSignalingPayload(SignalingMessage(
                type: message.type,
                rid: getRoomId(),
                cid: message.from,
                payload: .object(payload)
            ))
        default:
            break
        }
    }

    func processErrorEvent(_ event: ErrorEvent) {
        let payload = ErrorPayload(code: event.code, message: event.message)
        onError(payload.toCallError())
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
