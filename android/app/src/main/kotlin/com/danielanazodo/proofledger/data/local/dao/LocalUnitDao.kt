package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalUnitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(unit: LocalUnitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(units: List<LocalUnitEntity>)

    @Query("SELECT * FROM local_unit WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalUnitEntity?

    @Query("SELECT * FROM local_unit WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalUnitEntity?

    @Query("DELETE FROM local_unit WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM local_unit
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalUnitEntity>

    @Query(
        """
        SELECT * FROM local_unit
        WHERE businessId = :businessId
          AND deletedAt IS NULL
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalUnitEntity>>

    @Query(
        """
        SELECT * FROM local_unit
        WHERE projectId = :projectId
          AND deletedAt IS NULL
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeByProject(projectId: String): Flow<List<LocalUnitEntity>>

    @Query("DELETE FROM local_unit")
    suspend fun clear()
}
