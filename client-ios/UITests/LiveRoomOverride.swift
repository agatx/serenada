import Foundation
import XCTest

enum LiveRoomOverride {
    static func require(keys: [String], purpose: String) throws -> String {
        for key in keys {
            let value = ProcessInfo.processInfo.environment[key]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !value.isEmpty {
                return value
            }
        }

        let joinedKeys = keys.joined(separator: " or ")
        throw XCTSkip("Requires live room override via \(joinedKeys) for \(purpose)")
    }
}
