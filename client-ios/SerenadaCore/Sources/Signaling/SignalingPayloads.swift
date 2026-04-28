import Foundation

// MARK: - Shared Parsing Helpers

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
        result.append(Participant(
            cid: cid,
            joinedAt: joinedAt,
            displayName: displayName,
            peerId: peerId,
            audioEnabled: audioEnabled,
            videoEnabled: videoEnabled,
            signalingStatus: signalingStatus
        ))
    }
    return result
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
    let participantCount: Int?

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            hostCid = nil; participants = nil; maxParticipants = nil; turnToken = nil
            turnTokenTTLMs = nil; reconnectToken = nil; participantCount = nil
            return
        }

        hostCid = obj["hostCid"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        maxParticipants = obj["maxParticipants"]?.intValue
        turnToken = obj["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        turnTokenTTLMs = obj["turnTokenTTLMs"]?.intValue
        reconnectToken = obj["reconnectToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)

        if let parsed = parseParticipants(from: obj["participants"]?.arrayValue) {
            participants = parsed
            participantCount = max(1, parsed.count)
        } else {
            participants = nil
            participantCount = nil
        }
    }
}

/// Payload for "error" message — server reports an error.
struct ErrorPayload {
    let code: String?
    let message: String?

    init(code: String?, message: String?) {
        self.code = code
        self.message = message
    }

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            code = nil; message = nil; return
        }
        code = obj["code"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        message = obj["message"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
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
    let active: Bool
    let contentType: String?

    init(fromCid: String?, active: Bool, contentType: String?) {
        self.fromCid = fromCid
        self.active = active
        self.contentType = contentType
    }

    init(from payload: JSONValue?) {
        guard let obj = payload?.objectValue else {
            fromCid = nil; active = false; contentType = nil; return
        }
        fromCid = obj["from"]?.stringValue
        active = obj["active"]?.boolValue == true
        contentType = active ? obj["contentType"]?.stringValue : nil
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
