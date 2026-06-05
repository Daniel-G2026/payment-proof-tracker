package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

/**
 * A development/estate (Phase 13). Mirrors [LocalCategoryEntity]'s offline-first
 * shape: client-supplied id, soft delete, and the standard sync fields so it
 * rides the existing [SyncWorker] drain.
 */
@Entity(
    tableName = "local_project",
    indices = [Index("businessId")],
)
data class LocalProjectEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
    val remoteUpdatedAt: String? = null,
)
