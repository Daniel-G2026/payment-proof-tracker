package com.danielanazodo.proofledger.data.security

import com.danielanazodo.proofledger.util.DebugLog
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Supabase session in EncryptedSharedPreferences via [SecureTokenStore]
 * so it survives app restarts and process death.
 *
 * Uses a lenient Json configuration to round-trip [UserSession] across
 * supabase-kt version upgrades (new optional fields land in stored sessions
 * and we want to keep decoding instead of dropping the user back to SignIn).
 */
@Singleton
class SupabaseSessionManager @Inject constructor(
    private val store: SecureTokenStore,
) : SessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override suspend fun saveSession(session: UserSession) {
        val encoded = json.encodeToString(UserSession.serializer(), session)
        store.put(KEY, encoded)
        DebugLog.d(TAG) { "saveSession: stored ${encoded.length} chars" }
    }

    override suspend fun loadSession(): UserSession? {
        val raw = store.get(KEY)
        if (raw == null) {
            DebugLog.d(TAG) { "loadSession: nothing stored" }
            return null
        }
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }
            .onSuccess { DebugLog.d(TAG) { "loadSession: restored session" } }
            .onFailure { DebugLog.e(TAG, { "loadSession: decode failed" }, it) }
            .getOrNull()
    }

    override suspend fun deleteSession() {
        store.clear(KEY)
        DebugLog.d(TAG) { "deleteSession: cleared" }
    }

    private companion object {
        const val KEY = "supabase_session"
        const val TAG = "proofledger.session"
    }
}
