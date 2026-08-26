package dev.xxemail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.xxemail.data.repo.ThemeMode
import dev.xxemail.ui.nav.LocalAuthLauncher
import dev.xxemail.ui.nav.XxNavHost
import dev.xxemail.ui.theme.XxTheme
import kotlinx.coroutines.launch

/**
 * Single-activity app. Also the OAuth redirect target (see manifest intent-filter):
 * both onActivityResult and onNewIntent are routed into one callback because browsers
 * may deliver the redirect either way depending on launch flags.
 */
class MainActivity : ComponentActivity() {

    private var authCallback: ((Intent?) -> Unit)? = null
    private var authDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as XxEmailApp).graph

        setContent {
            val themeMode by graph.settings.themeFlow.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val dynamicColors by graph.settings.dynamicColorsFlow.collectAsStateWithLifecycle(true)
            XxTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = dynamicColors,
            ) {
                CompositionLocalProvider(LocalAuthLauncher provides ::launchOAuthFlow) {
                    XxNavHost()
                }
            }
        }

        // A redirect may arrive via onNewIntent before setContent finishes wiring a callback.
        intent?.let { maybeConsumeRedirect(it) }
    }

    private fun launchOAuthFlow(onResult: (Result<String>) -> Unit) {
        authDelivered = false
        authCallback = { data ->
            lifecycleScope.launch {
                onResult(runCatching { (application as XxEmailApp).graph.auth.onAuthorizationResult(data) })
            }
        }
        lifecycleScope.launch {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult((application as XxEmailApp).graph.auth.buildAuthIntent(), REQ_AUTH)
            } catch (t: Throwable) {
                authCallback = null
                onResult(Result.failure(t))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTH) maybeConsumeRedirect(data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeConsumeRedirect(intent)
    }

    private fun maybeConsumeRedirect(intent: Intent?) {
        if (authDelivered) return
        if (intent?.data?.scheme == "dev.xxemail") {
            authDelivered = true
            val callback = authCallback
            authCallback = null
            callback?.invoke(intent)
        }
    }

    companion object { private const val REQ_AUTH = 4242 }
}
