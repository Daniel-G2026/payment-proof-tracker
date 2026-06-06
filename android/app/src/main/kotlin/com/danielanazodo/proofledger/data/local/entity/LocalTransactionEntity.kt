package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

@Entity(
    tableName = "local_transaction",
    indices = [
        Index("businessId"),
        Index("direction"),
        Index("transactionDate"),
        Index("counterpartyId"),
    ],
)
data class LocalTransactionEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val amountMinor: Long,
    val currency: String,
    val direction: String,
    val transactionDate: String,
    val counterpartyId: String?,
    val counterpartyName: String?,
    val category: String?,
    val note: String?,
    val bankName: String?,
    val referenceNumber: String?,
    val source: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
    val remoteUpdatedAt: String? = null,
    // Phase 13 (additive, nullable). Money-in carries unitId; money-out carries
    // projectId. Wired into the create/edit flow in Cut 3 / Cut 5.
    val projectId: String? = null,
    val unitId: String? = null,
    // Phase 13 Cut 3: the only stored allocation intent. null = pure waterfall.
    val earmarkedInstallmentId: String? = null,
)
