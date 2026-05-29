package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalCounterpartyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(counterparty: LocalCounterpartyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(counterparties: List<LocalCounterpartyEntity>)

    @Query("SELECT * FROM local_counterparty WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalCounterpartyEntity?

    @Query("SELECT * FROM local_counterparty WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalCounterpartyEntity?

    @Query("DELETE FROM local_counterparty WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM local_counterparty
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalCounterpartyEntity>

    @Query(
        """
        SELECT * FROM local_counterparty
        WHERE businessId = :businessId AND syncStatus = :syncStatus AND deletedAt IS NULL
        ORDER BY createdAt ASC
        """
    )
    suspend fun getByBusinessAndSyncStatus(
        businessId: String,
        syncStatus: String,
    ): List<LocalCounterpartyEntity>

    @Query(
        """
        SELECT * FROM local_counterparty
        WHERE businessId = :businessId AND deletedAt IS NULL
        ORDER BY normalizedName ASC, createdAt ASC, id ASC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalCounterpartyEntity>>

    @Query(
        """
        SELECT * FROM local_counterparty
        WHERE businessId = :businessId
          AND deletedAt IS NULL
          AND (:type IS NULL OR type = :type)
          AND (
            :search IS NULL OR :search = '' OR
            name LIKE '%' || :search || '%' OR
            normalizedName LIKE '%' || :search || '%'
          )
        ORDER BY normalizedName ASC, createdAt ASC, id ASC
        """
    )
    fun observeFiltered(
        businessId: String,
        search: String?,
        type: String?,
    ): Flow<List<LocalCounterpartyEntity>>

    @Query("SELECT * FROM local_counterparty WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<LocalCounterpartyEntity?>

    @Query(
        """
        SELECT * FROM local_counterparty
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED', 'CONFLICT')
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeRowsNeedingAttention(businessId: String): Flow<List<LocalCounterpartyEntity>>

    @Query("DELETE FROM local_counterparty")
    suspend fun clear()
}
