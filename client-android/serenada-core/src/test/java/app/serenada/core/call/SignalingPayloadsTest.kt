package app.serenada.core.call

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalingPayloadsTest {

    // ---------------------------------------------------------------
    // toJoinedPayload
    // ---------------------------------------------------------------

    @Test
    fun toJoinedPayload_fullParse() {
        val json = JSONObject().apply {
            put("hostCid", "C-host")
            put("turnToken", "tok123")
            put("turnTokenTTLMs", 60000L)
            put("reconnectToken", "rk-abc")
            put("reconnectTokenTTLMs", 1_200_000L)
            put("maxParticipants", 4)
            put("participants", JSONArray().apply {
                put(JSONObject().apply { put("cid", "C-host"); put("joinedAt", 1000) })
                put(JSONObject().apply { put("cid", "C-guest") })
            })
        }
        val payload = json.toJoinedPayload()
        assertNotNull(payload)
        assertEquals("C-host", payload!!.hostCid)
        assertEquals("tok123", payload.turnToken)
        assertEquals(60000L, payload.turnTokenTTLMs)
        assertEquals("rk-abc", payload.reconnectToken)
        assertEquals(1_200_000L, payload.reconnectTokenTTLMs)
        assertEquals(4, payload.maxParticipants)
        assertEquals(2, payload.participants.size)
        assertEquals("C-host", payload.participants[0].cid)
        assertEquals(1000L, payload.participants[0].joinedAt)
        assertNull(payload.participants[1].joinedAt)
    }

    @Test
    fun toJoinedPayload_nullReturnsNull() {
        val result = (null as JSONObject?).toJoinedPayload()
        assertNull(result)
    }

    @Test
    fun toJoinedPayload_blankHostCidBecomesNull() {
        val json = JSONObject().apply { put("hostCid", "  ") }
        val payload = json.toJoinedPayload()
        assertNull(payload!!.hostCid)
    }

    @Test
    fun toJoinedPayload_zeroTurnTokenTTLBecomesNull() {
        val json = JSONObject().apply { put("turnTokenTTLMs", 0) }
        val payload = json.toJoinedPayload()
        assertNull(payload!!.turnTokenTTLMs)
    }

    @Test
    fun toReconnectTokenRefreshedPayload_fullParse() {
        val json = JSONObject().apply {
            put("reconnectToken", "rk-new")
            put("reconnectTokenTTLMs", 1_200_000L)
        }
        val payload = json.toReconnectTokenRefreshedPayload()
        assertNotNull(payload)
        assertEquals("rk-new", payload!!.reconnectToken)
        assertEquals(1_200_000L, payload.reconnectTokenTTLMs)
    }

    @Test
    fun toReconnectTokenRefreshedPayload_missingTokenReturnsNull() {
        val json = JSONObject().apply { put("reconnectTokenTTLMs", 1_200_000L) }
        assertNull(json.toReconnectTokenRefreshedPayload())
    }

    // ---------------------------------------------------------------
    // toRoomStatePayload
    // ---------------------------------------------------------------

    @Test
    fun toRoomStatePayload_fullParse() {
        val json = JSONObject().apply {
            put("hostCid", "C-host")
            put("maxParticipants", 2)
            put("participants", JSONArray().apply {
                put(JSONObject().apply { put("cid", "C-a") })
            })
        }
        val payload = json.toRoomStatePayload()
        assertNotNull(payload)
        assertEquals("C-host", payload!!.hostCid)
        assertEquals(2, payload.maxParticipants)
        assertEquals(1, payload.participants.size)
    }

    @Test
    fun toRoomStatePayload_nullReturnsNull() {
        assertNull((null as JSONObject?).toRoomStatePayload())
    }

    // ---------------------------------------------------------------
    // toErrorPayload
    // ---------------------------------------------------------------

    @Test
    fun toErrorPayload_fullParse() {
        val json = JSONObject().apply {
            put("code", "ROOM_CAPACITY_UNSUPPORTED")
            put("message", "Room is full")
        }
        val payload = json.toErrorPayload()
        assertEquals("ROOM_CAPACITY_UNSUPPORTED", payload!!.code)
        assertEquals("Room is full", payload.message)
    }

    @Test
    fun toErrorPayload_nullReturnsNull() {
        assertNull((null as JSONObject?).toErrorPayload())
    }

    @Test
    fun toErrorPayload_blankCodeBecomesNull() {
        val json = JSONObject().apply {
            put("code", "  ")
            put("message", "")
        }
        val payload = json.toErrorPayload()
        assertNull(payload!!.code)
        assertNull(payload.message)
    }

    // ---------------------------------------------------------------
    // toContentStatePayload
    // ---------------------------------------------------------------

    @Test
    fun toContentStatePayload_active() {
        val json = JSONObject().apply {
            put("from", "C-peer")
            put("active", true)
            put("contentType", "screen")
        }
        val payload = json.toContentStatePayload()
        assertNotNull(payload)
        assertEquals("C-peer", payload!!.fromCid)
        assertTrue(payload.active)
        assertEquals("screen", payload.contentType)
    }

    @Test
    fun toContentStatePayload_inactive() {
        val json = JSONObject().apply {
            put("from", "C-peer")
            put("active", false)
            put("contentType", "screen")
        }
        val payload = json.toContentStatePayload()
        assertFalse(payload!!.active)
        assertNull(payload.contentType)
    }

    @Test
    fun toContentStatePayload_parsesRevision() {
        val json = JSONObject().apply {
            put("from", "C-peer")
            put("active", true)
            put("contentType", "screenShare")
            put("revision", 7)
        }
        val payload = json.toContentStatePayload()
        assertEquals(7L, payload!!.revision)
    }

    @Test
    fun toContentStatePayload_missingRevisionIsNull() {
        val json = JSONObject().apply {
            put("from", "C-peer")
            put("active", true)
        }
        val payload = json.toContentStatePayload()
        assertNull(payload!!.revision)
    }

    @Test
    fun toContentStatePayload_nullReturnsNull() {
        assertNull((null as JSONObject?).toContentStatePayload())
    }

    @Test
    fun toContentStatePayload_blankFromCidReturnsNull() {
        val json = JSONObject().apply {
            put("from", "")
            put("active", true)
        }
        assertNull(json.toContentStatePayload())
    }

    // ---------------------------------------------------------------
    // toParticipantList
    // ---------------------------------------------------------------

    @Test
    fun toParticipantList_emptyArray() {
        val result = JSONArray().toParticipantList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun toParticipantList_validEntries() {
        val arr = JSONArray().apply {
            put(JSONObject().apply { put("cid", "C-1"); put("joinedAt", 500) })
            put(JSONObject().apply { put("cid", "C-2") })
        }
        val result = arr.toParticipantList()
        assertEquals(2, result.size)
        assertEquals("C-1", result[0].cid)
        assertEquals(500L, result[0].joinedAt)
        assertNull(result[1].joinedAt)
    }

    @Test
    fun toParticipantList_skipsBlankCid() {
        val arr = JSONArray().apply {
            put(JSONObject().apply { put("cid", "") })
            put(JSONObject().apply { put("cid", "C-valid") })
        }
        val result = arr.toParticipantList()
        assertEquals(1, result.size)
        assertEquals("C-valid", result[0].cid)
    }

    @Test
    fun toParticipantList_nullReturnsEmpty() {
        val result = (null as JSONArray?).toParticipantList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun toParticipantList_parsesCapabilitiesAndMediaPolicy() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("cid", "C-1")
                put("capabilities", JSONObject().apply {
                    put("independentContentVideo", true)
                    put("trickleIce", true) // unknown-to-model key tolerated
                })
                put("mediaPolicy", JSONObject().apply { put("videoMediaEnabled", false) })
            })
        }
        val p = arr.toParticipantList().single()
        assertTrue(p.capabilities!!.independentContentVideo)
        assertFalse(p.mediaPolicy!!.videoMediaEnabled)
    }

    @Test
    fun toParticipantList_capabilitiesDefaultsWhenAbsent() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("cid", "C-1")
                // Present but empty capabilities/mediaPolicy objects.
                put("capabilities", JSONObject())
                put("mediaPolicy", JSONObject())
            })
        }
        val p = arr.toParticipantList().single()
        assertFalse("independentContentVideo defaults to false", p.capabilities!!.independentContentVideo)
        assertTrue("videoMediaEnabled defaults to true", p.mediaPolicy!!.videoMediaEnabled)
    }

    @Test
    fun toParticipantList_capabilitiesAndPolicyNullWhenObjectsAbsent() {
        val arr = JSONArray().apply {
            put(JSONObject().apply { put("cid", "C-1") })
        }
        val p = arr.toParticipantList().single()
        assertNull(p.capabilities)
        assertNull(p.mediaPolicy)
    }

    @Test
    fun toParticipantList_parsesContentStateRevision() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("cid", "C-1")
                put("contentState", JSONObject().apply {
                    put("active", true)
                    put("contentType", "screenShare")
                    put("revision", 3)
                })
            })
        }
        val p = arr.toParticipantList().single()
        val content = p.contentState!!
        assertTrue(content.active)
        assertEquals(3L, content.revision)
    }

    @Test
    fun toContentStatePayload_ignoresMalformedRevision() {
        val payload = JSONObject().apply {
            put("from", "C-1")
            put("active", true)
            put("revision", "bad")
        }.toContentStatePayload()

        assertEquals("C-1", payload!!.fromCid)
        assertTrue(payload.active)
        assertNull(payload.revision)
    }

    @Test
    fun toParticipantList_ignoresMalformedContentStateRevision() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("cid", "C-1")
                put("contentState", JSONObject().apply {
                    put("active", true)
                    put("revision", 1.5)
                })
            })
        }
        val content = arr.toParticipantList().single().contentState!!
        assertTrue(content.active)
        assertNull(content.revision)
    }

    @Test
    fun toParticipantList_ignoresUnsafeContentStateRevision() {
        val arr = JSONArray().apply {
            put(JSONObject().apply {
                put("cid", "C-1")
                put("contentState", JSONObject().apply {
                    put("active", true)
                    put("revision", 9_007_199_254_740_992L)
                })
            })
        }
        val content = arr.toParticipantList().single().contentState!!
        assertTrue(content.active)
        assertNull(content.revision)
    }
}
