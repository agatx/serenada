package app.serenada.core.call

enum class CallPhase {
    Idle,
    CreatingRoom,
    Joining,
    Waiting,
    InCall,
    Ending,
    Error
}
