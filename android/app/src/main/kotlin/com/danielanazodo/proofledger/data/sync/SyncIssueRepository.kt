package com.danielanazodo.proofledger.data.sync

import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class SyncIssue(
    val entityType: SyncIssueEntityType,
    val entityId: String,
    val businessId: String,
    val title: String,
    val statusLabel: String,
    val detail: String?,
    val canRetrySync: Boolean,
    val canRetryProof: Boolean,
    val openTarget: SyncIssueOpenTarget?,
    val updatedAt: Long,
)

enum class SyncIssueEntityType {
    RECORD,
    PERSON,
    PROOF,
}

data class SyncIssueOpenTarget(
    val type: SyncIssueOpenTargetType,
    val id: String,
)

enum class SyncIssueOpenTargetType {
    RECORD,
    PERSON,
}

@Singleton
class SyncIssueRepository @Inject constructor(
    private val transactionDao: LocalTransactionDao,
    private val counterpartyDao: LocalCounterpartyDao,
    private val screenshotDao: LocalScreenshotDao,
    private val businessRepository: BusinessRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val transactionRepository: TransactionRepository,
    private val proofRepository: ProofRepository,
) {
    fun observeIssueCount(businessId: String): Flow<Int> =
        observeIssues(businessId).map { it.size }

    fun observeIssues(businessId: String): Flow<List<SyncIssue>> =
        combine(
            transactionDao.observeRowsNeedingAttention(businessId),
            counterpartyDao.observeRowsNeedingAttention(businessId),
            screenshotDao.observeRowsNeedingAttention(businessId),
        ) { records, people, proofs ->
            buildList {
                addAll(records.map { it.toSyncIssue() })
                addAll(people.map { it.toSyncIssue() })
                addAll(proofs.map { it.toSyncIssue() })
            }.sortedWith(
                compareByDescending<SyncIssue> { it.statusLabel == "Sync issue" }
                    .thenByDescending { it.updatedAt }
            )
        }

    suspend fun retrySync(businessId: String) {
        businessRepository.syncPending(includeAuthExpiredFailures = true)
        counterpartyRepository.syncPending(businessId, includeAuthExpiredFailures = true)
        transactionRepository.syncPending(businessId, includeAuthExpiredFailures = true)
    }

    suspend fun retryProof(proofId: String) {
        proofRepository.backupNow(proofId)
    }

    private fun LocalTransactionEntity.toSyncIssue(): SyncIssue =
        SyncIssue(
            entityType = SyncIssueEntityType.RECORD,
            entityId = id,
            businessId = businessId,
            title = counterpartyName?.takeIf { it.isNotBlank() } ?: "Record",
            statusLabel = syncStatus.toIssueStatusLabel(),
            detail = lastSyncError,
            canRetrySync = syncStatus == SyncStatus.FAILED,
            canRetryProof = false,
            openTarget = if (deletedAt == null) {
                SyncIssueOpenTarget(SyncIssueOpenTargetType.RECORD, id)
            } else {
                null
            },
            updatedAt = updatedAt,
        )

    private fun LocalCounterpartyEntity.toSyncIssue(): SyncIssue =
        SyncIssue(
            entityType = SyncIssueEntityType.PERSON,
            entityId = id,
            businessId = businessId,
            title = name,
            statusLabel = syncStatus.toIssueStatusLabel(),
            detail = lastSyncError,
            canRetrySync = syncStatus == SyncStatus.FAILED,
            canRetryProof = false,
            openTarget = if (deletedAt == null) {
                SyncIssueOpenTarget(SyncIssueOpenTargetType.PERSON, id)
            } else {
                null
            },
            updatedAt = updatedAt,
        )

    private fun LocalScreenshotEntity.toSyncIssue(): SyncIssue =
        SyncIssue(
            entityType = SyncIssueEntityType.PROOF,
            entityId = id,
            businessId = businessId,
            title = counterpartyName?.takeIf { it.isNotBlank() } ?: "Proof",
            statusLabel = backupStatus.toIssueStatusLabel(),
            detail = lastBackupError,
            canRetrySync = false,
            canRetryProof = backupStatus in setOf(
                ProofBackupStatus.BACKUP_FAILED,
                ProofBackupStatus.PENDING_REMOTE_DELETE,
            ),
            openTarget = SyncIssueOpenTarget(SyncIssueOpenTargetType.RECORD, transactionId),
            updatedAt = updatedAt,
        )

    private fun SyncStatus.toIssueStatusLabel(): String =
        when (this) {
            SyncStatus.FAILED -> "Sync issue"
            SyncStatus.CONFLICT -> "Sync issue"
            SyncStatus.PENDING_DELETE -> "Waiting to sync"
            SyncStatus.PENDING_UPLOAD,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.SYNCING -> "Waiting to sync"
            SyncStatus.LOCAL_ONLY,
            SyncStatus.SYNCED -> "Synced"
        }

    private fun ProofBackupStatus.toIssueStatusLabel(): String =
        when (this) {
            ProofBackupStatus.BACKUP_FAILED -> "Sync issue"
            ProofBackupStatus.PENDING_REMOTE_DELETE -> "Waiting to sync"
            ProofBackupStatus.PENDING_BACKUP,
            ProofBackupStatus.WAITING_FOR_WIFI -> "Waiting to sync"
            ProofBackupStatus.SAVED_ON_PHONE_ONLY,
            ProofBackupStatus.BACKING_UP,
            ProofBackupStatus.BACKED_UP -> "Synced"
            // Restore states don't flow into the issues list (the attention
            // query excludes them), but the label must stay exhaustive.
            ProofBackupStatus.DOWNLOAD_FAILED -> "Sync issue"
            ProofBackupStatus.PENDING_DOWNLOAD,
            ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD,
            ProofBackupStatus.DOWNLOADING -> "Waiting to sync"
        }
}
