import Foundation

/// Persisted call-recovery state — surfaced to host apps so a relaunched
/// process can prompt the user to rejoin an in-flight call instead of
/// silently dropping them on the home screen.
///
/// Backed by `UserDefaults` (the active app group when the host app
/// configures one, else `.standard`); cleared on clean leave,
/// `room_ended`, or `INVALID_RECONNECT_TOKEN`.
public struct RecoveryRecord: Equatable, Codable, Sendable {
    public let roomId: String
    public let cid: String
    public let reconnectToken: String
    public let lastEpoch: Int64?
    public let sessionStartTs: Int64
    /// Unix-ms after which the host app should NOT offer the rejoin
    /// prompt. Computed as `now + reconnectTokenTTLMs` at write time so the
    /// SDK does not need to know server clocks.
    public let expiresAtMs: Int64

    public init(
        roomId: String,
        cid: String,
        reconnectToken: String,
        lastEpoch: Int64?,
        sessionStartTs: Int64,
        expiresAtMs: Int64
    ) {
        self.roomId = roomId
        self.cid = cid
        self.reconnectToken = reconnectToken
        self.lastEpoch = lastEpoch
        self.sessionStartTs = sessionStartTs
        self.expiresAtMs = expiresAtMs
    }
}

/// Lightweight, pluggable store for recovery records. The SDK keeps one
/// per `SerenadaCore`; the active session reads/writes via that instance.
public final class RecoveryStorage: @unchecked Sendable {
    private static let recordKey = "serenada.recovery.record_v1"

    private let defaults: UserDefaults
    private let now: () -> Int64

    public convenience init() {
        self.init(defaults: .standard)
    }

    public init(defaults: UserDefaults, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.defaults = defaults
        self.now = now
    }

    public func load() -> RecoveryRecord? {
        guard let data = defaults.data(forKey: Self.recordKey) else { return nil }
        guard let record = try? JSONDecoder().decode(RecoveryRecord.self, from: data) else {
            defaults.removeObject(forKey: Self.recordKey)
            return nil
        }
        if now() > record.expiresAtMs {
            defaults.removeObject(forKey: Self.recordKey)
            return nil
        }
        return record
    }

    public func save(_ record: RecoveryRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        defaults.set(data, forKey: Self.recordKey)
    }

    public func clear() {
        defaults.removeObject(forKey: Self.recordKey)
    }
}
