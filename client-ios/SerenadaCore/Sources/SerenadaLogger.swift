import Foundation

public enum SerenadaLogLevel: Int, Comparable, Sendable {
    case debug = 0, info, warning, error

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

public protocol SerenadaLogger: AnyObject, Sendable {
    func log(_ level: SerenadaLogLevel, tag: String, _ message: String)
}

public final class PrintSerenadaLogger: SerenadaLogger {
    public init() {}

    public func log(_ level: SerenadaLogLevel, tag: String, _ message: String) {
        let levelLabel: String
        switch level {
        case .debug: levelLabel = "DEBUG"
        case .info: levelLabel = "INFO"
        case .warning: levelLabel = "WARN"
        case .error: levelLabel = "ERROR"
        }
        print("[\(levelLabel)] [\(tag)] \(message)")
    }
}
