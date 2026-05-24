package com.danielanazodo.proofledger.data.proof

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException

@HiltWorker
class ProofBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val proofRepository: ProofRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val proofId = inputData.getString(KEY_PROOF_ID) ?: return Result.failure()
        return runCatching {
            proofRepository.upload(proofId)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { t -> if (isTerminal(t)) Result.failure() else maybeRetry() },
        )
    }

    private fun maybeRetry(): Result =
        if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()

    private fun isTerminal(t: Throwable): Boolean {
        val code = (t as? HttpException)?.code() ?: return false
        // Retry only on transient HTTP failures. 4xx outcomes (auth missing,
        // file too big, wrong mime, transaction gone) won't change by trying
        // again, so we let the user see the failure instead of looping.
        if (code in RETRYABLE_HTTP_CODES) return false
        return code in 400..499
    }

    companion object {
        const val KEY_PROOF_ID = "proof_id"
        private const val MAX_ATTEMPTS = 3
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429)
    }
}
