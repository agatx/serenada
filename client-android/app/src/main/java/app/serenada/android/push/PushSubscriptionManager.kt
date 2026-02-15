package app.serenada.android.push

import android.content.Context
import android.util.Log
import app.serenada.android.data.SettingsStore
import app.serenada.android.network.ApiClient
import app.serenada.android.network.PushSubscribeRequest
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale

class PushSubscriptionManager(
    context: Context,
    private val apiClient: ApiClient,
    private val settingsStore: SettingsStore,
    private val pushKeyStore: PushKeyStore = PushKeyStore()
) {
    private val appContext = context.applicationContext

    fun cachedEndpoint(): String? = settingsStore.pushEndpoint?.trim()?.ifBlank { null }

    fun refreshPushEndpoint(onResult: (String?) -> Unit) {
        val cached = cachedEndpoint()
        if (!cached.isNullOrBlank()) {
            onResult(cached)
            return
        }

        if (!FirebasePushInitializer.ensureInitialized(appContext)) {
            onResult(null)
            return
        }

        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w("Push", "Failed to fetch FCM token", task.exception)
                        onResult(null)
                        return@addOnCompleteListener
                    }
                    val token = task.result?.trim().orEmpty()
                    if (token.isBlank()) {
                        onResult(null)
                        return@addOnCompleteListener
                    }
                    settingsStore.pushEndpoint = token
                    onResult(token)
                }
        }.onFailure {
            Log.w("Push", "Unable to request FCM token", it)
            onResult(null)
        }
    }

    fun subscribeRoom(roomId: String, host: String) {
        val normalizedRoomId = roomId.trim()
        if (normalizedRoomId.isBlank()) return

        refreshPushEndpoint { endpoint ->
            val token = endpoint?.trim().orEmpty()
            if (token.isBlank()) {
                Log.w("Push", "Skipping room subscription: missing FCM token")
                return@refreshPushEndpoint
            }

            val publicJwk = pushKeyStore.getPublicJwk()
            val request = PushSubscribeRequest(
                transport = PUSH_TRANSPORT,
                endpoint = token,
                locale = Locale.getDefault().toLanguageTag(),
                encPublicKey = publicJwk
            )

            apiClient.subscribePush(host, normalizedRoomId, request) { result ->
                result.onSuccess {
                    Log.d("Push", "Subscribed Android push for room $normalizedRoomId")
                }.onFailure {
                    Log.w("Push", "Failed to subscribe Android push for room $normalizedRoomId", it)
                }
            }
        }
    }

    companion object {
        const val PUSH_TRANSPORT = "fcm"
    }
}
