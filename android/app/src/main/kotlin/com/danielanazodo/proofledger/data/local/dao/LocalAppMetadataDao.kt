package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalAppMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAppMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LocalAppMetadataEntity)

    @Query("SELECT * FROM local_app_metadata WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): LocalAppMetadataEntity?

    @Query("SELECT * FROM local_app_metadata WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<LocalAppMetadataEntity?>
}
