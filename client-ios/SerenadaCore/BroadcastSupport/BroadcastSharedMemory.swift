import Foundation

/// Host-independent shared-memory frame layout, shared by the broadcast
/// extension (writer) and the main app (reader). Unlike `BroadcastIPCConfig`,
/// which carries per-host identifiers, these constants describe the on-wire
/// frame format and never change per consumer.
public enum BroadcastShared {
    /// Fixed header size in bytes. Frame pixel data follows the header.
    public static let headerSize = 64
    /// Upper bound on the mmap file: header + a 4K BGRA frame.
    public static let maxFrameFileSize = headerSize + 3840 * 2160 * 4 // header + 4K BGRA upper bound
    /// Reader poll cadence (~30fps).
    public static let pollIntervalMs = 33 // ~30fps
    /// How often the reader refreshes its liveness heartbeat in the sidecar.
    public static let heartbeatIntervalMs = 1000
    /// How long after the last heartbeat the extension treats the reader as gone
    /// (self-stop) and the start-time marker as stale (refuse to write frames).
    /// Three missed beats.
    public static let heartbeatStaleThresholdMs = 3000
}

/// Named byte offsets for the shared-memory frame header.
///
/// Layout (64 bytes):
/// ```
///   0: seqNo            UInt32  — frame sequence number (written last as publish barrier)
///   4: width            UInt32
///   8: height           UInt32
///  12: pixelFormat      UInt32  — CVPixelFormatType (e.g., 420v for NV12)
///  16: planeCount       UInt32
///  20: plane0BytesPerRow UInt32
///  24: plane0Height     UInt32
///  28: plane1BytesPerRow UInt32
///  32: plane1Height     UInt32
///  36: timestampNs      Int64   — presentation timestamp in nanoseconds
///  44: rotation         UInt32  — RTCVideoRotation raw value (0, 90, 180, 270)
///  48: generation       UInt32  — per-share session generation; the reader rejects
///                                  frames whose generation != the live session's
///  52..63: reserved
/// ```
///
/// `seqNo` is an odd/even seqlock: the writer makes it odd before touching the
/// frame and even after publishing, so a reader that observes an odd value (or a
/// changed value across its read) discards the frame as torn.
public enum BroadcastHeaderOffset {
    public static let seqNo = 0
    public static let width = 4
    public static let height = 8
    public static let pixelFormat = 12
    public static let planeCount = 16
    public static let plane0BytesPerRow = 20
    public static let plane0Height = 24
    public static let plane1BytesPerRow = 28
    public static let plane1Height = 32
    public static let timestampNs = 36
    public static let rotation = 44
    public static let generation = 48
}

public enum BroadcastSharedMemoryIO {
    public static func loadInt64(from ptr: UnsafeRawPointer, byteOffset: Int) -> Int64 {
        var value: Int64 = 0
        withUnsafeMutableBytes(of: &value) { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            memcpy(baseAddress, ptr.advanced(by: byteOffset), buffer.count)
        }
        return value
    }

    public static func storeInt64(_ value: Int64, to ptr: UnsafeMutableRawPointer, byteOffset: Int) {
        var mutableValue = value
        withUnsafeBytes(of: &mutableValue) { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            memcpy(ptr.advanced(by: byteOffset), baseAddress, buffer.count)
        }
    }
}
