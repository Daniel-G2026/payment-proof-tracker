package com.danielanazodo.proofledger.data.repository

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val auth: Auth,
) {
    val sessionStatus: Flow<SessionStatus> = auth.sessionStatus

    /** Awaits auth initialization (the async storage load on app start)
     *  before checking, so we don't race with [autoLoadFromStorage]. */
    suspend fun currentSession(): UserSession? {
        auth.awaitInitialization()
        return auth.currentSessionOrNull()
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id

    fun currentUserEmail(): String? = auth.currentUserOrNull()?.email

    /** Send a 6-digit OTP to the user's email. createUser=true auto-creates the
     *  Supabase auth.users row on first sign-in. */
    suspend fun sendOtp(email: String) {
        auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
    }

    /** Verify the 6-digit OTP. On success the session is persisted automatically
     *  via [SupabaseSessionManager] and broadcast through [sessionStatus]. */
    suspend fun verifyOtp(email: String, code: String) {
        auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = email.trim(),
            token = code.trim(),
        )
    }

    suspend fun signOut() {
        auth.signOut()
    }
}
