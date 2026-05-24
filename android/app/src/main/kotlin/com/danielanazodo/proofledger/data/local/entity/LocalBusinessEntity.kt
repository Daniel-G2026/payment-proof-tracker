package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

@Entity(tableName = "local_business")
data class LocalBusinessEntity(
    /** Client-generated UUID. Same id is used remotely (offline-first / idempotent). */
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val country: String,
    val currency: String,
    val businessType: String?,
    val plan: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
    val lastSyncError: String? = null,
)
