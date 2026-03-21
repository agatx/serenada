package app.serenada.core.network

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

internal fun buildHttpsUrl(hostInput: String, path: String, query: Map<String, String> = emptyMap()): String? {
    val raw = hostInput.trim()
    val isLocal = raw.startsWith("localhost") || raw.startsWith("127.") || raw.startsWith("http://")
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else if (isLocal) "http://$raw" else "https://$raw"
    val base = withScheme.toHttpUrlOrNull() ?: return null
    val builder = base.newBuilder()
        .scheme(if (isLocal) "http" else "https")
        .encodedPath(path)

    for ((key, value) in query) {
        builder.addQueryParameter(key, value)
    }

    return builder.build().toString()
}

class CoreApiClient(private val okHttpClient: OkHttpClient) : SessionAPIClient {
    fun validateServerHost(host: String, onResult: (Result<Unit>) -> Unit) {
        val url = buildHttpsUrl(host, "/api/room-id")
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
                        onResult(Result.failure(IOException("Host validation failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    parseRoomId(body).fold(
                        onSuccess = { onResult(Result.success(Unit)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

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
                    parseRoomId(body).fold(
                        onSuccess = { onResult(Result.success(it)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

    override fun fetchTurnCredentials(host: String, token: String, onResult: (Result<TurnCredentials>) -> Unit) {
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
                    parseTurnCredentials(body).fold(
                        onSuccess = { onResult(Result.success(it)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

    fun fetchDiagnosticToken(host: String, onResult: (Result<String>) -> Unit) {
        val url = buildHttpsUrl(host, "/api/diagnostic-token")
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
                        onResult(Result.failure(IOException("Diagnostic token failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    parseDiagnosticToken(body).fold(
                        onSuccess = { onResult(Result.success(it)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

    private fun parseRoomId(body: String): Result<String> {
        return try {
            val json = JSONObject(body)
            val roomId = json.optString("roomId", "")
            if (roomId.isBlank()) {
                Result.failure(IOException("Room ID missing in response"))
            } else {
                Result.success(roomId)
            }
        } catch (_: Exception) {
            Result.failure(IOException("Invalid room ID response"))
        }
    }

    private fun parseTurnCredentials(body: String): Result<TurnCredentials> {
        return try {
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
                Result.failure(IOException("Invalid TURN credentials"))
            } else {
                Result.success(TurnCredentials(username, password, uris, ttl))
            }
        } catch (_: Exception) {
            Result.failure(IOException("Invalid TURN credentials response"))
        }
    }

    private fun parseDiagnosticToken(body: String): Result<String> {
        return try {
            val token = JSONObject(body).optString("token", "")
            if (token.isBlank()) {
                Result.failure(IOException("Diagnostic token missing in response"))
            } else {
                Result.success(token)
            }
        } catch (_: Exception) {
            Result.failure(IOException("Invalid diagnostic token response"))
        }
    }

}

data class TurnCredentials(
    val username: String,
    val password: String,
    val uris: List<String>,
    val ttl: Int
)
