package app.serenada.core.call

interface SessionClock {
    fun nowMs(): Long
}
