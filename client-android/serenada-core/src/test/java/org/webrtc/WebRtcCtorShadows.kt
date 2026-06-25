package org.webrtc

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The real `RtpTransceiver(long)`, `RtpReceiver(long)`, and `RtpSender(long)`
 * constructors eagerly call into native libwebrtc (`nativeGetSender`,
 * `nativeGetReceiver`, `nativeGetTrack`, ...) to populate cached fields, which
 * throws `UnsatisfiedLinkError` under a plain JVM unit test. These Robolectric
 * shadows replace ONLY those constructors with a no-op so the JVM-only fakes in
 * `FakeWebRtcTransceivers.kt` can be instantiated.
 *
 * Everything else (mid, mediaType, receiver, sender, direction, track) is
 * supplied by the fake subclasses overriding the public getters; no other method
 * is shadowed. Apply with `@Config(shadows = [...])` on the test class.
 */

@Implements(RtpTransceiver::class)
class ShadowRtpTransceiver {
    @Implementation
    fun __constructor__(nativeRtpTransceiver: Long) {
        // Skip the native getSender/getReceiver caching the real ctor performs.
    }
}

@Implements(RtpReceiver::class)
class ShadowRtpReceiver {
    @Implementation
    fun __constructor__(nativeRtpReceiver: Long) {
        // Skip the native getTrack the real ctor performs.
    }
}

@Implements(RtpSender::class)
class ShadowRtpSender {
    @Implementation
    fun __constructor__(nativeRtpSender: Long) {
        // Skip the native getTrack/getMediaType/getDtmfSender the real ctor performs.
    }
}

@Implements(PeerConnectionFactory::class)
class ShadowPeerConnectionFactory {
    @Implementation
    fun __constructor__(nativeFactory: Long) {
        // Skip checkInitializeHasBeenCalled() so a fake factory can be built
        // without a real native PeerConnectionFactory.initialize().
    }
}

@Implements(MediaStreamTrack::class)
class ShadowMediaStreamTrack {
    @Implementation
    fun __constructor__(nativeTrack: Long) {
        // The real ctor rejects a 0 (= "null") native handle; FakeVideoTrack uses
        // 0L because it never touches native. Skip the precondition check.
    }
}
