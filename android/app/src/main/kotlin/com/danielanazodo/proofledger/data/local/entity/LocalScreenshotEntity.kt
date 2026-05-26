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
)
