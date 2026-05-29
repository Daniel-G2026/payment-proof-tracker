package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_sync_event",
    indices = [
        Index("businessId"),
        Index("entityType"),
        Index("entityId"),
        Index("action"),
        Index("status"),
        Index("createdAt"),
    ],
)
data class LocalSyncEventEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val entityType: String,
    val entityId: String?,
    val action: String,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long,
)
