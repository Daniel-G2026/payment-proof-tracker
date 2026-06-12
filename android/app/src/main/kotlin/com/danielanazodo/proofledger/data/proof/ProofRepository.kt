package com.danielanazodo.proofledger.data.proof

import android.net.Uri
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.dao.LocalAppMetadataDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.escapeLikeTerm
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.ScreenshotDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftConfirmDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
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
    private val transactionDao: LocalTransactionDao,
    private val metadataDao: LocalAppMetadataDao,
    private val fileStore: ProofFileStore,
    private val scheduler: BackupWorkScheduler,
    private val api: ApiService,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeProofForTransaction(transactionId: String): Flow<LocalScreenshotEntity?> =
        screenshotDao.observeLatestForTransaction(transactionId)

    fun observeProofs(businessId: String): Flow<List<LocalScreenshotEntity>> =
        screenshotDao.observeByBusiness(businessId)

    /** Number of proofs awaiting a download *decision* on this device. Drives
     *  the dashboard "you have proof backed up that isn't on this phone" prompt
     *  — zero on a normal device, non-zero only just after a fresh restore. */
    fun observePendingDownloadCount(businessId: String): Flow<Int> =
        screenshotDao.observePendingDownloadCount(businessId)

    /**
     * Proof restore — rebuild local proof *metadata* for a business from the
     * server so a fresh device shows every transaction's "proof attached"
     * badge immediately. Image bytes are intentionally NOT pulled here: rows
     * land as [ProofBackupStatus.PENDING_DOWNLOAD] and the user decides when
     * to download them (now vs on WiFi) — proofs can be large and the user is
     * often on metered mobile data.
     *
     * Idempotent and safe to run on every sync: a proof we already have a
     * local row for is left untouched, so we never overwrite bytes that are
     * already on disk with an empty placeholder. Best-effort — a failed list
     * call just returns and the next sync retries.
     *
     * Must run AFTER transactions are restored: each proof row carries
     * denormalized transaction fields (amount/date/direction/counterparty)
     * that we copy from the already-restored local transaction. A proof whose
     * transaction isn't local yet is skipped and picked up on the next pass.
     */
    suspend fun refreshFromRemote(businessId: String) {
        var offset = 0
        while (true) {
            val page = runCatching {
                api.listScreenshots(businessId, RESTORE_PAGE_SIZE, offset)
            }.getOrNull() ?: return
            page.forEach { restoreMetadata(it) }
            if (page.size < RESTORE_PAGE_SIZE) break
            offset += page.size
        }
    }

    private suspend fun restoreMetadata(dto: ScreenshotDto) {
        // Orphan proofs (no transaction yet) are transient server-side and
        // have no ledger row to hang off — skip. Soft-deleted too.
        val transactionId = dto.transactionId ?: return
        if (dto.deletedAt != null) return
        // Already have it locally? Leave it: its bytes/paths may already be on
        // disk and a placeholder would break the on-device viewer.
        if (screenshotDao.getById(dto.id) != null) return
        val txn = transactionDao.getById(transactionId) ?: return
        val now = System.currentTimeMillis()
        screenshotDao.upsert(
            LocalScreenshotEntity(
                id = dto.id,
                businessId = dto.businessId,
                transactionId = transactionId,
                // Empty until the bytes are downloaded; the viewer treats a
                // non-existent file as "not on this device yet".
                localOriginalPath = "",
                localUploadPath = null,
                originalFileSizeBytes = dto.originalFileSize,
                uploadFileSizeBytes = dto.uploadedFileSize,
                mimeType = dto.mimeType,
                width = dto.width,
                height = dto.height,
                transactionAmountMinor = txn.amountMinor,
                transactionDate = txn.transactionDate,
                transactionDirection = txn.direction,
                counterpartyId = txn.counterpartyId,
                counterpartyName = txn.counterpartyName,
                isImportant = false,
                backupStatus = ProofBackupStatus.PENDING_DOWNLOAD,
                remoteScreenshotId = dto.id,
                remoteStorageKey = dto.storageKey,
                imageSha256 = dto.imageSha256,
                createdAt = now,
                updatedAt = now,
                lastBackupAttemptAt = null,
                lastBackupError = null,
            )
        )
    }

    /**
     * Download every not-yet-downloaded proof for a business right now,
     * regardless of network — the "Download now" choice. Best-effort per row;
     * a single failure doesn't abort the rest. Returns the number that landed
     * on disk successfully.
     */
    suspend fun downloadAllProofsNow(businessId: String): Int {
        var downloaded = 0
        screenshotDao.findNeedingDownload(businessId).forEach { row ->
            runCatching { downloadProof(row.id) }.onSuccess { downloaded += 1 }
        }
        return downloaded
    }

    /**
     * The "Wait for WiFi" choice: flip pending downloads to the WiFi-wait
     * state and enqueue a WiFi-only worker per proof. The existing
     * [ProofBackupWorker] routes each one back through [upload] → [downloadProof]
     * once an unmetered network is available.
     */
    suspend fun scheduleProofDownloadsOnWifi(businessId: String) {
        val now = System.currentTimeMillis()
        screenshotDao.findNeedingDownload(businessId).forEach { row ->
            screenshotDao.upsert(
                row.copy(
                    backupStatus = ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD,
                    updatedAt = now,
                )
            )
            scheduler.enqueue(row.id, wifiOnly = true)
        }
    }

    /**
     * Re-kick Wi-Fi proof downloads that are queued-but-stuck
     * ([ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD], e.g. the worker was lost on
     * reinstall) or previously failed ([ProofBackupStatus.DOWNLOAD_FAILED], e.g. a
     * transient error or a bug that's since been fixed). Called on app open so
     * these self-heal on the next unmetered network without the user re-tapping.
     *
     * Statuses are left as-is: the worker acts on any awaiting-download row and
     * sets the result, and `DOWNLOAD_FAILED` stays visible in the download prompt
     * until it actually succeeds. Enqueue is per-proof unique + REPLACE, so
     * calling this every open never piles up duplicate workers. Proofs still
     * awaiting the user's *first* decision (`PENDING_DOWNLOAD`) are untouched —
     * image bytes are never pulled until the user opts in.
     */
    suspend fun resumeWifiProofDownloads(businessId: String) {
        screenshotDao.findNeedingDownload(businessId)
            .filter {
                it.backupStatus == ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD ||
                    it.backupStatus == ProofBackupStatus.DOWNLOAD_FAILED
            }
            .forEach { scheduler.enqueue(it.id, wifiOnly = true) }
    }

    /**
     * Pull the actual image bytes for one restored proof onto this device.
     * Safe to call repeatedly: only acts on a proof still awaiting download.
     * Throws on failure (so the WorkManager retry/backoff path sees it); the
     * foreground "download all" caller wraps this in runCatching.
     */
    suspend fun downloadProof(proofId: String) {
        val proof = screenshotDao.getById(proofId) ?: return
        if (!proof.isAwaitingDownload()) return
        val remoteId = proof.remoteScreenshotId
        if (remoteId.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        screenshotDao.upsert(
            proof.copy(
                backupStatus = ProofBackupStatus.DOWNLOADING,
                lastBackupAttemptAt = now,
                lastBackupError = null,
                updatedAt = now,
            )
        )
        val result = runCatching {
            val signed = api.getScreenshotSignedUrl(remoteId)
            withContext(Dispatchers.IO) {
                api.downloadProofBytes(signed.url).use { body ->
                    fileStore.writeDownloaded(proofId, body.bytes(), proof.mimeType)
                }
            }
        }
        val latest = screenshotDao.getById(proofId) ?: return
        result.onSuccess { path ->
            screenshotDao.upsert(
                latest.copy(
                    localOriginalPath = path,
                    backupStatus = ProofBackupStatus.BACKED_UP,
                    lastBackupAttemptAt = System.currentTimeMillis(),
                    lastBackupError = null,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            logProof(latest, "restore", "success")
        }.onFailure { t ->
            screenshotDao.upsert(
                latest.copy(
                    backupStatus = ProofBackupStatus.DOWNLOAD_FAILED,
                    lastBackupAttemptAt = System.currentTimeMillis(),
                    lastBackupError = t.toProofBackupMessage(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            logProof(latest, "restore", "failure", t.toProofBackupMessage())
            throw t
        }
    }

    private fun LocalScreenshotEntity.isAwaitingDownload(): Boolean =
        backupStatus == ProofBackupStatus.PENDING_DOWNLOAD ||
            backupStatus == ProofBackupStatus.WAITING_FOR_WIFI_DOWNLOAD ||
            backupStatus == ProofBackupStatus.DOWNLOADING ||
            backupStatus == ProofBackupStatus.DOWNLOAD_FAILED

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
     * Remove a proof row and its on-disk files. Already-uploaded proofs keep a
     * hidden tombstone if remote cleanup fails, so a later worker retry can
     * finish deleting the server copy without showing a broken local proof.
     */
    suspend fun deleteProof(proofId: String) {
        val row = screenshotDao.getById(proofId) ?: return
        withContext(Dispatchers.IO) {
            fileStore.deleteForId(proofId)
        }
        deleteRemoteOrQueue(row)
    }

    /** Cascade-delete every proof attached to a transaction. Called when the
     *  user deletes the transaction so we don't accumulate orphan files. */
    suspend fun deleteForTransaction(transactionId: String) {
        screenshotDao.findByTransactionId(transactionId).forEach { row ->
            withContext(Dispatchers.IO) {
                fileStore.deleteForId(row.id)
            }
            deleteRemoteOrQueue(row)
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
     * Phase 13 Cut 6 — upload an offer-letter image as an orphan screenshot
     * tagged `document_type=offer_letter`, so the backend runs offer-letter
     * extraction. Identical preparation to [uploadOrphan]; only the route +
     * document_type differ. The returned screenshot id is what the schedule
     * draft is polled by, and is linked onto the schedule version on confirm.
     */
    suspend fun uploadOfferLetter(
        uri: Uri,
        businessId: String,
        proofId: String,
    ): OrphanUploadResult {
        // allowPdf=true: offer letters are often PDFs, not photos. A PDF skips
        // the bitmap pipeline (no uploadPath) and uploads with its own mime.
        val prepared = withContext(Dispatchers.IO) {
            fileStore.prepare(uri, proofId, allowPdf = true)
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
            api.uploadOfferLetterScreenshot(
                businessId = businessId,
                id = proofId.toRequestBody(TEXT_MEDIA_TYPE),
                originalFileSize = prepared.originalSizeBytes.toString().toRequestBody(TEXT_MEDIA_TYPE),
                width = (prepared.width ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                height = (prepared.height ?: "").toString().toRequestBody(TEXT_MEDIA_TYPE),
                documentType = "offer_letter".toRequestBody(TEXT_MEDIA_TYPE),
                file = part,
            )
        } catch (t: Throwable) {
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

    suspend fun confirmDraft(
        businessId: String,
        draftId: String,
        transactionId: String,
    ) {
        runCatching {
            api.confirmDraft(draftId, TransactionDraftConfirmDto(transactionId = transactionId))
        }.onSuccess {
            syncEventLogger.log(
                businessId = businessId,
                entityType = "transaction_draft",
                entityId = draftId,
                action = "confirm",
                status = "success",
            )
        }.onFailure { t ->
            syncEventLogger.log(
                businessId = businessId,
                entityType = "transaction_draft",
                entityId = draftId,
                action = "confirm",
                status = "failure",
                errorMessage = t.toProofBackupMessage(),
            )
        }
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
        // A row queued for restore (e.g. WAITING_FOR_WIFI_DOWNLOAD) reaches the
        // same ProofBackupWorker as an upload. Route it to the download path so
        // we reuse the worker's WiFi constraint + retry/backoff for free.
        if (proof.isAwaitingDownload()) {
            downloadProof(proofId)
            return
        }
        if (proof.backupStatus == ProofBackupStatus.PENDING_REMOTE_DELETE) {
            finishPendingRemoteDelete(proof)
            return
        }
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
            logProof(latest, "backup", "success")
        }.onFailure { t ->
            screenshotDao.upsert(
                latest.copy(
                    backupStatus = ProofBackupStatus.BACKUP_FAILED,
                    lastBackupAttemptAt = System.currentTimeMillis(),
                    lastBackupError = t.toProofBackupMessage(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            logProof(latest, "backup", "failure", t.toProofBackupMessage())
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

    private suspend fun deleteRemoteOrQueue(row: LocalScreenshotEntity) {
        if (row.remoteScreenshotId.isNullOrBlank()) {
            screenshotDao.deleteById(row.id)
            return
        }
        runCatching { deleteRemoteScreenshot(row.remoteScreenshotId) }
            .onSuccess {
                screenshotDao.deleteById(row.id)
                logProof(row, "remote_delete", "success")
            }
            .onFailure { t ->
                val now = System.currentTimeMillis()
                screenshotDao.upsert(
                    row.copy(
                        backupStatus = ProofBackupStatus.PENDING_REMOTE_DELETE,
                        lastBackupAttemptAt = now,
                        lastBackupError = t.toProofBackupMessage(),
                        updatedAt = now,
                    )
                )
                scheduler.enqueue(row.id, wifiOnly = false)
                logProof(row, "remote_delete", "failure", t.toProofBackupMessage())
            }
    }

    private suspend fun finishPendingRemoteDelete(row: LocalScreenshotEntity) {
        val remoteId = row.remoteScreenshotId
        if (remoteId.isNullOrBlank()) {
            screenshotDao.deleteById(row.id)
            return
        }
        runCatching { deleteRemoteScreenshot(remoteId) }
            .onSuccess {
                screenshotDao.deleteById(row.id)
                logProof(row, "remote_delete", "success")
            }
            .onFailure { t ->
                screenshotDao.upsert(
                    row.copy(
                        lastBackupAttemptAt = System.currentTimeMillis(),
                        lastBackupError = t.toProofBackupMessage(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                logProof(row, "remote_delete", "failure", t.toProofBackupMessage())
                throw t
            }
    }

    private suspend fun deleteRemoteScreenshot(screenshotId: String) {
        try {
            api.deleteScreenshot(screenshotId)
        } catch (t: HttpException) {
            if (t.code() == 404) return
            throw t
        }
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
            // Escape so a literal % / _ isn't a wildcard (DAO LIKE declares ESCAPE '\').
            counterpartySearch = filter.counterpartySearch?.let { escapeLikeTerm(it) },
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

    private suspend fun logProof(
        row: LocalScreenshotEntity,
        action: String,
        status: String,
        errorMessage: String? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "proof",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = errorMessage,
        )
    }

    companion object {
        private const val BACKUP_PREFERENCE_KEY = "proof_backup_preference"
        private val TEXT_MEDIA_TYPE = "text/plain".toMediaTypeOrNull()

        // Matches the backend's MAX_LIST_LIMIT so restore walks the screenshot
        // list in the largest pages the server allows.
        private const val RESTORE_PAGE_SIZE = 200
    }
}
