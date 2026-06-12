package com.danielanazodo.proofledger.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInterceptorHostTest {

    private val apiBaseUrl = "https://proofledger-api.onrender.com/"

    @Test
    fun `attaches token to backend api requests`() {
        assertTrue(
            targetsApiHost(
                "https://proofledger-api.onrender.com/screenshots/abc/signed-url".toHttpUrl(),
                apiBaseUrl,
            ),
        )
    }

    @Test
    fun `does not attach token to r2 presigned download`() {
        // Adding Authorization here is what makes R2 reject the download with HTTP 400.
        assertFalse(
            targetsApiHost(
                "https://acct.r2.cloudflarestorage.com/bucket/key?X-Amz-Signature=deadbeef".toHttpUrl(),
                apiBaseUrl,
            ),
        )
    }

    @Test
    fun `attaches token to the backend content fallback url`() {
        // When object storage has no signed URL, the backend serves bytes from
        // its own host — same host as the API, so the token still belongs.
        assertTrue(
            targetsApiHost(
                "https://proofledger-api.onrender.com/screenshots/abc/content?expires=1&signature=x".toHttpUrl(),
                apiBaseUrl,
            ),
        )
    }

    @Test
    fun `handles the local emulator base url`() {
        assertTrue(
            targetsApiHost("http://10.0.2.2:8000/me".toHttpUrl(), "http://10.0.2.2:8000/"),
        )
        assertFalse(
            targetsApiHost(
                "https://acct.r2.cloudflarestorage.com/bucket/key".toHttpUrl(),
                "http://10.0.2.2:8000/",
            ),
        )
    }

    @Test
    fun `fails open when the base url is unparseable`() {
        // A config typo must never silently strip auth from every API call.
        assertTrue(targetsApiHost("https://anything.example/x".toHttpUrl(), "not a url"))
    }
}
