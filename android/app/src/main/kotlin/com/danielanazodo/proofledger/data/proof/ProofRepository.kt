package com.danielanazodo.proofledger.data.proof

import android.net.Uri
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ScreenshotDto
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

data class ProofBackupSummary(
    val pendingCount: Int,
    val estimatedBytes: Long,
)

data class ManualBackupResult(
    val selectedCount: Int,
    val backedUpCount: Int,
    val failedCount: Int,
)

data class ManualBackupFilter(
    val importantOnly: Boolean = false,
    val direction: String? = null,
    val counterpartyId: String? = null,
    val counterpartySearch: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
)

@Singleton
class ProofRepository @Inject constructor(
    private val screenshotDao: LocalScreenshotDao,
    private val metadataDao: LocalAppMetadataDao,
    private val fileStore: ProofFileStore,
    private val scheduler: BackupWorkScheduler,
    private val api: ApiService,
) {
    fun observeProofForTransaction(transactionId: String): Flow<LocalScreenshotEntity?> =
        screenshotDao.observeLatestForTransaction(transactionId)

    fun observeProofs(businessId: String): Flow<List<LocalScreenshotEntity>> =
        screenshotDao.observeByBusiness(businessId)

    suspend fun attachProof(uri: Uri, transaction: LocalTransactionEntity): LocalScreenshotEntity {
        val proofId = UUID.randomUUID().toString()
        val prepared = fileStore.prepare(uri, proofId)
        val preference = getBackupPreference()
        val status = when (preference) {
            ProofBackupPreference.WIFI_ONLY -> ProofBackupStatus.WAITING_FOR_WIFI
            ProofBackupPreference.MANUAL,
            ProofBackupPreference.IMPORTANT_ONLY -> ProofBackupStatus.SAVED_ON_PHONE_ONLY
        }
        val now = System.currentTimeMillis()
        val row = LocalScreenshotEntity(
            id = proofId,
            businessId = transaction.businessId,
            transactionId = transaction.id,
            localOriginalPath = prepared.originalPath,
            localUploadPath = prepared.uploadPath,
            originalFileSizeBytes = prepared.originalSizeBytes,
            uploadFileSizeBytes = prepared.uploadSizeBytes,
            mimeType = prepared.mimeType,
            width = prepared.width,
            height = prepared.height,
            transactionAmountMinor = transaction.amountMinor,
            transactionDate = transaction.transactionDate,
            transactionDirection = transaction.direction,
            counterpartyId = transaction.counterpartyId,
            counterpartyName = transaction.counterpartyName,
            isImportant = false,
            backupStatus = status,
            remoteScreenshotId = null,
            remoteStorageKey = null,
            createdAt = now,
            updatedAt = now,
            lastBackupAttemptAt = null,
            lastBackupError = null,
        )
        try {
            screenshotDao.upsert(row)
        } catch (t: Throwable) {
            // Don't leave the gallery copy + compressed upload sitting in
            // internal storage if we never managed to record them.
            fileStore.deleteForId(proofId)
            throw t
        }
        if (preference == ProofBackupPreference.WIFI_ONLY) {
            scheduler.enqueue(proofId, wifiOnly = true)
        }
        return row
    }

    /**
     * Remove a proof row and its on-disk files. Safe to call for rows in any
     * backup state — the local files always go, the DAO row always goes.
     * Backend cleanup of an already-uploaded screenshot is a TODO; today the
     * remote copy is left in place.
     */
    suspend fun deleteProof(proofId: String) {
        screenshotDao.getById(proofId) ?: return
        fileStore.deleteForId(proofId)
        screenshotDao.deleteById(proofId)
    }

    /** Cascade-delete every proof attached to a transaction. Called when the
     *  user deletes the transaction so we don't accumulate orphan files. */
    suspend fun deleteForTransaction(transactionId: String) {
        screenshotDao.findByTransactionId(transactionId).forEach { row ->
            fileStore.deleteForId(row.id)
            screenshotDao.deleteById(row.id)
        }
    }

    /** Wipe every local proof — files and DAO rows. Called on sign-out so the
     *  next user (or the same user signing back in) starts clean. */
    suspend fun clearAllLocal() {
        screenshotDao.findAll().forEach { row -> fileStore.deleteForId(row.id) }
        screenshotDao.clear()
    }

    /**
     * Reset rows stuck in BACKING_UP (worker killed mid-upload by OOM, system
     * force-stop, or battery optimization) back to PENDING_BACKUP and re-enqueue
     * them. Without this, a single bad launch would leave the proof showing a
     * permanent "backing up..." spinner with no way to retry.
     *
     * Idempotent and cheap when there are no stuck rows. Safe to call on every
     * app start before WorkManager starts dispatching.
     */
    suspend fun recoverStuckBackups() {
        val stuck = screenshotDao.findStuckBackingUp()
        if (stuck.isEmpty()) return
        val now = System.currentTimeMillis()
        stuck.forEach { row ->
            screenshotDao.upsert(
                row.copy(
                    backupStatus = ProofBackupStatus.PENDING_BACKUP,
                    lastBackupError = "Recovered from interrupted upload.",
                    updatedAt = now,
                )
            )
            scheduler.enqueue(row.id, wifiOnly = false)
        }
    }

    suspend fun setBackupPreference(preference: ProofBackupPreference) {
        metadataDao.upsert(
            LocalAppMetadataEntity(
                key = BACKUP_PREFERENCE_KEY,
                value = preference.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun getBackupPreference(): ProofBackupPreference {
        val raw = metadataDao.get(BACKUP_PREFERENCE_KEY)?.value ?: return ProofBackupPreference.WIFI_ONLY
        return runCatching { ProofBackupPreference.valueOf(raw) }
            .getOrDefault(ProofBackupPreference.WIFI_ONLY)
    }

    suspend fun setImportant(proofId: String, important: Boolean) {
        val proof = screenshotDao.getById(proofId) ?: return
        val updated = proof.copy(
            isImportant = important,
            updatedAt = System.currentTimeMillis(),
        )
        screenshotDao.upsert(updated)
        if (important && getBackupPreference() == ProofBackupPreference.IMPORTANT_ONLY) {
            queueProof(proofId, wifiOnly = true)
        }
    }

    suspend fun backupNow(proofId: String) {
        upload(proofId)
    }

    suspend fun manualBackupSummary(businessId: String, filter: ManualBackupFilter): ProofBackupSummary {
        val rows = pendingRows(businessId, filter)
        return ProofBackupSummary(
            pendingCount = rows.size,
            estimatedBytes = rows.sumOf { it.uploadFileSizeBytes ?: it.originalFileSizeBytes },
        )
    }

    suspend fun queueManualBackup(businessId: String, filter: ManualBackupFilter): Int {
        val rows = pendingRows(businessId, filter)
        rows.forEach { queueProof(it.id, wifiOnly = false) }
        return rows.size
    }

    suspend fun backUpManualNow(businessId: String, filter: ManualBackupFilter): ManualBackupResult {
        val rows = pendingRows(businessId, filter)
        var backedUp = 0
        var failed = 0
        rows.forEach { proof ->
            runCatching { upload(proof.id) }
                .onSuccess { backedUp += 1 }
                .onFailure { failed += 1 }
        }
        return ManualBackupResult(
            selectedCount = rows.size,
            backedUpCount = backedUp,
            failedCount = failed,
        )
    }

    suspend fun upload(proofId: String) {
        val proof = screenshotDao.getById(proofId) ?: return
        if (proof.backupStatus == ProofBackupStatus.BACKED_UP) return
        val now = System.currentTimeMillis()
        screenshotDao.upsert(
            proof.copy(
                backupStatus = ProofBackupStatus.BACKING_UP,
                lastBackupAttemptAt = now,
                lastBackupError = null,
                updatedAt = now,
            )
        )
        val result = runCatching {
            val file = File(proof.localUploadPath ?: proof.localOriginalPath)
            val uploadMimeType = if (proof.localUploadPath != null) {
                "image/jpeg"
            } else {
                proof.mimeType.ifBlank { "image/jpeg" }
            }
            val part = MultipartBody.Part.createFormData(
                name = "file",
                filename = file.name,
                body = file.asRequestBody(uploadMimeType.toMediaTypeOrNull()),
            )
            api.uploadScreenshot(
                businessId = proof.businessId,
                id = proof.id.toRequestBody(TEXT_MEDIA_TYPE),
                transactionId = proof.transactionId.toRequestBody(TEXT_MEDIA_TYPE),
                originalFileSize = proof.originalFileSizeBytes.toString().toRequestBody(TEXT_MEDIA_TYPE),
                width = (proof.width ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                height = (proof.height ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                file = part,
            )
        }
        val latest = screenshotDao.getById(proofId) ?: return
        result.onSuccess { remote ->
            screenshotDao.upsert(latest.toBackedUp(remote))
        }.onFailure { t ->
            screenshotDao.upsert(
                latest.copy(
                    backupStatus = ProofBackupStatus.BACKUP_FAILED,
                    lastBackupAttemptAt = System.currentTimeMillis(),
                    lastBackupError = t.toProofBackupMessage(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            throw t
        }
    }

    private suspend fun queueProof(proofId: String, wifiOnly: Boolean) {
        val proof = screenshotDao.getById(proofId) ?: return
        val status = if (wifiOnly) ProofBackupStatus.WAITING_FOR_WIFI else ProofBackupStatus.PENDING_BACKUP
        screenshotDao.upsert(
            proof.copy(
                backupStatus = status,
                lastBackupError = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
        scheduler.enqueue(proofId, wifiOnly)
    }

    private suspend fun pendingRows(
        businessId: String,
        filter: ManualBackupFilter,
    ): List<LocalScreenshotEntity> =
        screenshotDao.pendingForManualBackup(
            businessId = businessId,
            statuses = listOf(
                ProofBackupStatus.SAVED_ON_PHONE_ONLY,
                ProofBackupStatus.PENDING_BACKUP,
                ProofBackupStatus.WAITING_FOR_WIFI,
                ProofBackupStatus.BACKUP_FAILED,
            ),
            importantOnly = filter.importantOnly,
            direction = filter.direction,
            counterpartyId = filter.counterpartyId,
            counterpartySearch = filter.counterpartySearch,
            startDate = filter.startDate,
            endDate = filter.endDate,
            minAmountMinor = filter.minAmountMinor,
            maxAmountMinor = filter.maxAmountMinor,
        )

    private fun LocalScreenshotEntity.toBackedUp(remote: ScreenshotDto): LocalScreenshotEntity =
        copy(
            backupStatus = ProofBackupStatus.BACKED_UP,
            remoteScreenshotId = remote.id,
            remoteStorageKey = remote.storageKey,
            uploadFileSizeBytes = remote.uploadedFileSize,
            lastBackupAttemptAt = System.currentTimeMillis(),
            lastBackupError = null,
            updatedAt = System.currentTimeMillis(),
        )

    private fun Throwable.toProofBackupMessage(): String {
        val status = (this as? HttpException)?.code()
        return when (status) {
            401, 403 -> "Sign in again before backing up proof."
            404 -> "The transaction is not online yet. Try again after it syncs."
            413 -> "Proof image is larger than the 5 MB backup limit."
            415 -> "Proof must be a JPEG, PNG, or WebP image."
            in 500..599 -> "Server problem while backing up proof. Try again later."
            null -> message ?: "Could not back up proof."
            else -> "Could not back up proof. HTTP $status."
        }
    }

    companion object {
        private const val BACKUP_PREFERENCE_KEY = "proof_backup_preference"
        private val TEXT_MEDIA_TYPE = "text/plain".toMediaTypeOrNull()
    }
}
