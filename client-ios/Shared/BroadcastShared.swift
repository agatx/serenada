import Foundation

/// Constants and utilities shared between the main app and the SerenadaBroadcast extension.
/// This file is compiled into both targets — keep it free of main-app-only imports.
enum BroadcastShared {
    static let appGroupIdentifier = "group.app.serenada.ios"
    static let extensionBundleId = "app.serenada.ios.broadcast"
    static let sharedFileName = "broadcast_frame.dat"
    static let headerSize = 64
    static let maxFrameFileSize = headerSize + 3840 * 2160 * 4 // header + 4K BGRA upper bound

    static let darwinNotifyStarted = "app.serenada.ios.broadcast.started"
    static let darwinNotifyFinished = "app.serenada.ios.broadcast.finished"
    static let darwinNotifyRequestStop = "app.serenada.ios.broadcast.requestStop"

    static let pollIntervalMs = 33 // ~30fps
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
///  48..63: reserved
/// ```
enum BroadcastHeaderOffset {
    static let seqNo = 0
    static let width = 4
    static let height = 8
    static let pixelFormat = 12
    static let planeCount = 16
    static let plane0BytesPerRow = 20
    static let plane0Height = 24
    static let plane1BytesPerRow = 28
    static let plane1Height = 32
    static let timestampNs = 36
    static let rotation = 44
}

enum BroadcastSharedMemoryIO {
    static func loadInt64(from ptr: UnsafeRawPointer, byteOffset: Int) -> Int64 {
        var value: Int64 = 0
        withUnsafeMutableBytes(of: &value) { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            memcpy(baseAddress, ptr.advanced(by: byteOffset), buffer.count)
        }
        return value
    }

    static func storeInt64(_ value: Int64, to ptr: UnsafeMutableRawPointer, byteOffset: Int) {
        var mutableValue = value
        withUnsafeBytes(of: &mutableValue) { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            memcpy(ptr.advanced(by: byteOffset), baseAddress, buffer.count)
        }
    }
}
