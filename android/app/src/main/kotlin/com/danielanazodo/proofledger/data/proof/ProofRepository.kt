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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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

/**
 * Phase 8.1 — return value of [ProofRepository.uploadOrphan]. Carries both
 * the server response and the local file paths we wrote during the upload,
 * so [ProofRepository.recordUploaded] can build a LocalScreenshotEntity on
 * save without re-running file IO or doing brittle filesystem lookups by id.
 */
data class OrphanUploadResult(
    val screenshot: com.danielanazodo.proofledger.data.remote.dto.ScreenshotDto,
    val localOriginalPath: String,
    val localUploadPath: String?,
    val imageSha256: String,
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

    suspend fun attachProof(
        uri: Uri,
        transaction: LocalTransactionEntity,
        ocrText: String? = null,
    ): LocalScreenshotEntity {
        val proofId = UUID.randomUUID().toString()
        val prepared = withContext(Dispatchers.IO) {
            fileStore.prepare(uri, proofId)
        }
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
            imageSha256 = prepared.imageSha256,
            createdAt = now,
            updatedAt = now,
            lastBackupAttemptAt = null,
            lastBackupError = null,
            ocrText = ocrText?.takeIf { it.isNotBlank() },
        )
        try {
            screenshotDao.upsert(row)
        } catch (t: Throwable) {
            // Don't leave the gallery copy + compressed upload sitting in
            // internal storage if we never managed to record them.
            withContext(Dispatchers.IO) {
                fileStore.deleteForId(proofId)
            }
            throw t
        }
        if (preference == ProofBackupPreference.WIFI_ONLY) {
            scheduler.enqueue(proofId, wifiOnly = true)
        }
        return row
    }

    suspend fun computeImageSha256(uri: Uri): String =
        withContext(Dispatchers.IO) {
            fileStore.sha256(uri)
        }

    suspend fun backfillMissingImageHashes(businessId: String) {
        screenshotDao.findMissingImageSha256(businessId).forEach { row ->
            val hash = runCatching {
                withContext(Dispatchers.IO) {
                    fileStore.sha256(Uri.fromFile(File(row.localOriginalPath)))
                }
            }.getOrNull()
            if (!hash.isNullOrBlank()) {
                screenshotDao.upsert(
                    row.copy(
                        imageSha256 = hash,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /**
     * Remove a proof row and its on-disk files. Safe to call for rows in any
     * backup state — the local files always go, the DAO row always goes.
     * Backend cleanup of an already-uploaded screenshot is a TODO; today the
     * remote copy is left in place.
     */
    suspend fun deleteProof(proofId: String) {
        screenshotDao.getById(proofId) ?: return
        withContext(Dispatchers.IO) {
            fileStore.deleteForId(proofId)
        }
        screenshotDao.deleteById(proofId)
    }

    /** Cascade-delete every proof attached to a transaction. Called when the
     *  user deletes the transaction so we don't accumulate orphan files. */
    suspend fun deleteForTransaction(transactionId: String) {
        screenshotDao.findByTransactionId(transactionId).forEach { row ->
            withContext(Dispatchers.IO) {
                fileStore.deleteForId(row.id)
            }
            screenshotDao.deleteById(row.id)
        }
    }

    /** Wipe every local proof — files and DAO rows. Called on sign-out so the
     *  next user (or the same user signing back in) starts clean. */
    suspend fun clearAllLocal() {
        screenshotDao.findAll().forEach { row ->
            withContext(Dispatchers.IO) {
                fileStore.deleteForId(row.id)
            }
        }
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

    /**
     * Phase 8.1 — upload a proof to the server *before* a transaction exists.
     * The server creates an orphan screenshot row (transaction_id = NULL),
     * enqueues extraction, and returns the DTO. The caller (form ViewModel)
     * holds onto the returned screenshot id and sends it back on save so the
     * transaction-create endpoint can link the orphan atomically.
     *
     * Local DAO is intentionally NOT touched here. The form session is the
     * only place the orphan id lives until save() either:
     *   - links it (via TransactionCreateDto.screenshotId), then we write the
     *     LocalScreenshotEntity via [recordUploaded], OR
     *   - drops it (user bailed; server cron cleans up after 7 days).
     *
     * @param uri local content:// URI of the picked / shared screenshot
     * @param businessId the business this proof belongs to
     * @param proofId client-generated UUID — used as both the local file id
     *  (via ProofFileStore) AND the remote screenshot id, so they're aligned
     *  for any future [recordUploaded] call
     */
    suspend fun uploadOrphan(
        uri: Uri,
        businessId: String,
        proofId: String,
    ): OrphanUploadResult {
        // Strip EXIF / re-encode using the same pipeline attachProof uses, so
        // orphan uploads and transaction-linked uploads send identical bytes.
        val prepared = withContext(Dispatchers.IO) {
            fileStore.prepare(uri, proofId)
        }
        val file = File(prepared.uploadPath ?: prepared.originalPath)
        val uploadMimeType = if (prepared.uploadPath != null) {
            "image/jpeg"
        } else {
            prepared.mimeType.ifBlank { "image/jpeg" }
        }
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = file.name,
            body = file.asRequestBody(uploadMimeType.toMediaTypeOrNull()),
        )
        val dto = try {
            api.uploadOrphanScreenshot(
                businessId = businessId,
                id = proofId.toRequestBody(TEXT_MEDIA_TYPE),
                originalFileSize = prepared.originalSizeBytes.toString().toRequestBody(TEXT_MEDIA_TYPE),
                width = (prepared.width ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                height = (prepared.height ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                file = part,
            )
        } catch (t: Throwable) {
            // Clean up the local copies so a failed orphan upload doesn't
            // leave megabytes on disk every time the user attaches offline.
            withContext(Dispatchers.IO) {
                fileStore.deleteForId(proofId)
            }
            throw t
        }
        return OrphanUploadResult(
            screenshot = dto,
            localOriginalPath = prepared.originalPath,
            localUploadPath = prepared.uploadPath,
            imageSha256 = prepared.imageSha256,
        )
    }

    /**
     * Phase 8.1 — best-effort delete of an orphan screenshot from the server.
     * Used when the user replaces or removes the proof mid-form. Swallows
     * failures: a leaked orphan will be cleaned up by the 7-day cron, so
     * client-side errors here aren't worth surfacing.
     */
    suspend fun deleteOrphan(screenshotId: String) {
        runCatching { api.deleteScreenshot(screenshotId) }
    }

    /**
     * Phase 8.1 — write the LocalScreenshotEntity for an orphan that the
     * server has already received and linked to a saved transaction. No HTTP
     * call, no WorkManager — the bytes are already on the server (and on
     * disk under the same proofId).
     *
     * Called from TransactionFormViewModel.save() in the orphan branch,
     * after the create-transaction request succeeded with the orphan
     * `screenshot_id`.
     */
    suspend fun recordUploaded(
        transaction: LocalTransactionEntity,
        upload: OrphanUploadResult,
        ocrText: String? = null,
    ): LocalScreenshotEntity {
        val now = System.currentTimeMillis()
        val screenshot = upload.screenshot
        val row = LocalScreenshotEntity(
            id = screenshot.id,
            businessId = transaction.businessId,
            transactionId = transaction.id,
            localOriginalPath = upload.localOriginalPath,
            localUploadPath = upload.localUploadPath,
            originalFileSizeBytes = screenshot.originalFileSize,
            uploadFileSizeBytes = screenshot.uploadedFileSize,
            mimeType = screenshot.mimeType,
            width = screenshot.width,
            height = screenshot.height,
            transactionAmountMinor = transaction.amountMinor,
            transactionDate = transaction.transactionDate,
            transactionDirection = transaction.direction,
            counterpartyId = transaction.counterpartyId,
            counterpartyName = transaction.counterpartyName,
            isImportant = false,
            backupStatus = ProofBackupStatus.BACKED_UP,
            remoteScreenshotId = screenshot.id,
            remoteStorageKey = screenshot.storageKey,
            imageSha256 = upload.imageSha256.ifBlank { screenshot.imageSha256 },
            createdAt = now,
            updatedAt = now,
            lastBackupAttemptAt = now,
            lastBackupError = null,
            ocrText = ocrText?.takeIf { it.isNotBlank() },
        )
        screenshotDao.upsert(row)
        return row
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
            imageSha256 = remote.imageSha256 ?: imageSha256,
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
