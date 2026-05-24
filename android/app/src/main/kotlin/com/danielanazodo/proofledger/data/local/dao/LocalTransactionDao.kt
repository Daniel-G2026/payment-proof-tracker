package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: LocalTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<LocalTransactionEntity>)

    @Query("SELECT * FROM local_transaction WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): LocalTransactionEntity?

    @Query(
        """
        SELECT * FROM local_transaction
        WHERE businessId = :businessId AND syncStatus = :syncStatus AND deletedAt IS NULL
        ORDER BY createdAt ASC
        """
    )
    suspend fun getByBusinessAndSyncStatus(
        businessId: String,
        syncStatus: String,
    ): List<LocalTransactionEntity>

    @Query("SELECT * FROM local_transaction WHERE id = :id LIMIT 1")
    suspend fun getByIdIncludingDeleted(id: String): LocalTransactionEntity?

    @Query(
        """
        SELECT * FROM local_transaction
        WHERE businessId = :businessId AND deletedAt IS NULL
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeByBusiness(businessId: String): Flow<List<LocalTransactionEntity>>

    @Query(
        """
        SELECT * FROM local_transaction
        WHERE businessId = :businessId
          AND deletedAt IS NULL
          AND (:direction IS NULL OR direction = :direction)
          AND (
            :search IS NULL OR :search = '' OR
            counterpartyName LIKE '%' || :search || '%' OR
            category LIKE '%' || :search || '%' OR
            note LIKE '%' || :search || '%' OR
            bankName LIKE '%' || :search || '%' OR
            referenceNumber LIKE '%' || :search || '%'
          )
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeFiltered(
        businessId: String,
        direction: String?,
        search: String?,
    ): Flow<List<LocalTransactionEntity>>

    @Query(
        """
        SELECT * FROM local_transaction
        WHERE businessId = :businessId
          AND counterpartyId = :counterpartyId
          AND deletedAt IS NULL
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeByCounterparty(
        businessId: String,
        counterpartyId: String,
    ): Flow<List<LocalTransactionEntity>>

    @Query("SELECT * FROM local_transaction WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<LocalTransactionEntity?>

    @Query("DELETE FROM local_transaction")
    suspend fun clear()
}
