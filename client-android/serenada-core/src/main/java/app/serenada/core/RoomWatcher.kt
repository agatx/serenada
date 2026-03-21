package app.serenada.core

import android.os.Handler
import android.os.Looper
import app.serenada.core.call.SignalingClient
import app.serenada.core.call.SignalingMessage
import kotlin.math.max
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class RoomOccupancy(
    val count: Int,
    val maxParticipants: Int? = null,
)

interface RoomWatcherDelegate {
    fun roomWatcher(watcher: RoomWatcher, didUpdateStatuses: Map<String, RoomOccupancy>)
}

class RoomWatcher @JvmOverloads constructor(
    okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    var delegate: RoomWatcherDelegate? = null

    private lateinit var signalingClient: SignalingClient
    private var watchedRoomIds: List<String> = emptyList()
    private var statuses: Map<String, RoomOccupancy> = emptyMap()
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var host: String? = null

    init {
        signalingClient = SignalingClient(
            okHttpClient = okHttpClient,
            handler = handler,
            initialListener = object : SignalingClient.Listener {
                override fun onOpen(activeTransport: String) {
                    reconnectAttempts = 0
                    clearReconnect()
                    sendWatchRooms()
                }

                override fun onMessage(message: SignalingMessage) {
                    when (message.type) {
                        "room_statuses" -> {
                            val watched = watchedRoomIds.toSet()
                            statuses =
                                RoomOccupancies
                                    .mergeStatusesPayload(previous = statuses, payload = message.payload)
                                    .filterKeys { watched.contains(it) }
                            delegate?.roomWatcher(this@RoomWatcher, statuses)
                        }

                        "room_status_update" -> {
                            val rid = message.payload?.optString("rid").orEmpty()
                            if (!watchedRoomIds.contains(rid)) {
                                return
                            }
                            statuses =
                                RoomOccupancies.mergeStatusUpdatePayload(previous = statuses, payload = message.payload)
                            delegate?.roomWatcher(this@RoomWatcher, statuses)
                        }

                        "pong" -> signalingClient.recordPong()
                    }
                }

                override fun onClosed(reason: String) {
                    scheduleReconnect()
                }
            },
        )
    }

    val currentStatuses: Map<String, RoomOccupancy>
        get() = statuses

    fun watchRooms(roomIds: List<String>, host: String) {
        val hostChanged = this.host?.equals(host, ignoreCase = true) == false
        this.host = host
        watchedRoomIds = roomIds

        val watched = watchedRoomIds.toSet()
        statuses = statuses.filterKeys { watched.contains(it) }

        clearReconnect()
        if (hostChanged) {
            signalingClient.close()
        }

        if (watchedRoomIds.isEmpty()) {
            signalingClient.close()
            return
        }

        if (signalingClient.isConnected()) {
            sendWatchRooms()
        } else {
            signalingClient.connect(host)
        }
    }

    fun stop() {
        clearReconnect()
        watchedRoomIds = emptyList()
        statuses = emptyMap()
        host = null
        signalingClient.close()
    }

    private fun sendWatchRooms() {
        if (watchedRoomIds.isEmpty()) return
        if (!signalingClient.isConnected()) return

        val payload = JSONObject().apply {
            put("rids", JSONArray(watchedRoomIds))
        }
        signalingClient.send(
            SignalingMessage(
                type = "watch_rooms",
                rid = null,
                sid = null,
                cid = null,
                to = null,
                payload = payload,
            ),
        )
    }

    private fun clearReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleReconnect() {
        val reconnectHost = host ?: return
        if (watchedRoomIds.isEmpty()) return
        if (reconnectRunnable != null) return

        reconnectAttempts += 1
        val backoffMs = (500L * (1L shl minOf(reconnectAttempts - 1, 13))).coerceAtMost(5_000L)
        val runnable = Runnable {
            reconnectRunnable = null
            if (watchedRoomIds.isEmpty()) return@Runnable
            if (!signalingClient.isConnected()) {
                signalingClient.connect(reconnectHost)
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, backoffMs)
    }
}

internal object RoomOccupancies {
    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = opt(key)
        }
        return result
    }

    private fun normalizeMaxParticipants(value: Int?, fallback: Int?): Int? {
        if (value != null && value >= 2) {
            return value
        }
        if (fallback != null && fallback >= 2) {
            return fallback
        }
        return null
    }

    private fun parseOccupancy(value: Any?, fallback: RoomOccupancy?): RoomOccupancy? {
        return when (value) {
            is Number ->
                RoomOccupancy(
                    count = max(0, value.toInt()),
                    maxParticipants = normalizeMaxParticipants(null, fallback?.maxParticipants),
                )

            is JSONObject -> {
                if (!value.has("count")) {
                    null
                } else {
                    val maxParticipants =
                        if (value.has("maxParticipants")) value.optInt("maxParticipants") else null
                    RoomOccupancy(
                        count = max(0, value.optInt("count", 0)),
                        maxParticipants = normalizeMaxParticipants(maxParticipants, fallback?.maxParticipants),
                    )
                }
            }

            is Map<*, *> -> {
                val count = (value["count"] as? Number)?.toInt() ?: return null
                val maxParticipants = (value["maxParticipants"] as? Number)?.toInt()
                RoomOccupancy(
                    count = max(0, count),
                    maxParticipants = normalizeMaxParticipants(maxParticipants, fallback?.maxParticipants),
                )
            }

            else -> null
        }
    }

    internal fun mergeStatusesPayload(
        previous: Map<String, RoomOccupancy>,
        payload: Map<String, Any?>?,
    ): Map<String, RoomOccupancy> {
        if (payload == null) return previous

        val next = previous.toMutableMap()
        for ((rid, value) in payload) {
            val occupancy = parseOccupancy(value, previous[rid]) ?: continue
            next[rid] = occupancy
        }
        return next
    }

    internal fun mergeStatusesPayload(
        previous: Map<String, RoomOccupancy>,
        payload: JSONObject?,
    ): Map<String, RoomOccupancy> {
        return mergeStatusesPayload(previous = previous, payload = payload?.toMap())
    }

    internal fun mergeStatusUpdatePayload(
        previous: Map<String, RoomOccupancy>,
        payload: Map<String, Any?>?,
    ): Map<String, RoomOccupancy> {
        if (payload == null) return previous

        val rid = (payload["rid"] as? String).orEmpty()
        val count = (payload["count"] as? Number)?.toInt() ?: return previous
        val maxParticipants = (payload["maxParticipants"] as? Number)?.toInt()
        if (rid.isBlank()) return previous

        return previous.toMutableMap().apply {
            this[rid] = RoomOccupancy(
                count = max(0, count),
                maxParticipants = normalizeMaxParticipants(maxParticipants, previous[rid]?.maxParticipants),
            )
        }
    }

    internal fun mergeStatusUpdatePayload(
        previous: Map<String, RoomOccupancy>,
        payload: JSONObject?,
    ): Map<String, RoomOccupancy> {
        return mergeStatusUpdatePayload(previous = previous, payload = payload?.toMap())
    }
}
