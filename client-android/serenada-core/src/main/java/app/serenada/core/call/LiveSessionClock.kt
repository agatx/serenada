package app.serenada.core.call

class LiveSessionClock : SessionClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
