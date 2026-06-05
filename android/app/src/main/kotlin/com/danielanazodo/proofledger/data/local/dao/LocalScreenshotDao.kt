package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.ProofBackupStatus
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalScreenshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LocalScreenshotEntity)

    @Query("SELECT * FROM local_screenshot WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalScreenshotEntity?

    @Query("SELECT * FROM local_screenshot WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<LocalScreenshotEntity?>

    @Query("SELECT * FROM local_screenshot WHERE backupStatus = 'BACKING_UP'")
    suspend fun findStuckBackingUp(): List<LocalScreenshotEntity>

    @Query("SELECT * FROM local_screenshot WHERE transactionId = :transactionId")
    suspend fun findByTransactionId(transactionId: String): List<LocalScreenshotEntity>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND imageSha256 = :imageSha256
        """
    )
    suspend fun findByImageSha256(
        businessId: String,
        imageSha256: String,
    ): List<LocalScreenshotEntity>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND imageSha256 IS NULL
        """
    )
    suspend fun findMissingImageSha256(businessId: String): List<LocalScreenshotEntity>

    @Query("SELECT * FROM local_screenshot")
    suspend fun findAll(): List<LocalScreenshotEntity>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE transactionId = :transactionId
          AND backupStatus != 'PENDING_REMOTE_DELETE'
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    fun observeLatestForTransaction(transactionId: String): Flow<LocalScreenshotEntity?>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND backupStatus != 'PENDING_REMOTE_DELETE'
        ORDER BY createdAt DESC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalScreenshotEntity>>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND backupStatus IN (
            'PENDING_BACKUP',
            'WAITING_FOR_WIFI',
            'BACKUP_FAILED',
            'PENDING_REMOTE_DELETE'
          )
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeRowsNeedingAttention(businessId: String): Flow<List<LocalScreenshotEntity>>

    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND backupStatus IN (:statuses)
          AND (:importantOnly = 0 OR isImportant = 1)
          AND (:direction IS NULL OR transactionDirection = :direction)
          AND (:counterpartyId IS NULL OR counterpartyId = :counterpartyId)
          AND (:counterpartySearch IS NULL OR counterpartyName LIKE '%' || :counterpartySearch || '%' ESCAPE '\')
          AND (:startDate IS NULL OR transactionDate >= :startDate)
          AND (:endDate IS NULL OR transactionDate <= :endDate)
          AND (:minAmountMinor IS NULL OR transactionAmountMinor >= :minAmountMinor)
          AND (:maxAmountMinor IS NULL OR transactionAmountMinor <= :maxAmountMinor)
        ORDER BY transactionDate DESC, createdAt DESC
        """
    )
    suspend fun pendingForManualBackup(
        businessId: String,
        statuses: List<ProofBackupStatus>,
        importantOnly: Boolean,
        direction: String?,
        counterpartyId: String?,
        counterpartySearch: String?,
        startDate: String?,
        endDate: String?,
        minAmountMinor: Long?,
        maxAmountMinor: Long?,
    ): List<LocalScreenshotEntity>

    /** Restored proofs whose bytes are not on this device yet. Covers every
     *  download-pending state (awaiting a user decision, waiting for WiFi, or
     *  a failed attempt to retry) so a single "download all" pass picks them
     *  all up. */
    @Query(
        """
        SELECT * FROM local_screenshot
        WHERE businessId = :businessId
          AND backupStatus IN (
            'PENDING_DOWNLOAD',
            'WAITING_FOR_WIFI_DOWNLOAD',
            'DOWNLOAD_FAILED'
          )
        ORDER BY createdAt DESC
        """
    )
    suspend fun findNeedingDownload(businessId: String): List<LocalScreenshotEntity>

    /** Count of proofs still awaiting a download *decision* (not those already
     *  queued for WiFi). Drives the one-time "download your proofs?" prompt —
     *  zero on a normal device, non-zero only right after a fresh-device
     *  restore. */
    @Query(
        """
        SELECT COUNT(*) FROM local_screenshot
        WHERE businessId = :businessId
          AND backupStatus = 'PENDING_DOWNLOAD'
        """
    )
    fun observePendingDownloadCount(businessId: String): Flow<Int>

    @Query("DELETE FROM local_screenshot WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_screenshot")
    suspend fun clear()
}
