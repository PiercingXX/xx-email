package dev.xxemail.data.auth

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * Shared service for token refreshes (interceptor bursts reuse one instance instead of
     * constructing+disposing per request). Tied to the app context; lives for app lifetime.
     */
    private val refreshService: AuthorizationService by lazy { AuthorizationService(context.applicationContext) }

    private val tokenCache = TokenCache(
        loadSession = { email -> tokens.load(email)?.let { AuthStateSession(it, ::refreshService) } },
        persist = { email, snapshot -> tokens.saveSerialized(email, snapshot) },
    )

    /**
     * The authorization request this app itself created for the flow in progress.
     * Redirect `state` values are validated against it before delivery, so a forged
     * AppAuth-shaped intent from another app can never pose as the auth result.
     */
    @Volatile
    private var pendingAuthRequest: AuthorizationRequest? = null

    /** State of the outstanding [buildAuthIntent] request, or null when no flow is in flight. */
    fun outstandingAuthState(): String? = pendingAuthRequest?.state

    /** Called once the redirect for the outstanding request has been accepted. */
    fun consumeOutstandingAuthRequest() {
        pendingAuthRequest = null
    }

    /** Accounts whose refresh failed permanently and need an interactive sign-in. */
    val reauthNeeded: StateFlow<Set<String>> get() = tokenCache.reauthNeeded

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
        // Remember this request so MainActivity can validate redirect state at delivery.
        pendingAuthRequest = request
        // getAuthorizationRequestIntent needs no binding; dispose immediately (no leak).
        val service = AuthorizationService(context)
        val intent = service.getAuthorizationRequestIntent(request)
        service.dispose()
        return intent
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
        // Evict any cached session/flag so the next use loads the fresh tokens.
        tokenCache.clear(email)
        Log.i(TAG, "Authorized account stored")
        return email
    }

    /** Run [block] with a fresh access token, transparently refreshing if needed. */
    suspend fun <T> withAccessToken(email: String, block: (String) -> T): T =
        tokenCache.withAccessToken(email, block)

    suspend fun signOut(email: String) {
        tokenCache.clear(email)
        tokens.remove(email)
    }

    private suspend fun extractEmail(state: AuthState): String? = withContext(Dispatchers.Default) {
        val idToken = state.idToken ?: return@withContext null
        runCatching {
            val payload = String(Base64.decode(idToken.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(payload).optString("email").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** [TokenSession] backed by AppAuth's AuthState and the shared refresh service. */
    private class AuthStateSession(
        private val state: AuthState,
        private val service: () -> AuthorizationService,
    ) : TokenSession {
        override fun serializedSnapshot(): String = state.jsonSerializeString()

        override fun acquire(onDone: (TokenAcquisition) -> Unit) {
            state.performActionWithFreshTokens(service()) { accessToken: String?, _: String?, ex: AuthorizationException? ->
                onDone(acquisitionFrom(ex, accessToken))
            }
        }

        private fun acquisitionFrom(exception: AuthorizationException?, accessToken: String?): TokenAcquisition = when {
            exception != null && isPermanentAuthError(exception) -> TokenAcquisition.ReauthNeeded(exception.error)
            exception != null -> TokenAcquisition.Failed("Token refresh failed (${exception.error})")
            accessToken != null -> TokenAcquisition.Success(accessToken)
            else -> TokenAcquisition.Failed("No access token")
        }

        private fun isPermanentAuthError(ex: AuthorizationException): Boolean =
            ex.error == INVALID_GRANT || ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR
    }

    companion object {
        private const val TAG = "AuthRepository"
        private const val INVALID_GRANT = "invalid_grant"
    }
}
