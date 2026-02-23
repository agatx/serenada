package app.serenada.android.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ApiClient(private val okHttpClient: OkHttpClient) {
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

    fun fetchPushRecipients(host: String, roomId: String, onResult: (Result<List<PushRecipient>>) -> Unit) {
        val url = buildHttpsUrl(host, "/api/push/recipients", mapOf("roomId" to roomId))
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
                        onResult(Result.failure(IOException("Push recipients request failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    parsePushRecipients(body).fold(
                        onSuccess = { onResult(Result.success(it)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

    fun subscribePush(
        host: String,
        roomId: String,
        request: PushSubscribeRequest,
        onResult: (Result<Unit>) -> Unit
    ) {
        val url = buildHttpsUrl(host, "/api/push/subscribe", mapOf("roomId" to roomId))
        if (url == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid host")))
            return
        }

        val payload = JSONObject().apply {
            put("transport", request.transport)
            put("endpoint", request.endpoint)
            put("locale", request.locale)
            if (!request.auth.isNullOrBlank() && !request.p256dh.isNullOrBlank()) {
                put(
                    "keys",
                    JSONObject().apply {
                        put("auth", request.auth)
                        put("p256dh", request.p256dh)
                    }
                )
            }
            request.encPublicKey?.let { put("encPublicKey", it) }
        }
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        okHttpClient.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(IOException("Push subscribe failed: ${response.code}")))
                        return
                    }
                    onResult(Result.success(Unit))
                }
            }
        })
    }

    fun uploadPushSnapshot(
        host: String,
        request: PushSnapshotUploadRequest,
        onResult: (Result<String>) -> Unit
    ) {
        val url = buildHttpsUrl(host, "/api/push/snapshot")
        if (url == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid host")))
            return
        }
        val recipientsJson = JSONArray().apply {
            request.recipients.forEach { recipient ->
                put(
                    JSONObject().apply {
                        put("id", recipient.id)
                        put("wrappedKey", recipient.wrappedKey)
                        put("wrappedKeyIv", recipient.wrappedKeyIv)
                    }
                )
            }
        }
        val payload = JSONObject().apply {
            put("ciphertext", request.ciphertext)
            put("snapshotIv", request.snapshotIv)
            put("snapshotSalt", request.snapshotSalt)
            put("snapshotEphemeralPubKey", request.snapshotEphemeralPubKey)
            put("snapshotMime", request.snapshotMime)
            put("recipients", recipientsJson)
        }
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        okHttpClient.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(IOException("Push snapshot upload failed: ${response.code}")))
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    parseSnapshotId(body).fold(
                        onSuccess = { onResult(Result.success(it)) },
                        onFailure = { onResult(Result.failure(it)) }
                    )
                }
            }
        })
    }

    fun sendPushInvite(
        host: String,
        roomId: String,
        endpoint: String?,
        onResult: (Result<Unit>) -> Unit
    ) {
        val url = buildHttpsUrl(host, "/api/push/invite", mapOf("roomId" to roomId))
        if (url == null) {
            onResult(Result.failure(IllegalArgumentException("Invalid host")))
            return
        }

        val payload = JSONObject().apply {
            endpoint?.trim()?.takeIf { it.isNotBlank() }?.let { put("endpoint", it) }
        }
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        okHttpClient.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(Result.failure(IOException("Push invite failed: ${response.code}")))
                        return
                    }
                    onResult(Result.success(Unit))
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

    private fun parsePushRecipients(body: String): Result<List<PushRecipient>> {
        return try {
            val recipientsJson = JSONArray(body)
            val recipients = mutableListOf<PushRecipient>()
            for (i in 0 until recipientsJson.length()) {
                val item = recipientsJson.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id <= 0) continue
                val publicKey = item.optJSONObject("publicKey") ?: continue
                val kty = publicKey.optString("kty", "")
                val crv = publicKey.optString("crv", "")
                val x = publicKey.optString("x", "")
                val y = publicKey.optString("y", "")
                if (kty != "EC" || crv != "P-256" || x.isBlank() || y.isBlank()) continue
                recipients.add(
                    PushRecipient(
                        id = id,
                        publicKey = PushRecipientPublicKey(
                            kty = kty,
                            crv = crv,
                            x = x,
                            y = y
                        )
                    )
                )
            }
            Result.success(recipients)
        } catch (_: Exception) {
            Result.failure(IOException("Invalid push recipients response"))
        }
    }

    private fun parseSnapshotId(body: String): Result<String> {
        return try {
            val snapshotId = JSONObject(body).optString("id", "")
            if (snapshotId.isBlank()) {
                Result.failure(IOException("Snapshot ID missing in response"))
            } else {
                Result.success(snapshotId)
            }
        } catch (_: Exception) {
            Result.failure(IOException("Invalid push snapshot response"))
        }
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

data class PushRecipientPublicKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String
)

data class PushRecipient(
    val id: Int,
    val publicKey: PushRecipientPublicKey
)

data class PushSnapshotRecipient(
    val id: Int,
    val wrappedKey: String,
    val wrappedKeyIv: String
)

data class PushSnapshotUploadRequest(
    val ciphertext: String,
    val snapshotIv: String,
    val snapshotSalt: String,
    val snapshotEphemeralPubKey: String,
    val snapshotMime: String,
    val recipients: List<PushSnapshotRecipient>
)

data class PushSubscribeRequest(
    val transport: String,
    val endpoint: String,
    val locale: String,
    val encPublicKey: JSONObject? = null,
    val auth: String? = null,
    val p256dh: String? = null
)
