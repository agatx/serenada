package app.serenada.android.call

import org.json.JSONObject

class SignalingMessage(
    val type: String,
    val rid: String?,
    val sid: String?,
    val cid: String?,
    val to: String?,
    val payload: JSONObject?
) {
    companion object {
        fun fromJson(raw: String): SignalingMessage? {
            val json = JSONObject(raw)
            val type = json.optString("type").ifBlank { return null }
            return SignalingMessage(
                type = type,
                rid = json.optString("rid").ifBlank { null },
                sid = json.optString("sid").ifBlank { null },
                cid = json.optString("cid").ifBlank { null },
                to = json.optString("to").ifBlank { null },
                payload = json.optJSONObject("payload")
            )
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("type", type)
        if (rid != null) obj.put("rid", rid)
        if (sid != null) obj.put("sid", sid)
        if (cid != null) obj.put("cid", cid)
        if (to != null) obj.put("to", to)
        if (payload != null) obj.put("payload", payload)
        return obj.toString()
    }
}
