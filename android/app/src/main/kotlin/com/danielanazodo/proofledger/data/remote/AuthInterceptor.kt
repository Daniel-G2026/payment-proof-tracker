package com.danielanazodo.proofledger.data.remote

import com.danielanazodo.proofledger.BuildConfig
import com.danielanazodo.proofledger.util.DebugLog
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the current Supabase access token to backend requests as
 * `Authorization: Bearer <token>`. If no session is present, the request goes
 * through unmodified and the backend returns 401.
 *
 * The token is attached **only** to requests targeting our own API host. Proof
 * restore downloads the image bytes from a presigned object-store URL (Cloudflare
 * R2), whose auth lives in the query string — adding an `Authorization` header to
 * such a request makes S3/R2 reject it with HTTP 400 ("only one auth mechanism
 * allowed"). Host-gating both fixes that download and avoids leaking our access
 * token to third-party hosts.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val auth: Auth,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val attachToken = targetsApiHost(original.url, BuildConfig.API_BASE_URL)
        val token = if (attachToken) {
            runBlocking {
                auth.awaitInitialization()
                auth.currentSessionOrNull()?.accessToken
            }
        } else {
            null
        }
        DebugLog.d(TAG) {
            "intercept ${original.method} ${original.url} attach=$attachToken " +
                "tokenPresent=${!token.isNullOrBlank()}"
        }
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }
        return try {
            val response = chain.proceed(request)
            DebugLog.d(TAG) { "response ${response.code} ${original.url}" }
            response
        } catch (t: Throwable) {
            // Surface the failure class in release without the URL, which can
            // contain business/transaction ids.
            DebugLog.e(TAG, { "request failed: ${t.javaClass.simpleName}" }, t)
            throw t
        }
    }

    private companion object {
        const val TAG = "proofledger.net"
    }
}

/**
 * True when [requestUrl] points at our own API (so the bearer token belongs on
 * it). Object-store downloads go to a different host and must not carry it.
 * Fails open (returns true) if [apiBaseUrl] can't be parsed, so a config typo
 * can never silently strip auth from every API call.
 */
internal fun targetsApiHost(requestUrl: HttpUrl, apiBaseUrl: String): Boolean {
    val apiHost = apiBaseUrl.toHttpUrlOrNull()?.host ?: return true
    return requestUrl.host.equals(apiHost, ignoreCase = true)
}
