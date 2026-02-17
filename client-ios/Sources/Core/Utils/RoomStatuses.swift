import Foundation

enum RoomStatuses {
    static func mergeStatusesPayload(previous: [String: Int], payload: JSONValue?) -> [String: Int] {
        guard let payload = payload?.objectValue else { return previous }
        var next = previous
        for (rid, value) in payload {
            if let count = value.intValue {
                next[rid] = max(0, count)
            }
        }
        return next
    }

    static func mergeStatusUpdatePayload(previous: [String: Int], payload: JSONValue?) -> [String: Int] {
        guard
            let payload = payload?.objectValue,
            let rid = payload["rid"]?.stringValue,
            let count = payload["count"]?.intValue
        else {
            return previous
        }

        var next = previous
        next[rid] = max(0, count)
        return next
    }
}
