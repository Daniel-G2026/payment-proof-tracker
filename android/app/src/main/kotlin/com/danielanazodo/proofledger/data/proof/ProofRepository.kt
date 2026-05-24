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

data class ProofBackupSummary(
    val pendingCount: Int,
    val estimatedBytes: Long,
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
        queueProof(proofId, wifiOnly = false)
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
            val part = MultipartBody.Part.createFormData(
                name = "file",
                filename = file.name,
                body = file.asRequestBody((proof.mimeType.ifBlank { "image/jpeg" }).toMediaTypeOrNull()),
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
                    lastBackupError = t.message ?: "Could not back up proof.",
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

    companion object {
        private const val BACKUP_PREFERENCE_KEY = "proof_backup_preference"
        private val TEXT_MEDIA_TYPE = "text/plain".toMediaTypeOrNull()
    }
}
