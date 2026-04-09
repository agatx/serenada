package app.serenada.core.call

/** Mode of a call: video (default) or voice (audio-only with optional camera sharing). */
enum class CallMode {
    /** Full video call (up to 4 participants). */
    VIDEO,
    /** Audio-first call with optional per-participant video (up to 8 participants). */
    VOICE,
}
