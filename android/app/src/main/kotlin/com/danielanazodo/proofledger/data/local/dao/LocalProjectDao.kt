package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: LocalProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(projects: List<LocalProjectEntity>)

    @Query("SELECT * FROM local_project WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalProjectEntity?

    @Query("SELECT * FROM local_project WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalProjectEntity?

    @Query("DELETE FROM local_project WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM local_project
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalProjectEntity>

    @Query(
        """
        SELECT * FROM local_project
        WHERE businessId = :businessId
          AND deletedAt IS NULL
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalProjectEntity>>

    @Query("DELETE FROM local_project")
    suspend fun clear()
}
