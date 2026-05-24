package com.danielanazodo.proofledger.data.proof

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupWorkScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(proofId: String, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ProofBackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ProofBackupWorker.KEY_PROOF_ID to proofId))
            .build()
        workManager.enqueueUniqueWork("proof-backup-$proofId", ExistingWorkPolicy.REPLACE, request)
    }
}
