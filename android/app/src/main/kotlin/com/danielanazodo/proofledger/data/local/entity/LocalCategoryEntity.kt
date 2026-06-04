package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

@Entity(
    tableName = "local_category",
    indices = [
        Index("businessId"),
        Index(value = ["businessId", "normalizedName"]),
    ],
)
data class LocalCategoryEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val normalizedName: String,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
    val remoteUpdatedAt: String? = null,
)
