@testable import SerenadaCore
import XCTest

@MainActor
final class RoomWatcherTests: XCTestCase {
    func testFiltersDroppedRoomsFromBulkAndIncrementalUpdates() {
        let signalingClient = FakeRoomWatcherSignalingClient()
        let watcher = RoomWatcher(signalingClient: signalingClient)
        let delegate = RoomWatcherDelegateSpy()
        watcher.delegate = delegate

        watcher.watchRooms(roomIds: ["alpha", "beta"], host: "one.example")
        XCTAssertEqual(signalingClient.connectHosts, ["one.example"])

        signalingClient.simulateOpen()
        XCTAssertEqual(signalingClient.lastWatchedRoomIds, ["alpha", "beta"])

        signalingClient.simulateMessage(
            SignalingMessage(
                type: "room_statuses",
                payload: .object([
                    "alpha": .object(["count": .number(1), "maxParticipants": .number(4)]),
                    "gamma": .object(["count": .number(3), "maxParticipants": .number(4)]),
                ])
            )
        )
        XCTAssertEqual(watcher.currentStatuses, [
            "alpha": RoomOccupancy(count: 1, maxParticipants: 4)
        ])

        watcher.watchRooms(roomIds: ["beta"], host: "one.example")
        XCTAssertEqual(signalingClient.lastWatchedRoomIds, ["beta"])

        signalingClient.simulateMessage(
            SignalingMessage(
                type: "room_statuses",
                payload: .object([
                    "alpha": .object(["count": .number(4), "maxParticipants": .number(4)]),
                    "beta": .object(["count": .number(2), "maxParticipants": .number(4)]),
                ])
            )
        )
        XCTAssertEqual(watcher.currentStatuses, [
            "beta": RoomOccupancy(count: 2, maxParticipants: 4)
        ])

        signalingClient.simulateMessage(
            SignalingMessage(
                type: "room_status_update",
                payload: .object([
                    "rid": .string("alpha"),
                    "count": .number(5),
                    "maxParticipants": .number(4),
                ])
            )
        )
        XCTAssertEqual(watcher.currentStatuses, [
            "beta": RoomOccupancy(count: 2, maxParticipants: 4)
        ])

        signalingClient.simulateMessage(
            SignalingMessage(
                type: "room_status_update",
                payload: .object([
                    "rid": .string("beta"),
                    "count": .number(6),
                    "maxParticipants": .number(4),
                ])
            )
        )
        XCTAssertEqual(watcher.currentStatuses, [
            "beta": RoomOccupancy(count: 6, maxParticipants: 4)
        ])
        XCTAssertEqual(delegate.snapshots.last, watcher.currentStatuses)
    }

    func testHostChangeClosesAndReconnectsBeforeResubscribing() {
        let signalingClient = FakeRoomWatcherSignalingClient()
        let watcher = RoomWatcher(signalingClient: signalingClient)

        watcher.watchRooms(roomIds: ["alpha"], host: "one.example")
        signalingClient.simulateOpen()
        XCTAssertEqual(signalingClient.connectHosts, ["one.example"])
        XCTAssertEqual(signalingClient.lastWatchedRoomIds, ["alpha"])

        signalingClient.sentMessages.removeAll()
        watcher.watchRooms(roomIds: ["alpha"], host: "two.example")

        XCTAssertEqual(signalingClient.closeCalls, 1)
        XCTAssertEqual(signalingClient.connectHosts, ["one.example", "two.example"])
        XCTAssertTrue(signalingClient.sentMessages.isEmpty)

        signalingClient.simulateOpen()
        XCTAssertEqual(signalingClient.lastWatchedRoomIds, ["alpha"])
    }
}

@MainActor
private final class RoomWatcherDelegateSpy: RoomWatcherDelegate {
    var snapshots: [[String: RoomOccupancy]] = []

    func roomWatcher(_ watcher: RoomWatcher, didUpdateStatuses statuses: [String : RoomOccupancy]) {
        snapshots.append(statuses)
    }
}

@MainActor
private final class FakeRoomWatcherSignalingClient: RoomWatcherSignalingClient {
    var listener: SignalingClientListener?
    var connected = false
    var connectHosts: [String] = []
    var sentMessages: [SignalingMessage] = []
    var closeCalls = 0
    var recordPongCalls = 0

    var lastWatchedRoomIds: [String]? {
        sentMessages.last?.payload?.objectValue?["rids"]?.arrayValue?.compactMap { $0.stringValue }
    }

    func connect(host: String) {
        connectHosts.append(host)
    }

    func isConnected() -> Bool {
        connected
    }

    func send(_ message: SignalingMessage) {
        sentMessages.append(message)
    }

    func close() {
        closeCalls += 1
        connected = false
    }

    func recordPong() {
        recordPongCalls += 1
    }

    func simulateOpen() {
        connected = true
        listener?.onOpen(activeTransport: "ws")
    }

    func simulateMessage(_ message: SignalingMessage) {
        listener?.onMessage(message)
    }
}
