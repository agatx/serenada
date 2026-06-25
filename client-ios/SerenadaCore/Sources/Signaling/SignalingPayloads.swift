import Foundation

// MARK: - Shared Parsing Helpers

private let maxSafeContentRevision = 9_007_199_254_740_991.0

func parseContentRevision(from value: JSONValue?) -> Int64? {
    guard case .number(let raw) = value else { return nil }
    guard raw >= 0, raw <= maxSafeContentRevision, raw.rounded(.towardZero) == raw else { return nil }
    return Int64(raw)
}

/// Parse a JSON array of participant objects into typed Participant values.
func parseParticipants(from arrayValue: [JSONValue]?) -> [Participant]? {
    guard let values = arrayValue else { return nil }
    var result: [Participant] = []
    for value in values {
        guard let obj = value.objectValue else { continue }
        guard let cid = obj["cid"]?.stringValue, !cid.isEmpty else { continue }
        let joinedAt = obj["joinedAt"]?.intValue.map(Int64.init)
        let displayName = obj["displayName"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let peerId = obj["peerId"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let audioEnabled = obj["audioEnabled"]?.boolValue
        let videoEnabled = obj["videoEnabled"]?.boolValue
        // Unknown status values fall back to .active per protocol spec.
        let signalingStatus: ParticipantSignalingStatus = (obj["connectionStatus"]?.stringValue == "suspended") ? .suspended : .active
        let contentState = parseContentState(from: obj["contentState"])
        let capabilities = parseCapabilities(from: obj["capabilities"])
        let mediaPolicy = parseMediaPolicy(from: obj["mediaPolicy"])
        result.append(Participant(
            cid: cid,
            joinedAt: joinedAt,
            displayName: displayName,
            peerId: peerId,
            audioEnabled: audioEnabled,
            videoEnabled: videoEnabled,
            signalingStatus: signalingStatus,
            contentState: contentState,
            capabilities: capabilities,
            mediaPolicy: mediaPolicy
        ))
    }
    return result
}

/// Parse the latest ephemeral content state for a participant, surfaced in
/// `joined`/`room_state` so a peer reconnecting after a suspension can
/// restore screen-share / content-camera UI without waiting for the sender
/// to toggle again.
func parseContentState(from value: JSONValue?) -> ParticipantContentState? {
    guard let obj = value?.objectValue else { return nil }
    guard let active = obj["active"]?.boolValue else { return nil }
    let contentType = active ? obj["contentType"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty : nil
    let updatedAtMs = obj["updatedAtMs"]?.intValue.map(Int64.init)
    let epoch = obj["epoch"]?.intValue.map(Int64.init)
    let revision = parseContentRevision(from: obj["revision"])
    return ParticipantContentState(
        active: active,
        contentType: contentType,
        updatedAtMs: updatedAtMs,
        epoch: epoch,
        revision: revision
    )
}

/// Parse a participant's static build `capabilities`, surfaced in
/// `joined`/`room_state`. Only allowlisted keys are read; unknown keys are
/// ignored. Returns `nil` when the object is absent so callers can apply the
/// documented per-field defaults.
func parseCapabilities(from value: JSONValue?) -> ParticipantCapabilities? {
    guard let obj = value?.objectValue else { return nil }
    return ParticipantCapabilities(
        independentContentVideo: obj["independentContentVideo"]?.boolValue
    )
}

/// Parse a participant's per-session `mediaPolicy`, surfaced in
/// `joined`/`room_state`. Returns `nil` when the object is absent.
func parseMediaPolicy(from value: JSONValue?) -> ParticipantMediaPolicy? {
    guard let obj = value?.objectValue else { return nil }
    return ParticipantMediaPolicy(
        videoMediaEnabled: obj["videoMediaEnabled"]?.boolValue
    )
}

// MARK: - Typed Signaling Payloads

/// Payload for "joined" message — server acknowledges the join and provides room info.
struct JoinedPayload {
    let hostCid: String?
    let participants: [Participant]?
    let maxParticipants: Int?
    let turnToken: String?
    let turnTokenTTLMs: Int?
    let reconnectToken: String?
    let reconnectTokenTTLMs: Int?
    let participantCount: Int?
    /// Server room-state epoch on this transport; monotonic per room.
    let epoch: Int64?
    /// How the server treated this join. nil for older servers.
    let reconnect: ReconnectOutcome?

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            hostCid = nil; participants = nil; maxParticipants = nil; turnToken = nil
            turnTokenTTLMs = nil; reconnectToken = nil; reconnectTokenTTLMs = nil
            participantCount = nil; epoch = nil; reconnect = nil
            return
        }

        hostCid = obj["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        maxParticipants = obj["maxParticipants"]?.intValue
        turnToken = obj["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        turnTokenTTLMs = obj["turnTokenTTLMs"]?.intValue
        reconnectToken = obj["reconnectToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        reconnectTokenTTLMs = obj["reconnectTokenTTLMs"]?.intValue
        epoch = obj["epoch"]?.intValue.map(Int64.init)
        if let raw = obj["reconnect"]?.stringValue {
            reconnect = ReconnectOutcome(rawValue: raw)
        } else {
            reconnect = nil
        }

        if let parsed = parseParticipants(from: obj["participants"]?.arrayValue) {
            participants = parsed
            participantCount = max(1, parsed.count)
        } else {
            participants = nil
            participantCount = nil
        }
    }
}

/// Payload for "room_state" message — server-emitted authoritative snapshot.
/// Used as the post-reconnect sync point that gates SDK ICE restart.
struct RoomStatePayload {
    let hostCid: String?
    let participants: [Participant]?
    let maxParticipants: Int?
    let epoch: Int64?

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            hostCid = nil; participants = nil; maxParticipants = nil; epoch = nil; return
        }
        hostCid = obj["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        maxParticipants = obj["maxParticipants"]?.intValue
        epoch = obj["epoch"]?.intValue.map(Int64.init)
        participants = parseParticipants(from: obj["participants"]?.arrayValue)
    }
}

/// Payload for "relay_failed" — server tells the sender that an offer/
/// answer/ice could not be delivered because the target was suspended.
struct RelayFailedPayload {
    let reason: String
    let targets: [String]
    let of: String?

    init?(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else { return nil }
        guard let reason = obj["reason"]?.stringValue, !reason.isEmpty else { return nil }
        guard let raw = obj["targets"]?.arrayValue else { return nil }
        let targets = raw.compactMap { $0.stringValue }.filter { !$0.isEmpty }
        guard !targets.isEmpty else { return nil }
        self.reason = reason
        self.targets = targets
        self.of = obj["of"]?.stringValue?.nilIfEmpty
    }
}

/// Payload for "negotiation_dirty" — server tells the sender that a
/// previously-suspended peer has reattached AND there were missed offer/
/// answer/ice messages during the suspension. The SDK should perform fresh
/// glare-safe negotiation for the named CID.
struct NegotiationDirtyPayload {
    let withCid: String

    init?(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else { return nil }
        guard let raw = obj["with"]?.stringValue, !raw.isEmpty else { return nil }
        self.withCid = raw
    }
}

struct ReconnectTokenRefreshedPayload {
    let reconnectToken: String
    let reconnectTokenTTLMs: Int64?

    init?(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else { return nil }
        guard let token = obj["reconnectToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else { return nil }
        reconnectToken = token
        reconnectTokenTTLMs = obj["reconnectTokenTTLMs"]?.intValue.map(Int64.init)
    }
}

/// Payload for "error" message — server reports an error.
struct ErrorPayload {
    let code: String?
    let message: String?
    /// Optional reason for terminal codes (e.g. ROOM_ENDED → "ended_by_host").
    let reason: String?

    init(code: String?, message: String?, reason: String? = nil) {
        self.code = code
        self.message = message
        self.reason = reason
    }

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            code = nil; message = nil; reason = nil; return
        }
        code = obj["code"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        message = obj["message"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        reason = obj["reason"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    func toCallError() -> CallError {
        switch code {
        case "ROOM_CAPACITY_UNSUPPORTED":
            return .roomFull
        case "CONNECTION_FAILED":
            return .connectionFailed
        case "JOIN_TIMEOUT":
            return .signalingTimeout
        case "ROOM_ENDED":
            return .roomEnded
        case "INVALID_RECONNECT_TOKEN":
            return .sessionExpired
        case .some:
            return .serverError(message ?? code ?? "Server error")
        default:
            return .unknown(message ?? "Unknown error")
        }
    }
}

/// Payload for "content_state" message — remote participant shares content state.
struct ContentStatePayload {
    let fromCid: String?
    /// Sender's session id (`sid`), used together with `fromCid` to scope
    /// `revision` tracking so a rejoin restarting at `revision:1` is accepted
    /// by identity. `nil` when the transport does not surface a sender sid.
    let sid: String?
    let active: Bool
    let contentType: String?
    /// Per-`(cid, sid)` monotonically increasing revision. `nil` when the
    /// sender is too old to emit it.
    let revision: Int64?

    init(fromCid: String?, sid: String? = nil, active: Bool, contentType: String?, revision: Int64? = nil) {
        self.fromCid = fromCid
        self.sid = sid
        self.active = active
        self.contentType = contentType
        self.revision = revision
    }

    init(from payload: JSONValue?, sid: String? = nil) {
        guard let obj = payload?.objectValue, let activeValue = obj["active"]?.boolValue else {
            fromCid = nil; self.sid = sid; active = false; contentType = nil; revision = nil; return
        }
        fromCid = obj["from"]?.stringValue
        self.sid = sid
        active = activeValue
        contentType = active ? obj["contentType"]?.stringValue : nil
        revision = parseContentRevision(from: obj["revision"])
    }
}

/// Payload for "participant_media_state" message — remote participant's audio/video state.
/// Fields are optional per the protocol: missing fields mean "no change", and the
/// consumer should preserve the previously cached value rather than overwriting.
struct MediaStatePayload {
    let fromCid: String?
    let audioEnabled: Bool?
    let videoEnabled: Bool?

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            fromCid = nil; audioEnabled = nil; videoEnabled = nil; return
        }
        fromCid = obj["from"]?.stringValue
        audioEnabled = obj["audioEnabled"]?.boolValue
        videoEnabled = obj["videoEnabled"]?.boolValue
    }
}
