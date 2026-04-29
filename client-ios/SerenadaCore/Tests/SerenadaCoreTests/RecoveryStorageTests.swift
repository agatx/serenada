import XCTest
@testable import SerenadaCore

final class RecoveryStorageTests: XCTestCase {
    private let suiteName = "serenada.recovery.tests"
    private var defaults: UserDefaults!
    private var storage: RecoveryStorage!
    private var nowMs: Int64 = 1_000_000

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)!
        storage = RecoveryStorage(defaults: defaults, now: { [weak self] in self?.nowMs ?? 0 })
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testLoadReturnsNilWhenNothingStored() {
        XCTAssertNil(storage.load())
    }

    func testRoundTripsValidRecord() {
        let record = RecoveryRecord(
            roomId: "room-1",
            cid: "C-abc",
            reconnectToken: "tok",
            lastEpoch: 7,
            sessionStartTs: nowMs - 10_000,
            expiresAtMs: nowMs + 60_000
        )
        storage.save(record)
        XCTAssertEqual(storage.load(), record)
    }

    func testLastEpochMayBeNil() {
        let record = RecoveryRecord(
            roomId: "room-1",
            cid: "C-abc",
            reconnectToken: "tok",
            lastEpoch: nil,
            sessionStartTs: nowMs,
            expiresAtMs: nowMs + 60_000
        )
        storage.save(record)
        XCTAssertNil(storage.load()?.lastEpoch)
    }

    func testExpiredRecordsAreDroppedOnLoad() {
        let record = RecoveryRecord(
            roomId: "room-1",
            cid: "C-abc",
            reconnectToken: "tok",
            lastEpoch: nil,
            sessionStartTs: nowMs - 100_000,
            expiresAtMs: nowMs - 1
        )
        storage.save(record)
        XCTAssertNil(storage.load())
        XCTAssertNil(storage.load())
    }

    func testClearRemovesAnyStoredValue() {
        let record = RecoveryRecord(
            roomId: "room-1",
            cid: "C-abc",
            reconnectToken: "tok",
            lastEpoch: 1,
            sessionStartTs: nowMs,
            expiresAtMs: nowMs + 60_000
        )
        storage.save(record)
        storage.clear()
        XCTAssertNil(storage.load())
    }

    func testCorruptedDataIsDropped() {
        defaults.set(Data("not json".utf8), forKey: "serenada.recovery.record_v1")
        XCTAssertNil(storage.load())
    }
}
