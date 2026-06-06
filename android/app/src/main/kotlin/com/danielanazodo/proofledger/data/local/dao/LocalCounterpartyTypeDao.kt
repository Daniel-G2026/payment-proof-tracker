package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyTypeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalCounterpartyTypeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(type: LocalCounterpartyTypeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(types: List<LocalCounterpartyTypeEntity>)

    @Query("SELECT * FROM local_counterparty_type WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalCounterpartyTypeEntity?

    @Query("SELECT * FROM local_counterparty_type WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalCounterpartyTypeEntity?

    @Query("DELETE FROM local_counterparty_type WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Non-deleted rows for a business (any sync status). Used to dedupe the
     * one-time import and to know whether the picker still has any types. */
    @Query(
        """
        SELECT * FROM local_counterparty_type
        WHERE businessId = :businessId AND deletedAt IS NULL
        ORDER BY normalizedName ASC, createdAt ASC, id ASC
        """
    )
    suspend fun getActive(businessId: String): List<LocalCounterpartyTypeEntity>

    /** Non-deleted rows matching a normalized type key (usually 0 or 1). Used
     * by removeType, which works off the type string rather than a row id. */
    @Query(
        """
        SELECT * FROM local_counterparty_type
        WHERE businessId = :businessId AND normalizedName = :normalizedName AND deletedAt IS NULL
        """
    )
    suspend fun getActiveByNormalizedName(
        businessId: String,
        normalizedName: String,
    ): List<LocalCounterpartyTypeEntity>

    @Query(
        """
        SELECT * FROM local_counterparty_type
        WHERE businessId = :businessId
          AND syncStatus IN ('PENDING_UPLOAD', 'PENDING_UPDATE', 'PENDING_DELETE', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRowsNeedingSync(businessId: String): List<LocalCounterpartyTypeEntity>

    @Query(
        """
        SELECT * FROM local_counterparty_type
        WHERE businessId = :businessId
          AND deletedAt IS NULL
          AND isArchived = 0
        ORDER BY normalizedName ASC, createdAt ASC, id ASC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalCounterpartyTypeEntity>>

    @Query("DELETE FROM local_counterparty_type")
    suspend fun clear()
}
