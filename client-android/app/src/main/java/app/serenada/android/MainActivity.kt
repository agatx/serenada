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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : AppCompatActivity() {
    companion object {
        private const val STATE_PENDING_DEEP_LINK = "pending_deep_link"
    }

    private val callManager by lazy { (application as SerenadaApp).callManager }
    private var pendingDeepLinkUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkUri = restorePendingDeepLink(savedInstanceState)
        setContent {
            Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                SerenadaAppRoot(
                    callManager = callManager,
                    deepLinkUri = pendingDeepLinkUri,
                    onDeepLinkConsumed = { pendingDeepLinkUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkUri = intent.data
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_DEEP_LINK, pendingDeepLinkUri?.toString())
    }

    private fun restorePendingDeepLink(savedInstanceState: Bundle?): Uri? {
        if (savedInstanceState?.containsKey(STATE_PENDING_DEEP_LINK) == true) {
            return savedInstanceState
                .getString(STATE_PENDING_DEEP_LINK)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
        }
        return intent?.data
    }
}
