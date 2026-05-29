package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalSyncEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSyncEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: LocalSyncEventEntity)

    @Query("SELECT * FROM local_sync_event WHERE businessId = :businessId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(businessId: String, limit: Int = 100): Flow<List<LocalSyncEventEntity>>

    @Query("SELECT * FROM local_sync_event WHERE status = 'failure' ORDER BY createdAt DESC")
    fun observeFailures(): Flow<List<LocalSyncEventEntity>>

    @Query("DELETE FROM local_sync_event")
    suspend fun clear()
}
