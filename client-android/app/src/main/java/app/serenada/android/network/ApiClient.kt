package app.serenada.android.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class ApiClient(private val okHttpClient: OkHttpClient) {
    fun createRoomId(host: String, onResult: (Result<String>) -> Unit) {
        val url = buildHttpsUrl(host, "/api/room-id")
        if (url == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid host")))
            return
        }
        val requestBody = "".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(IOException("Room ID request failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val roomId = json.optString("roomId", "")
                    if (roomId.isBlank()) {
                        onResult(Result.failure(IOException("Room ID missing in response")))
                        return
                    }
                    onResult(Result.success(roomId))
                }
            }
        })
    }

    fun fetchTurnCredentials(host: String, token: String, onResult: (Result<TurnCredentials>) -> Unit) {
        val url = buildHttpsUrl(host, "/api/turn-credentials", mapOf("token" to token))
        if (url == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid host")))
            return
        }
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(IOException("TURN credentials failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val username = json.optString("username", "")
                    val password = json.optString("password", "")
                    val ttl = json.optInt("ttl", 0)
                    val urisJson = json.optJSONArray("uris")
                    val uris = mutableListOf<String>()
                    if (urisJson != null) {
                        for (i in 0 until urisJson.length()) {
                            val uri = urisJson.optString(i, "")
                            if (uri.isNotBlank()) {
                                uris.add(uri)
                            }
                        }
                    }
                    if (username.isBlank() || password.isBlank() || uris.isEmpty()) {
                        onResult(Result.failure(IOException("Invalid TURN credentials")))
                        return
                    }
                    onResult(Result.success(TurnCredentials(username, password, uris, ttl)))
                }
            }
        })
    }

    private fun buildHttpsUrl(hostInput: String, path: String, query: Map<String, String> = emptyMap()): String? {
        val raw = hostInput.trim()
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val base = withScheme.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
            .scheme("https")
            .encodedPath(path)

        for ((key, value) in query) {
            builder.addQueryParameter(key, value)
        }

        return builder.build().toString()
    }
}

data class TurnCredentials(
    val username: String,
    val password: String,
    val uris: List<String>,
    val ttl: Int
)
