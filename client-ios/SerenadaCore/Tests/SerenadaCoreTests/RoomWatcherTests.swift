@testable import SerenadaCore
import XCTest

@MainActor
final class RoomWatcherTests: XCTestCase {
    private final class RecordingRoomWatcherDelegate: RoomWatcherDelegate {
        var snapshots: [[String: RoomOccupancy]] = []

        func roomWatcher(_ watcher: RoomWatcher, didUpdateStatuses statuses: [String : RoomOccupancy]) {
            snapshots.append(statuses)
        }
    }

    func testWatchRoomsRequiresServerHost() {
        let watcher = RoomWatcher()

        XCTAssertThrowsError(try watcher.watchRooms(roomIds: ["room-1"], host: nil)) { error in
            XCTAssertEqual(error.localizedDescription, "requires serverHost")
        }
    }

    func testWatchRoomsConnectsSendsWatchRequestAndTracksStatuses() throws {
        let signaling = FakeSessionSignaling()
        let watcher = RoomWatcher(signalingClient: signaling)
        let delegate = RecordingRoomWatcherDelegate()
        watcher.delegate = delegate

        try watcher.watchRooms(roomIds: ["room-1", "room-2"], host: "serenada.app")
        XCTAssertEqual(signaling.connectHosts, ["serenada.app"])

        signaling.simulateOpen()
        let watchMessage = try XCTUnwrap(signaling.sentMessages.last)
        XCTAssertEqual(watchMessage.type, "watch_rooms")
        XCTAssertEqual(
            watchMessage.payload?.objectValue?["rids"]?.arrayValue?.compactMap(\.stringValue),
            ["room-1", "room-2"]
        )

        signaling.simulateMessage(
            SignalingMessage(
                type: "room_statuses",
                payload: .object([
                    "room-1": .object([
                        "count": .number(1),
                        "maxParticipants": .number(4)
                    ]),
                    "room-2": .number(0)
                ])
            )
        )

        XCTAssertEqual(
            watcher.currentStatuses,
            [
                "room-1": RoomOccupancy(count: 1, maxParticipants: 4),
                "room-2": RoomOccupancy(count: 0, maxParticipants: nil)
            ]
        )
        XCTAssertEqual(delegate.snapshots.last, watcher.currentStatuses)

        signaling.simulateMessage(
            SignalingMessage(
                type: "room_status_update",
                payload: .object([
                    "rid": .string("room-1"),
                    "count": .number(2),
                    "maxParticipants": .number(4)
                ])
            )
        )

        XCTAssertEqual(watcher.currentStatuses["room-1"], RoomOccupancy(count: 2, maxParticipants: 4))

        watcher.stop()
        XCTAssertTrue(watcher.currentStatuses.isEmpty)
        XCTAssertEqual(signaling.closeCalls, 1)
    }

    func testChangingHostsReconnectsRoomWatcher() throws {
        let signaling = FakeSessionSignaling()
        let watcher = RoomWatcher(signalingClient: signaling)

        try watcher.watchRooms(roomIds: ["room-1"], host: "serenada.app")
        signaling.simulateOpen()

        try watcher.watchRooms(roomIds: ["room-1"], host: "serenada-app.ru")

        XCTAssertEqual(signaling.closeCalls, 1)
        XCTAssertEqual(signaling.connectHosts, ["serenada.app", "serenada-app.ru"])
    }

    func testFiltersDroppedRoomsFromBulkAndIncrementalUpdates() throws {
        let signaling = FakeSessionSignaling()
        let watcher = RoomWatcher(signalingClient: signaling)
        let delegate = RecordingRoomWatcherDelegate()
        watcher.delegate = delegate

        try watcher.watchRooms(roomIds: ["alpha", "beta"], host: "one.example")
        XCTAssertEqual(signaling.connectHosts, ["one.example"])

        signaling.simulateOpen()
        XCTAssertEqual(
            signaling.sentMessages.last?.payload?.objectValue?["rids"]?.arrayValue?.compactMap(\.stringValue),
            ["alpha", "beta"]
        )

        signaling.simulateMessage(
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

        try watcher.watchRooms(roomIds: ["beta"], host: "one.example")
        XCTAssertEqual(
            signaling.sentMessages.last?.payload?.objectValue?["rids"]?.arrayValue?.compactMap(\.stringValue),
            ["beta"]
        )

        signaling.simulateMessage(
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

        signaling.simulateMessage(
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

        signaling.simulateMessage(
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

    func testHostChangeClosesAndReconnectsBeforeResubscribing() throws {
        let signaling = FakeSessionSignaling()
        let watcher = RoomWatcher(signalingClient: signaling)

        try watcher.watchRooms(roomIds: ["alpha"], host: "one.example")
        signaling.simulateOpen()
        XCTAssertEqual(signaling.connectHosts, ["one.example"])
        XCTAssertEqual(
            signaling.sentMessages.last?.payload?.objectValue?["rids"]?.arrayValue?.compactMap(\.stringValue),
            ["alpha"]
        )

        signaling.clearSentMessages()
        try watcher.watchRooms(roomIds: ["alpha"], host: "two.example")

        XCTAssertEqual(signaling.closeCalls, 1)
        XCTAssertEqual(signaling.connectHosts, ["one.example", "two.example"])
        XCTAssertTrue(signaling.sentMessages.isEmpty)

        signaling.simulateOpen()
        XCTAssertEqual(
            signaling.sentMessages.last?.payload?.objectValue?["rids"]?.arrayValue?.compactMap(\.stringValue),
            ["alpha"]
        )
    }
}
