package app.serenada.core.fakes

import app.serenada.core.MultiSessionSignalingProvider
import app.serenada.core.SignalingProvider
import org.webrtc.PeerConnection

/**
 * One app-global fake [MultiSessionSignalingProvider] that vends a recordable
 * [FakeProviderChannel] per session (contract §"Custom provider"). Mirrors a
 * correct implementor: every [openSession] mints a distinct channel bound to one
 * room, and a channel drops inbound events once it has closed. Tests assert
 * per-session isolation and channel-scoped lifecycle through these records.
 */
internal class FakeMultiSessionSignalingProvider : MultiSessionSignalingProvider {
    /** Room ids passed to [openSession], in call order. */
    val openSessionRoomIds = mutableListOf<String>()

    /** Vended channels, in creation order. */
    val channels = mutableListOf<FakeProviderChannel>()

    /** Diagnostic ICE fetches made WITHOUT a session (via [getIceServers]). */
    var getIceServersCalls = 0
        private set

    override fun openSession(roomId: String): SignalingProvider {
        openSessionRoomIds += roomId
        return FakeProviderChannel(roomId).also { channels += it }
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        getIceServersCalls += 1
        return emptyList()
    }

    /** The single channel bound to [roomId], or null when none was vended. */
    fun channelFor(roomId: String): FakeProviderChannel? =
        channels.firstOrNull { it.channelRoomId == roomId }
}

/**
 * A per-session channel vended by [FakeMultiSessionSignalingProvider]. Extends the
 * single-session [FakeSignalingProvider] recorder and adds the channel's canonical
 * room id plus the implementor's closed-channel guard: once [disconnect] has run,
 * the channel drops its listener so a stale/queued event is never delivered to the
 * torn-down session.
 */
internal class FakeProviderChannel(val channelRoomId: String) : FakeSignalingProvider() {
    var closed = false
        private set

    override fun disconnect() {
        super.disconnect()
        // Channel-generation guard (implementor obligation): after close, no further
        // event reaches the session that owned this channel.
        closed = true
        listener = null
    }
}
