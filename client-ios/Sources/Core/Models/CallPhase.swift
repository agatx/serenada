import Foundation

enum CallPhase: String, Equatable {
    case idle = "Idle"
    case creatingRoom = "CreatingRoom"
    case joining = "Joining"
    case waiting = "Waiting"
    case inCall = "InCall"
    case ending = "Ending"
    case error = "Error"
}
