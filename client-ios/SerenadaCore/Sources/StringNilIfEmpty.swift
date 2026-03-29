import Foundation

internal extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
