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
    private let onParticipantMediaState: (_ payload: MediaStatePayload) -> Void
    // `serverCode` is the original signaling error code, preserved so the
    // shared reconnect-reason table classifies the failure by its concrete
    // code, not the coarse mapped `CallError` case.
    private let onError: (_ error: CallError, _ serverCode: String?) -> Void
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
        onParticipantMediaState: @escaping (_ payload: MediaStatePayload) -> Void,
        onError: @escaping (_ error: CallError, _ serverCode: String?) -> Void,
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
        self.onParticipantMediaState = onParticipantMediaState
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
        case "offer", "answer", "ice", "media_restart_request":
            onSignalingPayload(message)
        case "content_state":
            let payload = ContentStatePayload(from: message.payload, sid: message.sid)
            onContentState(payload)
        case "error":
            let payload = ErrorPayload(from: message.payload)
            onError(payload.toCallError(), payload.code)
        default:
            break
        }
    }

    // MARK: - Direct-dispatch methods for provider events

    func processJoinedEvent(_ event: JoinedEvent) {
        let participants = dedupeParticipants(
            participants: event.participants.map(Self.toParticipant),
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
            roomState = RoomState(
                hostCid: host,
                participants: participants,
                maxParticipants: event.maxParticipants,
                epoch: event.epoch
            )
        } else {
            roomState = nil
        }
        let hint = participants.isEmpty ? nil : max(1, participants.count)
        onJoined(event.peerId, roomState, hint)
    }

    func processRoomStateEvent(_ event: RoomStateEvent) {
        let localPeerId = getClientId()
        let participants = dedupeParticipants(
            participants: event.participants.map(Self.toParticipant),
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
            RoomState(
                hostCid: host,
                participants: participants,
                maxParticipants: event.maxParticipants,
                epoch: event.epoch
            ),
            hint
        )
    }

    private static func toParticipant(_ p: SignalingProviderParticipant) -> Participant {
        Participant(
            cid: p.peerId,
            joinedAt: p.joinedAt,
            displayName: p.displayName,
            peerId: p.appPeerId,
            audioEnabled: p.audioEnabled,
            videoEnabled: p.videoEnabled,
            signalingStatus: p.signalingStatus,
            contentState: p.contentState.map {
                ParticipantContentState(
                    active: $0.active,
                    contentType: $0.contentType,
                    updatedAtMs: $0.updatedAtMs,
                    epoch: $0.epoch,
                    revision: $0.revision
                )
            },
            capabilities: p.capabilities.map {
                ParticipantCapabilities(independentContentVideo: $0.independentContentVideo)
            },
            mediaPolicy: p.mediaPolicy.map {
                ParticipantMediaPolicy(videoMediaEnabled: $0.videoMediaEnabled)
            }
        )
    }

    func processPeerMessage(_ message: PeerMessage) {
        switch message.type {
        case "content_state":
            let fromCid = message.payload?["from"]?.stringValue ?? message.from
            guard let active = message.payload?["active"]?.boolValue else { return }
            let contentType = active ? message.payload?["contentType"]?.stringValue : nil
            let revision = parseContentRevision(from: message.payload?["revision"])
            onContentState(ContentStatePayload(fromCid: fromCid, sid: message.sid, active: active, contentType: contentType, revision: revision))
        case "participant_media_state":
            let payload = MediaStatePayload(from: message.payload.map { .object($0) })
            onParticipantMediaState(payload)
        case "offer", "answer", "ice", "media_restart_request":
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
        let payload = ErrorPayload(code: event.code, message: event.message, reason: nil)
        onError(payload.toCallError(), payload.code)
    }

    // MARK: - Outbound Helpers

    /// Monotonic per-`(cid, sid)` revision for outgoing `content_state`. The
    /// local sid is fixed for the lifetime of this router, so a single counter
    /// scopes correctly. Every send (including a rollback `active:false`) uses
    /// a strictly-greater revision than the message it supersedes.
    private var outgoingContentRevision: Int64 = 0

    /// Broadcast the local content state with a freshly incremented revision.
    /// Returns the revision used so the caller can mirror it into local public
    /// state. Every send (including a rollback `active:false`) gets a strictly
    /// greater revision than the message it supersedes.
    @discardableResult
    func broadcastContentState(active: Bool, contentType: String? = nil) -> Int64 {
        outgoingContentRevision += 1
        var payload: [String: JSONValue] = [
            "active": .bool(active),
            "revision": .number(Double(outgoingContentRevision)),
        ]
        if active, let contentType {
            payload["contentType"] = .string(contentType)
        }
        sendMessage("content_state", .object(payload), nil)
        return outgoingContentRevision
    }

    /// Advance the outgoing counter past a server-persisted local snapshot so
    /// post-reconnect sends are strictly greater than the revision peers already
    /// cached for this same CID.
    func seedContentRevision(_ revision: Int64?) {
        guard let revision, revision > outgoingContentRevision else { return }
        outgoingContentRevision = revision
    }

    func broadcastMediaState(audioEnabled: Bool, videoEnabled: Bool) {
        let payload: [String: JSONValue] = [
            "audioEnabled": .bool(audioEnabled),
            "videoEnabled": .bool(videoEnabled),
        ]
        sendMessage("participant_media_state", .object(payload), nil)
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
        let epoch = obj["epoch"]?.intValue.map(Int64.init)
        return RoomState(hostCid: resolvedHostCid, participants: participants, maxParticipants: maxParticipants, epoch: epoch)
    }

    static func turnToken(from payload: JSONValue?) -> String? {
        payload?.objectValue?["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func participantCountHint(payload: JSONValue?) -> Int? {
        guard let participants = payload?.objectValue?["participants"]?.arrayValue else { return nil }
        return max(1, participants.count)
    }
}
