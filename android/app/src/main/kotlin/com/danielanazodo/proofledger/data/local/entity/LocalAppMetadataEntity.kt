package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_app_metadata")
data class LocalAppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
