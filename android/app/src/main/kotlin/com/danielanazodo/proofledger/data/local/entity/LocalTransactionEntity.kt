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
    ],
)
data class LocalTransactionEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val amountMinor: Long,
    val currency: String,
    val direction: String,
    val transactionDate: String,
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
)
