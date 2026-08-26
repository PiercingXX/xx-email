package dev.xxemail.data.auth

import android.net.Uri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * OAuth2 endpoints and scopes.
 *
 * Privacy posture: we request ONLY gmail.modify (+ identity). This scope cannot permanently
 * delete mail (no bypass-trash), which Google enforces server-side regardless of our code.
 * No Play Services involved: plain RFC 8252 native-app flow via any browser / Custom Tabs.
 */
object OAuthConfig {
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REDIRECT_URI = "dev.xxemail:/oauth2redirect"
    const val SCOPES = "openid email profile https://www.googleapis.com/auth/gmail.modify"

    val serviceConfiguration: AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            Uri.parse(AUTH_ENDPOINT),
            Uri.parse(TOKEN_ENDPOINT),
        )
}
