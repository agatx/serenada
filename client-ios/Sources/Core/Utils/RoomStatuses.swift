import Foundation
import SerenadaCore

enum RoomStatusIndicatorState: Equatable {
    case hidden
    case waiting
    case full
}

enum RoomStatuses {
    static func indicatorState(for occupancy: RoomOccupancy?) -> RoomStatusIndicatorState {
        let count = occupancy?.count ?? 0
        if count <= 0 {
            return .hidden
        }

        let maxParticipants: Int
        if let max = occupancy?.maxParticipants, max >= 2 {
            maxParticipants = max
        } else {
            maxParticipants = 2
        }

        return count >= maxParticipants ? .full : .waiting
    }
}
