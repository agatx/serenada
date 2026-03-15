import Foundation

struct RoomStatus: Equatable {
    let count: Int
    let maxParticipants: Int?
}

enum RoomStatusIndicatorState: Equatable {
    case hidden
    case waiting
    case full
}

enum RoomStatuses {
    private static func normalizeMaxParticipants(_ value: Int?, fallback: Int?) -> Int? {
        if let value, value >= 2 {
            return value
        }
        if let fallback, fallback >= 2 {
            return fallback
        }
        return nil
    }

    private static func parseStatus(_ value: JSONValue?, fallback: RoomStatus?) -> RoomStatus? {
        if let count = value?.intValue {
            return RoomStatus(
                count: max(0, count),
                maxParticipants: normalizeMaxParticipants(nil, fallback: fallback?.maxParticipants)
            )
        }

        guard let object = value?.objectValue,
              let count = object["count"]?.intValue else {
            return nil
        }

        return RoomStatus(
            count: max(0, count),
            maxParticipants: normalizeMaxParticipants(
                object["maxParticipants"]?.intValue,
                fallback: fallback?.maxParticipants
            )
        )
    }

    static func indicatorState(for status: RoomStatus?) -> RoomStatusIndicatorState {
        let count = status?.count ?? 0
        if count <= 0 {
            return .hidden
        }

        let maxParticipants = normalizeMaxParticipants(status?.maxParticipants, fallback: 2) ?? 2
        return count >= maxParticipants ? .full : .waiting
    }

    static func mergeStatusesPayload(previous: [String: RoomStatus], payload: JSONValue?) -> [String: RoomStatus] {
        guard let payload = payload?.objectValue else { return previous }
        var next = previous
        for (rid, value) in payload {
            if let status = parseStatus(value, fallback: previous[rid]) {
                next[rid] = status
            }
        }
        return next
    }

    static func mergeStatusUpdatePayload(previous: [String: RoomStatus], payload: JSONValue?) -> [String: RoomStatus] {
        guard
            let payload = payload?.objectValue,
            let rid = payload["rid"]?.stringValue,
            let count = payload["count"]?.intValue
        else {
            return previous
        }

        var next = previous
        next[rid] = RoomStatus(
            count: max(0, count),
            maxParticipants: normalizeMaxParticipants(
                payload["maxParticipants"]?.intValue,
                fallback: previous[rid]?.maxParticipants
            )
        )
        return next
    }
}
