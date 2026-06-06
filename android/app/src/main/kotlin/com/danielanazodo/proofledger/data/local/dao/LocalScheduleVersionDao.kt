package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalScheduleVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalScheduleVersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: LocalScheduleVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(versions: List<LocalScheduleVersionEntity>)

    @Query("SELECT * FROM local_schedule_version WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalScheduleVersionEntity?

    @Query("SELECT * FROM local_schedule_version WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalScheduleVersionEntity?

    @Query(
        """
        SELECT * FROM local_schedule_version
        WHERE unitId = :unitId AND isActive = 1 AND deletedAt IS NULL
        LIMIT 1
        """
    )
    suspend fun getActiveForUnit(unitId: String): LocalScheduleVersionEntity?

    @Query(
        """
        SELECT * FROM local_schedule_version
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY versionNo ASC, createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalScheduleVersionEntity>

    @Query(
        """
        SELECT * FROM local_schedule_version
        WHERE unitId = :unitId AND deletedAt IS NULL
        ORDER BY versionNo ASC
        """
    )
    fun observeByUnit(unitId: String): Flow<List<LocalScheduleVersionEntity>>

    @Query(
        """
        SELECT * FROM local_schedule_version
        WHERE unitId = :unitId AND isActive = 1 AND deletedAt IS NULL
        LIMIT 1
        """
    )
    fun observeActiveForUnit(unitId: String): Flow<LocalScheduleVersionEntity?>

    @Query("SELECT COALESCE(MAX(versionNo), 0) FROM local_schedule_version WHERE unitId = :unitId")
    suspend fun getMaxVersionNo(unitId: String): Int

    /** App-level "one active per unit": deactivate the current active version
     * before activating a new one (the backend enforces this with a partial
     * unique index; Room can't). */
    @Query("UPDATE local_schedule_version SET isActive = 0 WHERE unitId = :unitId AND isActive = 1")
    suspend fun deactivateActiveForUnit(unitId: String)

    @Query("DELETE FROM local_schedule_version")
    suspend fun clear()
}
