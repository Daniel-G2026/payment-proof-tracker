package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.danielanazodo.proofledger.data.local.SyncStatus

@Entity(tableName = "local_profile")
data class LocalProfileEntity(
    /** Equal to the Supabase auth.users.id (UUID, stored as String). */
    @PrimaryKey val id: String,
    val fullName: String?,
    val phone: String?,
    val email: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus,
    val lastSyncAttemptAt: Long?,
)
