package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

/**
 * A versioned offer-letter schedule for a unit (Phase 13 Cut 2). Installments
 * hang off it. Renegotiation creates a new active version; old ones are kept.
 * "One active per unit" is enforced app-side in [ScheduleRepository] (Room can't
 * express the backend's partial unique index cleanly).
 */
@Entity(
    tableName = "local_schedule_version",
    indices = [
        Index("businessId"),
        Index("unitId"),
    ],
)
data class LocalScheduleVersionEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val unitId: String,
    val versionNo: Int,
    val source: String,
    val isActive: Boolean,
    val offerLetterScreenshotId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
    val remoteUpdatedAt: String? = null,
)
