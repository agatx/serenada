package app.serenada.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.serenada.android.ui.SerenadaAppRoot

class MainActivity : AppCompatActivity() {
    private val callManager by lazy { (application as SerenadaApp).callManager }
    private var pendingDeepLinkUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkUri = intent?.data
        setContent {
            SerenadaAppRoot(
                callManager = callManager,
                deepLinkUri = pendingDeepLinkUri,
                onDeepLinkConsumed = { pendingDeepLinkUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDeepLinkUri = intent.data
    }
}
