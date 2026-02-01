package app.serenada.android.call

enum class CallPhase {
    Idle,
    CreatingRoom,
    Joining,
    Waiting,
    InCall,
    Ending,
    Error
}
