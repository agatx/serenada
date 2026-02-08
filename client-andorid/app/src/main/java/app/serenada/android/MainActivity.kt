package app.serenada.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import app.serenada.android.ui.SerenadaAppRoot

class MainActivity : AppCompatActivity() {
    private val callManager by lazy { (application as SerenadaApp).callManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent?.data)
        setContent {
            SerenadaAppRoot(callManager = callManager)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent.data)
    }

    private fun handleDeepLink(uri: Uri?) {
        if (uri == null) return
        callManager.handleDeepLink(uri)
    }
}
