package dev.xxemail

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import dev.xxemail.ui.theme.ThemePreset
import dev.xxemail.ui.theme.XxTheme
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

/**
 * Single-activity app. OAuth redirects land in AppAuth's RedirectUriReceiverActivity
 * (declared by the library manifest) and are forwarded here either as an activity
 * result or via onNewIntent; both paths funnel into one callback.
 */
class MainActivity : ComponentActivity() {

    private var authCallback: ((Intent?) -> Unit)? = null
    private var authDelivered = false

    /** A redirect that arrived before [launchOAuthFlow] registered [authCallback]. */
    private var pendingRedirect: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as XxEmailApp).graph

        // New-mail notification taps carry the target account/thread (see Notifier).
        val notificationAccount = intent?.getStringExtra(EXTRA_ACCOUNT)
        val notificationThreadId = intent?.getStringExtra(EXTRA_THREAD_ID)

        setContent {
            val themeMode by graph.settings.themeFlow.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val dynamicColors by graph.settings.dynamicColorsFlow.collectAsStateWithLifecycle(true)
            val themePreset by graph.settings.themePresetFlow.collectAsStateWithLifecycle(ThemePreset.DEFAULT)
            XxTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = dynamicColors,
                preset = themePreset,
            ) {
                CompositionLocalProvider(LocalAuthLauncher provides ::launchOAuthFlow) {
                    XxNavHost(
                        notificationAccount = notificationAccount,
                        notificationThreadId = notificationThreadId,
                    )
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
        // A redirect can arrive (via onNewIntent) before a callback was registered, e.g.
        // while the activity was being recreated mid-sign-in. Deliver it now instead of
        // opening the browser again.
        pendingRedirect?.let { pending ->
            pendingRedirect = null
            deliverRedirect(pending)
        }
        if (!authDelivered) {
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
        if (intent == null || !isAppAuthResult(intent)) return
        if (authCallback == null) {
            // Queue until launchOAuthFlow wires the callback; never drop or misroute.
            pendingRedirect = intent
            return
        }
        deliverRedirect(intent)
    }

    private fun deliverRedirect(intent: Intent) {
        if (!redirectStateMatches(intent)) {
            Log.w(TAG, "Dropped authorization redirect: state does not match any in-flight request")
            return
        }
        (application as XxEmailApp).graph.auth.consumeOutstandingAuthRequest()
        authDelivered = true
        val callback = authCallback
        authCallback = null
        callback?.invoke(intent)
    }

    /**
     * CSRF defense for the exported entry point: an intent only counts as the result of
     * the flow this app itself started when its OAuth `state` equals the one generated
     * in [dev.xxemail.data.auth.AuthRepository.buildAuthIntent]. AuthorizationResponses
     * must match; exceptions are accepted when they match OR carry no state at all
     * (AppAuth 0.11.1 exposes no state on exceptions — compare the raw query parameter).
     */
    private fun redirectStateMatches(intent: Intent): Boolean {
        val expected = (application as XxEmailApp).graph.auth.outstandingAuthState()
        val response = AuthorizationResponse.fromIntent(intent)
        if (response != null) {
            val presented = response.state ?: response.request.state ?: return false
            return presented == expected
        }
        val rawState = intent.data?.getQueryParameter("state")
        return rawState == null || rawState == expected
    }

    /** Only AppAuth result intents qualify; raw VIEW uris are never treated as results. */
    private fun isAppAuthResult(intent: Intent): Boolean =
        AuthorizationResponse.fromIntent(intent) != null || AuthorizationException.fromIntent(intent) != null

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_AUTH = 4242
        const val EXTRA_ACCOUNT = "dev.xxemail.extra.ACCOUNT"
        const val EXTRA_THREAD_ID = "dev.xxemail.extra.THREAD_ID"
    }
}
