package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.ProofBackupStatus

@Entity(
    tableName = "local_screenshot",
    indices = [
        Index("businessId"),
        Index("transactionId"),
        Index("backupStatus"),
        Index("isImportant"),
    ],
)
data class LocalScreenshotEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val transactionId: String,
    val localOriginalPath: String,
    val localUploadPath: String?,
    val originalFileSizeBytes: Long,
    val uploadFileSizeBytes: Long?,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val transactionAmountMinor: Long,
    val transactionDate: String,
    val transactionDirection: String,
    val counterpartyId: String?,
    val counterpartyName: String?,
    val isImportant: Boolean,
    val backupStatus: ProofBackupStatus,
    val remoteScreenshotId: String?,
    val remoteStorageKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastBackupAttemptAt: Long?,
    val lastBackupError: String?,
    /** Raw on-device OCR text captured from the screenshot when the proof was
     *  attached. Phase 7 stores it alongside the proof so future server-side
     *  extraction can compare results without re-OCRing. Null when OCR was
     *  not run (e.g. proof attached before Phase 7, or OCR failed). */
    val ocrText: String? = null,
    /** Phase 8 server-extraction tracking. Latest status the client has seen
     *  for this screenshot's extraction job (queued/processing/ready/failed/
     *  rate_limited). Null when no extraction was attempted (feature off,
     *  proof predates Phase 8, or upload never completed). */
    val serverDraftStatus: String? = null,
    /** Epoch ms of the last successful draft fetch from the server. Used to
     *  decide whether to re-poll on form re-open vs trust the cached state. */
    val serverDraftFetchedAt: Long? = null,
    /** Short user-facing error from a terminal extraction failure (e.g.
     *  unreadable image). Null when status != failed. */
    val serverDraftError: String? = null,
)
