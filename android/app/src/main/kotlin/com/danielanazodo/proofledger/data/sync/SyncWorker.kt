package com.danielanazodo.proofledger.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CategoryRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.ProjectRepository
import com.danielanazodo.proofledger.data.repository.ScheduleRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.repository.UnitRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background drain of every locally-pending row across businesses,
 * counterparties, and transactions. Runs both on user-triggered enqueue (e.g.
 * app launch) and on the recurring schedule registered by [SyncScheduler].
 *
 * Per-row failures are absorbed inside each repository's syncPending() — they
 * flip the row to FAILED with a lastSyncError. We only return Result.retry()
 * when the worker itself throws, which lets WorkManager apply exponential
 * backoff before the next attempt.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val businessRepository: BusinessRepository,
    private val categoryRepository: CategoryRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val projectRepository: ProjectRepository,
    private val unitRepository: UnitRepository,
    private val scheduleRepository: ScheduleRepository,
    private val transactionRepository: TransactionRepository,
    private val proofRepository: ProofRepository,
    private val syncEventReporter: SyncEventReporter,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        businessRepository.syncPending()
        businessRepository.getLocalBusinesses()
            .filter { it.syncStatus == SyncStatus.SYNCED }
            .forEach { business ->
                // Dependency-ordered drain (Phase 13): a unit references a
                // project and a counterparty; a transaction references a unit.
                // Push parents before children so a child never lands as an
                // orphan — categories/counterparties → projects → units → txns.
                categoryRepository.syncPending(business.id)
                counterpartyRepository.syncPending(business.id)
                projectRepository.syncPending(business.id)
                unitRepository.syncPending(business.id)
                scheduleRepository.syncPending(business.id)
                transactionRepository.syncPending(business.id)
            }
        businessRepository.getSelectedBusinessId()?.let { selected ->
            categoryRepository.refreshFromRemote(selected)
            projectRepository.refreshFromRemote(selected)
            unitRepository.refreshFromRemote(selected)
            transactionRepository.refreshFromRemote(selected)
            // Metadata only — restores "proof attached" badges without pulling
            // image bytes. Byte downloads are user-gated (now vs WiFi), so this
            // never spends metered data behind the user's back. Runs after the
            // transaction refresh so each proof's denormalized fields resolve.
            proofRepository.refreshFromRemote(selected)
        }
        // Flush locally-logged sync attempts to the server-side sync_events
        // table. Runs last (we're online if we got here) and is best-effort —
        // unreported rows stay queued for the next run.
        runCatching { syncEventReporter.flush() }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { t ->
            if (t.isAuthExpiredSyncFailure()) {
                Result.failure()
            } else if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.failure()
            }
        },
    )

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
