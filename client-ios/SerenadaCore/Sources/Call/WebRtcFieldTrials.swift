import Foundation
@preconcurrency import WebRTC

private let serenadaWebRtcFieldTrialsLock = NSLock()
private var serenadaWebRtcFieldTrialsInitialized = false

// Audio RED availability in native libwebrtc is process-wide. Enabling the
// capability here is inert until a session with enableOpusRed=true promotes RED
// ahead of Opus on its audio transceivers.
func initializeSerenadaWebRtcFieldTrialsIfNeeded() {
    serenadaWebRtcFieldTrialsLock.lock()
    defer { serenadaWebRtcFieldTrialsLock.unlock() }
    guard !serenadaWebRtcFieldTrialsInitialized else { return }
    RTCInitFieldTrialDictionary(["WebRTC-Audio-Red-For-Opus": "Enabled"])
    serenadaWebRtcFieldTrialsInitialized = true
}
