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
    fun provideSupabaseClient(sessionManager: SupabaseSessionManager): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                this.sessionManager = sessionManager
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
        }

    @Provides
    @Singleton
    fun provideAuth(client: SupabaseClient): Auth = client.auth
}
