package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

/**
 * Synced picker source for person *types* (customer, supplier, …). Mirrors
 * [LocalCategoryEntity] exactly — the type list now syncs across devices like
 * categories do, instead of living in the on-device metadata table. The
 * `name`/`normalizedName` both hold the snake_case *key* form the app stores on
 * `counterparty.type`; the UI derives a Title-Case label for display.
 */
@Entity(
    tableName = "local_counterparty_type",
    indices = [
        Index("businessId"),
        Index(value = ["businessId", "normalizedName"]),
    ],
)
data class LocalCounterpartyTypeEntity(
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
