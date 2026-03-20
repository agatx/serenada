package app.serenada.core.call

enum class CallPhase {
    Idle,
    CreatingRoom,
    AwaitingPermissions,
    Joining,
    Waiting,
    InCall,
    Ending,
    Error
}
