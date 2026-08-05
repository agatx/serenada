package app.serenada.core.call

// Enabling the capability on Serenada's native factory is inert until a session
// with enableOpusRed=true promotes RED ahead of Opus on its audio transceivers.
internal const val SERENADA_WEBRTC_FIELD_TRIALS = "WebRTC-Audio-Red-For-Opus/Enabled/"
