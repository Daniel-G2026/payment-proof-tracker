package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: LocalCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<LocalCategoryEntity>)

    @Query("SELECT * FROM local_category WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalCategoryEntity?

    @Query("SELECT * FROM local_category WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalCategoryEntity?

    @Query("DELETE FROM local_category WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM local_category
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalCategoryEntity>

    @Query(
        """
        SELECT * FROM local_category
        WHERE businessId = :businessId
          AND deletedAt IS NULL
          AND isArchived = 0
        ORDER BY normalizedName ASC, createdAt ASC, id ASC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalCategoryEntity>>

    @Query("DELETE FROM local_category")
    suspend fun clear()
}
