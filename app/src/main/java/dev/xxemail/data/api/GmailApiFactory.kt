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
            .build()

        return Retrofit.Builder()
            .baseUrl("https://gmail.googleapis.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GmailApi::class.java)
    }
}
