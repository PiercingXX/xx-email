package dev.xxemail.data.api

import dev.xxemail.data.auth.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object GmailApiFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Marks a retried request so a second 401 cannot loop the authenticator. */
    private const val REAUTH_RETRY_HEADER = "X-Xx-Reauth-Retry"

    fun create(accountEmail: String, auth: AuthRepository): GmailApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // OkHttp interceptors run on worker threads; runBlocking is safe here.
                val token = runBlocking { auth.withAccessToken(accountEmail) { it } }
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build(),
                )
            }
            .authenticator { _, response ->
                // F9: a 401 means the access token we just acquired was rejected. Route
                // through TokenCache again: if the refresh now fails permanently (e.g.
                // invalid_grant) TokenCache flips its reauthNeeded flow — the existing B4
                // "sign in again" banner picks it up instead of the sync failing silently.
                if (response.code != 401) return@authenticator null
                // One automatic re-auth attempt per request; a second 401 on the retried
                // request must not loop.
                if (response.request.header(REAUTH_RETRY_HEADER) != null) return@authenticator null
                val failedAuth = response.request.header("Authorization")
                val fresh = runCatching { runBlocking { auth.withAccessToken(accountEmail) { it } } }
                    .getOrNull()
                    ?: return@authenticator null
                // Same token ⇒ server genuinely rejects us (revoked/delegated scopes gone);
                // retrying would loop, and no refresh failure occurred to raise the banner.
                // The 401 surfaces as an HttpException and shows in the sync-error snackbar.
                if ("Bearer $fresh" == failedAuth) return@authenticator null
                response.request.newBuilder()
                    .header(REAUTH_RETRY_HEADER, "1")
                    .header("Authorization", "Bearer $fresh")
                    .build()
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://gmail.googleapis.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GmailApi::class.java)
    }
}
