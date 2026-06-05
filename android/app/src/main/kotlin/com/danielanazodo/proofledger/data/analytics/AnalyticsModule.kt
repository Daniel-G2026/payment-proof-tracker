package com.danielanazodo.proofledger.data.analytics

import android.content.Context
import com.danielanazodo.proofledger.BuildConfig
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {

    /**
     * Provides the real PostHog-backed analytics when a key is configured,
     * otherwise a no-op. Initializing PostHog here (once, on first injection)
     * keeps SDK setup out of the Application class and behind the key guard.
     */
    @Provides
    @Singleton
    fun provideAnalytics(@ApplicationContext context: Context): Analytics {
        val key = BuildConfig.POSTHOG_API_KEY
        if (key.isBlank()) return NoOpAnalytics()

        PostHogAndroid.setup(
            context,
            PostHogAndroidConfig(apiKey = key, host = BuildConfig.POSTHOG_HOST),
        )
        return PostHogAnalytics()
    }
}
