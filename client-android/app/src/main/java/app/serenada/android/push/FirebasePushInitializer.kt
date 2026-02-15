package app.serenada.android.push

import android.content.Context
import android.util.Log
import app.serenada.android.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebasePushInitializer {
    fun ensureInitialized(context: Context): Boolean {
        val appContext = context.applicationContext
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }

        val appId = BuildConfig.FIREBASE_APP_ID.trim()
        val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
        val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
        val senderId = BuildConfig.FIREBASE_SENDER_ID.trim()
        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            Log.w("Push", "Firebase push is not configured (missing firebase* Gradle properties)")
            return false
        }

        val options = FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build()

        return runCatching {
            FirebaseApp.initializeApp(appContext, options)
            FirebaseApp.getApps(appContext).isNotEmpty()
        }.onFailure {
            Log.e("Push", "Failed to initialize Firebase", it)
        }.getOrDefault(false)
    }
}
