import Foundation

@MainActor
protocol RoomWatcherSignalingClient: AnyObject {
    var listener: SignalingClientListener? { get set }
    func connect(host: String)
    func isConnected() -> Bool
    func send(_ message: SignalingMessage)
    func close()
    func recordPong()
}

/// Room occupancy info (participant count and max capacity).
public struct RoomOccupancy: Equatable {
    public let count: Int
    public let maxParticipants: Int?

    public init(count: Int, maxParticipants: Int? = nil) {
        self.count = count
        self.maxParticipants = maxParticipants
    }
}

/// Callback for room occupancy status changes.
@MainActor
public protocol RoomWatcherDelegate: AnyObject {
    func roomWatcher(_ watcher: RoomWatcher, didUpdateStatuses statuses: [String: RoomOccupancy])
}

/// Monitors room occupancy via signaling. Use to display room status before joining.
@MainActor
public final class RoomWatcher {
    /// Delegate notified when room occupancy changes.
    public weak var delegate: RoomWatcherDelegate?

    private let signalingClient: any RoomWatcherSignalingClient
    private var watchedRoomIds: [String] = []
    private var statuses: [String: RoomOccupancy] = [:]
    private var reconnectAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var host: String?

    public init() {
        self.signalingClient = SignalingClient()
        self.signalingClient.listener = self
    }

    init(signalingClient: any RoomWatcherSignalingClient) {
        self.signalingClient = signalingClient
        self.signalingClient.listener = self
    }

    /// Current occupancy statuses keyed by room ID.
    public var currentStatuses: [String: RoomOccupancy] {
        statuses
    }

    /// Start watching the given room IDs for occupancy changes.
    public func watchRooms(roomIds: [String], host: String) {
        let hostChanged = self.host.map { $0.compare(host, options: .caseInsensitive) != .orderedSame } ?? false
        self.host = host
        watchedRoomIds = roomIds

        let watchedSet = Set(watchedRoomIds)
        statuses = statuses.filter { watchedSet.contains($0.key) }

        reconnectTask?.cancel()
        reconnectTask = nil

        if hostChanged {
            signalingClient.close()
        }

        guard !watchedRoomIds.isEmpty else {
            if signalingClient.isConnected() {
                signalingClient.close()
            }
            return
        }

        if signalingClient.isConnected() {
            sendWatchRooms()
        } else {
            signalingClient.connect(host: host)
        }
    }

    /// Stop watching all rooms and disconnect.
    public func stop() {
        reconnectTask?.cancel()
        reconnectTask = nil
        watchedRoomIds = []
        statuses = [:]
        host = nil
        signalingClient.close()
    }

    private func sendWatchRooms() {
        guard !watchedRoomIds.isEmpty else { return }
        guard signalingClient.isConnected() else { return }

        signalingClient.send(
            SignalingMessage(
                type: "watch_rooms",
                payload: .object([
                    "rids": .array(watchedRoomIds.map { .string($0) })
                ])
            )
        )
    }

    private func scheduleReconnect() {
        guard !watchedRoomIds.isEmpty, let host else { return }

        reconnectAttempts += 1
        let backoffMs = Backoff.reconnectDelayMs(attempt: reconnectAttempts)

        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(backoffMs) * 1_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            guard !self.signalingClient.isConnected() else { return }
            guard !self.watchedRoomIds.isEmpty else { return }

            self.signalingClient.connect(host: host)
        }
    }

    // MARK: - Room status parsing

    private func mergeStatusesPayload(payload: JSONValue?) {
        guard let payload = payload?.objectValue else { return }
        let watchedSet = Set(watchedRoomIds)
        for (rid, value) in payload where watchedSet.contains(rid) {
            if let status = parseOccupancy(value, fallback: statuses[rid]) {
                statuses[rid] = status
            }
        }
        delegate?.roomWatcher(self, didUpdateStatuses: statuses)
    }

    private func mergeStatusUpdatePayload(payload: JSONValue?) {
        guard
            let payload = payload?.objectValue,
            let rid = payload["rid"]?.stringValue,
            let count = payload["count"]?.intValue
        else {
            return
        }
        guard watchedRoomIds.contains(rid) else { return }

        statuses[rid] = RoomOccupancy(
            count: max(0, count),
            maxParticipants: normalizeMaxParticipants(
                payload["maxParticipants"]?.intValue,
                fallback: statuses[rid]?.maxParticipants
            )
        )
        delegate?.roomWatcher(self, didUpdateStatuses: statuses)
    }

    private func parseOccupancy(_ value: JSONValue?, fallback: RoomOccupancy?) -> RoomOccupancy? {
        if let count = value?.intValue {
            return RoomOccupancy(
                count: max(0, count),
                maxParticipants: normalizeMaxParticipants(nil, fallback: fallback?.maxParticipants)
            )
        }

        guard let object = value?.objectValue,
              let count = object["count"]?.intValue else {
            return nil
        }

        return RoomOccupancy(
            count: max(0, count),
            maxParticipants: normalizeMaxParticipants(
                object["maxParticipants"]?.intValue,
                fallback: fallback?.maxParticipants
            )
        )
    }

    private func normalizeMaxParticipants(_ value: Int?, fallback: Int?) -> Int? {
        if let value, value >= 2 {
            return value
        }
        if let fallback, fallback >= 2 {
            return fallback
        }
        return nil
    }
}

extension RoomWatcher: SignalingClientListener {
    func onOpen(activeTransport: String) {
        reconnectAttempts = 0
        sendWatchRooms()
    }

    func onMessage(_ message: SignalingMessage) {
        switch message.type {
        case "room_statuses":
            mergeStatusesPayload(payload: message.payload)
        case "room_status_update":
            mergeStatusUpdatePayload(payload: message.payload)
        case "pong":
            signalingClient.recordPong()
        default:
            break
        }
    }

    func onClosed(reason: String) {
        scheduleReconnect()
    }
}

extension SignalingClient: RoomWatcherSignalingClient {}
