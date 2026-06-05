package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

/**
 * A single house/plot within a project (Phase 13). One buyer (a counterparty)
 * and, from Cut 2, one active versioned schedule. Offline-first like
 * [LocalProjectEntity].
 */
@Entity(
    tableName = "local_unit",
    indices = [
        Index("businessId"),
        Index("projectId"),
        Index("buyerCounterpartyId"),
    ],
)
data class LocalUnitEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val projectId: String,
    val label: String,
    val buyerCounterpartyId: String?,
    val totalPriceMinor: Long?,
    val currency: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
    val remoteUpdatedAt: String? = null,
)
