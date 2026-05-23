package com.danielanazodo.proofledger.data.remote

import android.util.Log
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the current Supabase access token to every outbound request as
 * `Authorization: Bearer <token>`. If no session is present, the request goes
 * through unmodified and the backend returns 401.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val auth: Auth,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runBlocking {
            auth.awaitInitialization()
            auth.currentSessionOrNull()?.accessToken
        }
        Log.d(
            TAG,
            "intercept ${original.method} ${original.url} token=${token?.take(20)}...",
        )
        val request = original.newBuilder().apply {
            if (!token.isNullOrBlank()) {
                header("Authorization", "Bearer $token")
            }
        }.build()
        return try {
            val response = chain.proceed(request)
            Log.d(TAG, "response ${response.code} ${original.url}")
            response
        } catch (t: Throwable) {
            Log.e(TAG, "request failed ${original.url}: ${t.message}", t)
            throw t
        }
    }

    private companion object {
        const val TAG = "proofledger.net"
    }
}
