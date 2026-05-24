package com.danielanazodo.proofledger.data.sync

import retrofit2.HttpException
import java.io.IOException

/**
 * Translates a sync exception into a short, user-readable reason stored in
 * `lastSyncError`. Intentionally generic — no URLs, request bodies, or stack
 * details so it's safe to display in the UI and safe to ship in release logs.
 */
fun Throwable.toSyncErrorMessage(): String {
    return when (val t = this) {
        is HttpException -> when (val code = t.code()) {
            401, 403 -> "Sign in again to continue syncing."
            404 -> "This item no longer exists on the server."
            409 -> "Server has a newer version of this item."
            in 400..499 -> "Request rejected by server (HTTP $code)."
            in 500..599 -> "Server problem — will retry automatically."
            else -> "Sync failed (HTTP $code)."
        }
        is IOException -> "No internet connection."
        else -> t.message?.takeIf { it.isNotBlank() } ?: "Sync failed."
    }
}
