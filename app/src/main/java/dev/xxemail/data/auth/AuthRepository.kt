package dev.xxemail.data.auth

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthState
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth2 via AppAuth (RFC 8252). Works on any device with any browser —
 * no Google Play Services required.
 */
class AuthRepository(
    private val context: Context,
    private val tokens: TokenStore,
    private val settings: dev.xxemail.data.repo.SettingsRepository,
) {

    /** Build the browser intent for the consent screen. Requires a configured client ID. */
    suspend fun buildAuthIntent(): Intent {
        val clientId = settings.clientId()
            ?: error("No OAuth client ID configured. See docs/oauth-setup.md.")
        val request = AuthorizationRequest.Builder(
            OAuthConfig.serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            android.net.Uri.parse(OAuthConfig.REDIRECT_URI),
        )
            .setScope(OAuthConfig.SCOPES)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
        return AuthorizationService(context).getAuthorizationRequestIntent(request)
    }

    /**
     * Complete the flow with the redirect/result intent. Returns the signed-in account email
     * (parsed locally from the id_token — no extra network round-trip).
     */
    suspend fun onAuthorizationResult(resultIntent: Intent?): String {
        val response = resultIntent?.let { AuthorizationResponse.fromIntent(it) }
        val exception = resultIntent?.let { AuthorizationException.fromIntent(it) }
        if (exception != null) throw IllegalStateException("Authorization failed: ${exception.error}", exception)
        if (response == null) throw IllegalStateException("No authorization response")

        val service = AuthorizationService(context)
        val state: AuthState = suspendCancellableCoroutine { cont ->
            service.performTokenRequest(
                response.createTokenExchangeRequest(),
                NoClientAuthentication.INSTANCE,
                object : AuthorizationService.TokenResponseCallback {
                    override fun onTokenRequestCompleted(tokenResponse: TokenResponse?, exception: AuthorizationException?) {
                        val resp = tokenResponse
                        val err = exception
                        when {
                            resp != null -> cont.resume(AuthState(response, resp, null))
                            err != null -> cont.resumeWithException(
                                IllegalStateException("Token exchange failed: ${err.error}", err),
                            )
                            else -> cont.resumeWithException(IllegalStateException("Token exchange returned nothing"))
                        }
                    }
                },
            )
        }
        service.dispose()

        val email = extractEmail(state)
            ?: throw IllegalStateException("Google did not return an email claim")
        tokens.save(email, state)
        Log.i(TAG, "Authorized account stored")
        return email
    }

    /** Run [block] with a fresh access token, transparently refreshing if needed. */
    suspend fun <T> withAccessToken(email: String, block: (String) -> T): T {
        val state = tokens.load(email) ?: error("Account not authorized: $email")
        return suspendCancellableCoroutine { cont ->
            val service = AuthorizationService(context)
            state.performActionWithFreshTokens(service) { accessToken: String?, _: String?, ex: AuthorizationException? ->
                service.dispose()
                when {
                    ex != null -> cont.resumeWithException(IllegalStateException("Token refresh failed (${ex.error}); re-add the account.", ex))
                    accessToken == null -> cont.resumeWithException(IllegalStateException("No access token"))
                    else -> cont.resume(block(accessToken))
                }
            }
        }
    }

    suspend fun signOut(email: String) = tokens.remove(email)

    private suspend fun extractEmail(state: AuthState): String? = withContext(Dispatchers.Default) {
        val idToken = state.idToken ?: return@withContext null
        runCatching {
            val payload = String(Base64.decode(idToken.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(payload).optString("email").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object { private const val TAG = "AuthRepository" }
}
