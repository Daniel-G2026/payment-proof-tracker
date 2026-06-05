package com.danielanazodo.proofledger

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.danielanazodo.proofledger.data.analytics.Analytics
import com.danielanazodo.proofledger.data.analytics.AnalyticsEvents
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PaymentProofApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var proofRepository: ProofRepository
    @Inject lateinit var analytics: Analytics

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initCrashReporting()
        // Idempotent — WorkManager keeps the existing schedule across launches.
        syncScheduler.ensureScheduled()
        // Any row stuck mid-upload from a previous process kill — reset + re-enqueue.
        appScope.launch { proofRepository.recoverStuckBackups() }
        analytics.track(AnalyticsEvents.APP_OPENED)
    }

    /** Manual Sentry init, guarded by a DSN so debug / CI builds stay no-op. */
    private fun initCrashReporting() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        }
    }
}
