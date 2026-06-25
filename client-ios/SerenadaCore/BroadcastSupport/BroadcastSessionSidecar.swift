import Foundation

/// Lifecycle metadata for one screen-share session, written by the app (the frame
/// reader) into the shared App Group container and read by the broadcast upload
/// extension. Kept in a sidecar file rather than the binary frame header so richer
/// fields can evolve without consuming the header's scarce reserved bytes.
public struct BroadcastSessionSidecar: Codable, Equatable, Sendable {
    /// Sidecar schema version; bump when the field set changes.
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    /// Opaque, non-PII per-share id for correlating telemetry across the
    /// app/extension boundary.
    public var sessionId: String
    /// Monotonic generation stamped into every frame header. The reader rejects
    /// frames whose header generation does not match the live session's.
    public var generation: UInt32
    /// True while a live in-call reader wants frames. The extension refuses to
    /// write frames (and finishes with an error) when this is false or stale —
    /// e.g. a Control Center start with no active call.
    public var activeCall: Bool
    /// Reader liveness: epoch milliseconds of the reader's last heartbeat.
    public var heartbeatMs: Int64

    public init(
        schemaVersion: Int = currentSchemaVersion,
        sessionId: String,
        generation: UInt32,
        activeCall: Bool,
        heartbeatMs: Int64
    ) {
        self.schemaVersion = schemaVersion
        self.sessionId = sessionId
        self.generation = generation
        self.activeCall = activeCall
        self.heartbeatMs = heartbeatMs
    }

    /// Whether the marker represents a live reader: an active call whose heartbeat
    /// is newer than the staleness threshold.
    public func isLive(nowMs: Int64, staleThresholdMs: Int) -> Bool {
        activeCall && (nowMs - heartbeatMs) < Int64(staleThresholdMs)
    }
}

/// Reads and writes the session sidecar and owns the per-share generation
/// counter, all keyed off a `BroadcastIPCConfig`'s App Group container. Shared by
/// the app (reader/writer of the sidecar) and the extension (reader only).
public struct BroadcastSessionStore {
    private let config: BroadcastIPCConfig

    public init(config: BroadcastIPCConfig) {
        self.config = config
    }

    /// Current wall-clock in epoch milliseconds.
    public static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: config.appGroupIdentifier)
    }

    private var sidecarURL: URL? { containerURL?.appendingPathComponent(config.sidecarFileName) }
    private var frameURL: URL? { containerURL?.appendingPathComponent(config.sharedFileName) }

    /// Next monotonic generation (app side only). Persisted in the App Group's
    /// UserDefaults so it survives sidecar and frame-file deletion; a fresh
    /// session therefore never reuses a generation a stale writer might still be
    /// stamping.
    public func nextGeneration() -> UInt32 {
        let key = "serenada.broadcast.generation"
        guard let defaults = UserDefaults(suiteName: config.appGroupIdentifier) else {
            // Fallback: time-derived, still effectively unique per share. 0 reserved.
            let g = UInt32(truncatingIfNeeded: Self.nowMs())
            return g == 0 ? 1 : g
        }
        var next = defaults.integer(forKey: key) &+ 1
        // Generation 0 is reserved as "invalid" — a zeroed frame header reads as 0,
        // so a truncation/wrap to 0 must not be handed out as a live generation.
        if UInt32(truncatingIfNeeded: next) == 0 { next &+= 1 }
        defaults.set(next, forKey: key)
        return UInt32(truncatingIfNeeded: next)
    }

    public func read() -> BroadcastSessionSidecar? {
        guard let url = sidecarURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(BroadcastSessionSidecar.self, from: data)
    }

    /// Atomically write the sidecar (temp + rename), so a concurrent reader sees
    /// either the old or the new complete file, never a torn one.
    @discardableResult
    public func write(_ sidecar: BroadcastSessionSidecar) -> Bool {
        guard let url = sidecarURL, let data = try? JSONEncoder().encode(sidecar) else { return false }
        return (try? data.write(to: url, options: .atomic)) != nil
    }

    /// Remove the sidecar and the frame file so a later start cannot read stale
    /// state (covers app kill, call teardown, picker cancel, and start timeout).
    public func clear() {
        if let url = sidecarURL { try? FileManager.default.removeItem(at: url) }
        if let url = frameURL { try? FileManager.default.removeItem(at: url) }
    }
}
