package com.danielanazodo.proofledger.di

import com.danielanazodo.proofledger.BuildConfig
import com.danielanazodo.proofledger.data.security.SupabaseSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(sessionManager: SupabaseSessionManager): SupabaseClient {
        val supabaseUrl = requireBuildConfigValue(
            name = "SUPABASE_URL",
            value = BuildConfig.SUPABASE_URL,
        )
        val supabaseAnonKey = requireBuildConfigValue(
            name = "SUPABASE_ANON_KEY",
            value = BuildConfig.SUPABASE_ANON_KEY,
        )

        return createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseAnonKey,
        ) {
            install(Auth) {
                this.sessionManager = sessionManager
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
        }
    }

    @Provides
    @Singleton
    fun provideAuth(client: SupabaseClient): Auth = client.auth

    private fun requireBuildConfigValue(name: String, value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) {
            "$name is blank. Set $name in android/local.properties before creating the Supabase client."
        }
        return trimmed
    }
}
